#!/usr/bin/env python3
"""Genera app/src/main/assets/emoji.txt (emoji per categoria) da emoji-test.txt di Unicode.

Uso: python3 tools/build_emoji_asset.py percorso/emoji-test.txt
(https://unicode.org/Public/emoji/15.1/emoji-test.txt)
"""
import os
import sys

source = sys.argv[1]
target = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "emoji.txt")

groups = []
current = None
for line in open(source, encoding="utf-8"):
    line = line.strip()
    if line.startswith("# group:"):
        name = line.split(":", 1)[1].strip()
        current = None if name == "Component" else (name, [])
        if current:
            groups.append(current)
    elif line and not line.startswith("#") and current and "; fully-qualified" in line:
        codepoints = [int(x, 16) for x in line.split(";")[0].split()]
        # Le varianti di tonalità della pelle non vengono elencate.
        if any(0x1F3FB <= c <= 0x1F3FF for c in codepoints):
            continue
        current[1].append("".join(chr(c) for c in codepoints))

with open(target, "w", encoding="utf-8") as out:
    for name, items in groups:
        out.write("# " + name + "\n" + " ".join(items) + "\n")
        print(name, len(items))
