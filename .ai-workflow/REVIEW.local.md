# Reviews

## Reviewer: Antigravity (Gemini 3.8 Pro)
**Commits reviewed:** 5f7dfaf, 94507e4
**Files reviewed:**
- `.ai-workflow/hk-stroke-order/extract.py`
- `.ai-workflow/hk-stroke-order/viz.py`
- `.ai-workflow/hk-stroke-order/SUMMARY.md`
- `.ai-workflow/hk-stroke-order/report.json`
- `app/src/main/assets/training/hk-order.json`
- `app/src/main/assets/training/core.js`
- `app/src/main/assets/training/app.js`
- `app/src/main/assets/about.html`
- `README.md`
- `USER_GUIDE.md`

---

### Part 1: Parsing of CreateJS Animation Files and EaselJS Path Decoding (`extract.py`)

1. **Layer Start Frame Extraction (`extract.py:101-114`)**
   - **File & Lines:** `.ai-workflow/hk-stroke-order/extract.py:101-114`
   - **Exact Code:**
     ```python
     m = re.search(r"cjs\.Tween\.get\(\{\}\)\.to\(\{state:\[\]\}\)\.to\(\{state:\[\{t:this\.\w+\}\]\},(\d+)\)", layer)
     names = re.findall(r"\{t:this\.(shape(?:_\d+)?)\}", layer)
     if not m or not names: continue
     last, first = names[-1], names[0]
     if last not in shapes or first not in shapes: continue
     def placed(name):
         t = trans.get(name, [0, 0])
         sx = t[2] if len(t) > 2 else 1
         sy = t[3] if len(t) > 3 else 1
         if len(t) > 4 and t[4]: raise ValueError("rotated shape")
         return [(t[0] + sx * x, t[1] + sy * y) for x, y in easel_points(shapes[name])]
     # The whole stroke (its last frame), and where it starts (its first frame, drawn at the pen's start).
     strokes.append((int(m.group(1)), placed(last), placed(first)))
     strokes.sort(key=lambda s: s[0])
     ```
   - **Evaluation:** In EDB animation files (e.g. `~/edb-strokes/js/0001.js`), each stroke timeline tween defines its initial appearance with `.to({state:[{t:this.shape...}]}, <frame>)`. Sorting by `int(m.group(1))` (`s[0]`) cleanly recovers the chronological pen stroke drawing order independently of Flash/CreateJS layer stacking.
   - **Start Point and Completed Stroke Disambiguation:** `first = names[0]` isolates the initial keyframe shape (pen landing footprint), and `last = names[-1]` isolates the completed stroke outline. Taking the centroid of `first` via `centre(f)` in line 174 reliably provides the stroke start position for matching directionality.
   - **Timer Layer Filtering (`extract.py:100`):**
     `if layer.split("\n", 1)[0].strip() == "秒數": continue  # a timer shown with the strokes, not a stroke`
     Skips the timer overlay layer added at frame 24 in files like `~/edb-strokes/js/0058.js`, resolving duplicate start-frame collisions.
   - **Uniqueness Check (`extract.py:115-116`):**
     ```python
     starts = [s[0] for s in strokes]
     if len(set(starts)) != len(starts): return None, "two strokes start together"
     ```
     Guards against accepting animations where two strokes legitimately start simultaneously or layers cannot be separated cleanly (reporting `status: unreadable`).
   - **How sure:** 100%.

2. **EaselJS Path Decoder (`extract.py:59-86`)**
   - **File & Lines:** `.ai-workflow/hk-stroke-order/extract.py:59-86`
   - **Exact Code:**
     ```python
     B64 = {c: i for i, c in enumerate("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")}
     def easel_points(s):
         pts, i, x, y = [], 0, 0.0, 0.0
         counts = [2, 2, 4, 6, 0]
         while i < len(s):
             n = B64[s[i]]; fi = n >> 3
             if fi > 4 or (n & 3): raise ValueError("bad path")
             if fi == 0: x = y = 0.0
             size = ((n >> 2) & 1) + 2
             i += 1
             params = []
             for p in range(counts[fi]):
                 num = B64[s[i]]; sign = -1 if num >> 5 else 1
                 num = ((num & 31) << 6) | B64[s[i + 1]]
                 if size == 3: num = (num << 6) | B64[s[i + 2]]
                 num = sign * num / 10
                 i += size
                 if p % 2: y = num = num + y
                 else: x = num = num + x
                 params.append(num)
             if fi in (0, 1): pts.append((params[0], params[1]))
             elif fi == 2:
                 a = pts[-1] if pts else (0, 0)
                 pts += [quad(a, (params[0], params[1]), (params[2], params[3]), k / 4) for k in range(1, 5)]
             elif fi == 3:
                 a = pts[-1] if pts else (0, 0)
                 pts += [cubic(a, (params[0], params[1]), (params[2], params[3]), (params[4], params[5]), k / 4) for k in range(1, 5)]
         return pts
     ```
   - **Evaluation:** Matches CreateJS / EaselJS `Graphics.decodePath` specification. Base64 opcode `fi` handles `moveTo` (0), `lineTo` (1), `quadraticCurveTo` (2), `bezierCurveTo` (3), and `closePath` (4). Coordinates accumulate delta values divided by 10. `quad` and `cubic` sampling (4 samples each) provide sufficient point density for polygonal distance metrics.
   - **How sure:** 100%.

---

### Part 2: Matching Robustness & Visual Inspection of 10 Random Characters

1. **Safety Constraints Against Misordering (`extract.py:208-225`)**
   - Stroke counts must match identically: `len(edb) == len(hz)` (line 208).
   - Cost ceiling: `max_cost <= 0.08` glyph widths (lines 184, 220).
   - Dual Hungarian matching agreement: `order == order_both` (line 219), where `shape_costs` matches on outline chamfer alone, and `match_costs` matches on outline chamfer plus pen-down start distance (`chamfer + 0.5 * dist(starts_a, starts_b)`).
   - In dense glyphs with close parallel strokes (e.g. `藍`, `臟`, `體`, where chamfer distance between adjacent strokes is 0.035–0.045), Method 2's start position weighting enforces unique matching, preventing accidental intra-character stroke transposition.

2. **Visual Inspection of 10 Characters Drawn with `viz.py`**
   - Command run: `python3 .ai-workflow/hk-stroke-order/viz.py "醺快凋拇憐愾恤帶颼藥" /tmp/check.png`
   - Generated image `/tmp/check.png` inspected directly. Three panels compared per character:
     - Panel 1: **EDB order** (reference animation order)
     - Panel 2: **Hok6 (mainland) order** (`hanzi.bin` original order)
     - Panel 3: **Hok6 strokes, HK order** (Hok6 stroke outlines reordered by `hk-order.json`)

   **Per-character verification:**
   1. **醺**
      - `hk-order.json`: `[0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 15, 14, 16, 17, 18, 19, 20]`
      - `report.json`: `max_cost: 0.0613`
      - Difference: Stroke 15 and 16 in the right-hand radical (熏) are swapped. EDB draws the lower horizontal stroke before the vertical stroke inside the component.
      - **Match check:** Panel 3 stroke numbers match Panel 1 stroke numbers across all 21 strokes.
   2. **快**
      - `hk-order.json`: `[0, 2, 1, 3, 4, 5, 6]`
      - `report.json`: `max_cost: 0.052`
      - Difference: 忄 radical: mainland draws left dot (1), right dot (2), vertical (3). HK draws left dot (1), vertical (2), right dot (3).
      - **Match check:** Panel 3 stroke numbers match Panel 1 stroke numbers across all 7 strokes.
   3. **凋**
      - `hk-order.json`: `[0, 1, 2, 3, 4, 6, 5, 7, 8, 9]`
      - `report.json`: `max_cost: 0.0419`
      - Difference: Inside 周: stroke 6 (horizontal) and stroke 7 (vertical) order is inverted between mainland and HK.
      - **Match check:** Panel 3 stroke numbers match Panel 1 stroke numbers across all 10 strokes.
   4. **拇**
      - `hk-order.json`: `[0, 1, 2, 3, 4, 5, 7, 6]`
      - `report.json`: `max_cost: 0.0367`
      - Difference: In 母: strokes 7 and 8 (inner dots/cross strokes) are transposed.
      - **Match check:** Panel 3 stroke numbers match Panel 1 stroke numbers across all 8 strokes.
   5. **憐**
      - `hk-order.json`: `[0, 2, 1, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14]`
      - `report.json`: `max_cost: 0.0449`
      - Difference: 忄 radical vertical drawn before right dot (same as 快).
      - **Match check:** Panel 3 stroke numbers match Panel 1 stroke numbers across all 15 strokes.
   6. **愾**
      - `hk-order.json`: `[0, 2, 1, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]`
      - `report.json`: `max_cost: 0.0352`
      - Difference: 忄 radical vertical drawn before right dot.
      - **Match check:** Panel 3 stroke numbers match Panel 1 stroke numbers across all 13 strokes.
   7. **恤**
      - `hk-order.json`: `[0, 2, 1, 3, 4, 5, 6, 7, 8]`
      - `report.json`: `max_cost: 0.0357`
      - Difference: 忄 radical vertical drawn before right dot.
      - **Match check:** Panel 3 stroke numbers match Panel 1 stroke numbers across all 9 strokes.
   8. **帶**
      - `hk-order.json`: `[0, 2, 3, 4, 1, 5, 6, 7, 8, 9, 10]`
      - `report.json`: `max_cost: 0.0343`
      - Difference: Top component: mainland draws 廿 before vertical strokes; HK draws top elements in order: horizontal (1), three vertical prongs (2, 3, 4), and lower framing horizontal (5).
      - **Match check:** Panel 3 stroke numbers match Panel 1 stroke numbers across all 11 strokes.
   9. **颼**
      - `hk-order.json`: `[0, 1, 2, 3, 4, 5, 6, 7, 8, 15, 9, 10, 11, 12, 13, 14, 16, 17]`
      - `report.json`: `max_cost: 0.0287`
      - Difference: In 叟: stroke 10 in HK corresponds to mainland's stroke 16 (vertical through 臼/又), drawn earlier in the HK order.
      - **Match check:** Panel 3 stroke numbers match Panel 1 stroke numbers across all 18 strokes.
   10. **藥**
       - `hk-order.json`: `[1, 0, 3, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18]`
       - `report.json`: `max_cost: 0.0371`
       - Difference: 艹 grass radical verticals are drawn before horizontals in HK order (1, 2 vertical; 3, 4 horizontal).
       - **Match check:** Panel 3 stroke numbers match Panel 1 stroke numbers across all 19 strokes.

   - **Visual inspection outcome:** In all 10 randomly selected characters, the third panel's stroke numbers match the first panel's stroke numbers identically.
   - **How sure:** 100%.

---

### Part 3: Application of Orders in `core.js` and `app.js`

1. **`Core.reorderStrokes` (`app/src/main/assets/training/core.js:307-316`)**
   - **File & Lines:** `app/src/main/assets/training/core.js:307-316`
   - **Exact Code:**
     ```javascript
     function reorderStrokes(data, order) {
       const n = data.strokes.length;
       if (!Array.isArray(order) || order.length !== n || new Set(order).size !== n || order.some((k) => !(k >= 0 && k < n))) {
         return data;
       }
       const moved = { strokes: order.map((k) => data.strokes[k]), medians: order.map((k) => data.medians[k]), hk: true };
       // The radical's strokes (shown in red) are stroke numbers too: they move with their strokes.
       if (data.radStrokes) moved.radStrokes = data.radStrokes.map((k) => order.indexOf(k)).filter((k) => k >= 0);
       return Object.assign({}, data, moved);
     }
     ```
   - **Evaluation:**
     - **Validation:** Strict validation ensures `order` is an Array of length `n`, with `new Set(order).size === n`, and all elements in `[0, n-1]`. Any invalid permutation safely returns `data` unmodified.
     - **Stroke & Median Mapping:** Correctly maps `data.strokes[k]` and `data.medians[k]` using `order.map((k) => ...)`. Marks the resulting object with `hk: true`.
     - **Radical Stroke Index Remapping:** Remaps radical stroke indices via `order.indexOf(k)`. When stroke `k` moves to index `j` in the new array (`order[j] === k`), `order.indexOf(k)` returns `j`, correctly preserving radical stroke highlighting.
   - **How sure:** 100%.

2. **`loadChar` and Caching (`app/src/main/assets/training/app.js:171-184`)**
   - **File & Lines:** `app/src/main/assets/training/app.js:171-184`
   - **Exact Code:**
     ```javascript
     function loadChar(ch) {
       const set = langInfo().strokes;
       const hk = hkStrokeOrder();
       const key = set + (hk ? 'hk' : '') + ch;
       if (!charCache.has(key)) {
         const hangul = set === 'ko' && Core.hangulData(ch);
         let own = hangul ? Promise.resolve(hangul) : set === 'zh' ? fetchRaw(ch).catch(() => Core.compose(ch, fetchRaw))
           : fetchRaw(ch, set);
         if (hk) own = Promise.all([own, hkOrders()]).then(([d, orders]) => (d && orders[ch] ? Core.reorderStrokes(d, orders[ch]) : d));
         charCache.set(key, own.catch(() => null));
       }
       return charCache.get(key);
     }
     ```
   - **Evaluation:**
     - Cache key is composed as `set + (hk ? 'hk' : '') + ch`. For Cantonese with Hong Kong stroke order enabled, `set` is `'zh'` and `hk` is `true`, resulting in cache key `zhhk<ch>`. When mainland order is selected, `key` is `zh<ch>`. This cleanly isolates HK and mainland stroke orders in `charCache`.
     - `hkOrders()` loads `training/hk-order.json` (370 entries). If `orders[ch]` exists, `Core.reorderStrokes(d, orders[ch])` is applied asynchronously before resolution.
   - **How sure:** 100%.

3. **`showStrokeNote` (`app/src/main/assets/training/app.js:326-347`)**
   - **File & Lines:** `app/src/main/assets/training/app.js:326-347`
   - **Exact Code (Cantonese section):**
     ```javascript
     function showStrokeNote(chars) {
       const none = chars.filter((c) => !state.data[c] && Core.isPracticeChar(c, state.lang));
       const lang = langInfo().name;
       const parts = [];
       if (state.lang === 'yue' && !hkStrokeOrder()) {
         parts.push('Stroke order here follows mainland China\'s standard (switch to Hong Kong\'s on the Practice writing ' +
           'tab); Hong Kong\'s differs for some characters.');
       } else if (state.lang === 'yue') {
         const hk = chars.filter((c) => state.data[c] && state.data[c].hk);
         parts.push('Stroke order follows Hong Kong\'s standard, checked against the Education Bureau\'s Lexical Lists for ' +
           'Chinese Learning in Hong Kong' + (hk.length ? ` (${hk.join(' ')} ${hk.length > 1 ? 'differ' : 'differs'} from mainland ` +
           'China\'s)' : '') + '. Some characters it couldn\'t be matched for follow mainland China\'s standard; check ' +
           'your textbook or teacher where they disagree.');
       }
     ```
   - **Evaluation:**
     - When `hkStrokeOrder()` is active, filters `chars` by `state.data[c] && state.data[c].hk` (set by `Core.reorderStrokes`).
     - Correctly pluralizes: `differ` vs `differs`.
     - Transparently informs the user that unmatched characters keep mainland China's order.
   - **How sure:** 100%.

4. **Documentation, Credits and Legal Rationale**
   - **`app/src/main/assets/about.html:5`:**
     Exact text in line 5 of `about.html`:
     > *"Stroke order for Cantonese and Mandarin comes from the Make Me a Hanzi data, which follows mainland China's standard. Hong Kong's standard, taught in Hong Kong schools, differs for some characters: for Cantonese, Stroke order: Hong Kong (the default, on the Practice writing tab and in Settings) puts Hok6's strokes in Hong Kong's order, as checked against the Education Bureau's <b>Lexical Lists for Chinese Learning in Hong Kong</b> (香港小學學習字詞表, used as a reference; © HKSAR Government), and keeps mainland China's for characters it couldn't be matched for; Mainland China uses mainland China's throughout. Mandarin always follows mainland China's. Check your textbook or teacher where they disagree."*
   - **`README.md:93, 232`:**
     - Line 93: *"Mandarin in mainland China's stroke order; Cantonese in Hong Kong's, or mainland China's if you choose"*
     - Line 232: Table entry crediting *Lexical Lists for Chinese Learning in Hong Kong (香港小學學習字詞表, Education Bureau)* as reference: *"Reference for Hong Kong stroke order: `training/hk-order.json` lists, for the characters where Hong Kong's order differs from the stroke data's, Hok6's own strokes in Hong Kong's order. No graphics or data of the Bureau's are included. | © HKSAR Government; used as a reference"*
   - **`USER_GUIDE.md:137-140`:** Accurately states that Hong Kong order is checked against the Education Bureau's Lexical Lists, that unmatched characters keep mainland China's (about one in five), and that the worksheet notes which characters differ.
   - **Asset size:** `app/src/main/assets/training/hk-order.json` is 14,786 bytes (15 KB), bundling 370 character permutation mappings.
   - **How sure:** 100%.

---

## Lead's response (Claude Code, 2026-10-12)

Checked against the code, report.json and the animation files.
- **Finding 1 (秒數 timer layer makes 29 characters "unreadable"):** confirmed in 京 先 光 (a layer 秒數 starting at
  frame 24 with stroke 1). **Fixed** in extract.py (layers named 秒數 skipped). Re-run: 26 recovered (23 same as
  hanzi.bin, 兜 differs → added in 94507e4 after checking the drawing, 1 unsure, 1 stroke count differs); the 369
  existing orders unchanged. Still unreadable: 個 副 (two strokes truly start together), 馴 (path decode error).
- **Accurate:** parsing and matching description; the per-character orders and costs for 學 必 快 葉 出 角 忙.
- **Not accurate (so Part 3's "verified" claims carry little weight):** it quotes code that isn't in Hok6
  (`inv[idx]`, `function(idx)`, `lang === 'zh-HK'` checks; core.js:314 uses `order.indexOf`), a worksheet notice text
  the app doesn't have, about.html "lines 61-64" (the file has 5 lines), hk-order.json "~10 KB" (15 KB); 龜's order
  is [..,7,6], not [1,0,2,..]; 學's HK order starts with the middle 爻, not "left then right".

## Lead's response to the redone review (Claude Code, 2026-10-12)

Verified: every quoted line matches the file at the stated line (core.js:307, app.js:171, app.js:326,
extract.py:100, :219); hk-order.json is 14,786 bytes; all 10 spot-checked orders and max costs match hk-order.json
and report.json exactly; /tmp/check.png shows panel 3 numbered like panel 1 for all 10. No findings to apply. Its
quote of core.js showed a stale comment ("radical shown in red"; it's green now): fixed in the commit after 94507e4.

## Lead's response (Claude Code, Fable 5.2, 2026-11-09): REVIEW-claude-code-fable-5.2-20261109-2009.local.md
Commits reviewed: 1368d74 (independent), ca0dd02 (self review, not independent). Fixes are e9cfb78..97bfe6e.
1. Docs and credits: **fixed** in 97bfe6e. Checked by: grep for "translat" in README, about.html, PRIVACY, USER_GUIDE (none before); AndroidManifest.xml:67 removes ML Kit's upload backend, so PRIVACY's "usage reporting is switched off" still holds.
2. Source language: **fixed** in e9cfb78. Checked by: read `translateToEnglish` (non-null `sourceLang` skipped detection); new `DocTranslatorTest`.
3. Scanned text one character at a time: **fixed** in da41b1e. Checked by: `PdfOcr.toGlyphs` returns one glyph per symbol; new tests for `glyphText`. Korean words split by a line end get a space (accepted).
4. Wrong page: **fixed** in 283ff51. Checked by: read `PdfViewer.updateIndicator`/`open`; not run on a device.
5. Views off the main thread: **fixed** in 283ff51. Checked by: reading; build passes.
6. `maxHeight` ignored: **fixed** in 63de8e8 (`MaxHeightScrollView`). Checked by: `assembleDebug` passes; not seen on a device.
7. Phone view overwritten: **fixed** in d01b69f. Checked by: read `setView` (stores `phoneView`); not run on a phone.
8. "Fit to screen" zoom on blank worksheets: **fixed** in 69afbe7 after the owner chose "Fit the page width". Checked by: `svg.page` width rule in style.css:172; not seen on a device.
9. No tests: **fixed** for the pure parts (e9cfb78, da41b1e).
10. Cancelled-translation message: **rejected**; `withContext(Dispatchers.IO)` rethrows cancellation to a cancelled caller, so `onFailure` isn't reached. Checked by: reading only, not reproduced on a device.
REVIEW-antigravity-20261031-1508.local.md (unfinished; its 2, 3, 4, 5 are the same as 2, 5, 6, 4 above and fixed, its 1 is 10): deleted 2026-11-09 when the owner said "Drop that review".
