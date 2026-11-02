# TX – Trasmittente Android

**0.3.0 – Attivazione da remoto, senza campi di configurazione sul trasmittente.**

## Esperienza TX

1. Si apre l'APK e si concede **Accesso a tutti i file** attraverso le impostazioni Android.
2. TX inoltra automaticamente una richiesta di attivazione al relay preconfigurato `https://tx-3dhs.onrender.com`.
3. Dal dispositivo RX si approva la richiesta (tramite la pagina di attivazione web in questa versione), inserendo **RX_TOKEN** per autenticarsi e **TX_TOKEN** per attivare il trasmittente. Selezionare soltanto la richiesta identificata dal codice mostrato da TX.
4. La chiave TX viene consegnata attraverso HTTPS, memorizzata cifrata con **Android Keystore** sul trasmittente e il pulsante **SALVA E AVVIA** diventa disponibile.
5. Quando si preme il pulsante, il servizio Android avvia la connessione HTTPS e risponde alle richieste del ricevente. Premendo nuovamente lo stesso pulsante si interrompe la sessione.

Il trasmittente **non chiede URL né chiavi**. Il relay non archivia i file del telefono, ma inoltra le risposte in RAM. L'accesso da remoto funziona soltanto per le aree consentite da Android: non sono accessibili i dati privati protetti delle altre app. Il server può vedere i dati inoltrati (TLS, non cifratura end-to-end).

## Approva dal browser RX, per ora

Aprire `https://tx-3dhs.onrender.com/pair` sul telefono ricevente.

- Inserire la chiave **RX_TOKEN** (autenticazione della pagina) e la chiave **TX_TOKEN** per autorizzare il TX.
- Premere **Cerca richieste**, confrontare il codice mostrato sul trasmittente, e premere **Approva questa richiesta**.
- Non condividere nessuna chiave con altre persone. Non sono presenti segreti nei sorgenti GitHub o nell'APK.

**Limite attuale:** la pagina web non invia notifiche push; va aperta manualmente. Una vera notifica sul telefono ricevente richiede ancora l'app RX e il suo canale push. Le richieste di attivazione scadono dopo 10 minuti; TX ne invia un'altra se è ancora aperta. Su Render Free lo spin-down può interrompere sessioni non ancora completate.

## Struttura

- `app/`: Android Kotlin, richiesta automatica, Keystore AES-GCM, avvio/interruzione del servizio solo dopo l'approvazione.
- `relay/server.mjs`: HTTPS dietro proxy Render, autenticazione RX e TX, richieste di pairing temporanee, lettura file a blocchi.
- `relay/pair.html` e `relay/pair.js`: pagina amministrativa senza script di terze parti e senza salvataggio delle chiavi nel browser.
- `relay/rx-cli.mjs`: client di collaudo, non APK ricevente.

## Build e test

L'APK non contiene chiavi. `cd relay && node --test test/*.test.mjs` verifica autenticazione e pairing; la pipeline GitHub esegue anche `gradle testDebugUnitTest assembleDebug`. Il relay deve essere pubblicato su Render aggiornando il branch `main`. Le richieste automatiche RX e TX sono **da collaudare su dispositivi reali**.

Il servizio Android può essere sospeso dal sistema dopo i limiti dei foreground service `dataSync` (Android 15+). Non è garantito il funzionamento permanente. L'app TX mostra lo stato e la notifica Android durante la trasmissione.
