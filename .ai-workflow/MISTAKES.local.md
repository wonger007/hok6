# Pitfalls & Gotchas (keep under ~40 lines; prune and graduate)
<!--
Format:
- [Area/Tool]: What fails or breaks, and the rule to prevent it.
Distil during handoff: delete what is fixed in code, keep active gotchas,
or graduate permanent rules into Invariants in AGENTS.local.md.
-->
- [tools/device_check.py]: on a fresh install it can miss the welcome screen (it looks once after 3 s) and doesn't handle the "Choose folder…" dialog; on a new emulator tap Skip, Choose book folder, Choose folder…, StudyBookCheck, Use this folder, Allow first.
- [Emulator tests]: the app remembers its language between runs, so a script that calls `setLang` must set it back to `yue`, or device_check's English lookup fails on another language's words.
- [git/new tests]: `.git/info/exclude` has an unanchored `test/` line, so a new file under `app/src/test/` is ignored and missing from `git status`; add it with `git add -f <file>` (or the owner removes that line; `/test/` above it already covers the book).
