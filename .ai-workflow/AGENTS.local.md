# Hok6 (my notes)

## Overview
- **Purpose:** "Hok6: Trace, Write and Learn" is an Android tablet and phone app for working through textbooks
  (PDF, Word, audio): write and trace on pages, play audio, and practice stroke order, worksheets and quizzes for
  character-based languages.
- **Users and consumers:** language learners of all ages, children included (Play Families Policy), on Android 8.0+
  tablets and phones, working from their own textbook files; testers join through Google Play's closed test (`TESTING.md`).
- **Packages:** release `com.wonger.hok6` ("Hok6"); debug `com.wonger.hok6.debug` ("Hok6 (test)"); Kotlin code
  package `com.studybook.reader`.
- **Languages:** `yue` (Cantonese), `cmn` (Mandarin), `ja` (Japanese), `ko` (Korean); profiles in `Core.LANGS`
  (`app/src/main/assets/training/core.js`). Focus is square-cell scripts with stroke order (characters, kana, hangul).

## Protected folders
<!-- Folders agents must not read: literal paths relative to the project root, one per entry, as "- `path/`" with
an optional reason after it, or "- None". setup.sh fills this in from --protect and never lists files in these
folders. The rule is in global agents.md, "Protected folders". Keep this section. -->
- None

## Team's agent files
<!-- Listed by setup.sh. Read them, follow them for anything committed, never edit them. -->
- None (solo project, so no Team rules summary or Enhancements).

## Architecture
- **Android (Kotlin), `app/src/main/java/com/studybook/reader/`:**
  - `MainActivity.kt`: book cards, folder navigation, backup and restore
  - `ChapterActivity.kt`: PDF and Word reader with ink tracing layer and audio playlist
  - `TrainingActivity.kt`: WebView host for Practice & Quiz (`assets/training/`) and native bridges
  - `AboutActivity.kt`, `SetupActivity.kt`: About page, updates, language/download setup
  - `Ink.kt`, `PageDrawing.kt`: touch/stylus ink, stroke rendering, PDF export with tracing
  - `PdfText.kt`, `PdfOcr.kt`: text extraction and on-device OCR
  - `DocTranslator.kt`: on-device ML Kit translation of PDF/Word text to English (models download on demand)
  - `PdfViewer.kt`, `DocxReader.kt`, `DocxViewer.kt`, `ZoomPanView.kt`, `MaxHeightScrollView.kt`: page rendering and zoom/pan
  - `Handwriting.kt`, `Downloads.kt`: ML Kit handwriting models and TTS voice engines
  - `SystemBars.kt`: edge-to-edge system bar insets for Android 15+
- **Practice & Quiz (HTML/JS), `app/src/main/assets/training/`:** `index.html`, `app.js`, `style.css` (UI);
  `core.js` (stroke order, decomposition, readings, offline dictionary lookup); `readings/` (sharded by codepoint);
  `dict*/` (offline dictionary shards); `hk-order.json` (Hong Kong stroke reorderings for Cantonese). Stroke bundles `hanzi.bin`, `kanji.bin`, `hanja.bin` are in `app/src/main/assets/`.
- **Tests, `app/src/test/`:** Kotlin unit tests in `java/com/studybook/reader/`; Node tests for `core.js` in `js/`.
- **Data:** all user data stays on the device, synced to `Hok6 backup.json` in the user's book folder. No servers or accounts.

## Commands
- Verify: `tools/run_tests.sh`
- Lint: none configured beyond the default Android Lint (`./gradlew lint`), which isn't part of the routine.
- Test: `tools/run_tests.sh` (Kotlin unit tests + JS tests; no device needed)
- Build debug: `./gradlew assembleDebug`
- Build release APKs: `./gradlew assembleRelease` (one per CPU in `app/build/outputs/apk/release/`)
- GitHub release: `./gradlew assembleRelease` (its own gradle run), copy the APKs to `build/release/Hok6-X.Y-<abi>.apk`, then `python3 .ai-workflow/gh_release.py wonger007/hok6 vX.Y "Hok6 X.Y" build/release/notes-X.Y.md <arm64 apk> <armeabi-v7a apk>` (no `gh` here).
- Play bundle: `./gradlew bundleRelease`; upload with `/root/.venvs/hok6-play/bin/python3 tools/play_upload.py`
- Device checks: `python3 tools/device_check.py`
- Regenerate stroke/reading data: `python3 tools/build_assets.py`
- WSL reaches devices via `tools/adbw` (plain `adb` isn't on the WSL PATH).
- JDK 17, Android SDK 36 (compileSdk/targetSdk 36).
- One at a time: `tools/device_check.py` and device installs (one connected device and debug app; fixed DevTools forward port).

## Conventions
- Idiomatic Kotlin; vanilla ES2020+ JavaScript, pure functions tested in `core.test.js`.
- Commit messages: plain sentences matching git history.
- Docs (`USER_GUIDE.md`, `README.md`, `PRIVACY.md`) describe the code as it is; verify labels and behavior against the source.

## Invariants
- Every release goes to both Google Play and GitHub (commits and tag pushed, and a GitHub release with the APKs) in the same task (decisions 2026-10-18, 2026-11-11).
- User-facing text never says "Chinese": use "word", "character", "the language you're learning", "voices".
- Any language test or feature check covers Japanese and Korean as well as Cantonese and Mandarin.
- Credit every new library or data source in README Credits and `app/src/main/assets/about.html`.
- `/test/` is the owner's real book: git-ignored; never commit it or put sample files there.
- No data collection (Play Families Policy): no ads, accounts, tracking or analytics; keep `PRIVACY.md` accurate.
- Upload keystore, `~/.gradle/gradle.properties` passwords and Play service account keys never go in the repo.
- Never mix languages on screen: a character is shown only with its own language and English (the common language); language names are in English (decision 2026-10-12).
- Stroke order never mixes languages: each language uses only its own stroke data, never another's (decision 2026-10-12).
- Devices: always upgrade in place (`tools/adbw install -r`), never uninstall; test only with the debug app.

## Non-goals
- No servers, accounts, cloud sync, ads, analytics or tracking (see Invariants).
- No bundled textbook content: users bring their own files.

## Roadmap
- **Done:** 1.17 (versionCode 19, tag v1.17, on Play closed and internal testing 2026-11-10; pushed to GitHub and released there with APKs 2026-11-11): Translate to English (on-device ML Kit), pen tools in a side bar, one-tap pen after erasing, blank worksheet zoom and Fit to screen. Before it: 1.16 (versionCode 18).
- **Current:** Google Play closed test (track: alpha).
- **Next:** not recorded; ask the owner.

## Quality standards
- Android 8.0+ (minSdk 26), targetSdk/compileSdk 36; edge-to-edge on Android 15+.
- Tested on Galaxy Tab S7 FE (Android 14, tablet), Razer Edge (Android 12, phone layout under 600dp) and
  Galaxy S25 Ultra (Android 17, edge-to-edge); keep README's Tested devices table accurate.
- Works offline after the first downloads (handwriting models, voices); the dictionary is built in.

## Safety
- Never commit credentials, secrets or real user data.
