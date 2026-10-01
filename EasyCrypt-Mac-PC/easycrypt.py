#!/usr/bin/env python3
"""EasyCrypt per Mac e PC: una finestra per cifrare e decifrare testi.

Stessa cifratura di EasyCrypt Keyboard (Android) e della web app EasyCrypt. Serve solo Python con tkinter.
"""
import os
import subprocess
import sys
import tkinter as tk
from tkinter import ttk

import easycrypt_core
import hotkeys
import password_store

ACCENT = '#1a73e8'
ERROR = '#d93025'
MAC = sys.platform == 'darwin'
UTF8 = dict(os.environ, LANG='en_US.UTF-8')  # pbcopy e pbpaste scambiano il testo in UTF-8


def resource(name):
    """Percorso di un file accanto al programma (anche dentro l'app creata con PyInstaller)."""
    return os.path.join(getattr(sys, '_MEIPASS', os.path.dirname(os.path.abspath(__file__))), name)


class App:
    def __init__(self, root):
        self.root = root
        self.result = ''
        self.pending = None

        root.title('EasyCrypt')
        root.geometry('680x780')
        root.minsize(460, 560)
        try:
            self.icon = tk.PhotoImage(file=resource('icon.png'))
            root.iconphoto(True, self.icon)
        except tk.TclError:
            self.icon = None

        style = ttk.Style()
        if not MAC and 'vista' not in style.theme_names():
            style.theme_use('clam')
        base = ('Helvetica Neue', 13) if MAC else ('Segoe UI', 10)
        mono = ('Menlo', 12) if MAC else ('Consolas', 10)
        style.configure('Title.TLabel', font=(base[0], base[1] + 9, 'bold'))
        style.configure('Head.TLabel', font=(base[0], base[1], 'bold'))
        style.configure('Muted.TLabel', foreground='gray50')

        self.password = tk.StringVar(value=password_store.load())
        self.remember = tk.BooleanVar(value=bool(self.password.get()))
        self.visible = tk.BooleanVar(value=False)
        self.encrypt = tk.BooleanVar(value=True)
        self.on_top = tk.BooleanVar(value=False)

        frame = ttk.Frame(root, padding=20)
        frame.pack(fill='both', expand=True)
        frame.columnconfigure(0, weight=1)

        # Intestazione
        header = ttk.Frame(frame)
        header.grid(row=0, column=0, sticky='ew')
        if self.icon:
            self.logo = self.icon.subsample(max(1, self.icon.width() // 52))
            ttk.Label(header, image=self.logo).pack(side='left', padx=(0, 14))
        titles = ttk.Frame(header)
        titles.pack(side='left')
        ttk.Label(titles, text='EasyCrypt', style='Title.TLabel').pack(anchor='w')
        ttk.Label(titles, text='Cifra e decifra i tuoi testi. Compatibile con la tastiera Android e la web app.',
                  style='Muted.TLabel').pack(anchor='w')

        # Password
        ttk.Label(frame, text='Password', style='Head.TLabel').grid(row=1, column=0, sticky='w', pady=(18, 4))
        row = ttk.Frame(frame)
        row.grid(row=2, column=0, sticky='ew')
        row.columnconfigure(0, weight=1)
        self.password_entry = ttk.Entry(row, textvariable=self.password, show='•', font=base)
        self.password_entry.grid(row=0, column=0, sticky='ew')
        ttk.Checkbutton(row, text='Mostra', variable=self.visible, command=self.toggle_visible).grid(row=0, column=1, padx=(10, 0))
        options = ttk.Frame(frame)
        options.grid(row=3, column=0, sticky='ew', pady=(6, 0))
        if password_store.available():
            ttk.Checkbutton(options, text='Ricorda la password su questo computer', variable=self.remember,
                            command=self.store_password).pack(side='left')
        ttk.Checkbutton(options, text='Finestra sempre in primo piano', variable=self.on_top,
                        command=lambda: root.attributes('-topmost', self.on_top.get())).pack(side='right')

        # Cifra / Decifra
        modes = ttk.Frame(frame)
        modes.grid(row=4, column=0, sticky='ew', pady=(18, 0))
        modes.columnconfigure((0, 1), weight=1, uniform='mode')
        ttk.Radiobutton(modes, text='Cifra', variable=self.encrypt, value=True, command=self.set_mode,
                        style='Toolbutton').grid(row=0, column=0, sticky='ew', ipady=5)
        ttk.Radiobutton(modes, text='Decifra', variable=self.encrypt, value=False, command=self.set_mode,
                        style='Toolbutton').grid(row=0, column=1, sticky='ew', ipady=5)

        # Testo
        head = ttk.Frame(frame)
        head.grid(row=5, column=0, sticky='ew', pady=(16, 4))
        self.input_label = ttk.Label(head, style='Head.TLabel')
        self.input_label.pack(side='left')
        ttk.Button(head, text='Svuota', command=self.clear).pack(side='right')
        ttk.Button(head, text='Incolla', command=self.paste).pack(side='right', padx=(0, 6))
        self.input = self.text_box(frame, row=6, font=base)
        self.input.focus_set()

        # Risultato
        head = ttk.Frame(frame)
        head.grid(row=7, column=0, sticky='ew', pady=(16, 4))
        self.output_label = ttk.Label(head, style='Head.TLabel')
        self.output_label.pack(side='left')
        self.copy_button = ttk.Button(head, text='Copia', command=self.copy, state='disabled')
        self.copy_button.pack(side='right')
        self.output = self.text_box(frame, row=8, font=mono)
        self.output.configure(state='disabled')
        self.fonts = (base, mono)

        self.status = ttk.Label(frame, text='', style='Muted.TLabel')
        self.status.grid(row=9, column=0, sticky='w', pady=(8, 0))
        # Scorciatoie da qualsiasi programma
        self.busy = False
        self.asked_permission = False
        if hotkeys.available:
            if hotkeys.start(root, self.hotkey):
                hint = (f'Da qualsiasi programma: seleziona un testo e premi {hotkeys.LABELS[hotkeys.ENCRYPT]} per cifrarlo o '
                        f'{hotkeys.LABELS[hotkeys.DECRYPT]} per decifrarlo. Viene sostituito sul posto. '
                        'EasyCrypt deve restare aperto (anche ridotto a icona).')
            else:
                hint = 'Scorciatoie non disponibili: sono già usate da un altro programma o da un\'altra copia di EasyCrypt.'
            hint_label = ttk.Label(frame, text=hint, style='Muted.TLabel', justify='left')
            hint_label.grid(row=10, column=0, sticky='ew', pady=(10, 0))
            frame.bind('<Configure>', lambda event: hint_label.configure(wraplength=event.width - 44))
        frame.rowconfigure(6, weight=1)
        frame.rowconfigure(8, weight=1)

        self.password.trace_add('write', lambda *_: (self.store_password(), self.schedule()))
        self.input.bind('<<Modified>>', self.modified)
        self.input.bind('<<Paste>>', self.pasted)
        self.set_mode()
        if not self.password.get():
            self.password_entry.focus_set()

    def text_box(self, parent, row, font):
        holder = ttk.Frame(parent)
        holder.grid(row=row, column=0, sticky='nsew')
        holder.columnconfigure(0, weight=1)
        holder.rowconfigure(0, weight=1)
        text = tk.Text(holder, height=6, wrap='word', font=font, undo=True, relief='solid', borderwidth=1,
                       highlightthickness=0, padx=8, pady=8)
        scroll = ttk.Scrollbar(holder, command=text.yview)
        text.configure(yscrollcommand=scroll.set)
        text.grid(row=0, column=0, sticky='nsew')
        scroll.grid(row=0, column=1, sticky='ns')
        return text

    # --- Azioni ---

    def toggle_visible(self):
        self.password_entry.configure(show='' if self.visible.get() else '•')

    def store_password(self):
        if self.remember.get():
            password_store.save(self.password.get())
        else:
            password_store.forget()

    def set_mode(self):
        encrypt = self.encrypt.get()
        self.input_label.configure(text='Testo da cifrare' if encrypt else 'Testo da decifrare')
        self.output_label.configure(text='Testo cifrato' if encrypt else 'Testo decifrato')
        base, mono = self.fonts
        self.output.configure(font=mono if encrypt else base, foreground=ACCENT if encrypt else self.input.cget('foreground'))
        self.update()

    def modified(self, _event=None):
        if self.input.edit_modified():
            self.input.edit_modified(False)
            self.schedule()

    def schedule(self):
        # Aggiorna poco dopo l'ultimo tasto, così scrivere resta fluido.
        if self.pending:
            self.root.after_cancel(self.pending)
        self.pending = self.root.after(120, self.update)

    def update(self):
        self.pending = None
        text = self.input.get('1.0', 'end-1c')
        password = self.password.get()
        if not text.strip():
            return self.show('', '')
        if not password:
            return self.show('', 'Inserisci la password per continuare.', error=True)
        try:
            if self.encrypt.get():
                self.show(easycrypt_core.encrypt(text, password), '')
            else:
                self.show(easycrypt_core.decrypt(text, password), '')
        except Exception:
            self.show('', 'Errore durante la cifratura.' if self.encrypt.get()
                      else 'Impossibile decifrare: password errata o testo non valido.', error=True)

    def show(self, text, message, error=False):
        self.result = text
        self.output.configure(state='normal')
        self.output.delete('1.0', 'end')
        self.output.insert('1.0', text)
        self.output.configure(state='disabled')
        self.copy_button.configure(state='normal' if text else 'disabled')
        self.set_status(message, error)

    def set_status(self, message, error=False):
        self.status.configure(text=message, foreground=ERROR if error else 'gray50')

    def load(self, text):
        """Mette un testo nel campo e sceglie da solo se va cifrato o decifrato."""
        self.input.delete('1.0', 'end')
        self.input.insert('1.0', text)
        self.input.edit_modified(False)
        self.encrypt.set(not easycrypt_core.looks_encrypted(text))
        self.set_mode()

    def clipboard(self):
        try:
            return self.root.clipboard_get()
        except tk.TclError:
            return ''

    def paste(self):
        text = self.clipboard()
        if text:
            self.load(text)
        else:
            self.set_status('Negli appunti non c\'è testo.', error=True)

    def pasted(self, _event=None):
        # Incollando in un campo vuoto si riconosce il testo cifrato e si passa a "Decifra".
        text = self.clipboard()
        if text and not self.input.get('1.0', 'end-1c').strip():
            self.load(text)
            return 'break'
        return None

    def clear(self):
        self.input.delete('1.0', 'end')
        self.update()
        self.input.focus_set()

    # --- Scorciatoie: copia la selezione, la cifra o decifra e la incolla al suo posto ---

    def report(self, message, error=True):
        self.set_status(message, error)
        hotkeys.notify(message)

    def hotkey(self, action):
        if self.busy:
            return
        if not self.password.get():
            return self.report('Inserisci prima la password in EasyCrypt.')
        if not hotkeys.can_send_keys():
            if not self.asked_permission:
                self.asked_permission = True
                hotkeys.open_permission_settings()
            return self.report('Consenti a EasyCrypt il controllo del computer in Impostazioni › Privacy e sicurezza › '
                               'Accessibilità, poi riprova.')
        self.busy = True
        self.wait_release(action, 50)

    def wait_release(self, action, tries):
        # Con i tasti della scorciatoia ancora premuti, «copia» diventerebbe un'altra combinazione.
        if hotkeys.modifiers_down() and tries > 0:
            self.root.after(30, self.wait_release, action, tries - 1)
            return
        saved = self.system_clipboard()
        self.set_clipboard('')
        hotkeys.send_copy()
        self.root.after(60, self.wait_copy, action, saved, 20)

    def wait_copy(self, action, saved, tries):
        text = self.system_clipboard()
        if not text:
            if tries > 0:
                self.root.after(50, self.wait_copy, action, saved, tries - 1)
            else:
                self.finish(saved, 'Nessun testo selezionato.')
            return
        encrypt = action == hotkeys.ENCRYPT
        try:
            password = self.password.get()
            result = easycrypt_core.encrypt(text, password) if encrypt else easycrypt_core.decrypt(text, password)
        except Exception:
            return self.finish(saved, 'Errore durante la cifratura.' if encrypt
                               else 'Impossibile decifrare: password errata o testo non valido.')
        self.set_clipboard(result)
        hotkeys.send_paste()
        self.set_status('Testo cifrato e sostituito.' if encrypt else 'Testo decifrato e sostituito.')
        # Dopo l'incolla rimette negli appunti quello che c'era prima.
        self.root.after(500, self.finish, saved, None)

    def finish(self, saved, error):
        if saved:
            self.set_clipboard(saved)
        self.busy = False
        if error:
            self.report(error)

    def system_clipboard(self):
        # Su Mac tkinter, quando la finestra è in secondo piano, non vede ciò che copiano gli altri programmi:
        # si leggono gli appunti con il comando di sistema.
        if MAC:
            try:
                return subprocess.run(['pbpaste'], capture_output=True, env=UTF8).stdout.decode('utf-8', 'replace')
            except OSError:
                return ''
        return self.clipboard()

    def set_clipboard(self, text):
        if MAC:
            try:
                subprocess.run(['pbcopy'], input=text.encode('utf-8'), env=UTF8)
                return
            except OSError:
                pass
        self.root.clipboard_clear()
        self.root.clipboard_append(text)
        self.root.update()

    def copy(self):
        self.root.clipboard_clear()
        self.root.clipboard_append(self.result)
        self.root.update()  # lascia il testo negli appunti anche dopo la chiusura
        self.set_status('Copiato negli appunti.')


def main():
    if sys.platform == 'win32':
        try:  # testo nitido sugli schermi ad alta risoluzione
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except Exception:
            pass
    root = tk.Tk()
    App(root)
    root.mainloop()


if __name__ == '__main__':
    main()
