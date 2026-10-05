'use strict';
/*
 * Writing practice: printable character worksheets (layout modelled on 12jr/chinese-character-worksheets)
 * that can also be written on directly, with stroke order animations and quizzes from chanind/hanzi-writer.
 * Page geometry is in millimetres on a US Letter sheet (8.5 × 11 in), so the screen and the printout match.
 */

const SVGNS = 'http://www.w3.org/2000/svg';
const PAGE_W = 215.9, PAGE_H = 279.4;
const CELL = 17, CELLS = 11, TOP = 26, ROW_GAP = 24.4, ROWS = 10;
const LEFT = (PAGE_W - CELLS * CELL) / 2;
const MODEL = '#111111', GREY = '#C8C8C8', GRID = '#CFCFCF', BRAND = '#C62828';
const PEN_SIZES = [0.45, 0.8, 1.3];
/* Set on each stroke rather than in CSS, so strokes also look right in the phone squares (<use> copies). */
const INK_STYLE = { fill: 'none', 'stroke-linecap': 'round', 'stroke-linejoin': 'round' };
const Native = window.Android || null;
/* Phones (smallest screen side under 600 dp) get the one-row-at-a-time view; tablets the whole pages. */
const IS_PHONE = Native && Native.isPhone ? Native.isPhone() : Math.min(screen.width, screen.height) < 600;

const $ = (id) => document.getElementById(id);

const store = {
  get(key, fallback) {
    try {
      const v = localStorage.getItem(key);
      return v == null ? fallback : JSON.parse(v);
    } catch (e) {
      return fallback;
    }
  },
  set(key, value) {
    try {
      localStorage.setItem(key, JSON.stringify(value));
    } catch (e) {
      toast('Could not save — storage is full');
    }
  },
  del(key) {
    try { localStorage.removeItem(key); } catch (e) { /* ignore */ }
  },
};

const DEFAULT_OPTS = { grey: 3, grid: 'mi', strokes: true, roman: true, name: true };

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
function toast(msg) {
  const t = $('toast');
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 2600);
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

/* Small pictures of the character after 1, 2, 3 … strokes; the newest stroke in red. */
function strokeStrip(parent, data, x, y, maxWidth) {
  const n = data.strokes.length;
  const step = Math.min(7.2, maxWidth / n);
  const size = step * 0.92;
  for (let k = 0; k < n; k++) {
    const t = HanziWriter.getScalingTransform(size, size, 0);
    const outer = el('g', { transform: `translate(${x + k * step} ${y})` }, parent);
    const g = el('g', { transform: t.transform }, outer);
    for (let s = 0; s <= k; s++) el('path', { d: data.strokes[s], fill: s === k ? BRAND : '#444' }, g);
  }
}

function buildPage(ws, pageIndex, totalPages, table) {
  const svg = el('svg', { viewBox: `0 0 ${PAGE_W} ${PAGE_H}`, class: 'page' });
  svg.dataset.page = pageIndex;
  // Phone squares show parts of this page through <use href="#pg<n>">.
  const root = el('g', { id: 'pg' + pageIndex }, svg);
  el('rect', { x: 0, y: 0, width: PAGE_W, height: PAGE_H, fill: '#fff' }, root);

  const right = LEFT + CELLS * CELL;
  const border = { stroke: '#000', 'stroke-width': 0.3 };
  line(root, LEFT, 17, right, 17, border);
  if (ws.opts.name) text(root, LEFT, 15.4, '姓名：', { 'font-size': 4.2 });
  text(root, PAGE_W / 2, 15.4, ws.title, { 'font-size': 5.2, 'text-anchor': 'middle' });
  text(root, right, 15.4, `${pageIndex + 1}/${totalPages}`, { 'font-size': 3.6, 'text-anchor': 'end', class: 'head-latin' });

  const rows = ws.chars.slice(pageIndex * ROWS, pageIndex * ROWS + ROWS);
  rows.forEach((ch, r) => {
    const y = TOP + r * ROW_GAP;
    const data = state.data[ch];
    const g = el('g', {}, root);
    if (ws.opts.roman) {
      text(g, LEFT, y - 1.6, readingsOf(ch, table)[0] || '', { 'font-size': 3.8, class: 'roman' });
    }
    if (ws.opts.strokes && data) strokeStrip(g, data, LEFT + 19, y - 7.1, right - (LEFT + 19));
    for (let j = 0; j < CELLS; j++) cellGrid(g, LEFT + j * CELL, y, CELL, ws.opts.grid);
    for (let j = 0; j <= CELLS; j++) line(g, LEFT + j * CELL, y, LEFT + j * CELL, y + CELL, border);
    line(g, LEFT, y, right, y, border);
    line(g, LEFT, y + CELL, right, y + CELL, border);
    for (let j = 0; j <= Math.min(ws.opts.grey, CELLS - 1); j++) {
      drawChar(g, ch, data, LEFT + j * CELL, y, CELL, j === 0 ? MODEL : GREY);
    }
    if (data && data.approx) text(g, LEFT + CELL - 0.6, y + 3, '≈', { 'font-size': 3, 'text-anchor': 'end', fill: '#999' });
  });

  el('g', { class: 'ink' }, root);
  return svg;
}

async function renderPages() {
  const ws = state.ws;
  const table = await loadReadings(ws.chars.join(''));
  const unique = [...new Set(ws.chars)];
  const loaded = await Promise.all(unique.map(loadChar));
  state.data = {};
  unique.forEach((c, i) => { state.data[c] = loaded[i]; });

  const pages = $('pages');
  const scrollRatio = pages.scrollTop / Math.max(1, pages.scrollHeight);
  pages.innerHTML = '';
  const total = Math.max(1, Math.ceil(ws.chars.length / ROWS));
  for (let p = 0; p < total; p++) {
    pages.appendChild(buildPage(ws, p, total, table));
    redrawInk(p);
  }
  pages.scrollTop = scrollRatio * pages.scrollHeight;
  if (state.view === 'row') renderRow();
}

// ---------------------------------------------------------------- phone: one row at a time

/* Each square is a window onto the printed page (same drawing, same writing), so printing includes what was written here. */
async function renderRow() {
  const ws = state.ws;
  const i = Math.max(0, Math.min(state.row, ws.chars.length - 1));
  state.row = i;
  const ch = ws.chars[i];
  const page = Math.floor(i / ROWS);
  const y = TOP + (i % ROWS) * ROW_GAP;
  const table = await loadReadings(ch);
  $('rowChar').textContent = ch;
  $('rowRoman').textContent = (readingsOf(ch, table)[0] || '') + ' 🔊';
  $('rowCount').textContent = `${i + 1} / ${ws.chars.length}`;
  $('rowStar').textContent = isBookmarked(ch) ? '★' : '☆';
  $('rowPrev').disabled = i === 0;
  $('rowNext').disabled = i >= ws.chars.length - 1;

  const strip = $('rowStrip');
  strip.innerHTML = '';
  const data = state.data[ch];
  if (data && ws.opts.strokes) {
    data.strokes.forEach((_, k) => {
      const svg = el('svg', { viewBox: '0 0 44 44' }, strip);
      const g = el('g', { transform: HanziWriter.getScalingTransform(44, 44, 3).transform }, svg);
      for (let s = 0; s <= k; s++) el('path', { d: data.strokes[s], fill: s === k ? BRAND : '#444' }, g);
    });
  }

  const cells = $('rowCells');
  cells.innerHTML = '';
  for (let j = 0; j < CELLS; j++) {
    const svg = el('svg', { viewBox: `${LEFT + j * CELL} ${y} ${CELL} ${CELL}`, class: 'pcell' + (j === 0 ? ' model' : '') }, cells);
    svg.dataset.page = page;
    el('use', { href: '#pg' + page }, svg);
  }
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
  $('rowPrev').onclick = () => { state.row--; renderRow(); };
  $('rowNext').onclick = () => { state.row++; renderRow(); };
  $('rowRoman').onclick = () => speak(state.ws.chars[state.row]);
  $('rowSlow').onclick = () => speak(state.ws.chars[state.row], SLOW);
  $('rowStar').onclick = () => { $('rowStar').textContent = toggleBookmark(state.ws.chars[state.row]) ? '★' : '☆'; };
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

let saveTimer = 0;
function saveInkSoon() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(saveInk, 600);
}
function saveInk() {
  clearTimeout(saveTimer);
  if (state.ws) store.set('ink:' + state.ws.id, state.ink);
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
  saveInkSoon();
}

function toPage(svg, e) {
  const pt = svg.createSVGPoint();
  pt.x = e.clientX;
  pt.y = e.clientY;
  return pt.matrixTransform(svg.getScreenCTM().inverse());
}

/* Taps on the model character open the practice panel; taps on the romanization speak it. */
function hitTest(svg, pt) {
  const page = Number(svg.dataset.page);
  const r = Math.floor((pt.y - (TOP - 7.5)) / ROW_GAP);
  if (r < 0 || r >= ROWS) return null;
  const idx = page * ROWS + r;
  if (idx >= state.ws.chars.length) return null;
  const y = TOP + r * ROW_GAP;
  if (pt.x >= LEFT && pt.x <= LEFT + CELL && pt.y >= y && pt.y <= y + CELL) return { type: 'model', idx };
  if (state.ws.opts.roman && pt.x >= LEFT && pt.x <= LEFT + 18 && pt.y >= y - 7 && pt.y < y) return { type: 'speak', idx };
  return null;
}

function setFingerDraw(on, announce) {
  state.fingerDraw = on;
  store.set('fingerDraw', on);
  $('pages').classList.toggle('finger-draw', on);
  $('rowView').classList.toggle('finger-draw', on);
  $('fingerBtn').classList.toggle('sel', on);
  if (announce) toast(on ? 'Fingers draw — scroll with two fingers' : 'Stylus detected — fingers now scroll, the pen writes');
}

function setupInk(pages) {
  const touches = new Map();
  let active = null;
  let tap = null;
  let pan = null;
  let blockTouch = false;

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

  // With finger scrolling on, a stylus must not scroll the page.
  pages.addEventListener('touchstart', (e) => {
    for (const t of e.changedTouches) if (t.touchType === 'stylus') e.preventDefault();
  }, { passive: false });

  pages.addEventListener('pointerdown', (e) => {
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
        else speak(state.ws.chars[tap.hit.idx]);
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
        saveInkSoon();
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
  } else if (a.type === 'erase') {
    state.ink[a.page] = (state.ink[a.page] || []).concat(a.strokes);
    redrawInk(a.page);
  } else if (a.type === 'clear') {
    state.ink = a.ink;
    Object.keys(state.ink).forEach((p) => redrawInk(Number(p)));
  }
  saveInkSoon();
}

function clearAll() {
  if (!Object.values(state.ink).some((l) => l.length)) return;
  if (!confirm('Clear all your writing on this worksheet?')) return;
  const old = state.ink;
  state.undo.push({ type: 'clear', ink: old });
  state.ink = {};
  Object.keys(old).forEach((p) => redrawInk(Number(p)));
  saveInkSoon();
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
  const bmList = $('bmList');
  bmList.innerHTML = '';
  for (const t of bm) bmList.appendChild(listItem(t, table, h[t]));
  $('bmEmpty').hidden = bm.length > 0;
  $('bmPractise').disabled = bm.length === 0;

  const filter = state.historyFilter || 'all';
  const entries = Object.values(h)
    .filter((e) => filter === 'all' || (filter === 'words' ? Array.from(e.text).length > 1 : Array.from(e.text).length === 1))
    .sort((a, b) => b.last - a.last)
    .slice(0, 100);
  const histList = $('histList');
  histList.innerHTML = '';
  for (const e of entries) histList.appendChild(listItem(e.text, table, e));
  $('histEmpty').hidden = entries.length > 0;
  document.querySelectorAll('.filters button').forEach((b) => b.classList.toggle('sel', b.dataset.filter === filter));
}

function setupLists() {
  document.querySelectorAll('.filters button').forEach((b) => {
    b.onclick = () => { state.historyFilter = b.dataset.filter; renderLists(); };
  });
  $('histClear').onclick = () => {
    if (!confirm('Clear your practice history? Bookmarks and worksheets are kept.')) return;
    store.del('history');
    renderLists();
  };
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
  if (Native && Native.setLanguage) Native.setLanguage(lang);
  if (!$('sheet').hidden && state.ws) renderPages();
  if (!$('practice').hidden) updatePracticeTitle();
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

function drawWriterGrid(size) {
  const g = $('pGrid');
  g.innerHTML = '';
  const a = { stroke: '#E0B4B4', 'stroke-width': 0.4, 'stroke-dasharray': '2 1.5' };
  line(g, 50, 0, 50, 100, a);
  line(g, 0, 50, 100, 50, a);
  line(g, 0, 0, 100, 100, a);
  line(g, 0, 100, 100, 0, a);
  el('rect', { x: 0.5, y: 0.5, width: 99, height: 99, fill: 'none', stroke: BRAND, 'stroke-width': 0.8 }, g);
  const wrap = document.querySelector('.writer-wrap');
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

  const size = Math.max(200, Math.min(window.innerWidth - 80, window.innerHeight - 300, 480));
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
  $('printBtn').onclick = () => { $('printDialog').hidden = false; };
  $('printDialog').addEventListener('click', (e) => {
    const mode = e.target.dataset && e.target.dataset.print;
    if (!mode && e.target.id !== 'printDialog') return;
    $('printDialog').hidden = true;
    if (!mode || mode === 'cancel') return;
    saveInk();
    document.body.classList.toggle('print-blank', mode === 'blank');
    const name = (state.ws.title || 'worksheet') + (mode === 'blank' ? '' : ' (written)');
    if (Native && Native.print) Native.print(name);
    else { window.print(); afterPrint(); }
  });
}

window.afterPrint = function afterPrint() {
  document.body.classList.remove('print-blank');
};

// ---------------------------------------------------------------- home: create & list worksheets

function worksheets() {
  return store.get('worksheets', []);
}

function renderSaved() {
  const list = $('savedList');
  list.innerHTML = '';
  const all = worksheets();
  $('savedEmpty').hidden = all.length > 0;
  for (const ws of all) {
    const li = document.createElement('li');
    const open = document.createElement('button');
    open.className = 'open';
    open.innerHTML = '<b></b><span></span>';
    open.querySelector('b').textContent = ws.title;
    open.querySelector('span').textContent = ws.chars.join('').slice(0, 24) + (ws.chars.length > 24 ? '…' : '');
    open.onclick = () => openSheet(ws);
    const del = document.createElement('button');
    del.className = 'icon';
    del.textContent = '🗑';
    del.setAttribute('aria-label', 'Delete ' + ws.title);
    del.onclick = () => {
      if (!confirm(`Delete “${ws.title}” and your writing on it?`)) return;
      store.set('worksheets', worksheets().filter((w) => w.id !== ws.id));
      store.del('ink:' + ws.id);
      renderSaved();
    };
    li.append(open, del);
    list.appendChild(li);
  }
}

function setupHome() {
  $('fGrey').oninput = () => { $('fGreyVal').textContent = $('fGrey').value; };
  $('homeBack').onclick = () => { if (Native && Native.close) Native.close(); };
  $('newForm').addEventListener('submit', (e) => {
    e.preventDefault();
    let chars = Array.from($('fChars').value).filter(isHan);
    if ($('fDedupe').checked) chars = [...new Set(chars)];
    const err = $('formError');
    if (!chars.length) {
      err.textContent = 'Type at least one Chinese character.';
      err.hidden = false;
      return;
    }
    err.hidden = true;
    const words = [...new Set($('fChars').value.match(/\p{Script=Han}+/gu) || [])];
    const ws = createWorksheet($('fTitle').value.trim(), chars, words, {
      grey: Number($('fGrey').value),
      grid: document.querySelector('input[name=grid]:checked').value,
      strokes: $('fStrokes').checked,
      roman: $('fRoman').checked,
      name: $('fName').checked,
    });
    $('fChars').value = '';
    $('fTitle').value = '';
    openSheet(ws);
  });
}

/* Saves a new worksheet; its options become the defaults for worksheets started from the chapter screen. */
function createWorksheet(title, chars, words, opts) {
  const ws = {
    id: Date.now().toString(36),
    title: title || chars.slice(0, 8).join(''),
    chars,
    words,
    opts,
    created: Date.now(),
  };
  store.set('worksheets', [ws].concat(worksheets()));
  store.set('lastOpts', opts);
  renderSaved();
  return ws;
}

/* Practise some text (from a chapter, History or Bookmarks): reuse the worksheet if the same characters were practised before. */
async function practiseText(title, text, fromChapter) {
  const chars = [...new Set(Array.from(text).filter(isHan))];
  if (!chars.length) return;
  if (fromChapter) state.autoStarted = true;
  const words = [...new Set(text.match(/\p{Script=Han}+/gu) || [])];
  const existing = worksheets().find((w) => w.title === title && w.chars.join('') === chars.join(''));
  const ws = existing || createWorksheet(title, chars, words, Object.assign({}, DEFAULT_OPTS, store.get('lastOpts', {})));
  await openSheet(ws);
  if (chars.length === 1) openPractice(0);
}

// ---------------------------------------------------------------- worksheet view

async function openSheet(ws) {
  state.ws = ws;
  state.ink = store.get('ink:' + ws.id, {});
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
  renderSaved();
  renderLists();
}

function setZoom(z) {
  state.zoom = Math.max(0.6, Math.min(2.5, z));
  store.set('zoom', state.zoom);
  $('pages').style.setProperty('--zoom', state.zoom);
}

function updateTools() {
  document.querySelectorAll('button.pen').forEach((b) => {
    b.classList.toggle('sel', state.tool === 'pen' && b.dataset.color === state.color);
    const dot = b.querySelector('i');
    const px = 10 + state.size * 5;
    dot.style.width = dot.style.height = px + 'px';
  });
  $('eraserBtn').classList.toggle('sel', state.tool === 'eraser');
}

function setupSheet() {
  $('sheetBack').onclick = () => {
    if (state.autoStarted && Native && Native.close) { saveInk(); Native.close(); } else closeSheet();
  };
  document.querySelectorAll('button.pen').forEach((b) => {
    b.onclick = () => { state.color = b.dataset.color; state.tool = 'pen'; updateTools(); };
  });
  $('sizeBtn').onclick = () => { state.size = (state.size + 1) % PEN_SIZES.length; state.tool = 'pen'; updateTools(); };
  $('eraserBtn').onclick = () => { state.tool = state.tool === 'eraser' ? 'pen' : 'eraser'; updateTools(); };
  $('undoBtn').onclick = undo;
  $('clearBtn').onclick = clearAll;
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
  if (!$('printDialog').hidden) { $('printDialog').hidden = true; return true; }
  if (!$('sheet').hidden) {
    if (state.autoStarted) { saveInk(); return false; }
    closeSheet();
    return true;
  }
  return false;
};

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
setupLists();
renderLists();
setLang(state.lang);
renderSaved();

// Opened from a chapter: either start a worksheet for the chosen characters straight away, or pre-fill the form.
if (Native && Native.initialText) {
  const initial = Native.initialText();
  const initialTitle = Native.initialTitle ? Native.initialTitle() : '';
  if (initial && Native.autoStart && Native.autoStart()) {
    practiseText(initialTitle, initial, true);
  } else {
    if (initial) $('fChars').value = initial;
    if (initialTitle) $('fTitle').value = initialTitle;
  }
}
