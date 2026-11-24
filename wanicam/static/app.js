"use strict";
// WaniCam dashboard. All text from cameras/network goes through textContent.

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];

function h(tag, attrs = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "class") el.className = v;
    else el.setAttribute(k, v === true ? "" : v);
  }
  for (const k of kids.flat()) if (k != null) el.append(k instanceof Node ? k : String(k));
  return el;
}

async function api(path, body, method) {
  const opt = body !== undefined ? { method: method || "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }
                                 : { method: method || "GET" };
  const r = await fetch(path, opt);
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
  return j;
}

const pref = {
  get(k, d) { try { return localStorage.getItem("wanicam." + k) ?? d; } catch { return d; } },
  set(k, v) { try { localStorage.setItem("wanicam." + k, v); } catch {} },
};

let BRANDS = [];
let INFO = {};

// ------------------------------------------------------------ tabs
function show(view) {
  $$(".tab").forEach(b => b.classList.toggle("active", b.dataset.view === view));
  $$(".view").forEach(v => v.hidden = v.id !== "view-" + view);
  if (view === "cameras") loadCameras(); else stopTiles();
}
$$(".tab").forEach(b => b.onclick = () => show(b.dataset.view));
function showSub(sub) {
  $$(".subtab").forEach(b => b.classList.toggle("active", b.dataset.sub === sub));
  $$(".sub").forEach(v => v.hidden = v.id !== "sub-" + sub);
  if (sub === "local") loadLocal();
}
$$(".subtab").forEach(b => b.onclick = () => showSub(b.dataset.sub));

// ------------------------------------------------------------ camera grid
const grid = $("#grid");
for (const id of ["layout", "fps", "width"]) {
  const el = $("#" + id);
  el.value = pref.get(id, el.value);
  el.onchange = () => { pref.set(id, el.value); applyLayout(); if (id !== "layout") loadCameras(); };
}
function applyLayout() { grid.style.setProperty("--cols", $("#layout").value); }
applyLayout();

function streamUrl(cam) {
  const q = new URLSearchParams({ fps: $("#fps").value, t: Date.now() });
  if ($("#width").value) q.set("w", $("#width").value);
  return `/stream/${cam.id}.mjpg?${q}`;
}

function stopTiles() {
  // Dropping the <img> src closes the connection so the server can stop ffmpeg.
  $$("#grid img").forEach(i => { i.removeAttribute("src"); });
}

async function loadCameras() {
  let data;
  try { data = await api("/api/cameras"); } catch (e) { return banner(e.message); }
  stopTiles();
  $$("#grid .tile:not(.webcam)").forEach(t => t.remove());
  $("#empty").hidden = data.cameras.length > 0 || !!$("#grid .webcam");
  const firstWebcam = $("#grid .webcam");
  for (const cam of data.cameras) grid.insertBefore(tile(cam), firstWebcam);
}

function tile(cam) {
  const msg = h("div", { class: "msg" }, "Connecting…");
  const img = h("img", { alt: cam.name || "" });
  let retry = 0;
  // MJPEG streams don't reliably fire "load", so watch for the first frame instead.
  const watch = setInterval(() => {
    if (!img.isConnected) return clearInterval(watch);
    if (img.naturalWidth > 0 && img.style.visibility !== "hidden") { msg.hidden = true; retry = 0; }
  }, 500);
  img.onload = () => { img.style.visibility = ""; msg.hidden = true; retry = 0; };
  img.onerror = async () => {
    img.style.visibility = "hidden";
    msg.hidden = false;
    // Ask the server why (an <img> can't read the error body).
    try {
      const r = await fetch(streamUrl(cam));
      if (!r.ok) {
        const j = await r.json().catch(() => ({}));
        msg.textContent = j.error || "Stream unavailable";
        if (/rejected/.test(j.error || "")) { msg.textContent += ". Click Edit to fix the login."; return; }
      }
      else { r.body?.cancel(); msg.textContent = "Reconnecting…"; }
    } catch { msg.textContent = "Stream unavailable"; }
    if (retry++ < 5 && !$("#view-cameras").hidden) setTimeout(() => { img.style.visibility = ""; img.src = streamUrl(cam); }, 3000 * retry);
  };
  img.src = streamUrl(cam);
  const kind = { rtsp: "RTSP", mjpeg: "MJPEG", snapshot: "Snapshot", local: "USB", ffmpeg: "Stream" }[cam.kind] || cam.kind;
  return h("div", { class: "tile", "data-id": cam.id },
    h("div", { class: "video" }, img, msg),
    h("div", { class: "bar" },
      h("span", { class: "name", title: cam.url || cam.device || "" }, cam.name || cam.host || "Camera"),
      h("span", { class: "pill" }, kind),
      h("button", { class: "secondary", title: "Full screen", onclick: () => openViewer(cam) }, "Expand"),
      h("a", { href: `/snapshot/${cam.id}.jpg`, download: `${(cam.name || "camera").replace(/\W+/g, "_")}.jpg` },
        h("button", { class: "secondary", title: "Save a snapshot" }, "Snapshot")),
      h("button", { class: "secondary", onclick: () => editCamera(cam) }, "Edit"),
      h("button", { class: "secondary danger", onclick: () => removeCamera(cam) }, "Remove"),
    ));
}

function openViewer(cam) {
  const d = $("#viewer");
  $("#viewer-title").textContent = cam.name || cam.host || "";
  const q = new URLSearchParams({ fps: 15, t: Date.now() });
  $("#viewer-img").src = `/stream/${cam.id}.mjpg?${q}`;
  d.showModal();
}
$("#viewer-close").onclick = () => $("#viewer").close();
$("#viewer").addEventListener("close", () => $("#viewer-img").removeAttribute("src"));

async function removeCamera(cam) {
  if (!confirm(`Remove "${cam.name}" from WaniCam? (This does not change the camera.)`)) return;
  await api(`/api/cameras/${cam.id}`, undefined, "DELETE");
  loadCameras();
}

function editCamera(cam) {
  show("add"); showSub("manual");
  const f = $("#manual-form");
  f.reset();
  for (const k of ["id", "url", "name", "kind", "user", "transport", "fps"]) if (f[k]) f[k].value = cam[k] ?? "";
  f.password.placeholder = cam.has_password ? "(saved – leave blank to keep)" : "";
  if (cam.kind === "local") { showSub("local"); }
}

// Browser webcam (the computer you're browsing from)
$("#webcam-btn").onclick = async () => {
  if (!navigator.mediaDevices?.getUserMedia) return banner("This browser can't access webcams here. Open WaniCam at http://localhost.");
  try {
    const first = await navigator.mediaDevices.getUserMedia({ video: true });
    first.getTracks().forEach(t => t.stop());
    const devs = (await navigator.mediaDevices.enumerateDevices()).filter(d => d.kind === "videoinput");
    $$("#grid .webcam").forEach(t => { t.querySelector("video").srcObject?.getTracks().forEach(x => x.stop()); t.remove(); });
    for (const d of devs) {
      const stream = await navigator.mediaDevices.getUserMedia({ video: { deviceId: { exact: d.deviceId } } });
      const v = h("video", { autoplay: true, muted: true, playsinline: true });
      v.srcObject = stream;
      const t = h("div", { class: "tile webcam" }, h("div", { class: "video" }, v),
        h("div", { class: "bar" }, h("span", { class: "name" }, d.label || "Webcam"), h("span", { class: "pill" }, "Browser"),
          h("button", { class: "secondary", onclick: () => { stream.getTracks().forEach(x => x.stop()); t.remove(); } }, "Close")));
      grid.append(t);
    }
    $("#empty").hidden = true;
  } catch (e) { banner("Webcam access was blocked or no webcam was found: " + e.message); }
};

// ------------------------------------------------------------ discovery
$("#discover-btn").onclick = async () => {
  const btn = $("#discover-btn"), st = $("#discover-status");
  btn.disabled = true;
  st.textContent = "Scanning… this takes 10 to 60 seconds depending on network size.";
  try {
    const subnets = $("#subnets").value.split(",").map(s => s.trim()).filter(Boolean);
    const res = await api("/api/discover", { subnets, scan: $("#do-scan").checked });
    renderDiscovery(res);
    const errs = Object.entries(res.errors || {}).map(([k, v]) => `${k}: ${v}`).join("; ");
    st.textContent = `Found ${res.devices.length} possible camera(s) on ${res.subnets.join(", ") || "your network"}.` + (errs ? ` Some methods failed: ${errs}` : "");
  } catch (e) { st.textContent = "Scan failed: " + e.message; }
  btn.disabled = false;
};

function renderDiscovery(res) {
  const tb = $("#discover-table tbody");
  tb.replaceChildren();
  $("#discover-table").hidden = !res.devices.length;
  const label = id => (BRANDS.find(b => b.id === id) || {}).label || id;
  for (const d of res.devices) {
    const banners = Object.entries(d.banners || {}).map(([p, b]) => `${p}: ${b}`).join("\n");
    tb.append(h("tr", {},
      h("td", {}, h("b", {}, d.ip), d.saved ? h("div", { class: "small ok" }, "Already added") : null),
      h("td", {}, d.brand === "generic" ? h("span", { class: "muted" }, "Unknown brand") : label(d.brand),
        d.onvif ? h("div", {}, h("span", { class: "pill" }, "ONVIF")) : null),
      h("td", { title: banners }, [d.manufacturer, d.model, d.name].filter(Boolean).join(" · ") || h("span", { class: "muted small" }, banners.split("\n")[0] || "")),
      h("td", {}, (d.sources || []).map(s => h("span", { class: "pill" }, s))),
      h("td", { class: "small" }, (d.open_ports || []).join(", ")),
      h("td", {}, h("button", { onclick: () => setupFromDiscovery(d) }, "Set up")),
    ));
    if (d.note) tb.append(h("tr", {}, h("td", { colspan: 6, class: "small muted" }, d.note)));
  }
}

function setupFromDiscovery(d) {
  show("add"); showSub("auto");
  const f = $("#auto-form");
  f.host.value = d.ip;
  f.brand.value = BRANDS.some(b => b.id === d.brand) && d.brand !== "generic" ? d.brand : "auto";
  f.rtsp_port.value = d.rtsp_port && d.rtsp_port !== 554 ? d.rtsp_port : "";
  f.http_port.value = d.http_port && d.http_port !== 80 ? d.http_port : "";
  f.dataset.xaddrs = JSON.stringify(d.onvif_xaddrs || []);
  f.dataset.name = d.name || d.model || "";
  f.user.focus();
}

// ------------------------------------------------------------ auto probe
$("#auto-form").onsubmit = async e => {
  e.preventDefault();
  const f = e.target, st = $("#auto-status"), out = $("#auto-results");
  const body = Object.fromEntries(new FormData(f));
  body.onvif = f.onvif.checked;
  try { body.xaddrs = JSON.parse(f.dataset.xaddrs || "[]"); } catch { body.xaddrs = []; }
  st.textContent = "Asking the camera for its streams… (up to a minute)";
  out.replaceChildren();
  f.querySelector("button[type=submit]").disabled = true;
  try {
    const r = await api("/api/probe", body);
    renderProbe(r, body, f.dataset.name);
    st.textContent = "";
  } catch (err) { st.textContent = "Failed: " + err.message; }
  f.querySelector("button[type=submit]").disabled = false;
};

function renderProbe(r, body, name) {
  const out = $("#auto-results");
  const ok = r.rtsp.filter(x => x.ok);
  const parts = [];
  if (r.onvif?.ok) {
    const i = r.onvif.info || {};
    parts.push(h("p", { class: "ok" }, `ONVIF answered: ${[i.Manufacturer, i.Model].filter(Boolean).join(" ") || "camera"}${i.FirmwareVersion ? " (firmware " + i.FirmwareVersion + ")" : ""}`));
  } else if (body.onvif) {
    parts.push(h("p", { class: "muted small" }, "ONVIF did not answer (it may be turned off on the camera). Tried known URL formats instead."));
  }
  if (r.auth_error && !ok.length) parts.push(h("p", { class: "bad" }, r.auth_error + ". Check the username and password set on the camera."));
  const rows = [...ok.map(x => ({ ...x, kind: "rtsp" })), ...r.http.map(x => ({ ...x, kind: x.type }))];
  if (!rows.length) {
    parts.push(h("p", { class: "bad" }, `No working stream found after ${r.tried} attempts.`),
      h("p", { class: "muted small" }, "Check the login, make sure RTSP or ONVIF is enabled in the camera's settings (Tapo, Eufy, Wyze and some others need it switched on in their app), or copy the stream URL from the camera's manual into \"Enter a stream URL\"."));
  }
  for (const x of rows) {
    const desc = [x.label, x.resolution, x.codec, x.kind === "rtsp" ? null : x.kind.toUpperCase()].filter(Boolean).join(" · ");
    parts.push(h("div", { class: "result" },
      h("code", {}, x.url),
      h("span", { class: "small muted" }, desc),
      h("button", { onclick: async ev => {
        ev.target.disabled = true;
        const cam = await api("/api/cameras", {
          kind: x.kind, url: x.url, user: body.user, password: body.password, host: body.host, brand: r.brand,
          name: name || `${(BRANDS.find(b => b.id === r.brand) || {}).label?.split(" ")[0] || "Camera"} ${body.host}`,
        }).catch(e2 => banner(e2.message));
        if (cam) { ev.target.textContent = "Added"; }
      } }, "Add")));
  }
  if (rows.length) parts.push(h("p", { class: "small muted" }, "Tip: the first RTSP result is usually the main (high-res) stream; a sub stream uses less bandwidth for the grid."));
  out.replaceChildren(...parts);
}

// ------------------------------------------------------------ manual
function manualBody() {
  const f = $("#manual-form");
  const b = Object.fromEntries(new FormData(f));
  if (!b.kind) {
    const u = b.url.trim().toLowerCase();
    b.kind = u.startsWith("rtsp") ? "rtsp"
      : /\.(jpe?g|png)(\?|$)|snapshot|snap|picture|image\.cgi|shot/.test(u) ? "snapshot"
      : /mjpe?g|video\.cgi|videostream|faststream|\/video$|stream/.test(u) && u.startsWith("http") ? "mjpeg"
      : "ffmpeg";
  }
  if (!b.id) delete b.id;
  return b;
}
$("#manual-test").onclick = async () => {
  const st = $("#manual-status"); const b = manualBody();
  st.textContent = "Testing…";
  try {
    const r = await api("/api/test", b);
    st.className = r.ok ? "ok" : "bad";
    st.textContent = r.ok ? `Works: ${[r.codec, r.width && `${r.width}x${r.height}`, r.type].filter(Boolean).join(" ")}` : r.error;
  } catch (e) { st.className = "bad"; st.textContent = e.message; }
};
$("#manual-form").onsubmit = async e => {
  e.preventDefault();
  const b = manualBody();
  try {
    await api("/api/cameras", b);
    e.target.reset(); e.target.id.value = "";
    $("#manual-status").textContent = "";
    show("cameras");
  } catch (err) { $("#manual-status").className = "bad"; $("#manual-status").textContent = err.message; }
};

// ------------------------------------------------------------ local devices
async function loadLocal() {
  const el = $("#local-list");
  el.textContent = "Looking for cameras…";
  try {
    const devs = await api("/api/local-devices");
    if (!INFO.ffmpeg) return el.textContent = "ffmpeg is not installed on the computer running WaniCam.";
    if (!devs.length) return el.textContent = "No USB or built-in cameras found on the computer running WaniCam.";
    el.replaceChildren(...devs.map(d => h("div", { class: "result" }, h("code", {}, d.name),
      h("button", { onclick: async ev => {
        ev.target.disabled = true;
        await api("/api/cameras", { kind: "local", device: d.id, name: d.name.replace(/\s*\(.*\)$/, "") });
        ev.target.textContent = "Added";
      } }, "Add"))));
  } catch (e) { el.textContent = e.message; }
}

// ------------------------------------------------------------ misc
function banner(msg) {
  const b = $("#banner"); b.textContent = msg; b.hidden = false;
  clearTimeout(banner.t); banner.t = setTimeout(() => b.hidden = true, 8000);
}

async function init() {
  try {
    [INFO, BRANDS] = await Promise.all([api("/api/info"), api("/api/brands")]);
  } catch (e) { return banner("Can't reach the WaniCam server: " + e.message); }
  if (!INFO.ffmpeg) banner("ffmpeg isn't installed, so RTSP and USB cameras won't play. MJPEG and snapshot cameras still work. See the README.");
  $("#subnets").placeholder = (INFO.subnets || []).join(", ") || "192.168.1.0/24";
  const sel = $("#brand-select");
  for (const b of BRANDS) if (b.id !== "generic") sel.append(h("option", { value: b.id }, b.label));
  $("#pattern-help").replaceChildren(h("ul", {}, ...[
    ["Hikvision", "rtsp://IP:554/Streaming/Channels/101 (102 = sub stream)"],
    ["Dahua / Amcrest / Lorex", "rtsp://IP:554/cam/realmonitor?channel=1&subtype=0"],
    ["Reolink", "rtsp://IP:554/h264Preview_01_main"],
    ["Axis", "rtsp://IP/axis-media/media.amp"],
    ["Foscam", "rtsp://IP:554/videoMain"],
    ["Uniview", "rtsp://IP:554/unicast/c1/s0/live"],
    ["TP-Link Tapo", "rtsp://IP:554/stream1 (stream2 = sub)"],
    ["UniFi Protect", "rtsps://NVR-IP:7441/TOKEN (copy from Protect)"],
    ["ESP32-CAM", "http://IP:81/stream"],
    ["Android IP Webcam", "http://IP:8080/video"],
  ].map(([b, u]) => h("li", {}, h("b", {}, b + ": "), u))));
  $("#auto-form").host.addEventListener("input", e => { e.target.form.dataset.xaddrs = "[]"; });
  const last = await api("/api/discover/last").catch(() => null);
  if (last?.devices?.length) renderDiscovery(last);
  loadCameras();
}
init();
