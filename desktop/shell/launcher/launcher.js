/* UjimaOS launcher (design turn 47a) — the category grid + identity. No framework, no CDN.
   Served from ujimad's desktop tier at /ujima-desktop on :1336, same-origin with every call it makes.
   The grid is built ONCE from the catalog; the identity line and open-state come live from
   ujimad's NDJSON streams (stream/state, stream/apps). */
'use strict';

const $ = (id) => document.getElementById(id);

// header identity glyph only (monitor-dot). App tiles use the real app icons, never glyphs.
const GLYPHS = {
  'monitor-dot': '<circle cx="19" cy="6" r="3"/><path d="M22 12v3a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h9"/><path d="M12 17v4"/><path d="M8 21h8"/>',
};
// app icons come from ujimad (assets/app-icon/<id>) — the app dir owns its face, the launcher
// never knows the filesystem layout

function glyph(name, size){
  return `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="none" stroke="currentColor"
    stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${GLYPHS[name] || ''}</svg>`;
}
const el = (html) => { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstElementChild; };

// ── category presentation only: display order + label + colour. The APP membership, icons and
//    labels come straight from the catalog (nothing about apps is embedded here). Keys/colours
//    match eww's v0.9 trays (eww.scss / eww.yuck). ──
//    'system' is deliberately absent -> its apps (files) never render in the launcher.
const CATEGORIES = [
  { key:'learn',   label:'LEARN',   color:'203,168,120' },
  { key:'explore', label:'EXPLORE', color:'126,158,214' },
  { key:'office',  label:'OFFICE',  color:'181,143,201' },
  { key:'create',  label:'CREATE',  color:'206,147,166' },
  { key:'code',    label:'CODE',    color:'176,184,119' },
];

// CATS = await fetchCatalog(): GET app/catalog, then fold each app into its category (display
// order). Every value about an app (id, icon, label) is the catalog's — the single source of truth.
async function fetchCatalog(){
  const res = await fetch('/ujima-desktop/app/catalog');
  const { apps } = await res.json();
  return CATEGORIES
    .map(c => ({ ...c, apps: apps.filter(a => a.category === c.key) }))
    .filter(c => c.apps.length);
}

// ── click = fire an HTTP POST (same-origin; fire-and-forget, we don't need the 202) ──
function launch(appId){
  fetch('/ujima-desktop/commands/app/open', {
    method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify({'app': appId}),
  }).catch(err => console.error('launch failed:', appId, err));
}

// ── build the grid ONCE from the fetched catalog (per-session; tiles get a stable id) ──
function buildGrid(cats){
  const grid = $('catgrid');
  grid.textContent = '';
  for (const cat of cats){
    const col = el(`<section class="cat" style="--cat:${cat.color}">
        <div class="cat-head"><span class="cat-swatch"></span><span class="cat-label">${cat.label}</span></div>
        <div class="tiles"></div></section>`);
    const tiles = col.querySelector('.tiles');
    for (const app of cat.apps){
      const b = el(`<button class="tile" id="tile-${app.id}" aria-label="${app.label || app.id}">
          <img src="/ujima-desktop/assets/app-icon/${app.id}" alt="" draggable="false"></button>`);
      b.addEventListener('click', () => launch(app.id));
      tiles.appendChild(b);
    }
    grid.appendChild(col);
  }
}

// ── the identity line: the machine's display name from the settings plane, plus its
//    last-4 serial digits on the side (absent off-Pi — the span just stays empty) ──
function buildIdent(){
  document.querySelectorAll('[data-glyph]').forEach(n => n.innerHTML = glyph(n.dataset.glyph, 30));
}

function applyState(state){
  const sys = state.system;
  if (!sys) return;
  $('name').textContent = sys.name;
  $('serial').textContent = sys.serialTail || '';
}

// ── reveal: fade the finished panel in once its icons + the shell font are painted ──
// A cold boot otherwise flashes the empty tile borders (CSS) then pops icons in one by one.
// img.decode() resolves when an image is actually paintable; fonts.ready avoids a text reflow.
// The 2s race is a guard — a stalled asset can never leave the panel stuck hidden.
async function reveal(){
  const home = document.querySelector('.home');
  const imgs = [...document.querySelectorAll('.home img')];
  const assets = Promise.all([
    ...imgs.map(img => img.decode().catch(() => {})),
    document.fonts ? document.fonts.ready : Promise.resolve(),
  ]);
  await Promise.race([assets, new Promise(r => setTimeout(r, 2000))]);
  home.classList.add('ready');
}

// ── open-app state: stream/apps (the same NDJSON source as the dock) and colour EVERY open
// app's tile with its category (.open). Each push carries the FULL open-apps list (running[] — not
// a delta, and populated even at home), so we just reapply it. Matching is by catalog id — stable
// until user-defined catalogs land in v1. Mirrors eww's deflisten: snapshot then a line per change.
function applyOpen(state){
  const open = new Set((state.running || []).map(a => a.id));
  for (const btn of document.querySelectorAll('.tile'))
    btn.classList.toggle('open', open.has(btn.id.replace(/^tile-/, '')));
}

async function watchStream(path, apply){
  for (;;){
    try {
      const reader = (await fetch(path)).body.getReader();
      const dec = new TextDecoder();
      let buf = '';
      for (;;){
        const { value, done } = await reader.read();
        if (done) break;
        buf += dec.decode(value, { stream:true });
        let nl;
        while ((nl = buf.indexOf('\n')) >= 0){
          const line = buf.slice(0, nl); buf = buf.slice(nl + 1);
          if (line.trim()) apply(JSON.parse(line));
        }
      }
    } catch (err){ console.error(path + ' stream dropped:', err); }
    await new Promise(r => setTimeout(r, 1000));   // reconnect (ujimad restart drops the stream)
  }
}

// The launcher is UNMAPPED (hidden) whenever you're inside an app, and WebKit suspends its JS there
// — so the watches miss pushes that happen while hidden, and a push already in flight can sit
// undelivered until the NEXT write wakes the connection (seen live: a hostname rename while
// hidden left the header stale). On becoming visible again, pull one fresh snapshot per stream
// and reapply, so everything is current whenever you're actually looking at the launcher.
async function syncStream(path, apply){
  try {
    const reader = (await fetch(path)).body.getReader();
    const dec = new TextDecoder(); let buf = '';
    for (;;){
      const { value, done } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream:true });
      const nl = buf.indexOf('\n');
      if (nl >= 0){ apply(JSON.parse(buf.slice(0, nl))); reader.cancel().catch(() => {}); return; }
    }
  } catch (err){ console.error(path + ' resync failed:', err); }
}
function syncAll(){
  syncStream('/ujima-desktop/stream/state', applyState);
  syncStream('/ujima-desktop/stream/apps',  applyOpen);
}
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') syncAll();
});
// backstop: the stream reconnect + visibilitychange above can still miss a change that happened
// while hidden (WebKit suspends us) — a slow poll while visible reconverges to truth.
setInterval(() => { if (document.visibilityState === 'visible') syncAll(); }, 1500);

async function main(){
  buildIdent();
  let cats = [];
  try { cats = await fetchCatalog(); }
  catch (err){ console.error('catalog fetch failed:', err); }
  buildGrid(cats);            // empty on failure — the header still shows, and reveal() still runs
  watchStream('/ujima-desktop/stream/state', applyState);  // identity line
  watchStream('/ujima-desktop/stream/apps',  applyOpen);   // live open-state — before reveal so open tiles fade in coloured
  await reveal();
}
document.addEventListener('DOMContentLoaded', main);
