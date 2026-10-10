#!/usr/bin/env python3
"""Reproduce the Android app's WARP setup in CI and report where it breaks.

Mirrors app code exactly:
  * Warp.kt          - key generation (clamped random + pure X25519) and Cloudflare registration
  * ConfigBuilder.kt - the sing-box config for "direct WARP" and "node -> WARP" (urltest detour)
Tests through a mixed (SOCKS/HTTP) inbound and through a real TUN interface like Android uses.
"""

from __future__ import annotations

import base64
import datetime
import json
import os
import secrets
import subprocess
import sys
import tempfile
import time
import urllib.request

SING_BOX = os.environ.get("SING_BOX", "sing-box")
TRACE = "https://www.cloudflare.com/cdn-cgi/trace"
P = 2 ** 255 - 19


def x25519_base(k: bytes) -> bytes:  # same algorithm as Warp.kt
    k = bytearray(k)
    k[0] &= 248
    k[31] &= 127
    k[31] |= 64
    kk = int.from_bytes(k, "little")
    x1, x2, z2, x3, z3, swap = 9, 1, 0, 9, 1, 0
    for t in range(254, -1, -1):
        kt = (kk >> t) & 1
        swap ^= kt
        if swap:
            x2, x3, z2, z3 = x3, x2, z3, z2
        swap = kt
        a = (x2 + z2) % P; aa = a * a % P; b = (x2 - z2) % P; bb = b * b % P; e = (aa - bb) % P
        c = (x3 + z3) % P; d = (x3 - z3) % P; da = d * a % P; cb = c * b % P
        x3 = (da + cb) ** 2 % P; z3 = x1 * (da - cb) ** 2 % P
        x2 = aa * bb % P; z2 = e * (aa + 121665 * e) % P
    if swap:
        x2, z2 = x3, z3
    return (x2 * pow(z2, P - 2, P) % P).to_bytes(32, "little")


def register_like_app() -> dict:
    priv = bytearray(secrets.token_bytes(32))
    priv[0] &= 248
    priv[31] = (priv[31] & 127) | 64
    pub = x25519_base(bytes(priv))
    body = json.dumps({
        "install_id": "", "fcm_token": "", "key": base64.b64encode(pub).decode(), "type": "Android",
        "model": "PC", "locale": "en_US", "warp_enabled": True,
        "tos": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z"),
    }).encode()
    req = urllib.request.Request("https://api.cloudflareclient.com/v0a2158/reg", data=body, method="POST",
                                 headers={"User-Agent": "okhttp/3.12.1", "CF-Client-Version": "a-6.10-2158",
                                          "Content-Type": "application/json; charset=UTF-8"})
    with urllib.request.urlopen(req, timeout=20) as r:
        root = json.loads(r.read())
    config = root.get("config") or root.get("result", {}).get("config")
    shape = {k: (sorted(v.keys()) if isinstance(v, dict) else type(v).__name__) for k, v in root.items()}
    print("registration response keys:", json.dumps(shape)[:600])
    print("config keys:", sorted(config.keys()))
    print("interface:", json.dumps(config["interface"]))
    peer = config["peers"][0]
    print("peer:", json.dumps({k: v for k, v in peer.items()}))
    print("client_id present:", "client_id" in config, " account.warp_enabled:",
          root.get("warp_enabled"), " account:", json.dumps(root.get("account", {}))[:300])
    reserved = list(base64.b64decode(config.get("client_id", "AAAA")))[:3]
    return {"privateKey": base64.b64encode(bytes(priv)).decode(),
            "peerPublicKey": peer.get("public_key"),
            "v4": config["interface"]["addresses"]["v4"],
            "v6": config["interface"]["addresses"].get("v6"),
            "reserved": (reserved + [0, 0, 0])[:3]}


def warp_endpoint(acct: dict, tag: str, detour: str | None) -> dict:  # Warp.endpoint()
    ep = {"type": "wireguard", "tag": tag,
          "address": [f"{acct['v4']}/32"] + ([f"{acct['v6']}/128"] if acct.get("v6") else []),
          "private_key": acct["privateKey"], "mtu": 1280,
          "peers": [{"address": "162.159.192.1", "port": 2408, "public_key": acct["peerPublicKey"],
                     "allowed_ips": ["0.0.0.0/0", "::/0"], "reserved": acct["reserved"]}]}
    if detour:
        ep["detour"] = detour
    return ep


def app_config(acct: dict, nodes: list[dict], inbound: str) -> dict:  # ConfigBuilder.build()
    outbounds, endpoints, groups, tags = [], [], [], []
    for n in nodes:
        o = dict(n["config"], tag=n["name"])
        (endpoints if n["kind"] == "endpoint" else outbounds).append(o)
        tags.append(n["name"])
    detour = "direct"  # app 1.0.13+: direct WARP goes through the "direct" outbound
    if nodes:
        groups.append({"type": "urltest", "tag": "warp-up", "outbounds": tags,
                       "url": "https://www.gstatic.com/generate_204", "interval": "1m", "tolerance": 100,
                       "idle_timeout": "30m"})
        detour = "warp-up"
    endpoints.append(warp_endpoint(acct, "warp", detour))
    if inbound == "tun":
        inb = {"type": "tun", "tag": "tun-in", "interface_name": "tun-test",
               "address": ["172.19.0.1/30", "fdfe:dcba:9876::1/126"], "mtu": 9000,
               "auto_route": False, "stack": "mixed"}
    else:
        inb = {"type": "mixed", "tag": "mixed-in", "listen": "127.0.0.1", "listen_port": 17890}
    return {
        "log": {"level": "debug"},
        "dns": {"servers": [{"type": "https", "tag": "remote", "server": "1.1.1.1", "detour": "warp"},
                            {"type": "local", "tag": "local"}],
                "final": "remote", "strategy": "prefer_ipv4"},
        "inbounds": [inb],
        "outbounds": outbounds + groups + [{"type": "direct", "tag": "direct"}],
        "endpoints": endpoints,
        "route": {"rules": [{"action": "sniff"}, {"protocol": "dns", "action": "hijack-dns"},
                            {"ip_is_private": True, "outbound": "direct"}],
                  "final": "warp", "auto_detect_interface": True, "default_domain_resolver": "local"},
    }


def run_case(name: str, cfg: dict, probe: list[list[str]], wait: float = 4) -> bool:
    print(f"\n===== {name}")
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        json.dump(cfg, f)
        path = f.name
    chk = subprocess.run([SING_BOX, "check", "-c", path], capture_output=True, text=True)
    if chk.returncode:
        print("CHECK FAILED:", chk.stderr[-500:])
        return False
    log = tempfile.TemporaryFile("w+")
    proc = subprocess.Popen([SING_BOX, "run", "-c", path], stdout=log, stderr=log, text=True)
    ok = False
    try:
        time.sleep(wait)
        for cmd in probe:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=40)
            out = (r.stdout + r.stderr).strip()
            print("$", " ".join(cmd), f"-> rc={r.returncode}\n   ", out.replace("\n", " | ")[:400])
            ok = ok or ("warp=on" in out or "warp=plus" in out)
    except subprocess.TimeoutExpired as e:
        print("probe timeout:", e)
    finally:
        proc.terminate()
        try:
            proc.wait(10)
        except subprocess.TimeoutExpired:
            proc.kill()
        log.seek(0)
        lines = [ln for ln in log.read().splitlines() if "wireguard" in ln.lower() or "warp" in ln.lower()
                 or "error" in ln.lower() or "FATAL" in ln]
        print("   sing-box log (wireguard/warp/errors):")
        for ln in lines[:40]:
            print("   |", ln[:220])
        os.unlink(path)
    print("RESULT:", "WORKS" if ok else "FAILED")
    return ok


def main() -> None:
    print("== register a WARP account exactly like the app")
    try:
        acct = register_like_app()
    except Exception as e:  # noqa: BLE001
        print("REGISTRATION FAILED:", repr(e))
        sys.exit(0)
    print("account:", {k: (v if k != "privateKey" else "<hidden>") for k, v in acct.items()})

    curl_mixed = ["curl", "-sS", "--max-time", "20", "-x", "socks5h://127.0.0.1:17890", TRACE]
    results = {}
    results["direct WARP, mixed inbound"] = run_case(
        "direct WARP via mixed inbound", app_config(acct, [], "mixed"), [curl_mixed])
    results["direct WARP, TUN"] = run_case(
        "direct WARP via TUN (like Android)", app_config(acct, [], "tun"),
        [["ip", "addr", "show", "tun-test"],
         ["dig", "+time=5", "+tries=1", "+short", "@172.19.0.2", "www.cloudflare.com"],
         ["curl", "-sS", "--max-time", "20", "--interface", "tun-test", TRACE]], wait=5)

    nodes_url = os.environ.get("NODES_URL")
    if nodes_url:
        data = json.loads(urllib.request.urlopen(nodes_url, timeout=30).read())
        capable = [n for n in data["nodes"] if n.get("warp_cc") and n.get("config")]
        by = {}
        for n in capable:
            by.setdefault(n["warp_cc"], []).append(n)
        print("\nWARP-capable nodes by WARP country:", {k: len(v) for k, v in by.items()})
        if by:
            cc = max(by, key=lambda k: len(by[k]))
            group = sorted(by[cc], key=lambda n: n.get("warp_ms") or 99999)[:20]
            results[f"chain via {cc} nodes, mixed"] = run_case(
                f"chain: {len(group)} {cc} nodes (urltest) -> WARP, mixed inbound",
                app_config(acct, group, "mixed"), [curl_mixed], wait=8)
            results[f"chain via {cc} nodes, TUN"] = run_case(
                f"chain: {len(group)} {cc} nodes (urltest) -> WARP, TUN",
                app_config(acct, group, "tun"),
                [["curl", "-sS", "--max-time", "25", "--interface", "tun-test", TRACE]], wait=8)
            one = group[0]
            results["chain via 1 node, no urltest"] = run_case(
                f"chain: single node {one['name']} -> WARP (detour straight to node)",
                {**app_config(acct, [], "mixed"),
                 "outbounds": [dict(one["config"], tag="up")] + [{"type": "direct", "tag": "direct"}]
                 if one["kind"] != "endpoint" else [{"type": "direct", "tag": "direct"}],
                 "endpoints": ([dict(one["config"], tag="up")] if one["kind"] == "endpoint" else [])
                 + [warp_endpoint(acct, "warp", "up")]},
                [curl_mixed], wait=6)

    print("\n===== SUMMARY")
    for k, v in results.items():
        print(f"  {'OK    ' if v else 'FAILED'}  {k}")


if __name__ == "__main__":
    main()
