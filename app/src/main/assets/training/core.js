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

  // ---------------------------------------------------------------- English → Chinese dictionary

  /* Must match STOP_WORDS in tools/build_assets.py. */
  const STOP_WORDS = new Set(['a', 'an', 'the', 'to', 'of', 'be', 'or', 'and', 'in', 'on', 'for', 'at', 'by', 'with', 'is', 'as',
    'it', 'sth', 'sb', 'etc', 'esp', 'from', 'into', 'one', 's']);

  /* The searchable English words of a phrase, as indexed by tools/build_assets.py. */
  function englishTokens(text) {
    const cleaned = text.toLowerCase().replace(/\([^)]*\)|\[[^\]]*\]/g, ' ');
    return (cleaned.match(/[a-z]+(?:'[a-z]+)?/g) || []).filter((t) => t.length > 1 && !STOP_WORDS.has(t));
  }

  /* English phrases typed among the characters: "thank you, 早晨, good night" → ["thank you", "good night"]. */
  function englishPhrases(text) {
    return [...new Set(text.split(/[,，;；\n\u3400-\u9fff\u{20000}-\u{3ffff}]+/u)
      .map((p) => p.replace(/[^A-Za-z' -]/g, ' ').replace(/\s+/g, ' ').trim())
      .filter((p) => englishTokens(p).length > 0))];
  }

  /* Meanings marked like this are rarely what a learner is after. */
  const UNUSUAL = /\((old slang|slang|loanword|humorous|literary|archaic|old|dialect|vulgar|internet slang|onom\.|derog\.|formal|written)[^)]*\)|\[written\]/i;

  /*
   * How well a dictionary entry [trad, simp, pinyin, jyutping, glosses, cantoneseOnly, level] matches a phrase.
   * Lower is better; null means it doesn't match (or is Cantonese-only while practising Mandarin).
   * level: how early the word's hardest character is learned, 1 (first grade) to 9 (rare).
   */
  function matchScore(entry, phrase, lang) {
    if (lang === 'cmn' && entry[5]) return null;
    const want = englishTokens(phrase);
    const raw = entry[4].toLowerCase().split(';').map((g) => g.replace(/\([^)]*\)|\[[^\]]*\]/g, '').trim());
    // "to thank", "I'm sorry", "to be happy" all count as the bare word.
    const glosses = raw.map((g) => g.replace(/^(to be |to |i'm |i am |be )/, ''));
    const p = phrase.toLowerCase().trim().replace(/^to /, '');
    let score;
    if (glosses[0] === p) score = 0;
    else if (glosses.includes(p)) score = 1;
    else if (glosses.some((g) => (' ' + g + ' ').includes(' ' + p + ' '))) score = 2;
    else if (want.every((t) => englishTokens(entry[4]).includes(t))) score = 3;
    else return null;
    if (UNUSUAL.test(entry[4])) score += 1.5;
    if (/\[colloquial\]/i.test(entry[4])) score += 0.8;
    // Everyday Cantonese characters (唔, 嘅, 咗…) aren't in the school grade lists, so don't count them as rare.
    const level = entry[5] ? Math.min(entry[6] || 1, 3) : (entry[6] || 1);
    score += (level - 1) * 0.15;
    // Cantonese words are what a Cantonese learner wants first.
    if (lang === 'yue' && entry[5]) score -= 0.3;
    return score;
  }

  /*
   * Everyday spoken words the dictionaries don't list under these English words (e.g. 早晨 is "early morning" in
   * CC-CEDICT, not "good morning"). Shown first. Traditional characters; Mandarin also gives simplified.
   */
  const FAVOURITES = {
    yue: {
      'good morning': ['早晨'], 'thank you': ['多謝', '唔該'], thanks: ['多謝', '唔該'], 'excuse me': ['唔該'], please: ['唔該'],
      goodbye: ['拜拜', '再見'], bye: ['拜拜'], sorry: ['對唔住', '唔好意思'], hello: ['你好', '哈佬'], 'good night': ['早唞'],
      yes: ['係'], no: ['唔係'], not: ['唔'], what: ['乜嘢'], where: ['邊度'], who: ['邊個'], when: ['幾時'], why: ['點解'],
      how: ['點樣'], 'how much': ['幾多錢'], this: ['呢個'], that: ['嗰個'], here: ['呢度'], there: ['嗰度'], thing: ['嘢'],
      he: ['佢'], she: ['佢'], him: ['佢'], her: ['佢'], they: ['佢哋'], we: ['我哋'], you: ['你'], 'you all': ['你哋'],
      have: ['有'], 'do not have': ['冇'], eat: ['食'], drink: ['飲'], look: ['睇'], see: ['睇'], sleep: ['瞓覺'],
      home: ['屋企'], 'go home': ['返屋企'], beautiful: ['靚'], pretty: ['靚'], money: ['錢'], very: ['好'], good: ['好'],
      tired: ['攰'], chat: ['傾偈'], work: ['返工'], school: ['學校'], 'go to school': ['返學'],
    },
    cmn: {
      'good morning': ['早上好', '早安'], 'thank you': ['謝謝'], thanks: ['謝謝'], goodbye: ['再見'], bye: ['拜拜'],
      sorry: ['對不起'], hello: ['你好'], 'good night': ['晚安'], yes: ['是'], no: ['不是'], what: ['什麼'], where: ['哪裡'],
      who: ['誰'], why: ['為什麼'], how: ['怎麼'], this: ['這個'], that: ['那個'], he: ['他'], she: ['她'], they: ['他們'],
      we: ['我們'], you: ['你'], have: ['有'], eat: ['吃'], drink: ['喝'], look: ['看'], sleep: ['睡覺'], home: ['家'],
    },
  };

  /* Favourite words for a phrase, as dictionary-shaped entries (no stored romanization: the app looks it up per character). */
  function favourites(phrase, lang, toSimplified) {
    const words = FAVOURITES[lang][phrase.toLowerCase().trim().replace(/^to /, '')] || [];
    return words.map((w) => [w, toSimplified ? toSimplified(w) : w, '', '', phrase, lang === 'yue' ? 1 : 0, 1]);
  }

  /* "ni3 hao3" → "nǐ hǎo" */
  function pinyinMarks(numbered) {
    const marks = { a: 'āáǎà', e: 'ēéěè', i: 'īíǐì', o: 'ōóǒò', u: 'ūúǔù', 'ü': 'ǖǘǚǜ' };
    return numbered.split(/\s+/).map((syl) => {
      const m = syl.replace(/u:/g, 'ü').match(/^([a-zü]+)([1-5])$/i);
      if (!m) return syl.replace(/u:/g, 'ü');
      const [, letters, toneDigit] = m;
      const tone = Number(toneDigit);
      if (tone === 5) return letters;
      const lower = letters.toLowerCase();
      // Tone mark goes on a or e; on the o of "ou"; otherwise on the last vowel.
      let at = lower.search(/[ae]/);
      if (at < 0) at = lower.indexOf('ou');
      if (at < 0) {
        for (let k = lower.length - 1; k >= 0; k--) if ('iouü'.includes(lower[k])) { at = k; break; }
      }
      if (at < 0) return letters;
      const v = lower[at];
      return letters.slice(0, at) + marks[v][tone - 1] + letters.slice(at + 1);
    }).join(' ');
  }

  // ---------------------------------------------------------------- worksheet page layout

  /*
   * US Letter in millimetres. Square sizes: large matches the K1 homework boxes (26.7 mm, about an inch) and suits
   * young children; small (17 mm) fits up to 11 across.
   */
  const PAGE = { W: 215.9, H: 279.4, CELL: 17, MAX_COLS: 11, TOP: 24, BOTTOM: 12, SIDE: 12 };
  const BOX_SIZES = { large: 26.7, medium: 20, small: 17 };

  /*
   * One page per word: stroke-order rows (each character built up stroke by stroke, each character starting a new
   * row), then a row of the word in grey to trace, then blank rows to the bottom of the page.
   * strokeCounts[i] is the number of strokes of chars[i] (0 = no stroke data: the character is shown whole).
   * Each row holds whole copies of the word, so the number of columns is a multiple of its length.
   */
  function wordLayout(chars, strokeCounts, showStrokes, cell = PAGE.CELL) {
    const maxCols = Math.min(PAGE.MAX_COLS, Math.floor((PAGE.W - 2 * PAGE.SIDE) / cell));
    const len = Math.min(chars.length, maxCols);
    const cols = Math.max(len, Math.floor(maxCols / len) * len);
    const totalRows = Math.floor((PAGE.H - PAGE.TOP - PAGE.BOTTOM) / cell);
    const rows = [];
    if (showStrokes) {
      const seen = new Set();
      chars.forEach((ch, i) => {
        if (seen.has(ch)) return;
        seen.add(ch);
        const n = strokeCounts[i] || 0;
        const steps = n > 0 ? Array.from({ length: n }, (_, k) => k + 1) : [0];
        for (let s = 0; s < steps.length; s += cols) {
          rows.push({ kind: 'strokes', cells: steps.slice(s, s + cols).map((step) => ({ ch, step })) });
        }
      });
      // Very long words: keep at least the trace row and one blank row on the page.
      rows.splice(Math.max(1, totalRows - 2));
    } else {
      rows.push({ kind: 'model' });
    }
    rows.push({ kind: 'trace' });
    while (rows.length < totalRows) rows.push({ kind: 'blank' });
    return { cols, left: (PAGE.W - cols * cell) / 2, top: PAGE.TOP, cell, rows };
  }

  /* Splits typed text into the words to practise: runs of Chinese characters, each short enough to fit a row of large
     squares (7), without repeats. */
  function practiceWords(text) {
    const runs = text.match(/[\u3400-\u9fff\uf900-\ufaff\u{20000}-\u{3ffff}]+/gu) || [];
    const words = [];
    for (const run of runs) {
      const chars = Array.from(run);
      for (let i = 0; i < chars.length; i += 7) words.push(chars.slice(i, i + 7).join(''));
    }
    return [...new Set(words)];
  }

  const Core = { SHARD_BITS, PAGE, BOX_SIZES, wordLayout, practiceWords, shardsOf, readingsIn, LEFT_PARTS, COMPOSE, pathNumbers, mapPath, xBounds, compose, YUE_TONES, toneLabel,
    englishTokens, englishPhrases, matchScore, pinyinMarks, favourites };
  if (typeof module !== 'undefined' && module.exports) module.exports = Core;
  else root.Core = Core;
})(this);
