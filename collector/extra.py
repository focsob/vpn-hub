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


_BLOCK = re.compile(r"(?s)<([a-z0-9-]+)>\s*(.*?)\s*</\1>")


def ovpn_to_singbox(text: str) -> dict | None:
    """Translate a client .ovpn profile into a sing-box (1.14+) openvpn-client endpoint."""
    blocks = {k.lower(): v.strip() for k, v in _BLOCK.findall(text)}
    body = _BLOCK.sub("", text)
    opts: dict[str, list[str]] = {}
    remotes: list[list[str]] = []
    for line in body.splitlines():
        parts = line.strip().split()
        if not parts or parts[0].startswith(("#", ";")):
            continue
        key = parts[0].lower()
        if key == "remote":
            remotes.append(parts[1:])
        else:
            opts[key] = parts[1:]
    if not remotes or "ca" not in blocks:
        return None
    if "secret" in opts or "auth-user-pass" in opts or "pkcs12" in opts:
        return None  # static-key mode, password login or PKCS#12 bundles are not handled
    proto = (opts.get("proto") or ["udp"])[0].lower()
    network = "tcp" if proto.startswith("tcp") else "udp"
    default_port = int((opts.get("port") or ["1194"])[0])
    servers = []
    for r in remotes:
        try:
            port = int(r[1]) if len(r) > 1 else default_port
        except ValueError:
            continue
        net = network if len(r) < 3 else ("tcp" if r[2].lower().startswith("tcp") else "udp")
        servers.append({"server": r[0], "server_port": port, "network": net})
    if not servers:
        return None

    tls: dict = {"certificate": blocks["ca"], "certificate_profile": "insecure", "version_min": "1.0"}
    if "cert" in blocks and "key" in blocks:
        tls["client_certificate"] = blocks["cert"]
        tls["client_key"] = blocks["key"]
    rct = (opts.get("remote-cert-tls") or [""])[0].lower()
    tls["remote_certificate_tls"] = rct if rct in ("server", "client") else "none"
    if "verify-x509-name" in opts and opts["verify-x509-name"]:
        args = opts["verify-x509-name"]
        tls["server_name"] = args[0].strip("'\"")
        kind = args[1].lower() if len(args) > 1 else "subject"
        tls["server_name_type"] = {"name": "name", "name-prefix": "name-prefix"}.get(kind, "subject")
    for block, wrap in (("tls-crypt-v2", "tls_crypt_v2"), ("tls-crypt", "tls_crypt"), ("tls-auth", "tls_auth")):
        if block in blocks:
            cw = {"type": wrap, "key": blocks[block]}
            if wrap == "tls_auth":
                kd = (opts.get("key-direction") or [""])[0]
                if kd in ("0", "1"):
                    cw["direction"] = "client" if kd == "1" else "server"
            tls["control_wrap"] = cw
            break

    cfg: dict = {"type": "openvpn-client", "network": network, "tls": tls, "mtu": 1400}
    if len(servers) == 1:
        cfg["server"], cfg["server_port"], cfg["network"] = servers[0]["server"], servers[0]["server_port"], \
            servers[0]["network"]
    else:
        cfg["servers"] = servers
    cipher = (opts.get("cipher") or [""])[0]
    if "data-ciphers" in opts and opts["data-ciphers"]:
        ciphers = opts["data-ciphers"][0].split(":")
    else:
        ciphers = ["AES-256-GCM", "AES-128-GCM", "CHACHA20-POLY1305"]
    if cipher and cipher not in ciphers:
        ciphers.append(cipher)
    cfg["data_ciphers"] = ciphers
    if cipher:
        cfg["data_ciphers_fallback"] = cipher
    if opts.get("auth"):
        cfg["auth"] = opts["auth"][0]
    if "comp-lzo" in opts or "compress" in opts:
        cfg["allow_compression"] = "asym"
    return cfg


def _ovpn_node(text: str, country_hint: str | None, exit_ip: str = "", score: int = 0) -> dict | None:
    """Wrap an .ovpn profile as a node that sing-box can test and the app can use directly."""
    tidy = _tidy_ovpn(text)
    cfg = ovpn_to_singbox(tidy)
    if not cfg:
        return None
    return {"protocol": "openvpn", "kind": "endpoint", "config": cfg, "ovpn": tidy, "link": "",
            "country_hint": country_hint, "exit_ip_hint": exit_ip, "score": score}


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
        node = _ovpn_node(ovpn, (row.get("CountryShort") or "ZZ").upper()[:2], (row.get("IP") or "").strip(), score)
        if node:
            out.append(node)
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
    node = _ovpn_node(text, None)
    return [node] if node else []


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

