#!/usr/bin/env python3
"""Genera app/src/main/assets/words_it.txt: parole italiane dalla più frequente alla meno frequente.

Uso: python3 tools/build_dictionary.py it_full.txt nomi_propri.txt elenco_valide.txt [altri elenchi...]

- it_full.txt: frequenze da https://github.com/hermitdave/FrequencyWords (content/2018/it)
- nomi propri ed elenchi di parole valide da https://github.com/napolux/paroleitaliane
  (9000_nomi_propri.txt, 660000_parole_italiane.txt, 280000_parole_italiane.txt, 95000_parole_italiane_con_nomi_propri.txt)

Le frequenze vengono dai sottotitoli, che contengono errori (perche, piu, citta...) e parole straniere.
Gli elenchi di parole valide sono incompleti (mancano parole comuni), quindi:
- le parole più frequenti si tengono tutte, tolte quelle scritte senza accento o con l'accento sbagliato;
- le parole rare si tengono solo se compaiono in un elenco di parole valide.
Tutte le parole sono in minuscolo, nomi propri compresi (l'elenco dei nomi contiene anche parole comuni).
"""
import os
import sys
import unicodedata

frequency_file, names_file, *valid_files = sys.argv[1:]
target = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "words_it.txt")

TRUSTED = 50000  # le prime parole per frequenza sono quasi tutte parole vere
MIN_COUNT = 2
LETTERS = set("abcdefghijklmnopqrstuvwxyzàèéìòù")
KEEP_PLAIN = {"papa", "meta", "sara", "faro", "casino"}  # parole vere anche senza accento


def read(path):
    with open(path, encoding="utf-8", errors="ignore") as f:
        return [line.strip() for line in f if line.strip()]


def strip_accents(word):
    return "".join(c for c in unicodedata.normalize("NFD", word) if unicodedata.category(c) != "Mn")


valid = set()
common = set()  # parole comuni: gli elenchi "con nomi propri" non dicono se una parola è un nome
for path in valid_files:
    entries = {w.lower() for w in read(path)}
    valid.update(entries)
    if "nomi_propri" not in os.path.basename(path):
        common.update(entries)
names = {w.lower() for w in read(names_file) if w.isalpha()}

rows = []
for line in read(frequency_file):
    parts = line.split()
    if len(parts) == 2 and set(parts[0]) <= LETTERS and int(parts[1]) >= MIN_COUNT:
        rows.append((parts[0], int(parts[1])))
count = dict(rows)

# Varianti accentate di ogni forma senza accenti (perche -> perché, perchè).
accented = {}
for word, _ in rows:
    plain = strip_accents(word)
    if plain != word:
        accented.setdefault(plain, []).append(word)

words = []
seen = set()
for position, (word, n) in enumerate(rows):
    if word in seen:
        continue
    plain = strip_accents(word)
    if plain == word:
        # Scritta senza accento (piu, citta): si scarta se la forma accentata è frequente quanto lei.
        variants = accented.get(word, [])
        if len(word) >= 3 and word not in KEEP_PLAIN and word not in valid and variants and n < 2 * max(count[v] for v in variants):
            continue
    else:
        # Accento sbagliato (perchè): tra le varianti si tiene quella valida, o la più frequente.
        variants = accented[plain]
        best = max(variants, key=lambda v: (v in valid, count[v]))
        if word != best and word not in valid:
            continue
    if position >= TRUSTED and word not in valid and word not in names:
        continue
    if len(word) <= 2 and position >= 2000 and word not in common:
        continue  # le parole brevi rare sono sigle o errori
    seen.add(word)
    words.append(word)

with open(target, "w", encoding="utf-8") as out:
    out.write("\n".join(words) + "\n")
print(len(words), "parole")
