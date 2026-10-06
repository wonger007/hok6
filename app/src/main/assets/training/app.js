'use strict';
/*
 * Writing practice: printable character worksheets (layout modelled on 12jr/chinese-character-worksheets)
 * that can also be written on directly, with stroke order animations and quizzes from chanind/hanzi-writer.
 * Page geometry is in millimetres on a US Letter sheet (8.5 × 11 in), so the screen and the printout match.
 */

const SVGNS = 'http://www.w3.org/2000/svg';
const PAGE_W = 215.9, PAGE_H = 279.4;
const MODEL = '#111111', GREY = '#C8C8C8', GRID = '#CFCFCF', BRAND = '#C62828';
const PEN_SIZES = [0.45, 0.8, 1.3];
/* Set on each stroke rather than in CSS, so strokes also look right in the phone squares (<use> copies). */
const INK_STYLE = { fill: 'none', 'stroke-linecap': 'round', 'stroke-linejoin': 'round' };
const Native = window.Android || null;
/* Phones (smallest screen side under 600 dp) get the one-row-at-a-time view; tablets the whole pages. */
const IS_PHONE = Native && Native.isPhone ? Native.isPhone() : Math.min(screen.width, screen.height) < 600;
/* Real millimetres to CSS px for this screen (from the app; ~6.3 is a typical Android phone). */
const PX_PER_MM = (Native && Native.cssPxPerMm && Number(Native.cssPxPerMm())) || 6.3;

const $ = (id) => document.getElementById(id);

/* Saved data (history, bookmarks, writing, settings). In the app it is kept in the app's own storage, which Android
   backs up; this page's localStorage is only used in a plain browser. Values are cached, as this page is the only writer. */
const NativeStore = Native && Native.storeGet ? Native : null;
const storeCache = new Map();

const store = {
  get(key, fallback) {
    if (!storeCache.has(key)) {
      let raw = null;
      try { raw = NativeStore ? NativeStore.storeGet(key) : localStorage.getItem(key); } catch (e) { /* ignore */ }
      storeCache.set(key, raw == null ? undefined : raw);
    }
    const v = storeCache.get(key);
    if (v === undefined) return fallback;
    try { return JSON.parse(v); } catch (e) { return fallback; }
  },
  set(key, value) {
    const v = JSON.stringify(value);
    storeCache.set(key, v);
    try {
      if (NativeStore) NativeStore.storeSet(key, v); else localStorage.setItem(key, v);
    } catch (e) {
      toast('Could not save — storage is full');
    }
  },
  del(key) {
    storeCache.set(key, undefined);
    try {
      if (NativeStore) NativeStore.storeDel(key); else localStorage.removeItem(key);
    } catch (e) { /* ignore */ }
  },
};

/* Earlier versions kept everything in localStorage, which is lost when the app is reinstalled; move it to the app. */
(function moveToAppStorage() {
  if (!NativeStore) return;
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (NativeStore.storeGet(key) == null) NativeStore.storeSet(key, localStorage.getItem(key));
    }
    localStorage.clear();
  } catch (e) { /* ignore */ }
}());

const DEFAULT_OPTS = { size: 'large', grid: 'mi', strokes: true, roman: true, name: true };

const state = {
  autoStarted: false,
  lang: store.get('lang', 'yue'),
  ws: null,
  data: {},
  ink: {},
  undo: [],
  color: '#212121',
  size: 1,
  tool: 'pen',
  fingerDraw: store.get('fingerDraw', true),
  zoom: store.get('zoom', 1),
  view: IS_PHONE ? store.get('phoneView', 'row') : 'page',
  layouts: [],
  row: 0,
};

// ---------------------------------------------------------------- helpers

function el(name, attrs, parent) {
  const node = document.createElementNS(SVGNS, name);
  for (const k in attrs || {}) node.setAttribute(k, attrs[k]);
  if (parent) parent.appendChild(node);
  return node;
}

function line(parent, x1, y1, x2, y2, attrs) {
  return el('line', Object.assign({ x1, y1, x2, y2 }, attrs), parent);
}

function text(parent, x, y, str, attrs) {
  const t = el('text', Object.assign({ x, y }, attrs), parent);
  t.textContent = str;
  return t;
}

let toastTimer = 0;
/* A short message at the bottom; [action] ({label, run}) adds a button to it, e.g. Undo after Clear. */
function toast(msg, action) {
  const t = $('toast');
  t.textContent = msg;
  if (action) {
    const b = document.createElement('button');
    b.className = 'toast-action';
    b.textContent = action.label;
    b.onclick = () => { t.hidden = true; action.run(); };
    t.appendChild(b);
  }
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, action ? 5000 : 2600);
}

function isHan(ch) {
  return /\p{Script=Han}/u.test(ch);
}

// ---------------------------------------------------------------- readings (Jyutping / Pinyin)

const readingsTable = {};
const shardLoads = new Map();

/* Loads the reading files that cover `text` (every character with a known reading is available). */
function loadReadings(text) {
  const loads = Core.shardsOf(text || '').map((id) => {
    if (!shardLoads.has(id)) {
      shardLoads.set(id, fetch(`readings/${id.toString(16)}.json`)
        .then((r) => (r.ok ? r.json() : {}))
        .catch(() => ({}))
        .then((t) => Object.assign(readingsTable, t)));
    }
    return shardLoads.get(id);
  });
  return Promise.all(loads).then(() => readingsTable);
}

function readingsOf(ch, table) {
  return Core.readingsIn(table[ch], state.lang);
}

function romanName() {
  return state.lang === 'yue' ? 'Jyutping' : 'Pinyin';
}

// ---------------------------------------------------------------- stroke data

const charCache = new Map();

function fetchRaw(ch) {
  const hex = ch.codePointAt(0).toString(16);
  return fetch('/hanzi/' + hex).then((r) => {
    if (!r.ok) throw new Error('missing');
    return r.json();
  });
}

function loadChar(ch) {
  if (!charCache.has(ch)) {
    charCache.set(ch, fetchRaw(ch).catch(() => Core.compose(ch, fetchRaw)).catch(() => null));
  }
  return charCache.get(ch);
}

// ---------------------------------------------------------------- drawing a worksheet page

function glyph(parent, data, x, y, size, color, pad) {
  const t = HanziWriter.getScalingTransform(size, size, pad);
  const outer = el('g', { transform: `translate(${x} ${y})` }, parent);
  const g = el('g', { transform: t.transform }, outer);
  for (const d of data) el('path', { d, fill: color }, g);
  return outer;
}

function drawChar(parent, ch, data, x, y, size, color) {
  if (data) {
    glyph(parent, data.strokes, x, y, size, color, size * 0.07);
  } else {
    text(parent, x + size / 2, y + size * 0.82, ch, { 'font-size': size * 0.8, 'text-anchor': 'middle', fill: color });
  }
}

function cellGrid(parent, x, y, s, kind) {
  if (kind === 'none') return;
  const a = { stroke: GRID, 'stroke-width': 0.2, 'stroke-dasharray': '0.9 0.7' };
  line(parent, x + s / 2, y, x + s / 2, y + s, a);
  line(parent, x, y + s / 2, x + s, y + s / 2, a);
  if (kind === 'mi') {
    line(parent, x, y, x + s, y + s, a);
    line(parent, x, y + s, x + s, y, a);
  }
}

/* Stroke-order square: the character after `step` strokes, newest stroke in red (step 0: the whole character). */
function strokeCell(parent, ch, data, step, x, y, size) {
  if (!data || step === 0) return drawChar(parent, ch, data, x, y, size, MODEL);
  const t = HanziWriter.getScalingTransform(size, size, size * 0.07);
  const g = el('g', { transform: t.transform }, el('g', { transform: `translate(${x} ${y})` }, parent));
  for (let s = 0; s < step; s++) el('path', { d: data.strokes[s], fill: s === step - 1 ? BRAND : '#333' }, g);
}

/* One page per word (see Core.wordLayout): stroke order, a row to trace, then blank rows to the bottom. */
function buildPage(ws, pageIndex, totalPages, table) {
  const word = ws.words[pageIndex];
  const chars = Array.from(word);
  const cellMm = Core.BOX_SIZES[ws.opts.size] || Core.BOX_SIZES.large;
  const layout = Core.wordLayout(chars, chars.map((c) => (state.data[c] ? state.data[c].strokes.length : 0)), ws.opts.strokes, cellMm);
  state.layouts[pageIndex] = layout;
  const { cols, left, top, cell } = layout;

  const svg = el('svg', { viewBox: `0 0 ${PAGE_W} ${PAGE_H}`, class: 'page' });
  svg.dataset.page = pageIndex;
  // Phone squares show parts of this page through <use href="#pg<n>">.
  const root = el('g', { id: 'pg' + pageIndex }, svg);
  el('rect', { x: 0, y: 0, width: PAGE_W, height: PAGE_H, fill: '#fff' }, root);

  const right = left + cols * cell;
  const border = { stroke: '#000', 'stroke-width': 0.3 };
  line(root, left, 17, right, 17, border);
  if (ws.opts.name) text(root, left, 15.4, '姓名：', { 'font-size': 4.2 });
  text(root, PAGE_W / 2, 15.2, word, { 'font-size': 7, 'text-anchor': 'middle' });
  if (ws.opts.roman) text(root, PAGE_W / 2, 21.6, romanOfText(word, table), { 'font-size': 3.8, 'text-anchor': 'middle', class: 'roman' });
  text(root, right, 15.4, `${pageIndex + 1}/${totalPages}`, { 'font-size': 3.6, 'text-anchor': 'end', class: 'head-latin' });
  if (chars.some((c) => state.data[c] && state.data[c].approx)) {
    text(root, right, 21.6, '≈ stroke order assembled from parts', { 'font-size': 2.6, 'text-anchor': 'end', fill: '#999' });
  }

  layout.rows.forEach((row, r) => {
    const y = top + r * cell;
    const g = el('g', {}, root);
    for (let j = 0; j < cols; j++) {
      const x = left + j * cell;
      cellGrid(g, x, y, cell, ws.opts.grid);
      if (row.kind === 'strokes' && j < row.cells.length) {
        const { ch, step } = row.cells[j];
        strokeCell(g, ch, state.data[ch], step, x, y, cell);
      } else if (row.kind === 'model' || row.kind === 'trace') {
        const ch = chars[j % chars.length];
        drawChar(g, ch, state.data[ch], x, y, cell, row.kind === 'model' ? MODEL : GREY);
      }
    }
    line(g, left, y, right, y, border);
  });
  const bottom = top + layout.rows.length * cell;
  line(root, left, bottom, right, bottom, border);
  for (let j = 0; j <= cols; j++) line(root, left + j * cell, top, left + j * cell, bottom, border);

  el('g', { class: 'ink' }, root);
  return svg;
}

async function renderPages() {
  const ws = state.ws;
  const table = await loadReadings(ws.words.join(''));
  const unique = [...new Set(ws.chars)];
  const loaded = await Promise.all(unique.map(loadChar));
  state.data = {};
  unique.forEach((c, i) => { state.data[c] = loaded[i]; });

  const pages = $('pages');
  const scrollRatio = pages.scrollTop / Math.max(1, pages.scrollHeight);
  pages.innerHTML = '';
  const total = ws.words.length;
  state.layouts = [];
  for (let p = 0; p < total; p++) {
    pages.appendChild(buildPage(ws, p, total, table));
    redrawInk(p);
  }
  fillWordSelect(table);
  selectWord(state.row);
  pages.scrollTop = scrollRatio * pages.scrollHeight;
}

// ---------------------------------------------------------------- phone: one row at a time

/* Each square is a window onto the printed page (same drawing, same writing), so printing includes what was written here. */
async function renderRow() {
  const ws = state.ws;
  const page = Math.max(0, Math.min(state.row, ws.words.length - 1));
  state.row = page;
  const word = ws.words[page];
  const len = Array.from(word).length;
  const layout = state.layouts[page];

  // Each row shows whole copies of the word, with squares sized to the screen: about four across in portrait,
  // more in landscape, and never so big that a row doesn't fit on screen.
  // The printed size on screen (real millimetres), but never wider than the screen allows.
  const gap = 6, avail = window.innerWidth - 40;
  const cellPx = Math.min(layout.cell * PX_PER_MM, window.innerHeight * 0.45, avail);
  const fit = Math.max(1, Math.floor((avail + gap) / (cellPx + gap)));
  const across = Math.min(layout.cols, Math.max(len, Math.floor(fit / len) * len));
  const cells = $('rowCells');
  cells.innerHTML = '';
  layout.rows.forEach((row, r) => {
    const count = row.kind === 'strokes' ? row.cells.length : across;
    const group = document.createElement('div');
    group.className = 'row-group ' + row.kind;
    group.style.gridTemplateColumns = `repeat(${across}, ${Math.floor(cellPx)}px)`;
    for (let j = 0; j < count; j++) {
      const x = layout.left + j * layout.cell, y = layout.top + r * layout.cell;
      const svg = el('svg', { viewBox: `${x} ${y} ${layout.cell} ${layout.cell}`, class: 'pcell' }, group);
      svg.dataset.page = page;
      el('use', { href: '#pg' + page }, svg);
    }
    cells.appendChild(group);
  });
}

/* Shows one word's page (tablet) or squares (phone); the others stay in the page for Export / Print. */
function selectWord(i) {
  const ws = state.ws;
  if (!ws) return;
  state.row = Math.max(0, Math.min(i, ws.words.length - 1));
  $('wordSelect').value = String(state.row);
  $('wordPrev').disabled = state.row === 0;
  $('wordNext').disabled = state.row >= ws.words.length - 1;
  $('wordStar').textContent = isBookmarked(ws.words[state.row]) ? '★' : '☆';
  $('pages').querySelectorAll('svg.page').forEach((p) => p.classList.toggle('current', Number(p.dataset.page) === state.row));
  $('pages').scrollTop = 0;
  if (state.view === 'row') renderRow();
}

function fillWordSelect(table) {
  const select = $('wordSelect');
  select.innerHTML = '';
  state.ws.words.forEach((w, i) => {
    const option = document.createElement('option');
    option.value = String(i);
    option.textContent = `${w}   ${romanOfText(w, table)}`;
    select.appendChild(option);
  });
}

function setView(view) {
  state.view = view;
  if (IS_PHONE) store.set('phoneView', view);
  document.body.classList.toggle('row-view', view === 'row');
  $('viewBtn').textContent = view === 'row' ? '📄' : '▦';
  $('viewBtn').setAttribute('aria-label', view === 'row' ? 'Show whole pages' : 'Show one row at a time');
  if (view === 'row' && state.ws) renderRow();
}

function setupRowView() {
  $('wordPrev').onclick = () => selectWord(state.row - 1);
  $('wordNext').onclick = () => selectWord(state.row + 1);
  $('wordSelect').onchange = () => selectWord(Number($('wordSelect').value));
  $('wordSay').onclick = () => speak(state.ws.words[state.row]);
  $('wordSlow').onclick = () => speak(state.ws.words[state.row], SLOW);
  $('wordStar').onclick = () => { $('wordStar').textContent = toggleBookmark(state.ws.words[state.row]) ? '★' : '☆'; };
  $('viewBtn').onclick = () => setView(state.view === 'row' ? 'page' : 'row');
  $('viewBtn').hidden = !IS_PHONE;
  document.body.classList.toggle('phone', IS_PHONE);
  setView(state.view);
}

// ---------------------------------------------------------------- handwriting

function pageSvg(page) {
  return $('pages').querySelector(`svg.page[data-page="${page}"]`);
}

function pathD(p) {
  if (p.length < 4) return `M${p[0]} ${p[1]} l0.01 0`;
  let d = `M${p[0]} ${p[1]}`;
  for (let i = 2; i < p.length - 2; i += 2) {
    const mx = (p[i] + p[i + 2]) / 2;
    const my = (p[i + 1] + p[i + 3]) / 2;
    d += ` Q${p[i]} ${p[i + 1]} ${mx.toFixed(2)} ${my.toFixed(2)}`;
  }
  return d + ` L${p[p.length - 2]} ${p[p.length - 1]}`;
}

function redrawInk(page) {
  const svg = pageSvg(page);
  if (!svg) return;
  const layer = svg.querySelector('.ink');
  layer.innerHTML = '';
  for (const s of state.ink[page] || []) {
    el('path', Object.assign({ d: pathD(s.p), stroke: s.c, 'stroke-width': s.w }, INK_STYLE), layer);
  }
}

/* Writing is kept for each word (at each box size) for the last INK_KEEP words written on, so it's there next time. */
const INK_KEEP = 60;
const inkDirty = new Set();
let inkTimer = 0;

function inkKey(page) {
  const ws = state.ws;
  return `ink:${ws.words[page]}|${ws.opts.size}|${ws.opts.strokes ? 's' : '-'}`;
}

function loadInk() {
  state.ink = {};
  inkDirty.clear();
  state.ws.words.forEach((w, p) => {
    const strokes = store.get(inkKey(p), null);
    if (strokes && strokes.length) state.ink[p] = strokes;
  });
}

function saveInkSoon(page) {
  inkDirty.add(page);
  clearTimeout(inkTimer);
  inkTimer = setTimeout(saveInk, 800);
}

function saveInk() {
  clearTimeout(inkTimer);
  if (!state.ws || !inkDirty.size) return;
  let recent = store.get('inkRecent', []);
  for (const p of inkDirty) {
    const key = inkKey(p);
    const strokes = state.ink[p] || [];
    recent = recent.filter((k) => k !== key);
    if (strokes.length) {
      store.set(key, strokes);
      recent.unshift(key);
    } else {
      store.del(key);
    }
  }
  inkDirty.clear();
  for (const old of recent.slice(INK_KEEP)) store.del(old);
  store.set('inkRecent', recent.slice(0, INK_KEEP));
}

function strokeHits(s, x, y, r) {
  const p = s.p;
  const rr = r + s.w / 2;
  if (p.length < 4) return Math.hypot(p[0] - x, p[1] - y) <= rr;
  for (let i = 0; i + 3 < p.length; i += 2) {
    const ax = p[i], ay = p[i + 1], bx = p[i + 2], by = p[i + 3];
    const dx = bx - ax, dy = by - ay;
    const len = dx * dx + dy * dy;
    const t = len === 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / len));
    if (Math.hypot(x - (ax + t * dx), y - (ay + t * dy)) <= rr) return true;
  }
  return false;
}

function eraseAt(page, x, y) {
  const list = state.ink[page];
  if (!list) return;
  const hit = list.filter((s) => strokeHits(s, x, y, 1.6));
  if (!hit.length) return;
  state.ink[page] = list.filter((s) => !hit.includes(s));
  state.undo.push({ type: 'erase', page, strokes: hit });
  redrawInk(page);
  saveInkSoon(page);
}

function toPage(svg, e) {
  const pt = svg.createSVGPoint();
  pt.x = e.clientX;
  pt.y = e.clientY;
  return pt.matrixTransform(svg.getScreenCTM().inverse());
}

/* Taps on the model character open the practice panel; taps on the romanization speak it. */
/* Taps on a stroke-order square open that character's practice panel; the word at the top of the page is read aloud. */
function hitTest(svg, pt) {
  const page = Number(svg.dataset.page);
  const layout = state.layouts[page];
  const word = state.ws.words[page];
  if (!layout) return null;
  if (pt.y < 23 && pt.y > 8 && Math.abs(pt.x - PAGE_W / 2) < 45) return { type: 'speak', text: word };
  const r = Math.floor((pt.y - layout.top) / layout.cell);
  const c = Math.floor((pt.x - layout.left) / layout.cell);
  const row = layout.rows[r];
  if (!row || c < 0 || c >= layout.cols) return null;
  let ch = null;
  if (row.kind === 'strokes' && c < row.cells.length) ch = row.cells[c].ch;
  if (row.kind === 'model') ch = Array.from(word)[c % Array.from(word).length];
  return ch ? { type: 'model', idx: state.ws.chars.indexOf(ch) } : null;
}

function setFingerDraw(on, announce) {
  state.fingerDraw = on;
  store.set('fingerDraw', on);
  $('pages').classList.toggle('finger-draw', on);
  $('rowView').classList.toggle('finger-draw', on);
  $('fingerBtn').classList.toggle('sel', on);
  // Says what a finger does now: write, or scroll (once a stylus is used, only the stylus writes).
  $('fingerBtn').textContent = on ? '☝ Fingers draw' : '☝ Fingers scroll';
  if (announce) toast(on ? 'Fingers draw — scroll with two fingers' : 'Stylus detected — fingers now scroll, the pen writes');
}

function setupInk(pages) {
  const touches = new Map();
  let active = null;
  let tap = null;
  let pan = null;
  let blockTouch = false;
  const pens = new Set();

  const avg = () => {
    let x = 0, y = 0;
    for (const t of touches.values()) { x += t.x; y += t.y; }
    return { x: x / touches.size, y: y / touches.size };
  };

  const cancelActive = () => {
    if (active && active.path) active.path.remove();
    active = null;
    tap = null;
  };

  // With finger scrolling on, a stylus must not scroll the page, or the browser cancels its stroke. Android's WebView
  // doesn't say which touches are a stylus, but the pen's pointerdown comes before its touchstart and touchmoves.
  const stopPenScroll = (e) => { if (pens.size) e.preventDefault(); };
  pages.addEventListener('touchstart', stopPenScroll, { passive: false });
  pages.addEventListener('touchmove', stopPenScroll, { passive: false });

  pages.addEventListener('pointerdown', (e) => {
    if (e.pointerType === 'pen') pens.add(e.pointerId);
    if (e.pointerType === 'touch') {
      touches.set(e.pointerId, { x: e.clientX, y: e.clientY });
      if (!state.fingerDraw) return;
      if (touches.size >= 2) {
        cancelActive();
        pan = avg();
        blockTouch = true;
        return;
      }
      if (blockTouch) return;
    }
    if (e.pointerType === 'pen' && state.fingerDraw) setFingerDraw(false, true);

    const svg = e.target.closest && e.target.closest('svg[data-page]');
    if (!svg) return;
    e.preventDefault();
    const pt = toPage(svg, e);
    const hit = hitTest(svg, pt);
    if (hit) {
      tap = { hit, x: e.clientX, y: e.clientY, id: e.pointerId };
      return;
    }
    try { svg.setPointerCapture(e.pointerId); } catch (err) { /* ignore */ }
    const page = Number(svg.dataset.page);
    const erasing = state.tool === 'eraser' || (e.buttons & 32) !== 0;
    if (erasing) {
      active = { svg, page, erase: true, id: e.pointerId };
      eraseAt(page, pt.x, pt.y);
      return;
    }
    const p = [+pt.x.toFixed(1), +pt.y.toFixed(1)];
    const w = PEN_SIZES[state.size];
    const path = el('path', Object.assign({ d: pathD(p), stroke: state.color, 'stroke-width': w }, INK_STYLE), pageSvg(page).querySelector('.ink'));
    active = { svg, page, p, path, w, color: state.color, id: e.pointerId };
  });

  pages.addEventListener('pointermove', (e) => {
    if (e.pointerType === 'touch' && touches.has(e.pointerId)) {
      touches.set(e.pointerId, { x: e.clientX, y: e.clientY });
      if (pan && touches.size >= 2) {
        const now = avg();
        pages.scrollBy(pan.x - now.x, pan.y - now.y);
        pan = now;
        return;
      }
    }
    if (tap && tap.id === e.pointerId && Math.hypot(e.clientX - tap.x, e.clientY - tap.y) > 12) tap = null;
    if (!active || active.id !== e.pointerId) return;
    const events = e.getCoalescedEvents ? e.getCoalescedEvents() : [e];
    for (const ev of events.length ? events : [e]) {
      const pt = toPage(active.svg, ev);
      if (active.erase) {
        eraseAt(active.page, pt.x, pt.y);
      } else {
        active.p.push(+pt.x.toFixed(1), +pt.y.toFixed(1));
      }
    }
    if (!active.erase) active.path.setAttribute('d', pathD(active.p));
  });

  const end = (e, cancelled) => {
    pens.delete(e.pointerId);
    if (e.pointerType === 'touch') {
      touches.delete(e.pointerId);
      if (touches.size < 2) pan = touches.size ? avg() : null;
      if (touches.size === 0) {
        blockTouch = false;
        pan = null;
      }
    }
    if (tap && tap.id === e.pointerId) {
      if (!cancelled) {
        if (tap.hit.type === 'model') openPractice(tap.hit.idx);
        else speak(tap.hit.text || state.ws.chars[tap.hit.idx]);
      }
      tap = null;
    }
    if (!active || active.id !== e.pointerId) return;
    if (!active.erase) {
      if (cancelled) {
        active.path.remove();
      } else {
        // Keep the lift-off point too, so a quick flick still reaches where the finger left the screen.
        const last = toPage(active.svg, e);
        const n = active.p.length;
        if (Math.hypot(last.x - active.p[n - 2], last.y - active.p[n - 1]) > 0.2) {
          active.p.push(+last.x.toFixed(1), +last.y.toFixed(1));
          active.path.setAttribute('d', pathD(active.p));
        }
        const stroke = { c: active.color, w: active.w, p: active.p };
        (state.ink[active.page] = state.ink[active.page] || []).push(stroke);
        state.undo.push({ type: 'add', page: active.page, stroke });
        saveInkSoon(active.page);
      }
    }
    active = null;
  };
  pages.addEventListener('pointerup', (e) => end(e, false));
  pages.addEventListener('pointercancel', (e) => end(e, true));
}

function undo() {
  const a = state.undo.pop();
  if (!a) return toast('Nothing to undo');
  if (a.type === 'add') {
    state.ink[a.page] = (state.ink[a.page] || []).filter((s) => s !== a.stroke);
    redrawInk(a.page);
  } else if (a.type === 'erase' || a.type === 'clear') {
    state.ink[a.page] = (state.ink[a.page] || []).concat(a.strokes);
    redrawInk(a.page);
  }
  saveInkSoon(a.page);
}

/* Clears all the writing on screen (the word shown, on its page or in the phone squares); Undo brings it back. */
function clearScreen() {
  const page = state.row;
  const old = state.ink[page] || [];
  if (!old.length) return toast('Nothing to clear');
  state.undo.push({ type: 'clear', page, strokes: old });
  state.ink[page] = [];
  redrawInk(page);
  saveInkSoon(page);
  toast('Cleared', { label: 'Undo', run: undo });
}

// ---------------------------------------------------------------- speech & language

/* Reads text aloud in the chosen language; rate below 1 is slower (for listening practice). */
function speak(textToSay, rate = 1) {
  if (Native && Native.speakAt) {
    Native.speakAt(textToSay, state.lang, rate);
  } else if (Native && Native.speak) {
    Native.speak(textToSay, state.lang);
  } else if (window.speechSynthesis) {
    const u = new SpeechSynthesisUtterance(textToSay);
    u.lang = state.lang === 'yue' ? 'zh-HK' : 'zh-CN';
    u.rate = rate;
    speechSynthesis.speak(u);
  }
}

const SLOW = 0.55;
function toneLabel(reading) {
  return Core.toneLabel(reading, state.lang);
}

/* Romanization of a word: first reading of each character. */
function romanOfText(textIn, table) {
  return Array.from(textIn).map((c) => readingsOf(c, table)[0] || '?').join(' ');
}

// ---------------------------------------------------------------- history & bookmarks

const HISTORY_MAX = 500;

function historyAll() {
  return store.get('history', {});
}

/* Updates (or creates) the history entry for a word or character. */
function touchHistory(textIn, update) {
  const h = historyAll();
  const e = h[textIn] || { text: textIn, first: Date.now(), count: 0 };
  e.last = Date.now();
  update(e);
  h[textIn] = e;
  const keys = Object.keys(h);
  if (keys.length > HISTORY_MAX) {
    keys.sort((a, b) => h[a].last - h[b].last).slice(0, keys.length - HISTORY_MAX).forEach((k) => delete h[k]);
  }
  store.set('history', h);
}

function recordPractised(texts) {
  for (const t of texts) touchHistory(t, (e) => { e.count++; });
}

function recordQuiz(ch, mistakes) {
  touchHistory(ch, (e) => {
    e.quizzes = (e.quizzes || 0) + 1;
    e.lastMistakes = mistakes;
    e.best = e.best == null ? mistakes : Math.min(e.best, mistakes);
  });
}

function bookmarks() {
  return store.get('bookmarks', []);
}

function isBookmarked(t) {
  return bookmarks().includes(t);
}

function toggleBookmark(t) {
  const list = bookmarks();
  const on = !list.includes(t);
  store.set('bookmarks', on ? [t].concat(list) : list.filter((b) => b !== t));
  toast(on ? `★ Bookmarked ${t}` : `Removed bookmark ${t}`);
  renderLists();
  return on;
}

function ago(ms) {
  const days = Math.floor((Date.now() - ms) / 86400000);
  if (days <= 0) return 'today';
  if (days === 1) return 'yesterday';
  if (days < 30) return `${days} days ago`;
  return new Date(ms).toLocaleDateString();
}

function quizNote(e) {
  if (!e || e.best == null) return '';
  return e.best === 0 ? '✓ written perfectly' : `best: ${e.best} ${e.best === 1 ? 'mistake' : 'mistakes'}`;
}

/* One row of the History or Bookmarks list: tap to practise, 🔊 to hear it, ☆ to bookmark. */
function listItem(t, table, e) {
  const li = document.createElement('li');
  const main = document.createElement('button');
  main.className = 'item-main';
  main.innerHTML = '<span class="item-text"></span><span class="item-sub"></span>';
  main.querySelector('.item-text').textContent = t;
  const bits = [romanOfText(t, table)];
  if (e) bits.push(`${ago(e.last)} · ${e.count}×`);
  const q = quizNote(e);
  if (q) bits.push(q);
  main.querySelector('.item-sub').textContent = bits.join('  ·  ');
  main.onclick = () => practiseText(t, t, false);
  const say = document.createElement('button');
  say.className = 'icon';
  say.textContent = '🔊';
  say.setAttribute('aria-label', 'Say ' + t);
  say.onclick = () => speak(t);
  const star = document.createElement('button');
  star.className = 'icon star';
  star.textContent = isBookmarked(t) ? '★' : '☆';
  star.setAttribute('aria-label', 'Bookmark ' + t);
  star.onclick = () => toggleBookmark(t);
  li.append(main, say, star);
  return li;
}

async function renderLists() {
  const h = historyAll();
  const table = await loadReadings(Object.keys(h).join('') + bookmarks().join(''));

  const bm = bookmarks();
  const filter = state.historyFilter || 'all';
  // History and bookmarks share one list; the ★ Bookmarks tab shows bookmarks (practised or not), newest first.
  const items = filter === 'bookmarks'
    ? bm.map((t) => h[t] || { text: t })
    : Object.values(h)
      .filter((e) => filter === 'all' || (filter === 'words' ? Array.from(e.text).length > 1 : Array.from(e.text).length === 1))
      .sort((a, b) => b.last - a.last)
      .slice(0, 100);
  renderRecent(h, table);
  const histList = $('histList');
  histList.innerHTML = '';
  for (const e of items) histList.appendChild(listItem(e.text, table, e.last ? e : null));
  $('histEmpty').hidden = items.length > 0;
  $('histEmpty').textContent = filter === 'bookmarks'
    ? 'No bookmarks yet. Tap ☆ on a character or word to bookmark it.'
    : 'Words and characters you practice appear here. Tap one to practice it again; tap ☆ to bookmark it.';
  $('bmPractise').hidden = filter !== 'bookmarks' || bm.length === 0;
  $('histClear').hidden = filter === 'bookmarks';
  document.querySelectorAll('.filters button').forEach((b) => b.classList.toggle('sel', b.dataset.filter === filter));
}

/* The words practised most recently, as chips under the practice box: one tap practises one again. */
function renderRecent(h, table) {
  const recent = Object.values(h).sort((a, b) => b.last - a.last).slice(0, 8);
  const out = $('recentChips');
  out.textContent = '';
  for (const e of recent) {
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'recent-chip';
    chip.innerHTML = '<span class="rc-word"></span><span class="rc-roman"></span>';
    chip.querySelector('.rc-word').textContent = e.text;
    chip.querySelector('.rc-roman').textContent = romanOfText(e.text, table);
    chip.onclick = () => practiseText(e.text, e.text, false);
    out.appendChild(chip);
  }
  $('recentRow').hidden = recent.length === 0;
}

function setupLists() {
  document.querySelectorAll('.filters button').forEach((b) => {
    b.onclick = () => { state.historyFilter = b.dataset.filter; renderLists(); };
  });
  $('histClear').onclick = () => {
    if (!confirm('Clear your practice history? Bookmarks and writing are kept.')) return;
    store.del('history');
    renderLists();
  };
  if (Native && Native.backUp) {
    $('backupRow').hidden = false;
    $('backUpBtn').onclick = () => { saveInk(); Native.backUp(); };
    $('restoreBtn').onclick = () => Native.restore();
  }
  $('bmPractise').onclick = () => {
    const all = bookmarks().join('');
    if (all) practiseText('★ Bookmarks ' + new Date().toLocaleDateString(), all, false);
  };
}

function setLang(lang) {
  state.lang = lang;
  store.set('lang', lang);
  document.querySelectorAll('input[name=lang], input[name=lang2]').forEach((r) => { r.checked = r.value === lang; });
  document.querySelectorAll('.romanName').forEach((n) => { n.textContent = romanName(); });
  if ($('optSummary')) showOptSummary();
  if (Native && Native.setLanguage) Native.setLanguage(lang);
  if (!$('sheet').hidden && state.ws) renderPages();
  if (!$('practice').hidden) updatePracticeTitle();
  if (!$('home').hidden) renderTranslations();
}

// ---------------------------------------------------------------- practice panel (Hanzi Writer)

let writer = null;
let pIndex = 0;
let pData = null;
let stepIdx = 0;
let outline = true;

function status(msg) {
  $('pStatus').textContent = msg;
}

async function updatePracticeTitle() {
  const ch = state.ws.chars[pIndex];
  const table = await loadReadings(ch + (wordOf(ch) || ''));
  const r = readingsOf(ch, table);
  $('pChar').textContent = ch;
  $('pRoman').textContent = r.length ? r.join(' / ') + ' 🔊' : '🔊';
  $('pTone').textContent = toneLabel(r[0]) + (r.length > 1 ? ' (most common reading first)' : '');
  $('pStar').textContent = isBookmarked(ch) ? '★' : '☆';
  // The word this character came from, so it can be heard in context.
  const word = wordOf(ch);
  $('pWord').hidden = !word;
  if (word) $('pWordText').textContent = `${word}  ${romanOfText(word, table)}`;
}

function wordOf(ch) {
  const words = (state.ws && state.ws.words) || [];
  return words.find((w) => Array.from(w).length > 1 && w.includes(ch)) || null;
}

/* The 米 guide and border behind a Hanzi Writer box (the practice panel's, or the quiz's). */
function drawWriterGrid(size, g = $('pGrid')) {
  g.innerHTML = '';
  const a = { stroke: '#E0B4B4', 'stroke-width': 0.4, 'stroke-dasharray': '2 1.5' };
  line(g, 50, 0, 50, 100, a);
  line(g, 0, 50, 100, 50, a);
  line(g, 0, 0, 100, 100, a);
  line(g, 0, 100, 100, 0, a);
  el('rect', { x: 0.5, y: 0.5, width: 99, height: 99, fill: 'none', stroke: BRAND, 'stroke-width': 0.8 }, g);
  const wrap = g.parentNode;
  wrap.style.width = size + 'px';
  wrap.style.height = size + 'px';
}

async function openPractice(idx) {
  pIndex = idx;
  $('practice').hidden = false;
  const ch = state.ws.chars[pIndex];
  recordPractised([ch]);
  updatePracticeTitle();
  $('pPrev').disabled = pIndex === 0;
  $('pNext').disabled = pIndex >= state.ws.chars.length - 1;

  // Portrait: writing box above the buttons. Landscape on a short screen: side by side (see style.css).
  const landscape = window.innerWidth > window.innerHeight && window.innerHeight < 700;
  const size = landscape
    ? Math.max(180, Math.min(window.innerHeight - 110, window.innerWidth * 0.5, 480))
    : Math.max(200, Math.min(window.innerWidth - 80, window.innerHeight - 300, 480));
  drawWriterGrid(size);
  const target = $('writer');
  target.innerHTML = '';
  writer = null;
  pData = await loadChar(ch);
  const buttons = ['pAnimate', 'pStep', 'pQuiz', 'pOutline'];
  buttons.forEach((b) => { $(b).disabled = !pData; });
  $('pStrip').innerHTML = '';
  if (!pData) {
    status('No stroke order data for this character.');
    const svg = el('svg', { width: size, height: size, viewBox: '0 0 100 100' }, target);
    text(svg, 50, 82, ch, { 'font-size': 80, 'text-anchor': 'middle', fill: '#222' });
    return;
  }
  writer = HanziWriter.create(target, ch, {
    width: size,
    height: size,
    padding: Math.round(size * 0.06),
    showOutline: outline,
    strokeColor: '#222222',
    radicalColor: BRAND,
    outlineColor: '#DDDDDD',
    drawingColor: '#1E88E5',
    drawingWidth: Math.max(6, Math.round(size / 28)),
    highlightColor: '#FFB300',
    strokeAnimationSpeed: 1,
    delayBetweenStrokes: 250,
    charDataLoader: (c, onLoad, onError) => {
      loadChar(c).then((d) => (d ? onLoad(d) : onError(new Error('no data'))));
    },
  });
  stepIdx = 0;
  status(`${pData.strokes.length} strokes` + (pData.approx ? ' · ≈ assembled from its parts' : ''));

  const n = pData.strokes.length;
  for (let k = 0; k < n; k++) {
    const svg = el('svg', { viewBox: '0 0 44 44' }, $('pStrip'));
    const t = HanziWriter.getScalingTransform(44, 44, 3);
    const g = el('g', { transform: t.transform }, svg);
    for (let s = 0; s <= k; s++) el('path', { d: pData.strokes[s], fill: s === k ? BRAND : '#444' }, g);
  }
}

function closePractice() {
  if (writer) writer.cancelQuiz();
  writer = null;
  $('practice').hidden = true;
}

function setupPractice() {
  $('pClose').onclick = closePractice;
  $('practice').addEventListener('click', (e) => { if (e.target.id === 'practice') closePractice(); });
  $('pPrev').onclick = () => { if (pIndex > 0) openPractice(pIndex - 1); };
  $('pNext').onclick = () => { if (pIndex < state.ws.chars.length - 1) openPractice(pIndex + 1); };
  $('pRoman').onclick = () => speak(state.ws.chars[pIndex]);
  $('pSlow').onclick = () => speak(state.ws.chars[pIndex], SLOW);
  $('pStar').onclick = () => { $('pStar').textContent = toggleBookmark(state.ws.chars[pIndex]) ? '★' : '☆'; };
  $('pWordSay').onclick = () => { const w = wordOf(state.ws.chars[pIndex]); if (w) speak(w); };
  $('pWordSlow').onclick = () => { const w = wordOf(state.ws.chars[pIndex]); if (w) speak(w, SLOW); };
  $('pAnimate').onclick = () => {
    if (!writer) return;
    stepIdx = 0;
    status('Watch the stroke order…');
    writer.showCharacter();
    writer.animateCharacter({ onComplete: () => status(`${pData.strokes.length} strokes`) });
  };
  $('pStep').onclick = async () => {
    if (!writer) return;
    const n = pData.strokes.length;
    if (stepIdx === 0) await writer.hideCharacter({ duration: 150 });
    const k = stepIdx;
    stepIdx = (stepIdx + 1) % n;
    status(`Stroke ${k + 1} of ${n}`);
    await writer.animateStroke(k);
  };
  $('pQuiz').onclick = () => {
    if (!writer) return;
    stepIdx = 0;
    const n = pData.strokes.length;
    status(`Write stroke 1 of ${n} — after 3 misses you get a hint`);
    writer.quiz({
      showHintAfterMisses: 3,
      highlightOnComplete: true,
      onCorrectStroke: (d) => status(d.strokesRemaining ? `✓ Stroke ${d.strokeNum + 1}. Now stroke ${d.strokeNum + 2} of ${n}` : '✓'),
      onMistake: (d) => status(`Not quite — try stroke ${d.strokeNum + 1} again (${d.mistakesOnStroke} ${d.mistakesOnStroke === 1 ? 'miss' : 'misses'})`),
      onComplete: (d) => (recordQuiz(state.ws.chars[pIndex], d.totalMistakes), status(d.totalMistakes === 0 ? '🎉 Perfect — no mistakes!' : `Done — ${d.totalMistakes} ${d.totalMistakes === 1 ? 'mistake' : 'mistakes'}. Try again?`)),
    });
  };
  $('pOutline').onclick = () => {
    if (!writer) return;
    outline = !outline;
    if (outline) writer.showOutline(); else writer.hideOutline();
    $('pOutline').classList.toggle('sel', !outline);
  };
}

// ---------------------------------------------------------------- printing

function setupPrint() {
  $('printBtn').onclick = () => {
    $('exportStatus').hidden = true;
    $('printDialog').hidden = false;
  };
  $('printDialog').addEventListener('click', async (e) => {
    const action = e.target.dataset && e.target.dataset.action;
    if (!action && e.target.id !== 'printDialog') return;
    if (!action || action === 'cancel') { $('printDialog').hidden = true; return; }
    const blank = document.querySelector('input[name=exportInk]:checked').value === 'blank';
    const name = (state.ws.title || 'worksheet') + (blank ? '' : ' (written)');
    if (action === 'print') {
      $('printDialog').hidden = true;
      document.body.classList.toggle('print-blank', blank);
      if (Native && Native.print) Native.print(name);
      else { window.print(); afterPrint(); }
    } else {
      await sharePdf(name, blank);
      $('printDialog').hidden = true;
    }
  });
}

/* Hands each page to the app as shapes and text, which it draws into a PDF and opens Android's share menu with. */
async function sharePdf(name, blank) {
  if (!Native || !Native.shareStart) return toast('Sharing needs the app');
  const status = $('exportStatus');
  status.hidden = false;
  const pages = [...$('pages').querySelectorAll('svg.page')];
  Native.shareStart(name);
  for (let i = 0; i < pages.length; i++) {
    status.textContent = `Preparing page ${i + 1} of ${pages.length}…`;
    Native.sharePage(JSON.stringify(pageDrawing(pages[i], blank)));
    await new Promise((r) => setTimeout(r, 0));
  }
  status.textContent = 'Opening share…';
  Native.shareFinish();
}

/*
 * One page as a list of shapes in page millimetres, with each one's transform, for PageDrawing.kt to draw as sharp
 * lines and real text. Repeated outlines (the same character in many squares) are sent once, in `paths`.
 */
function pageDrawing(svg, blank) {
  const paths = [], pathIndex = new Map(), ops = [];
  const round = (v) => Math.round(v * 1e4) / 1e4;
  const num = (node, attr) => Number(node.getAttribute(attr)) || 0;
  const paint = (node, attr, fallback) => {
    const v = node.getAttribute(attr);
    if (v == null) return fallback;
    return v === 'none' ? null : v;
  };
  const strokeOf = (node) => {
    const dash = node.getAttribute('stroke-dasharray');
    return {
      stroke: paint(node, 'stroke', null),
      sw: num(node, 'stroke-width') || 1,
      cap: node.getAttribute('stroke-linecap') || undefined,
      dash: dash ? dash.trim().split(/[\s,]+/).map(Number) : undefined,
    };
  };
  const walk = (node, m) => {
    for (const child of node.children) {
      if (blank && child.classList.contains('ink')) continue;
      let cm = m;
      const list = child.transform && child.transform.baseVal;
      for (let i = 0; list && i < list.numberOfItems; i++) {
        const t = list.getItem(i).matrix;
        cm = cm.multiply(new DOMMatrix([t.a, t.b, t.c, t.d, t.e, t.f]));
      }
      const at = [cm.a, cm.b, cm.c, cm.d, cm.e, cm.f].map(round);
      switch (child.localName) {
        case 'g':
          walk(child, cm);
          break;
        case 'rect':
          ops.push({ k: 'rect', m: at, x: num(child, 'x'), y: num(child, 'y'), w: num(child, 'width'), h: num(child, 'height'), fill: paint(child, 'fill', '#000') });
          break;
        case 'line':
          ops.push(Object.assign({ k: 'line', m: at, x1: num(child, 'x1'), y1: num(child, 'y1'), x2: num(child, 'x2'), y2: num(child, 'y2') }, strokeOf(child)));
          break;
        case 'path': {
          const d = child.getAttribute('d');
          if (!pathIndex.has(d)) { pathIndex.set(d, paths.length); paths.push(d); }
          ops.push(Object.assign({ k: 'path', m: at, d: pathIndex.get(d), fill: paint(child, 'fill', '#000') }, strokeOf(child)));
          break;
        }
        case 'text': {
          const latin = child.classList.contains('roman') || child.classList.contains('head-latin');
          ops.push({
            k: 'text', m: at, x: num(child, 'x'), y: num(child, 'y'), s: child.textContent, size: num(child, 'font-size'),
            anchor: child.getAttribute('text-anchor') || 'start', font: latin ? 'sans' : 'serif',
            fill: paint(child, 'fill', child.classList.contains('roman') ? '#333' : '#000'),
          });
          break;
        }
        default:
          break;
      }
    }
  };
  walk(svg, new DOMMatrix());
  return { w: PAGE_W, h: PAGE_H, lang: state.lang, paths, ops };
}

window.afterPrint = function afterPrint() {
  document.body.classList.remove('print-blank');
};

// ---------------------------------------------------------------- home: create & list worksheets

/* Earlier versions saved worksheets and their writing; keep just their words, in History. */
function migrateSavedWorksheets() {
  const old = store.get('worksheets', null);
  if (!old) return;
  const h = historyAll();
  for (const ws of old) {
    for (const w of (ws.words && ws.words.length ? ws.words : ws.chars)) {
      if (!h[w]) h[w] = { text: w, first: ws.created || Date.now(), last: ws.created || Date.now(), count: 1 };
    }
    store.del('ink:' + ws.id);
  }
  store.set('history', h);
  store.del('worksheets');
}

/* The worksheet options in the form: set from the last worksheet's, and summed up next to "Worksheet options". */
function formOpts() {
  return {
    size: document.querySelector('input[name=size]:checked').value,
    grid: document.querySelector('input[name=grid]:checked').value,
    strokes: $('fStrokes').checked,
    roman: $('fRoman').checked,
    name: $('fName').checked,
  };
}

function showOptSummary() {
  const o = formOpts();
  const bits = [{ large: 'Large', medium: 'Medium', small: 'Small' }[o.size], { mi: '米', tian: '田', none: 'no guide lines' }[o.grid]];
  if (o.strokes) bits.push('stroke order');
  if (o.roman) bits.push(romanName());
  if (o.name) bits.push('姓名');
  $('optSummary').textContent = '— ' + bits.join(' · ');
}

function setupOptions() {
  const o = Object.assign({}, DEFAULT_OPTS, store.get('lastOpts', {}));
  document.querySelectorAll('input[name=size]').forEach((r) => { r.checked = r.value === o.size; });
  document.querySelectorAll('input[name=grid]').forEach((r) => { r.checked = r.value === o.grid; });
  $('fStrokes').checked = o.strokes !== false;
  $('fRoman').checked = o.roman !== false;
  $('fName').checked = o.name !== false;
  $('wsOptions').addEventListener('change', showOptSummary);
  showOptSummary();
}

function setupHome() {
  $('homeBack').onclick = () => { if (Native && Native.close) Native.close(); };
  setupOptions();
  $('newForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const words = Core.practiceWords($('fChars').value);
    const chars = [...new Set(words.join(''))];
    const err = $('formError');
    if (!words.length) {
      err.textContent = Core.englishPhrases($('fChars').value).length
        ? 'Tap a Chinese word below to use it.' : 'Type at least one Chinese character or English word.';
      err.hidden = false;
      return;
    }
    err.hidden = true;
    const ws = createWorksheet('', chars, words, formOpts());
    $('fChars').value = '';
    renderTranslations();
    openSheet(ws);
  });
}

/* A worksheet for the given characters. Only its words (in History), its options (as defaults) and the writing on each
   word are stored, so practising a word again brings back what was written on it. */
function createWorksheet(title, chars, words, opts) {
  store.set('lastOpts', opts);
  return {
    id: Date.now().toString(36),
    title: title || (words.length ? words.join(' ') : chars.join('')).slice(0, 30),
    chars,
    words,
    opts,
  };
}

/* Practise some text (from a chapter, History or Bookmarks) on a fresh worksheet. */
async function practiseText(title, text, fromChapter) {
  const words = Core.practiceWords(text);
  const chars = [...new Set(words.join(''))];
  if (!words.length) return;
  if (fromChapter) state.autoStarted = true;
  const ws = createWorksheet(title, chars, words, Object.assign({}, DEFAULT_OPTS, store.get('lastOpts', {})));
  await openSheet(ws);
  if (chars.length === 1) openPractice(0);
}

// ---------------------------------------------------------------- English → Chinese

const dictIndex = new Map();
const dictChunks = new Map();
let dictMeta = null;

function dictFile(name) {
  return fetch('dict/' + name + '.json').then((r) => (r.ok ? r.json() : null)).catch(() => null);
}

/* The best Chinese words for an English phrase, in the current language. */
async function searchEnglish(phrase) {
  const tokens = Core.englishTokens(phrase);
  if (!tokens.length) return [];
  if (!dictMeta) dictMeta = (await dictFile('meta')) || { chunk: 1000 };
  const postings = await Promise.all(tokens.map(async (t) => {
    if (!dictIndex.has(t[0])) dictIndex.set(t[0], dictFile('i' + t[0]));
    const table = (await dictIndex.get(t[0])) || {};
    return table[t] || [];
  }));
  // Start from the rarest word; every other word must also be in the entry. Postings are best-first already.
  postings.sort((a, b) => a.length - b.length);
  const others = postings.slice(1).map((p) => new Set(p));
  const candidates = postings[0].filter((i) => others.every((s) => s.has(i))).slice(0, 60);
  const entries = await Promise.all(candidates.map(async (i) => {
    const c = Math.floor(i / dictMeta.chunk);
    if (!dictChunks.has(c)) dictChunks.set(c, dictFile('e' + c));
    return { order: candidates.indexOf(i), entry: ((await dictChunks.get(c)) || [])[i % dictMeta.chunk] };
  }));
  const seen = new Set();
  // Simplified forms for the Mandarin favourites come from the dictionary entries already loaded, when found.
  const simp = new Map(entries.filter((x) => x.entry).map((x) => [x.entry[0], x.entry[1]]));
  const favs = Core.favourites(phrase, state.lang, (w) => simp.get(w) || w);
  return favs.concat(entries
    .map((x) => Object.assign(x, { score: x.entry ? Core.matchScore(x.entry, phrase, state.lang) : null }))
    .filter((x) => x.score !== null)
    .sort((a, b) => a.score - b.score || a.order - b.order)
    .map((x) => x.entry))
    .filter((e) => {
      const word = state.lang === 'yue' ? e[0] : e[1];
      if (seen.has(word)) return false;
      seen.add(word);
      return true;
    })
    .slice(0, 8);
}

let translateTimer = 0;
let translateRun = 0;

/* Shows Chinese words for any English typed in the practice box. Tapping one replaces the English with it. */
function renderTranslations() {
  clearTimeout(translateTimer);
  translateTimer = setTimeout(async () => {
    const run = ++translateRun;
    const phrases = Core.englishPhrases($('fChars').value);
    $('trLang').textContent = state.lang === 'yue' ? '廣東話 Cantonese' : '普通話 Mandarin';
    $('trHint').hidden = phrases.length > 0;
    if (!phrases.length) { $('trResults').innerHTML = ''; return; }
    const results = await Promise.all(phrases.map(searchEnglish));
    if (run !== translateRun) return;
    const table = await loadReadings(results.flat().map((e) => e[0] + e[1]).join(''));
    if (state.lang === 'cmn') {
      // Favourites carry traditional characters; use the dictionary's simplified form when it has one.
      await Promise.all(results.flat().filter((e) => !e[2]).map(async (e) => {
        const hit = (await searchEnglish(e[4])).find((x) => x[0] === e[0] && x[2]);
        if (hit) { e[1] = hit[1]; e[2] = hit[2]; }
      }));
    }
    const out = $('trResults');
    out.innerHTML = '';
    phrases.forEach((phrase, i) => {
      const row = document.createElement('div');
      row.className = 'tr-row';
      const label = document.createElement('div');
      label.className = 'tr-phrase';
      label.textContent = `“${phrase}”`;
      row.appendChild(label);
      if (!results[i].length) {
        const none = document.createElement('span');
        none.className = 'muted';
        none.textContent = 'no match — try another word';
        row.appendChild(none);
      }
      for (const e of results[i]) {
        const word = state.lang === 'yue' ? e[0] : e[1];
        const roman = state.lang === 'yue'
          ? (e[3] || romanOfText(e[0], table))
          : (e[2] ? Core.pinyinMarks(e[2]) : romanOfText(e[0], table));
        const chip = document.createElement('button');
        chip.type = 'button';
        chip.className = 'tr-chip';
        chip.innerHTML = '<span class="tr-word"></span><span class="tr-roman"></span><span class="tr-gloss"></span>';
        chip.querySelector('.tr-word').textContent = word + (state.lang === 'cmn' && e[0] !== e[1] ? ` (${e[0]})` : '');
        chip.querySelector('.tr-roman').textContent = roman;
        chip.querySelector('.tr-gloss').textContent = e[4];
        chip.onclick = () => {
          const field = $('fChars');
          const rx = new RegExp(phrase.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/ /g, '\\s+'), 'i');
          field.value = rx.test(field.value) ? field.value.replace(rx, ' ' + word + ' ') : field.value + ' ' + word;
          field.value = field.value.replace(/\s+/g, ' ').replace(/^ | $/g, '').replace(/ ?[,，] ?/g, ' ');
          renderTranslations();
        };
        row.appendChild(chip);
      }
      out.appendChild(row);
    });
  }, 300);
}

// ---------------------------------------------------------------- quiz: stash, flash cards, written quiz

/* Characters and words set aside for the quiz (added from homework pages by the app, key 'quiz'). Flash cards and the
   written quiz are separate quizzes; each goes through the whole stash in random order, and a missed one comes back
   once at the end of the round. */
const quiz = { mode: '', opt: '', timer: 0, deck: [], i: 0, results: new Map(), writer: null, run: 0, hideTimer: 0 };

function quizItems() {
  return store.get('quiz', []);
}

function renderQuizCard() {
  const items = quizItems();
  const out = $('quizItems');
  out.textContent = '';
  for (const t of items) {
    const chip = document.createElement('span');
    chip.className = 'quiz-item';
    chip.innerHTML = '<span class="q-word"></span><button aria-label="Say it">🔊</button><button aria-label="Remove from the quiz">✕</button>';
    chip.querySelector('.q-word').textContent = t;
    const [say, remove] = chip.querySelectorAll('button');
    say.onclick = () => speak(t);
    remove.onclick = () => { store.set('quiz', quizItems().filter((x) => x !== t)); renderQuizCard(); };
    out.appendChild(chip);
  }
  // Until something is added, a one-line tip says how, instead of the whole card.
  $('quizCard').hidden = items.length === 0;
  $('quizTip').hidden = items.length > 0;
  $('quizStart').hidden = items.length === 0;
  $('quizClear').hidden = items.length === 0;
}

function setupQuiz() {
  const opts = store.get('quizOpts', {});
  const pick = (name, value) => document.querySelectorAll(`input[name=${name}]`).forEach((r) => { r.checked = r.value === value; });
  if (opts.fcDir) pick('fcDir', opts.fcDir);
  if (opts.wqPrompt) pick('wqPrompt', opts.wqPrompt);
  if (opts.wqTimer != null) $('wqTimer').value = String(opts.wqTimer);
  const chosen = (name) => document.querySelector(`input[name=${name}]:checked`).value;
  const saveOpts = () => store.set('quizOpts', { fcDir: chosen('fcDir'), wqPrompt: chosen('wqPrompt'), wqTimer: Number($('wqTimer').value) });
  // Choosing a timer means the Chinese character prompt.
  $('wqTimer').addEventListener('change', () => pick('wqPrompt', 'zh'));
  $('fcStart').onclick = () => { saveOpts(); startQuiz('cards', chosen('fcDir'), 0, quizItems()); };
  $('wqStart').onclick = () => { saveOpts(); startQuiz('write', chosen('wqPrompt'), Number($('wqTimer').value), quizItems()); };
  $('quizClear').onclick = () => {
    if (!confirm('Remove everything from the quiz?')) return;
    store.set('quiz', []);
    renderQuizCard();
  };
  $('qBack').onclick = closeQuiz;
  renderQuizCard();
}

function shuffle(list) {
  for (let i = list.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [list[i], list[j]] = [list[j], list[i]];
  }
  return list;
}

function startQuiz(mode, opt, timer, items) {
  if (!items.length) return;
  Object.assign(quiz, { mode, opt, timer, deck: shuffle(items.slice()), i: 0, results: new Map() });
  $('qTitle').textContent = mode === 'cards' ? 'Flash cards' : 'Written quiz';
  $('home').hidden = true;
  $('quizView').hidden = false;
  showQuizItem();
}

function closeQuiz() {
  stopQuizItem();
  $('quizView').hidden = true;
  $('home').hidden = false;
  renderQuizCard();
  renderLists();
}

function stopQuizItem() {
  quiz.run++;
  clearInterval(quiz.hideTimer);
  if (quiz.writer) quiz.writer.cancelQuiz();
  quiz.writer = null;
}

function quizButtons(list) {
  const out = $('qButtons');
  out.textContent = '';
  for (const [label, onClick, cls] of list) {
    const b = document.createElement('button');
    b.textContent = label;
    if (cls) b.className = cls;
    b.onclick = onClick;
    out.appendChild(b);
  }
}

function bigText(textIn, cls = 'q-big') {
  const d = document.createElement('div');
  d.className = cls;
  d.textContent = textIn;
  return d;
}

function soundButtons(item, auto) {
  const row = document.createElement('div');
  row.className = 'q-buttons';
  const play = document.createElement('button');
  play.className = 'q-sound';
  play.textContent = '🔊';
  play.setAttribute('aria-label', 'Play the sound');
  play.onclick = () => speak(item);
  const slow = document.createElement('button');
  slow.className = 'q-sound';
  slow.textContent = '🐢';
  slow.setAttribute('aria-label', 'Play it slowly');
  slow.onclick = () => speak(item, SLOW);
  row.append(play, slow);
  if (auto) setTimeout(() => speak(item), 300);
  return row;
}

/* The answer side of a card: the word, how it's said, and what it means, leaving out what the prompt already showed. */
async function answerFor(item, prompt) {
  const table = await loadReadings(item);
  const box = document.createElement('div');
  if (prompt !== 'zh') box.append(bigText(item));
  box.append(bigText(romanOfText(item, table), 'q-roman'));
  if (prompt !== 'en') box.append(bigText(await meaningOf(item) || '(no English meaning found)', 'q-meaning'));
  return box;
}

async function showQuizItem() {
  stopQuizItem();
  const run = quiz.run;
  if (quiz.i >= quiz.deck.length) return showQuizSummary();
  const item = quiz.deck[quiz.i];
  $('qProgress').textContent = `${quiz.i + 1} / ${quiz.deck.length}`;
  $('qSummary').hidden = true;
  document.querySelector('.q-side').hidden = false;
  $('qAnswer').hidden = true;
  $('qStatus').textContent = '';
  const prompt = $('qPrompt');
  prompt.textContent = '';
  prompt.hidden = false;
  $('qButtons').hidden = false;
  if (quiz.mode === 'cards') {
    $('qWriteArea').hidden = true;
    if (quiz.opt === 'zh') prompt.append(bigText(item));
    else if (quiz.opt === 'en') prompt.append(bigText(await meaningOf(item) || '(no English meaning found)', 'q-meaning'));
    else prompt.append(soundButtons(item, true));
    if (run !== quiz.run) return;
    quizButtons([['Show answer', () => revealCard(item)]]);
  } else {
    await startWriting(item, run);
  }
}

async function revealCard(item) {
  const run = quiz.run;
  const answer = await answerFor(item, quiz.opt);
  if (run !== quiz.run) return;
  $('qAnswer').textContent = '';
  $('qAnswer').append(answer);
  $('qAnswer').hidden = false;
  if (quiz.opt !== 'sound') speak(item);
  quizButtons([
    ['✗ Again', () => nextQuizItem(item, false), 'q-no'],
    ['✓ Knew it', () => nextQuizItem(item, true), 'q-yes'],
  ]);
}

/* Records the first answer for each item; a missed one comes back once, at the end of the round. */
function nextQuizItem(item, ok, detail) {
  if (!quiz.results.has(item)) {
    quiz.results.set(item, { ok, detail });
    if (!ok) quiz.deck.push(item);
  }
  quiz.i++;
  showQuizItem();
}

async function startWriting(item, run) {
  const prompt = $('qPrompt');
  const chars = Array.from(item).filter(isHan);
  const count = chars.length > 1 ? ` (${chars.length} characters)` : '';
  if (quiz.opt === 'en') {
    prompt.append(bigText(await meaningOf(item) || '(no English meaning found)', 'q-meaning'));
    $('qStatus').textContent = 'Write it in Chinese' + count;
  } else if (quiz.opt === 'sound') {
    const table = await loadReadings(item);
    prompt.append(soundButtons(item, true), bigText(romanOfText(item, table), 'q-roman'));
    $('qStatus').textContent = 'Write what you hear' + count;
  } else {
    const shown = bigText(item);
    prompt.append(shown);
    if (quiz.timer > 0) {
      // Count down, then hide the character so it is written from memory.
      let left = quiz.timer;
      $('qStatus').textContent = `Look carefully — it hides in ${left} s`;
      quiz.hideTimer = setInterval(() => {
        left--;
        if (left > 0) { $('qStatus').textContent = `Look carefully — it hides in ${left} s`; return; }
        clearInterval(quiz.hideTimer);
        shown.className = 'q-hidden';
        shown.textContent = '？'.repeat(Math.max(1, chars.length));
        $('qStatus').textContent = 'Now write it from memory' + count;
      }, 1000);
    } else {
      $('qStatus').textContent = 'Write it' + count;
    }
  }
  if (run !== quiz.run) return;
  $('qWriteArea').hidden = false;
  let mistakes = 0;
  let gaveUp = false;
  const landscape = window.innerWidth > window.innerHeight && window.innerHeight < 700;
  const size = landscape
    ? Math.max(180, Math.min(window.innerHeight - 100, window.innerWidth * 0.45, 460))
    : Math.max(200, Math.min(window.innerWidth - 40, window.innerHeight - 380, 460));
  drawWriterGrid(size, $('qGrid'));

  const finish = async () => {
    if (run !== quiz.run) return;
    for (const ch of chars) recordQuiz(ch, mistakes);
    const ok = !gaveUp && mistakes === 0;
    $('qStatus').textContent = gaveUp ? 'Here is how it is written.' : ok ? '🎉 Perfect — no mistakes!' : `Done — ${mistakes} ${mistakes === 1 ? 'mistake' : 'mistakes'}`;
    // The character was written, so it's in the box; the answer shows the whole word and its reading.
    const answer = await answerFor(item, quiz.opt === 'en' ? 'en' : '');
    if (run !== quiz.run) return;
    $('qAnswer').textContent = '';
    $('qAnswer').append(answer);
    $('qAnswer').hidden = false;
    speak(item);
    quizButtons([['Next →', () => nextQuizItem(item, ok, gaveUp ? 'shown' : mistakes)]]);
  };

  // Each character in turn, from a blank box: no outline, and a hint only after 3 misses on a stroke.
  const writeChar = async (k) => {
    if (run !== quiz.run) return;
    if (k >= chars.length) return finish();
    const ch = chars[k];
    const target = $('qWriter');
    target.innerHTML = '';
    const data = await loadChar(ch);
    if (run !== quiz.run) return;
    if (!data) {
      toast(`No stroke data for ${ch} — skipped`);
      return writeChar(k + 1);
    }
    if (chars.length > 1) $('qProgress').textContent = `${quiz.i + 1} / ${quiz.deck.length} · character ${k + 1} of ${chars.length}`;
    quiz.writer = HanziWriter.create(target, ch, {
      width: size,
      height: size,
      padding: Math.round(size * 0.06),
      showOutline: false,
      showCharacter: false,
      strokeColor: '#222222',
      outlineColor: '#DDDDDD',
      drawingColor: '#1E88E5',
      drawingWidth: Math.max(6, Math.round(size / 28)),
      highlightColor: '#FFB300',
      charDataLoader: (c, onLoad, onError) => {
        loadChar(c).then((d) => (d ? onLoad(d) : onError(new Error('no data'))));
      },
    });
    const w = quiz.writer;
    quizButtons([['Show me', async () => {
      gaveUp = true;
      w.cancelQuiz();
      await w.animateCharacter();
      if (run === quiz.run) writeChar(k + 1);
    }]]);
    w.quiz({
      showHintAfterMisses: 3,
      onCorrectStroke: (d) => { if (d.strokesRemaining) $('qStatus').textContent = `✓ ${d.strokesRemaining} more ${d.strokesRemaining === 1 ? 'stroke' : 'strokes'}`; },
      onMistake: (d) => {
        mistakes++;
        $('qStatus').textContent = d.mistakesOnStroke >= 3 ? 'Follow the hint' : 'Not quite — try that stroke again';
      },
      onComplete: () => setTimeout(() => writeChar(k + 1), 600),
    });
  };
  writeChar(0);
}

function showQuizSummary() {
  stopQuizItem();
  $('qProgress').textContent = '';
  document.querySelector('.q-side').hidden = true;
  $('qPrompt').hidden = true;
  $('qAnswer').hidden = true;
  $('qWriteArea').hidden = true;
  $('qButtons').hidden = true;
  $('qStatus').textContent = '';
  const results = Array.from(quiz.results.entries());
  const right = results.filter(([, r]) => r.ok).length;
  const out = $('qSummary');
  out.textContent = '';
  const head = document.createElement('h2');
  head.textContent = `${right} of ${results.length} right first time`;
  const list = document.createElement('ul');
  for (const [item, r] of results) {
    const li = document.createElement('li');
    const note = quiz.mode === 'write' ? (r.detail === 'shown' ? ' — shown' : r.ok ? '' : ` — ${r.detail} ${r.detail === 1 ? 'mistake' : 'mistakes'}`) : '';
    li.textContent = `${r.ok ? '✓' : '✗'} ${item}${note}`;
    list.appendChild(li);
  }
  const missed = results.filter(([, r]) => !r.ok).map(([item]) => item);
  const buttons = document.createElement('div');
  buttons.className = 'q-buttons';
  if (missed.length) {
    const again = document.createElement('button');
    again.className = 'primary';
    again.textContent = `Quiz the ${missed.length} missed again`;
    again.onclick = () => startQuiz(quiz.mode, quiz.opt, quiz.timer, missed);
    buttons.appendChild(again);
  }
  const done = document.createElement('button');
  done.textContent = 'Done';
  done.onclick = closeQuiz;
  buttons.appendChild(done);
  out.append(head, list, buttons);
  out.hidden = false;
}

// ---------------------------------------------------------------- Chinese → English (quiz meanings)

const wordIndex = new Map();

/* Dictionary entries whose headword (traditional or simplified) is this word. */
async function dictEntriesFor(word) {
  if (!dictMeta) dictMeta = (await dictFile('meta')) || { chunk: 1000 };
  const shard = (word.codePointAt(0) >> Core.SHARD_BITS).toString(16);
  if (!wordIndex.has(shard)) wordIndex.set(shard, dictFile('c' + shard));
  const ids = ((await wordIndex.get(shard)) || {})[word] || [];
  const entries = await Promise.all(ids.map(async (i) => {
    const c = Math.floor(i / dictMeta.chunk);
    if (!dictChunks.has(c)) dictChunks.set(c, dictFile('e' + c));
    return ((await dictChunks.get(c)) || [])[i % dictMeta.chunk];
  }));
  return entries.filter(Boolean);
}

/* A short English meaning for a character or word, e.g. 華 → "flower; magnificent; splendid". */
async function meaningOf(word) {
  let entries = await dictEntriesFor(word);
  // Everyday meanings: names of people and places (capitalised pinyin) only when there is nothing else.
  const common = entries.filter((e) => !/^[A-Z]/.test(e[2]));
  if (common.length) entries = common;
  const glosses = [];
  for (const e of entries.slice(0, 4)) {
    for (let g of e[4].split(';')) {
      g = g.trim();
      // Skip cross-references ("variant of 唔", "see …"): they name other Chinese words, which would give the answer away.
      if (!g || /\p{Script=Han}/u.test(g) || /^(old )?variant of|^see |^used in /i.test(g)) continue;
      if (!glosses.includes(g)) glosses.push(g);
    }
  }
  // Plain meanings first; labelled ones such as "(Beijing dialect) stupid" only when there is nothing else.
  const plain = glosses.filter((g) => !g.startsWith('('));
  if (glosses.length) return (plain.length ? plain : glosses).slice(0, 3).join('; ');
  // Not in the dictionary as a whole: the meanings of its characters.
  const chars = Array.from(word).filter(isHan);
  if (chars.length > 1) {
    const parts = await Promise.all(chars.map(async (c) => ((await meaningOf(c)) || '?').split(';')[0]));
    return parts.join(' + ');
  }
  return '';
}

// ---------------------------------------------------------------- handwriting pad (New worksheet)

/* A teacher writes characters with a stylus (or finger); the app recognises them (ML Kit, on the device) and the
   chosen one is added to the New worksheet box. Strokes are [x, y, t, x, y, t, …] in CSS px and ms. */
const hand = { strokes: [], current: null, penSeen: false, timer: 0, request: 0, t0: 0 };

function setupHandPad() {
  if (!Native || !Native.recognizeInk) return; // recognition is done by the app
  $('handBtn').hidden = false;
  $('handBtn').onclick = openHandPad;
  const canvas = $('hCanvas');
  const point = (e) => {
    const r = canvas.getBoundingClientRect();
    hand.current.push(Math.round(e.clientX - r.left), Math.round(e.clientY - r.top), Math.round(e.timeStamp - hand.t0));
  };
  canvas.addEventListener('pointerdown', (e) => {
    if (e.pointerType === 'pen') hand.penSeen = true;
    else if (hand.penSeen && e.pointerType === 'touch') return; // a hand resting on the screen while writing with a stylus
    canvas.setPointerCapture(e.pointerId);
    clearTimeout(hand.timer);
    if (!hand.strokes.length) hand.t0 = e.timeStamp;
    hand.current = [];
    hand.strokes.push(hand.current);
    point(e);
    drawHand();
  });
  canvas.addEventListener('pointermove', (e) => {
    if (!hand.current) return;
    for (const p of (e.getCoalescedEvents ? e.getCoalescedEvents() : [e])) point(p);
    drawHand();
  });
  const end = () => {
    if (!hand.current) return;
    hand.current = null;
    // Recognise once the writer pauses, so a character isn't guessed after each stroke.
    hand.timer = setTimeout(recognizeHand, 400);
  };
  canvas.addEventListener('pointerup', end);
  canvas.addEventListener('pointercancel', end);
  $('hClear').onclick = clearHand;
  $('hSpace').onclick = () => addToBox(' ');
  $('hDel').onclick = () => {
    const f = $('fChars');
    f.value = Array.from(f.value.replace(/\s+$/, '')).slice(0, -1).join('');
    handBoxChanged();
  };
  $('hDone').onclick = closeHandPad;
  $('hClose').onclick = closeHandPad;
}

function openHandPad() {
  $('handPad').hidden = false;
  clearHand();
  handBoxChanged();
  // Starts the one-time model download now, rather than after the first character is written.
  const r = $('hCanvas').getBoundingClientRect();
  Native.recognizeInk(++hand.request, state.lang, '[]', r.width, r.height);
}

function closeHandPad() {
  clearTimeout(hand.timer);
  $('handPad').hidden = true;
}

function clearHand() {
  clearTimeout(hand.timer);
  hand.strokes = [];
  hand.current = null;
  hand.request++;
  $('hCands').textContent = '';
  drawHand();
}

function drawHand() {
  const canvas = $('hCanvas');
  const dpr = window.devicePixelRatio || 1;
  const r = canvas.getBoundingClientRect();
  if (canvas.width !== Math.round(r.width * dpr)) {
    canvas.width = Math.round(r.width * dpr);
    canvas.height = Math.round(r.height * dpr);
  }
  const g = canvas.getContext('2d');
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.clearRect(0, 0, r.width, r.height);
  g.strokeStyle = '#212121';
  g.lineWidth = Math.max(4, r.width / 60);
  g.lineCap = 'round';
  g.lineJoin = 'round';
  for (const s of hand.strokes) {
    g.beginPath();
    g.moveTo(s[0], s[1]);
    for (let i = 3; i < s.length; i += 3) g.lineTo(s[i], s[i + 1]);
    if (s.length === 3) g.lineTo(s[0] + 0.1, s[1]);
    g.stroke();
  }
}

function recognizeHand() {
  if (!hand.strokes.length) return;
  const r = $('hCanvas').getBoundingClientRect();
  Native.recognizeInk(++hand.request, state.lang, JSON.stringify(hand.strokes), r.width, r.height);
}

window.inkResult = function inkResult(id, result) {
  if (id !== hand.request || $('handPad').hidden) return;
  const status = $('hStatus');
  if (result.downloading) {
    status.textContent = 'Getting handwriting recognition ready (a one-time download)…';
    return;
  }
  if (result.error) {
    status.textContent = 'Handwriting recognition isn\'t ready: connect to the internet once so it can download. (' + result.error + ')';
    return;
  }
  status.textContent = hand.strokes.length ? 'Tap the right character:' : 'Write a character in the square, then tap the right one below.';
  const out = $('hCands');
  out.textContent = '';
  for (const c of result.candidates.filter((t) => Array.from(t).some(isHan)).slice(0, 8)) {
    const b = document.createElement('button');
    b.textContent = c;
    b.onclick = () => { addToBox(c); clearHand(); };
    out.appendChild(b);
  }
};

function addToBox(text) {
  const f = $('fChars');
  f.value = (text === ' ' ? f.value.replace(/\s+$/, '') : f.value) + text;
  handBoxChanged();
}

function handBoxChanged() {
  $('hText').textContent = $('fChars').value.trim() || '—';
  renderTranslations();
}

// ---------------------------------------------------------------- worksheet view

async function openSheet(ws) {
  state.ws = ws;
  loadInk();
  state.undo = [];
  state.row = 0;
  $('home').hidden = true;
  $('sheet').hidden = false;
  $('sheetTitle').textContent = ws.title;
  $('pages').scrollTop = 0;
  recordPractised(ws.words && ws.words.length ? ws.words : ws.chars);
  await renderPages();
}

function closeSheet() {
  saveInk();
  state.ws = null;
  $('pages').innerHTML = '';
  $('sheet').hidden = true;
  $('home').hidden = false;
  renderLists();
}

function setZoom(z) {
  state.zoom = Math.max(0.6, Math.min(2.5, z));
  store.set('zoom', state.zoom);
  $('pages').style.setProperty('--zoom', state.zoom);
}

function updateTools() {
  document.querySelectorAll('#colorTray button.pen').forEach((b) => {
    b.classList.toggle('sel', b.dataset.color === state.color);
  });
  // The pen button shows the pen's colour, and its size by the dot's size.
  const pen = $('penBtn');
  pen.classList.toggle('sel', state.tool === 'pen');
  const dot = pen.querySelector('i');
  const px = 10 + state.size * 5;
  dot.style.width = dot.style.height = px + 'px';
  dot.style.background = state.color;
  $('eraserBtn').classList.toggle('sel', state.tool === 'eraser');
}

/* The pen colours open under the pen button; choosing one (or tapping anywhere else) closes them. */
function toggleColorTray() {
  const tray = $('colorTray');
  if (!tray.hidden) { tray.hidden = true; return; }
  const r = $('penBtn').getBoundingClientRect();
  tray.style.top = (r.bottom + 6) + 'px';
  tray.style.left = Math.max(8, Math.min(r.left, innerWidth - 200)) + 'px';
  tray.hidden = false;
}

function setupSheet() {
  $('sheetBack').onclick = () => {
    if (state.autoStarted && Native && Native.close) { saveInk(); Native.close(); } else closeSheet();
  };
  $('penBtn').onclick = (e) => { e.stopPropagation(); toggleColorTray(); };
  document.querySelectorAll('#colorTray button.pen').forEach((b) => {
    b.onclick = () => { state.color = b.dataset.color; state.tool = 'pen'; $('colorTray').hidden = true; updateTools(); };
  });
  document.addEventListener('pointerdown', (e) => {
    if (!$('colorTray').hidden && !e.target.closest('#colorTray, #penBtn')) $('colorTray').hidden = true;
  }, true);
  $('sizeBtn').onclick = () => { state.size = (state.size + 1) % PEN_SIZES.length; state.tool = 'pen'; updateTools(); };
  $('eraserBtn').onclick = () => { state.tool = state.tool === 'eraser' ? 'pen' : 'eraser'; updateTools(); };
  $('clearBtn').onclick = clearScreen;
  $('fingerBtn').onclick = () => setFingerDraw(!state.fingerDraw, true);
  $('zoomIn').onclick = () => setZoom(state.zoom * 1.25);
  $('zoomOut').onclick = () => setZoom(state.zoom / 1.25);
  setFingerDraw(state.fingerDraw, false);
  setZoom(state.zoom);
  updateTools();
}

// Android back button: returns true when handled here.
window.handleBack = function handleBack() {
  if (!$('practice').hidden) { closePractice(); return true; }
  if (!$('handPad').hidden) { closeHandPad(); return true; }
  if (!$('quizView').hidden) { closeQuiz(); return true; }
  if (!$('printDialog').hidden) { $('printDialog').hidden = true; return true; }
  if (!$('sheet').hidden) {
    if (state.autoStarted) { saveInk(); return false; }
    closeSheet();
    return true;
  }
  return false;
};

// Rotating the device: re-size the phone squares and the practice panel for the new shape.
let resizeTimer = 0;
window.addEventListener('resize', () => {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(() => {
    if (state.ws && state.view === 'row' && !$('sheet').hidden) renderRow();
    if (!$('practice').hidden && state.ws) openPractice(pIndex);
  }, 250);
});

window.addEventListener('pagehide', saveInk);
document.addEventListener('visibilitychange', () => { if (document.hidden) saveInk(); });

document.querySelectorAll('input[name=lang], input[name=lang2]').forEach((r) => {
  r.addEventListener('change', () => { if (r.checked) setLang(r.value); });
});

setupHome();
setupSheet();
setupInk($('pages'));
setupInk($('rowView'));
setupRowView();
setupPractice();
setupPrint();
setupHandPad();
setupQuiz();
setupLists();
renderLists();
migrateSavedWorksheets();
setLang(state.lang);
renderLists();
$('fChars').addEventListener('input', renderTranslations);

// Opened from a chapter: either start a worksheet for the chosen characters straight away, or pre-fill the form.
if (Native && Native.initialText) {
  const initial = Native.initialText();
  const initialTitle = Native.initialTitle ? Native.initialTitle() : '';
  if (initial && Native.autoStart && Native.autoStart()) {
    practiseText(initialTitle, initial, true);
  } else {
    if (initial) $('fChars').value = initial;
  }
}
