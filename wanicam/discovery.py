"""Network discovery: ONVIF WS-Discovery, SSDP/UPnP, mDNS and a camera port scan.

Everything here only listens for announcements or checks whether a port is
open and reads public banners (RTSP OPTIONS, HTTP Server header). No logins
are attempted.
"""
import asyncio
import ipaddress
import re
import socket
import struct
import time
import uuid
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from . import brands

CAMERA_PORTS = {
    554: "rtsp", 8554: "rtsp", 10554: "rtsp", 7447: "rtsp (UniFi)", 5554: "rtsp",
    80: "http", 8080: "http", 8000: "http/Hik SDK", 81: "http", 88: "http (Foscam)",
    443: "https", 8443: "https", 8899: "onvif", 2020: "onvif (Tapo)", 37777: "Dahua SDK",
    34567: "XMeye/NetSurveillance", 9000: "Reolink", 1935: "rtmp", 8081: "http", 5000: "http",
}
RTSP_PORTS = {554, 8554, 10554, 7447, 5554}
HTTP_PORTS = {80, 8080, 8000, 81, 88, 8081, 5000, 8899, 2020}


# ---------------------------------------------------------------- helpers

def local_ipv4s():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    return sorted(ip for ip in ips if not ip.startswith("127."))


def default_subnets():
    nets = []
    for ip in local_ipv4s():
        n = ipaddress.ip_network(f"{ip}/24", strict=False)
        if n.is_private and str(n) not in nets:
            nets.append(str(n))
    return nets


def _host_of(url):
    m = re.match(r"\w+://(?:[^@/]*@)?\[?([^\]/:]+)", url or "")
    return m.group(1) if m else None


# ---------------------------------------------------------------- WS-Discovery

WSD_PROBE = """<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope"
 xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing"
 xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
 xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
<e:Header><w:MessageID>uuid:{uid}</w:MessageID>
<w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
<w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>
</e:Header><e:Body><d:Probe><d:Types>{types}</d:Types></d:Probe></e:Body></e:Envelope>"""


def ws_discovery(timeout=3.0):
    found = {}
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
    sock.settimeout(0.5)
    try:
        sock.bind(("", 0))
        for types in ("dn:NetworkVideoTransmitter", "", "tds:Device"):
            msg = WSD_PROBE.format(uid=uuid.uuid4(), types=types)
            if types == "tds:Device":
                msg = msg.replace('xmlns:dn=', 'xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:dn=')
            for dst in ("239.255.255.250",):
                try:
                    sock.sendto(msg.encode(), (dst, 3702))
                except OSError:
                    pass
        end = time.time() + timeout
        while time.time() < end:
            try:
                data, addr = sock.recvfrom(65535)
            except socket.timeout:
                continue
            except OSError:
                break
            text = data.decode("utf-8", "replace")
            xaddrs = re.findall(r"<[^>]*XAddrs>([^<]+)<", text)
            scopes = " ".join(re.findall(r"<[^>]*Scopes[^>]*>([^<]+)<", text))
            urls = xaddrs[0].split() if xaddrs else []
            info = {"ip": addr[0], "source": "onvif", "onvif_xaddrs": urls}
            meta = {}
            for sc in scopes.split():
                m = re.match(r"onvif://www\.onvif\.org/(name|hardware|location/\w+|manufacturer|mac)/(.+)", sc)
                if m:
                    meta[m.group(1).split("/")[0]] = urllib.request.unquote(m.group(2))
            info["name"] = meta.get("name")
            info["model"] = meta.get("hardware")
            info["manufacturer"] = meta.get("manufacturer")
            found.setdefault(addr[0], info)
    finally:
        sock.close()
    return list(found.values())


# ---------------------------------------------------------------- SSDP

def ssdp_discovery(timeout=3.0):
    found = {}
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
    sock.settimeout(0.5)
    try:
        sock.bind(("", 0))
        for st in ("ssdp:all", "urn:schemas-upnp-org:device:Basic:1",
                   "urn:schemas-upnp-org:device:NetworkCamera:1"):
            msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
                   f"MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: {st}\r\n\r\n")
            try:
                sock.sendto(msg.encode(), ("239.255.255.250", 1900))
            except OSError:
                pass
        end = time.time() + timeout
        while time.time() < end:
            try:
                data, addr = sock.recvfrom(65535)
            except socket.timeout:
                continue
            except OSError:
                break
            hdr = {}
            for line in data.decode("utf-8", "replace").split("\r\n")[1:]:
                if ":" in line:
                    k, v = line.split(":", 1)
                    hdr[k.strip().lower()] = v.strip()
            e = found.setdefault(addr[0], {"ip": addr[0], "source": "ssdp", "ssdp_server": hdr.get("server"),
                                           "ssdp_location": hdr.get("location")})
            if not e.get("ssdp_location") and hdr.get("location"):
                e["ssdp_location"] = hdr["location"]
    finally:
        sock.close()

    def fetch_desc(e):
        loc = e.get("ssdp_location")
        if not loc:
            return
        try:
            with urllib.request.urlopen(loc, timeout=2) as r:
                x = r.read(65536).decode("utf-8", "replace")
            for tag, key in (("manufacturer", "manufacturer"), ("modelName", "model"),
                             ("friendlyName", "name"), ("deviceType", "device_type")):
                m = re.search(rf"<{tag}>([^<]*)</{tag}>", x)
                if m:
                    e[key] = m.group(1).strip()
        except Exception:
            pass

    with ThreadPoolExecutor(16) as ex:
        list(ex.map(fetch_desc, found.values()))
    return list(found.values())


# ---------------------------------------------------------------- mDNS

MDNS_SERVICES = ["_rtsp._tcp.local", "_axis-video._tcp.local", "_onvif._tcp.local",
                 "_camera._tcp.local", "_hap._tcp.local", "_http._tcp.local", "_nvr._tcp.local"]


def _dns_name(data, off):
    labels, jumped, ret = [], False, off
    for _ in range(64):
        if off >= len(data):
            break
        ln = data[off]
        if ln == 0:
            off += 1
            break
        if ln & 0xC0 == 0xC0:
            ptr = ((ln & 0x3F) << 8) | data[off + 1]
            if not jumped:
                ret = off + 2
            jumped = True
            off = ptr
            continue
        labels.append(data[off + 1:off + 1 + ln].decode("utf-8", "replace"))
        off += 1 + ln
    return ".".join(labels), (ret if jumped else off)


def _parse_mdns(data):
    recs = []
    try:
        _, _, qd, an, ns, ar = struct.unpack("!HHHHHH", data[:12])
        off = 12
        for _ in range(qd):
            _, off = _dns_name(data, off)
            off += 4
        for _ in range(an + ns + ar):
            name, off = _dns_name(data, off)
            rtype, _, _, rdlen = struct.unpack("!HHIH", data[off:off + 10])
            off += 10
            rd = data[off:off + rdlen]
            if rtype == 12:
                recs.append(("PTR", name, _dns_name(data, off)[0]))
            elif rtype == 1 and rdlen == 4:
                recs.append(("A", name, socket.inet_ntoa(rd)))
            elif rtype == 33:
                port = struct.unpack("!H", rd[4:6])[0]
                recs.append(("SRV", name, (port, _dns_name(data, off + 6)[0])))
            elif rtype == 16:
                txt, i = [], 0
                while i < len(rd):
                    l = rd[i]
                    txt.append(rd[i + 1:i + 1 + l].decode("utf-8", "replace"))
                    i += 1 + l
                recs.append(("TXT", name, txt))
            off += rdlen
    except Exception:
        pass
    return recs


def mdns_discovery(timeout=3.0):
    q = struct.pack("!HHHHHH", 0, 0, len(MDNS_SERVICES), 0, 0, 0)
    for s in MDNS_SERVICES:
        q += b"".join(bytes([len(p)]) + p.encode() for p in s.split(".")) + b"\x00"
        q += struct.pack("!HH", 12, 0x8001)  # PTR, unicast-response bit
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
    sock.settimeout(0.5)
    found = {}
    try:
        sock.bind(("", 0))
        sock.sendto(q, ("224.0.0.251", 5353))
        end = time.time() + timeout
        while time.time() < end:
            try:
                data, addr = sock.recvfrom(9000)
            except socket.timeout:
                continue
            except OSError:
                break
            recs = _parse_mdns(data)
            services = [r[2] for r in recs if r[0] == "PTR" and any(r[1] == s for s in MDNS_SERVICES)]
            camera_like = [s for s in services if not s.endswith("_http._tcp.local")]
            txt = " ".join(" ".join(r[2]) for r in recs if r[0] == "TXT")
            if not services:
                continue
            if not camera_like and not brands.guess_brand(txt + " ".join(services)) \
                    and "cam" not in (txt + " ".join(services)).lower():
                continue
            e = found.setdefault(addr[0], {"ip": addr[0], "source": "mdns", "mdns_services": []})
            for s in services:
                if s not in e["mdns_services"]:
                    e["mdns_services"].append(s)
            if services and not e.get("name"):
                e["name"] = services[0].split("._")[0]
            for r in recs:
                if r[0] == "SRV" and "_rtsp._tcp" in r[1]:
                    e["rtsp_port"] = r[2][0]
            if "_hap._tcp" in " ".join(services):
                e["note"] = "HomeKit device: HomeKit cameras do not expose a standard stream"
            e["mdns_txt"] = txt[:300]
    finally:
        sock.close()
    return list(found.values())


# ---------------------------------------------------------------- port scan

async def _probe_port(ip, port, sem, timeout):
    async with sem:
        try:
            r, w = await asyncio.wait_for(asyncio.open_connection(ip, port), timeout)
        except Exception:
            return None
        banner = None
        try:
            if port not in HTTP_PORTS:
                w.write(f"OPTIONS rtsp://{ip}:{port}/ RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: WaniCam\r\n\r\n".encode())
                await w.drain()
                data = await asyncio.wait_for(r.read(1024), 1.5)
                banner = data.decode("utf-8", "replace")
            elif port in HTTP_PORTS:
                w.write(f"GET / HTTP/1.0\r\nHost: {ip}\r\nUser-Agent: WaniCam\r\n\r\n".encode())
                await w.drain()
                data = await asyncio.wait_for(r.read(4096), 1.5)
                banner = data.decode("utf-8", "replace")
        except Exception:
            pass
        finally:
            try:
                w.close()
            except Exception:
                pass
        return ip, port, banner


async def _scan(hosts, ports, timeout, concurrency):
    sem = asyncio.Semaphore(concurrency)
    tasks = [_probe_port(h, p, sem, timeout) for h in hosts for p in ports]
    return [r for r in await asyncio.gather(*tasks) if r]


def port_scan(subnets=None, ports=None, timeout=0.6, concurrency=300, max_hosts=4096):
    subnets = subnets or default_subnets()
    ports = ports or list(CAMERA_PORTS)
    me = set(local_ipv4s())
    hosts = []
    for s in subnets:
        net = ipaddress.ip_network(s, strict=False)
        for h in net.hosts() if net.num_addresses > 1 else [net.network_address]:
            if str(h) not in me:
                hosts.append(str(h))
            if len(hosts) >= max_hosts:
                break
    results = asyncio.run(_scan(hosts, ports, timeout, concurrency))
    by_ip = {}
    for ip, port, banner in results:
        e = by_ip.setdefault(ip, {"ip": ip, "source": "scan", "open_ports": [], "banners": {}})
        e["open_ports"].append(port)
        if banner:
            e["banners"][port] = _summarize_banner(banner)
            if banner.startswith("RTSP/"):
                e.setdefault("rtsp_ports", []).append(port)
    out = []
    for e in by_ip.values():
        ports_open = set(e["open_ports"])
        rtsp = sorted(set(e.pop("rtsp_ports", [])) | (ports_open & RTSP_PORTS))
        camera_ports = set(rtsp) | (ports_open & {8899, 2020, 37777, 34567, 8000, 9000, 7447})
        text = " ".join(e["banners"].values())
        is_cam = bool(camera_ports) or bool(brands.guess_brand(text)) or \
            re.search(r"camera|webcam|ipcam|nvr|dvr|mjpeg|onvif|rtsp", text, re.I)
        if not is_cam:
            continue
        e["open_ports"].sort()
        if rtsp:
            e["rtsp_port"] = rtsp[0]
        http = sorted(ports_open & HTTP_PORTS)
        if http:
            e["http_port"] = 80 if 80 in http else http[0]
        out.append(e)
    return out


def _summarize_banner(b):
    parts = []
    m = re.search(r"^Server:\s*(.+)$", b, re.I | re.M)
    if m:
        parts.append(m.group(1).strip())
    m = re.search(r"<title>([^<]{1,80})</title>", b, re.I)
    if m:
        parts.append(m.group(1).strip())
    m = re.search(r'realm="([^"]+)"', b, re.I)
    if m:
        parts.append("realm=" + m.group(1))
    if not parts:
        parts.append(b.split("\r\n", 1)[0][:80])
    return " | ".join(parts)


# ---------------------------------------------------------------- combined

def discover_all(subnets=None, scan=True, timeout=3.0):
    jobs = {"onvif": lambda: ws_discovery(timeout), "ssdp": lambda: ssdp_discovery(timeout),
            "mdns": lambda: mdns_discovery(timeout)}
    if scan:
        jobs["scan"] = lambda: port_scan(subnets)
    results, errors = {}, {}
    with ThreadPoolExecutor(len(jobs)) as ex:
        futs = {k: ex.submit(f) for k, f in jobs.items()}
        for k, f in futs.items():
            try:
                results[k] = f.result()
            except Exception as e:  # keep other methods working
                results[k] = []
                errors[k] = str(e)

    merged = {}
    for method in ("onvif", "mdns", "ssdp", "scan"):
        for d in results.get(method, []):
            e = merged.setdefault(d["ip"], {"ip": d["ip"], "sources": []})
            e["sources"].append(method)
            for k, v in d.items():
                if k in ("source", "ip") or v in (None, "", [], {}):
                    continue
                e.setdefault(k, v)

    devices = []
    for e in merged.values():
        # SSDP alone finds TVs, routers, etc. Keep only those that look like cameras.
        if e["sources"] == ["ssdp"]:
            txt = " ".join(str(e.get(k, "")) for k in ("manufacturer", "model", "name", "device_type", "ssdp_server"))
            if not (brands.guess_brand(txt) or re.search(r"camera|ipcam|nvr|dvr|video", txt, re.I)):
                continue
        txt = " ".join(str(v) for k, v in e.items() if k not in ("sources",))
        e["brand"] = brands.guess_brand(txt) or ("generic")
        e.setdefault("rtsp_port", brands.BRANDS[e["brand"]]["rtsp_port"])
        e.setdefault("http_port", brands.BRANDS[e["brand"]]["http_port"])
        e["onvif"] = bool(e.get("onvif_xaddrs")) or 8899 in e.get("open_ports", []) or 2020 in e.get("open_ports", [])
        devices.append(e)
    devices.sort(key=lambda d: tuple(int(x) for x in d["ip"].split(".")) if d["ip"].count(".") == 3 else (999,))
    return {"devices": devices, "errors": errors, "subnets": subnets or default_subnets()}
