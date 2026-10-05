#!/usr/bin/env python3
"""
Rebuilds the app's character data from its original sources.

  app/src/main/assets/hanzi.bin           stroke data for every character in hanzi-writer-data, in one file
  app/src/main/assets/training/readings/  Jyutping + Pinyin for every character with a known reading,
                                          split into small files by Unicode block so only what is needed is loaded
  app/src/main/assets/training/           hanzi-writer.min.js and the licences that must ship with the data

Sources (downloaded into tools/.cache):
  hanzi-writer, hanzi-writer-data (npm)   https://github.com/chanind/hanzi-writer(-data)
  rime-cantonese jyut6ping3.chars         https://github.com/rime/rime-cantonese   (Jyutping, ordered by frequency)
  Unicode Unihan_Readings.txt             https://www.unicode.org/charts/unihan.html (kMandarin, kCantonese)

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
}

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
    copy_library()
    # Old layout: one file per character, and one big readings file.
    shutil.rmtree(os.path.join(ASSETS, "hanzi"), ignore_errors=True)
    old = os.path.join(TRAINING, "readings.json")
    if os.path.exists(old):
        os.remove(old)
