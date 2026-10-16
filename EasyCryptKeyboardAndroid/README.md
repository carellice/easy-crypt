# EasyCrypt per Android

Cifra e decifra testi usando Gboard o la tua tastiera preferita. Android 8.0 o successivo.

1. Apri EasyCrypt e configura la password in **Impostazioni**. Chi riceve il testo deve usare la stessa password.
2. Scrivi o incolla nell’editor: in modalità **Cifra** il risultato si aggiorna mentre scrivi. Scegli **Decifra** per leggere un messaggio cifrato.
3. Copia o condividi il risultato.

Puoi anche condividere testo da un’altra app verso **EasyCrypt**, oppure selezionarlo e scegliere **Cifra** o **Decifra** dal menu copia/incolla (a volte sotto i tre puntini). Nei campi modificabili puoi sostituire la selezione; nei messaggi ricevuti puoi copiare il risultato. Alcune app non mostrano queste voci: usa Condividi o copia e incolla nell’editor.

La tastiera personalizzata è stata rimossa. Il pacchetto resta `com.easycrypt.keyboard` per consentire l’aggiornamento delle installazioni esistenti e conservare la password. Se la vecchia tastiera era selezionata, scegli Gboard o un’altra tastiera di sistema dopo l’aggiornamento.

## Aggiornamenti

In **Impostazioni › Aggiornamenti** premi **«Cerca aggiornamenti»**: EasyCrypt controlla l’ultima [release](https://github.com/carellice/easy-crypt/releases/latest) su GitHub e, se c’è una versione più recente, la scarica e la installa con **«Scarica e installa»**. La prima volta Android chiede di consentire a EasyCrypt di installare app; prima di ogni installazione chiede sempre conferma. L’aggiornamento conserva la password salvata.

La password è protetta con Android Keystore. L’app si collega a Internet solo quando premi «Cerca aggiornamenti» o «Scarica e installa», e solo verso GitHub; la cifratura avviene sempre sul dispositivo. Il formato di cifratura resta compatibile con [Mac e PC](../EasyCrypt-Mac-PC/README.md) e la web app: PBKDF2-HMAC-SHA256 (100 000 iterazioni), AES-256-GCM, `base64(salt[16] + iv[12] + ciphertext + tag)`.

## Sviluppo

Servono Android SDK 36 e JDK 17+. Dalla cartella del progetto:

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
```

`Avvia su emulatore.command` compila, installa e apre l’editor sul Pixel configurato, usando la tastiera di sistema. La pubblicazione avviene tramite `Pubblica release.command` nella cartella principale.

- `TextCryptActivity.kt`: editor principale e testo condiviso (`ACTION_SEND`), elaborazione su thread separato con debounce e scarto dei risultati superati.
- `ProcessTextActivity.kt`: azioni del menu di selezione (`ACTION_PROCESS_TEXT`), copia e sostituzione.
- `SettingsActivity.kt`, `PasswordStore.kt`: impostazioni e password protetta.
- `EasyCrypt.kt`, `EasyCryptTest.kt`: cifratura e test di compatibilità.
