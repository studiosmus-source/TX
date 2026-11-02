# RX 0.3 + GALLERY 0.5 – più telefoni Android

Ogni telefono GALLERY ha un **ID UUID e un token dedicato** generato dal relay al momento dell'approvazione. Il token di amministrazione TX_TOKEN resta esclusivamente su RX/Render e **non viene più consegnato** a GALLERY. I token per dispositivo sono verificabili anche dopo un riavvio Render, senza archiviare le chiavi sul server.

## Collegamento
1. Installare le versioni aggiornate RX 0.3 e GALLERY 0.5 sui telefoni interessati.
2. Le vecchie installazioni GALLERY con chiave condivisa devono essere nuovamente approvate una volta (nessun file viene cancellato).
3. In RX, aprire **Attivazioni** e approvare ciascuna richiesta confrontando il codice sui due schermi.
4. Nella pagina **Dispositivi**, scegliere il telefono *Collegato* e toccare **Apri galleria**.
5. Le operazioni **galleria / cartelle / anteprime / lettura / download** portano l'ID del telefono selezionato. Il relay instrada ciascuna richiesta soltanto verso quel telefono e rifiuta risposte inviate da altri.

## Sicurezza e stato
- Le chiavi GALLERY sono memorizzate cifrate con Android Keystore e vincolate all'ID.
- RX memorizza localmente il nome e l'ID dei telefoni autorizzati (non le loro chiavi) e li mostra come *non collegati* se il relay si riavvia.
- La presenza *Collegato* deriva dai polling HTTP del servizio GALLERY; non garantisce che l'operazione successiva riesca.
- Il vecchio token globale TX_TOKEN **non è più accettato** come credenziale delle API di trasferimento.
- L'identità del dispositivo sopravvive a un riavvio del relay finché TX_TOKEN configurato su Render non cambia.
- Il server di piano Free conserva in RAM soltanto presenza e richieste; RX conserva la lista approvata sul proprio telefono. La revoca permanente di un singolo dispositivo richiederà una lista di revoca persistente e non è implementata.
- I file transitano sul relay HTTPS ma non sono cifrati end-to-end; nessun file viene salvato sul server.
- Nessuna funzione cerca di nascondere la notifica Android del servizio.

## Build e collaudo
GitHub Actions: test Node per routing multi-device e autorizzazione, test Android ed entrambi gli APK debug. Collaudo su più telefoni Android reali ancora necessario.

## RX 0.3.1 — Correzione attivazioni

Nella schermata Dispositivi il pulsante **ATTIVAZIONI** apre la lista delle richieste GALLERY in attesa. Lo stato segnala anche il numero di attivazioni disponibili. In precedenza il pulsante rimandava erroneamente alla stessa schermata, impedendo di approvare un nuovo TX. Nessuna modifica al relay, alle chiavi o ai telefoni GALLERY.
