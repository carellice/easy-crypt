"""Algoritmo di EasyCrypt, identico alla web app e alla tastiera Android.

PBKDF2-HMAC-SHA256 (100000 iterazioni) -> AES-256-GCM, output base64(salt[16] + iv[12] + ciphertext + tag[16]).

Funziona con la sola libreria standard di Python. Se è installato il pacchetto `cryptography` lo usa per AES-GCM,
altrimenti usa l'implementazione in puro Python qui sotto (più lenta, ma i testi sono brevi).
"""
import base64
import hashlib
import hmac
import os
import re

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except Exception:  # pacchetto assente o non utilizzabile
    AESGCM = None

SALT_LENGTH = 16
IV_LENGTH = 12
TAG_LENGTH = 16
ITERATIONS = 100000


# --- AES-256-GCM in puro Python (serve solo la cifratura del blocco: GCM usa AES in modalità contatore) ---

def _build_sbox():
    # Inverso moltiplicativo in GF(2^8) seguito dalla trasformazione affine.
    exp, log = [0] * 255, [0] * 256
    value = 1
    for i in range(255):
        exp[i], log[value] = value, i
        value ^= (value << 1) ^ (0x11B if value & 0x80 else 0)  # moltiplica per 3
        value &= 0xFF
    sbox = [0x63] * 256
    for n in range(1, 256):
        inv = exp[(255 - log[n]) % 255]
        s = inv
        for shift in range(1, 5):
            s ^= ((inv << shift) | (inv >> (8 - shift))) & 0xFF
        sbox[n] = s ^ 0x63
    return sbox


_SBOX = _build_sbox()
_MUL2 = [((n << 1) ^ (0x11B if n & 0x80 else 0)) & 0xFF for n in range(256)]


def _expand_key(key):
    words = [list(key[i:i + 4]) for i in range(0, 32, 4)]
    rcon = 1
    for i in range(8, 60):
        word = list(words[i - 1])
        if i % 8 == 0:
            word = [_SBOX[b] for b in word[1:] + word[:1]]
            word[0] ^= rcon
            rcon = _MUL2[rcon]
        elif i % 8 == 4:
            word = [_SBOX[b] for b in word]
        words.append([a ^ b for a, b in zip(words[i - 8], word)])
    return [sum(words[r * 4:r * 4 + 4], []) for r in range(15)]


_SHIFT = [(r + 4 * ((c + r) % 4)) for c in range(4) for r in range(4)]  # SubBytes + ShiftRows: indice sorgente


def _encrypt_block(round_keys, block):
    state = [b ^ k for b, k in zip(block, round_keys[0])]
    for rnd in range(1, 15):
        state = [_SBOX[state[i]] for i in _SHIFT]
        if rnd < 14:
            mixed = []
            for c in range(0, 16, 4):
                a0, a1, a2, a3 = state[c:c + 4]
                total = a0 ^ a1 ^ a2 ^ a3
                mixed += [
                    a0 ^ total ^ _MUL2[a0 ^ a1],
                    a1 ^ total ^ _MUL2[a1 ^ a2],
                    a2 ^ total ^ _MUL2[a2 ^ a3],
                    a3 ^ total ^ _MUL2[a3 ^ a0],
                ]
            state = mixed
        state = [s ^ k for s, k in zip(state, round_keys[rnd])]
    return bytes(state)


def _gf_multiply(x, y):
    z = 0
    for i in range(127, -1, -1):
        if (x >> i) & 1:
            z ^= y
        y = (y >> 1) ^ (0xE1 << 120) if y & 1 else y >> 1
    return z


class _PureAESGCM:
    """Stessa interfaccia di cryptography.AESGCM, per chiavi da 32 byte e IV da 12."""

    def __init__(self, key):
        self._keys = _expand_key(key)
        self._h = int.from_bytes(_encrypt_block(self._keys, bytes(16)), 'big')

    def _ctr(self, iv, data):
        out = bytearray()
        for n, start in enumerate(range(0, len(data), 16)):
            stream = _encrypt_block(self._keys, iv + (n + 2).to_bytes(4, 'big'))
            out += bytes(a ^ b for a, b in zip(data[start:start + 16], stream))
        return bytes(out)

    def _tag(self, iv, ciphertext):
        y = 0
        padded = ciphertext + bytes(-len(ciphertext) % 16) + bytes(8) + (len(ciphertext) * 8).to_bytes(8, 'big')
        for start in range(0, len(padded), 16):
            y = _gf_multiply(y ^ int.from_bytes(padded[start:start + 16], 'big'), self._h)
        mask = _encrypt_block(self._keys, iv + b'\x00\x00\x00\x01')
        return bytes(a ^ b for a, b in zip(y.to_bytes(16, 'big'), mask))

    def encrypt(self, iv, data, associated_data=None):
        ciphertext = self._ctr(iv, data)
        return ciphertext + self._tag(iv, ciphertext)

    def decrypt(self, iv, data, associated_data=None):
        if len(data) < TAG_LENGTH:
            raise ValueError('testo troppo corto')
        ciphertext, tag = data[:-TAG_LENGTH], data[-TAG_LENGTH:]
        if not hmac.compare_digest(tag, self._tag(iv, ciphertext)):
            raise ValueError('password errata o testo non valido')
        return self._ctr(iv, ciphertext)


# --- EasyCrypt ---

_cached = None  # (password, salt, cifrario): derivare la chiave è la parte lenta
_session = None  # (password, salt) usato per cifrare finché la password non cambia


def _cipher(password, salt, pure=False):
    global _cached
    if _cached and _cached[:2] == (password, salt) and isinstance(_cached[2], _PureAESGCM) == (pure or AESGCM is None):
        return _cached[2]
    key = hashlib.pbkdf2_hmac('sha256', password.encode('utf-8'), salt, ITERATIONS, 32)
    cipher = _PureAESGCM(key) if pure or AESGCM is None else AESGCM(key)
    _cached = (password, salt, cipher)
    return cipher


def encrypt(plaintext, password, pure=False):
    """Cifra un testo. `pure=True` forza l'implementazione in puro Python (usato dai test)."""
    global _session
    if not _session or _session[0] != password:
        _session = (password, os.urandom(SALT_LENGTH))
    salt = _session[1]
    iv = os.urandom(IV_LENGTH)
    encrypted = _cipher(password, salt, pure).encrypt(iv, plaintext.encode('utf-8'), None)
    return base64.b64encode(salt + iv + encrypted).decode('ascii')


def decrypt(ciphertext, password, pure=False):
    """Decifra un testo; solleva un'eccezione se la password è errata o il testo non è valido."""
    data = base64.b64decode(ciphertext.strip(), validate=True)
    salt, iv, encrypted = data[:SALT_LENGTH], data[SALT_LENGTH:SALT_LENGTH + IV_LENGTH], data[SALT_LENGTH + IV_LENGTH:]
    if len(iv) < IV_LENGTH:
        raise ValueError('testo troppo corto')
    return _cipher(password, salt, pure).decrypt(iv, encrypted, None).decode('utf-8')


def looks_encrypted(text):
    """Il testo ha la forma di un testo cifrato da EasyCrypt (base64 abbastanza lungo)."""
    value = text.strip()
    return len(value) >= 60 and len(value) % 4 == 0 and re.fullmatch(r'[A-Za-z0-9+/]+={0,2}', value) is not None
