# GALLERY — ripresa del servizio dopo il riavvio

Comportamento previsto solo dopo che l'utente ha concesso l'accesso ai file, ha completato l'autorizzazione RX e ha avviato volontariamente il collegamento almeno una volta:

- Android 11–14: al broadcast BOOT_COMPLETED GALLERY tenta l'avvio del proprio foreground service dataSync, con notifica visibile.
- Android 15–16: Android vieta avviare un foreground service dataSync da BOOT_COMPLETED. GALLERY pubblica, **se l'utente ha concesso il permesso notifiche**, il promemoria **«Tocca per riprendere il collegamento»**. Un tocco apre l'app, che ripristina la connessione e ritorna alla home. Il collegamento continuo senza alcun intervento non è supportato su queste versioni.
- Riavvio del processo quando il sistema lo consente: il servizio usa START_STICKY, ma questa non è una garanzia di persistenza; restano i limiti del sistema e il limite dataSync di 6 ore cumulative ogni 24 ore su Android 15+.
- **INTERROMPI CONNESSIONE** elimina l'intenzione di ripartire e cancella il promemoria. L'avvio involontario non è consentito.
- Se l'app è stata arrestata forzatamente dall'utente, le trasmissioni di boot possono essere sospese fino a un nuovo avvio manuale.
- Al primo avvio viene richiesto l'accesso alle notifiche. Se negato, GALLERY resta avviabile manualmente, ma Android 15+ non può mostrare il promemoria.

Verifica consigliata su dispositivi reali: autorizzazione iniziale, avvio, riavvio Android 14 e Android 16, notifica e tap, comando Interrompi, assenza di riavvio dopo Interrompi, perdita e ritorno della rete, permessi negati, eventuale riavvio dopo installazione update e timer Android dataSync. Compilazione in CI non equivale a test funzionale di riavvio.
