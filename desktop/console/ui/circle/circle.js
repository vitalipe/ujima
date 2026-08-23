/* Circle panel — thin client over the circle server.
   Server truth: GET /ui/circle (peers + the active action), polled every 2s.
   Local truth: selection and sheets — per-viewer state only.
   Verbs POST /circle/<verb> {targets,...} and re-poll; machine screens render
   the action's per-peer statuses (pending / ok / fail / noreply / accepted). */

const POLL_MS       = 2000;
const RESULT_LINGER = 4000;   // clean runs clear on their own; failures stick
const FADE_MS       = 450;    // matches the .45s transitions in circle.css

const CAT = {learn:'203,168,120', office:'168,150,201', create:'206,147,166',
             web:'134,184,126', explore:'126,158,214', system:'136,192,208'};

/* one voice: btn = the bar's short label, label = the sentence form used by
   both the hover hints and the running/settled toast.
   The class verbs are a pair of pairs: Focus/Release take a machine and hand it
   back, Lock/Unlock do the same for the whole screen; Close is Release that also
   clears the desk. Opening an app WITHOUT taking control is not a circle gesture. */
const VERBS = {
  'mute':      {btn: 'Mute',      label: 'Mute'},
  'unmute':    {btn: 'Sound on',  label: 'Sound on'},
  'volume':    {btn: 'Volume',    label: 'Set volume'},
  'lock':      {btn: 'Lock',      label: 'Lock screens'},
  'unlock':    {btn: 'Unlock',    label: 'Unlock screens'},
  'focus':     {btn: 'Focus',     label: 'Keep to one app'},
  'release':   {btn: 'Release',   label: 'Hand back'},
  'close-app': {btn: 'Close',     label: 'Close the app'},
  'open-url':  {btn: 'Open page', label: 'Open page'},
  'restart':   {btn: 'Restart',   label: 'Restart'},
  'poweroff':  {btn: 'Power off', label: 'Power off'}};

let S    = null;          // last /ui/circle payload
let M    = [];            // render models of S.peers
let sel  = new Set();
let run  = null;          // computed from lastAction on each render

/* discovery lifecycle: startup opens in scanning; rescan re-enters it; the
   reveal staggers machines in as they are "found" (mock fakes the delay) */
let scanning  = true;
let revealing = false;

/* the server owns the sweep; setting `scanning` after the POST lands means a
   poll can never catch a stale not-running */
async function rescanNow(){
  if (scanning || busyNow()) return;
  hint = '';
  clearSettled();
  sel.clear();
  try { await fetch('/console/rescan', {method: 'POST'}); } catch (e) {}
  scanning = true;
  render();
}
function finishScan(){
  scanning  = false;
  revealing = true;
  render();
  setTimeout(() => { revealing = false; render(); }, M.length*70 + 700);
}

let lastAction = null;    // latest action seen, {..., live:bool}

/* ── server state ────────────────────────────────────────────────────────── */
const esc = s => String(s ?? '').replace(/[&<>"']/g,
  c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

/* held = under the circle's hold. Lock is a hold too (a solo on the lock app),
   but it is its own state with its own pair of verbs, so it never reads as held. */
const model = p => {
  const d = p.desktop || {};
  const locked = !!d.locked;
  return {
    id:      p.id,
    name:    p.label,
    online:  !!p.online,
    self:    p.id === S.self,
    locked:  locked,
    held:    !locked && !!(d.mode && d.mode.mode === 'solo'),
    muted:   !!(p.audio && p.audio.muted),
    app:     d.running ? {n: d.running.label, c: d.running.category} : null,
    catalog: d.catalog || []};
};

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
      const j = await (await fetch('/console/job/' + lastAction.job)).json();
      lastAction = Object.assign({}, j, {live: false});
    } catch (e) { lastAction = Object.assign({}, lastAction, {live: false}); }
    scheduleResultClear();
  }

  for (const id of [...sel]){
    const m = byId(id);
    if (!m || !m.online) sel.delete(id);
  }
  if (scanning && !(s.scan && s.scan.running)) return finishScan();
  render();
}

let lastPost = null;   // {verb, extra} this panel sent — retry needs the args

function post(verb, extra, targets){
  hint = '';
  lastPost = {verb, extra: extra || {}};
  fetch('/circle/' + verb, {
    method: 'POST',
    headers: {'content-type': 'application/json'},
    body: JSON.stringify(Object.assign({targets: targets || [...sel]}, extra || {}))
  }).then(poll);
}

function act(verb, extra, targets){
  if (!sel.size || busyNow()) return;
  if (targets && !targets.length) return;
  closeSheet();
  clearSettled();
  post(verb, extra, targets);
}

/* ── the selection, read by state ───────────────────────────────────────── */
const selPeers  = () => [...sel].map(byId).filter(Boolean);
const heldSel   = () => selPeers().filter(m => m.held);
const lockedSel = () => selPeers().filter(m => m.locked);
/* a locked machine is not "held" and Release must not unlock it — the two
   gestures stay separate, so Release and Close simply pass locked machines by */
const freeSel   = () => selPeers().filter(m => !m.locked);

/* after the linger, good outcomes always release: a clean run fades away
   whole, a mixed run sheds its ok/accepted machines and keeps only the
   errors — their red screens, dead threads and retry knobs stay until a
   gesture or a retry resolves them. */
function scheduleResultClear(){
  const job = lastAction.job;
  setTimeout(() => {
    if (!(lastAction && !lastAction.live && lastAction.job === job)) return;
    const entries = Object.entries(lastAction.peers || {});
    const bad     = entries.filter(([, s]) => s === 'fail' || s === 'noreply');
    if (!bad.length) return clearSettled();
    if (bad.length === entries.length) return;      // all-error: nothing to shed
    lastAction = Object.assign({}, lastAction, {peers: Object.fromEntries(bad)});
    render();
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

/* retry the machines that failed or never answered — same verb, same args,
   fewer targets. Only for an action this panel itself sent (we hold the args) */
function canRetry(){
  return !!(lastAction && !lastAction.live && lastPost && lastPost.verb === lastAction.verb);
}
function retryOne(e, id){
  e.stopPropagation();
  if (busyNow() || !canRetry()) return;
  post(lastPost.verb, lastPost.extra, [id]);
  lastAction = null;
  render();
}
function lineRetryOn(m){
  return !!(run && run.finished && !run.fading && canRetry() &&
            (run.res.get(m.id) === 'fail' || run.res.get(m.id) === 'noreply'));
}

function runView(){
  if (!lastAction) return null;
  const pending = new Set(), res = new Map();
  for (const [id, st] of Object.entries(lastAction.peers || {}))
    st === 'pending' ? pending.add(id) : res.set(id, st);
  return {label: (VERBS[lastAction.verb] || {label: lastAction.verb}).label,
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
  x:    '<line x1="6" y1="6" x2="18" y2="18"/><line x1="18" y1="6" x2="6" y2="18"/>',
  lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',
  focus:'<path d="M4 8V5.5A1.5 1.5 0 0 1 5.5 4H8"/><path d="M16 4h2.5A1.5 1.5 0 0 1 20 5.5V8"/><path d="M20 16v2.5a1.5 1.5 0 0 1-1.5 1.5H16"/><path d="M8 20H5.5A1.5 1.5 0 0 1 4 18.5V16"/><circle cx="12" cy="12" r="2.5"/>',
  free: '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/>',
  unlock:'<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 7.9-.9"/>',
};
const ic = k => `<span class="ic"><svg viewBox="0 0 24 24">${I[k]}</svg></span>`;

/* ── shared render bits ──────────────────────────────────────────────────── */
const $  = id => document.getElementById(id);
const byId = id => M.find(m => m.id === id);

function sndHtml(m){
  return m.online ? `<span class="ic snd" title="${m.muted?'muted':'sound on'}">
    <svg viewBox="0 0 24 24">${I[m.muted?'mute':'vol']}</svg></span>` : '';
}
/* Locked replaces the app name — a locked screen shows nothing else. Held does
   NOT: which app they are held in is the whole point, so the row keeps the name
   and only swaps the category dot for the focus mark. */
function appRow(m, cls){   /* cls: 'napp' (ring) or 'approw' (grid) */
  if (!m.online) return `<div class="${cls} offline"><span class="txt">Off</span></div>`;
  if (m.locked)  return `<div class="${cls} lockedrow">${ic('lock')}<span class="txt">Locked</span></div>`;
  if (!m.app)    return `<div class="${cls} home"><span class="txt">Home</span></div>`;
  if (m.held)    return `<div class="${cls} heldrow">${ic('focus')}<span class="txt">${esc(m.app.n)}</span></div>`;
  return `<div class="${cls}" style="--cat:${CAT[m.app.c] || '105,113,128'}">
            <span class="catdot"></span><span class="txt">${esc(m.app.n)}</span>
          </div>`;
}

function render(){
  if (!S) return;
  run = runView();
  $('counts').textContent =
    `${M.length} machines · ${M.filter(m => m.online).length} on`;
  $('rescan').classList.toggle('on', scanning);
  $('rescan').classList.toggle('off', busyNow());
  $('rescan-label').textContent = scanning ? 'Scanning…' : 'Rescan';

  renderRing();

  const bar = $('bar');
  bar.classList.toggle('show', sel.size > 0);
  bar.classList.toggle('busy', busyNow());
  renderVerbs();
}

/* ── RING render ─────────────────────────────────────────────────────────── */
/* threads exist only while a command is in flight; a machine that answered
   hands its thread off to the frame color, one that never answered keeps a
   dead red-dashed thread until the results clear. Selection draws no lines. */
function spokeCls(m){
  let cls = 'spoke';
  if (run && !run.fading && run.pending.has(m.id)) cls += ' pending';
  else if (run && !run.fading && run.res.get(m.id) === 'noreply') cls += ' noreply';
  return cls;
}
function packetCls(m){
  return 'packet' + ((run && run.pending.has(m.id)) ? ' on' : '');
}
function retryCls(m){
  return 'lineretry' + (lineRetryOn(m) ? ' on' : '');
}
function nodeCls(m, tierCls){
  const res = (run && !run.fading && run.res.has(m.id)) && 'res-' + run.res.get(m.id);
  return ['node', tierCls, !m.online && 'off', sel.has(m.id) && 'sel', res,
          screenCls(m),
          (run && run.fading) && 'fading',
          (m.online && !busyNow()) && 'pickable'].filter(Boolean).join(' ');
}
/* flat computer glyph — the screen shows STATE, not the running app: loading
   dots in flight, a check on done, an X on error, a lock when locked, dead
   when offline. Every glyph is always in the markup, hidden by default and
   revealed by node classes, so the svg never changes and transitions animate */
function monSvg(hub){
  return `<svg class="mon" viewBox="0 0 48 42" aria-hidden="true">
    <rect class="mon-frame" x="1.5" y="1.5" width="45" height="31" rx="4.5"/>
    <rect class="mon-screen" x="6" y="6" width="36" height="22" rx="2"/>
    <g class="scr scr-lock"><rect x="19.5" y="15" width="9" height="7.5" rx="1.5"/>
      <path d="M21.5 15v-2.6a2.5 2.5 0 0 1 5 0V15"/></g>
    <g class="scr scr-hold"><path d="M15 12.6V10h3"/><path d="M33 12.6V10h-3"/>
      <path d="M15 21.4V24h3"/><path d="M33 21.4V24h-3"/><circle cx="24" cy="17" r="2.3"/></g>
    <g class="scr scr-load"><circle cx="17" cy="17" r="2.2"/><circle cx="24" cy="17" r="2.2"/><circle cx="31" cy="17" r="2.2"/></g>
    <g class="scr scr-ok"><polyline points="17.5 17.5 22 22 30.5 12.5"/></g>
    <g class="scr scr-err"><line x1="19.5" y1="12.5" x2="28.5" y2="21.5"/><line x1="28.5" y1="12.5" x2="19.5" y2="21.5"/></g>
    ${hub ? `<g class="scr scr-cog">
        <circle cx="24" cy="17" r="3.1"/>
        <line x1="30.6" y1="17" x2="28.4" y2="17"/><line x1="17.4" y1="17" x2="19.6" y2="17"/>
        <line x1="24" y1="10.4" x2="24" y2="12.6"/><line x1="24" y1="23.6" x2="24" y2="21.4"/>
        <line x1="28.7" y1="12.3" x2="27.1" y2="13.9"/><line x1="28.7" y1="21.7" x2="27.1" y2="20.1"/>
        <line x1="19.3" y1="12.3" x2="20.9" y2="13.9"/><line x1="19.3" y1="21.7" x2="20.9" y2="20.1"/>
      </g>
      <text id="hub-num" class="scr scr-num" x="24" y="21.4" text-anchor="middle">${sel.size || ''}</text>`
    : ''}
    <path class="mon-stand" d="M19.5 36.5h9l2 4h-13z"/>
  </svg>`;
}

/* which screen state the machine shows; res-* node classes carry results */
function screenCls(m){
  if (!m.online) return '';
  if (run && run.pending.has(m.id)) return 'busy';
  if (run && !run.fading && run.res.has(m.id)) return '';
  if (m.locked) return 'locked';
  if (m.held)   return 'held';
  return '';
}

function nodeInner(m){
  return `${monSvg()}
    <span class="nname">${esc(m.name)}</span>
    ${appRow(m, 'napp')}
    <div class="nfoot">${sndHtml(m)}</div>`;
}
/* the hub is mission control: a bigger machine glyph whose screen carries the
   run state through the SAME classes the peers use (busy dots / check / X),
   with fleet counts + selection pills when idle and results + dismiss after */
/* the hub button: retry-all when a failed run is on screen, otherwise the
   two-mode selection toggle — choose everyone, or clear when all are chosen */
function failedIds(){
  return run ? [...run.res.entries()]
                 .filter(([, s]) => s === 'fail' || s === 'noreply')
                 .map(([id]) => id)
             : [];
}
function retryAll(){
  const targets = failedIds();
  if (!targets.length) return;
  post(lastPost.verb, lastPost.extra, targets);
  lastAction = null;
  render();
}
function hubClick(){
  if (busyNow() || scanning) return;
  const targets = M.filter(m => !m.self && m.online);
  clearSettled();
  if (targets.length && targets.every(m => sel.has(m.id))) sel.clear();
  else targets.forEach(m => sel.add(m.id));
  render();
}

function hubCls(){
  const bad = run && run.finished &&
              [...run.res.values()].some(s => s === 'fail' || s === 'noreply');
  return ['node center',
          run && !run.finished && 'busy',
          run && run.finished && !run.fading && (bad ? 'res-fail' : 'res-ok'),
          run && run.fading && !scanning && 'fading',
          !run && (sel.size ? 'has-sel' : 'calm')].filter(Boolean).join(' ');
}

/* the hub renders into four persistent slots — actions on top, glyph, two
   text lines — so the svg is never recreated (state transitions stay soft)
   and content swaps never move the glyph */
let hint = '';   // transient hover hint, shown in the hub's text lines

function setHint(h){
  h = h || '';
  if (h === hint) return;
  hint = h;
  const l1 = $('hub-l1');
  if (!l1) return;
  const s = hubSlots();
  setInner(l1, s.l1);
  setInner($('hub-l2'), s.l2);
}
function peerHint(m){
  if (!m || m.self) return '';
  if (!m.online) return `${esc(m.name)} is off`;
  return (sel.has(m.id) ? 'Unselect ' : 'Select ') + esc(m.name);
}
function hubHint(){
  if (scanning) return '';
  const targets = M.filter(m => !m.self && m.online);
  if (targets.length && targets.every(m => sel.has(m.id))) return 'Clear selection';
  return `Select all ${targets.length}`;
}

function crownBtns(){
  const n = run && run.finished && !run.fading && canRetry() ? failedIds().length : 0;
  return n
    ? `<button class="hubretry" data-hh="Retry ${n} machine${n === 1 ? '' : 's'}" onclick="retryBtnClick(event)">
         <span class="ic"><svg viewBox="0 0 24 24">${I.boot}</svg></span><span>${n}</span></button>`
    : '';
}
function retryBtnClick(e){
  e.stopPropagation();
  if (busyNow() || !canRetry()) return;
  retryAll();
}

function hubSlots(){
  const top = crownBtns();
  if (scanning) return {top, l1: `<span class="hubprog">looking for machines…</span>`, l2: ''};
  if (run && !run.finished)
    return {top, l1: `<span class="hubverb">${run.label}</span>`,
            l2: `<span class="hubprog">${run.done} of ${run.total}…</span>`};
  if (hint) return {top, l1: `<span class="hubhint">${hint}</span>`, l2: ''};
  if (run){
    const c = {ok:0, accepted:0, fail:0, noreply:0};
    run.res.forEach(v => { if (v in c) c[v]++; });
    return {top, l1: `<span class="hubverb">${run.label}</span>`,
            l2: [c.ok ? `<span class="ok">${c.ok} done</span>` : '',
                 c.accepted ? `<span class="ok">${c.accepted} accepted</span>` : '',
                 c.fail ? `<span class="fail">${c.fail} failed</span>` : '',
                 c.noreply ? `<span>${c.noreply} no reply</span>` : '']
                .filter(Boolean).join('<span class="hubdot">·</span>')};
  }
  return {top, l1: '', l2: holdSummary()};
}

/* at rest the hub says who is not free — the one place the whole class is
   readable without hunting the ring */
function holdSummary(){
  const held   = M.filter(m => m.online && m.held).length;
  const locked = M.filter(m => m.online && m.locked).length;
  return [held   ? `<span class="hold">${held} focused</span>`     : '',
          locked ? `<span class="warn">${locked} locked</span>` : '']
         .filter(Boolean).join('<span class="hubdot">·</span>');
}

function centerInner(){
  const s = hubSlots();
  return `<span class="hubslot hubtop" id="hub-top">${s.top}</span>
    ${monSvg(true)}
    <span class="hubslot hubl" id="hub-l1">${s.l1}</span>
    <span class="hubslot hubl" id="hub-l2">${s.l2}</span>`;
}
function setInner(el, html){ if (el.innerHTML !== html) el.innerHTML = html; }

/* size tiers by population: the ring always spans the available space, and
   the machines grow as the class shrinks */
function ringTier(n){
  if (n <= 8)  return {cls: 'big',   d: 170, iconR: 40, iconOff: 30};
  if (n <= 13) return {cls: 'mid',   d: 132, iconR: 30, iconOff: 28};
  return       {cls: 'small', d: 76,  iconR: 20, iconOff: 9};
}

function renderRing(){
  const others = M.filter(m => !m.self);

  const W = Math.min(window.innerWidth - 40, 980);
  const H = Math.max(window.innerHeight - 160, 430);   // floating chrome + bar
  const cx = W/2, cy = H/2;

  const n      = Math.max(others.length, 1);
  const onks   = others.filter(m => m.online);
  const allIn  = onks.length > 0 && onks.every(m => sel.has(m.id));
  const tier = ringTier(n);
  const d    = tier.d, centerD = 196, gap = 5;
  const maxR = Math.min(W, H)/2 - d/2 - 26;
  const minR = centerD/2 + gap + d/2 + 8;
  const R    = Math.max(minR, maxR);
  const sig  = [W, H, others.map(m => m.id).join(',')].join('|');

  /* same ring already on stage -> patch classes/content on the existing
     elements, so the spoke CSS transitions actually run (a rebuilt element
     just appears in its end state) */
  const wrap = $('stage').firstElementChild;
  if (wrap && wrap.dataset && wrap.dataset.sig === sig){
    wrap.classList.toggle('lock', busyNow());
    wrap.classList.toggle('scanning', scanning);
    wrap.classList.toggle('reveal', revealing);
    wrap.classList.toggle('all-in', allIn);
    for (const m of others){
      $('spoke-' + m.id).setAttribute('class', spokeCls(m));
      $('packet-' + m.id).setAttribute('class', packetCls(m));
      $('retry-' + m.id).setAttribute('class', retryCls(m));
      const node = $('node-' + m.id);
      node.setAttribute('class', nodeCls(m, tier.cls));
      node.setAttribute('aria-checked', sel.has(m.id));
      setInner(node, nodeInner(m));
    }
    const hub = $('ring-center');
    hub.setAttribute('class', hubCls());
    const s = hubSlots();
    setInner($('hub-top'), s.top);
    setInner($('hub-l1'), s.l1);
    setInner($('hub-l2'), s.l2);
    $('hub-num').textContent = String(sel.size || '');
    return;
  }

  const pos = {};
  others.forEach((m,i) => {
    const a = -Math.PI/2 + i * 2*Math.PI/others.length;
    pos[m.id] = {x: cx + R*Math.cos(a), y: cy + R*Math.sin(a),
                 ux: Math.cos(a), uy: Math.sin(a)};
  });

  /* each peer gets a thread line plus a packet line: the packet is a short
     dash swept along the same geometry by animating stroke-dashoffset —
     dasharray's gap exceeds the line length so only one dash is ever visible */
  const iconR  = tier.iconR;
  const hubOff = 20, hubIR = 66;                // threads anchor on the hub glyph
  const hy     = cy - hubOff;
  const retryBtns = [];
  const spokes = others.map((m, i) => {
    const p  = pos[m.id];
    const ix = p.x, iy = p.y - tier.iconOff;
    const vx = ix - cx, vy = iy - hy, dl = Math.hypot(vx, vy);
    const ux = vx/dl, uy = vy/dl;
    const x1 = cx + ux*hubIR,         y1 = hy + uy*hubIR;
    const x2 = ix - ux*(iconR + gap), y2 = iy - uy*(iconR + gap);
    const L  = Math.ceil(Math.hypot(x2 - x1, y2 - y1));
    retryBtns.push(`<button id="retry-${m.id}" class="${retryCls(m)}"
      style="left:${(x1 + x2)/2}px;top:${(y1 + y2)/2}px"
      onclick="retryOne(event, '${m.id}')" title="retry ${esc(m.name)}" aria-label="retry ${esc(m.name)}">
      <span class="ic"><svg viewBox="0 0 24 24">${I.boot}</svg></span></button>`);
    return `<line id="spoke-${m.id}" class="${spokeCls(m)}" x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}"/>
      <line id="packet-${m.id}" class="${packetCls(m)}" x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}"
            stroke-dasharray="12 ${L + 24}" style="--end:${-L}px;--delay:${(i % 5) * .15}s"/>`;
  }).join('');

  const nodes = others.map((m, i) => `
    <div id="node-${m.id}" class="${nodeCls(m, tier.cls)}" data-id="${m.id}"
         style="left:${pos[m.id].x}px;top:${pos[m.id].y}px;width:${d}px;height:${d}px;--i:${i}"
         role="checkbox" aria-checked="${sel.has(m.id)}" tabindex="0">${nodeInner(m)}</div>`).join('');

  $('stage').innerHTML = `
    <div class="ringwrap tier-${tier.cls} ${busyNow() ? 'lock' : ''}${scanning ? ' scanning' : ''}${revealing ? ' reveal' : ''}${allIn ? ' all-in' : ''}" data-sig="${sig}" style="width:${W}px;height:${H}px">
      <svg class="spokes" width="${W}" height="${H}">${spokes}</svg>
      <div id="ring-center" class="${hubCls()}" role="button" tabindex="0"
           aria-label="select all or clear"
           style="left:${cx}px;top:${cy}px;width:${centerD}px;height:${centerD}px">${centerInner()}</div>
      ${nodes}
      ${retryBtns.join('')}
    </div>`;
}

/* ── action bar ─────────────────────────────────────────────────────────── */
/* Class holds four gestures in two pairs. Focus reads "Change focus" once the
   whole selection is already held — the same button, told what it will do.
   Release and Close carry the count they will actually reach: locked machines
   are passed by, so the number on the button is the promise. */
function renderVerbs(){
  const vbtn = (verb, icon, onclick, o) =>
    `<button class="verb${(o||{}).danger ? ' danger' : ''}${(o||{}).off ? ' off' : ''}"
       data-verb="${verb}"
       onclick="${onclick || `act('${verb}')`}">${ic(icon)}${(o||{}).label || VERBS[verb].btn}${
         (o||{}).badge ? `<span class="vnum">${(o||{}).badge}</span>` : ''}</button>`;
  const held = heldSel().length, free = freeSel().length;
  $('verbs').innerHTML = `
    <span class="count">${sel.size} selected</span>
    <div class="vgroup">
      <span class="glabel">Class</span>
      <div class="vrow">
        ${vbtn('focus', 'focus', 'openFocus()',
               {label: held === sel.size && held ? 'Change focus' : 'Focus'})}
        ${vbtn('release', 'free', `act('release', null, freeIds())`,
               {off: !free, badge: held || ''})}
        ${vbtn('close-app', 'close', `confirmClose()`, {off: !free})}
        ${vbtn('open-url', 'globe', 'openUrl()')}
      </div>
    </div>
    <div class="vgroup">
      <span class="glabel">Session</span>
      <div class="vrow">
        ${vbtn('mute', 'mute')}
        ${vbtn('unmute', 'vol')}
        ${vbtn('volume', 'slid', 'openVolume()')}
        ${vbtn('lock', 'lock')}
        ${vbtn('unlock', 'unlock')}
      </div>
    </div>
    <div class="vgroup">
      <span class="glabel">Power</span>
      <div class="vrow">
        ${vbtn('restart', 'boot', 'confirmRestart()', true)}
        ${vbtn('poweroff', 'pwr', 'confirmPoweroff()', true)}
      </div>
    </div>
    <button class="selx" onclick="clearSel()" title="clear selection" aria-label="clear selection">
      <span class="ic"><svg viewBox="0 0 24 24"><line x1="6" y1="6" x2="18" y2="18"/><line x1="18" y1="6" x2="6" y2="18"/></svg></span>
    </button>`;
}

/* ── selection ───────────────────────────────────────────────────────────── */
$('stage').addEventListener('click', e => {
  if (e.target.closest('#ring-center')){ if (e.target.closest('.mon')) hubClick(); return; }
  const el = e.target.closest('[data-id]'); if (!el) return;
  toggle(byId(el.dataset.id));
});
$('stage').addEventListener('mouseover', e => {
  const hb = e.target.closest('.hubretry');
  if (hb && hb.dataset && hb.dataset.hh){
    const wrap0 = $('stage').firstElementChild;
    if (wrap0 && wrap0.classList) wrap0.classList.toggle('hub-hot', false);
    return setHint(hb.dataset.hh);
  }
  const hubHot = !!(e.target.closest('#ring-center') && e.target.closest('.mon'));
  const wrap = $('stage').firstElementChild;
  if (wrap && wrap.classList) wrap.classList.toggle('hub-hot', hubHot);
  if (e.target.closest('.lineretry')){
    const m = byId(e.target.closest('.lineretry').id.replace('retry-', ''));
    return setHint(m ? 'Retry ' + esc(m.name) : '');
  }
  if (hubHot) return setHint(hubHint());
  const el = e.target.closest('[data-id]');
  if (el) return setHint(peerHint(byId(el.dataset.id)));
  setHint('');
});
$('stage').addEventListener('mouseout', () => {
  const wrap = $('stage').firstElementChild;
  if (wrap && wrap.classList) wrap.classList.toggle('hub-hot', false);
  setHint('');
});
function verbHint(verb){
  const machines = n => `${n} machine${n === 1 ? '' : 's'}`;
  if (verb === 'release' || verb === 'close-app'){
    const free = freeSel().length, lk = lockedSel().length;
    if (!free) return `All ${machines(lk)} locked — Unlock hands those back`;
    return `${VERBS[verb].label} · ${machines(free)}` +
           (lk ? ` · ${lk} locked, skipped` : '');
  }
  return `${VERBS[verb].label} · ${machines(sel.size)}`;
}
$('bar').addEventListener('mouseover', e => {
  if (e.target.closest('.selx')) return setHint('Clear selection');
  const v = e.target.closest('[data-verb]');
  if (v) return setHint(verbHint(v.dataset.verb));
  setHint('');
});
$('bar').addEventListener('mouseout', () => setHint(''));

$('stage').addEventListener('keydown', e => {
  if (e.key !== ' ' && e.key !== 'Enter') return;
  if (e.target.closest('.hubretry,.lineretry')) return;   // buttons handle their own keys
  if (e.target.closest('#ring-center')){ e.preventDefault(); return hubClick(); }
  const el = e.target.closest('[data-id]'); if (!el) return;
  e.preventDefault(); toggle(byId(el.dataset.id));
});
function toggle(m){
  if (!m || m.self || !m.online || busyNow()) return;
  clearSettled();
  sel.has(m.id) ? sel.delete(m.id) : sel.add(m.id);
  if (hint) hint = peerHint(m);
  render();
}
function clearSel(){ clearSettled(); sel.clear(); render(); }
window.addEventListener('resize', render);
$('rescan').addEventListener('mouseover', () => setHint('Rescan the circle'));
$('rescan').addEventListener('mouseout', () => setHint(''));

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

/* Focus: one app for everyone, or each machine's own. "Where they are now" is
   sent as the literal "current" — the server resolves it per machine, so five
   children in five different apps are all held where they already are. */
let pickApp = null, sheetApps = [];
const freeIds  = () => freeSel().map(m => m.id);
/* free, and with something to be held in — a locked machine's "current app" is
   the lock screen, which is not a thing to focus anyone on */
const busyPeers = () => freeSel().filter(m => m.app);

function openFocus(){
  if (!sel.size) return;
  pickApp = null;
  sheetApps = selApps();
  const here = busyPeers().length, n = sel.size;
  openSheet(`
    <h2>Keep the class to one app</h2>
    <button class="hereopt${here ? '' : ' off'}" id="ap-here" onclick="chooseApp('current')">
      <span class="ic"><svg viewBox="0 0 24 24">${I.focus}</svg></span>
      <span class="ht"><b>Where they are now</b>
        <em>${here === n ? `each of the ${n} in its own app`
                         : `${here} of ${n} have an app open — the rest are skipped`}</em></span>
    </button>
    <div class="orline"><span>or the same app for everyone</span></div>
    <div class="appgrid">${sheetApps.map((a,i) => `
      <button class="apptile" style="--cat:${CAT[a.category] || '105,113,128'}" onclick="chooseApp(${i})" id="ap${i}">
        <span class="glyph">${esc((a.label || '?')[0])}</span><span class="an">${esc(a.label)}</span>
      </button>`).join('')}
    </div>
    <p>No switching, no closing, no bars — until you hand the machines back.</p>
    <div class="sfoot">
      <button class="btn quiet" onclick="closeSheet()">Cancel</button>
      <button class="btn primary off" id="focusgo" onclick="applyFocus()">Focus ${n} machine${n>1?'s':''}</button>
    </div>`);
}
function chooseApp(i){
  pickApp = i === 'current' ? 'current' : sheetApps[i];
  sheetApps.forEach((_,j) => $('ap'+j).classList.toggle('on', j === i));
  $('ap-here').classList.toggle('on', i === 'current');
  const n = (pickApp === 'current' ? busyPeers() : selPeers()).length;
  const go = $('focusgo');
  go.classList.toggle('off', !n);
  go.textContent = `Focus ${n} machine${n === 1 ? '' : 's'}`;
}
function applyFocus(){
  if (!pickApp) return;
  if (pickApp === 'current') return act('focus', {app: 'current'}, busyPeers().map(m => m.id));
  act('focus', {app: pickApp.id}, freeIds());
}

/* Closing lets go of the hold on its own — the machine has to leave it to close
   the app it was held in — but it is not a hand-back: whatever the circle set on
   the class stays set. Only Release drops that. */
function confirmClose(){
  const n = freeSel().length;
  if (!n) return;
  openSheet(`
    <h2>Close on ${n} machine${n>1?'s':''}?</h2>
    <p>Whatever they have open closes and the machines go home. Anything unsaved is lost.</p>
    <div class="sfoot">
      <button class="btn quiet" onclick="closeSheet()">Cancel</button>
      <button class="btn primary" onclick="act('close-app', null, freeIds())">Close</button>
    </div>`);
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
