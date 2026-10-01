@echo off
rem Doppio clic: crea EasyCrypt.exe (cartella dist), che funziona anche sui PC senza Python.
rem Scarica PyInstaller in una cartella locale (.venv-build).
cd /d "%~dp0"
where py >nul 2>nul && (set PY=py -3) || (set PY=python)
%PY% -m venv .venv-build || goto :error
.venv-build\Scripts\pip install --quiet pyinstaller cryptography || goto :error
.venv-build\Scripts\pyinstaller --noconfirm --windowed --onefile --name EasyCrypt --icon icon.ico --add-data "icon.png;." easycrypt.py || goto :error
echo Fatto: EasyCrypt.exe e nella cartella dist.
start "" dist
pause
exit /b 0
:error
echo Creazione non riuscita. Serve Python 3: https://www.python.org/downloads/
pause
