"""Convert Clash / Clash.Meta (mihomo) YAML subscriptions into sing-box nodes."""

from __future__ import annotations

import re

from parse import SS_METHODS, _WG_KEY, _host, _is_ip, _port, _tls, _transport, _truthy, _uuid


def _b(v) -> bool:
    return v is True or _truthy(v)


def _common_q(p: dict) -> dict:
    """Translate Clash TLS / transport keys into the share-link style dict the URI helpers expect."""
    q = {
        "sni": str(p.get("servername") or p.get("sni") or ""),
        "allowinsecure": "1" if _b(p.get("skip-cert-verify")) else "",
        "fp": str(p.get("client-fingerprint") or ""),
        "alpn": ",".join(p.get("alpn") or []) if isinstance(p.get("alpn"), list) else str(p.get("alpn") or ""),
    }
    net = str(p.get("network") or "tcp")
    if net == "ws":
        o = p.get("ws-opts") or {}
        q["path"] = str(o.get("path") or "/")
        q["host"] = str((o.get("headers") or {}).get("Host") or "")
        if o.get("max-early-data"):
            q["path"] += f"?ed={o['max-early-data']}"
    elif net == "grpc":
        q["servicename"] = str((p.get("grpc-opts") or {}).get("grpc-service-name") or "")
    elif net in ("h2", "http"):
        o = p.get("h2-opts") or p.get("http-opts") or {}
        hosts = o.get("host") or []
        q["host"] = ",".join(hosts) if isinstance(hosts, list) else str(hosts)
        path = o.get("path") or "/"
        q["path"] = path[0] if isinstance(path, list) else str(path)
        if net == "http":
            net = "http-header"  # plain HTTP header obfuscation: unsupported
    elif net == "httpupgrade":
        o = p.get("http-upgrade-opts") or p.get("ws-opts") or {}
        q["path"] = str(o.get("path") or "/")
        q["host"] = str(o.get("host") or (o.get("headers") or {}).get("Host") or "")
    ro = p.get("reality-opts") or {}
    if ro:
        q["pbk"] = str(ro.get("public-key") or "")
        q["sid"] = str(ro.get("short-id") or "")
    return q | {"_net": net}


def _with_tls_transport(cfg: dict, p: dict, tls_on: bool) -> dict | None:
    q = _common_q(p)
    net = q.pop("_net")
    if net == "http-header":
        return None
    transport = _transport(net, q)
    if transport is False:
        return None
    security = "reality" if q.get("pbk") else ("tls" if tls_on else "")
    tls = _tls(q, cfg["server"], security, transport and transport["type"])
    if tls is False:
        return None
    if tls:
        cfg["tls"] = tls
    if transport:
        cfg["transport"] = transport
    return cfg


def _simple_tls(p: dict, server: str, alpn_default: list[str] | None = None) -> dict:
    tls: dict = {"enabled": True}
    sni = str(p.get("sni") or p.get("servername") or "")
    if sni or not _is_ip(server):
        tls["server_name"] = sni or server
    if _b(p.get("skip-cert-verify")):
        tls["insecure"] = True
    alpn = p.get("alpn") or alpn_default
    if alpn:
        tls["alpn"] = alpn if isinstance(alpn, list) else [str(alpn)]
    return tls


def _mbps(v, default: int) -> int:
    m = re.search(r"\d+", str(v or ""))
    return max(1, int(m.group())) if m else default


def convert(p: dict) -> dict | None:
    t = str(p.get("type") or "").lower()
    server, port = _host(str(p.get("server") or "")), _port(p.get("port"))
    if not (server and port):
        return None
    base = {"server": server, "server_port": port}

    if t == "ss":
        method = str(p.get("cipher") or "").lower()
        method = {"chacha20-poly1305": "chacha20-ietf-poly1305"}.get(method, method)
        if method not in SS_METHODS or not p.get("password"):
            return None
        cfg = {"type": "shadowsocks", **base, "method": method, "password": str(p["password"])}
        plugin, opts = p.get("plugin"), p.get("plugin-opts") or {}
        if plugin == "obfs":
            cfg["plugin"] = "obfs-local"
            cfg["plugin_opts"] = f"obfs={opts.get('mode', 'http')};obfs-host={opts.get('host', '')}"
        elif plugin == "v2ray-plugin":
            parts = [f"mode={opts.get('mode', 'websocket')}"]
            if opts.get("host"):
                parts.append(f"host={opts['host']}")
            if opts.get("path"):
                parts.append(f"path={opts['path']}")
            if _b(opts.get("tls")):
                parts.append("tls")
            cfg["plugin"], cfg["plugin_opts"] = "v2ray-plugin", ";".join(parts)
        elif plugin:
            return None
        return {"protocol": "shadowsocks", "kind": "outbound", "config": cfg}

    if t in ("vmess", "vless"):
        uid = _uuid(str(p.get("uuid") or ""))
        if not uid:
            return None
        if t == "vmess":
            cipher = str(p.get("cipher") or "auto")
            cfg = {"type": "vmess", **base, "uuid": uid, "security": cipher if cipher in (
                "auto", "none", "zero", "aes-128-gcm", "chacha20-poly1305", "aes-128-ctr") else "auto",
                "alter_id": int(p.get("alterId") or 0), "packet_encoding": "xudp"}
        else:
            if str(p.get("encryption") or "none") not in ("none", ""):
                return None
            cfg = {"type": "vless", **base, "uuid": uid, "packet_encoding": "xudp"}
            flow = str(p.get("flow") or "")
            if flow:
                if flow != "xtls-rprx-vision":
                    return None
                cfg["flow"] = flow
        cfg = _with_tls_transport(cfg, p, _b(p.get("tls")))
        if not cfg or (cfg.get("flow") and ("tls" not in cfg or "transport" in cfg)):
            return None
        return {"protocol": t, "kind": "outbound", "config": cfg}

    if t == "trojan":
        if not p.get("password"):
            return None
        cfg = _with_tls_transport({"type": "trojan", **base, "password": str(p["password"])}, p, True)
        return cfg and {"protocol": "trojan", "kind": "outbound", "config": cfg}

    if t == "hysteria2":
        pw = str(p.get("password") or p.get("auth") or "")
        if not pw:
            return None
        cfg = {"type": "hysteria2", **base, "password": pw, "tls": _simple_tls(p, server)}
        if p.get("obfs") == "salamander" and p.get("obfs-password"):
            cfg["obfs"] = {"type": "salamander", "password": str(p["obfs-password"])}
        elif p.get("obfs"):
            return None
        ports = str(p.get("ports") or "")
        hops = [x.strip().replace("-", ":") for x in ports.split(",") if re.match(r"^\d+-\d+$", x.strip())]
        if hops:
            cfg["server_ports"] = hops
        return {"protocol": "hysteria2", "kind": "outbound", "config": cfg}

    if t == "hysteria":
        if str(p.get("protocol") or "udp") != "udp":
            return None
        cfg = {"type": "hysteria", **base, "up_mbps": _mbps(p.get("up"), 20), "down_mbps": _mbps(p.get("down"), 100),
               "tls": _simple_tls(p, server, ["hysteria"])}
        auth = p.get("auth-str") or p.get("auth_str")
        if auth:
            cfg["auth_str"] = str(auth)
        if p.get("obfs"):
            cfg["obfs"] = str(p["obfs"])
        return {"protocol": "hysteria", "kind": "outbound", "config": cfg}

    if t == "tuic":
        uid = str(p.get("uuid") or "")
        if not (_uuid(uid) and p.get("password")):
            return None
        cfg = {"type": "tuic", **base, "uuid": _uuid(uid), "password": str(p["password"]),
               "tls": _simple_tls(p, server, ["h3"])}
        cc = str(p.get("congestion-controller") or "")
        if cc in ("cubic", "new_reno", "bbr"):
            cfg["congestion_control"] = cc
        mode = str(p.get("udp-relay-mode") or "")
        if mode in ("native", "quic"):
            cfg["udp_relay_mode"] = mode
        return {"protocol": "tuic", "kind": "outbound", "config": cfg}

    if t == "anytls":
        if not p.get("password"):
            return None
        cfg = {"type": "anytls", **base, "password": str(p["password"]), "tls": _simple_tls(p, server)}
        return {"protocol": "anytls", "kind": "outbound", "config": cfg}

    if t in ("socks5", "http"):
        cfg = {"type": "socks", **base, "version": "5"} if t == "socks5" else {"type": "http", **base}
        user, pw = str(p.get("username") or ""), str(p.get("password") or "")
        if user and user.lower() not in ("null", "none"):
            cfg["username"] = user
        if pw and pw.lower() not in ("null", "none"):
            cfg["password"] = pw
        if _b(p.get("tls")):
            if t == "socks5":
                return None
            cfg["tls"] = {"enabled": True, "insecure": True}
        return {"protocol": "socks" if t == "socks5" else "http", "kind": "outbound", "config": cfg}

    if t == "snell":
        if not p.get("psk"):
            return None
        version = int(p.get("version") or 2)
        if version not in (1, 2, 3, 4, 5):
            return None
        cfg = {"type": "snell", **base, "psk": str(p["psk"]), "version": version}
        obfs = p.get("obfs-opts") or {}
        if obfs.get("mode") in ("http", "tls"):
            cfg["obfs_mode"] = obfs["mode"]
            if obfs.get("host"):
                cfg["obfs_host"] = str(obfs["host"])
        return {"protocol": "snell", "kind": "outbound", "config": cfg}

    if t == "ssh":
        if not (p.get("username") and p.get("password")):
            return None
        cfg = {"type": "ssh", **base, "user": str(p["username"]), "password": str(p["password"])}
        return {"protocol": "ssh", "kind": "outbound", "config": cfg}

    if t == "wireguard":
        priv, pub = str(p.get("private-key") or ""), str(p.get("public-key") or "")
        if not (_WG_KEY.match(priv) and _WG_KEY.match(pub)):
            return None
        addrs = []
        if p.get("ip"):
            addrs.append(str(p["ip"]) if "/" in str(p["ip"]) else f"{p['ip']}/32")
        if p.get("ipv6"):
            addrs.append(str(p["ipv6"]) if "/" in str(p["ipv6"]) else f"{p['ipv6']}/128")
        peer = {"address": server, "port": port, "public_key": pub, "allowed_ips": ["0.0.0.0/0", "::/0"]}
        if p.get("pre-shared-key"):
            peer["pre_shared_key"] = str(p["pre-shared-key"])
        res = p.get("reserved")
        if isinstance(res, list) and len(res) == 3:
            peer["reserved"] = [int(x) for x in res]
        cfg = {"type": "wireguard", "address": addrs or ["172.16.0.2/32"], "private_key": priv,
               "mtu": int(p.get("mtu") or 1280), "peers": [peer]}
        return {"protocol": "wireguard", "kind": "endpoint", "config": cfg}

    return None  # ssr and anything sing-box cannot run


def nodes_from_yaml(text: str) -> list[dict]:
    if "proxies:" not in text:
        return []
    try:
        import yaml
        data = yaml.safe_load(text)
    except Exception:  # noqa: BLE001
        return []
    proxies = data.get("proxies") if isinstance(data, dict) else None
    out = []
    for p in proxies or []:
        if not isinstance(p, dict):
            continue
        try:
            node = convert(p)
        except Exception:  # noqa: BLE001
            node = None
        if node:
            node["link"] = ""
            out.append(node)
    return out
