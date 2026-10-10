// Tests for app/src/main/assets/training/core.js and the bundled character data.
// Run: node --test app/src/test/js
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ASSETS = path.join(__dirname, '..', '..', 'main', 'assets');
const Core = require(path.join(ASSETS, 'training', 'core.js'));

/* Reads a stroke data bundle (hanzi.bin, kanji.bin, hanja.bin) the same way HanziBundle.kt does. */
function openBundle(name = 'hanzi.bin') {
  const buf = fs.readFileSync(path.join(ASSETS, name));
  assert.strictEqual(buf.toString('ascii', 0, 4), 'HZB1');
  const count = buf.readUInt32LE(4);
  const index = new Map();
  for (let i = 0; i < count; i++) {
    const at = 8 + i * 12;
    index.set(buf.readUInt32LE(at), [buf.readUInt32LE(at + 4), buf.readUInt32LE(at + 8)]);
  }
  return {
    count,
    get(ch) {
      const e = index.get(ch.codePointAt(0));
      return e ? JSON.parse(zlib.inflateSync(buf.subarray(e[0], e[0] + e[1])).toString('utf8')) : null;
    },
  };
}
const bundle = openBundle();
const fetchRaw = async (ch) => {
  const d = bundle.get(ch);
  if (!d) throw new Error('missing ' + ch);
  return d;
};

function readings(ch) {
  const shard = Core.shardsOf(ch)[0].toString(16);
  const table = JSON.parse(fs.readFileSync(path.join(ASSETS, 'training', 'readings', shard + '.json'), 'utf8'));
  return table[ch];
}

test('every assembled Cantonese character can be built from the bundled data', async () => {
  for (const ch of Object.keys(Core.COMPOSE)) {
    assert.strictEqual(bundle.get(ch), null, `${ch} is in the stroke data now; its COMPOSE rule is unused`);
    const d = await Core.compose(ch, fetchRaw);
    assert.ok(d.approx, ch);
    assert.ok(d.strokes.length >= 4, `${ch}: only ${d.strokes.length} strokes`);
    assert.strictEqual(d.strokes.length, d.medians.length, `${ch}: strokes and medians differ`);
  }
});

test('assembled characters stay inside the character box, radical on the left', async () => {
  for (const ch of Object.keys(Core.COMPOSE)) {
    const rule = Core.COMPOSE[ch];
    const d = await Core.compose(ch, fetchRaw);
    for (const m of d.medians) {
      for (const [x, y] of m) {
        assert.ok(x >= -5 && x <= 1030 && y >= -130 && y <= 905, `${ch}: point ${x},${y} outside the box`);
      }
    }
    if (rule.l && rule.r) {
      const [srcName, srcRight] = Core.LEFT_PARTS[rule.l];
      const n = bundle.get(srcName).strokes.length - bundle.get(srcRight).strokes.length;
      const [, leftMax] = Core.xBounds(d.strokes.slice(0, n));
      const [rightMin] = Core.xBounds(d.strokes.slice(n));
      assert.ok(leftMax < rightMin + 40, `${ch}: left part overlaps the right part`);
    }
  }
});

test('咗 is 口 (3 strokes) followed by 左 (5 strokes)', async () => {
  const d = await Core.compose('咗', fetchRaw);
  assert.strictEqual(d.strokes.length, 8);
});

test('冇 is the first four strokes of 有', async () => {
  const d = await Core.compose('冇', fetchRaw);
  assert.deepStrictEqual(d.strokes, bundle.get('有').strokes.slice(0, 4));
});

test('characters without data or a rule are not assembled', async () => {
  await assert.rejects(Core.compose('A', fetchRaw));
});

test('mapPath moves every x and y but keeps the drawing commands', () => {
  const out = Core.mapPath('M 10 20 Q 30 40 50 60 L 70 80 Z', (x) => x + 1, (y) => y * 2);
  assert.strictEqual(out, 'M 11.0 40.0 Q 31.0 80.0 51.0 120.0 L 71.0 160.0 Z');
});

test('tone names for Jyutping and Pinyin', () => {
  assert.strictEqual(Core.toneLabel('nei5', 'yue'), 'tone 5 · low rising');
  assert.strictEqual(Core.toneLabel('si1', 'yue'), 'tone 1 · high level');
  assert.strictEqual(Core.toneLabel('caa4', 'yue'), 'tone 4 · low falling');
  assert.strictEqual(Core.toneLabel('nǐ', 'cmn'), '3rd tone · dipping');
  assert.strictEqual(Core.toneLabel('chá', 'cmn'), '2nd tone · rising');
  assert.strictEqual(Core.toneLabel('xiè', 'cmn'), '4th tone · falling');
  assert.strictEqual(Core.toneLabel('mā', 'cmn'), '1st tone · high level');
  assert.strictEqual(Core.toneLabel('de', 'cmn'), 'neutral tone');
  assert.strictEqual(Core.toneLabel('', 'yue'), '');
});

test('readings: most common Cantonese reading first, Pinyin from Unihan', () => {
  assert.deepStrictEqual(Core.readingsIn(readings('好'), 'yue').slice(0, 2), ['hou2', 'hou3']);
  assert.deepStrictEqual(Core.readingsIn(readings('你'), 'cmn'), ['nǐ']);
  assert.strictEqual(Core.readingsIn(readings('咗'), 'yue')[0], 'zo2');
  assert.strictEqual(Core.readingsIn(readings('嘅'), 'yue')[0], 'ge3');
  assert.strictEqual(Core.readingsIn(readings('謝'), 'yue')[0], 'ze6');
});

test('readings cover rare characters beyond the stroke data', () => {
  // 𠝹 (U+20779, CJK Extension B) and 㗎 (Extension A) have no stroke data but do have readings.
  assert.ok(Core.readingsIn(readings('𠝹'), 'yue').length > 0, '𠝹 has no Jyutping');
  assert.ok(Core.readingsIn(readings('㗎'), 'yue').length > 0, '㗎 has no Jyutping');
  const files = fs.readdirSync(path.join(ASSETS, 'training', 'readings'));
  const total = files.reduce((n, f) => n + Object.keys(JSON.parse(fs.readFileSync(path.join(ASSETS, 'training', 'readings', f), 'utf8'))).length, 0);
  assert.ok(total > 45000, `only ${total} characters have readings`);
});

test('shardsOf lists each reading file once', () => {
  assert.deepStrictEqual(Core.shardsOf('你你好'), [0x4f60 >> 10, 0x597d >> 10]);
  assert.deepStrictEqual(Core.shardsOf(''), []);
});

test('bundle holds the full stroke data set', () => {
  assert.ok(bundle.count > 9000, `only ${bundle.count} characters`);
  assert.strictEqual(bundle.get('你').strokes.length, 7);
});

test('English phrases are picked out of mixed input', () => {
  assert.deepStrictEqual(Core.englishPhrases('thank you, 早晨, good night'), ['thank you', 'good night']);
  assert.deepStrictEqual(Core.englishPhrases('你好 謝謝'), []);
  assert.deepStrictEqual(Core.englishTokens('to thank (formal)'), ['thank']);
});

test('pinyin with tone numbers becomes tone marks', () => {
  assert.strictEqual(Core.pinyinMarks('ni3 hao3'), 'nǐ hǎo');
  assert.strictEqual(Core.pinyinMarks('xie4 xie5'), 'xiè xie');
  assert.strictEqual(Core.pinyinMarks('lu:4'), 'lǜ');
  assert.strictEqual(Core.pinyinMarks('zhou1 gui4'), 'zhōu guì');
});

test('match score prefers exact meanings and hides Cantonese-only words in Mandarin', () => {
  const xiexie = ['謝謝', '谢谢', 'xie4 xie5', 'ze6 ze6', 'to thank; thanks; thank you', 0];
  const mgoi = ['唔該', '唔该', 'wu2 gai1', 'm4 goi1', '(verb) please; thanks (for services rendered)', 1];
  assert.strictEqual(Core.matchScore(xiexie, 'thank', 'yue'), 0);
  assert.strictEqual(Core.matchScore(xiexie, 'thank you', 'yue'), 1);
  assert.strictEqual(Core.matchScore(mgoi, 'please', 'cmn'), null);
  assert.ok(Core.matchScore(mgoi, 'please', 'yue') < 1);
  const slang = ['盛惠', '盛惠', 'sheng4 hui4', 'sing6 wai6', 'thank you (slang)', 1, 4];
  const doze = ['多謝', '多谢', 'duo1 xie4', 'do1 ze6', 'thank you (for a gift)', 1, 2];
  assert.ok(Core.matchScore(doze, 'thank you', 'yue') < Core.matchScore(xiexie, 'thank you', 'yue') + 0.5);
  assert.ok(Core.matchScore(xiexie, 'thank you', 'yue') < Core.matchScore(slang, 'thank you', 'yue'), 'slang outranks 謝謝');
  assert.strictEqual(Core.matchScore(xiexie, 'goodbye', 'yue'), null);
});

test('dictionary finds everyday words for common English', () => {
  const dir = path.join(ASSETS, 'training', 'dict');
  const meta = JSON.parse(fs.readFileSync(path.join(dir, 'meta.json'), 'utf8'));
  const top = (word, n) => JSON.parse(fs.readFileSync(path.join(dir, 'i' + word[0] + '.json'), 'utf8'))[word]
    .slice(0, n).map((i) => JSON.parse(fs.readFileSync(path.join(dir, 'e' + Math.floor(i / meta.chunk) + '.json'), 'utf8'))[i % meta.chunk][0]);
  assert.ok(top('thank', 3).includes('謝謝'));
  assert.ok(top('tea', 2).includes('茶'));
  assert.ok(top('water', 2).includes('水'));
  assert.ok(top('goodbye', 2).includes('再見'));
  assert.ok(meta.entries > 100000);
});

test('everyday spoken words come first for common phrases', () => {
  assert.deepStrictEqual(Core.favourites('good morning', 'yue').map((e) => e[0]), ['早晨']);
  assert.deepStrictEqual(Core.favourites('Thank you', 'yue').map((e) => e[0]), ['多謝', '唔該']);
  assert.deepStrictEqual(Core.favourites('to eat', 'cmn').map((e) => e[0]), ['吃']);
  assert.deepStrictEqual(Core.favourites('sesquipedalian', 'yue'), []);
});

test('worksheet: one page per word, stroke order rows, then trace, then blank rows to the bottom', () => {
  const xiexie = Core.wordLayout(['謝', '謝'], [17, 17], true);
  assert.strictEqual(xiexie.cols, 10, 'whole copies of a 2-character word fit 10 across');
  assert.deepStrictEqual(xiexie.rows.map((r) => r.kind).slice(0, 4), ['strokes', 'strokes', 'trace', 'blank']);
  assert.strictEqual(xiexie.rows[0].cells.length + xiexie.rows[1].cells.length, 17, '謝 once, all 17 strokes');
  assert.ok(xiexie.top + xiexie.rows.length * xiexie.cell <= Core.PAGE.H - 10, 'fits on the page');
  assert.ok(xiexie.left > 10 && xiexie.left + xiexie.cols * xiexie.cell < Core.PAGE.W - 10, 'centred with margins');

  const nihao = Core.wordLayout(['你', '好'], [7, 6], true);
  assert.deepStrictEqual(nihao.rows.slice(0, 3).map((r) => r.cells && r.cells.map((c) => c.ch + c.step).join(' ')),
    ['你1 你2 你3 你4 你5 你6 你7', '好1 好2 好3 好4 好5 好6', undefined]);

  const yi = Core.wordLayout(['一'], [1], true);
  assert.strictEqual(yi.cols, 11);
  assert.strictEqual(yi.rows.filter((r) => r.kind === 'blank').length, yi.rows.length - 2);

  const noStrokes = Core.wordLayout(['早', '晨'], [6, 11], false);
  assert.deepStrictEqual(noStrokes.rows.slice(0, 2).map((r) => r.kind), ['model', 'trace']);

  const unknown = Core.wordLayout(['𠝹'], [0], true);
  assert.deepStrictEqual(unknown.rows[0].cells, [{ ch: '𠝹', step: 0 }], 'no stroke data: shown whole');

  const long = Core.wordLayout(Array.from('龘龘龘龘龘龘'), Array(6).fill(48), true);
  assert.ok(long.rows.some((r) => r.kind === 'trace'), 'very long words still get a trace row');
});

test('practice words: runs of Chinese, no repeats, long runs split', () => {
  assert.deepStrictEqual(Core.practiceWords('你好 謝謝, 你好 thank you 早晨'), ['你好', '謝謝', '早晨']);
  assert.deepStrictEqual(Core.practiceWords('abc'), []);
  assert.strictEqual(Core.practiceWords('一二三四五六七八九十百千').length, 2);
});

test('large boxes (the default) match the K1 homework: about 27 mm, whole words per row, fit the page', () => {
  assert.strictEqual(Core.BOX_SIZES.large, 26.7);
  for (const [chars, strokes] of [[['謝', '謝'], [17, 17]], [['一'], [1]], [['你', '好'], [7, 6]], [['早', '晨'], [6, 11]]]) {
    const l = Core.wordLayout(chars, strokes, true, Core.BOX_SIZES.large);
    assert.strictEqual(l.cols % chars.length, 0, `${chars.join('')}: whole copies per row`);
    assert.ok(l.left >= Core.PAGE.SIDE - 0.01, `${chars.join('')}: inside the side margins`);
    assert.ok(l.top + l.rows.length * l.cell <= Core.PAGE.H - Core.PAGE.BOTTOM + 0.01, `${chars.join('')}: fits the page`);
    assert.ok(l.rows.some((r) => r.kind === 'trace') && l.rows.some((r) => r.kind === 'blank'), `${chars.join('')}: trace and blank rows`);
  }
  assert.strictEqual(Core.wordLayout(['謝', '謝'], [17, 17], true, Core.BOX_SIZES.large).cols, 6);
  assert.strictEqual(Core.wordLayout(['一'], [1], true, Core.BOX_SIZES.large).cols, 7);
});

// ---------------------------------------------------------------- Japanese and Korean

function topWords(dirName, word, n) {
  const dir = path.join(ASSETS, 'training', dirName);
  const meta = JSON.parse(fs.readFileSync(path.join(dir, 'meta.json'), 'utf8'));
  return JSON.parse(fs.readFileSync(path.join(dir, 'i' + word[0] + '.json'), 'utf8'))[word]
    .slice(0, n).map((i) => JSON.parse(fs.readFileSync(path.join(dir, 'e' + Math.floor(i / meta.chunk) + '.json'), 'utf8'))[i % meta.chunk][0]);
}

test('Japanese and Korean stroke data: their own bundles, in the same coordinates as the Chinese data', () => {
  const kanji = openBundle('kanji.bin');
  assert.ok(kanji.count > 7000);
  for (const ch of ['必', 'あ', 'ア', 'ん']) {
    const d = kanji.get(ch);
    assert.ok(d && d.strokes.length === d.medians.length, ch);
  }
  assert.strictEqual(kanji.get('あ').strokes.length, 3);
  assert.ok(openBundle('hanja.bin').get('學'));
  // Same box as hanzi-writer-data: x 0-1024, y -124-900.
  const [x0, x1] = Core.xBounds(kanji.get('日').strokes);
  const [c0, c1] = Core.xBounds(bundle.get('日').strokes);
  assert.ok(Math.abs(x0 - c0) < 40 && Math.abs(x1 - c1) < 40);
});

test('languages: which characters each practises, and the language of some text', () => {
  assert.deepStrictEqual(Core.practiceWords('食べる ありがとう, hello 学校', 'ja'), ['食べる', 'ありがとう', '学校']);
  assert.deepStrictEqual(Core.practiceWords('안녕하세요 學校 thanks', 'ko'), ['안녕하세요', '學校']);
  assert.deepStrictEqual(Core.practiceWords('你好 ありがとう 안녕', 'yue'), ['你好']);
  assert.strictEqual(Core.scriptLang('学校'), null);
  assert.strictEqual(Core.scriptLang('がっこう'), 'ja');
  assert.strictEqual(Core.scriptLang('학교'), 'ko');
  assert.deepStrictEqual(Core.englishPhrases('thank you ありがとう good night'), ['thank you', 'good night']);
});

test('readings: Jyutping | Pinyin | Japanese | Korean, and worked out for kana and hangul', () => {
  assert.deepStrictEqual(Core.readingsIn(readings('学'), 'ja'), ['ガク', 'まな(ぶ)']);
  assert.deepStrictEqual(Core.readingsIn(readings('學'), 'ko'), ['학']);
  assert.deepStrictEqual(Core.readingsIn(readings('學'), 'yue'), ['hok6']);
  assert.deepStrictEqual(Core.charReadings('が', undefined, 'ja'), ['ga']);
  assert.deepStrictEqual(Core.charReadings('학', undefined, 'ko'), ['hak']);
  assert.strictEqual(Core.toneLabel('ga', 'ja'), '');
});

test('kana to rōmaji (Hepburn)', () => {
  assert.strictEqual(Core.kanaToRomaji('がっこう'), 'gakkou');
  assert.strictEqual(Core.kanaToRomaji('キャンプ'), 'kyanpu');
  assert.strictEqual(Core.kanaToRomaji('ラーメン'), 'raamen');
  assert.strictEqual(Core.kanaToRomaji('ちょっと'), 'chotto');
  assert.strictEqual(Core.kanaToRomaji('しゃしん'), 'shashin');
  assert.strictEqual(Core.kanaToRomaji('ファン'), 'fan');
});

test('hangul: letters, romanization and strokes built from the letters', () => {
  assert.deepStrictEqual(Core.hangulParts('한'), [18, 0, 4]);
  assert.strictEqual(Core.romanizeHangul('학교'), 'hakgyo');
  assert.strictEqual(Core.romanizeHangul('안녕하세요'), 'annyeonghaseyo');
  // Stroke counts: ㅎ 3 + ㅏ 2 + ㄴ 1; ㄱ 1 + ㅘ (ㅗ 2 + ㅏ 2); ㄷ 2 + ㅏ 2 + ㄺ (ㄹ 3 + ㄱ 1); ㅇ on its own.
  assert.strictEqual(Core.hangulData('한').strokes.length, 6);
  assert.strictEqual(Core.hangulData('과').strokes.length, 5);
  assert.strictEqual(Core.hangulData('닭').strokes.length, 8);
  assert.strictEqual(Core.hangulData('ㅇ').strokes.length, 1);
  assert.strictEqual(Core.hangulData('A'), null);
  const d = Core.hangulData('한');
  assert.strictEqual(d.strokes.length, d.medians.length);
  for (const m of d.medians) {
    for (const [x, y] of m) assert.ok(x >= 0 && x <= 1024 && y >= -124 && y <= 900, `${x},${y} outside the box`);
  }
});

test('Japanese and Korean dictionaries find everyday words for common English', () => {
  assert.ok(topWords('dict-ja', 'school', 2).includes('学校'));
  assert.ok(topWords('dict-ja', 'water', 2).includes('水'));
  assert.ok(topWords('dict-ja', 'apple', 2).includes('りんご'));
  assert.ok(topWords('dict-ko', 'school', 2).includes('학교'));
  assert.ok(topWords('dict-ko', 'water', 2).includes('물'));
  assert.ok(topWords('dict-ko', 'tree', 2).includes('나무'));
  assert.deepStrictEqual(Core.favourites('thank you', 'ja').map((e) => e[0]), ['ありがとう', 'ありがとうございます']);
  assert.deepStrictEqual(Core.favourites('hello', 'ko').map((e) => e[0]), ['안녕하세요']);
  assert.strictEqual(Core.entryWord(['謝謝', '谢谢'], 'cmn'), '谢谢');
  assert.strictEqual(Core.entryWord(['学校', 'がっこう'], 'ja'), '学校');
});
