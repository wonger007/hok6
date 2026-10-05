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

/* Worksheets aren't saved (History keeps the words), so writing lasts only while the worksheet is open. */
function saveInkSoon() {}
function saveInk() {}

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
  const filter = state.historyFilter || 'all';
  // History and bookmarks share one list; the ★ Bookmarks tab shows bookmarks (practised or not), newest first.
  const items = filter === 'bookmarks'
    ? bm.map((t) => h[t] || { text: t })
    : Object.values(h)
      .filter((e) => filter === 'all' || (filter === 'words' ? Array.from(e.text).length > 1 : Array.from(e.text).length === 1))
      .sort((a, b) => b.last - a.last)
      .slice(0, 100);
  const histList = $('histList');
  histList.innerHTML = '';
  for (const e of items) histList.appendChild(listItem(e.text, table, e.last ? e : null));
  $('histEmpty').hidden = items.length > 0;
  $('histEmpty').textContent = filter === 'bookmarks'
    ? 'No bookmarks yet. Tap ☆ on a character or word to bookmark it.'
    : 'Words and characters you practise appear here. Tap one to practise it again; tap ☆ to bookmark it.';
  $('bmPractise').hidden = filter !== 'bookmarks' || bm.length === 0;
  $('histClear').hidden = filter === 'bookmarks';
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

/* Draws each page to an image and hands them to the app, which makes a PDF and opens Android's share menu. */
async function sharePdf(name, blank) {
  if (!Native || !Native.shareStart) return toast('Sharing needs the app');
  const status = $('exportStatus');
  status.hidden = false;
  const pages = [...$('pages').querySelectorAll('svg.page')];
  Native.shareStart(name);
  for (let i = 0; i < pages.length; i++) {
    status.textContent = `Preparing page ${i + 1} of ${pages.length}…`;
    Native.sharePage(await pageImage(pages[i], blank));
  }
  status.textContent = 'Opening share…';
  Native.shareFinish();
}

/* One page as a PNG (base64), about 190 dpi on US Letter. */
function pageImage(svg, blank) {
  const scale = 2;
  const width = 816 * scale, height = 1056 * scale;
  const copy = svg.cloneNode(true);
  copy.setAttribute('width', width);
  copy.setAttribute('height', height);
  copy.setAttribute('xmlns', SVGNS);
  if (blank) copy.querySelectorAll('.ink').forEach((n) => n.remove());
  const style = document.createElementNS(SVGNS, 'style');
  style.textContent = 'text{font-family:"Noto Serif CJK HK","Noto Serif CJK TC",serif}.roman,.head-latin{font-family:"Noto Sans",Roboto,sans-serif}.roman{fill:#333}';
  copy.insertBefore(style, copy.firstChild);
  const url = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(new XMLSerializer().serializeToString(copy));
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => {
      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;
      const ctx = canvas.getContext('2d');
      ctx.fillStyle = '#fff';
      ctx.fillRect(0, 0, width, height);
      ctx.drawImage(img, 0, 0, width, height);
      resolve(canvas.toDataURL('image/png').split(',')[1]);
    };
    img.onerror = reject;
    img.src = url;
  });
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

function setupHome() {
  $('homeBack').onclick = () => { if (Native && Native.close) Native.close(); };
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
    const ws = createWorksheet('', chars, words, {
      size: document.querySelector('input[name=size]:checked').value,
      grid: document.querySelector('input[name=grid]:checked').value,
      strokes: $('fStrokes').checked,
      roman: $('fRoman').checked,
      name: $('fName').checked,
    });
    $('fChars').value = '';
    renderTranslations();
    openSheet(ws);
  });
}

/* A worksheet for the given characters. Nothing is stored except its words (in History) and its options (as defaults). */
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

// ---------------------------------------------------------------- worksheet view

async function openSheet(ws) {
  state.ws = ws;
  state.ink = {};
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
