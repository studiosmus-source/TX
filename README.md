# TX – Trasmittente Android

**Versione sorgenti: 0.2.1** – app di accesso remoto **in sola lettura** alla memoria condivisa del dispositivo, anche quando TX e RX si trovano su reti differenti. Il progetto è composto da:

- `app/`: Android nativo Kotlin, schermata permesso, catalogo file (inclusi file nascosti accessibili), lettura in blocchi, client HTTPS in uscita e servizio esplicitamente avviato con notifica visibile. Stato di connessione consultabile dall’app; lettura risposta HTTP limitata durante il download.
- `relay/`: server Node.js 22 senza dipendenze, funzioni di inoltro richieste e risposte in RAM, niente archiviazione permanente dei file.
- `relay/rx-cli.mjs`: ricevente **di prova** da terminale (non è ancora l'APK RX).

## Cosa fa esattamente

TX apre una connessione HTTPS in uscita al server relay e aspetta richieste. Il futuro RX invia un comando di elenco cartella (`list`) o lettura a blocchi (`read`). TX risponde consultando la memoria condivisa già accessibile al suo processo. Non richiede l'apertura di porte sui router; non carica foto o video senza una richiesta di lettura; non replica l'archivio intero.

Non è una connessione peer-to-peer: il server inoltra i dati **temporaneamente in memoria** e può leggere i contenuti (TLS protegge il trasporto, non cifratura end-to-end). Occorrono **due chiavi distinte**, una sul trasmittente e una sul ricevente; il server richiede l'autenticazione su ogni chiamata. Le chiavi non sono presenti nel repository.

## Avvio del relay

Su un server con **HTTPS pubblico**, installare Node.js >= 22, configurare `TX_TOKEN` e `RX_TOKEN` casuali e diversi (minimo 32 caratteri ciascuno), quindi:

```bash
cd relay
node server.mjs
```

Il server ascolta sulla porta `PORT` (predefinita `8080`). **Per Internet deve trovarsi dietro un proxy TLS attendibile** che imposti/sovrascriva `X-Forwarded-Proto: https`, come un provider gestito. Non esporre la porta HTTP direttamente a Internet. Senza TLS valido TX rifiuta il collegamento. La URL finale è `https://...`.

Generare ciascuna chiave con `node -e "console.log(require('node:crypto').randomBytes(32).toString('hex'))"`. Conservare segretamente e separatamente le chiavi; **mai nel GitHub repository, ZIP di sorgenti condiviso o log**. Il relay è a coppia singola nella versione 0.2; riavvii server cancellano le richieste ancora in coda.

## Avvio TX su Android

1. Installare TX sul proprio telefono, aprirla e concedere **Accesso a tutti i file** nella schermata speciale di Android.
2. Inserire una volta l'URL HTTPS del relay e `TX_TOKEN` (non `RX_TOKEN`).
3. Toccare **Salva e avvia**. La notifica Android identifica la sessione remota attiva; dall'app si può toccare **Interrompi connessione**.

Su Android 15+ il servizio `dataSync` in background può avere un limite **cumulativo di 6 ore su 24**. Non è garantita disponibilità 24/7. In futuro servirà una soluzione di sessioni on-demand e/o un'architettura alternativa conforme alle regole del sistema.

L'accesso a tutti i file non dà accesso a `Android/data` e alle directory private di altre app. Anche foto e file esclusi dalle gallerie tramite `.nomedia` sono mostrati se il sistema li rende leggibili.

Le credenziali sono conservate nelle preferenze private dell'app Android; per produzione andranno protette ulteriormente con Android Keystore. L'app non parte automaticamente al riavvio e **non è progettata per funzionare nascosta**.

## Ricevente di prova

Prima dell'APK Android RX, da un computer con Node 22 si può provare:

```bash
RX_TOKEN='<chiave RX>' node relay/rx-cli.mjs https://relay.example list DCIM/Camera
RX_TOKEN='<chiave RX>' node relay/rx-cli.mjs https://relay.example download DCIM/Camera/esempio.jpg ./foto.jpg
```

L'URL deve essere `https://`; il test CLI non accetta server HTTP. Il download della CLI accumula i blocchi in RAM, quindi è adatta a prove su file piccoli, non a video molto grandi. La futura RX Android dovrà scrivere i blocchi progressivamente su disco e supportare ripresa del download.

## Verifiche

- `cd relay && node --test test/*.test.mjs` (test autenticazione e inoltro remoto in-memory).
- `gradle testDebugUnitTest assembleDebug` in ambiente Android SDK 35, Gradle 8.13 e JDK 17.
- `.github/workflows/android.yml` esegue le prove e salva l'APK di debug sotto gli artifact GitHub Actions quando la build riesce.

**Non è stata dichiarata nessuna prova funzionale fra due telefoni reali.** Un server pubblico configurato e l'APK RX sono ancora necessari.
