# EasyCrypt per Android

Cifra e decifra testi usando Gboard o la tua tastiera preferita. Android 8.0 o successivo.

1. Apri EasyCrypt e configura la password in **Impostazioni**. Chi riceve il testo deve usare la stessa password.
2. Scrivi o incolla nell’editor: in modalità **Cifra** il risultato si aggiorna mentre scrivi. Scegli **Decifra** per leggere un messaggio cifrato.
3. Copia o condividi il risultato.

Puoi anche condividere testo da un’altra app verso **EasyCrypt**, oppure selezionarlo e scegliere **Cifra** o **Decifra** dal menu copia/incolla (a volte sotto i tre puntini). Nei campi modificabili puoi sostituire la selezione; nei messaggi ricevuti puoi copiare il risultato. Alcune app non mostrano queste voci: usa Condividi o copia e incolla nell’editor.

La tastiera personalizzata è stata rimossa. Il pacchetto resta `com.easycrypt.keyboard` per consentire l’aggiornamento delle installazioni esistenti e conservare la password. Se la vecchia tastiera era selezionata, scegli Gboard o un’altra tastiera di sistema dopo l’aggiornamento.

## Blocco biometrico

In **Impostazioni › Sicurezza** attiva **«Richiedi autenticazione biometrica»** e conferma con l’impronta o il volto già configurati sul telefono. L’opzione è disattivata inizialmente; senza una biometria disponibile non si attiva.

Quando il blocco è attivo, EasyCrypt richiede lo sblocco all’apertura e dopo essere passata in background o dopo lo spegnimento dello schermo. Protegge anche Condividi, Cifra/Decifra nel menu di selezione e le Impostazioni. Puoi usare il PIN, la sequenza o la password del telefono come alternativa; annullare o fallire l’autenticazione lascia l’app bloccata. Per disattivare la protezione occorre autenticarsi di nuovo.

Durante il blocco i testi sono nascosti; gli screenshot e le anteprime nelle app recenti sono disabilitati finché l’opzione è attiva. La password usata per cifrare i messaggi resta distinta dal codice di sblocco del telefono.

**Passaggio alla 1.0.11:** la chiave delle vecchie release non è più disponibile e questa versione usa una nuova firma dedicata. Android richiede di disinstallare la vecchia app prima di installare l’APK 1.0.11. Assicurati prima di conoscere la password di cifratura: la disinstallazione cancella la password e le impostazioni salvate, che vanno reinserite. I messaggi già cifrati restano decifrabili con la stessa password. Dalla 1.0.11 gli aggiornamenti successivi usano la nuova firma.

## Aggiornamenti

In **Impostazioni › Aggiornamenti** premi **«Cerca aggiornamenti»**: EasyCrypt controlla l’ultima [release](https://github.com/carellice/easy-crypt/releases/latest) su GitHub e, se c’è una versione più recente, la scarica e la installa con **«Scarica e installa»**. La prima volta Android chiede di consentire a EasyCrypt di installare app; prima di ogni installazione chiede sempre conferma. L’aggiornamento conserva la password salvata.

La password è protetta con Android Keystore. L’app si collega a Internet solo quando premi «Cerca aggiornamenti» o «Scarica e installa», e solo verso GitHub; la cifratura avviene sempre sul dispositivo. Il formato di cifratura resta compatibile con [Mac e PC](../EasyCrypt-Mac-PC/README.md) e la web app: PBKDF2-HMAC-SHA256 (100 000 iterazioni), AES-256-GCM, `base64(salt[16] + iv[12] + ciphertext + tag)`.

## Sviluppo

Servono Android SDK 36 e JDK 17+. Dalla cartella del progetto:

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
```

`Avvia su emulatore.command` compila, installa e apre l’editor sul Pixel configurato, usando la tastiera di sistema. La pubblicazione avviene tramite `Pubblica release.command` nella cartella principale.

Per compilare la release, configura `keystore.properties` (ignorato da Git) nella cartella Android con `storeFile`, `storePassword`, `keyAlias`, `keyPassword`, oppure usa `EASYCRYPT_KEYSTORE`, `EASYCRYPT_KEYSTORE_PASSWORD`, `EASYCRYPT_KEY_ALIAS`, `EASYCRYPT_KEY_PASSWORD`. La chiave dedicata e una copia della configurazione sono conservate in `~/.android/easycrypt-signing/`: fanne un backup sicuro. La compilazione controlla l’impronta pubblica del certificato e rifiuta una firma diversa, così i futuri APK restano aggiornabili. Chiave e password non devono entrare nel repository.

I test di accesso su dispositivo si eseguono con `./gradlew connectedDebugAndroidTest`; coprono editor, testi condivisi, entrambe le azioni del menu di selezione, impostazioni, rotazione e ritorno dal background.

- `TextCryptActivity.kt`: editor principale e testo condiviso (`ACTION_SEND`), elaborazione su thread separato con debounce e scarto dei risultati superati.
- `ProcessTextActivity.kt`: azioni del menu di selezione (`ACTION_PROCESS_TEXT`), copia e sostituzione.
- `SettingsActivity.kt`, `PasswordStore.kt`: impostazioni e password protetta.
- `LockedActivity.kt`, `AppLock.kt`: autenticazione di sistema, schermata bloccata e sessione in memoria.
- `EasyCrypt.kt`, `EasyCryptTest.kt`: cifratura e test di compatibilità.
