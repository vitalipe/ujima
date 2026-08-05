/* Circle panel — thin client over the circle server.
   Server truth: GET /ui/circle (peers + the active action), polled every 2s.
   Local truth: selection, ring/grid view, sheets — per-viewer state only.
   Verbs POST /circle/<verb> {targets,...} and re-poll; chips render from the
   action's per-peer statuses (pending / ok / fail / noreply / accepted). */

const POLL_MS       = 2000;
const RESULT_LINGER = 4000;   // clean runs clear on their own; failures stick
const FADE_MS       = 450;    // matches the .45s transitions in circle.css

const CAT = {learn:'203,168,120', office:'168,150,201', create:'206,147,166',
             web:'134,184,126', explore:'126,158,214', system:'136,192,208'};

const VERB_LABEL = {
  'mute':'Mute', 'unmute':'Sound on', 'volume':'Volume',
  'close-app':'Close app', 'open-app':'Open app', 'open-url':'Open page',
  'lock':'Lock screens', 'unlock':'Unlock screens',
  'restart':'Restart', 'poweroff':'Power off'};

let S    = null;          // last /ui/circle payload
let M    = [];            // render models of S.peers
let sel  = new Set();
let view = 'ring';
let run  = null;          // computed from lastAction on each render

let lastAction = null;    // latest action seen, {..., live:bool}

/* ── server state ────────────────────────────────────────────────────────── */
const esc = s => String(s ?? '').replace(/[&<>"']/g,
  c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

const model = p => ({
  id:      p.id,
  name:    p.name,
  online:  !!p.online,
  self:    p.id === S.self,
  locked:  !!(p.desktop && p.desktop.locked),
  muted:   !!(p.audio && p.audio.muted),
  app:     (p.apps && p.apps.running)
             ? {n: p.apps.running.name, c: p.apps.running.category} : null,
  catalog: (p.apps && p.apps.catalog) || []});

async function poll(){
  let s;
  try { s = await (await fetch('/ui/circle')).json(); }
  catch (e) { return; }                       // server gone — keep last frame
  S = s;
  M = (s.peers || []).map(model);

  const action = s.panel && s.panel.action;
  if (action) lastAction = Object.assign({}, action, {live: true});
  else if (lastAction && lastAction.live){    // it finished — fetch final chips
    try {
      const j = await (await fetch('/circle/job/' + lastAction.job)).json();
      lastAction = Object.assign({}, j, {live: false});
    } catch (e) { lastAction = Object.assign({}, lastAction, {live: false}); }
    scheduleResultClear();
  }

  for (const id of [...sel]){
    const m = byId(id);
    if (!m || !m.online) sel.delete(id);
  }
  render();
}

function post(verb, extra){
  fetch('/circle/' + verb, {
    method: 'POST',
    headers: {'content-type': 'application/json'},
    body: JSON.stringify(Object.assign({targets: [...sel]}, extra || {}))
  }).then(poll);
}

function act(verb, extra){
  if (!sel.size || busyNow()) return;
  closeSheet();
  clearSettled();
  post(verb, extra);
}

/* clean results fade on their own shortly after settling; results with a
   fail/noreply stick until the teacher's next gesture (selection, new verb).
   Clearing goes through a short fading phase: spokes ease back to blue while
   the chips and the status line fade out, on the same clock. */
function scheduleResultClear(){
  const job    = lastAction.job;
  const sticky = Object.values(lastAction.peers || {})
                       .some(s => s === 'fail' || s === 'noreply');
  if (sticky) return;
  setTimeout(() => {
    if (lastAction && !lastAction.live && lastAction.job === job) clearSettled();
  }, RESULT_LINGER);
}
function clearSettled(){
  if (!lastAction || lastAction.live || lastAction.fading) return;
  lastAction = Object.assign({}, lastAction, {fading: true});
  render();
  const job = lastAction.job;
  setTimeout(() => {
    if (lastAction && lastAction.fading && lastAction.job === job){
      lastAction = null;
      render();
    }
  }, FADE_MS);
}

function runView(){
  if (!lastAction) return null;
  const pending = new Set(), res = new Map();
  for (const [id, st] of Object.entries(lastAction.peers || {}))
    st === 'pending' ? pending.add(id) : res.set(id, st);
  return {label: VERB_LABEL[lastAction.verb] || lastAction.verb,
          total: pending.size + res.size, done: res.size,
          pending, res, finished: !lastAction.live, fading: !!lastAction.fading};
}
const busyNow = () => { const r = runView(); return !!r && !r.finished; };

/* ── icons ───────────────────────────────────────────────────────────────── */
const I = {
  vol:  '<polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><path d="M15.5 8.5a5 5 0 0 1 0 7"/>',
  mute: '<polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><line x1="22" y1="9" x2="16" y2="15"/><line x1="16" y1="9" x2="22" y2="15"/>',
  slid: '<line x1="4" y1="21" x2="4" y2="14"/><line x1="4" y1="10" x2="4" y2="3"/><line x1="12" y1="21" x2="12" y2="12"/><line x1="12" y1="8" x2="12" y2="3"/><line x1="20" y1="21" x2="20" y2="16"/><line x1="20" y1="12" x2="20" y2="3"/><line x1="2" y1="14" x2="6" y2="14"/><line x1="10" y1="8" x2="14" y2="8"/><line x1="18" y1="16" x2="22" y2="16"/>',
  apps: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/>',
  close:'<rect x="3" y="3" width="18" height="18" rx="2"/><line x1="9" y1="9" x2="15" y2="15"/><line x1="15" y1="9" x2="9" y2="15"/>',
  globe:'<circle cx="12" cy="12" r="10"/><path d="M2 12h20"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>',
  boot: '<path d="M21 12a9 9 0 1 1-9-9c2.5 0 4.9 1 6.6 2.6L21 8"/><path d="M21 3v5h-5"/>',
  pwr:  '<path d="M12 2v10"/><path d="M18.4 6.6a9 9 0 1 1-12.8 0"/>',
  check:'<polyline points="20 6 9 17 4 12"/>',
  lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',
  unlock:'<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 7.9-.9"/>',
};
const ic = k => `<span class="ic"><svg viewBox="0 0 24 24">${I[k]}</svg></span>`;

/* ── shared render bits ──────────────────────────────────────────────────── */
const $  = id => document.getElementById(id);
const byId = id => M.find(m => m.id === id);

function slotHtml(m, ring){
  if (run && run.pending.has(m.id)) return `<span class="spin" title="waiting for reply"></span>`;
  if (run && run.res.has(m.id)){
    if (ring) return '';   /* the ring shows results as node borders, not chips */
    const r = run.res.get(m.id);
    return {ok:      `<span class="chip ok">Done</span>`,
            accepted:`<span class="chip ok">Accepted</span>`,
            fail:    `<span class="chip fail">Failed</span>`,
            noreply: `<span class="chip noreply">No reply</span>`}[r] || '';
  }
  return '';
}
function sndHtml(m){
  return m.online ? `<span class="ic snd" title="${m.muted?'muted':'sound on'}">
    <svg viewBox="0 0 24 24">${I[m.muted?'mute':'vol']}</svg></span>` : '';
}
function appRow(m, cls){   /* cls: 'napp' (ring) or 'approw' (grid) */
  if (!m.online) return `<div class="${cls} offline"><span class="txt">Off</span></div>`;
  if (m.locked)  return `<div class="${cls} lockedrow">${ic('lock')}<span class="txt">Locked</span></div>`;
  if (!m.app)    return `<div class="${cls} home"><span class="txt">Home</span></div>`;
  return `<div class="${cls}" style="--cat:${CAT[m.app.c] || '105,113,128'}">
            <span class="catdot"></span><span class="txt">${esc(m.app.n)}</span>
          </div>`;
}

function render(){
  if (!S) return;
  run = runView();
  const online = M.filter(m => m.online).length;
  $('counts').textContent = `${M.length} machines · ${online} on`;
  $('all').disabled = $('none').disabled = busyNow();
  $('vring').classList.toggle('on', view === 'ring');
  $('vgrid').classList.toggle('on', view === 'grid');

  view === 'ring' ? renderRing() : renderGrid();

  const bar = $('bar');
  bar.classList.toggle('show', sel.size > 0 || !!run);
  bar.classList.toggle('busy', busyNow());
  renderStatus();
  renderVerbs();
}

/* ── RING render ─────────────────────────────────────────────────────────── */
function spokeCls(m){   /* result colors stay with the labels; both leave on the fade */
  let cls = 'spoke';
  if (run && !run.fading && run.pending.has(m.id)) cls += ' pending';
  else if (run && !run.fading && run.res.has(m.id)) cls += ' ' + run.res.get(m.id);
  else if (sel.has(m.id)) cls += ' sel';
  return cls;
}
function nodeCls(m, d){
  const res = (run && !run.fading && run.res.has(m.id)) && 'res-' + run.res.get(m.id);
  return ['node', d < 96 && 'small', !m.online && 'off', sel.has(m.id) && 'sel', res,
          (run && run.fading) && 'fading',
          (m.online && !busyNow()) && 'pickable'].filter(Boolean).join(' ');
}
/* flat computer glyph — the screen carries the machine's state: app category
   color while running, dark at home, lock when locked, dead when offline */
function monSvg(m){
  const cat    = (m.online && !m.locked && m.app) ? (CAT[m.app.c] || '105,113,128') : null;
  const screen = !m.online ? 'mon-screen dead' : m.locked ? 'mon-screen locked' : 'mon-screen';
  const fill   = cat ? ` style="fill:rgba(${cat},.5)"` : '';
  const lock   = (m.online && m.locked)
    ? `<g class="mon-lock"><rect x="19.5" y="15" width="9" height="7.5" rx="1.5"/>
         <path d="M21.5 15v-2.6a2.5 2.5 0 0 1 5 0V15"/></g>` : '';
  return `<svg class="mon" viewBox="0 0 48 42" aria-hidden="true">
    <rect class="mon-frame" x="1.5" y="1.5" width="45" height="31" rx="4.5"/>
    <rect class="${screen}" x="6" y="6" width="36" height="22" rx="2"${fill}/>
    ${lock}
    <path class="mon-stand" d="M19.5 36.5h9l2 4h-13z"/>
  </svg>`;
}

function nodeInner(m){
  return `${monSvg(m)}
    <span class="nname">${esc(m.name)}</span>
    ${appRow(m, 'napp')}
    <div class="nfoot">${sndHtml(m)}${slotHtml(m, true)}</div>`;
}
function centerInner(self){
  if (!self) return '';
  return `${monSvg(self)}
    <span class="nname">${esc(self.name)}</span>
    <span class="selftag">this computer</span>`;
}
function setInner(el, html){ if (el.innerHTML !== html) el.innerHTML = html; }

function renderRing(){
  const others = M.filter(m => !m.self);
  const self   = M.find(m => m.self);

  const W = Math.min(window.innerWidth - 40, 980);
  const H = Math.max(window.innerHeight - 235, 430);
  const cx = W/2, cy = H/2;
  const R  = Math.min(W, H)/2 - 66;

  /* node size adapts: full 116px for small circles, shrinking toward 74px near 20 */
  const d = Math.max(74, Math.min(116, (2*Math.PI*R)/(Math.max(others.length,1)*1.45)));
  const centerD = 150, gap = 5;
  const sig = [W, H, others.map(m => m.id).join(',')].join('|');

  /* same ring already on stage -> patch classes/content on the existing
     elements, so the spoke CSS transitions actually run (a rebuilt element
     just appears in its end state) */
  const wrap = $('stage').firstElementChild;
  if (wrap && wrap.dataset && wrap.dataset.sig === sig){
    wrap.classList.toggle('lock', busyNow());
    for (const m of others){
      $('spoke-' + m.id).setAttribute('class', spokeCls(m));
      const node = $('node-' + m.id);
      node.setAttribute('class', nodeCls(m, d));
      node.setAttribute('aria-checked', sel.has(m.id));
      setInner(node, nodeInner(m));
    }
    setInner($('ring-center'), centerInner(self));
    return;
  }

  const pos = {};
  others.forEach((m,i) => {
    const a = -Math.PI/2 + i * 2*Math.PI/others.length;
    pos[m.id] = {x: cx + R*Math.cos(a), y: cy + R*Math.sin(a),
                 ux: Math.cos(a), uy: Math.sin(a)};
  });

  /* the orbit passes through every peer glyph's midpoint (they all sit d*0.24
     above their box centers, so its center shifts up by the same amount);
     spokes are invisible at rest and only light up for selection / runs */
  const iconR = d < 96 ? 20 : 26;
  const cix = cx, ciy = cy - centerD*0.13;      // center glyph midpoint
  const orbit = `<circle class="orbit" cx="${cx}" cy="${cy - d*0.24}" r="${R}"/>`;
  const spokes = others.map(m => {
    const p  = pos[m.id];
    const ix = p.x, iy = p.y - d*0.24;
    const vx = ix - cix, vy = iy - ciy, len = Math.hypot(vx, vy);
    const ux = vx/len, uy = vy/len;
    const x1 = cix + ux*40,           y1 = ciy + uy*40;
    const x2 = ix - ux*(iconR + gap), y2 = iy - uy*(iconR + gap);
    return `<line id="spoke-${m.id}" class="${spokeCls(m)}" x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}"/>`;
  }).join('');

  const nodes = others.map(m => `
    <div id="node-${m.id}" class="${nodeCls(m, d)}" data-id="${m.id}"
         style="left:${pos[m.id].x}px;top:${pos[m.id].y}px;width:${d}px;height:${d}px"
         role="checkbox" aria-checked="${sel.has(m.id)}" tabindex="0">${nodeInner(m)}</div>`).join('');

  $('stage').innerHTML = `
    <div class="ringwrap ${busyNow() ? 'lock' : ''}" data-sig="${sig}" style="width:${W}px;height:${H}px">
      <svg class="spokes" width="${W}" height="${H}">${orbit}${spokes}</svg>
      <div id="ring-center" class="node center" style="left:${cx}px;top:${cy}px;width:${centerD}px;height:${centerD}px">${centerInner(self)}</div>
      ${nodes}
    </div>`;
}

/* ── GRID render ─────────────────────────────────────────────────────────── */
function renderGrid(){
  $('stage').innerHTML = `<div class="grid ${busyNow() ? 'lock' : ''}">${M.map(card).join('')}</div>`;
}
function card(m){
  const cls = ['card', m.self && 'self', !m.online && 'off',
               sel.has(m.id) && 'sel',
               (run && run.fading) && 'fading',
               (!m.self && m.online && !busyNow()) && 'pickable'].filter(Boolean).join(' ');
  const mark = m.self
    ? `<span class="selftag">this computer</span>`
    : (m.online ? `<span class="pick">${ic('check')}</span>` : '');
  return `<div class="${cls}" data-id="${m.id}" role="checkbox" aria-checked="${sel.has(m.id)}" tabindex="0">
    <div class="row1"><span class="dot"></span><span class="mname">${esc(m.name)}</span>${mark}</div>
    ${appRow(m, 'approw')}
    <div class="foot">${sndHtml(m)}${slotHtml(m)}</div>
  </div>`;
}

/* ── bar ─────────────────────────────────────────────────────────────────── */
function renderStatus(){
  const st = $('status');
  st.classList.toggle('fade', !!(run && run.fading));
  if (!run){ st.hidden = true; st.innerHTML = ''; return; }
  st.hidden = false;
  if (!run.finished){
    st.innerHTML = `<b>${run.label}</b> · ${run.done} of ${run.total}…`;
  } else {
    const c = {ok:0, accepted:0, fail:0, noreply:0};
    run.res.forEach(v => { if (v in c) c[v]++; });
    st.innerHTML = `<b>${run.label}</b>
      ${c.ok ? `<span class="ok">${c.ok} done</span>` : ''}
      ${c.accepted ? `<span class="ok">${c.accepted} accepted</span>` : ''}
      ${c.fail ? `<span class="fail">${c.fail} failed</span>` : ''}
      ${c.noreply ? `<span>${c.noreply} no reply</span>` : ''}`;
  }
}
function renderVerbs(){
  $('verbs').innerHTML = `
    <span class="count">${sel.size} chosen</span>
    <div class="vgroup">
      <span class="glabel">Session</span>
      <div class="vrow">
        <button class="verb" onclick="act('mute')">${ic('mute')}Mute</button>
        <button class="verb" onclick="act('unmute')">${ic('vol')}Sound on</button>
        <button class="verb" onclick="openVolume()">${ic('slid')}Volume</button>
        <button class="verb" onclick="act('lock')">${ic('lock')}Lock</button>
        <button class="verb" onclick="act('unlock')">${ic('unlock')}Unlock</button>
      </div>
    </div>
    <div class="vgroup">
      <span class="glabel">Apps</span>
      <div class="vrow">
        <button class="verb" onclick="openApps()">${ic('apps')}Open app</button>
        <button class="verb" onclick="act('close-app')">${ic('close')}Close app</button>
        <button class="verb" onclick="openUrl()">${ic('globe')}Open page</button>
      </div>
    </div>
    <div class="vgroup">
      <span class="glabel">Power</span>
      <div class="vrow">
        <button class="verb danger" onclick="confirmRestart()">${ic('boot')}Restart</button>
        <button class="verb danger" onclick="confirmPoweroff()">${ic('pwr')}Power off</button>
      </div>
    </div>`;
}

/* ── selection ───────────────────────────────────────────────────────────── */
$('stage').addEventListener('click', e => {
  const el = e.target.closest('[data-id]'); if (!el) return;
  toggle(byId(el.dataset.id));
});
$('stage').addEventListener('keydown', e => {
  if (e.key !== ' ' && e.key !== 'Enter') return;
  const el = e.target.closest('[data-id]'); if (!el) return;
  e.preventDefault(); toggle(byId(el.dataset.id));
});
function toggle(m){
  if (!m || m.self || !m.online || busyNow()) return;
  clearSettled();
  sel.has(m.id) ? sel.delete(m.id) : sel.add(m.id);
  render();
}
function setView(v){ view = v; render(); }
$('all').onclick  = () => { clearSettled(); M.forEach(m => { if (!m.self && m.online) sel.add(m.id); }); render(); };
$('none').onclick = () => { clearSettled(); sel.clear(); render(); };
window.addEventListener('resize', () => { if (view === 'ring') render(); });

/* ── sheets ──────────────────────────────────────────────────────────────── */
function openSheet(html){ $('sheet').innerHTML = html; $('ovl').classList.add('show'); }
function closeSheet(){ $('ovl').classList.remove('show'); }
$('ovl').addEventListener('click', e => { if (e.target === $('ovl')) closeSheet(); });

/* apps every selected machine has — the pushable set */
function selApps(){
  const ms = [...sel].map(byId).filter(Boolean);
  let apps = (ms[0] && ms[0].catalog) || [];
  for (const m of ms.slice(1))
    apps = apps.filter(a => m.catalog.some(b => b.id === a.id));
  return apps;
}

let pickApp = null, sheetApps = [];
function openApps(){
  if (!sel.size) return;
  pickApp = null;
  sheetApps = selApps();
  openSheet(`
    <h2>Open an app</h2>
    <div class="appgrid">${sheetApps.map((a,i) => `
      <button class="apptile" style="--cat:${CAT[a.category] || '105,113,128'}" onclick="chooseApp(${i})" id="ap${i}">
        <span class="glyph">${esc((a.name || '?')[0])}</span><span class="an">${esc(a.name)}</span>
      </button>`).join('')}
    </div>
    <div class="sfoot">
      <button class="btn quiet" onclick="closeSheet()">Cancel</button>
      <button class="btn primary" onclick="applyApp()">Open on ${sel.size} machine${sel.size>1?'s':''}</button>
    </div>`);
}
function chooseApp(i){
  pickApp = sheetApps[i];
  sheetApps.forEach((_,j) => $('ap'+j).classList.toggle('on', j === i));
}
function applyApp(){
  if (pickApp) act('open-app', {app: pickApp.id});
}

function openUrl(){
  if (!sel.size) return;
  openSheet(`
    <h2>Open a web page</h2>
    <input class="field" id="url" placeholder="library.local or a web address">
    <div class="sfoot">
      <button class="btn quiet" onclick="closeSheet()">Cancel</button>
      <button class="btn primary" onclick="applyUrl()">Open on ${sel.size} machine${sel.size>1?'s':''}</button>
    </div>`);
  $('url').focus();
}
function applyUrl(){
  const v = $('url').value.trim();
  if (v) act('open-url', {url: v});
}

function openVolume(){
  if (!sel.size) return;
  openSheet(`
    <h2>Set volume</h2>
    <div class="volrow">
      <input type="range" id="vr" min="0" max="100" value="40"
             oninput="document.getElementById('vn').textContent = this.value + '%'">
      <span class="volnum" id="vn">40%</span>
    </div>
    <div class="sfoot">
      <button class="btn quiet" onclick="closeSheet()">Cancel</button>
      <button class="btn primary" onclick="applyVolume()">Set on ${sel.size} machine${sel.size>1?'s':''}</button>
    </div>`);
}
function applyVolume(){
  act('volume', {value: +$('vr').value});
}

function confirmRestart(){
  if (!sel.size) return;
  openSheet(`
    <h2>Restart ${sel.size} machine${sel.size>1?'s':''}?</h2>
    <p>Anything the children are doing now will close. Machines come back in about a minute.</p>
    <div class="sfoot">
      <button class="btn quiet" onclick="closeSheet()">Cancel</button>
      <button class="btn dangerf" onclick="act('restart')">Restart</button>
    </div>`);
}
function confirmPoweroff(){
  if (!sel.size) return;
  openSheet(`
    <h2>Power off ${sel.size} machine${sel.size>1?'s':''}?</h2>
    <p>Anything the children are doing now will close. Someone must switch the machines on again by hand.</p>
    <div class="sfoot">
      <button class="btn quiet" onclick="closeSheet()">Cancel</button>
      <button class="btn dangerf" onclick="act('poweroff')">Power off</button>
    </div>`);
}

setInterval(poll, POLL_MS);
poll();
