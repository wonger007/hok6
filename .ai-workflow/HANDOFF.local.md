# Handoff (keep under ~50 lines; rewrite, don't append)

## Current lead
Claude Code (lead since 2026-11-08; this conversation continued it 2026-11-09)

## Current task
None open. 1.17 (versionCode 19, 7594a2c, tag v1.17) is on Google Play closed testing (alpha) and internal testing since 2026-11-10.

## Goal and done when
Review 1368d74 and ca0dd02 and fix what the review finds. Done when: each finding is fixed, rejected with a reason or deferred to the owner; `tools/run_tests.sh` passes; docs and credits match the code.

## My requests (verbatim)
- 2026-11-09: "review and take lead and fix"
- 2026-11-09: "Drop that review"; then, on whether Fit to screen on a blank worksheet should fit the whole page width instead of zoom 1.45: "Fit the page width"
- 2026-11-10: "push to appstore"
- 2026-11-11: "Push"; then "always push to appstore and github" (recorded as a decision and an invariant); "Yes, publish it" (GitHub release with APKs for 1.17); "make sure we push both places and publish apk as well"

## Corrections from me
- None.

## Commits under review
- 1368d74: reviewed by Claude Code (Fable 5.2, independent) and Antigravity (Gemini 3.8 Flash, self review, unfinished; dropped by the owner 2026-11-09). Applied.
- ca0dd02: reviewed by Claude Code (Fable 5.2, self review, not independent). Applied.
- Not reviewed (the fixes, built in the conversation that reviewed, so it can't review them): e9cfb78, da41b1e, 283ff51, 63de8e8, d01b69f, 97bfe6e, 69afbe7, 7594a2c (version bump only).

## Recently done
- 2026-11-10: 1.17 released to Play (alpha and internal): 7594a2c, tag v1.17 (local); the fixes in it are not reviewed and not device-tested; pushed and released on GitHub with APKs 2026-11-11.
- 2026-11-09: review of 1368d74 and ca0dd02 applied: translation source language, scanned text, page shown, panel height, blank worksheet phone view, docs/credits/privacy: e9cfb78..97bfe6e; not reviewed; not pushed.
- 2026-11-08: one-tap pen after erasing (reader, worksheet, User Guide): ca0dd02; self review 2026-11-09; not pushed.

## Next steps
- Device checks nothing here covered: translation with the model download (all four languages), the panel's height and page following, the pen side bar on a phone, Fit to screen on a blank worksheet (69afbe7; blank worksheets still open at 1.45 / 1.75), pinch on worksheets in Stylus mode, ca0dd02's one tap on the tablet.
- Optional, not requested: Practice mode has the same two-tap return to the pen.
- `.git/info/exclude` has an unanchored `test/` line that hides new test files (MISTAKES); owner to remove it.
- 1.17 is everywhere it goes: Play (alpha, internal), GitHub main and tag v1.17, and the GitHub release v1.17 with the arm64-v8a and armeabi-v7a APKs (2026-11-11). 1.16 never got a GitHub release; 1.17's notes cover it. `.ai-workflow/hk-stroke-order/` stays on this PC (folders don't travel with the notes).

## Blockers / open questions
- Two decisions flagged as not matching the code (`DECISIONS.local.md`, Active decisions): waiting for the owner.

## Verification
`tools/run_tests.sh` passes on 7594a2c (2026-11-10); `./gradlew bundleRelease` built the uploaded bundle (`build/release/Hok6-1.17.aab`); `play_upload.py check` shows 1.17 (19) completed on alpha and internal. No device or emulator run: none was connected.

## Last updated
2026-11-11 by Claude Code (Fable 5.2; workflow 8.13.2)
