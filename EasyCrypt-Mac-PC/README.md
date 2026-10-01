<div align="center">

<img src="icon.png" width="128" alt="Icona di EasyCrypt">

# EasyCrypt per Mac e PC

**Cifra e decifra i tuoi testi dal computer, con una finestra o con una scorciatoia.**

Stessa cifratura di [EasyCrypt Keyboard per Android](../EasyCryptKeyboardAndroid/README.md) e della web app
EasyCrypt: ciò che cifri sul telefono lo leggi sul computer, e viceversa.

</div>

---

## Cosa puoi fare

### Nella finestra

1. Inserisci la **password**.
2. Scegli **«Cifra»** o **«Decifra»**.
3. Scrivi o incolla il testo: il risultato compare mentre scrivi. Con **«Copia»** lo metti negli appunti.

Se incolli un testo cifrato, EasyCrypt lo riconosce e passa da solo a «Decifra».

### Da qualsiasi programma, con una scorciatoia

Seleziona un testo in una chat, un'email o un documento e premi la scorciatoia: il testo viene cifrato (o
decifrato) e **sostituito sul posto**. Gli appunti tornano poi com'erano.

| | Mac | PC |
|---|---|---|
| Cifra la selezione | `⌃⌥⌘E` (Control + Opzione + Comando + E) | `Ctrl + Alt + Maiusc + E` |
| Decifra la selezione | `⌃⌥⌘D` | `Ctrl + Alt + Maiusc + D` |

EasyCrypt deve restare aperto (anche ridotto a icona) e avere la password inserita.

> **Mac, la prima volta**: alla prima scorciatoia si aprono le Impostazioni in *Privacy e sicurezza ›
> Accessibilità*. Attiva EasyCrypt (il sistema lo richiede per simulare copia e incolla), poi chiudi e riapri
> il programma.

### Opzioni

- **Ricorda la password su questo computer** — viene salvata protetta dal sistema operativo. Senza la spunta
  resta in memoria solo finché il programma è aperto.
- **Finestra sempre in primo piano** — comoda per tenerla accanto a una chat.

## Installazione

**Mac, senza installare Python**: scarica `EasyCrypt-Mac-….zip` dall'ultima
[release](https://github.com/carellice/easy-crypt/releases/latest), estrailo e apri `EasyCrypt.app` (al primo avvio: clic destro → «Apri»).

**PC, senza installare Python**: scarica `EasyCrypt-PC-….exe` dalla stessa release e aprilo (se SmartScreen
avvisa: «Ulteriori informazioni» → «Esegui comunque»).

**Dal codice** (Mac e PC): serve [Python 3](https://www.python.org/downloads/) con tkinter (incluso
nell'installer ufficiale). Non ci sono altri pacchetti da installare. Per il PC lo zip `EasyCrypt-PC-….zip`
della release contiene già tutto il necessario.

| | Come si avvia |
|---|---|
| **Mac** | Doppio clic su `EasyCrypt.app`. Si apre come un programma normale e si può trascinare nel Dock |
| **PC** | Doppio clic su `Avvia EasyCrypt.bat` |

Note per il Mac:

- `EasyCrypt.app` deve restare nella stessa cartella di `easycrypt.py`.
- Il Python fornito da Apple (`/usr/bin/python3`) ha una versione di Tk troppo vecchia e viene ignorato: serve
  quello di python.org o di Homebrew.

### App che non richiede Python

Per usare EasyCrypt su un computer senza Python si può creare un'app autonoma:

| | Script | Risultato |
|---|---|---|
| **Mac** | `Crea app Mac.command` | `dist/EasyCrypt.app` |
| **PC** (da eseguire su Windows) | `Crea app PC.bat` | `dist\EasyCrypt.exe` |

Gli script scaricano PyInstaller in una cartella locale (`.venv-build`). Le app non sono firmate: su un altro
Mac vanno aperte la prima volta con clic destro → «Apri»; su Windows SmartScreen chiede «Ulteriori informazioni»
→ «Esegui comunque».

## Privacy

- Il programma non fa nessuna connessione a Internet.
- La password salvata sta nel Portachiavi (Mac) o in un file cifrato con DPAPI, leggibile solo dal tuo utente
  (Windows).

---

## Per sviluppatori

### Cifratura

PBKDF2-HMAC-SHA256 (100 000 iterazioni) → AES-256-GCM, output `base64(salt[16] + iv[12] + ciphertext + tag)`.

`easycrypt_core.py` funziona con la sola libreria standard: PBKDF2 viene da `hashlib`, AES-GCM da
un'implementazione in puro Python inclusa nel file. Se è installato il pacchetto `cryptography`, AES-GCM usa
quello. Le due strade producono testi intercambiabili e sono entrambe coperte dai test.

```bash
python3 test_easycrypt.py
```

### Struttura

| File | Ruolo |
|---|---|
| `easycrypt.py` | Finestra (tkinter) e flusso delle scorciatoie |
| `easycrypt_core.py` | Algoritmo di cifratura |
| `hotkeys.py` | Scorciatoie globali e tasti simulati via `ctypes` (Carbon e CoreGraphics su Mac, `user32` su Windows) |
| `password_store.py` | Password nel Portachiavi (comando `security`) o con DPAPI |
| `EasyCrypt.app` | Lanciatore per Mac: avvia `easycrypt.py` senza Terminale |
| `Avvia EasyCrypt.bat` | Lanciatore per Windows |
| `Crea app Mac.command`, `Crea app PC.bat` | Creazione dell'app autonoma con PyInstaller |
| `test_easycrypt.py` | Test di compatibilità con i testi cifrati dalla web app |
| `icon.png`, `icon.icns`, `icon.ico` | Icone |

### Come funziona la scorciatoia

1. Attende il rilascio dei tasti della scorciatoia.
2. Salva gli appunti, li svuota e simula «copia».
3. Cifra o decifra il testo copiato, lo mette negli appunti e simula «incolla».
4. Ripristina gli appunti di prima.

Su Mac gli appunti vengono letti e scritti con `pbpaste`/`pbcopy`: tkinter, quando la finestra è in secondo
piano, non vede ciò che copiano gli altri programmi.

### Stato delle verifiche

- **Mac**: finestra, cifratura, Portachiavi e registrazione delle scorciatoie provati; il flusso della
  scorciatoia è provato con copia e incolla simulati.
- **Windows**: il codice specifico (scorciatoie, DPAPI, script `.bat`) non è ancora stato provato su un PC.
