# GALLERY 0.5 — multi telefono e collegamento quotidiano

Dopo un'approvazione esplicita per **ogni telefono GALLERY**, RX mostra i dispositivi separatamente. Ogni GALLERY usa un identificativo UUID e un token distinto; non riceve la chiave principale di amministrazione.

## Attivazione
1. Installare GALLERY 0.5 su ciascun telefono e RX 0.3 sul telefono ricevitore.
2. In GALLERY, concedere accesso ai file e alle notifiche e confrontare il codice mostrato con quello in RX.
3. Approvare il codice su RX. GALLERY programma una verifica quotidiana tramite WorkManager, anche dopo i riavvii. Le vecchie chiavi GALLERY 0.4.x richiedono una nuova approvazione.
4. In RX scegliere il telefono nella pagina **Dispositivi**. Ogni galleria o download è associato esclusivamente al dispositivo scelto.
5. Sul telefono GALLERY il pulsante **Disattiva collegamento** annulla tutte le attività automatiche.

## Comportamento e limiti verificabili
- All'attivazione viene programmata una prima finestra di connessione. Una nuova finestra di **circa 4 minuti** è programmata **ogni 24 ore**, quando Android consente l'esecuzione e Internet è disponibile.
- WorkManager persiste i lavori dopo riavvio, ma Android 15/16, Doze, risparmio batteria e le restrizioni dei produttori possono ritardare o sopprimere una finestra; nessuna garanzia entro 24 ore esatte.
- RX può trasferire i file **soltanto mentre il dispositivo scelto è online**. I file non sono caricati sul server e non esiste una coda persistente per trasferimenti rinviati.
- Durante ogni finestra, GALLERY pubblica una notifica di collegamento autorizzato; se l'accesso alle notifiche non è concesso, la sincronizzazione automatica non viene avviata. La disattivazione esplicita rimane disponibile.
- Il server su Render mantiene in RAM l'elenco dei dispositivi momentaneamente collegati. Dopo un riavvio i dispositivi si ripresentano al prossimo controllo; RX conserva sul dispositivo gli ID già approvati.
- La disponibilità immediata da RX in qualunque momento **non è garantita**. I dispositivi mostrati offline non rispondono a richieste remote.
- Le chiavi restano protette con Android Keystore. Le comunicazioni usano HTTPS; il relay legge i contenuti in memoria durante il trasferimento, senza cifratura end-to-end.
- Il server deriva la chiave del dispositivo dalla chiave principale e dal suo ID, per cui una modifica di TX_TOKEN richiede una nuova approvazione. La revoca selettiva permanente non è ancora implementata.

Collaudo necessario: almeno due telefoni GALLERY e un RX, prove di approvazione, isolamento delle richieste e dei file, conferma che ogni download provenga dal telefono selezionato, riavvio Android 16, intervallo giornaliero, risparmio batteria, perdita Internet, disattivazione e notifica. Il successo di CI non dimostra che tutte le condizioni Android siano soddisfatte.
