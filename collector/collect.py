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
VPNGATE_LIMIT = int(ENV("VPNGATE_LIMIT", "1000"))  # VPN Gate: test every server the API lists
TEST_TIMEOUT = int(ENV("TEST_TIMEOUT", "10"))
TEST_CONCURRENCY = int(ENV("TEST_CONCURRENCY", "128"))
TCP_PROTOCOLS = {"vless", "vmess", "trojan"}
# Countries to collect as many nodes as possible for: every candidate whose server IP is located there gets tested
PRIORITY_COUNTRIES = {c.strip().upper() for c in ENV("PRIORITY_COUNTRIES", "PH,IN,TR,AR,KZ").split(",") if c.strip()}
PRIORITY_MAX = int(ENV("PRIORITY_MAX", "4000"))
GEOIP_DB = ENV("GEOIP_DB", os.path.join(os.path.dirname(os.path.abspath(__file__)), "Country.mmdb"))


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
                got = extra.vpngate(val, VPNGATE_LIMIT, PRIORITY_COUNTRIES)
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


def _server_host(n: dict) -> str:
    c = n["config"]
    if c.get("type") == "wireguard":
        return c["peers"][0]["address"]
    if "server" in c:
        return c["server"]
    return c["servers"][0]["server"]  # openvpn-client with several remotes


def geo_hint(nodes: list[dict]) -> None:
    """Resolve server names and look up their country (a hint: CDN-fronted nodes may exit elsewhere)."""
    try:
        import maxminddb
        reader = maxminddb.open_database(GEOIP_DB)
    except Exception as e:  # noqa: BLE001
        print(f"  ! GeoIP database unavailable ({e}); country priority disabled")
        return
    hosts = sorted({_server_host(n) for n in nodes})

    async def resolve_all():
        loop = asyncio.get_running_loop()
        loop.set_default_executor(ThreadPoolExecutor(256))
        sem = asyncio.Semaphore(512)

        async def one(h):
            async with sem:
                try:
                    infos = await asyncio.wait_for(loop.getaddrinfo(h, None), 5)
                    return h, infos[0][4][0]
                except Exception:  # noqa: BLE001
                    return h, None
        return dict(await asyncio.gather(*(one(h) for h in hosts)))

    addr = asyncio.run(resolve_all())
    for n in nodes:
        ip = addr.get(_server_host(n))
        cc = None
        if ip:
            try:
                rec = reader.get(ip) or {}
                cc = (rec.get("country") or rec.get("registered_country") or {}).get("iso_code")
            except ValueError:
                pass
        n["geo_hint"] = cc
    counts = Counter(n["geo_hint"] for n in nodes if n["geo_hint"] in PRIORITY_COUNTRIES)
    print("  priority candidates by server location: " +
          (", ".join(f"{k} {v}" for k, v in counts.most_common()) or "none"))


def cap_per_protocol(nodes: list[dict]) -> list[dict]:
    """Test every candidate in a priority country, then a random sample of the rest."""
    priority = [n for n in nodes if n.get("geo_hint") in PRIORITY_COUNTRIES]
    random.shuffle(priority)
    priority = priority[:PRIORITY_MAX]
    chosen = {id(n) for n in priority}
    by = defaultdict(list)
    for n in nodes:
        if id(n) not in chosen:
            by[n["protocol"]].append(n)
    out = list(priority)
    for proto, items in by.items():
        random.shuffle(items)
        out += items[:MAX_PER_PROTOCOL]
        print(f"  {proto:10s} {len(items):6d} others -> testing {min(len(items), MAX_PER_PROTOCOL)}")
    print(f"  priority countries ({','.join(sorted(PRIORITY_COUNTRIES))}): testing {len(priority)}")
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
            else:
                tasks.append(_dns_ok(_server_host(n), sem))
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
        rec["config"] = n["config"]
        if n.get("link"):
            rec["link"] = clean_link(n["link"]) + "#" + rec["name"]
        if n.get("ovpn"):
            rec["ovpn"] = n["ovpn"]  # original profile, for use in other OpenVPN apps
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

    print("== locate servers")
    geo_hint(proxies)
    proxies = cap_per_protocol(proxies)
    print("== validate config")
    proxies = singbox.validate(proxies) + singbox.validate(warp)
    ovpn = singbox.validate(ovpn)
    print(f"   {len(proxies)} proxy + {len(ovpn)} OpenVPN accepted by sing-box")
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

    # OpenVPN handshakes are slower, so they get their own batches and a longer timeout
    print("== OpenVPN test (sing-box openvpn-client)")
    os.makedirs("logs", exist_ok=True)
    alive_ovpn = singbox.test(ovpn, batch=100, concurrency=50, timeout=25, base_port=40000,
                              log_file="logs/openvpn-debug.log" if ENV("DEBUG_OPENVPN") else None)
    print(f"   {len(alive_ovpn)} working")

    final = finalize(alive + alive_ovpn)
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
