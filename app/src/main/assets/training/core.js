/*
 * Pure logic shared by the worksheet page (app.js) and the tests (app/src/test/js):
 * no DOM, no app state. Loaded as a plain script in the app, and with require() in Node.
 */
(function (root) {
  'use strict';

  /* Readings are stored in files of 2^SHARD_BITS code points (see tools/build_assets.py). */
  const SHARD_BITS = 10;

  /* The reading files needed for some text. */
  function shardsOf(text) {
    return [...new Set(Array.from(text).map((c) => c.codePointAt(0) >> SHARD_BITS))];
  }

  /* A readings entry "jyut1 jyut2|pin1 pin2" → the list for one language ('yue' or 'cmn'). */
  function readingsIn(entry, lang) {
    if (!entry) return [];
    const [yue, cmn] = entry.split('|');
    return ((lang === 'yue' ? yue : cmn) || '').split(' ').filter(Boolean);
  }

  /*
   * Colloquial Cantonese characters are missing from the stroke data. They are assembled from parts:
   * a left-hand radical cut out of a character that has it, plus a right-hand part.
   *   r:    a whole character, squeezed into the right-hand side
   *   from: a character with the same right-hand side; its first `drop` strokes (its own radical) are replaced
   *   keep: the first `keep` strokes of a related character
   */
  const LEFT_PARTS = {
    '口': ['吃', '乞'], '亻': ['他', '也'], '扌': ['打', '丁'], '訁': ['說', '兌'],
    '飠': ['飯', '反'], '火': ['炒', '少'], '氵': ['河', '可'],
  };
  const COMPOSE = {
    '咗': { l: '口', r: '左' }, '哋': { l: '口', r: '地' }, '啲': { l: '口', r: '的' }, '喺': { l: '口', r: '係' },
    '嚟': { l: '口', r: '黎' }, '噉': { l: '口', r: '敢' }, '咁': { l: '口', r: '甘' }, '嗰': { l: '口', r: '個' },
    '㗎': { l: '口', r: '架' }, '啱': { l: '口', r: '岩' }, '嚿': { l: '口', r: '舊' }, '嘥': { l: '口', r: '徙' },
    '啩': { l: '口', r: '卦' }, '嗱': { l: '口', r: '拿' }, '喐': { l: '口', r: '郁' }, '噏': { l: '口', r: '翕' },
    '嚫': { l: '口', r: '親' }, '嚡': { l: '口', r: '鞋' }, '嗮': { l: '口', r: '晒' }, '噚': { l: '口', r: '尋' },
    '噃': { l: '口', from: '播', drop: 3 }, '喎': { l: '口', from: '渦', drop: 3 },
    '佢': { l: '亻', r: '巨' },
    '攞': { l: '扌', r: '羅' }, '㩒': { l: '扌', r: '禽' }, '掹': { l: '扌', r: '孟' }, '扻': { l: '扌', r: '欠' },
    '抦': { l: '扌', r: '丙' }, '搵': { l: '扌', from: '溫', drop: 3 }, '揾': { l: '扌', from: '溫', drop: 3 },
    '諗': { l: '訁', r: '念' }, '餸': { l: '飠', r: '送' }, '煀': { l: '火', r: '屈' },
    '冇': { from: '有', keep: 4 },
  };

  function pathNumbers(d) {
    return (d.match(/-?\d+(?:\.\d+)?/g) || []).map(Number);
  }

  /* Stroke paths only use absolute M/L/Q/C/Z commands, so the numbers alternate x, y. */
  function mapPath(d, fx, fy) {
    let i = 0;
    return d.replace(/-?\d+(?:\.\d+)?/g, (m) => {
      const v = Number(m);
      const out = i % 2 === 0 ? fx(v) : fy(v);
      i++;
      return out.toFixed(1);
    });
  }

  function xBounds(strokes) {
    let min = Infinity, max = -Infinity;
    for (const d of strokes) {
      const n = pathNumbers(d);
      for (let i = 0; i < n.length; i += 2) {
        min = Math.min(min, n[i]);
        max = Math.max(max, n[i]);
      }
    }
    return [min, max];
  }

  async function compose(ch, fetchRaw) {
    const rule = COMPOSE[ch];
    if (!rule) throw new Error('no data');
    if (rule.keep) {
      const d = await fetchRaw(rule.from);
      return { strokes: d.strokes.slice(0, rule.keep), medians: d.medians.slice(0, rule.keep), approx: true };
    }
    const [srcName, srcRightName] = LEFT_PARTS[rule.l];
    const [src, srcRight] = await Promise.all([fetchRaw(srcName), fetchRaw(srcRightName)]);
    const n = src.strokes.length - srcRight.strokes.length;
    const left = { strokes: src.strokes.slice(0, n), medians: src.medians.slice(0, n) };
    let right;
    if (rule.from) {
      const d = await fetchRaw(rule.from);
      right = { strokes: d.strokes.slice(rule.drop), medians: d.medians.slice(rule.drop) };
    } else {
      const d = await fetchRaw(rule.r);
      const [, leftMax] = xBounds(left.strokes);
      const [x0, x1] = xBounds(d.strokes);
      const start = leftMax + 25;
      const sx = (1000 - start) / (x1 - x0);
      const fx = (x) => (x - x0) * sx + start;
      const fy = (y) => (y - 388) * 0.95 + 388;
      right = {
        strokes: d.strokes.map((p) => mapPath(p, fx, fy)),
        medians: d.medians.map((m) => m.map(([x, y]) => [fx(x), fy(y)])),
      };
    }
    return {
      strokes: left.strokes.concat(right.strokes),
      medians: left.medians.concat(right.medians),
      approx: true,
    };
  }

  const YUE_TONES = { 1: 'high level', 2: 'high rising', 3: 'mid level', 4: 'low falling', 5: 'low rising', 6: 'low level' };

  /* "nei5" → "tone 5 · low rising"; "nǐ" → "3rd tone · dipping". */
  function toneLabel(reading, lang) {
    if (!reading) return '';
    if (lang === 'yue') {
      const m = reading.match(/([1-6])$/);
      return m ? `tone ${m[1]} · ${YUE_TONES[m[1]]}` : '';
    }
    const marks = reading.normalize('NFD');
    if (marks.includes('\u0304')) return '1st tone · high level';
    if (marks.includes('\u0301')) return '2nd tone · rising';
    if (marks.includes('\u030C')) return '3rd tone · dipping';
    if (marks.includes('\u0300')) return '4th tone · falling';
    return 'neutral tone';
  }

  const Core = { SHARD_BITS, shardsOf, readingsIn, LEFT_PARTS, COMPOSE, pathNumbers, mapPath, xBounds, compose, YUE_TONES, toneLabel };
  if (typeof module !== 'undefined' && module.exports) module.exports = Core;
  else root.Core = Core;
})(this);
