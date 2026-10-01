#!/bin/bash
# Doppio clic: incrementa la versione, crea i pacchetti di EasyCrypt e li pubblica come release su GitHub.
#
#   - Android: APK della tastiera
#   - Mac:     EasyCrypt.app autonoma (creata con PyInstaller, non richiede Python), in uno zip
#   - PC:      zip con il programma in Python e «Avvia EasyCrypt.bat» (richiede Python sul PC).
#              L'exe autonomo lo crea GitHub dopo la pubblicazione (.github/workflows/windows-exe.yml)
#              e lo aggiunge alla stessa release.

cd "$(dirname "$0")" || exit 1
ROOT="$PWD"

REPO="carellice/easy-crypt"
ANDROID="$ROOT/EasyCryptKeyboardAndroid"
DESKTOP="$ROOT/EasyCrypt-Mac-PC"
VERSION_FILE="$ANDROID/version.properties"
OUT="$ROOT/release"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

fail() {
    echo
    echo "ERRORE: $1"
    read -n 1 -s -r -p "Premi un tasto per chiudere..."
    exit 1
}

command -v gh >/dev/null || fail "GitHub CLI (gh) non trovata"
gh auth status >/dev/null 2>&1 || fail "GitHub CLI non autenticata: esegui 'gh auth login'"
[ -x "$JAVA_HOME/bin/java" ] || fail "JDK di Android Studio non trovato"
[ -f "$VERSION_FILE" ] || fail "$VERSION_FILE non trovato"
# Una release ha bisogno di almeno un commit nel repository.
[ "$(gh repo view "$REPO" --json isEmpty -q .isEmpty 2>/dev/null)" = "false" ] \
    || fail "il repository $REPO è vuoto o non raggiungibile: carica prima il codice (git push)"

# Python con Tk 8.6+ per creare l'app Mac (quello fornito da Apple ha un Tk troppo vecchio).
PYTHON=""
for candidate in /usr/local/bin/python3 /opt/homebrew/bin/python3 /Library/Frameworks/Python.framework/Versions/Current/bin/python3 python3; do
    if "$candidate" -c 'import tkinter, sys; sys.exit(tkinter.TkVersion < 8.6)' 2>/dev/null; then
        PYTHON="$candidate"
        break
    fi
done
[ -n "$PYTHON" ] || fail "serve Python 3 con Tk 8.6 o successivo (python.org o Homebrew)"

# ---- incremento della versione (1.0.N -> 1.0.N+1, versionCode +1) ----
OLD_CODE=$(grep '^VERSION_CODE=' "$VERSION_FILE" | cut -d= -f2)
OLD_NAME=$(grep '^VERSION_NAME=' "$VERSION_FILE" | cut -d= -f2)
[[ "$OLD_CODE" =~ ^[0-9]+$ && "$OLD_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "versione non valida in $VERSION_FILE"
NEW_CODE=$((OLD_CODE + 1))
NEW_NAME="${OLD_NAME%.*}.$(( ${OLD_NAME##*.} + 1 ))"
TAG="v$NEW_NAME"

write_version() { printf 'VERSION_CODE=%s\nVERSION_NAME=%s\n' "$1" "$2" > "$VERSION_FILE"; }
# Se qualcosa va storto la versione torna quella di prima, così il prossimo tentativo non salta un numero.
abort() {
    write_version "$OLD_CODE" "$OLD_NAME"
    fail "$1 (versione ripristinata a $OLD_NAME, niente è stato pubblicato)"
}

gh release view "$TAG" -R "$REPO" >/dev/null 2>&1 && fail "la release $TAG esiste già su $REPO"

echo "Versione: $OLD_NAME -> $NEW_NAME"
write_version "$NEW_CODE" "$NEW_NAME"
rm -rf "$OUT"
mkdir -p "$OUT"

APK="$OUT/EasyCryptKeyboard-Android-$NEW_NAME.apk"
MAC_ZIP="$OUT/EasyCrypt-Mac-$NEW_NAME.zip"
PC_ZIP="$OUT/EasyCrypt-PC-$NEW_NAME.zip"

# ---- Android ----
echo
echo "[1/3] Android: generazione dell'APK..."
(cd "$ANDROID" && ./gradlew assembleRelease -q) || abort "compilazione Android non riuscita"
cp "$ANDROID/app/build/outputs/apk/release/app-release.apk" "$APK" || abort "APK non trovato"

# ---- Mac ----
echo "[2/3] Mac: creazione di EasyCrypt.app..."
(
    cd "$DESKTOP" || exit 1
    "$PYTHON" -m venv .venv-build || exit 1
    .venv-build/bin/pip install --quiet --upgrade pyinstaller cryptography || exit 1
    .venv-build/bin/pyinstaller --noconfirm --log-level WARN --windowed --name EasyCrypt --icon icon.icns \
        --add-data "icon.png:." --osx-bundle-identifier com.easycrypt.desktop easycrypt.py || exit 1
    # Versione mostrata dal Finder.
    /usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $NEW_NAME" dist/EasyCrypt.app/Contents/Info.plist || exit 1
    codesign --force --deep --sign - dist/EasyCrypt.app >/dev/null 2>&1  # firma locale, rifatta dopo la modifica
    ditto -c -k --keepParent dist/EasyCrypt.app "$MAC_ZIP"
) || abort "creazione dell'app Mac non riuscita"

# ---- PC ----
echo "[3/3] PC: creazione dello zip..."
(
    STAGE="$OUT/EasyCrypt-PC"
    mkdir -p "$STAGE" || exit 1
    cd "$DESKTOP" || exit 1
    cp easycrypt.py easycrypt_core.py hotkeys.py password_store.py icon.png icon.ico \
        "Avvia EasyCrypt.bat" "Crea app PC.bat" README.md "$STAGE/" || exit 1
    ditto -c -k --keepParent "$STAGE" "$PC_ZIP" || exit 1
    rm -rf "$STAGE"
) || abort "creazione dello zip per PC non riuscita"

# ---- pubblicazione ----
NOTES="## EasyCrypt $NEW_NAME

| File | Per | Come si installa |
|---|---|---|
| \`$(basename "$APK")\` | Android 8.0+ | Apri l'APK sul telefono e segui l'introduzione dell'app |
| \`$(basename "$MAC_ZIP")\` | Mac | Estrai e apri EasyCrypt.app. Al primo avvio macOS blocca le app non firmate: clic destro → «Apri», oppure Impostazioni › Privacy e sicurezza › «Apri comunque» |
| \`$(basename "$PC_ZIP")\` | Windows, con Python | Serve [Python 3](https://www.python.org/downloads/). Estrai e fai doppio clic su «Avvia EasyCrypt.bat» |
| \`EasyCrypt-PC-$NEW_NAME.exe\` | Windows, senza Python | Compare qui qualche minuto dopo la pubblicazione. Doppio clic; se SmartScreen avvisa: «Ulteriori informazioni» → «Esegui comunque» |

Su tutti i dispositivi va usata la stessa password."

echo
echo "Caricamento di EasyCrypt $NEW_NAME su $REPO..."
gh release create "$TAG" "$APK" "$MAC_ZIP" "$PC_ZIP" -R "$REPO" --title "EasyCrypt $NEW_NAME" --notes "$NOTES" \
    || abort "caricamento non riuscito"

echo
echo "Fatto: EasyCrypt $NEW_NAME pubblicata."
echo "https://github.com/$REPO/releases/tag/$TAG"
echo "L'exe per Windows viene creato da GitHub e aggiunto alla release tra qualche minuto:"
echo "https://github.com/$REPO/actions"
echo "Ricorda di fare commit di version.properties."
read -n 1 -s -r -p "Premi un tasto per chiudere..."
