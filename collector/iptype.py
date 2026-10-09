"""Classify exit IPs as Data Centre / Residential / ISP / Mobile.

Sources
  * ip-api.com batch API (free, non-commercial): hosting / mobile flags, ISP name, reverse DNS
  * X4BNet/lists_vpn datacenter ranges: second opinion for data-centre detection

Rules (first match wins)
  1. ip-api "mobile"                                   -> mobile
  2. ip-api "hosting" or inside a known DC range        -> dc
  3. reverse DNS looks like a home line (dsl, ppp, ...) -> residential
  4. otherwise (registered to an ISP, static/no rDNS)   -> isp   ("static residential")
"""

from __future__ import annotations

import ipaddress
import json
import re
import bisect
import time
import urllib.error
import urllib.request

API = "http://ip-api.com/batch?fields=status,query,isp,org,as,mobile,hosting,reverse"
DC_LISTS = [
    "https://raw.githubusercontent.com/X4BNet/lists_vpn/main/output/datacenter/ipv4.txt",
    "https://raw.githubusercontent.com/X4BNet/lists_vpn/main/output/datacenter/ipv6.txt",
]

_HOME = re.compile(
    r"(dyn|pool|dsl|cable|fib|ftth|fttx|ppp|dhcp|cust|client|home|broadband|bbtec|subscriber|"
    r"\bcpe|resid|\bres\b|user|catv|wifi|wlan|ipoe|flets|ocn|\bbb\b)", re.I)
_STATIC = re.compile(r"(static|biz|business|server|srv|host|vps|cloud|colo|dedicated|compute|amazonaws|"
                     r"googleusercontent|azure|linode|ovh|hetzner|digitalocean|vultr|contabo)", re.I)


def _load_dc_ranges():
    nets = []
    for url in DC_LISTS:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "vpn-hub"})
            text = urllib.request.urlopen(req, timeout=30).read().decode()
        except Exception as e:  # noqa: BLE001
            print(f"  ! datacenter list {url}: {e}")
            continue
        for line in text.splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                try:
                    nets.append(ipaddress.ip_network(line, strict=False))
                except ValueError:
                    pass
    # merged, sorted (start, end) integer intervals per IP version for bisect lookups
    table = {}
    for version in (4, 6):
        spans = sorted((int(n.network_address), int(n.broadcast_address)) for n in nets if n.version == version)
        merged: list[list[int]] = []
        for a, b in spans:
            if merged and a <= merged[-1][1] + 1:
                merged[-1][1] = max(merged[-1][1], b)
            else:
                merged.append([a, b])
        table[version] = ([m[0] for m in merged], [m[1] for m in merged])
    return table


def _in_ranges(ip: str, table) -> bool:
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return False
    starts, ends = table.get(addr.version, ([], []))
    i = bisect.bisect_right(starts, int(addr)) - 1
    return i >= 0 and int(addr) <= ends[i]


def _looks_residential(reverse: str, ip: str) -> bool:
    if not reverse:
        return False
    if _STATIC.search(reverse):
        return False
    if _HOME.search(reverse):
        return True
    # hostnames that embed the address (e.g. 123-45-67-89.isp.net) are typically dynamic home lines
    parts = ip.split(".")
    if len(parts) == 4:
        joined = re.sub(r"[^0-9]", "-", reverse)
        if all(p in joined.split("-") for p in parts):
            return True
    return False


def _query(ips: list[str]) -> dict[str, dict]:
    out: dict[str, dict] = {}
    for start in range(0, len(ips), 100):
        chunk = ips[start:start + 100]
        body = json.dumps([{"query": ip} for ip in chunk]).encode()
        for attempt in range(3):
            req = urllib.request.Request(API, data=body, method="POST",
                                         headers={"Content-Type": "application/json", "User-Agent": "vpn-hub"})
            try:
                with urllib.request.urlopen(req, timeout=60) as r:
                    data = json.loads(r.read())
                    remaining = int(r.headers.get("X-Rl", "1"))
                    ttl = int(r.headers.get("X-Ttl", "0"))
                for row in data:
                    if row.get("status") == "success":
                        out[row["query"]] = row
                if remaining <= 0:
                    time.sleep(ttl + 1)
                break
            except urllib.error.HTTPError as e:
                if e.code == 429:
                    time.sleep(int(e.headers.get("X-Ttl", "60")) + 1)
                    continue
                print(f"  ! ip-api: {e}")
                break
            except Exception as e:  # noqa: BLE001
                print(f"  ! ip-api: {e}")
                time.sleep(5)
        time.sleep(4.1)  # free tier: 15 batch requests per minute
    return out


def classify(ips: list[str]) -> dict[str, dict]:
    """Return {ip: {"ip_type": dc|residential|isp|mobile|unknown, "isp": name}}."""
    ips = sorted({ip for ip in ips if ip})
    if not ips:
        return {}
    info = _query(ips)
    dc_nets = _load_dc_ranges()
    result = {}
    for ip in ips:
        row = info.get(ip)
        in_dc = _in_ranges(ip, dc_nets)
        if row is None:
            result[ip] = {"ip_type": "dc" if in_dc else "unknown", "isp": ""}
            continue
        isp = row.get("isp") or row.get("org") or ""
        if row.get("mobile"):
            kind = "mobile"
        elif row.get("hosting") or in_dc:
            kind = "dc"
        elif _looks_residential(row.get("reverse") or "", ip):
            kind = "residential"
        else:
            kind = "isp"
        result[ip] = {"ip_type": kind, "isp": isp[:60]}
    return result
