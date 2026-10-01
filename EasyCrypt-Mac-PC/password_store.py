"""Salvataggio facoltativo della password, protetta dal sistema operativo.

- Mac: Portachiavi, tramite il comando di sistema `security`.
- Windows: file cifrato con DPAPI (leggibile solo dall'utente di Windows che l'ha salvato).
- Altri sistemi: non disponibile, la password resta solo in memoria.
"""
import base64
import os
import subprocess
import sys

SERVICE = 'EasyCrypt'
ACCOUNT = 'password'


def available():
    return sys.platform in ('darwin', 'win32')


def load(service=SERVICE):
    """Password salvata, oppure stringa vuota."""
    try:
        if sys.platform == 'darwin':
            result = subprocess.run(
                ['security', 'find-generic-password', '-s', service, '-a', ACCOUNT, '-w'],
                capture_output=True, text=True,
            )
            return base64.b64decode(result.stdout.strip()).decode('utf-8') if result.returncode == 0 else ''
        if sys.platform == 'win32':
            with open(_windows_file(service), 'rb') as f:
                return _dpapi(f.read(), protect=False).decode('utf-8')
    except Exception:
        pass
    return ''


def save(password, service=SERVICE):
    """Salva la password; una password vuota la rimuove."""
    if not password:
        return forget(service)
    try:
        if sys.platform == 'darwin':
            # In base64 (niente caratteri da proteggere) e passata sullo standard input, non tra gli argomenti
            # del comando, che sarebbero visibili agli altri programmi.
            encoded = base64.b64encode(password.encode('utf-8')).decode('ascii')
            command = f'add-generic-password -U -s {service} -a {ACCOUNT} -w {encoded}\n'
            subprocess.run(['security', '-i'], input=command, capture_output=True, text=True)
        elif sys.platform == 'win32':
            path = _windows_file(service)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, 'wb') as f:
                f.write(_dpapi(password.encode('utf-8'), protect=True))
    except Exception:
        pass


def forget(service=SERVICE):
    try:
        if sys.platform == 'darwin':
            subprocess.run(['security', 'delete-generic-password', '-s', service, '-a', ACCOUNT], capture_output=True)
        elif sys.platform == 'win32':
            os.remove(_windows_file(service))
    except Exception:
        pass


def _windows_file(service):
    return os.path.join(os.environ.get('APPDATA', os.path.expanduser('~')), service, 'password.bin')


def _dpapi(data, protect):
    import ctypes
    from ctypes import wintypes

    class Blob(ctypes.Structure):
        _fields_ = [('cbData', wintypes.DWORD), ('pbData', ctypes.POINTER(ctypes.c_char))]

    buffer = ctypes.create_string_buffer(data, len(data))
    source = Blob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_char)))
    target = Blob()
    function = ctypes.windll.crypt32.CryptProtectData if protect else ctypes.windll.crypt32.CryptUnprotectData
    if not function(ctypes.byref(source), None, None, None, None, 0, ctypes.byref(target)):
        raise OSError('DPAPI non riuscita')
    try:
        return ctypes.string_at(target.pbData, target.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree.argtypes = [ctypes.c_void_p]
        ctypes.windll.kernel32.LocalFree(ctypes.cast(target.pbData, ctypes.c_void_p))
