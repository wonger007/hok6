#!/usr/bin/env python3
"""
Rebuilds the app's character data from its original sources.

  app/src/main/assets/hanzi.bin           stroke data for every character in hanzi-writer-data, in one file
  app/src/main/assets/training/readings/  Jyutping + Pinyin for every character with a known reading,
                                          split into small files by Unicode block so only what is needed is loaded
  app/src/main/assets/training/dict/      English ↔ Chinese dictionary (entries in chunks + English word index by letter
                                          + Chinese headword index by first character)
  app/src/main/assets/training/           hanzi-writer.min.js and the licences that must ship with the data

Sources (downloaded into tools/.cache):
  hanzi-writer, hanzi-writer-data (npm)   https://github.com/chanind/hanzi-writer(-data)
  rime-cantonese jyut6ping3.chars         https://github.com/rime/rime-cantonese   (Jyutping, ordered by frequency)
  Unicode Unihan_Readings.txt             https://www.unicode.org/charts/unihan.html (kMandarin, kCantonese)
  CC-CEDICT (MDBG)                        https://www.mdbg.net/chinese/dictionary?page=cedict  (CC BY-SA 4.0)
  CC-Canto + CC-CEDICT Cantonese readings https://cantonese.org  (CC BY-SA 3.0)

hanzi.bin layout (little-endian):
  "HZB1", u32 count, count x (u32 codepoint, u32 offset, u32 length) sorted by codepoint,
  then each character's JSON compressed with zlib. Offsets are from the start of the file.

Usage: python3 tools/build_assets.py
"""
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

SOURCES = {
    "hanzi-writer.tgz": "https://registry.npmjs.org/hanzi-writer/-/hanzi-writer-3.7.3.tgz",
    "hanzi-writer-data.tgz": "https://registry.npmjs.org/hanzi-writer-data/-/hanzi-writer-data-2.0.1.tgz",
    "jyut6ping3.chars.dict.yaml": "https://raw.githubusercontent.com/rime/rime-cantonese/main/jyut6ping3.chars.dict.yaml",
    "Unihan.zip": "https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip",
    "cedict.zip": "https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.zip",
    "cccanto.zip": "https://cantonese.org/cccanto-170202.zip",
    "cccedict-canto-readings.zip": "https://cantonese.org/cccedict-canto-readings-150923.zip",
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
    codepoints = sorted(entries)
    header = 8 + 12 * len(codepoints)
    blobs, index, offset = [], [], header
    for cp in codepoints:
        blob = zlib.compress(entries[cp], 9)
        index.append(struct.pack("<III", cp, offset, len(blob)))
        blobs.append(blob)
        offset += len(blob)
    with open(os.path.join(ASSETS, "hanzi.bin"), "wb") as out:
        out.write(b"HZB1" + struct.pack("<I", len(codepoints)))
        out.writelines(index)
        out.writelines(blobs)
    with open(os.path.join(TRAINING, "ARPHICPL.TXT"), "wb") as out:
        out.write(licence)
    print(f"hanzi.bin: {len(codepoints)} characters, {offset / 1048576:.1f} MB")


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

    shards = {}
    for ch in set(jyutping) | set(mandarin) | set(cantonese):
        yue = [r for _, _, r in sorted(jyutping[ch])] if ch in jyutping else cantonese.get(ch, [])
        yue = list(dict.fromkeys(yue))
        shards.setdefault(ord(ch) >> SHARD_BITS, {})[ch] = " ".join(yue) + "|" + " ".join(mandarin.get(ch, []))

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

    out_dir = os.path.join(TRAINING, "dict")
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
        for word in dict.fromkeys(e[:2]):
            by_word.setdefault(ord(word[0]) >> SHARD_BITS, {}).setdefault(word, []).append(i)
    for shard, table in by_word.items():
        with open(os.path.join(out_dir, f"c{shard:x}.json"), "w", encoding="utf-8") as out:
            json.dump(table, out, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    with open(os.path.join(out_dir, "meta.json"), "w") as out:
        json.dump({"chunk": DICT_CHUNK, "entries": len(entries)}, out)
    size = sum(os.path.getsize(os.path.join(out_dir, f)) for f in os.listdir(out_dir))
    print(f"dictionary: {len(entries)} entries, {len(index)} English words, {size / 1048576:.1f} MB")


def copy_library():
    for path, data in npm_files("hanzi-writer.tgz"):
        if path == "dist/hanzi-writer.min.js":
            open(os.path.join(TRAINING, "hanzi-writer.min.js"), "wb").write(data)
        elif path == "LICENSE":
            open(os.path.join(TRAINING, "HANZI-WRITER-LICENSE.txt"), "wb").write(data)


if __name__ == "__main__":
    os.makedirs(TRAINING, exist_ok=True)
    build_stroke_bundle()
    build_readings()
    build_dictionary()
    copy_library()
    # Old layout: one file per character, and one big readings file.
    shutil.rmtree(os.path.join(ASSETS, "hanzi"), ignore_errors=True)
    old = os.path.join(TRAINING, "readings.json")
    if os.path.exists(old):
        os.remove(old)
