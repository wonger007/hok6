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

  /*
   * The languages Hok6 can practise. strokes: which stroke data (zh: hanzi-writer-data; ja: AnimCJK kanji and kana;
   * ko: AnimCJK hanja, hangul built from its letters below). field: where its readings are in a readings entry.
   */
  const LANGS = {
    yue: { name: '廣東話 Cantonese', short: '粵', reading: 'Jyutping', strokes: 'zh', field: 0, html: 'zh-HK', nameLabel: '姓名：', examples: '你好 謝謝 唔該' },
    cmn: { name: '普通話 Mandarin', short: '普', reading: 'Pinyin', strokes: 'zh', field: 1, html: 'zh-CN', nameLabel: '姓名：', examples: '你好 谢谢 学校' },
    ja: { name: '日本語 Japanese', short: '日', reading: 'reading (kana)', strokes: 'ja', field: 2, html: 'ja', nameLabel: 'なまえ：', examples: 'ありがとう 学校 水' },
    ko: { name: '한국어 Korean', short: '한', reading: 'romanization', strokes: 'ko', field: 3, html: 'ko', nameLabel: '이름:', examples: '안녕하세요 학교 물' },
  };
  const LANG_CODES = Object.keys(LANGS);

  const HAN = /[\u3400-\u9fff\uf900-\ufaff\u{20000}-\u{3ffff}]/u;
  const KANA = /[\u3041-\u3096\u30a1-\u30fa\u30fc\u3005]/u; // hiragana, katakana, ー and 々
  const HANGUL = /[\uac00-\ud7a3\u3131-\u318e]/u; // syllables and letters (jamo)

  /* Whether a character is practised in a language: Han characters in all of them, plus kana in Japanese and hangul in
     Korean. */
  function isPracticeChar(ch, lang) {
    if (HAN.test(ch)) return true;
    if (lang === 'ja') return KANA.test(ch);
    if (lang === 'ko') return HANGUL.test(ch);
    return false;
  }

  /* The language some text is written in, when its letters say so (kana: Japanese, hangul: Korean); else null. */
  function scriptLang(text) {
    if (KANA.test(text.replace(/[\u3005]/g, ''))) return 'ja';
    if (HANGUL.test(text)) return 'ko';
    return null;
  }

  /* A readings entry "jyutping|pinyin|japanese|korean" → the list for one language. */
  function readingsIn(entry, lang) {
    if (!entry) return [];
    return (entry.split('|')[(LANGS[lang] || LANGS.yue).field] || '').split(' ').filter(Boolean);
  }

  /* A character's readings: from its readings entry, or worked out for kana (rōmaji) and hangul (romanization). */
  function charReadings(ch, entry, lang) {
    if (lang === 'ja' && KANA.test(ch)) return [kanaToRomaji(ch)].filter(Boolean);
    if (lang === 'ko' && HANGUL.test(ch)) return [romanizeHangul(ch)].filter(Boolean);
    return readingsIn(entry, lang);
  }

  // ---------------------------------------------------------------- Japanese: kana → rōmaji (Hepburn)

  const KANA_ROMAJI = {};
  ('あa いi うu えe おo かka きki くku けke こko さsa しshi すsu せse そso たta ちchi つtsu てte とto なna にni ぬnu ねne のno ' +
   'はha ひhi ふfu へhe ほho まma みmi むmu めme もmo やya ゆyu よyo らra りri るru れre ろro わwa ゐi ゑe をo んn ' +
   'がga ぎgi ぐgu げge ごgo ざza じji ずzu ぜze ぞzo だda ぢji づzu でde どdo ばba びbi ぶbu べbe ぼbo ぱpa ぴpi ぷpu ぺpe ぽpo ' +
   'ぁa ぃi ぅu ぇe ぉo ゃya ゅyu ょyo ゎwa ゔvu').split(' ').forEach((p) => { KANA_ROMAJI[p[0]] = p.slice(1); });

  function toHiragana(text) {
    return text.replace(/[\u30a1-\u30f6]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0x60));
  }

  /* "がっこう" → "gakkou", "キャンプ" → "kyanpu", "ラーメン" → "raamen". */
  function kanaToRomaji(text) {
    const s = Array.from(toHiragana(text));
    let out = '';
    for (let i = 0; i < s.length; i++) {
      const c = s[i];
      const next = s[i + 1];
      if (c === 'っ') {
        const r = KANA_ROMAJI[next] || '';
        out += r.startsWith('ch') ? 't' : r[0] || '';
        continue;
      }
      if (c === 'ー') { out += out.slice(-1); continue; }
      let r = KANA_ROMAJI[c];
      if (r === undefined) { out += c; continue; }
      if (next && 'ゃゅょ'.includes(next) && r.endsWith('i') && r.length > 1) {
        // きゃ kya, しゃ sha, ちゃ cha, じゃ ja
        const y = KANA_ROMAJI[next];
        r = /^(sh|ch|j)/.test(r) ? r.slice(0, -1) + y.slice(1) : r.slice(0, -1) + y;
        i++;
      } else if (next && 'ぁぃぅぇぉ'.includes(next) && r.length > 1) {
        r = r.slice(0, -1) + KANA_ROMAJI[next]; // ふぁ fa, てぃ ti
        i++;
      }
      out += r;
    }
    return out;
  }

  // ---------------------------------------------------------------- Korean: hangul letters, romanization, strokes

  const INITIALS = 'ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ';
  const MEDIALS = 'ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ';
  const FINALS = ['', 'ㄱ', 'ㄲ', 'ㄳ', 'ㄴ', 'ㄵ', 'ㄶ', 'ㄷ', 'ㄹ', 'ㄺ', 'ㄻ', 'ㄼ', 'ㄽ', 'ㄾ', 'ㄿ', 'ㅀ', 'ㅁ', 'ㅂ', 'ㅄ', 'ㅅ', 'ㅆ',
    'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'];
  const RR_INITIAL = ['g', 'kk', 'n', 'd', 'tt', 'r', 'm', 'b', 'pp', 's', 'ss', '', 'j', 'jj', 'ch', 'k', 't', 'p', 'h'];
  const RR_MEDIAL = ['a', 'ae', 'ya', 'yae', 'eo', 'e', 'yeo', 'ye', 'o', 'wa', 'wae', 'oe', 'yo', 'u', 'wo', 'we', 'wi', 'yu', 'eu',
    'ui', 'i'];
  const RR_FINAL = ['', 'k', 'k', 'k', 'n', 'n', 'n', 't', 'l', 'k', 'm', 'l', 'l', 'l', 'p', 'l', 'm', 'p', 'p', 't', 't', 'ng', 't', 't',
    'k', 't', 'p', 't'];

  /* A hangul syllable → [initial, medial, final] letter indexes, or null. */
  function hangulParts(ch) {
    const code = ch.codePointAt(0) - 0xac00;
    if (code < 0 || code > 11171) return null;
    return [Math.floor(code / 588), Math.floor((code % 588) / 28), code % 28];
  }

  /* Revised Romanization, letter by letter (without the sound changes between syllables): "학교" → "hakgyo". */
  function romanizeHangul(text) {
    return Array.from(text).map((ch) => {
      const p = hangulParts(ch);
      return p ? RR_INITIAL[p[0]] + RR_MEDIAL[p[1]] + RR_FINAL[p[2]] : (HANGUL.test(ch) ? '' : ch);
    }).join('');
  }

  /*
   * Hangul letters as strokes in a box from 0 to 1 (y down), in stroke order and direction: each stroke a list of
   * points, or {c: [x, y], r: [rx, ry]} for a circle (drawn from the top, anticlockwise).
   */
  const J = {
    'ㄱ': [[[0.12, 0.18], [0.84, 0.18], [0.84, 0.5], [0.74, 0.88]]],
    'ㄴ': [[[0.18, 0.12], [0.18, 0.82], [0.9, 0.82]]],
    'ㄷ': [[[0.18, 0.18], [0.86, 0.18]], [[0.18, 0.18], [0.18, 0.82], [0.9, 0.82]]],
    'ㄹ': [[[0.16, 0.12], [0.84, 0.12], [0.84, 0.47]], [[0.16, 0.47], [0.84, 0.47]], [[0.16, 0.47], [0.16, 0.86], [0.9, 0.86]]],
    'ㅁ': [[[0.16, 0.16], [0.16, 0.86]], [[0.16, 0.16], [0.84, 0.16], [0.84, 0.86]], [[0.16, 0.86], [0.84, 0.86]]],
    'ㅂ': [[[0.18, 0.1], [0.18, 0.88]], [[0.82, 0.1], [0.82, 0.88]], [[0.18, 0.48], [0.82, 0.48]], [[0.18, 0.88], [0.82, 0.88]]],
    'ㅅ': [[[0.5, 0.1], [0.42, 0.45], [0.12, 0.88]], [[0.47, 0.42], [0.88, 0.88]]],
    'ㅇ': [{ c: [0.5, 0.5], r: [0.36, 0.38] }],
    'ㅈ': [[[0.14, 0.16], [0.82, 0.16], [0.5, 0.5], [0.12, 0.88]], [[0.5, 0.5], [0.88, 0.88]]],
    'ㅊ': [[[0.5, 0.0], [0.5, 0.14]], [[0.14, 0.26], [0.82, 0.26], [0.5, 0.58], [0.12, 0.9]], [[0.5, 0.58], [0.88, 0.9]]],
    'ㅋ': [[[0.14, 0.16], [0.84, 0.16], [0.84, 0.5], [0.74, 0.88]], [[0.14, 0.5], [0.82, 0.5]]],
    'ㅌ': [[[0.18, 0.14], [0.86, 0.14]], [[0.18, 0.48], [0.84, 0.48]], [[0.18, 0.14], [0.18, 0.84], [0.9, 0.84]]],
    'ㅍ': [[[0.1, 0.16], [0.9, 0.16]], [[0.34, 0.16], [0.34, 0.84]], [[0.66, 0.16], [0.66, 0.84]], [[0.08, 0.84], [0.92, 0.84]]],
    'ㅎ': [[[0.5, 0.02], [0.5, 0.16]], [[0.12, 0.28], [0.88, 0.28]], { c: [0.5, 0.66], r: [0.28, 0.24] }],
    // Vowels: the long line of ㅏ-type vowels runs the full height, of ㅗ-type vowels the full width.
    'ㅏ': [[[0.35, 0.04], [0.35, 0.96]], [[0.35, 0.48], [0.8, 0.48]]],
    'ㅐ': [[[0.25, 0.04], [0.25, 0.96]], [[0.25, 0.48], [0.6, 0.48]], [[0.75, 0.04], [0.75, 0.96]]],
    'ㅑ': [[[0.35, 0.04], [0.35, 0.96]], [[0.35, 0.36], [0.8, 0.36]], [[0.35, 0.62], [0.8, 0.62]]],
    'ㅒ': [[[0.25, 0.04], [0.25, 0.96]], [[0.25, 0.36], [0.58, 0.36]], [[0.25, 0.62], [0.58, 0.62]], [[0.75, 0.04], [0.75, 0.96]]],
    'ㅓ': [[[0.2, 0.48], [0.65, 0.48]], [[0.65, 0.04], [0.65, 0.96]]],
    'ㅔ': [[[0.08, 0.48], [0.42, 0.48]], [[0.42, 0.04], [0.42, 0.96]], [[0.78, 0.04], [0.78, 0.96]]],
    'ㅕ': [[[0.2, 0.36], [0.65, 0.36]], [[0.2, 0.62], [0.65, 0.62]], [[0.65, 0.04], [0.65, 0.96]]],
    'ㅖ': [[[0.08, 0.36], [0.42, 0.36]], [[0.08, 0.62], [0.42, 0.62]], [[0.42, 0.04], [0.42, 0.96]], [[0.78, 0.04], [0.78, 0.96]]],
    'ㅣ': [[[0.5, 0.04], [0.5, 0.96]]],
    'ㅗ': [[[0.5, 0.1], [0.5, 0.6]], [[0.04, 0.6], [0.96, 0.6]]],
    'ㅛ': [[[0.36, 0.1], [0.36, 0.6]], [[0.64, 0.1], [0.64, 0.6]], [[0.04, 0.6], [0.96, 0.6]]],
    'ㅜ': [[[0.04, 0.35], [0.96, 0.35]], [[0.5, 0.35], [0.5, 0.9]]],
    'ㅠ': [[[0.04, 0.35], [0.96, 0.35]], [[0.36, 0.35], [0.36, 0.9]], [[0.64, 0.35], [0.64, 0.9]]],
    'ㅡ': [[[0.04, 0.5], [0.96, 0.5]]],
  };
  const DOUBLE = { 'ㄲ': 'ㄱ', 'ㄸ': 'ㄷ', 'ㅃ': 'ㅂ', 'ㅆ': 'ㅅ', 'ㅉ': 'ㅈ' };
  const PAIRS = { 'ㄳ': 'ㄱㅅ', 'ㄵ': 'ㄴㅈ', 'ㄶ': 'ㄴㅎ', 'ㄺ': 'ㄹㄱ', 'ㄻ': 'ㄹㅁ', 'ㄼ': 'ㄹㅂ', 'ㄽ': 'ㄹㅅ', 'ㄾ': 'ㄹㅌ', 'ㄿ': 'ㄹㅍ', 'ㅀ': 'ㄹㅎ', 'ㅄ': 'ㅂㅅ' };
  const COMPOUND = { 'ㅘ': 'ㅗㅏ', 'ㅙ': 'ㅗㅐ', 'ㅚ': 'ㅗㅣ', 'ㅝ': 'ㅜㅓ', 'ㅞ': 'ㅜㅔ', 'ㅟ': 'ㅜㅣ', 'ㅢ': 'ㅡㅣ' };
  const TALL = 'ㅏㅐㅑㅒㅓㅔㅕㅖㅣ';

  /* A letter's strokes placed in the box [x0, y0, x1, y1] (doubled and paired consonants side by side). */
  function placeLetter(letter, box) {
    const [x0, y0, x1, y1] = box;
    const twin = DOUBLE[letter] ? DOUBLE[letter] + DOUBLE[letter] : PAIRS[letter];
    if (twin) {
      const mid = (x0 + x1) / 2;
      return placeLetter(twin[0], [x0, y0, mid + 0.02, y1]).concat(placeLetter(twin[1], [mid - 0.02, y0, x1, y1]));
    }
    const fx = (u) => x0 + u * (x1 - x0);
    const fy = (v) => y0 + v * (y1 - y0);
    return (J[letter] || []).map((s) => {
      if (Array.isArray(s)) return s.map(([u, v]) => [fx(u), fy(v)]);
      const pts = [];
      for (let k = 0; k <= 24; k++) {
        const a = -Math.PI / 2 - (k / 24) * 2 * Math.PI;
        pts.push([fx(s.c[0] + s.r[0] * Math.cos(a)), fy(s.c[1] + s.r[1] * Math.sin(a))]);
      }
      return pts;
    });
  }

  /* Where the letters of a syllable go (boxes in a 0-1 square, y down), by the shape of its vowel and whether it has a
     final consonant. */
  function syllableLayout(medial, hasFinal) {
    const compound = COMPOUND[medial];
    if (compound) {
      return hasFinal
        ? { init: [0.04, 0, 0.52, 0.3], parts: [[0, 0.24, 0.68, 0.6], [0.58, 0, 0.96, 0.6]], final: [0.14, 0.62, 0.86, 0.98] }
        : { init: [0.04, 0.02, 0.56, 0.46], parts: [[0, 0.4, 0.72, 0.96], [0.6, 0, 0.98, 1]] };
    }
    if (TALL.includes(medial)) {
      return hasFinal
        ? { init: [0.04, 0.04, 0.54, 0.52], parts: [[0.5, 0, 0.96, 0.58]], final: [0.12, 0.6, 0.88, 0.98] }
        : { init: [0.04, 0.14, 0.56, 0.86], parts: [[0.5, 0.02, 0.98, 0.98]] };
    }
    return hasFinal
      ? { init: [0.24, 0, 0.76, 0.28], parts: [[0.02, 0.24, 0.98, 0.6]], final: [0.16, 0.62, 0.84, 0.98] }
      : { init: [0.2, 0.04, 0.8, 0.44], parts: [[0.02, 0.42, 0.98, 0.96]] };
  }

  /* The strokes of a hangul syllable or letter as lists of points in the 0-1 square, or null. */
  function hangulStrokes(ch) {
    const p = hangulParts(ch);
    if (p) {
      const medial = MEDIALS[p[1]];
      const final = FINALS[p[2]];
      const layout = syllableLayout(medial, !!final);
      const vowels = COMPOUND[medial] || medial;
      let strokes = placeLetter(INITIALS[p[0]], layout.init);
      Array.from(vowels).forEach((v, i) => { strokes = strokes.concat(placeLetter(v, layout.parts[i])); });
      if (final) strokes = strokes.concat(placeLetter(final, layout.final));
      return strokes;
    }
    if (!/[\u3131-\u3163]/.test(ch)) return null;
    // A letter on its own.
    if (COMPOUND[ch]) {
      return placeLetter(COMPOUND[ch][0], [0.05, 0.35, 0.7, 0.85]).concat(placeLetter(COMPOUND[ch][1], [0.6, 0.05, 0.95, 0.95]));
    }
    if (TALL.includes(ch)) return placeLetter(ch, [0.28, 0.06, 0.72, 0.94]);
    if (MEDIALS.includes(ch)) return placeLetter(ch, [0.06, 0.25, 0.94, 0.75]);
    return placeLetter(ch, [0.14, 0.14, 0.86, 0.86]);
  }

  /*
   * Stroke data for a hangul syllable or letter, in hanzi-writer-data's form: each stroke's outline (a pen of even
   * width along the stroke: one rounded piece per segment, so joins and ends are round) and its median.
   */
  function hangulData(ch, width = 58) {
    const strokes = hangulStrokes(ch);
    if (!strokes) return null;
    const X = (u) => 70 + u * 884;
    const Y = (v) => 830 - v * 884; // y up, as in the stroke data
    const w = width / 2;
    const n = (v) => Math.round(v * 10) / 10;
    const capsule = ([ax, ay], [bx, by]) => {
      const len = Math.hypot(bx - ax, by - ay) || 1;
      const dx = (bx - ax) / len, dy = (by - ay) / len;
      const pts = [];
      // Down one side, round the far end, back up the other side, round the near end.
      for (let k = 0; k <= 6; k++) {
        const a = Math.PI / 2 - (k / 6) * Math.PI;
        pts.push([bx + w * (dx * Math.cos(a) - dy * Math.sin(a)), by + w * (dy * Math.cos(a) + dx * Math.sin(a))]);
      }
      for (let k = 0; k <= 6; k++) {
        const a = -Math.PI / 2 - (k / 6) * Math.PI;
        pts.push([ax + w * (dx * Math.cos(a) - dy * Math.sin(a)), ay + w * (dy * Math.cos(a) + dx * Math.sin(a))]);
      }
      return 'M' + pts.map(([x, y]) => `${n(x)} ${n(y)}`).join(' L') + ' Z';
    };
    const medians = strokes.map((s) => s.map(([u, v]) => [n(X(u)), n(Y(v))]));
    return {
      strokes: medians.map((m) => m.slice(1).map((pt, i) => capsule(m[i], pt)).join(' ')),
      medians,
    };
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
    if (!reading || (lang !== 'yue' && lang !== 'cmn')) return '';
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
    return [...new Set(text.split(/[,，;；、。\n\u3040-\u30ff\u3131-\u318e\uac00-\ud7a3\u3400-\u9fff\u{20000}-\u{3ffff}]+/u)
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
    // Japanese and Korean: [word, its kana reading / hanja].
    ja: {
      'good morning': [['おはよう', 'おはよう'], ['おはようございます', 'おはようございます']], hello: [['こんにちは', 'こんにちは']],
      'good evening': [['こんばんは', 'こんばんは']], 'good night': [['おやすみなさい', 'おやすみなさい']],
      'thank you': [['ありがとう', 'ありがとう'], ['ありがとうございます', 'ありがとうございます']], thanks: [['ありがとう', 'ありがとう']],
      goodbye: [['さようなら', 'さようなら']], bye: [['じゃあね', 'じゃあね']], sorry: [['ごめんなさい', 'ごめんなさい'], ['すみません', 'すみません']],
      'excuse me': [['すみません', 'すみません']], please: [['ください', 'ください'], ['おねがいします', 'おねがいします']],
      yes: [['はい', 'はい']], no: [['いいえ', 'いいえ']], eat: [['食べる', 'たべる']], drink: [['飲む', 'のむ']], see: [['見る', 'みる']],
      look: [['見る', 'みる']], go: [['行く', 'いく']], come: [['来る', 'くる']], water: [['水', 'みず']], school: [['学校', 'がっこう']],
      good: [['いい', 'いい']], friend: [['友達', 'ともだち']], i: [['私', 'わたし']], you: [['あなた', 'あなた']],
    },
    ko: {
      hello: [['안녕하세요', '']], 'good morning': [['안녕하세요', '']], hi: [['안녕', '']], 'thank you': [['감사합니다', ''], ['고맙습니다', '']],
      thanks: [['고마워요', '']], sorry: [['미안합니다', ''], ['죄송합니다', '']], please: [['주세요', '']], yes: [['네', ''], ['예', '']],
      no: [['아니요', '']], eat: [['먹다', '']], drink: [['마시다', '']], see: [['보다', '']], look: [['보다', '']], go: [['가다', '']],
      come: [['오다', '']], water: [['물', '']], school: [['학교', '學校']], friend: [['친구', '親舊']], love: [['사랑', '']],
      i: [['나', ''], ['저', '']], you: [['너', ''], ['당신', '']], good: [['좋다', '']], apple: [['사과', '沙果']],
    },
  };

  /* Favourite words for a phrase, as dictionary-shaped entries (no stored romanization: the app looks it up per character). */
  function favourites(phrase, lang, toSimplified) {
    const words = (FAVOURITES[lang] || {})[phrase.toLowerCase().trim().replace(/^to /, '')] || [];
    return words.map((w) => (Array.isArray(w)
      ? [w[0], w[1], '', '', phrase, 0, 1]
      : [w, toSimplified ? toSimplified(w) : w, '', '', phrase, lang === 'yue' ? 1 : 0, 1]));
  }

  /* The word a dictionary entry gives in a language: simplified for Mandarin, else the headword. */
  function entryWord(entry, lang) {
    return lang === 'cmn' ? entry[1] : entry[0];
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

  /* Splits typed text into the words to practise: runs of the language's characters (Han; kana in Japanese, hangul in
     Korean), each short enough to fit a row of large squares (7), without repeats. */
  function practiceWords(text, lang = 'yue') {
    const runs = [];
    let run = '';
    for (const ch of text) {
      if (isPracticeChar(ch, lang)) run += ch;
      else if (run) { runs.push(run); run = ''; }
    }
    if (run) runs.push(run);
    const words = [];
    for (const run of runs) {
      const chars = Array.from(run);
      for (let i = 0; i < chars.length; i += 7) words.push(chars.slice(i, i + 7).join(''));
    }
    return [...new Set(words)];
  }

  const Core = { SHARD_BITS, PAGE, BOX_SIZES, wordLayout, practiceWords, shardsOf, readingsIn, LEFT_PARTS, COMPOSE, pathNumbers, mapPath, xBounds, compose, YUE_TONES, toneLabel,
    englishTokens, englishPhrases, matchScore, pinyinMarks, favourites, LANGS, LANG_CODES, isPracticeChar, scriptLang, charReadings,
    kanaToRomaji, toHiragana, hangulParts, romanizeHangul, hangulStrokes, hangulData, entryWord };
  if (typeof module !== 'undefined' && module.exports) module.exports = Core;
  else root.Core = Core;
})(this);
