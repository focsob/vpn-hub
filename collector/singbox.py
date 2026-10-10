"""Validate and test nodes with a real sing-box core."""

from __future__ import annotations

import json
import os
import re
import socket
import subprocess
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor

SING_BOX = os.environ.get("SING_BOX", "sing-box")
TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
_ERR_INDEX = re.compile(r"(outbound|endpoint)s?\[(\d+)\]")


def _base_config() -> dict:
    return {
        "log": {"level": "error"},
        "dns": {"servers": [{"type": "local", "tag": "local"}]},
        "route": {"default_domain_resolver": "local", "final": "direct", "rules": []},
    }


def _build(nodes: list[dict], base_port: int | None = None) -> tuple[dict, list[int], list[int]]:
    """Build a config; returns (config, outbound_index->node, endpoint_index->node)."""
    cfg = _base_config()
    outbounds, endpoints, inbounds = [], [], []
    ob_map, ep_map = [], []
    for i, n in enumerate(nodes):
        item = dict(n["config"], tag=f"n{i}")
        if n["kind"] == "endpoint":
            ep_map.append(i)
            endpoints.append(item)
        else:
            ob_map.append(i)
            outbounds.append(item)
        if base_port is not None:
            inbounds.append({"type": "mixed", "tag": f"in{i}", "listen": "127.0.0.1", "listen_port": base_port + i})
            cfg["route"]["rules"].append({"inbound": [f"in{i}"], "outbound": f"n{i}"})
    outbounds.append({"type": "direct", "tag": "direct"})
    cfg["outbounds"] = outbounds
    if endpoints:
        cfg["endpoints"] = endpoints
    if inbounds:
        cfg["inbounds"] = inbounds
    return cfg, ob_map, ep_map


def _check(nodes: list[dict]) -> tuple[bool, str]:
    cfg, _, _ = _build(nodes)
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        json.dump(cfg, f)
        path = f.name
    try:
        r = subprocess.run([SING_BOX, "check", "-c", path], capture_output=True, text=True, timeout=120)
        return r.returncode == 0, r.stderr + r.stdout
    finally:
        os.unlink(path)


def validate(nodes: list[dict], batch: int = 400) -> list[dict]:
    """Drop every node that sing-box refuses to load."""
    good: list[dict] = []
    for start in range(0, len(nodes), batch):
        pending = nodes[start:start + batch]
        stack = [pending]
        while stack:
            group = stack.pop()
            if not group:
                continue
            ok, err = _check(group)
            if ok:
                good.extend(group)
                continue
            if len(group) == 1:
                continue
            m = _ERR_INDEX.search(err)
            if m:
                _, ob_map, ep_map = _build(group)
                idx = int(m.group(2))
                table = ob_map if m.group(1) == "outbound" else ep_map
                if idx < len(table):
                    bad = table[idx]
                    stack.append(group[:bad] + group[bad + 1:])
                    continue
            mid = len(group) // 2
            stack.extend([group[:mid], group[mid:]])
    return good


def _wait_port(port: int, timeout: float = 15) -> bool:
    end = time.time() + timeout
    while time.time() < end:
        with socket.socket() as s:
            s.settimeout(0.3)
            if s.connect_ex(("127.0.0.1", port)) == 0:
                return True
        time.sleep(0.3)
    return False


def _ports_free(base: int, count: int) -> bool:
    for port in range(base, base + count):
        with socket.socket() as s:
            try:
                s.bind(("127.0.0.1", port))
            except OSError:
                return False
    return True


def _free_base(preferred: int, count: int) -> int:
    """Find `count` consecutive free local ports (runners sometimes have random ports taken)."""
    base = preferred
    while base + count < 65000:
        if _ports_free(base, count):
            return base
        base += count + 7
    raise RuntimeError("no free port range")


def _curl(port: int, timeout: int) -> dict | None:
    try:
        r = subprocess.run(
            ["curl", "-sS", "--max-time", str(timeout), "-x", f"socks5h://127.0.0.1:{port}",
             "-o", "-", "-w", "\n__TIME=%{time_total}", TRACE_URL],
            capture_output=True, text=True, timeout=timeout + 5)
    except subprocess.TimeoutExpired:
        return None
    if r.returncode != 0 or "loc=" not in r.stdout:
        return None
    info = dict(line.split("=", 1) for line in r.stdout.splitlines() if "=" in line)
    try:
        latency = int(float(info.get("__TIME", "0")) * 1000)
    except ValueError:
        latency = 0
    return {"country": info.get("loc", "ZZ").upper(), "exit_ip": info.get("ip", ""), "latency": latency}


def test(nodes: list[dict], batch: int = 400, concurrency: int = 128, timeout: int = 10,
         base_port: int = 20000, log_file: str | None = None, warmup: int = 0,
         retries: int = 1) -> list[dict]:
    """Return nodes that can fetch the Cloudflare trace page, annotated with country/latency."""
    alive: list[dict] = []
    for start in range(0, len(nodes), batch):
        group = nodes[start:start + batch]
        base_port = _free_base(base_port, len(group))
        cfg, _, _ = _build(group, base_port)
        if log_file:
            cfg["log"] = {"level": "debug"}
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump(cfg, f)
            path = f.name
        log = tempfile.TemporaryFile("w+")
        proc = subprocess.Popen([SING_BOX, "run", "-c", path], stdout=subprocess.DEVNULL, stderr=log, text=True)
        try:
            if not _wait_port(base_port + len(group) - 1):
                log.seek(0)
                err = log.read(2000)
                print(f"  ! sing-box failed to start batch {start}: {err.strip()[:300]}", flush=True)
                continue
            if warmup:
                time.sleep(warmup)  # let tunnel-style endpoints (OpenVPN) finish their handshake

            def probe(i):
                for attempt in range(retries):
                    res = _curl(base_port + i, timeout)
                    if res:
                        return res
                    if attempt + 1 < retries:
                        time.sleep(5)
                return None

            with ThreadPoolExecutor(concurrency) as pool:
                results = list(pool.map(probe, range(len(group))))
            for node, res in zip(group, results):
                if res:
                    alive.append({**node, **res})
            print(f"  batch {start // batch + 1}: {sum(1 for r in results if r)}/{len(group)} alive", flush=True)
        finally:
            proc.terminate()
            try:
                proc.wait(10)
            except subprocess.TimeoutExpired:
                proc.kill()
            if log_file:
                log.seek(0)
                lines = log.read().splitlines()
                with open(log_file, "a") as out:
                    out.write("\n".join(lines[:400] + ["..."] + lines[-200:]) + "\n")
            log.close()
            os.unlink(path)
    return alive


def _curl_trace(port: int, timeout: int) -> dict | None:
    try:
        r = subprocess.run(
            ["curl", "-sS", "--max-time", str(timeout), "-x", f"socks5h://127.0.0.1:{port}",
             "-o", "-", "-w", "\n__TIME=%{time_total}", TRACE_URL],
            capture_output=True, text=True, timeout=timeout + 5)
    except subprocess.TimeoutExpired:
        return None
    if r.returncode != 0 or "loc=" not in r.stdout:
        return None
    return dict(line.split("=", 1) for line in r.stdout.splitlines() if "=" in line)


def test_warp_chain(nodes: list[dict], accounts: list[dict], timeout: int = 10,
                    base_port: int = 46000, budget: int = 150, focus: set | None = None) -> list[tuple[dict, str, int]]:
    """phone -> node -> Cloudflare WARP. Returns (node, WARP exit country, latency ms) for chains that work.

    Each node in a batch gets its own WARP account: one WireGuard key used from two places at once
    makes the two sessions knock each other off.
    """
    if not nodes or not accounts:
        return []
    out = []
    width = len(accounts)
    if focus:  # test focus-country nodes first so the time budget never skips them
        nodes = sorted(nodes, key=lambda n: n.get("country") not in focus)
    deadline = time.time() + budget  # hard time limit so the hourly schedule is kept
    for start in range(0, len(nodes), width):
        # focus nodes bypass the time budget; others stop when the budget runs out
        if time.time() + timeout + 5 > deadline and not any(n.get("country") in focus for n in nodes[start:start + width] if focus):
            print(f"  chain test time budget reached after {start} nodes", flush=True)
            break
        group = nodes[start:start + width]
        base_port = _free_base(base_port, len(group))
        cfg = _base_config()
        outbounds, endpoints, inbounds = [], [], []
        for i, (node, acct) in enumerate(zip(group, accounts)):
            up = dict(node["config"], tag=f"u{i}")
            (endpoints if node["kind"] == "endpoint" else outbounds).append(up)
            endpoints.append(dict(acct["config"], tag=f"w{i}", detour=f"u{i}"))
            inbounds.append({"type": "mixed", "tag": f"in{i}", "listen": "127.0.0.1", "listen_port": base_port + i})
            cfg["route"]["rules"].append({"inbound": [f"in{i}"], "outbound": f"w{i}"})
        cfg["outbounds"] = outbounds + [{"type": "direct", "tag": "direct"}]
        cfg["endpoints"] = endpoints
        cfg["inbounds"] = inbounds
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump(cfg, f)
            path = f.name
        log = tempfile.TemporaryFile("w+")
        proc = subprocess.Popen([SING_BOX, "run", "-c", path], stdout=subprocess.DEVNULL, stderr=log, text=True)
        try:
            if not _wait_port(base_port + len(group) - 1):
                log.seek(0)
                print(f"  ! chain batch failed to start: {log.read(300).strip()}", flush=True)
                continue

            def probe(i):
                t0 = time.time()
                info = _curl_trace(base_port + i, timeout)
                if info and info.get("warp") in ("on", "plus"):
                    return info.get("loc", "ZZ").upper(), int((time.time() - t0) * 1000)
                return None

            with ThreadPoolExecutor(len(group)) as pool:
                results = list(pool.map(probe, range(len(group))))
            for node, res in zip(group, results):
                if res:
                    out.append((node, res[0], res[1]))
        finally:
            proc.terminate()
            try:
                proc.wait(10)
            except subprocess.TimeoutExpired:
                proc.kill()
            log.close()
            os.unlink(path)
    return out
