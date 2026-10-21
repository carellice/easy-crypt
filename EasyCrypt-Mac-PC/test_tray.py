"""Test del ciclo di vita e delle API Win32 simulate, senza password o impostazioni reali."""
import ctypes
import queue
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

import easycrypt
import tray


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        self.app = easycrypt.App.__new__(easycrypt.App)
        self.app.root = MagicMock()
        self.app.tray = MagicMock()
        self.app.hide_button = MagicMock()
        self.app.tray_ready = True
        self.app.tray_starting = False
        self.app.hide_when_ready = False
        self.app.closing = False
        self.app.busy = False
        self.app.pending = None
        self.app.set_status = MagicMock()

    def test_close_keeps_process_and_reopen_restores_window(self):
        self.app.close_window()
        self.app.root.withdraw.assert_called_once()
        self.app.root.destroy.assert_not_called()
        self.app.tray.stop.assert_not_called()
        self.app.tray.set_window_visible.assert_called_with(False)
        self.app.show_window()
        self.app.root.deiconify.assert_called_once()
        self.app.tray.set_window_visible.assert_called_with(True)

    def test_no_icon_never_hides_window(self):
        self.app.tray_ready = False
        self.app.hide_window()
        self.app.root.withdraw.assert_not_called()
        with patch.object(easycrypt.sys, 'platform', 'darwin'):
            self.app.close_window()
        self.app.root.destroy.assert_called_once()

    def test_windows_close_during_startup_waits_for_icon(self):
        self.app.tray_ready = False
        self.app.tray_starting = True
        with patch.object(easycrypt.sys, 'platform', 'win32'):
            self.app.close_window()
        self.app.root.destroy.assert_not_called()
        self.app.root.withdraw.assert_not_called()
        self.app.tray.start.assert_not_called()
        self.app.tray.poll.return_value = [('ready', None)]
        self.app.poll_tray()
        self.app.root.withdraw.assert_called_once()
        self.assertFalse(self.app.hide_when_ready)

    def test_windows_close_retries_failed_tray(self):
        self.app.tray_ready = False
        with patch.object(easycrypt.sys, 'platform', 'win32'):
            self.app.close_window()
        self.app.tray.stop.assert_called_once()
        self.app.tray.start.assert_called_once()
        self.app.root.destroy.assert_not_called()
        self.app.tray.poll.return_value = [('error', 'access denied')]
        self.app.poll_tray()
        self.app.root.deiconify.assert_called_once()
        self.app.root.withdraw.assert_not_called()
        self.assertFalse(self.app.hide_when_ready)

    def test_windows_close_with_synchronous_tray_failure_keeps_window(self):
        self.app.tray_ready = False
        self.app.tray.start.side_effect = OSError('tray unavailable')
        with patch.object(easycrypt.sys, 'platform', 'win32'):
            self.app.close_window()
        self.app.root.destroy.assert_not_called()
        self.app.root.withdraw.assert_not_called()
        self.assertFalse(self.app.tray_starting)
        self.assertFalse(self.app.hide_when_ready)

    def test_reopen_cancels_pending_hide(self):
        self.app.hide_when_ready = True
        self.app.show_window()
        self.app.tray.poll.return_value = [('ready', None)]
        self.app.poll_tray()
        self.app.root.withdraw.assert_not_called()

    def test_icon_failure_restores_hidden_window(self):
        self.app.tray.poll.return_value = [('error', 'tray unavailable')]
        self.app.poll_tray()
        self.assertFalse(self.app.tray_ready)
        self.app.root.deiconify.assert_called_once()
        self.app.hide_button.configure.assert_called_with(state='disabled')

    def test_exit_waits_for_clipboard_restore_and_cleans_up_once(self):
        self.app.busy = True
        self.app.quit()
        self.app.root.after.assert_called_with(100, self.app.quit)
        self.app.tray.stop.assert_not_called()
        self.app.busy = False
        self.app.pending = 'pending-update'
        self.app.quit()
        self.app.quit()
        self.app.tray.stop.assert_called_once()
        self.app.root.after_cancel.assert_called_once_with('pending-update')
        self.app.root.destroy.assert_called_once()

    def test_menu_actions_are_dispatched_on_tk_poll(self):
        self.app.tray.poll.return_value = [('ready', None), ('hide', None), ('show', None), ('quit', None)]
        self.app.poll_tray()
        self.app.root.withdraw.assert_called_once()
        self.app.root.deiconify.assert_called_once()
        self.app.root.destroy.assert_called_once()
        self.app.root.after.assert_not_called()

    def test_minimize_handles_only_the_main_window(self):
        self.app.root.state.return_value = 'iconic'
        self.app.minimized(SimpleNamespace(widget=object()))
        self.app.root.withdraw.assert_not_called()
        self.app.minimized(SimpleNamespace(widget=self.app.root))
        self.app.root.withdraw.assert_called_once()


class NativeWindowsTests(unittest.TestCase):
    def run_backend(self, fail_first_add=False, fail_initialization=False):
        events = queue.Queue()
        backend = tray._WindowsTray(events, 'test.ico')
        api = SimpleNamespace()
        api.procedure = None
        api.quit = False
        api.notifications = []
        api.messages = [(0x8001, 0x0202), (0x8001, 0x0205), (tray.SHOW_MESSAGE, 0), (0xC001, 0), (0x0010, 0)]
        def register(pointer):
            api.procedure = pointer._obj.procedure
            return 1
        def get_message(pointer, *args):
            if api.quit or not api.messages: return 0
            message, lp = api.messages.pop(0)
            pointer._obj.hWnd = 10
            pointer._obj.message = message
            pointer._obj.lParam = lp
            return 1
        def notify(kind, pointer):
            api.notifications.append((kind, pointer._obj.tip))
            return not (fail_first_add and len(api.notifications) == 1)
        def destroy(hwnd):
            api.procedure(hwnd, 2, 0, 0)
            return 1
        def quit_message(code): api.quit = True
        def get_cursor(pointer):
            pointer._obj.x, pointer._obj.y = 100, 200
            return 1
        implementations = {
            'RegisterClassW': register, 'CreateWindowExW': lambda *args: 10,
            'RegisterWindowMessageW': lambda *args: 0xC001, 'LoadImageW': lambda *args: 20,
            'GetMessageW': get_message,
            'DispatchMessageW': lambda pointer: api.procedure(10, pointer._obj.message, 0, pointer._obj.lParam),
            'DestroyWindow': destroy, 'PostQuitMessage': quit_message,
            'CreatePopupMenu': lambda: 30, 'GetCursorPos': get_cursor,
            'TrackPopupMenu': lambda *args: 3, 'GetModuleHandleW': lambda *args: 1,
            'Shell_NotifyIconW': notify,
        }
        functions = {}
        class Library:
            def __getattr__(self, name):
                if name not in functions:
                    functions[name] = MagicMock(side_effect=implementations.get(name, lambda *args: 1))
                return functions[name]
        def load(*args, **kwargs):
            if fail_initialization: raise OSError('DLL unavailable')
            return Library()
        with patch.object(ctypes, 'WinDLL', side_effect=load, create=True), \
                patch.object(ctypes, 'WINFUNCTYPE', ctypes.CFUNCTYPE, create=True):
            backend._run()
        return list(events.queue), api, functions

    def test_click_menu_explorer_restart_and_cleanup(self):
        events, api, functions = self.run_backend()
        self.assertEqual(events, [('ready', None), ('show', None), ('quit', None), ('show', None), ('ready', None)])
        self.assertEqual([kind for kind, _ in api.notifications][:2], [0, 0])
        self.assertIn(2, [kind for kind, _ in api.notifications])
        functions['DestroyIcon'].assert_called_once_with(20)
        functions['UnregisterClassW'].assert_called_once()
        self.assertIs(functions['CreateWindowExW'].restype, ctypes.c_void_p)
        self.assertIs(functions['DefWindowProcW'].restype, ctypes.c_ssize_t)
        functions['DestroyMenu'].assert_called_once_with(30)

    def test_failed_icon_add_can_recover_when_explorer_starts(self):
        events, _, _ = self.run_backend(fail_first_add=True)
        self.assertEqual(events[0][0], 'error')
        self.assertIn(('ready', None), events)

    def test_initialization_failure_is_reported_to_ui(self):
        events, _, _ = self.run_backend(fail_initialization=True)
        self.assertEqual(events, [('error', 'DLL unavailable')])


class SingleInstanceTests(unittest.TestCase):
    def claim(self, last_error, window=10):
        functions = {}
        class Library:
            def __getattr__(self, name):
                if name not in functions:
                    functions[name] = MagicMock(return_value={'CreateMutexW': 5, 'FindWindowW': window}.get(name, 1))
                return functions[name]
        with patch.object(tray.sys, 'platform', 'win32'), \
                patch.object(ctypes, 'WinDLL', side_effect=lambda *a, **k: Library(), create=True), \
                patch.object(ctypes, 'get_last_error', return_value=last_error, create=True), \
                patch.object(tray.time, 'sleep'), patch.object(tray, '_instance_lock', []):
            return tray.claim_single_instance(), functions

    def test_first_copy_keeps_the_lock(self):
        started, functions = self.claim(0)
        self.assertTrue(started)
        functions['PostMessageW'].assert_not_called()

    def test_second_copy_shows_the_first_and_exits(self):
        started, functions = self.claim(183)
        self.assertFalse(started)
        functions['PostMessageW'].assert_called_once_with(10, tray.SHOW_MESSAGE, 0, 0)
        functions['AllowSetForegroundWindow'].assert_called_once()

    def test_second_copy_starts_if_the_first_never_answers(self):
        started, functions = self.claim(183, window=None)
        self.assertTrue(started)
        functions['PostMessageW'].assert_not_called()


if __name__ == '__main__':
    unittest.main()
