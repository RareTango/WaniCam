"""Saved cameras, kept in a JSON file readable only by the current user."""
import json
import os
import threading
import uuid
from pathlib import Path

PUBLIC_FIELDS = ("id", "name", "kind", "url", "user", "brand", "host", "device", "transport",
                 "fps", "width", "notes")


class Store:
    def __init__(self, path):
        self.path = Path(path)
        self.lock = threading.Lock()
        self.cams = {}
        if self.path.exists():
            try:
                for c in json.loads(self.path.read_text()).get("cameras", []):
                    self.cams[c["id"]] = c
            except (ValueError, KeyError):
                pass

    def _save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump({"cameras": list(self.cams.values())}, f, indent=2)
        os.replace(tmp, self.path)

    def list(self):
        with self.lock:
            return [self.public(c) for c in self.cams.values()]

    @staticmethod
    def public(c):
        d = {k: c.get(k) for k in PUBLIC_FIELDS if c.get(k) not in (None, "")}
        d["has_password"] = bool(c.get("password"))
        return d

    def get(self, cid):
        with self.lock:
            c = self.cams.get(cid)
            return dict(c) if c else None

    def upsert(self, data):
        with self.lock:
            cid = data.get("id") or uuid.uuid4().hex[:10]
            old = self.cams.get(cid, {})
            c = {**old, **{k: v for k, v in data.items() if k in PUBLIC_FIELDS or k == "password"}}
            c["id"] = cid
            # Empty password field on edit means "keep the saved one".
            if data.get("password") in (None, "") and "password" in old and not data.get("clear_password"):
                c["password"] = old["password"]
            if data.get("clear_password"):
                c["password"] = ""
            c.setdefault("name", c.get("host") or c.get("url") or "Camera")
            self.cams[cid] = c
            self._save()
            return dict(c)

    def delete(self, cid):
        with self.lock:
            if self.cams.pop(cid, None) is not None:
                self._save()
                return True
            return False
