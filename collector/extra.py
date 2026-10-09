"""OpenVPN (VPN Gate / .ovpn URLs) and WireGuard (Cloudflare WARP) sources."""

from __future__ import annotations

import base64
import csv
import datetime
import io
import json
import os
import re
import select
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"


def http_get(url: str, timeout: int = 30, limit: int = 40 << 20) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read(limit).decode("utf-8", "ignore")


# --------------------------------------------------------------------------- OpenVPN

def _tidy_ovpn(text: str) -> str:
    lines = [ln.rstrip() for ln in text.replace("\r", "").splitlines()]
    lines = [ln for ln in lines if ln.strip() and not ln.lstrip().startswith(("#", ";"))]
    body = "\n".join(lines)
    m = re.search(r"^cipher\s+(\S+)", body, re.M)
    if m and "data-ciphers" not in body:
        # OpenVPN 2.6 (OpenVPN for Android) refuses old CBC-only servers without this
        body += f"\ndata-ciphers AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305:{m.group(1)}"
        body += f"\ndata-ciphers-fallback {m.group(1)}"
    return body + "\n"


def _remote(ovpn: str) -> tuple[str, int, str] | None:
    m = re.search(r"^remote\s+(\S+)\s+(\d+)(?:\s+(\w+))?", ovpn, re.M)
    if not m:
        return None
    proto = (m.group(3) or "").lower()
    if not proto:
        p = re.search(r"^proto\s+(\w+)", ovpn, re.M)
        proto = p.group(1).lower() if p else "udp"
    return m.group(1), int(m.group(2)), "tcp" if proto.startswith("tcp") else "udp"


def vpngate(url: str, limit: int, priority: set[str] | None = None) -> list[dict]:
    text = http_get(url)
    rows = [ln for ln in text.splitlines() if ln and not ln.startswith("*")]
    if rows and rows[0].startswith("#"):
        rows[0] = rows[0][1:]
    out = []
    for row in csv.DictReader(io.StringIO("\n".join(rows))):
        try:
            ovpn = base64.b64decode(row.get("OpenVPN_ConfigData_Base64") or "").decode("utf-8", "ignore")
            score = int(row.get("Score") or 0)
        except (ValueError, TypeError):
            continue
        if "remote " not in ovpn:
            continue
        out.append({"protocol": "openvpn", "kind": "openvpn", "ovpn": _tidy_ovpn(ovpn),
                    "country_hint": (row.get("CountryShort") or "ZZ").upper()[:2], "score": score,
                    "exit_ip": (row.get("IP") or "").strip()})
    out.sort(key=lambda n: -n["score"])
    priority = priority or set()
    top = out[:limit]
    # always keep servers in priority countries, even when their score is low
    extra = [n for n in out[limit:] if n["country_hint"] in priority]
    return top + extra


def ovpn_url(url: str) -> list[dict]:
    text = http_get(url)
    if "remote " not in text:
        return []
    return [{"protocol": "openvpn", "kind": "openvpn", "ovpn": _tidy_ovpn(text), "country_hint": None}]


def test_openvpn(nodes: list[dict], concurrency: int = 12, timeout: int = 25) -> list[dict]:
    """Bring each tunnel up (without touching routes) and keep the ones that finish the handshake.

    Needs root (CI runs the collector with sudo). Without the openvpn binary the step is skipped.
    """
    if not nodes:
        return []
    if subprocess.run(["which", "openvpn"], capture_output=True).returncode != 0 or os.geteuid() != 0:
        print("  ! openvpn missing or not root; OpenVPN nodes kept untested")
        return []

    def one(args):
        i, node = args
        with tempfile.NamedTemporaryFile("w", suffix=".ovpn", delete=False) as f:
            f.write(node["ovpn"])
            path = f.name
        t0 = time.time()
        proc = subprocess.Popen(
            ["openvpn", "--config", path, "--dev", f"tunvt{i}", "--dev-type", "tun", "--route-nopull",
             "--pull-filter", "ignore", "redirect-gateway", "--pull-filter", "ignore", "dhcp-option",
             "--script-security", "0", "--connect-retry-max", "1", "--connect-timeout", "10",
             "--resolv-retry", "0", "--auth-nocache", "--verb", "3"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        ok = False
        try:
            end = t0 + timeout
            fd = proc.stdout.fileno()
            buf = b""
            while time.time() < end:
                ready, _, _ = select.select([fd], [], [], 0.5)
                if ready:
                    chunk = os.read(fd, 65536)
                    if not chunk:  # process exited
                        break
                    buf += chunk
                    if b"Initialization Sequence Completed" in buf:
                        ok = True
                        break
                    if b"AUTH_FAILED" in buf:
                        break
                elif proc.poll() is not None:
                    break
        finally:
            proc.terminate()
            try:
                proc.wait(5)
            except subprocess.TimeoutExpired:
                proc.kill()
            os.unlink(path)
        if not ok:
            return None
        return {**node, "latency": int((time.time() - t0) * 1000)}

    def safe(args):
        try:
            return one(args)
        except Exception as e:  # noqa: BLE001
            print(f"  ! OpenVPN test error: {e}")
            return None

    with ThreadPoolExecutor(concurrency) as pool:
        results = list(pool.map(safe, enumerate(nodes)))
    alive = []
    for r in results:
        if r:
            r["country"] = r.pop("country_hint", None) or "ZZ"
            r.pop("score", None)
            alive.append(r)
    return alive


# --------------------------------------------------------------------------- WireGuard (Cloudflare WARP)

WARP_PEER_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
WARP_ENDPOINTS = ["162.159.192.1", "162.159.193.1", "162.159.195.1", "188.114.97.1"]


def warp_accounts(count: int) -> list[dict]:
    """Register anonymous Cloudflare WARP devices and emit WireGuard endpoints for them."""
    if count <= 0:
        return []
    try:
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
    except ImportError:
        print("  ! python package 'cryptography' missing; WARP skipped")
        return []
    nodes = []
    for i in range(count):
        priv = X25519PrivateKey.generate()
        priv_b64 = base64.b64encode(priv.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw,
                                                       serialization.NoEncryption())).decode()
        pub_b64 = base64.b64encode(priv.public_key().public_bytes(serialization.Encoding.Raw,
                                                                  serialization.PublicFormat.Raw)).decode()
        body = json.dumps({
            "install_id": "", "fcm_token": "", "key": pub_b64, "type": "Android", "model": "PC",
            "locale": "en_US", "warp_enabled": True,
            "tos": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z"),
        }).encode()
        req = urllib.request.Request("https://api.cloudflareclient.com/v0a2158/reg", data=body, method="POST",
                                     headers={"User-Agent": "okhttp/3.12.1", "CF-Client-Version": "a-6.10-2158",
                                              "Content-Type": "application/json; charset=UTF-8"})
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                data = json.loads(r.read())
            conf = data.get("config") or data.get("result", {}).get("config")
            v4 = conf["interface"]["addresses"]["v4"]
            v6 = conf["interface"]["addresses"].get("v6")
            peer_key = conf["peers"][0].get("public_key") or WARP_PEER_KEY
            reserved = list(base64.b64decode(conf.get("client_id", "AAAA")))[:3]
        except Exception as e:  # noqa: BLE001
            print(f"  ! WARP registration failed: {e}")
            continue
        endpoint = WARP_ENDPOINTS[i % len(WARP_ENDPOINTS)]
        addresses = [f"{v4}/32"] + ([f"{v6}/128"] if v6 else [])
        cfg = {"type": "wireguard", "address": addresses, "private_key": priv_b64, "mtu": 1280,
               "peers": [{"address": endpoint, "port": 2408, "public_key": peer_key,
                          "allowed_ips": ["0.0.0.0/0", "::/0"], "reserved": reserved + [0] * (3 - len(reserved))}]}
        link = (f"wireguard://{urllib.parse.quote(priv_b64, safe='')}@{endpoint}:2408"
                f"?publickey={urllib.parse.quote(peer_key, safe='')}"
                f"&address={urllib.parse.quote(','.join(addresses), safe='')}"
                f"&reserved={','.join(map(str, cfg['peers'][0]['reserved']))}&mtu=1280")
        nodes.append({"protocol": "wireguard", "kind": "endpoint", "config": cfg, "link": link, "warp": True})
        time.sleep(1)
    return nodes

