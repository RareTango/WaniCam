"""Minimal ONVIF client: device info, profiles, stream and snapshot URIs.

Uses WS-Security UsernameToken (digest) with the credentials the user enters,
falling back to HTTP Digest auth which some cameras require instead.
"""
import base64
import datetime as dt
import hashlib
import os
import re
import urllib.request
import xml.etree.ElementTree as ET

NS_DEV = "http://www.onvif.org/ver10/device/wsdl"
NS_MEDIA = "http://www.onvif.org/ver10/media/wsdl"
NS_MEDIA2 = "http://www.onvif.org/ver20/media/wsdl"
NS_SCHEMA = "http://www.onvif.org/ver10/schema"


class OnvifError(Exception):
    pass


def _local(tag):
    return tag.rsplit("}", 1)[-1]


def _find_all(el, name):
    return [e for e in el.iter() if _local(e.tag) == name]


def _find(el, name):
    for e in el.iter():
        if _local(e.tag) == name:
            return e
    return None


def _text(el, name):
    e = _find(el, name)
    return e.text.strip() if e is not None and e.text else None


class Onvif:
    def __init__(self, xaddr, user="", password="", timeout=5):
        self.device_url = xaddr
        self.user, self.password, self.timeout = user, password, timeout
        self.offset = dt.timedelta(0)
        self.media_url = None
        self.media2_url = None

    # -- transport
    def _security(self):
        if not self.user:
            return ""
        nonce = os.urandom(16)
        created = (dt.datetime.now(dt.timezone.utc) + self.offset).strftime("%Y-%m-%dT%H:%M:%S.000Z")
        digest = base64.b64encode(hashlib.sha1(nonce + created.encode() + self.password.encode()).digest()).decode()
        return ('<s:Header><Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/'
                'oasis-200401-wss-wssecurity-secext-1.0.xsd"><UsernameToken>'
                f'<Username>{_xml_escape(self.user)}</Username>'
                '<Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0'
                f'#PasswordDigest">{digest}</Password>'
                '<Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0'
                f'#Base64Binary">{base64.b64encode(nonce).decode()}</Nonce>'
                '<Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">'
                f'{created}</Created></UsernameToken></Security></s:Header>')

    def _call(self, url, body, auth=True):
        env = ('<?xml version="1.0" encoding="UTF-8"?>'
               '<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" '
               f'xmlns:tt="{NS_SCHEMA}">{self._security() if auth else ""}<s:Body>{body}</s:Body></s:Envelope>')
        headers = {"Content-Type": "application/soap+xml; charset=utf-8"}
        handlers = []
        if auth and self.user:
            pm = urllib.request.HTTPPasswordMgrWithDefaultRealm()
            pm.add_password(None, url, self.user, self.password)
            handlers = [urllib.request.HTTPDigestAuthHandler(pm), urllib.request.HTTPBasicAuthHandler(pm)]
        opener = urllib.request.build_opener(*handlers)
        req = urllib.request.Request(url, data=env.encode(), headers=headers, method="POST")
        try:
            with opener.open(req, timeout=self.timeout) as r:
                data = r.read()
        except urllib.error.HTTPError as e:
            data = e.read()
            if e.code in (401, 403) and b"Fault" not in data:
                raise OnvifError("Camera rejected the username or password (HTTP %d)" % e.code)
            if b"Fault" not in data:
                raise OnvifError(f"HTTP {e.code} from {url}")
        except Exception as e:
            raise OnvifError(f"Could not reach {url}: {e}")
        root = ET.fromstring(data)
        fault = _find(root, "Fault")
        if fault is not None:
            reason = " ".join(t.strip() for t in fault.itertext() if t.strip())
            if re.search(r"NotAuthorized|authority|Sender", reason, re.I):
                raise OnvifError("Not authorized: check the camera username and password. " + reason[:200])
            raise OnvifError(reason[:300])
        return root

    # -- operations
    def sync_time(self):
        try:
            root = self._call(self.device_url, f'<GetSystemDateAndTime xmlns="{NS_DEV}"/>', auth=False)
            utc = _find(root, "UTCDateTime")
            if utc is None:
                return
            d, t = _find(utc, "Date"), _find(utc, "Time")
            cam = dt.datetime(int(_text(d, "Year")), int(_text(d, "Month")), int(_text(d, "Day")),
                              int(_text(t, "Hour")), int(_text(t, "Minute")), int(_text(t, "Second")),
                              tzinfo=dt.timezone.utc)
            self.offset = cam - dt.datetime.now(dt.timezone.utc)
        except Exception:
            pass

    def device_info(self):
        root = self._call(self.device_url, f'<GetDeviceInformation xmlns="{NS_DEV}"/>')
        return {k: _text(root, k) for k in ("Manufacturer", "Model", "FirmwareVersion", "SerialNumber")}

    def find_services(self):
        try:
            root = self._call(self.device_url,
                              f'<GetServices xmlns="{NS_DEV}"><IncludeCapability>false</IncludeCapability></GetServices>')
            for svc in _find_all(root, "Service"):
                ns, xa = _text(svc, "Namespace"), _text(svc, "XAddr")
                if ns == NS_MEDIA:
                    self.media_url = xa
                elif ns == NS_MEDIA2:
                    self.media2_url = xa
        except OnvifError:
            pass
        if not self.media_url:
            try:
                root = self._call(self.device_url,
                                  f'<GetCapabilities xmlns="{NS_DEV}"><Category>All</Category></GetCapabilities>')
                media = _find(root, "Media")
                if media is not None:
                    self.media_url = _text(media, "XAddr")
            except OnvifError:
                pass
        if not self.media_url and not self.media2_url:
            self.media_url = self.device_url
        # Some cameras report an internal IP in XAddrs; rewrite to the host we reached.
        host = re.match(r"(\w+://[^/]+)", self.device_url).group(1)
        for attr in ("media_url", "media2_url"):
            v = getattr(self, attr)
            if v:
                setattr(self, attr, re.sub(r"^\w+://[^/]+", host, v))

    def streams(self):
        """Return a list of {profile, token, resolution, encoding, rtsp, snapshot}."""
        self.sync_time()
        self.find_services()
        out = []
        if self.media_url:
            try:
                root = self._call(self.media_url, f'<GetProfiles xmlns="{NS_MEDIA}"/>')
                for p in _find_all(root, "Profiles"):
                    token = p.get("token")
                    enc = _find(p, "VideoEncoderConfiguration")
                    item = {"profile": _text(p, "Name") or token, "token": token,
                            "encoding": _text(enc, "Encoding") if enc is not None else None,
                            "resolution": None}
                    if enc is not None and _find(enc, "Resolution") is not None:
                        r = _find(enc, "Resolution")
                        item["resolution"] = f"{_text(r, 'Width')}x{_text(r, 'Height')}"
                    try:
                        sr = self._call(self.media_url, (
                            f'<GetStreamUri xmlns="{NS_MEDIA}"><StreamSetup><tt:Stream>RTP-Unicast</tt:Stream>'
                            '<tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></StreamSetup>'
                            f'<ProfileToken>{_xml_escape(token)}</ProfileToken></GetStreamUri>'))
                        item["rtsp"] = _text(sr, "Uri")
                    except OnvifError as e:
                        item["rtsp_error"] = str(e)
                    try:
                        sn = self._call(self.media_url,
                                        f'<GetSnapshotUri xmlns="{NS_MEDIA}"><ProfileToken>{_xml_escape(token)}'
                                        '</ProfileToken></GetSnapshotUri>')
                        item["snapshot"] = _text(sn, "Uri")
                    except OnvifError:
                        pass
                    out.append(item)
            except OnvifError:
                if not self.media2_url:
                    raise
        if not out and self.media2_url:
            root = self._call(self.media2_url, f'<GetProfiles xmlns="{NS_MEDIA2}"><Type>All</Type></GetProfiles>')
            for p in _find_all(root, "Profiles"):
                token = p.get("token")
                item = {"profile": _text(p, "Name") or token, "token": token}
                try:
                    sr = self._call(self.media2_url, f'<GetStreamUri xmlns="{NS_MEDIA2}"><Protocol>RTSP</Protocol>'
                                    f'<ProfileToken>{_xml_escape(token)}</ProfileToken></GetStreamUri>')
                    item["rtsp"] = _text(sr, "Uri")
                except OnvifError as e:
                    item["rtsp_error"] = str(e)
                out.append(item)
        return out


def _xml_escape(s):
    return (s or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def default_xaddrs(host, ports=(80, 8899, 2020, 8000, 8080)):
    return [f"http://{host}:{p}/onvif/device_service" for p in ports]


def lookup(host=None, xaddrs=None, user="", password="", extra_ports=()):
    """Try each device-service address until one answers. Returns dict."""
    errors = []
    addrs = list(xaddrs or [])
    if host:
        ports = list(extra_ports) + [p for p in (80, 8899, 2020, 8000, 8080) if p not in extra_ports]
        addrs += [a for a in default_xaddrs(host, ports) if a not in addrs]
    for xa in addrs:
        cli = Onvif(xa, user, password)
        try:
            streams = cli.streams()
            info = {}
            try:
                info = cli.device_info()
            except OnvifError:
                pass
            return {"ok": True, "xaddr": xa, "info": info, "streams": streams}
        except OnvifError as e:
            errors.append(f"{xa}: {e}")
            if "Not authorized" in str(e) or "rejected" in str(e):
                break  # Right endpoint, wrong credentials: don't keep trying.
    return {"ok": False, "errors": errors}
