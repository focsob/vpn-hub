"""Parse share links (vless / vmess / trojan / hysteria2 / wireguard) into sing-box configs.

Every parser returns a dict:
    {"protocol": str, "kind": "outbound" | "endpoint", "config": {...}, "link": str}
or None when the link is malformed or uses a feature sing-box does not support.
The "config" never contains a "tag"; the tester / app assigns one.
"""

from __future__ import annotations

import base64
import binascii
import ipaddress
import json
import re
import uuid
from urllib.parse import parse_qsl, unquote, urlsplit

SCHEMES = ("vless", "vmess", "trojan", "hysteria2", "hy2", "wireguard", "wg")
_SPLIT = re.compile(r"(?=(?:%s)://)" % "|".join(SCHEMES), re.I)
_UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", re.I)
_HEX = re.compile(r"^[0-9a-fA-F]*$")
_WG_KEY = re.compile(r"^[A-Za-z0-9+/]{42,43}=?$")

UTLS = {"chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized"}
VMESS_SECURITY = {"auto", "none", "zero", "aes-128-gcm", "chacha20-poly1305", "aes-128-ctr"}


# --------------------------------------------------------------------------- helpers

def b64decode_loose(text: str) -> bytes:
    s = re.sub(r"\s", "", text).replace("-", "+").replace("_", "/")
    s += "=" * (-len(s) % 4)
    return base64.b64decode(s)


def decode_subscription(raw: str) -> str:
    """Subscriptions are either plain text or one big base64 blob."""
    if "://" in raw[:4096]:
        return raw
    try:
        text = b64decode_loose(raw).decode("utf-8", "ignore")
        if "://" in text:
            return text
    except (binascii.Error, ValueError):
        pass
    return raw


def extract_links(raw: str) -> list[str]:
    text = decode_subscription(raw).replace("&amp;", "&")
    links = []
    for chunk in _SPLIT.split(text):
        chunk = chunk.strip()
        if "://" not in chunk[:12]:
            continue
        link = re.split(r"[\s\"'<>`]", chunk, maxsplit=1)[0]
        if len(link) > 12:
            links.append(link)
    return links


def _truthy(v) -> bool:
    return str(v).strip().lower() in ("1", "true", "yes")


def _port(v) -> int | None:
    try:
        p = int(str(v).strip())
    except (TypeError, ValueError):
        return None
    return p if 0 < p < 65536 else None


def _host(h: str | None) -> str | None:
    if not h:
        return None
    h = h.strip().strip("[]")
    if not h or len(h) > 253 or any(c in h for c in " /\\@#?"):
        return None
    try:
        ip = ipaddress.ip_address(h)
        if ip.is_private or ip.is_loopback or ip.is_unspecified or ip.is_multicast or ip.is_link_local:
            return None
    except ValueError:
        if not re.match(r"^[A-Za-z0-9.-]+$", h) or "." not in h:
            return None
    return h


def _is_ip(h: str) -> bool:
    try:
        ipaddress.ip_address(h)
        return True
    except ValueError:
        return False


def _first(v: str | None) -> str:
    return (v or "").split(",")[0].strip()


def _split_url(link: str):
    """urlsplit that tolerates junk; returns (user, host, port, query dict, fragment)."""
    try:
        u = urlsplit(link)
        host, port = u.hostname, u.port
    except ValueError:
        return None
    q = {k.lower().removeprefix("amp;"): v.strip() for k, v in parse_qsl(u.query, keep_blank_values=True)}
    return unquote(u.username or ""), host, port, q, unquote(u.fragment or "")


def _tls(q: dict, server: str, security: str, transport_type: str | None) -> dict | None | bool:
    """Return tls dict, None for plain, or False if unsupported."""
    security = (security or "").lower()
    if security in ("", "none", "false", "0"):
        return None
    if security not in ("tls", "reality", "xtls"):
        return False
    sni = _first(q.get("sni") or q.get("peer") or q.get("servername")) or _first(q.get("host"))
    if not sni and not _is_ip(server):
        sni = server
    tls: dict = {"enabled": True}
    if sni:
        tls["server_name"] = sni
    if _truthy(q.get("allowinsecure")) or _truthy(q.get("insecure")) or _truthy(q.get("allow_insecure")) \
            or _truthy(q.get("skip-cert-verify")):
        tls["insecure"] = True
    alpn = [a.strip() for a in (q.get("alpn") or "").split(",") if a.strip() in ("h2", "http/1.1", "h3")]
    if transport_type in ("ws", "httpupgrade"):
        alpn = [a for a in alpn if a == "http/1.1"]
    if alpn:
        tls["alpn"] = alpn
    fp = (q.get("fp") or "").lower()
    if fp in UTLS:
        tls["utls"] = {"enabled": True, "fingerprint": fp}
    if security == "reality":
        pbk = q.get("pbk") or q.get("publickey") or ""
        sid = q.get("sid") or ""
        if not pbk or len(sid) > 16 or not _HEX.match(sid):
            return False
        try:
            if len(b64decode_loose(pbk)) != 32:
                return False
        except (binascii.Error, ValueError):
            return False
        tls.pop("insecure", None)
        tls["reality"] = {"enabled": True, "public_key": pbk, "short_id": sid}
        tls.setdefault("utls", {"enabled": True, "fingerprint": "chrome"})
    return tls


def _transport(net: str, q: dict) -> dict | None | bool:
    """Return transport dict, None for raw TCP, or False if unsupported."""
    net = (net or "tcp").lower()
    header = (q.get("headertype") or q.get("type_header") or "").lower()
    host = _first(q.get("host"))
    path = q.get("path") or "/"
    if net in ("tcp", "raw", "none", ""):
        return False if header == "http" else None
    if net == "ws":
        t: dict = {"type": "ws", "path": path}
        m = re.search(r"[?&]ed=(\d+)", path)
        if m:
            t["path"] = re.sub(r"[?&]ed=\d+", "", path) or "/"
            t["max_early_data"] = int(m.group(1))
            t["early_data_header_name"] = "Sec-WebSocket-Protocol"
        if host:
            t["headers"] = {"Host": host}
        return t
    if net == "httpupgrade":
        t = {"type": "httpupgrade", "path": path}
        if host:
            t["host"] = host
        return t
    if net == "grpc":
        sn = q.get("servicename") or q.get("path") or ""
        return {"type": "grpc", "service_name": sn.strip("/")}
    if net in ("http", "h2"):
        t = {"type": "http", "path": path}
        if host:
            t["host"] = [h.strip() for h in q.get("host", "").split(",") if h.strip()]
        return t
    return False  # xhttp / splithttp / kcp / quic are not supported by sing-box


def _uuid(raw: str) -> str | None:
    raw = raw.strip()
    if _UUID.match(raw):
        return raw.lower()
    if 0 < len(raw) <= 30:  # Xray maps short custom ids with UUIDv5(nil, id)
        return str(uuid.uuid5(uuid.UUID(int=0), raw))
    return None


# --------------------------------------------------------------------------- parsers

def parse_vless(link: str) -> dict | None:
    p = _split_url(link)
    if not p:
        return None
    user, host, port, q, _ = p
    server, port, uid = _host(host), _port(port), _uuid(user)
    if not (server and port and uid):
        return None
    if (q.get("encryption") or "none").lower() not in ("none", ""):
        return None
    transport = _transport(q.get("type", "tcp"), q)
    if transport is False:
        return None
    tls = _tls(q, server, q.get("security", ""), transport and transport["type"])
    if tls is False:
        return None
    cfg = {"type": "vless", "server": server, "server_port": port, "uuid": uid, "packet_encoding": "xudp"}
    flow = (q.get("flow") or "").lower().removesuffix("-udp443")
    if flow == "xtls-rprx-vision":
        if not tls or transport:
            return None
        cfg["flow"] = flow
    elif flow:
        return None
    if tls:
        cfg["tls"] = tls
    if transport:
        cfg["transport"] = transport
    return {"protocol": "vless", "kind": "outbound", "config": cfg, "link": link}


def parse_trojan(link: str) -> dict | None:
    p = _split_url(link)
    if not p:
        return None
    user, host, port, q, _ = p
    server, port = _host(host), _port(port)
    if not (server and port and user) or len(user) > 128:
        return None
    transport = _transport(q.get("type", "tcp"), q)
    if transport is False:
        return None
    tls = _tls(q, server, q.get("security") or "tls", transport and transport["type"])
    if tls is False:
        return None
    cfg = {"type": "trojan", "server": server, "server_port": port, "password": user}
    if tls:
        cfg["tls"] = tls
    if transport:
        cfg["transport"] = transport
    return {"protocol": "trojan", "kind": "outbound", "config": cfg, "link": link}


def parse_vmess(link: str) -> dict | None:
    body = link[len("vmess://"):].split("#", 1)[0]
    try:
        d = json.loads(b64decode_loose(body).decode("utf-8", "ignore"))
        if not isinstance(d, dict):
            return None
    except (ValueError, binascii.Error):
        # "vmess://uuid@host:port?..." style
        p = _split_url(link)
        if not p:
            return None
        user, host, port, q, _ = p
        d = {"add": host, "port": port, "id": user, "net": q.get("type", "tcp"), "tls": q.get("security", ""),
             "host": q.get("host", ""), "path": q.get("path", ""), "sni": q.get("sni", ""),
             "type": q.get("headertype", ""), "scy": q.get("encryption", "auto"), "fp": q.get("fp", ""),
             "alpn": q.get("alpn", ""), "allowInsecure": q.get("allowinsecure", "")}
    d = {str(k).lower(): ("" if v is None else str(v)) for k, v in d.items()}
    server, port, uid = _host(d.get("add")), _port(d.get("port")), _uuid(d.get("id", ""))
    if not (server and port and uid):
        return None
    q = {"host": d.get("host", ""), "path": d.get("path", ""), "headertype": d.get("type", ""),
         "servicename": d.get("servicename") or d.get("path", ""), "sni": d.get("sni", ""),
         "alpn": d.get("alpn", ""), "fp": d.get("fp", ""),
         "allowinsecure": d.get("allowinsecure") or d.get("insecure") or d.get("skip-cert-verify") or ""}
    net = d.get("net") or "tcp"
    transport = _transport(net, q)
    if transport is False:
        return None
    tls = _tls(q, server, d.get("tls") or "", transport and transport["type"])
    if tls is False or (tls and "reality" in tls):
        return None
    scy = (d.get("scy") or "auto").lower()
    if scy not in VMESS_SECURITY:
        scy = "auto"
    try:
        aid = int(d.get("aid") or 0)
    except ValueError:
        aid = 0
    cfg = {"type": "vmess", "server": server, "server_port": port, "uuid": uid, "security": scy,
           "alter_id": max(0, min(aid, 65535)), "packet_encoding": "xudp"}
    if tls:
        cfg["tls"] = tls
    if transport:
        cfg["transport"] = transport
    return {"protocol": "vmess", "kind": "outbound", "config": cfg, "link": link}


def parse_hysteria2(link: str) -> dict | None:
    if link.lower().startswith("hy2://"):
        link = "hysteria2://" + link[6:]
    # port hopping links look like host:20000-30000 which urlsplit rejects
    m = re.match(r"^(hysteria2://[^@/?#]*@[^:/?#\[\]]+|hysteria2://[^@/?#]*@\[[^\]]+\]):(\d+)-(\d+)(.*)$", link, re.I)
    hop = None
    if m:
        hop = f"{m.group(2)}:{m.group(3)}"
        link = f"{m.group(1)}:{m.group(2)}{m.group(4)}"
    p = _split_url(link)
    if not p:
        return None
    user, host, port, q, _ = p
    server, port = _host(host), _port(port) or 443
    if not (server and user):
        return None
    sni = _first(q.get("sni") or q.get("peer"))
    tls: dict = {"enabled": True, "server_name": sni or (server if not _is_ip(server) else "")}
    if not tls["server_name"]:
        tls.pop("server_name")
    if _truthy(q.get("insecure")) or _truthy(q.get("allowinsecure")) or _truthy(q.get("allow_insecure")):
        tls["insecure"] = True
    alpn = [a.strip() for a in (q.get("alpn") or "").split(",") if a.strip()]
    if alpn:
        tls["alpn"] = alpn
    cfg = {"type": "hysteria2", "server": server, "server_port": port, "password": user, "tls": tls}
    obfs = (q.get("obfs") or "").lower()
    if obfs == "salamander":
        if not q.get("obfs-password"):
            return None
        cfg["obfs"] = {"type": "salamander", "password": q["obfs-password"]}
    elif obfs not in ("", "none"):
        return None
    ports = []
    for part in (q.get("mport") or "").split(","):
        part = part.strip().replace("-", ":")
        if re.match(r"^\d+:\d+$", part):
            ports.append(part)
    if hop:
        ports.append(hop)
    if ports:
        cfg["server_ports"] = sorted(set(ports))
    return {"protocol": "hysteria2", "kind": "outbound", "config": cfg, "link": link}


def parse_wireguard(link: str) -> dict | None:
    if link.lower().startswith("wg://"):
        link = "wireguard://" + link[5:]
    p = _split_url(link)
    if not p:
        return None
    user, host, port, q, _ = p
    server, port = _host(host), _port(port)
    priv = user.strip()
    pub = (q.get("publickey") or q.get("public_key") or q.get("peer_public_key") or "").strip()
    if not (server and port and _WG_KEY.match(priv) and _WG_KEY.match(pub)):
        return None
    addrs = []
    for a in (q.get("address") or q.get("ip") or "").split(","):
        a = a.strip()
        if not a:
            continue
        try:
            net = ipaddress.ip_interface(a if "/" in a else a + ("/128" if ":" in a else "/32"))
            addrs.append(str(net))
        except ValueError:
            pass
    if not addrs:
        addrs = ["172.16.0.2/32"]
    peer: dict = {"address": server, "port": port, "public_key": pub, "allowed_ips": ["0.0.0.0/0", "::/0"]}
    psk = (q.get("presharedkey") or q.get("pre_shared_key") or "").strip()
    if psk:
        if not _WG_KEY.match(psk):
            return None
        peer["pre_shared_key"] = psk
    reserved = [r for r in re.split(r"[,\s]+", q.get("reserved") or "") if r]
    if reserved:
        try:
            vals = [int(r) for r in reserved]
            if len(vals) != 3 or not all(0 <= v < 256 for v in vals):
                return None
            peer["reserved"] = vals
        except ValueError:
            return None
    try:
        mtu = int(q.get("mtu") or 1280)
    except ValueError:
        mtu = 1280
    cfg = {"type": "wireguard", "address": addrs, "private_key": priv, "mtu": max(1000, min(mtu, 1500)),
           "peers": [peer]}
    return {"protocol": "wireguard", "kind": "endpoint", "config": cfg, "link": link}


PARSERS = {
    "vless": parse_vless, "vmess": parse_vmess, "trojan": parse_trojan,
    "hysteria2": parse_hysteria2, "hy2": parse_hysteria2,
    "wireguard": parse_wireguard, "wg": parse_wireguard,
}


def parse_link(link: str) -> dict | None:
    scheme = link.split("://", 1)[0].lower()
    fn = PARSERS.get(scheme)
    if not fn:
        return None
    try:
        return fn(link)
    except Exception:  # noqa: BLE001 - junk input must never crash the run
        return None


def node_key(node: dict) -> str:
    """Identity used for de-duplication (ignores remarks)."""
    import hashlib
    return hashlib.sha1(json.dumps(node["config"], sort_keys=True).encode()).hexdigest()[:16]


def clean_link(link: str) -> str:
    """Drop the remark (#...) so advertising names are not republished."""
    if link.lower().startswith("vmess://"):
        return link.split("#", 1)[0]
    return link.split("#", 1)[0]
