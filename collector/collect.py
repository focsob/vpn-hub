#!/usr/bin/env python3
"""Hourly job: fetch public node lists -> de-duplicate -> test with real cores -> group by exit country.

Output (in --out):
  nodes.json          everything the Android app needs
  sub/<CC>.txt        base64 subscription per country (works in v2rayNG / NekoBox / sing-box etc.)
  sub/all.txt         base64 subscription with every working node
  README.md           summary table
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import datetime
import hashlib
import json
import os
import random
import socket
import sys
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import extra  # noqa: E402
import iptype  # noqa: E402
import singbox  # noqa: E402
from parse import clean_link, extract_links, node_key, parse_link  # noqa: E402

ENV = os.environ.get
MAX_PER_PROTOCOL = int(ENV("MAX_PER_PROTOCOL", "2500"))
PER_COUNTRY = int(ENV("PER_COUNTRY", "150"))
PER_EXIT_IP = int(ENV("PER_EXIT_IP", "3"))
VPNGATE_LIMIT = int(ENV("VPNGATE_LIMIT", "80"))
TEST_TIMEOUT = int(ENV("TEST_TIMEOUT", "10"))
TEST_CONCURRENCY = int(ENV("TEST_CONCURRENCY", "128"))
TCP_PROTOCOLS = {"vless", "vmess", "trojan"}


def read_sources(paths: list[str]) -> list[tuple[str, str]]:
    out = []
    for path in paths:
        if not os.path.exists(path):
            continue
        for line in open(path, encoding="utf-8"):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if parts[0] in ("vpngate", "ovpn", "warp") and len(parts) >= 2:
                out.append((parts[0], parts[1]))
            elif parts[0].startswith(("http://", "https://", "file://")):
                out.append(("sub", parts[0]))
    return list(dict.fromkeys(out))


def fetch_all(sources: list[tuple[str, str]]) -> tuple[list[dict], list[dict], list[dict]]:
    subs = [u for k, u in sources if k == "sub"]

    def get(url):
        try:
            return url, extract_links(extra.http_get(url))
        except Exception as e:  # noqa: BLE001
            return url, e

    links: list[str] = []
    with ThreadPoolExecutor(16) as pool:
        for url, res in pool.map(get, subs):
            if isinstance(res, Exception):
                print(f"  ! {url}: {res}")
            else:
                print(f"  {len(res):6d}  {url}")
                links.extend(res)

    proxies: dict[str, dict] = {}
    for link in links:
        node = parse_link(link)
        if node:
            proxies.setdefault(node_key(node), node)

    ovpn: list[dict] = []
    warp: list[dict] = []
    for kind, val in sources:
        try:
            if kind == "vpngate":
                got = extra.vpngate(val, VPNGATE_LIMIT)
                print(f"  {len(got):6d}  {val} (OpenVPN)")
                ovpn += got
            elif kind == "ovpn":
                ovpn += extra.ovpn_url(val)
            elif kind == "warp":
                got = extra.warp_accounts(int(val))
                print(f"  {len(got):6d}  Cloudflare WARP (WireGuard)")
                warp += got
        except Exception as e:  # noqa: BLE001
            print(f"  ! {kind} {val}: {e}")
    return list(proxies.values()), ovpn, warp


def cap_per_protocol(nodes: list[dict]) -> list[dict]:
    by = defaultdict(list)
    for n in nodes:
        by[n["protocol"]].append(n)
    out = []
    for proto, items in by.items():
        random.shuffle(items)
        out += items[:MAX_PER_PROTOCOL]
        print(f"  {proto:10s} {len(items):6d} unique -> testing {min(len(items), MAX_PER_PROTOCOL)}")
    return out


async def _tcp_ok(host: str, port: int, sem: asyncio.Semaphore) -> bool:
    async with sem:
        try:
            _, w = await asyncio.wait_for(asyncio.open_connection(host, port), 4)
            w.close()
            return True
        except Exception:  # noqa: BLE001
            return False


async def _dns_ok(host: str, sem: asyncio.Semaphore) -> bool:
    async with sem:
        try:
            await asyncio.wait_for(asyncio.get_running_loop().getaddrinfo(host, None), 4)
            return True
        except Exception:  # noqa: BLE001
            return False


def prefilter(nodes: list[dict]) -> list[dict]:
    """Cheap reachability check so the expensive proxy test only sees plausible servers."""
    async def run():
        sem = asyncio.Semaphore(400)
        tasks = []
        for n in nodes:
            c = n["config"]
            if n["protocol"] in TCP_PROTOCOLS:
                tasks.append(_tcp_ok(c["server"], c["server_port"], sem))
            elif n["kind"] == "endpoint":
                tasks.append(_dns_ok(c["peers"][0]["address"], sem))
            else:
                tasks.append(_dns_ok(c["server"], sem))
        return await asyncio.gather(*tasks)

    flags = asyncio.run(run())
    return [n for n, ok in zip(nodes, flags) if ok]


def finalize(alive: list[dict]) -> list[dict]:
    alive.sort(key=lambda n: n.get("latency") or 99999)
    per_ip: Counter = Counter()
    per_country: Counter = Counter()
    out = []
    for n in alive:
        ip = n.get("exit_ip")
        if ip and per_ip[ip] >= PER_EXIT_IP:
            continue
        if per_country[n["country"]] >= PER_COUNTRY:
            continue
        per_ip[ip] += 1
        per_country[n["country"]] += 1
        out.append(n)
    return out


def annotate_ip_types(nodes: list[dict]) -> None:
    """Attach ip_type (dc / residential / isp / mobile) and ISP name based on the exit IP."""
    print("== IP type")
    for n in nodes:
        if n.get("warp"):
            n["ip_type"], n["isp"] = "dc", "Cloudflare WARP"
        elif n["kind"] == "openvpn" and not n.get("exit_ip"):
            remote = extra._remote(n["ovpn"])
            if remote:
                try:
                    n["exit_ip"] = socket.gethostbyname(remote[0])
                except OSError:
                    pass
    todo = [n["exit_ip"] for n in nodes if n.get("exit_ip") and "ip_type" not in n]
    info = iptype.classify(todo)
    for n in nodes:
        if "ip_type" in n:
            continue
        got = info.get(n.get("exit_ip", ""), {})
        n["ip_type"] = got.get("ip_type", "unknown")
        n["isp"] = got.get("isp", "")
    print("   " + ", ".join(f"{k} {v}" for k, v in Counter(n["ip_type"] for n in nodes).most_common()))


def write_output(nodes: list[dict], out_dir: str, stats: dict) -> None:
    os.makedirs(os.path.join(out_dir, "sub"), exist_ok=True)
    now = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
    seq = Counter()
    records = []
    for n in nodes:
        cc = n["country"]
        seq[cc] += 1
        if n["kind"] == "openvpn":
            nid = hashlib.sha1(n["ovpn"].encode()).hexdigest()[:16]
        else:
            nid = node_key(n)
        rec = {
            "id": nid,
            "name": f"{cc}-{seq[cc]:03d}",
            "protocol": n["protocol"],
            "country": cc,
            "latency": n.get("latency", 0),
            "kind": n["kind"],
            "ip_type": n.get("ip_type", "unknown"),
            "isp": n.get("isp", ""),
        }
        if n["kind"] == "openvpn":
            rec["ovpn"] = n["ovpn"]
        else:
            rec["config"] = n["config"]
            rec["link"] = clean_link(n["link"]) + "#" + rec["name"]
        records.append(rec)

    by_cc = defaultdict(list)
    for r in records:
        by_cc[r["country"]].append(r)
    countries = {cc: {"count": len(v), "best": min(x["latency"] for x in v),
                      "protocols": dict(Counter(x["protocol"] for x in v)),
                      "ip_types": dict(Counter(x["ip_type"] for x in v))} for cc, v in by_cc.items()}
    data = {"version": 1, "updated": now.isoformat().replace("+00:00", "Z"), "count": len(records),
            "countries": dict(sorted(countries.items(), key=lambda kv: -kv[1]["count"])),
            "stats": stats, "nodes": records}
    with open(os.path.join(out_dir, "nodes.json"), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))

    def b64(lines):
        return base64.b64encode("\n".join(lines).encode()).decode()

    all_links = []
    for cc, items in by_cc.items():
        links = [x["link"] for x in items if "link" in x]
        if links:
            open(os.path.join(out_dir, "sub", f"{cc}.txt"), "w").write(b64(links))
            all_links += links
    open(os.path.join(out_dir, "sub", "all.txt"), "w").write(b64(all_links))

    rows = "\n".join(f"| {cc} | {v['count']} | {v['best']} ms | "
                     f"{', '.join(f'{p} {c}' for p, c in v['protocols'].items())} | "
                     f"{', '.join(f'{p} {c}' for p, c in v['ip_types'].items())} |"
                     for cc, v in data["countries"].items())
    open(os.path.join(out_dir, "README.md"), "w", encoding="utf-8").write(
        f"# Nodes\n\nUpdated: {data['updated']} · Working: {len(records)}\n\n"
        f"| Country | Nodes | Best | Protocols | IP type |\n|---|---|---|---|---|\n{rows}\n")


def main() -> None:
    ap = argparse.ArgumentParser()
    here = os.path.dirname(os.path.abspath(__file__))
    ap.add_argument("--sources", nargs="*", default=[os.path.join(here, "sources.txt"),
                                                      os.path.join(here, "custom_sources.txt")])
    ap.add_argument("--out", default="out")
    args = ap.parse_args()
    random.seed()

    print("== fetch")
    proxies, ovpn, warp = fetch_all(read_sources(args.sources))
    stats = {"unique": len(proxies) + len(ovpn) + len(warp)}
    print(f"== {len(proxies)} unique proxy nodes, {len(ovpn)} OpenVPN, {len(warp)} WARP")

    proxies = cap_per_protocol(proxies)
    print("== validate config")
    proxies = singbox.validate(proxies) + singbox.validate(warp)
    print(f"   {len(proxies)} accepted by sing-box")
    print("== reachability prefilter")
    proxies = prefilter(proxies)
    print(f"   {len(proxies)} reachable")
    stats["tested"] = len(proxies) + len(ovpn)

    print("== proxy test (sing-box)")
    alive = singbox.test(proxies, concurrency=TEST_CONCURRENCY, timeout=TEST_TIMEOUT)
    for n in alive:
        if n.get("warp"):
            n["country"] = "WARP"
    print(f"   {len(alive)} working")

    print("== OpenVPN test")
    alive_ovpn = extra.test_openvpn(ovpn)
    print(f"   {len(alive_ovpn)} working")

    final = finalize(alive) + sorted(alive_ovpn, key=lambda n: n["latency"])
    annotate_ip_types(final)
    stats["working"] = len(final)
    stats["by_ip_type"] = dict(Counter(n.get("ip_type", "unknown") for n in final))
    stats["by_protocol"] = dict(Counter(n["protocol"] for n in final))
    if not final:
        sys.exit("no working nodes; keeping the previous list")
    write_output(final, args.out, stats)
    print(f"== wrote {len(final)} nodes in {len({n['country'] for n in final})} countries -> {args.out}")


if __name__ == "__main__":
    socket.setdefaulttimeout(30)
    main()
