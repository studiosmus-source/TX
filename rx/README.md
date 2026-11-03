# GALLERY RX 0.2.9

RX riceve l'autorizzazione e visualizza i file del trasmittente TX.

## Uso

- Una sola configurazione iniziale delle chiavi `RX_TOKEN` e `TX_TOKEN` in RX; le credenziali vengono cifrate tramite Android Keystore.
- In **ATTIVAZIONI** appare la richiesta generata dal servizio TX. Confronta il codice con la notifica GALLERY di TX e premi **APPROVA**.
- Dopo la conferma, RX apre automaticamente **GALLERIA**. Se TX non ha ancora concluso il polling, attende il collegamento iniziale e ritenta senza chiedere un nuovo pairing.
- Tocca una foto o un video per un'anteprima grande, con X per tornare all'elenco e SALVA per il file originale. I file vengono salvati nelle raccolte Android (Pictures/GALLERY, Movies/GALLERY, Music/GALLERY o Download/GALLERY) e verificati prima della pubblicazione.
- L'esplorazione **FILE** mostra le aree accessibili della memoria condivisa di TX, ma non i dati riservati ad altre app o file definitivamente eliminati.

RX 0.2.8 usa blocchi fino a 512 KiB con ritorno ai blocchi da 64 KiB quando necessario; sospende le miniature durante un download. Il server Render continua a usare HTTPS e credenziali distinte, senza modifiche al pairing.

## Foto nascoste da .nomedia

La scheda **NASCOSTI** su RX ricerca solo su richiesta foto e video in cartelle della memoria condivisa TX contrassegnate da un file `.nomedia`. La ricerca procede a blocchi e può richiedere più passaggi su grandi memorie, senza bloccare la normale GALLERIA. I contenuti trovati hanno anteprima e SALVA come gli altri; la scansione è in sola lettura. Serve **TX 0.4.11 o superiore**. Non vengono letti dati privati protetti dalle altre app né i contenuti della cassaforte OPPO.

## Limiti e test

La richiesta RX viene aggiornata automaticamente quando la schermata Attivazioni è aperta, senza notifiche push di sistema. Il download non riprende dopo la chiusura forzata di RX. La connessione TX è soggetta ai limiti di Android sui servizi `dataSync`.

Verifiche locali/CI: `gradle :rx:testDebugUnitTest :rx:assembleDebug`. Il pairing, la chiusura della schermata TX e il funzionamento reale su due telefoni devono essere collaudati sul dispositivo. Le APK debug generate da runner diversi possono avere firme incompatibili per gli aggiornamenti.
