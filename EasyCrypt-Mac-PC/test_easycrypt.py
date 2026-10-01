"""Verifica la compatibilità con la web app: `python3 test_easycrypt.py`."""
import easycrypt_core as ec

# Testi cifrati dalla web app EasyCrypt (gli stessi usati nei test della tastiera Android).
WEB = '8KLLgNkK0VXGB2IVq4w4EBOS/VsUveh82aK9gqGZrRAMSHffmP0a2CvI9BB7TtiVgS3xD2VYqYO1AD6l8qGtvwjfb8zQbhU='
WEB_UNICODE = 'NHNplYLyJlf2JCPTbvO2xHACNefAIEu6XbxV+dNS6g4OB/8vGrS3BNcyYJLZST7fnuljzRBEMbd6pts='


def fails(function, *args, **kwargs):
    try:
        function(*args, **kwargs)
    except Exception:
        return True
    return False


modes = [True] + ([False] if ec.AESGCM else [])  # puro Python sempre; `cryptography` se installato
for pure in modes:
    assert ec.decrypt(WEB, 'password123', pure=pure) == 'Ciao mondo! àèìòù 🔐'
    assert ec.decrypt(WEB_UNICODE, 'pässwörd€', pure=pure) == 'test unicode pw'
    assert fails(ec.decrypt, WEB, 'sbagliata', pure=pure)
    assert fails(ec.decrypt, WEB[:-6] + 'AAAAA=', 'password123', pure=pure)
    assert fails(ec.decrypt, 'non è cifrato', 'password123', pure=pure)

    for text in ['', 'a', 'Ci vediamo alle 8 🍕', 'x' * 16, 'riga\n' * 300]:
        first = ec.encrypt(text, 'segreta', pure=pure)
        assert first != ec.encrypt(text, 'segreta', pure=pure)  # IV nuovo a ogni cifratura
        assert ec.decrypt(f'  {first}\n', 'segreta', pure=pure) == text
        # Le due implementazioni devono capirsi a vicenda.
        for other in modes:
            assert ec.decrypt(first, 'segreta', pure=other) == text

assert ec.looks_encrypted(WEB)
assert not ec.looks_encrypted('Ci vediamo alle 8')
print('OK', '(puro Python + cryptography)' if ec.AESGCM else '(puro Python)')
