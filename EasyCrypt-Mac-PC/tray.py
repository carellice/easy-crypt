"""Icona nativa nella barra dei menu/tray, senza dipendenze aggiuntive.

I callback nativi inviano azioni in coda: solo il thread Tk gestisce la finestra.
"""
import ctypes
import queue
import sys
import threading


class Tray:
    def __init__(self, png_path, ico_path):
        self.events = queue.Queue()
        self.png_path = png_path
        self.ico_path = ico_path
        self.backend = None

    def start(self):
        if sys.platform == 'darwin':
            self.backend = _MacTray(self.events, self.png_path)
        elif sys.platform == 'win32':
            self.backend = _WindowsTray(self.events, self.ico_path)
        else:
            raise OSError('Icona in background non disponibile su questo sistema.')
        self.backend.start()

    def poll(self):
        while True:
            try:
                yield self.events.get_nowait()
            except queue.Empty:
                return

    def set_window_visible(self, visible):
        if self.backend:
            self.backend.set_window_visible(visible)

    def stop(self):
        if self.backend:
            self.backend.stop()
            self.backend = None


class _MacTray:
    def __init__(self, events, icon_path):
        self.events = events
        self.icon_path = icon_path
        self.status = self.menu = self.target = self.image = None
        self.callbacks = []  # Impedisce a ctypes di liberare le funzioni Objective-C.
        self.objc = ctypes.CDLL('/usr/lib/libobjc.A.dylib')
        self.appkit = ctypes.CDLL('/System/Library/Frameworks/AppKit.framework/AppKit')
        self.objc.objc_getClass.argtypes = [ctypes.c_char_p]
        self.objc.objc_getClass.restype = ctypes.c_void_p
        self.objc.sel_registerName.argtypes = [ctypes.c_char_p]
        self.objc.sel_registerName.restype = ctypes.c_void_p
        self.objc.objc_allocateClassPair.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_size_t]
        self.objc.objc_allocateClassPair.restype = ctypes.c_void_p
        self.objc.class_addMethod.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_char_p]
        self.objc.class_addMethod.restype = ctypes.c_bool
        self.objc.objc_registerClassPair.argtypes = [ctypes.c_void_p]
        self.objc.objc_registerClassPair.restype = None
        self.message_address = ctypes.cast(self.objc.objc_msgSend, ctypes.c_void_p).value
        self.senders = {}

    def cls(self, name):
        return self.objc.objc_getClass(name.encode('ascii'))

    def send(self, obj, name, *args, restype=ctypes.c_void_p, argtypes=None):
        types = tuple(argtypes if argtypes is not None else [ctypes.c_void_p] * len(args))
        signature = (restype, types)
        if signature not in self.senders:
            self.senders[signature] = ctypes.CFUNCTYPE(restype, ctypes.c_void_p, ctypes.c_void_p, *types)(self.message_address)
        return self.senders[signature](obj, self.objc.sel_registerName(name.encode('ascii')), *args)

    def string(self, text):
        return self.send(self.cls('NSString'), 'stringWithUTF8String:', text.encode('utf-8'), argtypes=[ctypes.c_char_p])

    def start(self):
        self.app = self.send(self.cls('NSApplication'), 'sharedApplication')
        self.bar = self.send(self.cls('NSStatusBar'), 'systemStatusBar')
        self.status = self.send(self.bar, 'statusItemWithLength:', -1.0, argtypes=[ctypes.c_double])
        if not self.status:
            raise OSError('Impossibile creare l’icona nella barra dei menu.')
        self.send(self.status, 'retain')
        self.menu = self.send(self.send(self.cls('NSMenu'), 'alloc'), 'initWithTitle:', self.string('EasyCrypt'))
        target_class = self.objc.objc_allocateClassPair(self.cls('NSObject'), f'EasyCryptTray_{id(self):x}'.encode('ascii'), 0)
        if not target_class:
            raise OSError('Impossibile creare il menu di EasyCrypt.')
        action_type = ctypes.CFUNCTYPE(None, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p)
        for selector, action in [('showEasyCrypt:', 'show'), ('hideEasyCrypt:', 'hide'), ('quitEasyCrypt:', 'quit')]:
            def callback(_self, _selector, _sender, action=action):
                self.events.put((action, None))
            function = action_type(callback)
            self.callbacks.append(function)
            if not self.objc.class_addMethod(target_class, self.objc.sel_registerName(selector.encode('ascii')), function, b'v@:@'):
                raise OSError('Impossibile registrare le azioni del menu.')
        self.objc.objc_registerClassPair(target_class)
        self.target = self.send(self.send(target_class, 'alloc'), 'init')
        for title, selector in [('Apri EasyCrypt', 'showEasyCrypt:'), ('Nascondi finestra', 'hideEasyCrypt:'), ('Esci', 'quitEasyCrypt:')]:
            if title == 'Esci':
                self.send(self.menu, 'addItem:', self.send(self.cls('NSMenuItem'), 'separatorItem'))
            item = self.send(self.send(self.cls('NSMenuItem'), 'alloc'), 'initWithTitle:action:keyEquivalent:',
                             self.string(title), self.objc.sel_registerName(selector.encode('ascii')), self.string(''))
            self.send(item, 'setTarget:', self.target, restype=None)
            self.send(self.menu, 'addItem:', item, restype=None)
            self.send(item, 'release', restype=None)
        self.send(self.status, 'setMenu:', self.menu, restype=None)
        button = self.send(self.status, 'button')
        self.image = self.send(self.send(self.cls('NSImage'), 'alloc'), 'initWithContentsOfFile:', self.string(self.icon_path))
        if self.image:
            class Size(ctypes.Structure):
                _fields_ = [('width', ctypes.c_double), ('height', ctypes.c_double)]
            self.send(self.image, 'setSize:', Size(18, 18), restype=None, argtypes=[Size])
            self.send(button, 'setImage:', self.image, restype=None)
        else:
            self.send(button, 'setTitle:', self.string('EC'), restype=None)
        self.send(button, 'setToolTip:', self.string('EasyCrypt'), restype=None)
        self.events.put(('ready', None))

    def set_window_visible(self, visible):
        # Accessory: quando la finestra è chiusa rimane solo l'icona nella barra dei menu.
        self.send(self.app, 'setActivationPolicy:', 0 if visible else 1, restype=ctypes.c_bool, argtypes=[ctypes.c_long])
        if visible:
            self.send(self.app, 'activateIgnoringOtherApps:', True, restype=None, argtypes=[ctypes.c_bool])

    def stop(self):
        if self.status:
            self.send(self.bar, 'removeStatusItem:', self.status, restype=None)
        for attribute in ('status', 'menu', 'target', 'image'):
            value = getattr(self, attribute)
            if value:
                self.send(value, 'release', restype=None)
                setattr(self, attribute, None)


class _WindowsTray:
    def __init__(self, events, icon_path):
        self.events = events
        self.icon_path = icon_path
        self.hwnd = None
        self.thread = None
        self.stopping = threading.Event()

    def start(self):
        self.thread = threading.Thread(target=self._run, name='EasyCrypt tray', daemon=True)
        self.thread.start()

    def set_window_visible(self, visible):
        pass

    def stop(self):
        self.stopping.set()
        if self.hwnd:
            self.user32.PostMessageW(self.hwnd, 0x0010, 0, 0)  # WM_CLOSE
        if self.thread:
            self.thread.join(timeout=2)

    def _run(self):
        try:
            self._listen()
        except Exception as error:
            self.events.put(('error', str(error)))

    def _listen(self):
        from ctypes import wintypes as w
        user = self.user32 = ctypes.WinDLL('user32', use_last_error=True)
        shell = ctypes.WinDLL('shell32', use_last_error=True)
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        pointer = ctypes.c_void_p
        result = ctypes.c_ssize_t
        procedure_type = ctypes.WINFUNCTYPE(result, w.HWND, w.UINT, w.WPARAM, w.LPARAM)

        class WindowClass(ctypes.Structure):
            _fields_ = [('style', w.UINT), ('procedure', procedure_type), ('class_extra', ctypes.c_int),
                        ('window_extra', ctypes.c_int), ('instance', w.HINSTANCE), ('icon', w.HICON),
                        ('cursor', w.HANDLE), ('background', w.HBRUSH), ('menu_name', w.LPCWSTR), ('class_name', w.LPCWSTR)]

        class Guid(ctypes.Structure):
            _fields_ = [('a', w.DWORD), ('b', w.WORD), ('c', w.WORD), ('d', w.BYTE * 8)]

        class NotifyIcon(ctypes.Structure):
            _fields_ = [('size', w.DWORD), ('hwnd', w.HWND), ('id', w.UINT), ('flags', w.UINT),
                        ('callback', w.UINT), ('icon', w.HICON), ('tip', w.WCHAR * 128),
                        ('state', w.DWORD), ('state_mask', w.DWORD), ('info', w.WCHAR * 256),
                        ('version', w.UINT), ('info_title', w.WCHAR * 64), ('info_flags', w.DWORD),
                        ('guid', Guid), ('balloon_icon', w.HICON)]

        # Tutti gli handle e LRESULT hanno la larghezza del puntatore, anche su Windows a 64 bit.
        signatures = {
            'RegisterClassW': (w.ATOM, [ctypes.POINTER(WindowClass)]),
            'UnregisterClassW': (w.BOOL, [w.LPCWSTR, w.HINSTANCE]),
            'CreateWindowExW': (w.HWND, [w.DWORD, w.LPCWSTR, w.LPCWSTR, w.DWORD, ctypes.c_int, ctypes.c_int,
                                       ctypes.c_int, ctypes.c_int, w.HWND, w.HMENU, w.HINSTANCE, pointer]),
            'DefWindowProcW': (result, [w.HWND, w.UINT, w.WPARAM, w.LPARAM]),
            'LoadImageW': (w.HANDLE, [w.HINSTANCE, w.LPCWSTR, w.UINT, ctypes.c_int, ctypes.c_int, w.UINT]),
            'DestroyIcon': (w.BOOL, [w.HICON]), 'DestroyWindow': (w.BOOL, [w.HWND]),
            'PostMessageW': (w.BOOL, [w.HWND, w.UINT, w.WPARAM, w.LPARAM]),
            'PostQuitMessage': (None, [ctypes.c_int]),
            'RegisterWindowMessageW': (w.UINT, [w.LPCWSTR]),
            'GetMessageW': (w.BOOL, [ctypes.POINTER(w.MSG), w.HWND, w.UINT, w.UINT]),
            'TranslateMessage': (w.BOOL, [ctypes.POINTER(w.MSG)]),
            'DispatchMessageW': (result, [ctypes.POINTER(w.MSG)]),
            'CreatePopupMenu': (w.HMENU, []),
            'AppendMenuW': (w.BOOL, [w.HMENU, w.UINT, ctypes.c_size_t, w.LPCWSTR]),
            'GetCursorPos': (w.BOOL, [ctypes.POINTER(w.POINT)]),
            'SetForegroundWindow': (w.BOOL, [w.HWND]),
            'TrackPopupMenu': (w.UINT, [w.HMENU, w.UINT, ctypes.c_int, ctypes.c_int, ctypes.c_int, w.HWND, pointer]),
            'DestroyMenu': (w.BOOL, [w.HMENU]),
        }
        for name, (returns, arguments) in signatures.items():
            function = getattr(user, name)
            function.restype, function.argtypes = returns, arguments
        kernel.GetModuleHandleW.argtypes = [w.LPCWSTR]
        kernel.GetModuleHandleW.restype = w.HMODULE
        shell.Shell_NotifyIconW.argtypes = [w.DWORD, ctypes.POINTER(NotifyIcon)]
        shell.Shell_NotifyIconW.restype = w.BOOL
        instance = kernel.GetModuleHandleW(None)
        name = f'EasyCryptTray_{id(self):x}'
        taskbar_created = user.RegisterWindowMessageW('TaskbarCreated')
        callback_message = 0x8001
        notification = NotifyIcon()
        icon = None
        registered = False

        def add_icon():
            if not shell.Shell_NotifyIconW(0, ctypes.byref(notification)):  # NIM_ADD
                self.events.put(('error', 'Impossibile creare l’icona nel tray di Windows.'))
            else:
                self.events.put(('ready', None))

        def menu(hwnd):
            popup = user.CreatePopupMenu()
            try:
                for item_id, title in [(1, 'Apri EasyCrypt'), (2, 'Nascondi finestra'), (3, 'Esci')]:
                    if item_id == 3:
                        user.AppendMenuW(popup, 0x0800, 0, None)  # MF_SEPARATOR
                    user.AppendMenuW(popup, 0, item_id, title)
                position = w.POINT()
                user.GetCursorPos(ctypes.byref(position))
                user.SetForegroundWindow(hwnd)
                chosen = user.TrackPopupMenu(popup, 0x0182, position.x, position.y, 0, hwnd, None)
                if chosen in (1, 2, 3):
                    self.events.put(({1: 'show', 2: 'hide', 3: 'quit'}[chosen], None))
                user.PostMessageW(hwnd, 0, 0, 0)
            finally:
                user.DestroyMenu(popup)

        def procedure(hwnd, message, wp, lp):
            if message == callback_message:
                if lp in (0x0202, 0x0203, 0x0400, 0x0401):  # click / doppio click sinistro
                    self.events.put(('show', None))
                elif lp == 0x0205:  # click destro
                    menu(hwnd)
                return 0
            if message == taskbar_created:
                add_icon()  # Ripristina l'icona dopo un riavvio di Explorer.
                return 0
            if message == 0x0010:  # WM_CLOSE
                shell.Shell_NotifyIconW(2, ctypes.byref(notification))
                user.DestroyWindow(hwnd)
                return 0
            if message == 0x0002:  # WM_DESTROY
                user.PostQuitMessage(0)
                return 0
            return user.DefWindowProcW(hwnd, message, wp, lp)

        callback = procedure_type(procedure)
        try:
            window_class = WindowClass(procedure=callback, instance=instance, class_name=name)
            if not user.RegisterClassW(ctypes.byref(window_class)):
                raise ctypes.WinError(ctypes.get_last_error())
            registered = True
            self.hwnd = user.CreateWindowExW(0, name, 'EasyCrypt', 0, 0, 0, 0, 0, None, None, instance, None)
            if not self.hwnd:
                raise ctypes.WinError(ctypes.get_last_error())
            icon = user.LoadImageW(None, self.icon_path, 1, 0, 0, 0x0010 | 0x0040)
            if not icon:
                raise ctypes.WinError(ctypes.get_last_error())
            notification.size = ctypes.sizeof(NotifyIcon)
            notification.hwnd = self.hwnd
            notification.id = 1
            notification.flags = 1 | 2 | 4  # NIF_MESSAGE | NIF_ICON | NIF_TIP
            notification.callback = callback_message
            notification.icon = icon
            notification.tip = 'EasyCrypt'
            if self.stopping.is_set():
                return
            add_icon()
            message = w.MSG()
            while not self.stopping.is_set():
                received = user.GetMessageW(ctypes.byref(message), None, 0, 0)
                if received == -1:
                    raise ctypes.WinError(ctypes.get_last_error())
                if received == 0:
                    break
                user.TranslateMessage(ctypes.byref(message))
                user.DispatchMessageW(ctypes.byref(message))
        except Exception as error:
            self.events.put(('error', str(error)))
        finally:
            if self.hwnd:
                shell.Shell_NotifyIconW(2, ctypes.byref(notification))
                user.DestroyWindow(self.hwnd)
                self.hwnd = None
            if icon:
                user.DestroyIcon(icon)
            if registered:
                user.UnregisterClassW(name, instance)
