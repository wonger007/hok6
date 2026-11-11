# Decisions (append-only; newest first)
Lesson export: on

## Active decisions
- 2026-11-11: Every release goes to Google Play and GitHub: the bundle to Play, the commits and tag pushed, and a GitHub release with the APKs, together.
- 2026-10-21: On-device English translation via ML Kit for PDF and Word docs: ML Kit on device, no cloud translation.
- 2026-10-18: Always publish to Google Play when cutting a git release: every git release is built and uploaded. [Extended by 2026-11-11.]
- 2026-10-12: Cantonese keeps mainland stroke order, said plainly: mainland order, stated in the app and docs. [FLAGGED 2026-11-08: code no longer matches; Hong Kong's order via hk-order.json is now the default, mainland a choice. Owner to confirm superseding.]
- 2026-10-12: Cantonese is traditional; simplified is an option: simplified only with the Settings toggle; Mandarin takes both.
- 2026-10-12: One language and English on each screen: a character appears only with its own language and English.
- 2026-10-12: Stroke order never mixes languages: each language uses only its own stroke data, no fallback.
- 2026-10-11: Agent ignore list lives in .gitignore: committed list, overriding the global rule for this project. [FLAGGED 2026-11-08: says .git/info/exclude holds only *.log; setup.sh now also keeps workflow and agent entries there.]
- 2026-10-11: Agent workflow files stay out of the repo: notes stay local, nothing workflow-related is committed.
- 2026-10-11: No data collection: no ads, accounts, tracking or analytics.
- 2026-10-11: All user data stays on the device: local data, synced only to the backup file in the book folder.
- 2026-10-11: Language-agnostic wording and testing: UI never says "Chinese"; tests cover all four languages.

## 2026-11-11: Every release goes to Google Play and GitHub
- **Status:** Active (extends 2026-10-18: Always publish to Google Play when cutting a git release)
- **Decision:** When a release is published, it goes to both places in the same task: the bundle to Google Play (closed testing, track alpha, and internal) the release commit and its `vX.Y` tag to GitHub (`main`), and a GitHub release for that tag with the arm64-v8a and armeabi-v7a APKs (notes in `build/release/notes-X.Y.md`, published with `.ai-workflow/gh_release.py`, which takes the token from git's credential helper), without waiting to be asked for each one.
- **Why:** The owner, 2026-11-11, after 1.17 went to Play one day and GitHub the next: "always push to appstore and github".
- **Confirmed by the owner 2026-11-11:** "Yes, publish it" (a GitHub release with APKs for 1.17) and "make sure we push both places and publish apk as well".
- **Rejected:** publishing to Play while the commits stay local.

## 2026-10-21: On-device English translation via ML Kit for PDF and Word docs
- **Status:** Active
- **Decision:** Use Google ML Kit on-device translation (`com.google.mlkit:translate:17.0.3`) for translating document instructions and text to English in `ChapterActivity`.
- **Why:** Maintains strict offline and privacy invariants (zero accounts, telemetry, or third-party servers) in compliance with Google Play Families Policy.
- **Rejected:** Cloud translation APIs (e.g. Google Cloud Translate, DeepL API) which send textbook content off-device.

## 2026-10-18: Always publish to Google Play when cutting a git release
- **Status:** Active
- **Decision:** Whenever a new release is cut in git (bumping versionCode and versionName in `app/build.gradle.kts`, tagging the release commit), always build the release bundle (`./gradlew bundleRelease`) and publish it to Google Play using `/root/.venvs/hok6-play/bin/python3 tools/play_upload.py`.
- **Why:** The owner: "Always publish when we cut a new release for git". Keeps Google Play releases in lockstep with git releases.
- **Rejected:** Cutting git release tags without publishing to Google Play; separate disconnected upload steps.

## 2026-10-12: Cantonese keeps mainland stroke order, said plainly
- **Status:** Active
- **Decision:** Cantonese uses hanzi.bin (Make Me a Hanzi, mainland PRC order). Cantonese worksheets (#strokeNote),
  the About page, README and User Guide say so and that Hong Kong's order differs for some characters.
- **Why:** No openly licensed Hong Kong stroke data exists (searched by two builders; see HANDOFF note); the owner
  chose this over asking the EDB for permission or hand-made HK overrides.
- **Rejected (for now):** EDB permission request; hand-made HK override set; Taiwan CNS 11643 order (not HK's).

## 2026-10-12: Cantonese is traditional; simplified is an option
- **Status:** Active
- **Decision:** Cantonese takes traditional characters; simplified-only ones (training/simplified.txt: in a
  simplified CC-CEDICT headword where the traditional differs, in no traditional headword) are left out of typing,
  Draw and the Practice Selector unless Settings → Languages → "Simplified characters too" is ticked. Mandarin
  takes both.
- **Why:** The owner asked for simplified Chinese as an option for Cantonese.
- **Rejected:** Accepting any character with a Jyutping reading (Unihan gives simplified ones Jyutping too).

## 2026-10-12: One language and English on each screen
- **Status:** Active
- **Decision:** Wherever a language's characters are shown, only that language and English appear. Language names
  are English everywhere (switches, Settings, voices). History, Bookmarks, the quiz and the English look-up show
  only the current language (quiz words remember the language they were added in: quizLangs, also written by the
  app's Add to quiz). The worksheet switch leaves out words with no form in the new language (counted in a message,
  never shown) and converts from the worksheet's original words, so switching back restores them. Grid styles are
  named in English. On phones the worksheet's language switch is in the ⋮ menu. Words typed or picked on a page
  that aren't in the language (a Han character with no reading in it) are left out, counted in a message.
  Recognition is in the practised language only (Draw candidates, Practice Selector list). Scanned pages are read by
  all recognisers only to tell the page's language; another language's page is refused, never offered (owner chose
  this over misreads from the practised language's recogniser).
- **Why:** The owner: "when displaying a korean character you should not have other languages other than english and
  korean... same with chinese and japanese... english is the common language".
- **Rejected:** Extra-language rows in the English look-up (the + toggles, removed); mixed History/Quiz lists with
  per-language filters (removed); short or native-script language labels.

## 2026-10-12: Stroke order never mixes languages
- **Status:** Active
- **Decision:** Each language's stroke order comes only from its own data: Japanese from kanji.bin, Korean from
  hanja.bin (+ hangul built from letters), Cantonese and Mandarin from hanzi.bin (+ assembled ≈ characters). No
  fallback to another language's data and no option to borrow; a character without data has no stroke order, and
  the worksheet's notice (strokeNote) says so and suggests alternatives (textbook, teacher, dictionary, turning off
  Stroke order).
- **Why:** The owner: "NEVER mix", "keep it strict korean to korean, cantonese to cantonese, mandarin to mandarin,
  japanese to japanese". Another language's order can differ and would teach the wrong order.
- **Rejected:** Chinese fallback for Japanese/Korean (the 1.15 behaviour); Korean → Japanese → Mandarin fallback with
  an opt-out button (built, then dropped). Cost: ~83% of Korean-dictionary hanja (2,446 of 2,962) have no stroke order.

## 2026-10-11: Agent ignore list lives in .gitignore
- **Status:** Active
- **Decision:** The full list of agent and builder files (instructions, context files, caches) is in the committed
  `.gitignore`, the only place the repo mentions builders. `.git/info/exclude` holds only `*.log`. The local notes (`*.local.md`) live in the ignored `.ai-workflow/` folder.
- **Why:** The owner wants it to follow every clone to a new PC without setup; `.git/info/exclude` can't be committed.
  This deliberately overrides the global workflow's "no agent entries in .gitignore" rule for this project.
- **Rejected:** A list in ai-workflow pointed to by git's global ignore setting (needs setup on each PC).

## 2026-10-11: Agent workflow files stay out of the repo
- **Status:** Active (agent cache entries moved: see "Agent ignore list lives in .gitignore")
- **Decision:** The repo holds only the project (app, build setup, tests, tools, docs). Agent notes live in
  `AGENTS.local.md`, `HANDOFF.local.md` and `DECISIONS.local.md`; agent cache folders and `*.log` are in
  `.git/info/exclude`.
- **Why:** The owner wants nothing workflow-related committed, matching the global agent workflow.
- **Rejected:** Committed `AGENTS.md`/`CLAUDE.md`/`HANDOFF.md`/`DECISIONS.md` (added in 6625c0b, removed after).

<!-- The entries below were seeded on 2026-10-11 from existing rules; original decision dates are unknown. -->

## 2026-10-11: No data collection
- **Status:** Active
- **Decision:** Zero ads, accounts, tracking or analytics; keep `PRIVACY.md` accurate.
- **Why:** Google Play Families Policy compliance.
- **Rejected:** Not recorded.

## 2026-10-11: All user data stays on the device
- **Status:** Active
- **Decision:** User data is saved locally and synced to `Hok6 backup.json` in the user's book folder; no external
  servers or cloud accounts.
- **Why:** Follows from the no-data-collection rule.
- **Rejected:** Not recorded.

## 2026-10-11: Language-agnostic wording and testing
- **Status:** Active
- **Decision:** User-facing text never says "Chinese"; every language feature or test covers Japanese and Korean as
  well as Cantonese and Mandarin.
- **Why:** The app supports `yue`, `cmn`, `ja` and `ko` equally.
- **Rejected:** Not recorded.
