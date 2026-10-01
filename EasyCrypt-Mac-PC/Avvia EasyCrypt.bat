@echo off
rem Doppio clic: avvia EasyCrypt su Windows.
cd /d "%~dp0"
where pyw >nul 2>nul && (start "" pyw -3 easycrypt.py & exit /b 0)
where pythonw >nul 2>nul && (start "" pythonw easycrypt.py & exit /b 0)
echo Serve Python 3: installalo da https://www.python.org/downloads/ e riprova.
pause
