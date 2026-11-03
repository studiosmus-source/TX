# GALLERY — TX + RX Android

Versioni candidate: **TX 0.4.11** e **RX 0.2.9** (accoppiamento con un solo avvio).

## Flusso di autorizzazione

1. Sul trasmittente TX, concedi una sola volta l'accesso ai file richiesto da Android e premi **INVIA RICHIESTA A RX**. Alla prima installazione può comparire anche il permesso per le notifiche.
2. TX avvia un servizio in primo piano, invia **una richiesta** e chiude la propria schermata. Continua ad attendere l'approvazione anche con la schermata chiusa. Il codice di verifica compare nella notifica discreta GALLERY (se le notifiche sono abilitate), oppure riaprendo TX.
3. Su RX, nella sezione Attivazioni, confronta il codice e scegli **APPROVA**. TX riceve la chiave su HTTPS, la salva cifrata tramite Android Keystore e avvia automaticamente la connessione al relay. Non occorre riaprire TX per premere AVVIA.
4. RX passa automaticamente alla galleria. Da lì è possibile consultare foto, video, cartelle e altri file condivisi.
5. Nei successivi avvii, se la credenziale TX è ancora salvata, **AVVIA** riparte senza nuovo pairing. Durante una sessione, l’unico pulsante nell’app TX è **TEST**: verifica lo stato del servizio e la raggiungibilità HTTPS senza fermare nulla. Per interrompere l’accesso remoto è disponibile il comando **DISATTIVA** nella notifica Android di GALLERY.

Il polling dell'attivazione è gestito dal servizio TX, non dalla Activity. Errori di rete temporanei durante il polling conservano la stessa richiesta. Una nuova richiesta si crea soltanto dopo scadenza o invalidazione della precedente. Nessun accesso remoto è consentito prima dell'approvazione su RX.

## Cartelle .nomedia

La scheda **NASCOSTI** di RX cerca separatamente foto e video presenti in cartelle `.nomedia` accessibili nella memoria condivisa di TX. La ricerca è avviata esplicitamente da RX e non rallenta l'elenco normale basato su MediaStore. Il contenuto resta sul TX finché l'utente non seleziona SALVA. Le aree private Android e le casseforti protette non sono accessibili.

## Architettura e sicurezza

- `app/`: trasmittente Android. Accesso in sola lettura alle aree della memoria condivisa consentite da Android. Connessione HTTPS in uscita; nessuna porta aperta sul dispositivo.
- `rx/`: ricevitore Android. Autorizzazione, galleria e file. Salvataggio via MediaStore con verifica dei byte e della decodificabilità delle foto.
- `relay/`: backend Node.js su Render. Autorizza separatamente TX e RX, inoltra i dati tramite HTTPS e RAM, senza archiviarne i file su disco. Non è cifratura end-to-end rispetto al server.
- I segreti `TX_TOKEN` e `RX_TOKEN` non sono inclusi negli APK o nei sorgenti. RX li richiede solo alla prima configurazione; TX riceve la propria chiave dopo approvazione.
- TX/RX 0.4.7+/0.2.7+ possono trasferire blocchi fino a 512 KiB, riducendo le richieste rispetto ai vecchi blocchi da 64 KiB.

## Limiti Android

Il servizio mantiene una notifica foreground (piccola e silenziosa) e rimane controllabile e interrompibile dall'utente. Android 15+ limita i servizi `dataSync` a 6 ore cumulative per 24 ore mentre l'app è in background, e ne limita il riavvio automatico dopo un reboot. Su Android 15+ il riavvio dal boot richiede un'azione sulla notifica di promemoria quando consentita. Le app private e i file eliminati definitivamente non sono accessibili con i normali permessi.

## Verifica

Test automatici: `cd relay && node --test test/*.test.mjs`, `gradle :app:testDebugUnitTest :rx:testDebugUnitTest :app:assembleDebug :rx:assembleDebug`. L'esecuzione CI e la compilazione non dimostrano il comportamento su due dispositivi reali. Le build debug GitHub Actions non dispongono di una chiave di firma persistente; non disinstallare una versione configurata senza prima considerare la perdita delle credenziali.
