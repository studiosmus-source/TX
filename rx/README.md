# RX Android 0.1.0

L'app RX è un modulo separato (:rx) nel repository TX; non richiede modifiche al relay Render.

Funzioni:
- Inserisci RX_TOKEN e TX_TOKEN **solo in RX** al primo avvio; le chiavi sono cifrate tramite Android Keystore.
- Consulta le richieste di attivazione TX e approva quella con il codice visualizzato sul dispositivo trasmittente.
- Apri "File remoti", naviga tra le cartelle (anche nascoste) e carica altri risultati scorrendo.
- Tocca un file, scegli "Scarica" e seleziona dove salvarlo tramite il selettore documenti Android. Il contenuto viene letto in blocchi e scritto direttamente nel documento, senza accumularlo in RAM.
- Dopo il download, scegli "Apri". È disponibile l'annullamento.

Sicurezza:
- Nessun token nel repository o APK.
- Solo autorizzazione INTERNET: RX non richiede accesso a tutti i file e non esplora la memoria locale.
- Tutto il traffico è HTTPS. I contenuti attraversano il relay in memoria, senza crittografia end-to-end.

Limitazioni:
- Le richieste TX vengono aggiornate ogni 10 secondi soltanto quando RX è aperto; non esistono notifiche push di sistema.
- L'ordine dell'elenco è deciso dal TX (prima cartelle, poi nomi).
- Download senza ripresa dopo chiusura; mantenere RX aperta durante i trasferimenti.
- Render Free può sospendersi per inattività. Il servizio TX può essere sospeso dal sistema Android.
- Dopo l'approvazione, sul trasmittente occorre premere "SALVA E AVVIA".

Build:
gradle :rx:testDebugUnitTest :rx:assembleDebug

L'APK di debug è generato in rx/build/outputs/apk/debug. I test funzionali tra due telefoni reali richiedono un collaudo a parte.
