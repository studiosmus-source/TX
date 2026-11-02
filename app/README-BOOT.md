# GALLERY 0.6.0 — ripristino servizio multi-TX

La versione 0.6.0 recupera il canale HTTPS continuo di GALLERY 0.4.2, adattato alle credenziali per dispositivo introdotte in 0.5. RX 0.3.1 e il server multi-TX rimangono invariati. Nessuna modifica a token, dati personali o autorizzazioni precedentemente salvate.

- Configurazione iniziale su TX: concedere una volta accesso memoria e notifiche. Verificare il codice di attivazione contro quello visualizzato su RX. Dopo approvazione di RX, GALLERY avvia automaticamente il servizio senza premere altri tasti. La schermata termina e viene tolta dalle applicazioni recenti.
- Mentre il servizio foreground è attivo, TX riceve e risponde alle richieste RX tramite HTTPS e mostra la notifica Android di accesso remoto. La notifica non richiede tocchi. È sempre possibile aprire GALLERY e selezionare «DISATTIVA COLLEGAMENTO» o revocare i permessi nelle impostazioni Android.
- Android 15/16 limita il servizio `dataSync` a **6 ore complessive in 24 ore**. Al timeout il servizio termina. Riavviarlo dopo l'esaurimento della quota richiede che l'utente riporti l'app in primo piano; non è garantita disponibilità 24/7.
- Al riavvio, Android 14 e precedenti possono riattivare il servizio dopo avvio se il consenso è ancora presente. Android 15/16 vieta al ricevitore BOOT_COMPLETED di riavviare un servizio dataSync. Restano programmati un controllo silenzioso ogni 24 ore e una finestra di accesso di circa 4 minuti ogni 24 ore solo se le notifiche sono autorizzate, con esecuzione soggetta a Doze e risparmio energetico.
- Se l'autorizzazione alle notifiche è negata, il solo heartbeat non consente accesso ai file. Non è previsto accesso nascosto.

Test di accettazione sul dispositivo: installazione aggiornamento senza perdita delle chiavi, nuova approvazione solo se necessaria, RX riceve galleria e file dal giusto TX, app GALLERY si chiude dopo approvazione, permesso negato blocca trasferimenti, durata servizio / timeout, ripristino da riavvio, revoca, due TX simultanei, duplicati storici RX. Build e test automatici non sostituiscono queste prove.
