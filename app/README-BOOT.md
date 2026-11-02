# GALLERY 0.5.1 — controlli periodici senza notifiche

Flusso di attivazione: installare, concedere l'accesso ai file, verificare che il codice sullo schermo GALLERY coincida con quello della richiesta su RX e approvare da RX. Non è previsto un ulteriore pulsante «Avvia» su TX dopo l'approvazione; l'app sposta la schermata in background automaticamente.

La nuova attività \`GalleryHeartbeatWorker\` effettua un check-in HTTPS quotidiano, senza trasferire file, senza chiedere il permesso notifiche e senza creare notifiche. La pianificazione WorkManager persiste dopo i riavvii, ma non garantisce esecuzioni a orari esatti. Sul relay \`POST /v1/tx/heartbeat\` aggiorna solo l'ultimo check-in del dispositivo: **non lo rende online per richieste di file** e rifiuta chiavi non valide.

Il trasferimento remoto vero e proprio resta separato. GALLERY mantiene una notifica durante le finestre in cui i file del telefono possono essere consultati. Se il permesso notifiche non è stato accordato, il worker remoto non viene programmato e RX non può leggere galleria o file; può vedere il telefono registrato come non collegato. Le autorizzazioni per la memoria restano revocabili dalle impostazioni Android. Un nuovo TX non può essere approvato senza confrontare il codice visualizzato sul telefono d'origine.

L'app GALLERY continua a essere installata e visibile nell'elenco delle applicazioni, anche dopo che la sua schermata è stata spostata in background. Il comando di disattivazione nell'app annulla sia check-in sia accesso remoto.

Verifica automatica: test Node per autenticazione heartbeat, mancata abilitazione del trasferimento file, isolamento multi-TX; test/build Android. Collaudo dispositivo reale e reboot resta necessario.
