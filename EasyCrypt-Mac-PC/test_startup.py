"""Verifica l'avvio automatico senza modificare le impostazioni del computer."""
from pathlib import Path
import plistlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import startup


class StartupTests(unittest.TestCase):
    def test_mac_enable_disable(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(startup.sys, 'platform', 'darwin'), \
                patch.object(startup, 'agent_path', return_value=Path(folder) / 'Agents' / 'test.plist'), \
                patch.object(startup, 'launch_command', return_value=['/usr/bin/open', '/Applications/Easy Crypt.app']):
            self.assertFalse(startup.enabled())
            startup.set_enabled(True)
            self.assertTrue(startup.enabled())
            with startup.agent_path().open('rb') as stream:
                data = plistlib.load(stream)
            self.assertEqual(data['ProgramArguments'], ['/usr/bin/open', '/Applications/Easy Crypt.app'])
            self.assertTrue(data['RunAtLoad'])
            self.assertNotIn('KeepAlive', data)
            startup.set_enabled(False)
            startup.set_enabled(False)
            self.assertFalse(startup.enabled())

    def test_packaged_commands(self):
        with patch.object(startup.sys, 'frozen', True, create=True), \
                patch.object(startup.sys, 'platform', 'darwin'), \
                patch.object(startup.sys, 'executable', '/Applications/Easy Crypt.app/Contents/MacOS/EasyCrypt'):
            self.assertEqual(startup.launch_command(), ['/usr/bin/open', str(Path('/Applications/Easy Crypt.app').resolve())])
        with patch.object(startup.sys, 'frozen', True, create=True), \
                patch.object(startup.sys, 'platform', 'win32'), \
                patch.object(startup.sys, 'executable', '/tmp/Easy Crypt/EasyCrypt.exe'):
            self.assertEqual(startup.launch_command(), [str(Path('/tmp/Easy Crypt/EasyCrypt.exe').resolve())])

    def test_windows_registry(self):
        values = {}
        class Key:
            def __enter__(self): return self
            def __exit__(self, *args): pass
        def query(key, name):
            if name not in values: raise FileNotFoundError(name)
            return values[name], 1
        def delete(key, name):
            if name not in values: raise FileNotFoundError(name)
            del values[name]
        registry = SimpleNamespace(HKEY_CURRENT_USER=1, KEY_SET_VALUE=2, REG_SZ=1,
            OpenKey=lambda *args: Key(), CreateKeyEx=lambda *args: Key(), QueryValueEx=query,
            SetValueEx=lambda key, name, reserved, kind, value: values.update({name: value}), DeleteValue=delete)
        command = [r'C:\Program Files\EasyCrypt\EasyCrypt.exe']
        with patch.object(startup.sys, 'platform', 'win32'), patch.dict('sys.modules', winreg=registry), \
                patch.object(startup, 'launch_command', return_value=command):
            self.assertFalse(startup.enabled())
            startup.set_enabled(True)
            self.assertEqual(values['EasyCrypt'], subprocess.list2cmdline(command))
            self.assertTrue(startup.enabled())
            startup.set_enabled(False)
            startup.set_enabled(False)
            self.assertFalse(startup.enabled())
            with patch.object(startup, 'launch_command', return_value=['x' * 261]):
                with self.assertRaises(OSError): startup.set_enabled(True)
            self.assertFalse(startup.enabled())


if __name__ == '__main__':
    unittest.main()
