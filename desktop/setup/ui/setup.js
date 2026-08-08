/* Setup panel — thin client over the console server.
   Server truth: GET /ui/setup (superset peers, machine :now precomputed),
   polled every 2s. Local truth: which machine/tab is open, section drafts.
   Every machine write is a job: POST /setup/* -> {job}, the button polls
   /console/job/<id> until finished and morphs with the outcome — Done
   releases after the linger, Failed / No reply stick, click retries.
   The detail pane rebuilds only on pick / tab switch / online flips, so
   polling never stomps a draft the user is typing. */

const POLL_MS = 2000;
const JOB_MS  = 500;
const LINGER  = 4000;   // shipped discipline: Done releases, errors stick

const TZ = ['Africa/Dar_es_Salaam','Africa/Nairobi','UTC'];

/* the full xkb base layout catalog (rules/base.lst, layouts section) */
const LAYCAT = [
 {k:'af',n:'Afghani'},{k:'al',n:'Albanian'},{k:'et',n:'Amharic'},{k:'ara',n:'Arabic'},
 {k:'ma',n:'Arabic (Morocco)'},{k:'sy',n:'Arabic (Syria)'},{k:'am',n:'Armenian'},
 {k:'az',n:'Azerbaijani'},{k:'ml',n:'Bambara'},{k:'bd',n:'Bangla'},{k:'by',n:'Belarusian'},
 {k:'be',n:'Belgian'},{k:'dz',n:'Berber (Algeria)'},{k:'ba',n:'Bosnian'},{k:'brai',n:'Braille'},
 {k:'bg',n:'Bulgarian'},{k:'mm',n:'Burmese'},{k:'cn',n:'Chinese'},{k:'hr',n:'Croatian'},
 {k:'cz',n:'Czech'},{k:'dk',n:'Danish'},{k:'mv',n:'Dhivehi'},{k:'nl',n:'Dutch'},
 {k:'bt',n:'Dzongkha'},{k:'au',n:'English (Australia)'},{k:'cm',n:'English (Cameroon)'},
 {k:'gh',n:'English (Ghana)'},{k:'ng',n:'English (Nigeria)'},{k:'za',n:'English (South Africa)'},
 {k:'gb',n:'English (UK)'},{k:'us',n:'English (US)'},{k:'epo',n:'Esperanto'},
 {k:'ee',n:'Estonian'},{k:'fo',n:'Faroese'},{k:'ph',n:'Filipino'},{k:'fi',n:'Finnish'},
 {k:'fr',n:'French'},{k:'ca',n:'French (Canada)'},{k:'cd',n:'French (Congo)'},
 {k:'gn',n:'French (Guinea)'},{k:'tg',n:'French (Togo)'},{k:'ge',n:'Georgian'},
 {k:'de',n:'German'},{k:'at',n:'German (Austria)'},{k:'ch',n:'German (Switzerland)'},
 {k:'gr',n:'Greek'},{k:'il',n:'Hebrew'},{k:'hu',n:'Hungarian'},{k:'is',n:'Icelandic'},
 {k:'in',n:'Indian'},{k:'id',n:'Indonesian'},{k:'ie',n:'Irish'},{k:'it',n:'Italian'},
 {k:'jp',n:'Japanese'},{k:'kz',n:'Kazakh'},{k:'kh',n:'Khmer (Cambodia)'},{k:'kr',n:'Korean'},
 {k:'kg',n:'Kyrgyz'},{k:'la',n:'Lao'},{k:'lv',n:'Latvian'},{k:'lt',n:'Lithuanian'},
 {k:'mk',n:'Macedonian'},{k:'my',n:'Malay'},{k:'mt',n:'Maltese'},{k:'md',n:'Moldavian'},
 {k:'mn',n:'Mongolian'},{k:'me',n:'Montenegrin'},{k:'np',n:'Nepali'},{k:'no',n:'Norwegian'},
 {k:'ir',n:'Persian'},{k:'pl',n:'Polish'},{k:'pt',n:'Portuguese'},{k:'br',n:'Portuguese (Brazil)'},
 {k:'ro',n:'Romanian'},{k:'ru',n:'Russian'},{k:'rs',n:'Serbian'},{k:'lk',n:'Sinhala'},
 {k:'sk',n:'Slovak'},{k:'si',n:'Slovenian'},{k:'es',n:'Spanish'},{k:'latam',n:'Spanish (Latin America)'},
 {k:'ke',n:'Swahili (Kenya)'},{k:'tz',n:'Swahili (Tanzania)'},{k:'se',n:'Swedish'},
 {k:'tw',n:'Taiwanese'},{k:'tj',n:'Tajik'},{k:'th',n:'Thai'},{k:'bw',n:'Tswana'},
 {k:'tr',n:'Turkish'},{k:'tm',n:'Turkmen'},{k:'ua',n:'Ukrainian'},{k:'pk',n:'Urdu (Pakistan)'},
 {k:'uz',n:'Uzbek'},{k:'vn',n:'Vietnamese'},{k:'sn',n:'Wolof'},
];
const SND = [{k:'hdmi',n:'Screen'},{k:'usb',n:'USB speaker'}];

const CHECKS = [['gateway','Gateway'],['internet','Internet'],['peers','Other machines'],
                ['storage','Storage'],['clock','Clock']];

let S   = null;          // last /ui/setup payload
let cur = null, tab = 'settings';
let workLay = [];
let layQ = '';
let inflight = 0;        // jobs in flight on the CURRENT machine (drives the head glyph)
let headRes  = null;     // 'ok' | 'noreply' — the head glyph's settled state
let detailKey = '';      // cur|tab|online — the pane rebuilds only when this moves
let scanning  = false;   // a sweep is running: rail shows the hint, the page locks
let revealing = false;   // the render right after a sweep staggers the rows in

const I = {
  kbd:'<rect x="2" y="6" width="20" height="12" rx="2"/><path d="M6 10h0M10 10h0M14 10h0M18 10h0M6 14h0M18 14h0M9 14h6"/>',
  snd:'<polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><path d="M15.5 8.5a5 5 0 0 1 0 7"/>',
  boot:'<path d="M21 12a9 9 0 1 1-9-9c2.5 0 4.9 1 6.6 2.6L21 8"/><path d="M21 3v5h-5"/>',
  pwr:'<path d="M12 2v10"/><path d="M18.4 6.6a9 9 0 1 1-12.8 0"/>',
  clock:'<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>',
  tag:'<path d="M20.6 13.4 13.4 20.6a2 2 0 0 1-2.8 0L3 13V3h10l7.6 7.6a2 2 0 0 1 0 2.8z"/><line x1="7.5" y1="7.5" x2="7.51" y2="7.5"/>',
  alert:'<path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>',
  file:'<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="8" y1="13" x2="16" y2="13"/><line x1="8" y1="17" x2="13" y2="17"/>',
  act:'<polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/>',
  cpu:'<rect x="4" y="4" width="16" height="16" rx="2"/><rect x="9" y="9" width="6" height="6"/><path d="M9 1v3M15 1v3M9 20v3M15 20v3M1 9h3M1 15h3M20 9h3M20 15h3"/>',
  plus:'<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>',
  x:'<line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>',
  copy:'<rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>',
  check:'<polyline points="20 6 9 17 4 12"/>',
  cast:'<circle cx="12" cy="12" r="1.6"/><path d="M16.2 7.8a6 6 0 0 1 0 8.4"/><path d="M7.8 16.2a6 6 0 0 1 0-8.4"/><path d="M19.1 4.9a10 10 0 0 1 0 14.2"/><path d="M4.9 19.1a10 10 0 0 1 0-14.2"/>',
};
const ic = k => `<span class="ic"><svg viewBox="0 0 24 24">${I[k]}</svg></span>`;
const $  = id => document.getElementById(id);
const byId = id => S && S.peers.find(p => p.id === id);
const self = () => byId(S.self);
const layName = k => { const l = LAYCAT.find(x => x.k === k); return l ? l.n : k; };
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

const sys      = m => m.system || {};
const layouts  = m => (m.keyboard && m.keyboard.layouts) || [];
const locked   = m => !!(m.desktop && m.desktop.locked);
const fmtUp    = min => min == null ? '—' : `${Math.floor(min/60)}h ${String(min%60).padStart(2,'0')}m`;
const fmtStore = pct => pct == null ? '—' : pct + '%';
const splitNow = now => (now || 'T').split('T');   // "2026-08-08T13:05" -> [date, time]

/* the shipped machine glyph, verbatim geometry from circle.js monSvg() */
function monSvg(){
  return `<svg class="mon" viewBox="0 0 48 42" aria-hidden="true">
    <rect class="mon-frame" x="1.5" y="1.5" width="45" height="31" rx="4.5"/>
    <rect class="mon-screen" x="6" y="6" width="36" height="22" rx="2"/>
    <g class="scr scr-lock"><rect x="19.5" y="15" width="9" height="7.5" rx="1.5"/>
      <path d="M21.5 15v-2.6a2.5 2.5 0 0 1 5 0V15"/></g>
    <g class="scr scr-load"><circle cx="17" cy="17" r="2.2"/><circle cx="24" cy="17" r="2.2"/><circle cx="31" cy="17" r="2.2"/></g>
    <g class="scr scr-ok"><polyline points="17.5 17.5 22 22 30.5 12.5"/></g>
    <g class="scr scr-err"><line x1="19.5" y1="12.5" x2="28.5" y2="21.5"/><line x1="28.5" y1="12.5" x2="19.5" y2="21.5"/></g>
    <path class="mon-stand" d="M19.5 36.5h9l2 4h-13z"/>
  </svg>`;
}
function glyphCls(m, extra){
  return ['gb', m.id === S.self && 'self', !m.online && 'off', locked(m) && 'locked', extra]
    .filter(Boolean).join(' ');
}

/* ── server state ────────────────────────────────────────────────────────── */
async function poll(){
  let s;
  try { s = await (await fetch('/ui/setup')).json(); }
  catch (e) { return; }                       // server gone — keep last frame
  const first = S === null;
  S = s;
  if (!byId(cur)) { cur = S.self; detailKey = ''; }
  if (first) reveal();
  render();
}
function reveal(){
  revealing = true;
  render();
  setTimeout(() => {
    revealing = false;
    $('rescan').disabled = false;
    render();
  }, S.peers.length * 60 + 500);
}

async function postJob(url, body){
  try {
    const r = await fetch(url, {method: 'POST',
      headers: {'content-type': 'application/json'}, body: JSON.stringify(body)});
    const d = await r.json();
    return r.ok ? {job: d.job} : {err: d.error || 'request failed'};
  } catch (e) { return {err: 'no reply from the server'}; }
}
async function pollJob(id){
  for (;;){
    await new Promise(r => setTimeout(r, JOB_MS));
    let j;
    try { j = await (await fetch('/console/job/' + id)).json(); }
    catch (e) { return null; }
    if (j && j.finished) return j;
  }
}

/* ── render ──────────────────────────────────────────────────────────────── */
function render(){
  $('counts').textContent =
    `${S.peers.length} machines · ${S.peers.filter(m => m.online).length} on`;
  document.querySelector('.wrap').classList.toggle('scanning', scanning);
  renderRail();
  const m = byId(cur);
  const key = [cur, tab, m.online].join('|');
  if (key !== detailKey){ detailKey = key; renderDetail(); }
  else patchDetail(m);
}
const railOrder = () =>
  [...S.peers.filter(m => m.id === S.self), ...S.peers.filter(m => m.id !== S.self)];
function renderRail(){
  if (scanning){
    $('rail').innerHTML =
      `<div class="scanhint"><span class="spin"></span>looking for machines…</div>`;
    return;
  }
  $('rail').innerHTML = railOrder().map((m, i) => `
    <div class="mrow ${glyphCls(m)} ${m.id===cur?'on':''} ${m.online?'':'off'} ${revealing?'appear':''}"
         style="--i:${i}" onclick="pick('${m.id}')">
      ${monSvg()}<span class="nm">${esc(m.name)}</span>
      ${m.id === S.self ? `<span class="tag">this</span>` : ''}
    </div>`).join('');
}
function pick(id){
  cur = id; tab = 'settings'; inflight = 0; headRes = null;
  render();
}
function setTab(t){ tab = t; detailKey = ''; render(); }

function headGlyphCls(m){
  const state = inflight ? 'busy' : (headRes ? 'res-' + headRes : '');
  return glyphCls(m, state);
}
function statusText(m){
  return m.online ? `on · up ${fmtUp(sys(m).upMin)}` : 'off';
}
function renderDetail(){
  const m = byId(cur);
  const head = `
    <div class="dhead ${headGlyphCls(m)}" id="dhead">
      ${monSvg()}
      <h2 id="dname">${esc(m.name)}</h2>
      ${m.id === S.self ? `<span class="selftag">this computer</span>` : ''}
      <span class="st" id="dstatus">${statusText(m)}</span>
      <span class="grow"></span>
      <div class="seg" role="group">
        <button class="${tab==='settings'?'on':''}" onclick="setTab('settings')">Settings</button>
        <button class="${tab==='diag'?'on':''}" onclick="setTab('diag')">Diagnostics</button>
      </div>
    </div>`;
  if (!m.online){
    $('detail').innerHTML = head + `
      <div class="scard"><span class="sc-title">${ic('pwr')}Off</span>
        <p class="hint">${esc(m.name)} is off or not reachable. Check power and the network cable —
        it appears here again when it starts.</p>
        ${m.id === S.self ? '' : `<div class="frow"><span class="grow"></span>
          <button class="btn dangerf" onclick="confirmRemove('${m.id}')">${ic('x')}Remove from list</button>
        </div>`}</div>`;
    return;
  }
  $('detail').innerHTML = head + (tab === 'settings' ? settingsHtml(m) : diagHtml(m));
  if (tab === 'settings') refreshGates(m);
}
function refreshGates(m){
  if (m.id === S.self){ refreshSend('clock'); refreshSend('layouts'); }
  ['name','clock','layouts','snd'].forEach(refreshSave);
}
/* between rebuilds the poll only touches what can't hold a draft */
function patchDetail(m){
  const st = $('dstatus'); if (st) st.textContent = statusText(m);
  const nm = $('dname');   if (nm && nm.textContent !== m.name) nm.textContent = m.name;
  if (tab === 'settings') refreshGates(m);
}
function refreshHead(){
  const el = $('dhead');
  if (el) el.setAttribute('class', 'dhead ' + headGlyphCls(byId(cur)));
}

/* ── settings tab ────────────────────────────────────────────────────────── */
function saveBtn(id, label, icon, cls, fn){
  return `<button class="btn ${cls}" id="${id}" data-idle="${label}" data-icx="${icon||''}"
    onclick="${fn}">${icon?ic(icon):''}${label}</button>`;
}
function settingsHtml(m){
  workLay = [...layouts(m)];
  const isSelf = m.id === S.self;
  const [today, now] = splitNow(sys(m).now);
  return `
  <div class="scard">
    <span class="sc-title">${ic('tag')}Name</span>
    <div class="frow">
      <input class="field grow" id="f-name" value="${esc(m.name)}" maxlength="16"
        oninput="refreshSave('name')">
      ${saveBtn('b-name','Save','','primary',"saveSec('name')")}
    </div>
    <p class="hint">Shown in Circle and on the machine. Letters, numbers and dashes.</p>
  </div>

  <div class="scard">
    <span class="sc-title">${ic('clock')}Clock</span>
    <div class="frow">
      <select class="field" id="f-tz" onchange="refreshSend('clock');refreshSave('clock')">${
        [...new Set([...TZ, sys(m).timezone].filter(Boolean))].map(t =>
        `<option ${t===sys(m).timezone?'selected':''}>${esc(t)}</option>`).join('')}</select>
      <input class="field" id="f-date" type="date" value="${esc(today)}" data-init="${esc(today)}"
        oninput="refreshSave('clock')">
      <input class="field" id="f-time" type="time" value="${esc(now)}" data-init="${esc(now)}"
        oninput="refreshSave('clock')">
    </div>
    <div class="frow">
      ${isSelf ? '' : `<button class="btn quiet" onclick="copyClock()">${ic('clock')}Use ${esc(self().name)}’s time</button>`}
      <span class="grow"></span>
      ${isSelf ? saveBtn('b-all-clock','Send to all machines','cast','quiet',"sendAll('clock')") : ''}
      ${saveBtn('b-clock','Save','','primary',"saveSec('clock')")}
    </div>
    <p class="hint">Machines forget the time when unplugged for long. After a blackout, set it here.</p>
  </div>

  <div class="scard" id="laycard">${layCardInner(m)}</div>

  <div class="scard">
    <span class="sc-title">${ic('snd')}Sound output</span>
    ${SND.map(s => `<button class="optrow snd ${(m.audio && m.audio.out)===s.k?'on':''}" data-k="${s.k}"
        onclick="pickSnd(this)">${ic('snd')}${s.n}</button>`).join('')}
    <div class="frow"><span class="grow"></span>
      ${saveBtn('b-snd','Save','','primary',"saveSec('snd')")}
    </div>
  </div>

  ${isSelf ? `
  <div class="scard">
    <span class="sc-title">${ic('pwr')}Power</span>
    <div class="frow">
      <button class="btn dangerf" onclick="confirmSelf('restart')">${ic('boot')}Restart</button>
      <button class="btn dangerf" onclick="confirmSelf('poweroff')">${ic('pwr')}Power off</button>
    </div>
    <p class="hint">Only this computer. Other machines restart from Circle.</p>
  </div>` : ''}`;
}
function layCardInner(m){
  m = m || byId(cur);
  return `<span class="sc-title">${ic('kbd')}Keyboard layouts</span>
  ${workLay.map(k => `
    <div class="optrow static">${ic('kbd')}${layName(k)}
      <button class="rm" title="Remove" onclick="rmLay('${k}')">${ic('x')}</button>
    </div>`).join('')}
  <div class="frow">
    <button class="btn quiet" onclick="openLayPicker()">${ic('plus')}Add layout</button>
    <span class="grow"></span>
    ${m.id === S.self ? saveBtn('b-all-layouts','Send to all machines','cast','quiet',"sendAll('layouts')") : ''}
    ${saveBtn('b-layouts','Save','','primary',"saveSec('layouts')")}
  </div>`;
}
/* full-catalog picker: type to filter */
function openLayPicker(){
  layQ = '';
  openSheet(`
    <h2>Add a keyboard layout</h2>
    <input class="field" id="laysearch" placeholder="Search ${LAYCAT.length} layouts"
      oninput="layQ=this.value;fillLayList()">
    <div class="laylist" id="laylist"></div>
    <div class="sfoot"><button class="btn quiet" onclick="closeSheet()">Cancel</button></div>`);
  fillLayList();
  $('laysearch').focus();
}
function fillLayList(){
  const q = layQ.trim().toLowerCase();
  const rows = LAYCAT.filter(l => !workLay.includes(l.k) &&
                                  (!q || l.n.toLowerCase().includes(q)));
  $('laylist').innerHTML = rows.length
    ? rows.map(l => `<button class="optrow" onclick="addLay('${l.k}')">${ic('kbd')}${l.n}</button>`).join('')
    : `<p class="hint">Nothing matches “${esc(layQ)}”.</p>`;
}
function addLay(k){ workLay.push(k); closeSheet(); $('laycard').innerHTML = layCardInner(); refreshSend('layouts'); refreshSave('layouts'); }
function rmLay(k){ workLay = workLay.filter(x => x !== k); $('laycard').innerHTML = layCardInner(); refreshSend('layouts'); refreshSave('layouts'); }
function pickSnd(el){
  document.querySelectorAll('.optrow.snd').forEach(r => r.classList.remove('on'));
  el.classList.add('on');
  refreshSave('snd');
}
function copyClock(){
  const [d, t] = splitNow(sys(self()).now);
  $('f-date').value = d;
  $('f-time').value = t;
  refreshSave('clock');
}

/* a section is dirty when its draft differs from the machine's saved state;
   the clock compares against the fields' render-time baseline (saved wall
   time keeps moving — what matters is whether the user touched it) */
function cardDirty(key){
  const m = byId(cur);
  if (key === 'name')    return $('f-name').value.trim() !== m.name;
  if (key === 'snd'){
    const on = document.querySelector('.optrow.snd.on');
    return !!on && on.dataset.k !== (m.audio && m.audio.out);
  }
  if (key === 'layouts'){
    const saved = layouts(m);
    return workLay.length !== saved.length || workLay.some(k => !saved.includes(k));
  }
  const d = $('f-date'), t = $('f-time');
  return $('f-tz').value !== sys(m).timezone ||
         d.value !== d.dataset.init || t.value !== t.dataset.init;
}
/* Save is an idle-state gate only: pending stays disabled on its own, and a
   stuck Failed / No reply must stay clickable — it is the retry. */
function refreshSave(key){
  const b = $('b-' + key); if (!b) return;
  if (b.querySelector('.spin') || b.classList.contains('done') || b.classList.contains('failed')) return;
  b.disabled = !cardDirty(key);
}

/* the Save button IS the status: Save → Saving… → Done (releases after the
   linger) / Failed / No reply (stick; click retries). */
function setBtn(id, state, label, title){
  const b = $(id); if (!b) return;
  b.classList.remove('done','failed'); b.disabled = false; b.title = title || '';
  if (state === 'pending'){ b.disabled = true; b.innerHTML = `<span class="spin"></span>Saving…`; }
  else if (state === 'ok'){ b.classList.add('done'); b.innerHTML = `${ic('check')}${label || 'Done'}`; }
  else if (state === 'fail'){ b.classList.add('failed'); b.innerHTML = label || 'Failed'; }
  else b.innerHTML = (b.dataset.icx ? ic(b.dataset.icx) : '') + b.dataset.idle;
}
function relax(id, machine){
  setTimeout(() => {
    const b = $(id);
    if (cur === machine && b && b.classList.contains('done')){
      setBtn(id, null);
      if (id.startsWith('b-all-')) refreshSend(id.slice(6));
      else if (id.startsWith('b-')) refreshSave(id.slice(2));
    }
  }, LINGER);
}

/* what a section would send — shared by Save and the client-side quick checks */
function readVals(key){
  if (key === 'clock'){
    return {url: '/setup/clock',
            body: {tz: $('f-tz').value, date: $('f-date').value, time: $('f-time').value}};
  }
  if (key === 'layouts'){
    if (!workLay.length) return {err: 'keep at least one layout'};
    return {url: '/setup/settings',
            body: {writes: [{path: ['keyboard','available-layouts'], value: [...workLay]}]}};
  }
  if (key === 'name'){
    const v = $('f-name').value.trim();
    if (!/^[A-Za-z0-9-]{1,16}$/.test(v)) return {err: 'letters, numbers and dashes only'};
    return {url: '/setup/settings', body: {writes: [{path: ['system','hostname'], value: v}]}};
  }
  if (key === 'snd'){
    const on = document.querySelector('.optrow.snd.on');
    if (!on) return {err: 'pick an output'};
    return {url: '/setup/settings', body: {writes: [{path: ['audio','active'], value: on.dataset.k}]}};
  }
}

function jobDone(machine){
  inflight = Math.max(0, inflight - 1);
  if (cur !== machine) return false;
  refreshHead();
  return true;
}
async function saveSec(key){
  const machine = cur, bid = 'b-' + key;
  const vals = readVals(key);
  if (vals.err) return setBtn(bid, 'fail', 'Failed', vals.err);
  setBtn(bid, 'pending');
  inflight++; headRes = null; refreshHead();
  const sent = await postJob(vals.url, Object.assign({targets: [machine]}, vals.body));
  const job  = sent.job != null ? await pollJob(sent.job) : null;
  if (!jobDone(machine)) return;
  const st = job && job.peers && job.peers[machine];
  if (st === 'ok' || st === 'accepted'){
    headRes = 'ok'; refreshHead();
    setBtn(bid, 'ok'); relax(bid, machine);
    if (key === 'clock'){                     // the sent time is the new baseline
      const d = $('f-date'), t = $('f-time');
      if (d) d.dataset.init = d.value;
      if (t) t.dataset.init = t.value;
    }
    poll();                                   // fresh truth: rail name, gates
    setTimeout(() => {
      if (cur === machine && headRes === 'ok' && !inflight){ headRes = null; refreshHead(); }
    }, LINGER);
  } else if (st === 'fail'){
    headRes = 'noreply'; refreshHead();
    setBtn(bid, 'fail', 'Failed', 'The machine could not apply it — try again');
  } else {
    headRes = 'noreply'; refreshHead();
    setBtn(bid, 'fail', 'No reply', sent.err || 'The machine did not answer — try again');
  }
}

/* Send = fanout of THIS COMPUTER'S SAVED STATE — it never reads the form.
   Editing gates it: Save first, then Send. "Make everyone match this computer." */
async function sendAll(key){
  const machine = cur, bid = 'b-all-' + key, me = self();
  const targets = S.peers.filter(m => m.online).map(m => m.id);
  let url, body;
  if (key === 'clock'){
    const [d, t] = splitNow(sys(me).now);
    url = '/setup/clock'; body = {tz: sys(me).timezone, date: d, time: t};
  } else {
    url = '/setup/settings';
    body = {writes: [{path: ['keyboard','available-layouts'], value: [...layouts(me)]}]};
  }
  setBtn(bid, 'pending');
  inflight++; refreshHead();
  const sent = await postJob(url, Object.assign({targets}, body));
  const job  = sent.job != null ? await pollJob(sent.job) : null;
  if (!jobDone(machine)) return;
  if (!job) return setBtn(bid, 'fail', 'Failed', sent.err || 'no reply from the server');
  const miss = Object.entries(job.peers || {})
    .filter(([, st]) => st !== 'ok' && st !== 'accepted')
    .map(([id]) => (byId(id) || {name: id}).name);
  if (miss.length){
    setBtn(bid, 'fail', `${targets.length - miss.length} of ${targets.length}`,
           miss.join(', ') + ' did not reply — click to try again');
  } else {
    setBtn(bid, 'ok', `Done on ${targets.length}`); relax(bid, machine);
  }
}
/* dirty gate: while the section's draft differs from this computer's saved
   state, Send is disabled — its tooltip names the exact value it would send. */
function refreshSend(key){
  const b = $('b-all-' + key); if (!b) return;
  if (b.querySelector('.spin') || b.classList.contains('done') || b.classList.contains('failed')) return;
  const me = self();
  let dirty, title;
  if (key === 'layouts'){
    const saved = layouts(me);
    dirty = workLay.length !== saved.length || workLay.some(k => !saved.includes(k));
    title = dirty
      ? 'Save first — Send uses the saved list, not this draft'
      : `Makes every machine match this computer’s layouts: ${saved.map(layName).join(', ')}`;
  } else {
    dirty = $('f-tz') && $('f-tz').value !== sys(me).timezone;
    title = dirty
      ? 'Save the timezone first — Send uses saved settings, not this draft'
      : `Makes every machine match this computer’s clock: ${splitNow(sys(me).now)[1]}, ${sys(me).timezone}`;
  }
  b.disabled = dirty; b.title = title;
}

/* ── diagnostics tab ─────────────────────────────────────────────────────── */
function diagHtml(m){
  const s = sys(m);
  return `
  <div class="scard">
    <span class="sc-title">${ic('cpu')}Identity</span>
    <div class="drow"><span class="k">Serial</span><span class="v mono">${esc(s.serial || '—')}</span></div>
    <div class="drow"><span class="k">Image</span><span class="v">v0.4.0 · slot ${esc(s.slot || '?')}</span></div>
    <div class="drow"><span class="k">Address</span><span class="v mono">${esc(s.ip || '—')}</span></div>
    <div class="drow"><span class="k">Up</span><span class="v">${fmtUp(s.upMin)}</span></div>
    <div class="drow"><span class="k">Storage free</span><span class="v">${fmtStore(s.storeFree)}</span></div>
    <div class="drow"><span class="k">Machine time</span><span class="v">${esc((s.now || '').replace('T',' '))}</span></div>
  </div>

  <div class="scard">
    <span class="sc-title">${ic('act')}Checks</span>
    ${CHECKS.map(([k, label]) => `<div class="drow"><span class="k">${label}</span><span class="v" id="ck-${k}">–</span></div>`).join('')}
    <div class="frow"><span class="grow"></span>
      <button class="btn primary" id="b-checks" data-idle="Run checks" data-icx="act" onclick="runChecks()">${ic('act')}Run checks</button>
    </div>
  </div>

  <div class="scard">
    <span class="sc-title">${ic('alert')}Warnings</span>
    ${(m.warns && m.warns.length)
      ? m.warns.map(w => `<div class="warnline"><span class="wd"></span>${esc(w)}</div>`).join('')
      : `<span class="nowarn">No warnings.</span>`}
  </div>

  <div class="scard">
    <span class="sc-title">${ic('file')}Log</span>
    <div class="log">${logLines(m)}</div>
    <div class="frow"><span class="grow"></span>
      ${saveBtn('b-log','Copy','copy','quiet',"copyLog()")}
    </div>
  </div>`;
}
function logLines(m){
  const s = sys(m);
  return [
    `11:58:41 boot slot ${s.slot || 'A'} ok`,
    `11:58:49 net link up (10.0.0.1)`,
    `11:58:52 ujimad converge ok (machine settings)`,
    `11:59:03 desktop session start (guest)`,
    `12:03:12 peers ${Math.max(S.peers.filter(p => p.online).length - 1, 0)} found`,
    `12:41:07 session reset (admin)`,
    `13:02:55 app open gcompris`,
  ].join('\n');
}
async function runChecks(){
  const machine = cur;
  CHECKS.forEach(([k]) => { $('ck-' + k).innerHTML = `<span class="spin"></span>`; });
  setBtn('b-checks', 'pending');
  inflight++; headRes = null; refreshHead();
  const sent = await postJob('/setup/checks', {targets: [machine]});
  const job  = sent.job != null ? await pollJob(sent.job) : null;
  if (!jobDone(machine)) return;
  setBtn('b-checks', null);
  if (tab !== 'diag') return;
  const st = job && job.peers && job.peers[machine];
  if (st !== 'ok'){
    headRes = 'noreply'; refreshHead();
    CHECKS.forEach(([k]) => { const el = $('ck-' + k);
      if (el) el.innerHTML = `<span class="chip noreply">No reply</span>`; });
    return;
  }
  headRes = 'ok'; refreshHead();
  setTimeout(() => {
    if (cur === machine && headRes === 'ok' && !inflight){ headRes = null; refreshHead(); }
  }, LINGER);
  for (const c of ((job.data || {})[machine] || {}).checks || []){
    const el = $('ck-' + c.id);
    if (el) el.innerHTML =
      `<span class="chip ${esc(c.status)}" ${c.note ? `title="${esc(c.note)}"` : ''}>${esc(c.label)}</span>`;
  }
}
function copyLog(){
  const txt = document.querySelector('.log').textContent;
  (navigator.clipboard ? navigator.clipboard.writeText(txt) : Promise.reject())
    .then(() => { setBtn('b-log', 'ok', 'Copied'); relax('b-log', cur); })
    .catch(() => { setBtn('b-log', 'fail', 'Failed'); });
}

/* ── sheets: self power, remove ──────────────────────────────────────────── */
function openSheet(html){ $('sheet').innerHTML = html; $('ovl').classList.add('show'); }
function closeSheet(){ $('ovl').classList.remove('show'); }
$('ovl').addEventListener('click', e => { if (e.target === $('ovl')) closeSheet(); });

function confirmSelf(kind){
  const restart = kind === 'restart';
  openSheet(`
    <h2>${restart ? 'Restart' : 'Power off'} this computer?</h2>
    <p>Circle and Setup close while it ${restart ? 'restarts. It comes back in about a minute.'
                                                : 'shuts down. Switch it on again by hand.'}</p>
    <div class="sfoot">
      <button class="btn quiet" onclick="closeSheet()">Cancel</button>
      <button class="btn dangerf" onclick="selfGo('${kind}')">${restart ? 'Restart' : 'Power off'}</button>
    </div>`);
}
async function selfGo(kind){
  openSheet(`
    <h2>${kind === 'restart' ? 'Restarting…' : 'Shutting down…'}</h2>
    <div class="frow"><span class="spin"></span>
      <p class="hint">this computer is going down — the panel reflects it in a moment</p></div>`);
  await postJob('/setup/' + kind, {targets: [S.self]});
  poll();
}

function confirmRemove(id){
  const m = byId(id);
  openSheet(`
    <h2>Remove ${esc(m.name)} from the list?</h2>
    <p>Only the list changes — nothing happens to the machine. If it’s on the
    network again, it comes back after a rescan.</p>
    <div class="sfoot">
      <button class="btn quiet" onclick="closeSheet()">Cancel</button>
      <button class="btn dangerf" onclick="doRemove('${id}')">Remove</button>
    </div>`);
}
async function doRemove(id){
  closeSheet();
  try { await fetch('/setup/remove', {method: 'POST',
    headers: {'content-type': 'application/json'}, body: JSON.stringify({id})}); }
  catch (e) {}
  if (cur === id) cur = S.self;
  poll();
}

/* ── rescan: the server runs the sweep; the page locks and the rail shows
   the scan, then the found machines stagger back in ─────────────────────── */
async function rescanNow(){
  if (scanning || revealing) return;
  scanning = true;
  const b = $('rescan');
  b.disabled = true;                 // native block: mouse, keyboard and focus
  b.classList.add('scanning');       // held until the reveal settles
  render();
  try { await fetch('/setup/rescan', {method: 'POST'}); } catch (e) {}
  try { S = await (await fetch('/ui/setup')).json(); } catch (e) {}
  if (!byId(cur)) { cur = S.self; detailKey = ''; }
  scanning = false;
  b.classList.remove('scanning');
  reveal();
}

/* ── boot: scanning until the first world arrives ────────────────────────── */
$('rail').innerHTML = `<div class="scanhint"><span class="spin"></span>looking for machines…</div>`;
setInterval(poll, POLL_MS);
poll();
