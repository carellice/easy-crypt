<div align="center">

<img src="EasyCrypt-Mac-PC/icon.png" width="128" alt="Icona di EasyCrypt">

# EasyCrypt

**Scrivi messaggi che può leggere solo chi conosce la tua password.**

Su Android è una tastiera, su Mac e PC un programma: stessa cifratura, stessi testi.

</div>

---

## Che cos'è

EasyCrypt trasforma un testo normale in un testo cifrato da incollare in qualsiasi chat, email o nota, e fa il
percorso inverso per chi conosce la password.

```
Ci vediamo alle 8 🍕   ──►   s/qQG3Bis0JTkkkpYSgROLQGDTxokXYAfj+J2hDAYQKJoAaCT2Ea9p…
```

- **Funziona ovunque**: il testo cifrato è testo semplice, quindi passa per WhatsApp, Telegram, email, note.
- **Tutto in locale**: nessun server, nessun account, nessuna connessione a Internet.
- **Compatibile tra dispositivi**: un testo cifrato sul telefono si decifra sul computer (e nella web app
  EasyCrypt) e viceversa, con la stessa password.

## Le due versioni

| | <img src="EasyCryptKeyboardAndroid/icon/icon-rounded-1024.png" width="40" alt=""><br>**Android** | <img src="EasyCrypt-Mac-PC/icon.png" width="40" alt=""><br>**Mac e PC** |
|---|---|---|
| Cos'è | Tastiera in stile Gboard con cifratura integrata | Finestra per cifrare e decifrare, più scorciatoie globali |
| Cifrare | Tocchi il lucchetto e scrivi: nel campo compare il testo già cifrato | Scrivi o incolli nella finestra, oppure selezioni un testo e premi una scorciatoia |
| Decifrare | Selezioni il testo e tocchi «Decifra» (tastiera, menu di selezione o Condividi) | Incolli nella finestra, oppure selezioni e premi una scorciatoia |
| Tecnologia | Kotlin, nativa, senza librerie esterne | Python con tkinter, senza pacchetti obbligatori |
| Cartella | [`EasyCryptKeyboardAndroid/`](EasyCryptKeyboardAndroid/) | [`EasyCrypt-Mac-PC/`](EasyCrypt-Mac-PC/) |
| Guida | [README Android](EasyCryptKeyboardAndroid/README.md) | [README Mac e PC](EasyCrypt-Mac-PC/README.md) |

## Per iniziare

**Android** — scarica l'APK dall'ultima
[release](https://github.com/carellice/easy-crypt/releases/latest), installalo e segui l'introduzione
dell'app: abilita la tastiera, sceglila e imposta la password.

**Mac** — scarica `EasyCrypt-Mac-….zip` dall'ultima [release](https://github.com/carellice/easy-crypt/releases/latest), estrailo e apri `EasyCrypt.app`
(al primo avvio: clic destro → «Apri»). In alternativa, dal codice: serve
[Python 3](https://www.python.org/downloads/), poi doppio clic su `EasyCrypt-Mac-PC/EasyCrypt.app`.

**PC** — scarica `EasyCrypt-PC-….exe` dall'ultima
[release](https://github.com/carellice/easy-crypt/releases/latest) e aprilo. In alternativa, con
[Python 3](https://www.python.org/downloads/) installato: scarica `EasyCrypt-PC-….zip`, estrailo e fai doppio
clic su `Avvia EasyCrypt.bat`.

Su tutti i dispositivi va usata **la stessa password**: è l'unica cosa che serve per leggere i messaggi.

## Come funziona la cifratura

Tutte le versioni usano lo stesso algoritmo, basato su primitive standard:

```
password ──PBKDF2-HMAC-SHA256, 100 000 iterazioni, salt casuale di 16 byte──► chiave AES a 256 bit
testo    ──AES-256-GCM, IV casuale di 12 byte──► testo cifrato + tag di autenticazione (16 byte)

risultato = base64( salt[16] ‖ iv[12] ‖ testo cifrato ‖ tag[16] )
```

- Ogni messaggio ha un IV diverso: lo stesso testo cifrato due volte dà due risultati diversi.
- Il tag di autenticazione rileva una password sbagliata o un testo modificato: in quel caso la decifratura
  fallisce invece di restituire testo senza senso.
- La sicurezza dipende dalla password: usane una lunga e non riutilizzata, e comunicala di persona o su un
  canale diverso da quello dei messaggi.

| Implementazione | File |
|---|---|
| Android (Kotlin, `javax.crypto`) | [`EasyCrypt.kt`](EasyCryptKeyboardAndroid/app/src/main/java/com/easycrypt/keyboard/EasyCrypt.kt) |
| Mac e PC (Python) | [`easycrypt_core.py`](EasyCrypt-Mac-PC/easycrypt_core.py) |

Entrambe hanno test automatici che decifrano testi prodotti dalla web app EasyCrypt.

## Struttura

```
EasyCrypt/
├── Pubblica release.command    crea e pubblica i pacchetti di entrambe le versioni
├── EasyCryptKeyboardAndroid/   tastiera Android (progetto Gradle)
└── EasyCrypt-Mac-PC/           programma per Mac e PC (Python)
```

Le due cartelle sono indipendenti: ognuna si compila, si avvia e si può spostare da sola.

## Pubblicare una release

`Pubblica release.command` (doppio clic, su Mac) incrementa la versione, crea i tre pacchetti e li pubblica con
GitHub CLI come release di [`carellice/easy-crypt`](https://github.com/carellice/easy-crypt):

| Pacchetto | Contenuto |
|---|---|
| `EasyCryptKeyboard-Android-<versione>.apk` | Tastiera Android |
| `EasyCrypt-Mac-<versione>.zip` | `EasyCrypt.app` autonoma, creata con PyInstaller (non richiede Python) |
| `EasyCrypt-PC-<versione>.zip` | Programma in Python con `Avvia EasyCrypt.bat` (richiede Python) |
| `EasyCrypt-PC-<versione>.exe` | Programma autonomo per Windows: lo crea GitHub (`.github/workflows/windows-exe.yml`) qualche minuto dopo la pubblicazione e lo aggiunge alla release |

Servono GitHub CLI autenticata, Android Studio e Python 3 con Tk 8.6+. La versione è unica per tutti i pacchetti
e sta in `EasyCryptKeyboardAndroid/version.properties`; se un passaggio fallisce viene ripristinata e non viene
pubblicato nulla.
