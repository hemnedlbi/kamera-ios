/*
 کامێرا — camera editor (Cloudflare Worker "camera-admin")
 Design & Development Hemn A.Muhammad

 Runs on routes of camera.4chra.com in front of the Pages site (camera-4chra):
   GET  /cameras.json           official camera list, now read from D1 (table "cameras")
   GET  /admin/cameras          map editor: move (drag), delete / restore, speed, add, mark checked
   GET  /api/admin/cameras      all cameras for the editor (admin only)
   POST /api/admin/cameras      { action, id, ... } changes (admin only)

 The admin password is the same one as /admin: it is checked by asking the Pages site
 (camera-4chra.pages.dev/api/admin/reports) with the same x-admin-pin header, so no
 password or hash is stored here.
 On first use the "cameras" table is filled from the Pages site's static cameras.json.
 Community cameras (approved reports) can be moved or removed here too.
*/

const PAGES = 'https://camera-4chra.pages.dev';
const SPEEDS = [40, 50, 60, 70, 80, 90, 100, 110, 120];

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    try {
      if (url.pathname === '/cameras.json' && req.method === 'GET') return camerasJson(env);
      if (url.pathname === '/admin/cameras' || url.pathname === '/admin/cameras/') return html(EDITOR);
      if (url.pathname === '/api/admin/cameras') {
        if (!(await isAdmin(req))) return json({ error: 'auth' }, 401);
        await ensureTable(env);
        if (req.method === 'GET') return json(await listAll(env));
        if (req.method === 'POST') return json(await change(env, await req.json()));
      }
      // anything else (style.css, icons, the rest of the site): the Pages site as before
      return fetch(new Request(PAGES + url.pathname + url.search, req));
    } catch (e) {
      return json({ error: String(e && e.message || e) }, 500);
    }
  },
};

// ------------------------------------------------------------------ helpers

function json(d, status = 200) {
  return new Response(JSON.stringify(d), {
    status, headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  });
}
function html(s) {
  return new Response(s, { headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store', 'x-robots-tag': 'noindex' } });
}

const okPins = new Map(); // pin -> time it was confirmed (per isolate, 10 min)
async function isAdmin(req) {
  const pin = req.headers.get('x-admin-pin') || '';
  if (pin.length < 8) return false;
  const t = okPins.get(pin);
  if (t && Date.now() - t < 10 * 60 * 1000) return true;
  const r = await fetch(PAGES + '/api/admin/reports?status=approved', { headers: { 'x-admin-pin': pin } });
  if (r.ok) { okPins.set(pin, Date.now()); return true; }
  return false;
}

let tableReady = false;
async function ensureTable(env) {
  if (tableReady) return;
  await env.DB.batch([
    env.DB.prepare(`CREATE TABLE IF NOT EXISTS cameras(
      id TEXT PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, speed INTEGER, dir REAL, area TEXT,
      source TEXT NOT NULL DEFAULT 'osm', active INTEGER NOT NULL DEFAULT 1,
      updated_at TEXT NOT NULL DEFAULT (datetime('now')), checked_at TEXT)`),
    env.DB.prepare(`CREATE TABLE IF NOT EXISTS camera_log(
      id INTEGER PRIMARY KEY AUTOINCREMENT, cam_id TEXT NOT NULL, action TEXT NOT NULL,
      before TEXT, after TEXT, at TEXT NOT NULL DEFAULT (datetime('now')))`),
  ]);
  const n = await env.DB.prepare('SELECT COUNT(*) AS n FROM cameras').first('n');
  if (!n) {
    // first use: copy the static list that the site has served until now
    const r = await fetch(PAGES + '/cameras.json', { cf: { cacheTtl: 0 } });
    const d = await r.json();
    const list = (d.cameras || []).filter(c => isFinite(c.lat) && isFinite(c.lon));
    const stmts = list.map(c => env.DB.prepare(
      'INSERT OR IGNORE INTO cameras(id, lat, lon, speed, dir, area, source) VALUES (?, ?, ?, ?, ?, ?, ?)'
    ).bind(String(c.id), +c.lat, +c.lon, c.speed ? +c.speed : null, c.dir ?? null, c.area || '', String(c.id).startsWith('n') ? 'osm' : 'admin'));
    for (let i = 0; i < stmts.length; i += 50) await env.DB.batch(stmts.slice(i, i + 50));
  }
  tableReady = true;
}

async function camerasJson(env) {
  try {
    await ensureTable(env);
    const { results } = await env.DB.prepare(
      'SELECT id, lat, lon, speed, dir, area FROM cameras WHERE active = 1 ORDER BY id').all();
    const upd = await env.DB.prepare('SELECT MAX(updated_at) AS u FROM cameras').first('u');
    return new Response(JSON.stringify({
      updated: String(upd || '').slice(0, 10),
      source: 'OpenStreetMap (ODbL), checked and corrected by TCK',
      cameras: results.map(c => ({ id: c.id, lat: c.lat, lon: c.lon, speed: c.speed ?? null, dir: c.dir ?? null, area: c.area || '' })),
    }), { headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-cache', 'access-control-allow-origin': '*' } });
  } catch (e) {
    return fetch(PAGES + '/cameras.json'); // never leave the apps without a list
  }
}

async function listAll(env) {
  const { results: cams } = await env.DB.prepare(
    'SELECT id, lat, lon, speed, area, source, active, updated_at, checked_at FROM cameras').all();
  const { results: reps } = await env.DB.prepare(
    "SELECT id, lat, lon, speed, note, status, created_at, reviewed_at FROM reports WHERE status = 'approved'").all();
  return {
    cameras: cams,
    community: reps.map(r => ({ id: 'c' + r.id, lat: r.lat, lon: r.lon, speed: r.speed, area: r.note || '',
      source: 'community', active: 1, updated_at: r.reviewed_at || r.created_at, checked_at: null })),
  };
}

function cleanSpeed(v) {
  if (v === null || v === '' || v === undefined) return null;
  const n = +v; return SPEEDS.includes(n) ? n : null;
}
function cleanLatLon(lat, lon) {
  lat = +lat; lon = +lon;
  if (!isFinite(lat) || !isFinite(lon) || lat < 29 || lat > 38.5 || lon < 38 || lon > 49) throw new Error('شوێنەکە دروست نییە');
  return [Math.round(lat * 1e7) / 1e7, Math.round(lon * 1e7) / 1e7];
}

async function log(env, id, action, before, after) {
  await env.DB.prepare('INSERT INTO camera_log(cam_id, action, before, after) VALUES (?, ?, ?, ?)')
    .bind(id, action, before ? JSON.stringify(before) : null, after ? JSON.stringify(after) : null).run();
}

async function change(env, b) {
  const action = String(b.action || '');
  const id = String(b.id || '');

  // community cameras live in the "reports" table of the Pages site
  if (id.startsWith('c')) {
    const rid = +id.slice(1);
    const row = await env.DB.prepare('SELECT * FROM reports WHERE id = ?').bind(rid).first();
    if (!row) throw new Error('نەدۆزرایەوە');
    if (action === 'move') {
      const [lat, lon] = cleanLatLon(b.lat, b.lon);
      await env.DB.prepare('UPDATE reports SET lat = ?, lon = ? WHERE id = ?').bind(lat, lon, rid).run();
      await log(env, id, 'move', { lat: row.lat, lon: row.lon }, { lat, lon });
    } else if (action === 'speed') {
      const sp = cleanSpeed(b.speed);
      await env.DB.prepare('UPDATE reports SET speed = ? WHERE id = ?').bind(sp, rid).run();
      await log(env, id, 'speed', { speed: row.speed }, { speed: sp });
    } else if (action === 'delete') {
      await env.DB.prepare("UPDATE reports SET status = 'rejected', reviewed_at = datetime('now') WHERE id = ?").bind(rid).run();
      await log(env, id, 'delete', row, null);
    } else throw new Error('کارێکی نەناسراو');
    return { ok: true };
  }

  if (action === 'add') {
    const [lat, lon] = cleanLatLon(b.lat, b.lon);
    const nid = 'm' + Date.now().toString(36);
    const sp = cleanSpeed(b.speed);
    await env.DB.prepare("INSERT INTO cameras(id, lat, lon, speed, area, source, checked_at) VALUES (?, ?, ?, ?, ?, 'admin', datetime('now'))")
      .bind(nid, lat, lon, sp, String(b.area || '').slice(0, 80)).run();
    await log(env, nid, 'add', null, { lat, lon, speed: sp });
    return { ok: true, id: nid };
  }

  const row = await env.DB.prepare('SELECT * FROM cameras WHERE id = ?').bind(id).first();
  if (!row) throw new Error('نەدۆزرایەوە');
  if (action === 'move') {
    const [lat, lon] = cleanLatLon(b.lat, b.lon);
    await env.DB.prepare("UPDATE cameras SET lat = ?, lon = ?, updated_at = datetime('now'), checked_at = datetime('now') WHERE id = ?").bind(lat, lon, id).run();
    await log(env, id, 'move', { lat: row.lat, lon: row.lon }, { lat, lon });
  } else if (action === 'speed') {
    const sp = cleanSpeed(b.speed);
    await env.DB.prepare("UPDATE cameras SET speed = ?, updated_at = datetime('now') WHERE id = ?").bind(sp, id).run();
    await log(env, id, 'speed', { speed: row.speed }, { speed: sp });
  } else if (action === 'delete') {
    await env.DB.prepare("UPDATE cameras SET active = 0, updated_at = datetime('now') WHERE id = ?").bind(id).run();
    await log(env, id, 'delete', row, null);
  } else if (action === 'restore') {
    await env.DB.prepare("UPDATE cameras SET active = 1, updated_at = datetime('now') WHERE id = ?").bind(id).run();
    await log(env, id, 'restore', null, row);
  } else if (action === 'checked') {
    await env.DB.prepare("UPDATE cameras SET checked_at = datetime('now') WHERE id = ?").bind(id).run();
    await log(env, id, 'checked', null, null);
  } else throw new Error('کارێکی نەناسراو');
  return { ok: true };
}

// ------------------------------------------------------------------ the editor page

const EDITOR = String.raw`<!doctype html>
<html lang="ckb" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#0b1630">
<meta name="robots" content="noindex">
<title>دەستکاریی کامێراکان</title>
<link rel="icon" href="/icons/icon-192.png">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<link rel="stylesheet" href="/style.css?v=5">
<style>
  html,body{height:100%;margin:0;overflow:hidden}
  #top{position:fixed;inset:0 0 auto 0;z-index:1000;display:flex;gap:8px;align-items:center;flex-wrap:wrap;
       padding:10px 12px;padding-top:max(10px,env(safe-area-inset-top));background:var(--navy2);border-bottom:1px solid var(--line)}
  #top h1{font-size:17px;margin:0 0 0 auto}
  .btn{border:1px solid var(--line);border-radius:10px;padding:9px 12px;font-weight:700;cursor:pointer;color:#fff;background:var(--navy);font-size:14px}
  .btn.green{background:var(--green);border-color:var(--green)}
  .btn.red{background:var(--red);border-color:var(--red)}
  .btn.on{outline:2px solid #fff}
  #emap{position:fixed;inset:0;top:var(--top,60px)}
  #login{position:fixed;inset:0;z-index:2000;display:flex;align-items:center;justify-content:center;background:var(--navy);padding:16px}
  #login .card{background:var(--navy2);border:1px solid var(--line);border-radius:14px;padding:18px;max-width:380px;width:100%}
  #login input{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid var(--line);background:var(--navy);color:var(--text);font-size:16px;direction:ltr}
  .err{color:#ff8a80;min-height:1.4em;margin-top:8px}
  .pop{min-width:210px;line-height:1.7;font-size:14px;direction:rtl;text-align:right}
  .pop b{font-size:15px}
  .pop select{font-size:15px;padding:4px 6px;border-radius:8px}
  .pop .row{display:flex;gap:6px;flex-wrap:wrap;margin-top:8px}
  .pop .row button{border:0;border-radius:8px;padding:8px 10px;font-weight:700;cursor:pointer;color:#fff;font-size:13px}
  .b-g{background:#16a34a}.b-r{background:#dc2626}.b-n{background:#334155}.b-b{background:#2563eb}
  .cam{width:26px;height:26px;border-radius:50%;border:3px solid #fff;box-shadow:0 1px 4px #0008;display:flex;align-items:center;justify-content:center;font:700 10px/1 sans-serif;color:#fff}
  .cam.osm{background:#2563eb}.cam.admin{background:#16a34a}.cam.community{background:#f59e0b}.cam.off{background:#6b7280;opacity:.65}
  .cam.unchecked{border-style:dashed}
  .cam.drag{background:#dc2626;transform:scale(1.25)}
  #hint{position:fixed;z-index:1000;left:12px;right:12px;bottom:max(14px,env(safe-area-inset-bottom));background:#000c;color:#fff;
        border-radius:12px;padding:10px 12px;font-size:14px;text-align:center}
  .legend{font-size:12px;color:var(--muted);width:100%}
  .legend i{display:inline-block;width:10px;height:10px;border-radius:50%;margin:0 4px 0 10px;vertical-align:middle}
</style>
</head>
<body>
<div id="login" hidden><div class="card">
  <h2 style="margin:0 0 10px;font-size:19px">📷 دەستکاریی کامێراکان</h2>
  <p>وشەی نهێنیی ئەدمین بنووسە (هەمان وشەی پەڕەی /admin)</p>
  <input type="password" id="pin" autocomplete="current-password" placeholder="••••••••">
  <div style="margin-top:12px"><button class="btn green" id="btnLogin">چوونەژوورەوە</button></div>
  <div class="err" id="loginErr"></div>
</div></div>

<div id="top" hidden>
  <h1>📷 دەستکاریی کامێراکان <span id="count" class="muted" style="font-size:13px"></span></h1>
  <button class="btn green" id="btnAdd">+ کامێرا</button>
  <button class="btn" id="btnMe">📍 شوێنی من</button>
  <button class="btn" id="btnSat">🛰 مانگی دەستکرد</button>
  <button class="btn" id="btnOff">سڕاوەکان</button>
  <a class="btn" href="/admin" style="text-decoration:none">ڕاپۆرتەکان</a>
  <div class="legend"><i style="background:#2563eb"></i>OpenStreetMap<i style="background:#16a34a"></i>هی تۆ<i style="background:#f59e0b"></i>بەکارهێنەران<i style="background:#6b7280"></i>سڕاوە · چوارچێوەی پچڕپچڕ = هێشتا نەپشکنراوە <span style="direction:ltr;display:inline-block;margin-inline-start:10px">Design &amp; Development Hemn A.Muhammad</span></div>
</div>
<div id="emap"></div>
<div id="hint" hidden></div>
<div class="toast" id="toast" hidden></div>

<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
'use strict';
const $ = id => document.getElementById(id);
let pin = sessionStorage.getItem('kc_admin') || '';
let map, layer, street, sat, satOn = false, showOff = false, adding = false, dragging = null, data = { cameras: [], community: [] };
const SPEEDS = ['', 40, 50, 60, 70, 80, 90, 100, 110, 120];
const esc = s => String(s ?? '').replace(/[&<>"]/g, m => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[m]));
function toast(m) { const t = $('toast'); t.textContent = m; t.hidden = false; clearTimeout(toast.t); toast.t = setTimeout(() => t.hidden = true, 2600); }
function hint(m) { $('hint').textContent = m || ''; $('hint').hidden = !m; }

async function call(method, body) {
  const r = await fetch('/api/admin/cameras', { method, headers: { 'content-type': 'application/json', 'x-admin-pin': pin }, body: body ? JSON.stringify(body) : undefined });
  const d = await r.json().catch(() => ({}));
  if (r.status === 401) { logout(); throw new Error('auth'); }
  if (!r.ok) throw new Error(d.error || r.status);
  return d;
}

function logout() { pin = ''; sessionStorage.removeItem('kc_admin'); $('top').hidden = true; $('login').hidden = false; }
$('btnLogin').onclick = async () => {
  const v = $('pin').value; if (v.length < 8) { $('loginErr').textContent = 'لانیکەم ٨ پیت'; return; }
  pin = v; $('loginErr').textContent = '';
  try { await start(); sessionStorage.setItem('kc_admin', pin); } catch (e) { $('loginErr').textContent = e.message === 'auth' ? 'وشەی نهێنی هەڵەیە' : 'هەڵە: ' + e.message; }
};
$('pin').addEventListener('keydown', e => { if (e.key === 'Enter') $('btnLogin').click(); });

async function start() {
  data = await call('GET');
  $('login').hidden = true; $('top').hidden = false;
  document.documentElement.style.setProperty('--top', $('top').offsetHeight + 'px');
  if (!map) initMap();
  draw(true);
}

function initMap() {
  map = L.map('emap', { zoomControl: true }).setView([36.19, 44.01], 10);
  street = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap' }).addTo(map);
  sat = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', { maxZoom: 19, attribution: 'Imagery © Esri' });
  layer = L.layerGroup().addTo(map);
  map.on('click', e => { if (adding) addAt(e.latlng); });
  setTimeout(() => map.invalidateSize(), 200);
  window.addEventListener('resize', () => { document.documentElement.style.setProperty('--top', $('top').offsetHeight + 'px'); map.invalidateSize(); });
}

function all() { return data.cameras.concat(data.community); }

function icon(c, extra) {
  const cls = ['cam', c.active ? c.source : 'off', c.source !== 'community' && !c.checked_at ? 'unchecked' : '', extra || ''].join(' ');
  return L.divIcon({ className: '', html: '<div class="' + cls + '">' + (c.speed || '') + '</div>', iconSize: [26, 26], iconAnchor: [13, 13], popupAnchor: [0, -14] });
}

function draw(fit) {
  layer.clearLayers();
  const shown = all().filter(c => c.active || showOff);
  const on = all().filter(c => c.active).length;
  $('count').textContent = '(' + on + ' چالاک · ' + (all().length - on) + ' سڕاوە)';
  for (const c of shown) {
    const m = L.marker([c.lat, c.lon], { icon: icon(c), draggable: false, autoPan: true });
    m.cam = c;
    m.bindPopup(() => popup(c), { maxWidth: 280 });
    m.on('dragend', () => { const p = m.getLatLng(); askMove(m, p); });
    m.addTo(layer);
  }
  if (fit && shown.length) map.fitBounds(shown.map(c => [c.lat, c.lon]), { padding: [30, 30], maxZoom: 14 });
}

function popup(c) {
  const src = { osm: 'OpenStreetMap', admin: 'هی تۆ', community: 'بەکارهێنەر' }[c.source] || c.source;
  const el = document.createElement('div'); el.className = 'pop';
  el.innerHTML = '<b>' + esc(c.area || 'کامێرا') + '</b><br>' +
    '<span class="muted">' + esc(src) + ' · ' + esc(c.id) + (c.checked_at ? ' · پشکنراوە ' + esc(String(c.checked_at).slice(0, 10)) : ' · نەپشکنراوە') + '</span><br>' +
    'سنووری خێرایی: <select data-sp>' + SPEEDS.map(s => '<option value="' + s + '"' + (String(s) === String(c.speed ?? '') ? ' selected' : '') + '>' + (s || 'نەزانراو') + '</option>').join('') + '</select>' +
    '<div class="row">' +
      (c.active ? '<button class="b-b" data-a="drag">✋ جوڵاندن</button>' : '') +
      (c.active && c.source !== 'community' ? '<button class="b-g" data-a="checked">✓ ڕاستە</button>' : '') +
      (c.active ? '<button class="b-r" data-a="delete">🗑 سڕینەوە</button>' : '<button class="b-g" data-a="restore">↩ گەڕاندنەوە</button>') +
      '<a class="b-n" style="padding:8px 10px;border-radius:8px;color:#fff;text-decoration:none;font-size:13px" target="_blank" rel="noopener" href="https://maps.google.com/?q=' + c.lat + ',' + c.lon + '">Google Maps</a>' +
    '</div>';
  el.querySelector('[data-sp]').onchange = async e => {
    try { await call('POST', { action: 'speed', id: c.id, speed: e.target.value }); c.speed = e.target.value ? +e.target.value : null; toast('سنوورەکە پاشەکەوت کرا'); draw(); }
    catch (err) { toast('هەڵە: ' + err.message); }
  };
  el.querySelectorAll('[data-a]').forEach(b => b.onclick = async () => {
    const a = b.dataset.a;
    if (a === 'drag') return startDrag(c);
    if (a === 'delete' && !b.dataset.sure) { b.dataset.sure = 1; b.textContent = 'دڵنیایت؟'; return; }
    try {
      await call('POST', { action: a, id: c.id });
      if (a === 'delete') { if (c.source === 'community') data.community = data.community.filter(x => x !== c); else c.active = 0; }
      if (a === 'restore') c.active = 1;
      if (a === 'checked') c.checked_at = new Date().toISOString();
      map.closePopup(); draw(); toast({ delete: 'سڕایەوە', restore: 'گەڕایەوە', checked: 'وەک ڕاست نیشان کرا ✓' }[a]);
    } catch (err) { toast('هەڵە: ' + err.message); }
  });
  return el;
}

function startDrag(c) {
  map.closePopup();
  layer.eachLayer(m => { if (m.cam === c) { dragging = m; m.dragging.enable(); m.setIcon(icon(c, 'drag')); map.panTo(m.getLatLng()); } });
  hint('کامێرا سوورەکە ڕابکێشە بۆ شوێنە ڕاستەکەی و بەری بدە');
}

async function askMove(m, p) {
  const c = m.cam;
  m.dragging.disable(); dragging = null; hint('');
  if (!confirm('کامێراکە بگوازرێتەوە بۆ ئەم شوێنە نوێیە؟')) { m.setLatLng([c.lat, c.lon]); m.setIcon(icon(c)); return; }
  try {
    await call('POST', { action: 'move', id: c.id, lat: p.lat, lon: p.lng });
    c.lat = p.lat; c.lon = p.lng; c.checked_at = new Date().toISOString();
    m.setIcon(icon(c)); toast('گوازرایەوە ✓');
  } catch (err) { m.setLatLng([c.lat, c.lon]); m.setIcon(icon(c)); toast('هەڵە: ' + err.message); }
}

$('btnAdd').onclick = () => {
  adding = !adding; $('btnAdd').classList.toggle('on', adding);
  hint(adding ? 'لەسەر نەخشەکە دەست لە شوێنی کامێراکە بدە' : '');
};
async function addAt(ll) {
  adding = false; $('btnAdd').classList.remove('on'); hint('');
  const sp = prompt('سنووری خێرایی (km/h) — بۆ نەزانراو بەتاڵی بهێڵەوە', '');
  if (sp === null) return;
  const area = prompt('ناوی شوێنەکە (بۆ نموونە: هەولێر / شەقامی ١٢٠ مەتری)', '') || '';
  try {
    const d = await call('POST', { action: 'add', lat: ll.lat, lon: ll.lng, speed: sp.trim(), area });
    data.cameras.push({ id: d.id, lat: ll.lat, lon: ll.lng, speed: SPEEDS.includes(+sp) ? +sp : null, area, source: 'admin', active: 1, checked_at: new Date().toISOString() });
    draw(); toast('کامێرا زیاد کرا ✓');
  } catch (err) { toast('هەڵە: ' + err.message); }
}

$('btnMe').onclick = () => {
  if (!navigator.geolocation) return toast('GPS نییە');
  navigator.geolocation.getCurrentPosition(p => {
    const ll = [p.coords.latitude, p.coords.longitude];
    map.setView(ll, 17);
    L.circleMarker(ll, { radius: 7, color: '#fff', weight: 2, fillColor: '#2563eb', fillOpacity: 1 }).addTo(map);
    if (adding) addAt({ lat: ll[0], lng: ll[1] });
  }, () => toast('شوێنەکەت نەدۆزرایەوە'), { enableHighAccuracy: true, timeout: 12000 });
};
$('btnSat').onclick = () => {
  satOn = !satOn; $('btnSat').classList.toggle('on', satOn);
  if (satOn) { map.removeLayer(street); sat.addTo(map); } else { map.removeLayer(sat); street.addTo(map); }
};
$('btnOff').onclick = () => { showOff = !showOff; $('btnOff').classList.toggle('on', showOff); draw(); };

if (pin) start().catch(() => { $('login').hidden = false; }); else { $('login').hidden = false; $('pin').focus(); }
</script>
</body>
</html>`;
