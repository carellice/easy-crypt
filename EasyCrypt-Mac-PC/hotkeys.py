"""Scorciatoie globali e tasti simulati, con la sola libreria standard (ctypes).

- Mac: scorciatoie con Carbon (RegisterEventHotKey), tasti con CoreGraphics (serve il permesso «Accessibilità»).
- Windows: scorciatoie con RegisterHotKey su un thread dedicato, tasti con keybd_event.
"""
import ctypes
import queue
import subprocess
import sys
import threading

MAC = sys.platform == 'darwin'
WINDOWS = sys.platform == 'win32'
available = MAC or WINDOWS

ENCRYPT, DECRYPT = 1, 2
# Mac: Control+Opzione+Comando (Control+Opzione da solo è usato dai gestori di finestre come Rectangle). Windows: Ctrl+Alt+Maiusc (Ctrl+Alt da solo è AltGr, che con E scrive €).
LABELS = {ENCRYPT: '⌃⌥⌘E', DECRYPT: '⌃⌥⌘D'} if MAC else {ENCRYPT: 'Ctrl+Alt+Maiusc+E', DECRYPT: 'Ctrl+Alt+Maiusc+D'}

_keep = []  # riferimenti che ctypes non deve liberare


# --- Mac ---

if MAC:
    _carbon = ctypes.CDLL('/System/Library/Frameworks/Carbon.framework/Carbon')
    _app_services = ctypes.CDLL('/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices')

    class _HotKeyID(ctypes.Structure):
        _fields_ = [('signature', ctypes.c_uint32), ('id', ctypes.c_uint32)]

    class _EventType(ctypes.Structure):
        _fields_ = [('eventClass', ctypes.c_uint32), ('eventKind', ctypes.c_uint32)]

    _Handler = ctypes.CFUNCTYPE(ctypes.c_int32, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p)

    _carbon.GetApplicationEventTarget.restype = ctypes.c_void_p
    _carbon.InstallEventHandler.argtypes = [ctypes.c_void_p, _Handler, ctypes.c_uint32, ctypes.POINTER(_EventType),
                                            ctypes.c_void_p, ctypes.c_void_p]
    _carbon.RegisterEventHotKey.argtypes = [ctypes.c_uint32, ctypes.c_uint32, _HotKeyID, ctypes.c_void_p,
                                            ctypes.c_uint32, ctypes.POINTER(ctypes.c_void_p)]
    _carbon.GetEventParameter.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p,
                                          ctypes.c_ulong, ctypes.c_void_p, ctypes.c_void_p]
    _app_services.CGEventCreateKeyboardEvent.restype = ctypes.c_void_p
    _app_services.CGEventCreateKeyboardEvent.argtypes = [ctypes.c_void_p, ctypes.c_uint16, ctypes.c_bool]
    _app_services.CGEventSetFlags.argtypes = [ctypes.c_void_p, ctypes.c_uint64]
    _app_services.CGEventPost.argtypes = [ctypes.c_uint32, ctypes.c_void_p]
    _app_services.CFRelease.argtypes = [ctypes.c_void_p]
    _app_services.CGEventSourceFlagsState.restype = ctypes.c_uint64
    _app_services.CGEventSourceFlagsState.argtypes = [ctypes.c_int32]
    _app_services.AXIsProcessTrusted.restype = ctypes.c_bool

    _KEY_C, _KEY_V, _KEY_D, _KEY_E = 8, 9, 2, 14
    _CONTROL, _OPTION, _COMMAND = 0x1000, 0x0800, 0x0100  # modificatori Carbon
    _COMMAND_FLAG = 0x100000
    _MODIFIER_FLAGS = 0x20000 | 0x40000 | 0x80000 | 0x100000  # maiuscole, control, opzione, comando

    def _fourcc(code):
        return int.from_bytes(code.encode('ascii'), 'big')

    def _start_mac(events):
        # Il gestore viene chiamato dal sistema dentro il ciclo di Tk: qui non si deve toccare tkinter
        # (manderebbe in crash Python), quindi mette solo l'evento in coda.
        def handler(_call, event, _data):
            hot_key = _HotKeyID()
            _carbon.GetEventParameter(event, _fourcc('----'), _fourcc('hkid'), None, ctypes.sizeof(hot_key), None,
                                      ctypes.byref(hot_key))
            events.put(hot_key.id)
            return 0

        function = _Handler(handler)
        kind = _EventType(_fourcc('keyb'), 5)  # tasto di scelta rapida premuto
        _keep.extend([function, kind])
        target = _carbon.GetApplicationEventTarget()
        _carbon.InstallEventHandler(target, function, 1, ctypes.byref(kind), None, None)
        registered = True
        for action, key in ((ENCRYPT, _KEY_E), (DECRYPT, _KEY_D)):
            reference = ctypes.c_void_p()
            status = _carbon.RegisterEventHotKey(key, _CONTROL | _OPTION | _COMMAND, _HotKeyID(_fourcc('EsCr'), action), target, 0,
                                                 ctypes.byref(reference))
            registered = registered and status == 0
            _keep.append(reference)
        return registered

    def _press_mac(key):
        for down in (True, False):
            event = _app_services.CGEventCreateKeyboardEvent(None, key, down)
            _app_services.CGEventSetFlags(event, _COMMAND_FLAG)
            _app_services.CGEventPost(0, event)
            _app_services.CFRelease(event)


# --- Windows ---

if WINDOWS:
    _user32 = ctypes.windll.user32
    _VK_SHIFT, _VK_CONTROL, _VK_ALT = 0x10, 0x11, 0x12
    _KEY_UP = 2

    def _start_windows(events):
        from ctypes import wintypes
        ready = queue.Queue()

        def listen():
            modifiers = 0x0001 | 0x0002 | 0x0004 | 0x4000  # Alt, Ctrl, Maiusc, senza ripetizione
            ok = all(_user32.RegisterHotKey(None, action, modifiers, ord(key)) for action, key in ((ENCRYPT, 'E'), (DECRYPT, 'D')))
            ready.put(ok)
            message = wintypes.MSG()
            while _user32.GetMessageW(ctypes.byref(message), None, 0, 0) > 0:
                if message.message == 0x0312:  # WM_HOTKEY
                    events.put(message.wParam)

        threading.Thread(target=listen, daemon=True).start()
        return ready.get(timeout=5)

    def _press_windows(key):
        _user32.keybd_event(_VK_CONTROL, 0, 0, 0)
        _user32.keybd_event(ord(key), 0, 0, 0)
        _user32.keybd_event(ord(key), 0, _KEY_UP, 0)
        _user32.keybd_event(_VK_CONTROL, 0, _KEY_UP, 0)


# --- Interfaccia comune ---

def start(root, callback):
    """Registra le scorciatoie; `callback(ENCRYPT | DECRYPT)` viene chiamata nel thread dell'interfaccia.

    Restituisce False se non è stato possibile registrarle (ad esempio perché già usate da un altro programma).
    """
    events = queue.Queue()

    def poll():
        try:
            while True:
                callback(events.get_nowait())
        except queue.Empty:
            pass
        root.after(60, poll)

    try:
        registered = _start_mac(events) if MAC else _start_windows(events) if WINDOWS else False
    except Exception:
        registered = False
    if registered:
        poll()
    return registered


def can_send_keys():
    """Su Mac simulare i tasti richiede il permesso «Accessibilità»."""
    return _app_services.AXIsProcessTrusted() if MAC else True


def open_permission_settings():
    if MAC:
        subprocess.Popen(['open', 'x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility'])


def modifiers_down():
    """L'utente sta ancora tenendo premuti i tasti della scorciatoia."""
    if MAC:
        return bool(_app_services.CGEventSourceFlagsState(1) & _MODIFIER_FLAGS)
    return any(_user32.GetAsyncKeyState(key) & 0x8000 for key in (_VK_SHIFT, _VK_CONTROL, _VK_ALT))


def send_copy():
    _press_mac(_KEY_C) if MAC else _press_windows('C')


def send_paste():
    _press_mac(_KEY_V) if MAC else _press_windows('V')


def notify(message):
    """Avviso che non toglie il fuoco al programma in uso (solo Mac)."""
    if MAC:
        text = message.replace('\\', '\\\\').replace('"', '\\"')
        subprocess.Popen(['osascript', '-e', f'display notification "{text}" with title "EasyCrypt"'],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
