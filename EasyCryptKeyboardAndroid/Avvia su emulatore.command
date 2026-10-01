#!/bin/bash
# Doppio click: avvia l'emulatore Pixel, compila e installa EasyCrypt Keyboard e apre l'app.

cd "$(dirname "$0")" || exit 1

AVD="Pixel_10_Pro"
PACKAGE="com.easycrypt.keyboard"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

fail() {
    echo
    echo "ERRORE: $1"
    read -n 1 -s -r -p "Premi un tasto per chiudere..."
    exit 1
}

[ -x "$ADB" ] || fail "adb non trovato in $SDK (Android SDK non installato?)"
[ -x "$EMULATOR" ] || fail "emulatore non trovato in $SDK"
[ -x "$JAVA_HOME/bin/java" ] || fail "JDK di Android Studio non trovato"
"$EMULATOR" -list-avds | grep -qx "$AVD" || fail "emulatore \"$AVD\" non trovato. Disponibili: $("$EMULATOR" -list-avds | tr '\n' ' ')"

"$ADB" start-server >/dev/null 2>&1

if "$ADB" devices | grep -q "^emulator-.*device$"; then
    echo "Emulatore già in esecuzione."
else
    echo "Avvio dell'emulatore $AVD..."
    nohup "$EMULATOR" -avd "$AVD" >/dev/null 2>&1 &
fi

echo "Compilazione dell'app..."
./gradlew assembleDebug -q || fail "compilazione non riuscita"

echo "Attesa dell'avvio di Android..."
"$ADB" wait-for-device
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 2
done

echo "Installazione..."
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null || fail "installazione non riuscita"

# Abilita e seleziona la tastiera, poi apre la schermata dell'app.
"$ADB" shell ime enable "$PACKAGE/.EasyCryptIME" >/dev/null
"$ADB" shell ime set "$PACKAGE/.EasyCryptIME" >/dev/null
"$ADB" shell am start -n "$PACKAGE/.SettingsActivity" >/dev/null

echo
echo "Fatto: EasyCrypt Keyboard è aperta nell'emulatore ed è la tastiera attiva."
sleep 3
