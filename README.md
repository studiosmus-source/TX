# TX – Android Trasmittente

App Android nativa (Kotlin), prima base di un sistema di consultazione file tra **due dispositivi dello stesso proprietario** su reti differenti.

## Stato reale (v0.1.0)

- Schermata iniziale TX minimal.
- Richiesta del permesso Android `MANAGE_EXTERNAL_STORAGE` tramite le impostazioni di sistema.
- Verifica locale della radice della memoria condivisa; include cartelle e file nascosti accessibili, per esempio `.nomedia`.
- Catalogo di cartelle in sola lettura, con paginazione, ordinamento cartelle-prima e protezione da percorsi fuori dalla radice.
- Test unitari per file nascosti, paginazione e path traversal.
- Workflow GitHub Actions per test e creazione dell'APK **debug** (artifact `TX-debug-apk`).

**Non implementati:** connessione alla ricevente, server/relay, autenticazione remota, download remoto, servizio in background. Nessun file viene spedito via rete dalla v0.1.0.

## Vincoli Android

Su Android 11+ l'autorizzazione a tutti i file non è un normale dialogo runtime SÌ/NO: il sistema apre la schermata delle impostazioni dedicata. Anche con il permesso, Android protegge le cartelle private di altre app (`Android/data`, ecc.).

## Prossima fase prevista

- Canale cifrato tramite relay fra reti diverse, con credenziali uniche per dispositivo e chiavi non inserite in chiaro nell'APK.
- Stato visibile di condivisione, attivazione/disattivazione dal dispositivo trasmittente, autorizzazione esplicita all'accesso remoto.
- Richieste remote di sola lettura per singola cartella e file, senza sincronizzazione completa.
- Gestione dei limiti Android ai servizi in background e test su dispositivi reali.

## Compilazione

Importare il progetto in Android Studio (JDK 17, Android SDK 35), oppure da CI usare il workflow `.github/workflows/android.yml`, che configura Gradle 8.13.
Il repository non contiene il Gradle Wrapper: la CI usa `gradle/actions/setup-gradle` con versione fissata. Nessuna chiave privata o password deve essere committata nel repository.
