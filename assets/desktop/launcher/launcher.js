/* UjimaOS launcher (design turn 47a) — the category grid + identity/status. No framework, no CDN.
   Served from ujimad on :1337 so it is same-origin with the app API (clicks POST /app/run).
   The grid is built ONCE from the catalog; pull(state) does stable, id-keyed updates of the
   changing bits. STATE is STATIC today — each field notes the API that will feed it. */
'use strict';

const $ = (id) => document.getElementById(id);

// header identity glyph only (monitor-dot). App tiles use the real app icons, never glyphs.
const GLYPHS = {
  'monitor-dot': '<circle cx="19" cy="6" r="3"/><path d="M22 12v3a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h9"/><path d="M12 17v4"/><path d="M8 21h8"/>',
};
const ICONS = '../icons';   // real app icons, staged at /opt/ujima/desktop/icons (served at /icons)

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
  { key:'office',  label:'OFFICE',  color:'168,150,201' },
  { key:'create',  label:'CREATE',  color:'206,147,166' },
  { key:'code',    label:'CODE',    color:'176,184,119' },
];

// CATS = await fetchCatalog(): GET /app/catalog, then fold each app into its category (display
// order). Every value about an app (id, icon, label) is the catalog's — the single source of truth.
async function fetchCatalog(){
  const res = await fetch('/app/catalog');
  const { apps } = await res.json();
  return CATEGORIES
    .map(c => ({ ...c, apps: apps.filter(a => a.category === c.key) }))
    .filter(c => c.apps.length);
}

// ── live state — STATIC today; each field maps to a real source (see comments) ──
const STATE = {
  hostname : 'UjimaOS',             // API: GET /ui/state -> hostname (settings plane)
  online   : true,                  // API: /ui/state -> network reachable
  wifiBars : 3,                     // API: /ui/state -> wifi strength 0..4 (0 = wired/none)
  room     : { filled:2, total:6 }, // API: "room to work" = free-RAM headroom (lowram policy)
};

// ── click = fire an HTTP POST (same-origin; fire-and-forget, we don't need the 202) ──
function launch(appId){
  fetch('/app/run', {
    method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify({'app-id': appId}),
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
      const b = el(`<button class="tile" id="tile-${app.id}" title="${app.label || app.id}">
          <img src="${ICONS}/${app.icon}.svg" alt="" draggable="false"></button>`);
      b.addEventListener('click', () => launch(app.id));
      tiles.appendChild(b);
    }
    grid.appendChild(col);
  }
}

// ── build the fixed status skeleton ONCE (glyphs + room segments with stable ids) ──
function buildStatus(){
  document.querySelectorAll('[data-glyph]').forEach(n => n.innerHTML = glyph(n.dataset.glyph, 30));
  const room = $('room');
  for (let i = 0; i < STATE.room.total; i++) room.appendChild(el(`<span id="room-${i}"></span>`));
}

// ── pull: a dead-simple id-keyed loop; stable updates, no re-render ──
function pull(state){
  $('hostname').textContent = state.hostname;
  $('net-label').textContent = state.online ? 'Online' : 'Offline';
  for (let i = 0; i < 4; i++) $('wifi-' + i).classList.toggle('on', state.online && i < state.wifiBars);
  for (let i = 0; i < state.room.total; i++) $('room-' + i).classList.toggle('on', i < state.room.filled);
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

async function main(){
  buildStatus();
  pull(STATE);
  let cats = [];
  try { cats = await fetchCatalog(); }
  catch (err){ console.error('catalog fetch failed:', err); }
  buildGrid(cats);            // empty on failure — the header still shows, and reveal() still runs
  await reveal();
  // live wiring later: an EventSource('/ui/state') / poll loop calls pull(nextState) on each push.
}
document.addEventListener('DOMContentLoaded', main);
