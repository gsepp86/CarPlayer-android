# CarPlayer

Lettore musicale per autoradio Android (e telefoni). L'audio passa da un motore PCM diretto con ripiego su ExoPlayer (AndroidX Media3).

## Cosa fa
- **Sinistra**: disco in vinile che gira con la copertina del brano (incorporata nel file, oppure `cover.jpg` / `folder.jpg` nella cartella). Tocca il disco per play/pausa.
- **Centro**: titolo (scorre se lungo), artista, **cuore** per aggiungere/togliere dai preferiti, barra di avanzamento, shuffle, precedente, play/pausa, successivo, ripeti (off / tutti / brano).
- **Destra**: elenco brani della cartella con titoli (34sp su 1024x600) e autori grandi. In alto due voci compatte: **ALBUM** (tocca per cambiare cartella) e il **cuore rosso** dei preferiti. Toccando un brano la riproduzione parte subito, senza premere play. Titolo e autore sono in **Barlow Bold** (largo e leggibile, non condensato) e scorrono da soli quando non entrano nella riga. Le tracce sono separate da una **linea sottile** nel colore del tema. Con uno swipe verso destra la lista si nasconde e disco e testi si allargano; tocca la linguetta `‹` sul bordo destro per riportarla, oppure **tocca fuori dal pannello** per chiuderla. Scegliendo ALBUM ogni cartella ha un **numero progressivo** e un'**icona disco**, così si riconosce subito.
- **Striscia di miniature** sotto i comandi: scorri col dito tra le copertine dei brani della coda e tocca per cambiare al volo. Il brano in esecuzione è evidenziato (bordo, titolo sottolineato); sottolineato anche nella lista a destra. Mostra fino a 40 brani prima e 40 dopo quello attuale. La didascalia è una **casella su due righe** con carattere più grande; se il brano non ha copertina, al suo posto c'è una **sfumatura di colore casuale** (sempre la stessa per quel brano).
- **Barra superiore**: marchio, stato ("IN RIPRODUZIONE 4/12") con equalizzatore animato e orologio con data.
- Titolo grande su due righe, in carattere robotico **Orbitron** (licenza OFL, in `app/src/main/assets/fonts/`).
- **Sfondo sfumato**: 7 combinazioni di colori scelte dall'ingranaggio, ritinte dal colore della copertina del brano.
- Continua a suonare in background (navigatore aperto), tasti del volante/Bluetooth, notifica con comandi.
- Ricorda ultimo brano, posizione, cartella, shuffle/ripeti e preferiti.
- Pausa automatica se si scollega l'audio, abbassa il volume per gli avvisi del navigatore.
- **Audio**: due motori in cascata. Prima **PCM diretto** (MediaExtractor + MediaCodec + AudioTrack, lo stesso percorso del «Prova audio» che su queste autoradio si sente), poi **ExoPlayer (AndroidX Media3)** se il PCM non riesce. Tutto avviene su thread dedicati e le scritture audio sono non bloccanti: l'interfaccia non si blocca mai. I brani si aprono tramite l'**URI di MediaStore** (`content://`), non con il percorso file: sulle chiavette USB Android nega il percorso diretto (`EACCES`) e la musica salterebbe subito al brano dopo. Con **Automatico** non si forza nessuna rotta audio (niente `setPreferredDevice`, niente speakerphone, nessun cambio di modalità). Il focus audio non mette in pausa e l'avviso "audio scollegato" non ferma il brano.
- **Comandi sempre visibili**: play, precedente, successivo, shuffle e ripeti stanno fuori dall'area che scorre, quindi non finiscono mai sotto la striscia delle copertine.
- **Ingranaggio** in alto a destra: scegli il **colore principale** (nella palette c'è anche il rosso) e lo **sfondo sfumato**, l'**uscita audio** (Automatico, cassa interna, USB, Bluetooth…); **Salva** memorizza, **Annulla** scarta. C'è anche la diagnostica (volume, modalità, ultimo errore, **esito del controllo del brano**: apertura file, tipo audio e decoder trovato) e **Prova audio**, che suona un tono di 1,5 s. La sezione **PREFERITI** ha **Salva playlist dei cuori (.m3u)** e **Ripristina**: il file resta anche dopo una disinstallazione. In fondo c'è la sezione **INFO**.

## Come ottenere l'APK
**Android Studio (più semplice):** File > Open > scegli questa cartella, attendi la sincronizzazione, poi
Build > Build Bundle(s) / APK(s) > Build APK(s). L'APK è in `app/build/outputs/apk/`.

**GitHub (senza installare nulla):** carica la cartella in un repository; Actions > "Build APK" > Run workflow.
Scarica l'APK dagli artifact.

Poi copia l'APK sull'autoradio (USB) e installalo (serve consentire le "origini sconosciute").
