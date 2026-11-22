# CarPlayer

Lettore musicale leggero per autoradio Android (e telefoni). Nessuna libreria esterna.

## Cosa fa
- **Sinistra**: disco in vinile che gira con la copertina del brano (incorporata nel file, oppure `cover.jpg` / `folder.jpg` nella cartella). Tocca il disco per play/pausa.
- **Centro**: titolo (scorre se lungo), artista, **cuore** per aggiungere/togliere dai preferiti, barra di avanzamento, shuffle, precedente, play/pausa, successivo, ripeti (off / tutti / brano).
- **Destra**: elenco brani della cartella. Tocca il nome cartella per cambiarla; tocca **Preferiti** per vedere solo i brani col cuore. Toccando un brano la riproduzione parte subito, senza premere play. Titolo e autore sono in **Barlow Bold** (largo e leggibile, non condensato) e scorrono da soli quando non entrano nella riga. Con uno swipe verso destra la lista si nasconde e disco e testi si allargano; tocca la linguetta `‹` sul bordo destro per riportarla.
- **Striscia di miniature** sotto i comandi: scorri col dito tra le copertine dei brani della coda e tocca per cambiare al volo. Il brano in esecuzione è evidenziato (bordo verde, titolo sottolineato); sottolineato anche nella lista a destra. Mostra fino a 40 brani prima e 40 dopo quello attuale.
- **Barra superiore**: marchio, stato ("IN RIPRODUZIONE 4/12") con equalizzatore animato e orologio con data.
- Titolo grande su due righe, in carattere robotico **Orbitron** (licenza OFL, in `app/src/main/assets/fonts/`).
- Sfondo sfumato che prende il colore della copertina.
- Continua a suonare in background (navigatore aperto), tasti del volante/Bluetooth, notifica con comandi.
- Ricorda ultimo brano, posizione, cartella, shuffle/ripeti e preferiti.
- Pausa automatica se si scollega l'audio, abbassa il volume per gli avvisi del navigatore.
- **Audio**: come AIMP e gli altri lettori del Play Store, con **Automatico** l'app non forza nessuna rotta: la musica esce dall'instradamento di Android, quello che sull'autoradio funziona. Le versioni precedenti forzavano le casse interne con `setPreferredDevice` e lo speakerphone, e su questi HAL l'audio finiva nel nulla (play acceso, casse mute, "Altoparlante telefono" in tendina). In più il focus audio non mette più in pausa (su molte radio viene revocato appena parte la musica) e l'avviso "audio scollegato" non ferma più il brano.
- **Ingranaggio** in alto a destra: scegli il **colore principale** e lo **sfondo** da una palette, e se serve un'**uscita audio** diversa (Automatico, cassa interna, USB, Bluetooth…). Colori e uscita restano memorizzati. Lo stesso elenco di uscite si può scorrere toccando lo stato in alto.

## Come ottenere l'APK
**Android Studio (più semplice):** File > Open > scegli questa cartella, attendi la sincronizzazione, poi
Build > Build Bundle(s) / APK(s) > Build APK(s). L'APK è in `app/build/outputs/apk/`.

**GitHub (senza installare nulla):** carica la cartella in un repository; Actions > "Build APK" > Run workflow.
Scarica l'APK dagli artifact.

Poi copia l'APK sull'autoradio (USB) e installalo (serve consentire le "origini sconosciute").
