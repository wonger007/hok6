# Hok6: Trace, Write and Learn

*Where you learn, study, and imitate.*

Hok6 is an Android tablet and phone app for working through your own textbook and worksheets, in any language: keep
them as folders of files (PDF, Word, audio), write and trace on the pages with a finger or stylus, and play the lesson
audio. For languages written with characters (Chinese: Cantonese or Mandarin; Japanese; Korean) it adds
stroke-order writing practice, worksheets and quizzes.

The name is 學 (*hok6* in Cantonese Jyutping): to learn, to study, to imitate.

## Install

**Help test Hok6 on Google Play:** see [Test Hok6](TESTING.md). Or install the APK from GitHub:

1. On the Android tablet or phone, open the [latest release](https://github.com/wonger007/hok6/releases/latest) and
   download `Hok6-<version>-arm64-v8a.apk` (use `armeabi-v7a` only on an old 32-bit device).
2. Open the downloaded file and allow installing from that source when Android asks. Android 8.0 or newer is needed.
   A new version installs over the old one and keeps your work.

**Coming from 1.10 or earlier?** From 1.11 Hok6 has a new package name (`com.wonger.hok6`, for Google Play), so it
installs as a second Hok6 next to the old one instead of replacing it. Your work comes across through
`Hok6 backup.json` in your book folder: in the new Hok6, choose the same book folder and tap **Restore** when it
offers (or old Hok6 ⋮ → **Back up my work…**, then new Hok6 ⋮ → **Restore my work…**). Then uninstall the old one.

### Tested devices

Hok6 has been tested only on:

| Device | Android | Notes |
| --- | --- | --- |
| Samsung Galaxy Tab S7 FE 5G | 14 | Tablet layout; book on the microSD card |
| Razer Edge WiFi | 12 | Phone layout (its screen is under 600dp at its narrowest) |
| Samsung Galaxy S25 Ultra | 17 | Phone layout; Android 15+ full-screen (edge-to-edge) layout |

It should work on other phones and tablets with Android 8.0 or newer, but they haven't been tried.

### Device settings

Set these once on the tablet or phone. Menu names differ a little between makers (Samsung, Pixel, …); if one isn't
where it's shown here, search for it in the Settings search bar.

| Setting | Where | Why |
| --- | --- | --- |
| **Install unknown apps** | Settings → Apps → Special app access → **Install unknown apps** → the browser or Files app you downloaded with → **Allow from this source** | Hok6 isn't on the Play Store, so Android blocks the APK otherwise. If **Play Protect** warns, tap **More details → Install anyway**. You can turn the setting off again afterwards. |
| **Voices** (text-to-speech) | Nothing to set if **Speech Services by Google** is installed (it is on most devices): Hok6 speaks with Google's engine itself, without changing the device's own voice setting, and says so once. With internet the voices work straight away, and Google's engine downloads them for offline use in the background. **Download all** opens Google's voice download (add the voice for each language you're learning, e.g. **Chinese (Hong Kong) / 粵語** for Cantonese) to get them sooner. | The 🔊 buttons and the **Sound → word** quiz speak with these voices. ⋮ → **Settings** → **Voices** switches between Google's engine and the device's own. |
| **Internet (once)** | Wi-Fi on, then **Download all** on the welcome screen Hok6 shows the first time it opens (later: ⋮ → **Settings**, on the main screen or in Practice & Quiz) | Downloads the handwriting models for the languages you ticked and opens Google's voice download for any voice not yet on the device (or the Play Store, if Speech Services by Google isn't installed). The list ticks itself off when you come back. The dictionary is built in. After that Hok6 works offline. If you skip, **✍ Draw** in Practice & Quiz shows a **⬇ Download handwriting** button under the pad (no pop-up), and Hok6 offers a missing voice when it's needed (with **Don't remind me again**). |
| **Auto-rotate** (optional) | Quick settings → **Auto-rotate** | Turn the tablet to portrait for a full page, landscape for wider worksheets. |
| **Stylus** (optional) | — | Any active or passive stylus works. On a device that takes a stylus, the title bar has a **✍ Stylus / ☝ Finger** toggle: with **✍ Stylus** only the stylus writes and fingers scroll, so your hand can rest on the screen. Hok6 switches to it the first time a stylus is used, then remembers your choice. Devices without a stylus have no toggle: fingers always write. |

#### Samsung Galaxy tablets (e.g. Galaxy Tab S7 FE)

Samsung's menus differ from the ones above:

- **Auto Blocker** (One UI 6 / Android 14) blocks installing apps from outside the Galaxy Store and Play Store.
  Turn it off to install or update Hok6: Settings → Security and privacy → **Auto Blocker** → off. You can turn it on
  again afterwards, but it blocks the next update the same way.
- **Install unknown apps**: Settings → Apps → ⋮ → **Special access** → Install unknown apps → **My Files** (or Chrome)
  → Allow.
- **Voices**: Samsung devices prefer **Samsung text-to-speech**, which may have no Cantonese. Hok6 uses
  **Speech Recognition and Synthesis from Google** instead (Samsung's name for Google's engine) and leaves the
  device's own setting alone; it tells you once when it does. If Google's engine is missing, install it from the Play
  Store.
- **Book on a microSD card**: when choosing the book folder, tap ☰ in the folder picker and pick the SD card (e.g.
  SD card → Download → *your book*). Hok6 reads it and keeps its backup there just like on the tablet's own storage.
- **Saving a worksheet as PDF**: Samsung asks for a folder in **My Files** (e.g. Download) and then **Done**; the file
  is saved with a `.PDF` ending.

No Google account or Google Drive is needed: Hok6 keeps its backup as a file in your book folder (see
[Keep your files tidy and your work safe](USER_GUIDE.md#6-keep-your-files-tidy-and-your-work-safe)).
No other permissions are needed either: Hok6 only asks for access to the book folder you choose, and audio keeps
playing with lock-screen controls without extra settings.

## Using Hok6

The [**User Guide**](USER_GUIDE.md) shows how to put your book on the device, do homework on the page, practice
writing characters, quiz yourself, and keep your work backed up.

## Features

- **Book folder → folders, as deep as you like.** Pick a folder; each folder in it is a card (sorted so "Chapter 2"
  comes before "Chapter 10"), so it can hold one book's chapters or several books, with chapters or not. A folder
  with folders in it opens as cards one level down (the path above it under the title); a folder of files opens as
  a full-screen file list. Files directly in a folder that also has folders appear as "Files in book folder" or
  "Files in this folder". A file opens full screen, and Back returns to the list.
- **Favourite folders** (☆ on a card) are listed first, here and when moving files; they're in the backup.
- **Organise files.** Long-press a file (or ⋮ → Move files…) to select files and move them to any folder of the book
  (shown with its path), the book folder, or a new folder; tracing and the last page read move with them. ⋮ → New
  folder… on the folder cards makes an empty folder there.
- **Sized for the screen.** Text is a step bigger on small tablets and again on large ones (held further away than a
  phone), on top of Android's font size setting, which Practice & Quiz follows too. Light or dark: the title bars
  stay red (a deeper red in dark mode), with lighter red and file-type colours on dark backgrounds.
- **PDF** pages rendered in the app, with zoom and remembered position; tap the page number to go to a page. Pages keep
  their shape however you pinch and scroll, so tracing stays exactly where you wrote it.
- **Word (.docx)** shown as text in the app, keeping headings, bold/italic, tables and ruby (pinyin/jyutping) annotations.
  It's laid out like a page as wide as the screen's narrow side: pinch or − / + to zoom (the lines wrap the same way at
  every zoom and when the device is turned, so tracing stays on its words), and two fingers scroll, sideways too.
- **Audio** (mp3, m4a, wav, …) as a folder playlist with a player bar, speed control, repeat-one and lock-screen controls.
- **Tracing** on PDFs and Word documents with finger or stylus, on as soon as a file opens (two fingers scroll and
  zoom; on stylus devices a **✍ Stylus / ☝ Finger** toggle says whether fingers write too). The pen (tap it for the colours),
  thickness (a drawer like the colours; its button shows the pen's line), eraser, **Clear** and zoom
  are outlined buttons in the title bar, so nothing covers the page (**Clear** erases one page's tracing after asking,
  with Undo in the message that follows); saved per page. ⋮ → **Save a copy with my tracing…** writes a new PDF with the tracing drawn into
  the pages (as sharp lines) into the same folder, e.g. `completed_Homework.pdf`; it warns before replacing a file.
- **Practice Selector** button on PDFs and Word documents: while it is on, tap a character to choose from the characters on that page (or Word paragraph), which the sheet says plainly, not the whole file; scanned pages are read with on-device text recognition; picking a pen colour goes back to writing.
- **Practice & Quiz**: writing worksheets (Cantonese, Mandarin, Japanese and Korean):
  - rows of practice squares (米 / 田 grid) with grey characters to trace, written on directly on the tablet;
  - stroke order shown above each row, plus stroke-order animation, step-by-step strokes and a writing quiz for each character;
  - Cantonese (Jyutping, Cantonese voice), Mandarin (Pinyin, Mandarin voice), Japanese (kanji, hiragana and katakana
    with Japanese stroke order; the reading in kana and rōmaji, Japanese voice) or Korean (hangul, built stroke by
    stroke from its letters, and hanja; Revised Romanization, letter by letter; Korean voice);
  - pronunciation: hear each character and the word it belongs to, at 1×, 0.75× or 0.5× speed (tap the speed button next to 🔊), with tone names for Cantonese and Mandarin (e.g. nei5 · tone 5, low rising);
  - type English to get words in the language you're practising, from offline dictionaries, shown right under the
    practice box; **+ 普通話 / + 日本語 / + 한국어** toggles (remembered) add a row of words for each other language, and
    tapping one of those switches to its language (adding it to your languages if needed);
  - worksheet options folded under the Create button and kept from the last worksheet;
  - **✏️ Practice writing** and **📝 Quiz** are separate tabs, so the two aren't mixed up;
  - what to practice is **⌨ Typed** (characters, or English to look up) or **✍ Drawn** on a pad right in the form,
    with 1 to 4 squares so a whole word can be written at once;
  - outlined title bar tools, as on homework pages: the pen opens its colours, the thickness its drawer, eraser, **Clear** (Undo in the
    message), the **✍ Stylus / ☝ Finger** toggle (under **⋮** on phones; in stylus mode a finger tap still opens a
    stroke-order square), and zoom under **⋮** so every button keeps Android's full 48dp size;
  - **Quiz**: on a homework page, tap **Practice Selector**, tap a character, choose characters and tap **Add to quiz**. Practice & Quiz → 📝 Quiz tab
    then offers **flash cards** (word → meaning, English → word, or sound → word; you mark Knew it / Again)
    or a **written quiz**: write the character from memory in a blank box, checked stroke by stroke (a hint after 3
    misses, or **Show me**), prompted by its English meaning, its pronunciation, or the character itself, shown at the
    top or hidden after a few seconds. Missed ones come back once at the end of the round; English meanings come from
    the offline dictionary;
  - **✍ Draw**: write a character on the pad and tap the recognised one to add it to the
    worksheet (Google ML Kit handwriting recognition, on the device; needs internet once to download the Chinese model);
  - history of practiced words and characters (with quiz results) and bookmarks in one list, each one tap away from a fresh
    worksheet in the language it was practiced in; a toggle per language shows or hides its words, ✕ removes one
    (with Undo), and Clear removes everything listed or only what's shown;
  - your writing is kept for each word (the last 60 words written on), so it's still there when you practice that word
    again; **Clear** erases everything on screen (the message offers Undo);
  - export: print, or share a PDF (US Letter, 8.5 × 11 in, sharp lines and real text) with another app such as Gmail or
    WhatsApp, blank or with your writing;
  - on phones, one character at a time with big squares (detected automatically).
- **About Hok6** (⋮ → About Hok6): the version, **Check for updates** (Google Play's in-app update, or the latest
  GitHub release for an APK from there; only when tapped), links to the User Guide, privacy policy and source, device
  tips and credits.
- **Welcome screen and Settings**: the first time Hok6 opens it asks which languages you're learning (Cantonese,
  Mandarin, Japanese, Korean, one or more; Settings can change them, and the downloads and Practice & Quiz follow),
  then shows what it
  needs from the internet (handwriting recognition and voices) with **Download all** and **Skip**. ⋮ → **Settings**
  has the same list any time (a voice that so far only works online is marked as such), which voice engine to speak
  with, the download reminders switch, light or dark, and the welcome screen again.
- **Light or dark**: ⋮ → **Settings** → *Same as the tablet* (the default), *Light* or *Dark*;
  Practice & Quiz follows it too. While Hok6 starts (and while Practice & Quiz loads) a splash shows its name, tag
  line and a progress bar.
- **Your work is backed up, on the device**: Practice & Quiz history, bookmarks and writing, the quiz and tracing are
  kept in the app's own storage and copied to `Hok6 backup.json` in the book folder whenever a screen is left after a
  change (the file is hidden from the chapter's file list). It survives uninstalling; choosing the book folder after
  reinstalling offers to restore it. Nothing goes to Google Drive (Android's cloud backup is turned off for Hok6),
  but moving to a new device by cable or Wi-Fi brings it along. **Back up my work…** / **Restore my work…**
  (main screen menu, or under History in Practice & Quiz) save a copy anywhere, or restore any backup file.
  Restoring adds to what's there: history, bookmarks and the quiz are combined.

## Building

Requires JDK 17 and the Android SDK (platform 36).

```bash
./gradlew assembleRelease
```

One APK is built per CPU type in `app/build/outputs/apk/release/`:

- `app-arm64-v8a-release.apk` — almost all current Android phones and tablets (including Razer Edge)
- `app-armeabi-v7a-release.apk` — older 32-bit devices
- `app-x86_64-release.apk` — the Android emulator

Two apps are built: **release** ("Hok6", `app-<cpu>-release.apk`) is the one to use, and **debug**
("Hok6 (test)", package `com.wonger.hok6.debug`) installs alongside it with its own data, for automated checks.

For Google Play, `./gradlew bundleRelease` builds `app/build/outputs/bundle/release/app-release.aab`, signed with
the upload key named in `~/.gradle/gradle.properties` (`hok6.upload.*`). Without that key, release builds are signed
with the debug key.

`tools/play_upload.py` uploads that bundle to Google Play with the Play Developer API (`check` lists the tracks,
`upload AAB --track alpha --name 1.15 --notes notes.txt` releases it on closed testing, `release CODE --track …`
puts a version already uploaded on another track). It needs a Google Cloud service account that's invited in Play
Console with release permissions for Hok6; its JSON key stays outside the repo, in
`~/.config/hok6/play-service-account.json`. See the top of the script for setting it up.

`test/` is a local sample book folder used for testing; it is excluded from git.

### Character data

`app/src/main/assets/hanzi.bin` (stroke data, one file) and `app/src/main/assets/training/readings/`
(Jyutping/Pinyin for 46,000+ characters) are generated from their sources by:

```bash
python3 tools/build_assets.py
```

## Testing

Quick tests (no device needed, about a minute): Kotlin unit tests for chapter sorting, file types, Word reading,
finding the character under a tap, the stroke data bundle, backups, and tracing loading back exactly as it was saved; JavaScript tests (Node.js) for the assembled Cantonese
characters, tone names and readings.

```bash
tools/run_tests.sh
```

### Checking on a device

`tools/device_check.py` installs "Hok6 (test)", copies `test/` to `Download/StudyBookCheck` on the device and
checks, with no taps needed: chapters in order, a chapter's files, opening a PDF, Practice writing from the PDF, the
stroke order panel, saving and sharing a US Letter PDF, writing on worksheets (finger and stylus), English lookup,
that pinching while dragging a PDF doesn't stretch its pages, going to a page by its number, tracing on a PDF page
being saved, Clear asking first and Undo bringing the tracing back, and saving a copy with the tracing. Gestures with more than one finger are played by
`TestGestures`, which is only in the test build (`app/src/debug/`). Screenshots go to `build/device-check/`. If no device is connected
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

Faster and steadier with WSL: let Windows talk to the device instead of passing USB through. Unzip Google's
[platform tools for Windows](https://developer.android.com/tools/releases/platform-tools) to `C:\platform-tools` and
copy `~/.android/adbkey` to `%USERPROFILE%\.android\` (so devices that already allowed this computer don't ask again).
With a device plugged in and not attached to WSL (`usbipd detach --busid …`), `tools/adbw` works like `adb` and
`device_check.py` uses it by itself: large copies don't stall the way they can over usbipd. The writing-practice checks
reach the app through a port that Windows' adb opens on Windows only, so they ask from the Windows side with
`tools/devtools_eval.ps1` (PowerShell, built into Windows); nothing else to set up.

Wireless instead of USB (Android 11+): Developer options → **Wireless debugging** → *Pair device with pairing code*,
then `adb pair IP:PORT CODE` once and `python3 tools/device_check.py --connect IP:PORT`
(the port changes when wireless debugging is turned off and on).

## License

Hok6's own code is © 2026 wonger007 and licensed under the [Apache License 2.0](LICENSE); see also [NOTICE](NOTICE).

The data and libraries it bundles keep their own licenses (listed under Credits below):

- **Stroke order data** (`app/src/main/assets/hanzi.bin`; Japanese and Korean in `kanji.bin` and `hanja.bin`):
  Arphic Public License.
- **English → Chinese dictionary** (`app/src/main/assets/training/dict/`): adapted (selected and reformatted) from
  CC-CEDICT and CC-Canto, and shared under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
- **English → Japanese dictionary** (`training/dict-ja/`): adapted from JMdict and KANJIDIC2 and shared under
  [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
- **English → Korean dictionary** (`training/dict-ko/`): adapted from kengdic, under the
  [Mozilla Public License 2.0](app/src/main/assets/training/MPL-2.0.txt).
- **Readings** (`app/src/main/assets/training/readings/`): Jyutping from rime-cantonese (CC BY 4.0), Pinyin and
  fallback Jyutping from the Unicode Unihan database (Unicode License V3,
  [UNICODE-LICENSE.txt](app/src/main/assets/training/UNICODE-LICENSE.txt)), and Japanese and Korean readings of kanji
  and hanja from KANJIDIC2 (CC BY-SA 4.0).

`test/` (a personal textbook used for testing) is not part of the repository.

## Credits

This project builds on, bundles data from, or was modelled on the following projects:

| Project | Used for | License |
| --- | --- | --- |
| [chanind/hanzi-writer](https://github.com/chanind/hanzi-writer) | Stroke order animations, stroke-by-stroke playback and the writing quiz (bundled as `hanzi-writer.min.js`) | MIT |
| [chanind/hanzi-writer-data](https://github.com/chanind/hanzi-writer-data) | Chinese stroke data (bundled as `app/src/main/assets/hanzi.bin`) | Arphic Public License ([ARPHICPL.TXT](app/src/main/assets/training/ARPHICPL.TXT)) |
| [skishore/makemeahanzi](https://github.com/skishore/makemeahanzi) | Original source of the stroke data, derived from fonts by Arphic Technology | Arphic Public License |
| [12jr/chinese-character-worksheets](https://github.com/12jr/chinese-character-worksheets) | Reference for the worksheet design (row layout, grey tracing characters, stroke-order strips, gridlines, name/title header). No code was copied. | GPL-3.0 |
| [rime/rime-cantonese](https://github.com/rime/rime-cantonese) | Jyutping readings (bundled in `training/readings/`) | CC BY 4.0 |
| [Unicode Unihan database](https://www.unicode.org/charts/unihan.html) | Pinyin and fallback Jyutping readings; character grade/frequency for ranking dictionary results | Unicode License |
| [CC-CEDICT](https://www.mdbg.net/chinese/dictionary?page=cedict) | English → Chinese dictionary (bundled in `training/dict/`) | CC BY-SA 4.0 |
| [CC-Canto](https://cantonese.org) | Cantonese words and Jyutping for the English → Chinese dictionary | CC BY-SA 3.0 |
| [AndroidX Media3](https://github.com/androidx/media) | Audio playback | Apache 2.0 |
| [AndroidX Input Motion Prediction](https://developer.android.com/jetpack/androidx/releases/input) | Drawing the line a little ahead of the pen, so tracing keeps up with its tip | Apache 2.0 |
| [TomRoush/PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) | Reading the characters on PDF pages, so tapping one in Practice mode opens Practice & Quiz | Apache 2.0 |
| [parsimonhi/animCJK](https://github.com/parsimonhi/animCJK) | Japanese stroke data (kanji, hiragana, katakana) and Korean hanja (bundled as `kanji.bin` and `hanja.bin`); Korean hangul strokes are built by Hok6 from its letters | Arphic Public License ([ANIMCJK-COPYING.txt](app/src/main/assets/training/ANIMCJK-COPYING.txt)) |
| [JMdict and KANJIDIC2](https://www.edrdg.org) (EDRDG), as JSON from [scriptin/jmdict-simplified](https://github.com/scriptin/jmdict-simplified) | English → Japanese dictionary (bundled in `training/dict-ja/`), Japanese readings of kanji, Korean readings of hanja | CC BY-SA 4.0 |
| [garfieldnate/kengdic](https://github.com/garfieldnate/kengdic) | English → Korean dictionary (bundled in `training/dict-ko/`) | MPL 2.0 (or LGPL 2.0+) |
| [Google ML Kit Text Recognition v2](https://developers.google.com/ml-kit/vision/text-recognition/v2) (Chinese, Japanese, Korean) | Recognising characters on scanned PDF pages that have no text layer (on-device, bundled models) | [ML Kit Terms](https://developers.google.com/ml-kit/terms) |
| [Google ML Kit Digital Ink Recognition](https://developers.google.com/ml-kit/vision/digital-ink-recognition) | **✍ Draw** in Practice & Quiz: recognising handwritten characters (on-device; each language's model downloads once) | [ML Kit Terms](https://developers.google.com/ml-kit/terms) |
| [Google Play In-App Updates](https://developer.android.com/guide/playcore/in-app-updates) | **Check for updates** on the About page, for Hok6 installed from Google Play | [Play Core Software Development Kit Terms](https://developer.android.com/guide/playcore#license) |

Colloquial Cantonese characters that are not in the stroke data (e.g. 咗, 佢, 哋, 喺) are assembled from their components by this app; those are marked ≈ and their stroke order is an approximation.
