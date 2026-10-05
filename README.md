# Hok6

*Where you learn, study, and imitate.*

Hok6 (學, *hok6* in Jyutping: to learn, to study, to imitate) is an Android tablet app for working through a Chinese (Cantonese or Mandarin) study book stored as folders of files.

- **Book folder → chapters.** Pick a folder; each sub-folder is a chapter (sorted so "Chapter 2" comes before "Chapter 10").
  Files directly in the book folder appear as "Files in book folder". A chapter opens as a full-screen file list; a
  file opens full screen, and Back returns to the list.
- **Organise files.** Long-press a file (or ⋮ → Move files…) to select files and move them to another chapter folder,
  the book folder, or a new folder; tracing and the last page read move with them. ⋮ → New chapter folder… on the
  main screen makes an empty chapter.
- **PDF** pages rendered in the app, with zoom and remembered position.
- **Word (.docx)** shown as text in the app, keeping headings, bold/italic, tables and ruby (pinyin/jyutping) annotations.
- **Audio** (mp3, m4a, wav, …) as a chapter playlist with a player bar, speed control, repeat-one and lock-screen controls.
- **Tracing** on PDFs and Word documents with finger or stylus, on as soon as a file opens (two fingers scroll and
  zoom; once a stylus is used, only the stylus writes). The pens, size, eraser, undo, **Clear** and zoom are in the title
  bar, so nothing covers the page (**Clear** erases everything on screen at once); saved per page. ⋮ → **Save a copy with my tracing…** writes a new PDF with the tracing drawn into
  the pages (as sharp lines) into the chapter folder, e.g. `completed_Homework.pdf`; it warns before replacing a file.
- **Practice** button on PDFs and Word documents: while it is on, tap a character to open writing practice for it; scanned pages are read with on-device text recognition; picking a pen goes back to writing.
- **Writing practice** worksheets:
  - rows of practice squares (米 / 田 grid) with grey characters to trace, written on directly on the tablet;
  - stroke order shown above each row, plus stroke-order animation, step-by-step strokes and a writing quiz for each character;
  - Cantonese (Jyutping, Cantonese voice) or Mandarin (Pinyin, Mandarin voice);
  - pronunciation: hear each character and the word it belongs to, at normal or slow speed, with tone names (e.g. nei5 · tone 5, low rising);
  - type English to get Chinese words (Cantonese or Mandarin, following the language switch), from an offline dictionary;
  - **Quiz**: on a homework page, tap **Practice**, choose characters and tap **Add to quiz**. Writing practice → Quiz
    then offers **flash cards** (Chinese → meaning, English → Chinese, or sound → Chinese; you mark Knew it / Again)
    or a **written quiz**: write the character from memory in a blank box, checked stroke by stroke (a hint after 3
    misses, or **Show me**), prompted by its English meaning, its pronunciation, or the character itself, shown at the
    top or hidden after a few seconds. Missed ones come back once at the end of the round; English meanings come from
    the offline dictionary;
  - **Write by hand (stylus)**: a teacher writes a character on a pad and taps the recognised one to add it to the
    worksheet (Google ML Kit handwriting recognition, on the device; needs internet once to download the Chinese model);
  - history of practiced words and characters (with quiz results) and bookmarks in one list, each one tap away from a fresh
    worksheet;
  - your writing is kept for each word (the last 60 words written on), so it's still there when you practice that word
    again; **Clear** erases everything on screen (Undo brings it back);
  - export: print, or share a PDF (US Letter, 8.5 × 11 in, sharp lines and real text) with another app such as Gmail or
    WhatsApp, blank or with your writing;
  - on phones, one character at a time with big squares (detected automatically).
- **Your work is backed up**: writing practice history, bookmarks and writing, and tracing are kept in the app's own
  storage, included in Android's backup and in transfers to a new device. **Back up my work…** / **Restore my work…**
  (main screen menu, or under History in writing practice) also save it all to one file you keep, e.g. before
  reinstalling. Restoring adds to what's there: history and bookmarks are combined.

## Building

Requires JDK 17 and the Android SDK (platform 35).

```bash
./gradlew assembleRelease
```

One APK is built per CPU type in `app/build/outputs/apk/release/`:

- `app-arm64-v8a-release.apk` — almost all current Android phones and tablets (including Razer Edge)
- `app-armeabi-v7a-release.apk` — older 32-bit devices
- `app-x86_64-release.apk` — the Android emulator

Two apps are built: **release** ("Hok6", `app-<cpu>-release.apk`) is the one to use, and **debug**
("Hok6 (test)", package `com.studybook.reader.debug`) installs alongside it with its own data, for automated checks.

`test/` is a local sample book folder used for testing; it is excluded from git.

### Character data

`app/src/main/assets/hanzi.bin` (stroke data, one file) and `app/src/main/assets/training/readings/`
(Jyutping/Pinyin for 46,000+ characters) are generated from their sources by:

```bash
python3 tools/build_assets.py
```

## Testing

Quick tests (no device needed, about a minute): Kotlin unit tests for chapter sorting, file types, Word reading,
finding the character under a tap and the stroke data bundle; JavaScript tests (Node.js) for the assembled Cantonese
characters, tone names and readings.

```bash
tools/run_tests.sh
```

### Checking on a device

`tools/device_check.py` installs "Hok6 (test)", copies `test/` to `Download/StudyBookCheck` on the device and
checks, with no taps needed: chapters in order, a chapter's files, opening a PDF, Practice writing from the PDF, the
stroke order panel, and saving a US Letter PDF. Screenshots go to `build/device-check/`. If no device is connected
and ready, it starts the emulator instead and shuts it down afterwards (`--emulator` forces this, `--avd NAME`
chooses which).

```bash
python3 tools/device_check.py
```

One-time setup for a USB device (e.g. Razer Edge) with this project in WSL:

1. On the device: Settings → About → tap **Build number** 7 times; then Settings → System → Developer options →
   turn on **USB debugging** (and **Stay awake** helps). Keep the screen unlocked while checks run (no PIN prompt).
2. Plug it in. In an **administrator** PowerShell on Windows, share it with WSL once
   (`usbipd list` shows its BUSID):
   ```powershell
   usbipd bind --busid 7-1
   ```
3. Run the check; it attaches the device to WSL itself. The first time, the device asks
   **"Allow USB debugging?"** — tick **Always allow from this computer** and tap **Allow**.

Wireless instead of USB (Android 11+): Developer options → **Wireless debugging** → *Pair device with pairing code*,
then `adb pair IP:PORT CODE` once and `python3 tools/device_check.py --connect IP:PORT`
(the port changes when wireless debugging is turned off and on).

## Credits

This project builds on, bundles data from, or was modelled on the following projects:

| Project | Used for | License |
| --- | --- | --- |
| [chanind/hanzi-writer](https://github.com/chanind/hanzi-writer) | Stroke order animations, stroke-by-stroke playback and the writing quiz (bundled as `hanzi-writer.min.js`) | MIT |
| [chanind/hanzi-writer-data](https://github.com/chanind/hanzi-writer-data) | Character stroke data (bundled in `app/src/main/assets/hanzi`) | Arphic Public License ([ARPHICPL.TXT](app/src/main/assets/training/ARPHICPL.TXT)) |
| [skishore/makemeahanzi](https://github.com/skishore/makemeahanzi) | Original source of the stroke data, derived from fonts by Arphic Technology | Arphic Public License |
| [12jr/chinese-character-worksheets](https://github.com/12jr/chinese-character-worksheets) | Reference for the worksheet design (row layout, grey tracing characters, stroke-order strips, gridlines, name/title header). No code was copied. | GPL-3.0 |
| [rime/rime-cantonese](https://github.com/rime/rime-cantonese) | Jyutping readings (bundled in `training/readings/`) | CC BY 4.0 |
| [Unicode Unihan database](https://www.unicode.org/charts/unihan.html) | Pinyin and fallback Jyutping readings; character grade/frequency for ranking dictionary results | Unicode License |
| [CC-CEDICT](https://www.mdbg.net/chinese/dictionary?page=cedict) | English → Chinese dictionary (bundled in `training/dict/`) | CC BY-SA 4.0 |
| [CC-Canto](https://cantonese.org) | Cantonese words and Jyutping for the English → Chinese dictionary | CC BY-SA 3.0 |
| [AndroidX Media3](https://github.com/androidx/media) | Audio playback | Apache 2.0 |
| [TomRoush/PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) | Reading the characters on PDF pages, so tapping one in Practice mode opens writing practice | Apache 2.0 |
| [Google ML Kit Text Recognition v2 (Chinese)](https://developers.google.com/ml-kit/vision/text-recognition/v2) | Recognising characters on scanned PDF pages that have no text layer (on-device, bundled model) | [ML Kit Terms](https://developers.google.com/ml-kit/terms) |
| [Google ML Kit Digital Ink Recognition](https://developers.google.com/ml-kit/vision/digital-ink-recognition) | **Write by hand** in writing practice: recognising handwritten characters (on-device; the Chinese model downloads once) | [ML Kit Terms](https://developers.google.com/ml-kit/terms) |

Colloquial Cantonese characters that are not in the stroke data (e.g. 咗, 佢, 哋, 喺) are assembled from their components by this app; those are marked ≈ and their stroke order is an approximation.
