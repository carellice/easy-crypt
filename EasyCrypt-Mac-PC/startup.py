"""Avvio al login dell'utente: LaunchAgent su macOS, chiave Run su Windows.

La registrazione avviene solo quando l'utente abilita l'opzione nell'app.
"""
from pathlib import Path
import os
import plistlib
import subprocess
import sys

LABEL = 'com.easycrypt.startup'
RUN_KEY = r'Software\Microsoft\Windows\CurrentVersion\Run'
VALUE_NAME = 'EasyCrypt'


def available():
    return sys.platform in ('darwin', 'win32')


def launch_command():
    if getattr(sys, 'frozen', False):
        executable = Path(sys.executable).resolve()
        if sys.platform == 'darwin':
            for parent in executable.parents:
                if parent.suffix == '.app':
                    return ['/usr/bin/open', str(parent)]
        return [str(executable)]
    executable = Path(sys.executable).resolve()
    if sys.platform == 'win32':
        windowed = executable.with_name('pythonw.exe')
        if windowed.exists():
            executable = windowed
    return [str(executable), str(Path(__file__).resolve().with_name('easycrypt.py'))]


def agent_path():
    return Path.home() / 'Library' / 'LaunchAgents' / f'{LABEL}.plist'


def enabled():
    if sys.platform == 'darwin':
        return agent_path().is_file()
    if sys.platform == 'win32':
        import winreg
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
                value, _ = winreg.QueryValueEx(key, VALUE_NAME)
                return bool(value)
        except FileNotFoundError:
            return False
    return False


def set_enabled(enable):
    if sys.platform == 'darwin':
        path = agent_path()
        if enable:
            path.parent.mkdir(parents=True, exist_ok=True)
            temporary = path.with_suffix('.tmp')
            try:
                with temporary.open('wb') as stream:
                    plistlib.dump({'Label': LABEL, 'ProgramArguments': launch_command(),
                                  'RunAtLoad': True}, stream)
                os.replace(temporary, path)
            finally:
                temporary.unlink(missing_ok=True)
        else:
            path.unlink(missing_ok=True)
        # Il file verrà caricato al prossimo login: non avvia una seconda copia ora.
    elif sys.platform == 'win32':
        import winreg
        with winreg.CreateKeyEx(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
            if enable:
                command = subprocess.list2cmdline(launch_command())
                if len(command) > 260:
                    raise OSError('Percorso troppo lungo per l’avvio automatico di Windows. Sposta EasyCrypt in una cartella con un percorso più breve.')
                winreg.SetValueEx(key, VALUE_NAME, 0, winreg.REG_SZ, command)
            else:
                try:
                    winreg.DeleteValue(key, VALUE_NAME)
                except FileNotFoundError:
                    pass
    else:
        raise OSError('Avvio automatico non supportato su questo sistema.')
