#!/usr/bin/env python3
"""
Rebuilds the app's character data from its original sources.

  app/src/main/assets/hanzi.bin           stroke data for every character in hanzi-writer-data, in one file
  app/src/main/assets/kanji.bin           Japanese stroke data (kanji, hiragana, katakana) from AnimCJK
  app/src/main/assets/hanja.bin           Korean hanja stroke data from AnimCJK (hangul is built from its letters in
                                          training/core.js, so it needs no data)
  app/src/main/assets/training/readings/  Jyutping | Pinyin | Japanese on/kun | Korean hangul reading for every character
                                          with a known reading, split into small files by Unicode block so only what
                                          is needed is loaded
  app/src/main/assets/training/dict/      English ↔ Chinese dictionary (entries in chunks + English word index by letter
                                          + headword index by first character); dict-ja/ and dict-ko/ the same for
                                          Japanese and Korean
  app/src/main/assets/training/           hanzi-writer.min.js and the licences that must ship with the data

Sources (downloaded into tools/.cache):
  hanzi-writer, hanzi-writer-data (npm)   https://github.com/chanind/hanzi-writer(-data)
  rime-cantonese jyut6ping3.chars         https://github.com/rime/rime-cantonese   (Jyutping, ordered by frequency)
  Unicode Unihan_Readings.txt             https://www.unicode.org/charts/unihan.html (kMandarin, kCantonese)
  CC-CEDICT (MDBG)                        https://www.mdbg.net/chinese/dictionary?page=cedict  (CC BY-SA 4.0)
  CC-Canto + CC-CEDICT Cantonese readings https://cantonese.org  (CC BY-SA 3.0)
  AnimCJK graphicsJa/JaKana/Ko            https://github.com/parsimonhi/animCJK  (Arphic Public License)
  JMdict (common words) + KANJIDIC2       https://www.edrdg.org (CC BY-SA 4.0), as JSON from
                                          https://github.com/scriptin/jmdict-simplified
  kengdic                                 https://github.com/garfieldnate/kengdic  (MPL 2.0 or LGPL 2.0+)

hanzi.bin layout (little-endian):
  "HZB1", u32 count, count x (u32 codepoint, u32 offset, u32 length) sorted by codepoint,
  then each character's JSON compressed with zlib. Offsets are from the start of the file.

Usage: python3 tools/build_assets.py
"""
import functools
import io
import json
import os
import re
import shutil
import struct
import tarfile
import urllib.request
import zipfile
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(ROOT, "tools", ".cache")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
TRAINING = os.path.join(ASSETS, "training")

ANIMCJK = "https://raw.githubusercontent.com/parsimonhi/animCJK/ec5e17cca76c87587790bcbce5ea0b4d4fb753d6/"
JMDICT_VERSION = "3.6.2+20261005200550"
JMDICT = "https://github.com/scriptin/jmdict-simplified/releases/download/" + JMDICT_VERSION.replace("+", "%2B") + "/"

SOURCES = {
    "hanzi-writer.tgz": "https://registry.npmjs.org/hanzi-writer/-/hanzi-writer-3.7.3.tgz",
    "hanzi-writer-data.tgz": "https://registry.npmjs.org/hanzi-writer-data/-/hanzi-writer-data-2.0.1.tgz",
    "jyut6ping3.chars.dict.yaml": "https://raw.githubusercontent.com/rime/rime-cantonese/main/jyut6ping3.chars.dict.yaml",
    "Unihan.zip": "https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip",
    "cedict.zip": "https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.zip",
    "cccanto.zip": "https://cantonese.org/cccanto-170202.zip",
    "cccedict-canto-readings.zip": "https://cantonese.org/cccedict-canto-readings-150923.zip",
    "graphicsJa.txt": ANIMCJK + "graphicsJa.txt",
    "graphicsJaKana.txt": ANIMCJK + "graphicsJaKana.txt",
    "graphicsKo.txt": ANIMCJK + "graphicsKo.txt",
    "animcjk-COPYING.txt": ANIMCJK + "licenses/COPYING.txt",
    "jmdict-eng-common.tgz": JMDICT + "jmdict-eng-common-" + JMDICT_VERSION + ".json.tgz",
    "kanjidic2-en.tgz": JMDICT + "kanjidic2-en-" + JMDICT_VERSION + ".json.tgz",
    "kengdic.tsv": "https://raw.githubusercontent.com/garfieldnate/kengdic/793de2369c9a98b944154eb4695d26854d2de59b/kengdic.tsv",
}

# English words too common to search on their own.
STOP_WORDS = {"a", "an", "the", "to", "of", "be", "or", "and", "in", "on", "for", "at", "by", "with", "is", "as",
              "it", "sth", "sb", "etc", "esp", "from", "into", "one", "s"}
DICT_CHUNK = 1000

# Readings are split into files of this many code points (cp >> SHARD_BITS); training/core.js uses the same value.
SHARD_BITS = 10


def fetch(name):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, name)
    if not os.path.exists(path):
        print("downloading", SOURCES[name])
        urllib.request.urlretrieve(SOURCES[name], path)
    return path


def npm_files(name):
    """Yields (path inside package, bytes) for every file of an npm tarball."""
    with tarfile.open(fetch(name)) as tar:
        for member in tar.getmembers():
            if member.isfile():
                yield member.name[len("package/"):], tar.extractfile(member).read()


def build_stroke_bundle():
    entries = {}
    licence = None
    for path, data in npm_files("hanzi-writer-data.tgz"):
        if path.endswith(".json") and path != "package.json":
            ch = path[:-len(".json")]
            if len(ch) == 1:
                # Re-serialise compactly; the data is already minimal but this drops any whitespace.
                entries[ord(ch)] = json.dumps(json.loads(data), separators=(",", ":")).encode()
        elif path == "ARPHICPL.TXT":
            licence = data
    write_bundle("hanzi.bin", entries)
    with open(os.path.join(TRAINING, "ARPHICPL.TXT"), "wb") as out:
        out.write(licence)


def write_bundle(name, entries):
    """Writes {codepoint: JSON bytes} as a stroke data bundle (layout above)."""
    codepoints = sorted(entries)
    header = 8 + 12 * len(codepoints)
    blobs, index, offset = [], [], header
    for cp in codepoints:
        blob = zlib.compress(entries[cp], 9)
        index.append(struct.pack("<III", cp, offset, len(blob)))
        blobs.append(blob)
        offset += len(blob)
    with open(os.path.join(ASSETS, name), "wb") as out:
        out.write(b"HZB1" + struct.pack("<I", len(codepoints)))
        out.writelines(index)
        out.writelines(blobs)
    print(f"{name}: {len(codepoints)} characters, {offset / 1048576:.1f} MB")


def kana_split_strokes(characters):
    """Kana whose strokes AnimCJK splits into parts (a stroke that crosses itself, as in あ, ぬ, め): {character: the
    stroke number of each part, e.g. [1, 2, 3, 3]}. Only its SVG files say which parts belong together (ids d3a, d3b)."""
    out = {}
    folder = os.path.join(CACHE, "svgsJaKana")
    os.makedirs(folder, exist_ok=True)
    for ch in characters:
        path = os.path.join(folder, f"{ord(ch)}.svg")
        if not os.path.exists(path):
            urllib.request.urlretrieve(ANIMCJK + f"svgsJaKana/{ord(ch)}.svg", path)
        with open(path, encoding="utf-8") as f:
            parts = re.findall(r'<path id="z\d+d(\d+)([a-z]?)"', f.read())
        if any(letter for _, letter in parts):
            out[ch] = [int(n) for n, _ in parts]
    return out


def animcjk(*names):
    """{codepoint: JSON bytes} from AnimCJK graphics files (one Make Me a Hanzi style JSON object per line, in the same
    coordinates as hanzi-writer-data). Kana strokes split into parts are joined again, so stroke counts are right."""
    entries = {}
    for name in names:
        with open(fetch(name), encoding="utf-8") as f:
            chars = [json.loads(line) for line in f if line.strip()]
        split = kana_split_strokes([d["character"] for d in chars]) if "Kana" in name else {}
        for d in chars:
            if len(d["character"]) != 1:
                continue
            strokes, medians = d["strokes"], d["medians"]
            numbers = split.get(d["character"])
            if numbers and len(numbers) == len(strokes):
                joined_strokes, joined_medians = [], []
                for i, n in enumerate(numbers):
                    if i and numbers[i - 1] == n:
                        joined_strokes[-1] += " " + strokes[i]
                        joined_medians[-1] += medians[i]
                    else:
                        joined_strokes.append(strokes[i])
                        joined_medians.append(list(medians[i]))
                strokes, medians = joined_strokes, joined_medians
            entries[ord(d["character"])] = json.dumps({"strokes": strokes, "medians": medians}, separators=(",", ":")).encode()
    return entries


def build_cjk_bundles():
    """Japanese and Korean stroke data: their own shapes and stroke order (e.g. Japanese 必, 写)."""
    write_bundle("kanji.bin", animcjk("graphicsJa.txt", "graphicsJaKana.txt"))
    write_bundle("hanja.bin", animcjk("graphicsKo.txt"))
    shutil.copy(fetch("animcjk-COPYING.txt"), os.path.join(TRAINING, "ANIMCJK-COPYING.txt"))


@functools.lru_cache(maxsize=None)
def kanjidic():
    """KANJIDIC2 characters: {literal: {"ja": [readings], "ko": [hangul readings], "meanings": [...], "grade": n}}."""
    with tarfile.open(fetch("kanjidic2-en.tgz")) as tar:
        member = next(m for m in tar.getmembers() if m.name.endswith(".json"))
        data = json.load(tar.extractfile(member))
    out = {}
    for c in data["characters"]:
        rm = c.get("readingMeaning") or {}
        on, kun, ko, meanings = [], [], [], []
        for g in rm.get("groups", []):
            for r in g["readings"]:
                if r["type"] == "ja_on":
                    on.append(r["value"].replace("-", ""))
                elif r["type"] == "ja_kun":
                    # まな.ぶ → まな(ぶ): the kana written after the kanji in brackets; -び → び
                    stem, _, tail = r["value"].replace("-", "").partition(".")
                    kun.append(stem + (f"({tail})" if tail else ""))
                elif r["type"] == "korean_h":
                    ko.append(r["value"])
            meanings += [m["value"] for m in g["meanings"] if m.get("lang", "en") == "en"]
        misc = c.get("misc") or {}
        out[c["literal"]] = {
            # On readings (katakana) first: they are how kanji are usually read in words of two or more.
            "ja": list(dict.fromkeys(r for r in on + kun if r)),
            "ko": list(dict.fromkeys(ko)),
            "meanings": meanings,
            "grade": misc.get("grade"),
            "jlpt": misc.get("jlptLevel"),
        }
    return out


def build_readings():
    # Jyutping from rime-cantonese, most common first. A missing weight means the main reading.
    jyutping = {}
    with open(fetch("jyut6ping3.chars.dict.yaml"), encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 2 or len(parts[0]) != 1:
                continue
            weight = 100.0
            if len(parts) > 2 and parts[2].endswith("%"):
                try:
                    weight = float(parts[2][:-1])
                except ValueError:
                    pass
            jyutping.setdefault(parts[0], []).append((-weight, len(jyutping.get(parts[0], [])), parts[1]))

    mandarin, cantonese = {}, {}
    with zipfile.ZipFile(fetch("Unihan.zip")) as z:
        for line in io.TextIOWrapper(z.open("Unihan_Readings.txt"), encoding="utf-8"):
            m = re.match(r"U\+([0-9A-F]+)\t(kMandarin|kCantonese)\t(.*)", line)
            if m:
                ch = chr(int(m.group(1), 16))
                (mandarin if m.group(2) == "kMandarin" else cantonese)[ch] = m.group(3).split()

    kd = kanjidic()
    shards = {}
    for ch in set(jyutping) | set(mandarin) | set(cantonese) | set(kd):
        yue = [r for _, _, r in sorted(jyutping[ch])] if ch in jyutping else cantonese.get(ch, [])
        yue = list(dict.fromkeys(yue))
        k = kd.get(ch, {})
        fields = [" ".join(yue), " ".join(mandarin.get(ch, [])), " ".join(k.get("ja", [])), " ".join(k.get("ko", []))]
        # "yue|cmn|ja|ko"; trailing empty fields are left off.
        while fields and not fields[-1]:
            fields.pop()
        shards.setdefault(ord(ch) >> SHARD_BITS, {})[ch] = "|".join(fields)

    out_dir = os.path.join(TRAINING, "readings")
    shutil.rmtree(out_dir, ignore_errors=True)
    os.makedirs(out_dir)
    for shard, table in shards.items():
        with open(os.path.join(out_dir, f"{shard:x}.json"), "w", encoding="utf-8") as out:
            json.dump(table, out, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    print(f"readings: {sum(len(t) for t in shards.values())} characters in {len(shards)} files")


def dictionary_lines(name):
    with zipfile.ZipFile(fetch(name)) as z:
        member = next(n for n in z.namelist() if n.endswith((".u8", ".txt")))
        for line in io.TextIOWrapper(z.open(member), encoding="utf-8"):
            if not line.startswith("#"):
                yield line.rstrip("\n")


ENTRY = re.compile(r"^(\S+) (\S+) \[([^\]]*)\](?: \{([^}]*)\})? /(.*)/$")
SKIP_GLOSS = re.compile(r"^(surname |(old |archaic |Japanese |)variant of |see |used in |abbr\. for |CL:|also pr\.|also written |\(old\) |"
                        r"Taiwan pr\.|pr\. |\(bound form\)$)", re.I)


def clean_glosses(raw):
    out = []
    for g in raw.split("/"):
        g = g.strip()
        if not g or SKIP_GLOSS.match(g):
            continue
        out.append(g)
    return out


def tokens(gloss):
    text = re.sub(r"\([^)]*\)|\[[^\]]*\]", " ", gloss.lower())
    return [t for t in re.findall(r"[a-z]+(?:'[a-z]+)?", text) if t not in STOP_WORDS and len(t) > 1]


def build_dictionary():
    """English → Chinese search data: entries in chunks, plus an index of English words by first letter."""
    canto_readings = {}
    for line in dictionary_lines("cccedict-canto-readings.zip"):
        m = re.match(r"^(\S+) (\S+) \[([^\]]*)\] \{([^}]*)\}", line)
        if m:
            canto_readings[(m.group(1), m.group(2), m.group(3))] = m.group(4)

    entries = []  # [traditional, simplified, pinyin, jyutping, glosses, cantonese-only]
    for source, cantonese in (("cedict.zip", 0), ("cccanto.zip", 1)):
        for line in dictionary_lines(source):
            m = ENTRY.match(line)
            if not m:
                continue
            trad, simp, pinyin, jyut, raw = m.groups()
            if not all(0x3400 <= ord(c) <= 0x9FFF or 0x20000 <= ord(c) <= 0x3FFFF for c in trad) or len(trad) > 6:
                continue
            glosses = clean_glosses(raw)
            if not glosses:
                continue
            jyut = jyut or canto_readings.get((trad, simp, pinyin), "")
            entries.append([trad, simp, pinyin, jyut, "; ".join(glosses)[:120], cantonese])

    # Characters with stroke data are the common ones; words made of them are usually what a learner wants.
    with tarfile.open(fetch("hanzi-writer-data.tgz")) as tar:
        common = {n[len("package/"):-len(".json")] for n in tar.getnames() if n.endswith(".json") and len(n) == len("package/") + 6}

    # How early a character is learned: Hong Kong primary school grade (kGradeLevel 1-6), else frequency band
    # (kFrequency 1-5); unknown characters count as rare.
    level = {}
    with zipfile.ZipFile(fetch("Unihan.zip")) as z:
        for line in io.TextIOWrapper(z.open("Unihan_DictionaryLikeData.txt"), encoding="utf-8"):
            m = re.match(r"U\+([0-9A-F]+)\t(kGradeLevel|kFrequency)\t(\d)", line)
            if m:
                ch, value = chr(int(m.group(1), 16)), int(m.group(3))
                value = value if m.group(2) == "kGradeLevel" else value + 1
                level[ch] = min(level.get(ch, 9), value)

    def rank(i, t):
        trad, _, _, _, gloss, cantonese = entries[i]
        first = re.sub(r"\([^)]*\)", "", gloss.split(";")[0]).strip().lower()
        first = first[3:] if first.startswith("to ") else first
        return (
            0 if first == t else 1,                        # the word *is* the meaning
            0 if t in tokens(gloss.split(";")[0]) else 1,  # in the first meaning
            max(level.get(c, 9) for c in trad),            # its hardest character is learned early
            0 if all(c in common for c in trad) else 1,
            len(trad),
            len(gloss),
        )

    index = {}
    for i, e in enumerate(entries):
        for t in set(tokens(e[4])):
            index.setdefault(t, []).append(i)
    for t, ids in index.items():
        ids.sort(key=lambda i: rank(i, t))
    # [7th field] how early the word's hardest character is learned (1 = earliest, 9 = rare).
    for e in entries:
        e.append(max(level.get(c, 9) for c in e[0]))

    write_dictionary("dict", entries, index)


def write_dictionary(name, entries, index):
    """Writes training/<name>/: entries [word, alt, reading, reading2, glosses, flag, level] in chunks, the English word
    index by first letter (entry numbers best first), and headwords (word and alt) by their first character."""
    out_dir = os.path.join(TRAINING, name)
    shutil.rmtree(out_dir, ignore_errors=True)
    os.makedirs(out_dir)
    for start in range(0, len(entries), DICT_CHUNK):
        with open(os.path.join(out_dir, f"e{start // DICT_CHUNK}.json"), "w", encoding="utf-8") as out:
            json.dump(entries[start:start + DICT_CHUNK], out, ensure_ascii=False, separators=(",", ":"))
    by_letter = {}
    for t, ids in index.items():
        by_letter.setdefault(t[0], {})[t] = ids
    for letter, table in by_letter.items():
        with open(os.path.join(out_dir, f"i{letter}.json"), "w", encoding="utf-8") as out:
            json.dump(table, out, separators=(",", ":"))
    # Chinese → English (meanings for the quiz): headword (traditional and simplified) → entry numbers, in files by the
    # headword's first character, like the readings.
    by_word = {}
    for i, e in enumerate(entries):
        for word in dict.fromkeys(w for w in e[:2] if w):
            by_word.setdefault(ord(word[0]) >> SHARD_BITS, {}).setdefault(word, []).append(i)
    for shard, table in by_word.items():
        with open(os.path.join(out_dir, f"c{shard:x}.json"), "w", encoding="utf-8") as out:
            json.dump(table, out, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    with open(os.path.join(out_dir, "meta.json"), "w") as out:
        json.dump({"chunk": DICT_CHUNK, "entries": len(entries)}, out)
    size = sum(os.path.getsize(os.path.join(out_dir, f)) for f in os.listdir(out_dir))
    print(f"{name}: {len(entries)} entries, {len(index)} English words, {size / 1048576:.1f} MB")


def rank_index(entries):
    """English word → entry numbers, best first: the word is the meaning, then in the first meaning, then easy words
    (level, field 6), then short ones."""
    def rank(i, t):
        e = entries[i]
        first = re.sub(r"\([^)]*\)", "", e[4].split(";")[0]).strip().lower()
        first = re.sub(r"^(to|an?|the) ", "", first)
        return (0 if first == t else 1, 0 if t in tokens(e[4].split(";")[0]) else 1, e[6], len(e[0]), len(e[4]))

    index = {}
    for i, e in enumerate(entries):
        for t in set(tokens(e[4])):
            index.setdefault(t, []).append(i)
    for t, ids in index.items():
        ids.sort(key=lambda i: rank(i, t))
    return index


HAN = re.compile(r"[\u3400-\u9fff\uf900-\ufaff\U00020000-\U0003ffff]")


def build_dictionary_ja():
    """English → Japanese: JMdict's common words [word, kana, "", "", glosses, 0, level], plus KANJIDIC2 kanji that
    aren't words of their own. level: the school grade of the word's hardest kanji (1-6; 7 secondary; 9 other)."""
    kd = kanjidic()

    def level(word):
        # Katakana loanwords (グー, スクール) after native words, which a learner usually wants first.
        if all("\u30a0" <= c <= "\u30ff" for c in word):
            return 5
        grades = []
        for c in word:
            if HAN.match(c):
                g = kd.get(c, {}).get("grade")
                grades.append(g if g and g <= 6 else 7 if g == 8 else 9)
        return max(grades, default=1)

    with tarfile.open(fetch("jmdict-eng-common.tgz")) as tar:
        member = next(m for m in tar.getmembers() if m.name.endswith(".json"))
        words = json.load(tar.extractfile(member))["words"]
    entries, seen = [], set()
    for w in words:
        kanji = [k["text"] for k in w["kanji"] if k.get("common")] or [k["text"] for k in w["kanji"]]
        kana = [k["text"] for k in w["kana"] if k.get("common")] or [k["text"] for k in w["kana"]]
        if not kana:
            continue
        # Words usually written in kana are entered as kana.
        usually_kana = all("uk" in s.get("misc", []) for s in w["sense"][:1])
        word = kanji[0] if kanji and not usually_kana else kana[0]
        if len(word) > 8:
            continue
        glosses = []
        for s in w["sense"][:4]:
            glosses += [g["text"] for g in s["gloss"] if g.get("lang", "eng") == "eng"][:3]
        if not glosses:
            continue
        entries.append([word, kana[0], "", "", "; ".join(glosses)[:120], 0, level(word)])
        seen.add(word)
    for ch, k in kd.items():
        if ch not in seen and k["meanings"] and k.get("grade"):
            kun = [r for r in k["ja"] if not ("\u30a0" <= r[0] <= "\u30ff")]
            entries.append([ch, (kun or k["ja"] or [""])[0], "", "", "; ".join(k["meanings"])[:120], 0, level(ch)])
    write_dictionary("dict-ja", entries, rank_index(entries))


def build_dictionary_ko():
    """English → Korean: kengdic's single words [hangul, hanja, "", "", glosses, 0, level]; level from kengdic's
    A-D (basic words first), 5 for the rest."""
    levels = {"A": 1, "B": 2, "C": 3, "D": 4}
    by_word = {}
    with open(fetch("kengdic.tsv"), encoding="utf-8") as f:
        next(f)
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 5:
                continue
            _, word, hanja, gloss, lvl = parts[:5]
            word, gloss = word.strip(), re.sub(r"\s+", " ", gloss).strip()
            if not gloss or not word or len(word) > 6 or not all("\uac00" <= c <= "\ud7a3" for c in word):
                continue
            hanja = hanja.strip() if hanja and all(HAN.match(c) for c in hanja.strip()) else ""
            key = (word, hanja)
            e = by_word.get(key)
            if e is None:
                by_word[key] = [word, hanja, "", "", gloss, 0, levels.get(lvl, 5)]
            elif gloss.lower() not in e[4].lower() and len(e[4]) < 100:
                # The meaning from the more basic list comes first (물 is "water" before "the color of something").
                if levels.get(lvl, 5) < e[6]:
                    e[4], e[6] = (gloss + "; " + e[4])[:120], levels[lvl]
                else:
                    e[4] = (e[4] + "; " + gloss)[:120]
    entries = list(by_word.values())
    write_dictionary("dict-ko", entries, rank_index(entries))


def copy_library():
    for path, data in npm_files("hanzi-writer.tgz"):
        if path == "dist/hanzi-writer.min.js":
            open(os.path.join(TRAINING, "hanzi-writer.min.js"), "wb").write(data)
        elif path == "LICENSE":
            open(os.path.join(TRAINING, "HANZI-WRITER-LICENSE.txt"), "wb").write(data)


if __name__ == "__main__":
    os.makedirs(TRAINING, exist_ok=True)
    build_stroke_bundle()
    build_cjk_bundles()
    build_readings()
    build_dictionary()
    build_dictionary_ja()
    build_dictionary_ko()
    copy_library()
    # Old layout: one file per character, and one big readings file.
    shutil.rmtree(os.path.join(ASSETS, "hanzi"), ignore_errors=True)
    old = os.path.join(TRAINING, "readings.json")
    if os.path.exists(old):
        os.remove(old)
