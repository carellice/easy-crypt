#!/bin/bash
# Doppio clic: crea EasyCrypt.app (cartella dist), che funziona anche sui Mac senza Python.
# Scarica PyInstaller in una cartella locale (.venv-build).
cd "$(dirname "$0")" || exit 1
for python in /usr/local/bin/python3 /opt/homebrew/bin/python3 /Library/Frameworks/Python.framework/Versions/Current/bin/python3 python3; do
  "$python" -c 'import tkinter, sys; sys.exit(tkinter.TkVersion < 8.6)' 2>/dev/null && found="$python" && break
done
if [ -z "$found" ]; then
  echo "Serve Python 3 con Tk: installalo da https://www.python.org/downloads/ e riprova."
  read -r -p "Premi Invio per chiudere."
  exit 1
fi
"$found" -m venv .venv-build && .venv-build/bin/pip install --quiet pyinstaller cryptography \
  && .venv-build/bin/pyinstaller --noconfirm --windowed --name EasyCrypt --icon icon.icns --add-data "icon.png:." \
     --osx-bundle-identifier com.easycrypt.desktop easycrypt.py \
  && echo && echo "Fatto: EasyCrypt.app è nella cartella dist." && open dist
read -r -p "Premi Invio per chiudere."
