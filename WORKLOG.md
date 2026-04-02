# Worklog

Questo file tiene traccia delle modifiche richieste e applicate al plugin `UVCCamera-1.0.0`.

Formato usato:

- data
- richiesta/problema
- modifica fatta
- motivo tecnico
- stato

## 2026-04-01

### 0. Stabilizzazione dopo crash nativo del callback raw

- Richiesta/problema:
  i log finali del totem mostravano un crash nativo `SIGSEGV` dentro `libUVCCamera.so` nel percorso `UVCPreview::do_capture_callback`, quindi il ramo basso livello basato su `UVCCamera.setFrameCallback(...)` non era affidabile su questo device/build.
- Modifica fatta:
  in `src/android/UsbUvcCamera.java` e' stato rimosso l'uso operativo del callback raw basso livello:
  - niente piu' installazione del callback dentro `configureUnderlyingPreviewStream()`
  - niente piu' reinstallazione del callback durante i retry di `takePhoto()`
  - `clearUnderlyingFrameCallback()` ora pulisce solo lo stato locale dei preview frame
- Motivo tecnico:
  riportiamo il plugin a una baseline stabile lasciando solo i percorsi camera-backed ad alto livello AUSBC, cosi' i prossimi log diranno chiaramente se `addPreviewDataCallBack(...)` consegna frame utili senza innescare crash nativi.
- Stato:
  implementato.

### 0b. Allineamento dimensione reale dei frame preview AUSBC

- Richiesta/problema:
  dopo la stabilizzazione, il callback alto livello `addPreviewDataCallBack(...)` ha iniziato a consegnare frame validi, ma in alcuni log la lunghezza dei byte non coincideva con la dimensione preview negoziata.
- Modifica fatta:
  in `src/android/UsbUvcCamera.java` il callback preview ora prova prima a verificare se il `byteLength` corrisponde davvero a `previewWidth x previewHeight`; se non coincide, ricava una size compatibile dai byte ricevuti e salva quella dentro `latestPreviewFrameWidth/Height`.
- Motivo tecnico:
  questo evita di etichettare i frame con una size sbagliata e rende piu' affidabile sia il filtro dei frame scuri sia la conversione JPEG del percorso `takePhoto()` quando il backend preview usa una risoluzione reale diversa da quella negoziata.
- Stato:
  implementato.

### 0c. Richiesta still al massimo target disponibile e rimozione log rumoroso

- Richiesta/problema:
  il comportamento corrente va mantenuto, ma l'acquisizione deve tentare sempre la massima risoluzione/qualita' possibile; inoltre il log di match della size preview veniva stampato ripetutamente.
- Modifica fatta:
  in `src/android/UsbUvcCamera.java`:
  - il tentativo high-res ora prepara la `HighResPhotoRequest` con la size migliore disponibile tra richiesta iniziale, preview negoziata e `available-preview-sizes`
  - il timeout del tentativo high-res e' stato alzato a `5000 ms`
  - sono stati rimossi i `Log.i(...)` ripetitivi dentro `resolvePreviewSizeForFrame(...)`
- Motivo tecnico:
  cosi' continuiamo a mantenere il fallback preview che oggi funziona, ma chiediamo sempre il massimo backend possibile prima di ripiegare; allo stesso tempo i log restano leggibili.
- Stato:
  implementato.

### 0d. Implementazione del backend still nativo offscreen

- Richiesta/problema:
  `ausbc-capture-image` continua a restituire `640x480`, quindi serve un tentativo still alternativo che provi a sfruttare direttamente `UVCCamera` alla risoluzione preview realmente negoziata.
- Modifica fatta:
  implementato `src/android/NativeStillCaptureBackend.java`:
  - usa `UVCCamera.startCapture(Surface)` verso una `ImageReader` offscreen in `RGBA_8888`
  - acquisisce un frame alla size preview corrente del layer UVC
  - lo converte in JPEG qualita' `100`
  - salva anche il file su `outputPath`
  inoltre `src/android/UsbUvcCamera.java` ora passa il riferimento all'`UVCCamera` sottostante al backend nativo.
- Motivo tecnico:
  questo backend entra come primo tentativo nel composito e prova a superare il limite di `captureImage()` di AUSBC senza toccare il fallback preview-based che oggi funziona.
- Stato:
  implementato, da verificare su device con log runtime.

### 0e. Correzione crop del backend still nativo

- Richiesta/problema:
  il backend `native-still-capture` produceva una JPEG `1920x1080`, ma l'immagine utile risultava piccola e posizionata in alto a sinistra.
- Modifica fatta:
  in `src/android/NativeStillCaptureBackend.java` il frame RGBA acquisito offscreen viene ora analizzato e ritagliato automaticamente all'area realmente popolata prima della compressione JPEG; inoltre il risultato espone le dimensioni effettive dell'area utile, non piu' quelle del canvas pieno.
- Motivo tecnico:
  se `UVCCamera.startCapture(Surface)` scrive il contenuto reale solo in una porzione del buffer di destinazione, ritagliare i bordi vuoti evita di restituire un'immagine corretta ma incollata nell'angolo alto sinistro.
- Stato:
  implementato, da verificare su device con log runtime.

### 0f. Nessun ridimensionamento/crop lato app sul path still nativo

- Richiesta/problema:
  l'obiettivo non e' "farla vedere bene" ritagliandola nell'app, ma ottenere davvero una foto alla risoluzione richiesta senza resize/crop artificiale lato applicazione.
- Modifica fatta:
  in `src/android/NativeStillCaptureBackend.java` e' stato rimosso l'approccio di crop dell'area utile; ora il backend:
  - verifica se il contenuto catturato riempie davvero quasi tutto il frame richiesto
  - se il frame non e' full-frame, rilancia un tentativo dopo aver richiesto esplicitamente la preview nativa alta (`MJPEG`, poi fallback `YUYV`)
  - accetta solo JPEG full-frame reali, senza ritaglio o upscaling lato app
- Motivo tecnico:
  cosi' il backend non "maschera" piu' una sorgente piccola dentro un canvas grande; o otteniamo davvero il frame ad alta risoluzione, oppure il tentativo nativo viene considerato non valido.
- Stato:
  implementato, da verificare su device con log runtime.

### 0g. Stabilizzazione teardown del backend still nativo dopo partial-frame

- Richiesta/problema:
  i log del 2026-04-02 hanno confermato che il path `native-still-capture` vede contenuto reale `640x480` dentro un canvas `1920x1080`; inoltre, dopo il `PartialFrameException`, il processo poteva ancora andare in `SIGSEGV` mentre il fallback AUSBC era gia' partito.
- Modifica fatta:
  in `src/android/NativeStillCaptureBackend.java`:
  - rimosso il secondo tentativo immediato del backend nativo
  - il teardown ora aspetta la fine del callback `ImageReader` e prova anche a fare `join()` del `HandlerThread` prima di restituire il controllo al composito
- Motivo tecnico:
  il backend nativo deve fallire in modo pulito quando non ottiene un vero full-frame ad alta risoluzione, senza lasciare lavoro nativo/thread attivi che possano collidere con il fallback successivo.
- Stato:
  implementato, da verificare su device con log runtime.

### 0h. Ritorno al path preview 1920x1080 gia' validato nel worklog

- Richiesta/problema:
  il backend `native-still-capture` non eredita il vero full-frame del path preview gia' validato e continua a vedere contenuto utile `640x480` dentro un canvas piu' grande; questo contraddice l'obiettivo di ottenere la foto dalla sorgente realmente a `1920x1080`.
- Modifica fatta:
  in `src/android/UsbUvcCamera.java`:
  - rimosso `NativeStillCaptureBackend` dal composito high-res
  - `attemptTakePhoto()` torna a provare prima la cattura JPEG dalla `TextureView` della preview alla size preview negoziata/reale
  - il frame raw preview resta come fallback successivo
- Motivo tecnico:
  il worklog aveva gia' validato che il path preview basso/MJPEG arriva davvero a `1920x1080`; quindi ha piu' senso usare direttamente quella superficie reale come sorgente foto, invece di inseguire un backend still nativo che al momento non eredita il full-frame.
- Stato:
  implementato, da validare su device con log runtime.

### 0i. Rimozione completa del backend still nativo dal sorgente

- Richiesta/problema:
  i log successivi mostravano ancora crash nel thread di capture nativo senza nemmeno arrivare ai log di `takePhoto()`, quindi serviva eliminare ogni ambiguita' sul fatto che l'APK testato potesse ancora contenere il backend still nativo sperimentale.
- Modifica fatta:
  - eliminato `src/android/NativeStillCaptureBackend.java`
  - aggiunto in `src/android/UsbUvcCamera.java` un log one-shot all'inizio di `attemptTakePhoto()`:
    `Photo capture path active: preview-texture-primary, raw-preview-fallback`
- Motivo tecnico:
  cosi' il prossimo test puo' confermare in modo netto che il percorso foto attivo e' solo quello basato sulla preview validata nel worklog, senza alcun backend still nativo residuo.
- Stato:
  implementato; al prossimo test serve una reinstallazione pulita dell'APK/plugin.

### 0l. `captureImage()` AUSBC sotto soglia non viene piu' trattato come successo finale

- Richiesta/problema:
  nei log del 2026-04-02 `captureImage()` chiudeva rapidamente con un file `640x480` e il plugin lo accettava come `High-res capture backend success`, impedendo di entrare nel path `TextureView` gia' riallineato alla preview `1920x1080`.
- Modifica fatta:
  in `src/android/AusbcHighResPhotoCaptureBackend.java` il backend ora verifica la risoluzione reale del file prodotto; se `width/height` sono inferiori alla `HighResPhotoRequest`, il risultato viene rifiutato con errore esplicito invece di essere restituito come successo.
- Motivo tecnico:
  `ausbc-capture-image` resta un tentativo utile, ma non deve piu' bloccare il fallback ad alta risoluzione quando produce solo `640x480`.
- Stato:
  implementato; al prossimo test il fallback `takePhoto()` dovrebbe poter proseguire verso `preview-texture-primary`.

### 1. Rimozione fallback screenshot-based

- Richiesta/problema:
  il fallback finale basato su contenuto renderizzato (`TextureView`/`PixelCopy`) non rispetta il requisito di fare una foto dalla camera, ma finisce per comportarsi come una cattura della preview a schermo.
- Modifica fatta:
  in `src/android/UsbUvcCamera.java` e' stato rimosso il fallback screenshot-based; `takePhoto()` ora considera validi solo percorsi camera-backed:
  - `captureImage()` del backend AUSBC
  - frame raw realmente consegnati dal callback preview/UVC
- Motivo tecnico:
  se i percorsi camera-backed non consegnano dati reali, e' preferibile fallire in modo esplicito piuttosto che restituire un'immagine che non proviene davvero dal sensore.
- Stato:
  implementato.

### 2. Filtro dei preview frame quasi neri

- Richiesta/problema:
  in alcuni casi il plugin riceve buffer preview formalmente validi ma visivamente quasi neri; questi venivano comunque convertiti in JPEG neri.
- Modifica fatta:
  in `src/android/UsbUvcCamera.java` i frame NV21 con luminanza media e picco troppo bassi vengono scartati sia dal preview callback AUSBC sia dal callback basso `UVCCamera`.
- Motivo tecnico:
  evita di trattare come "foto riuscita" un frame iniziale/vuoto/nero del backend preview.
- Stato:
  implementato, da validare con log runtime.

### 3. Dump one-shot delle API backend reali

- Richiesta/problema:
  serviva capire quali metodi espone davvero la libreria caricata sul totem per individuare un eventuale percorso foto alternativo a `captureImage()`.
- Modifica fatta:
  in `src/android/UsbUvcCamera.java` viene loggato una sola volta, all'apertura camera, uno snapshot delle API disponibili su:
  - `CameraRequest.Builder`
  - `MultiCameraClient.Camera`
  - `UVCCamera`
- Motivo tecnico:
  permette di vedere dal `logcat` del device reale quali entrypoint preview/capture/frame sono davvero presenti nella build in uso.
- Stato:
  implementato, in attesa del prossimo log di apertura camera.

### 4. Rimozione riavvio preview low-level durante la negoziazione

- Richiesta/problema:
  i log runtime mostrano warning nativi `UVCCamera::window does not exist/already running/could not create thread etc.` durante la riconfigurazione del layer basso `UVCCamera`.
- Modifica fatta:
  in `src/android/UsbUvcCamera.java` la funzione `configureUnderlyingPreviewStream()` non forza piu' `stopPreview()` e `startPreview()` sul `UVCCamera` sottostante mentre AUSBC gestisce gia' la preview.
- Motivo tecnico:
  riduce il conflitto tra il lifecycle preview di AUSBC e quello della `UVCCamera` low-level, lasciando al layer basso solo la negoziazione dimensione/formato e il tentativo di installare il frame callback.
- Stato:
  implementato, da validare con prossimo log runtime.

## 2026-03-30

### 1. Creazione plugin UVC standalone

- Richiesta/problema:
  serviva separare il nuovo plugin UVC dal vecchio plugin basato su `Camera2`.
- Modifica fatta:
  e' stato impostato un plugin standalone Cordova Android con `plugin.xml`, `package.json`, bridge JS e implementazione Android dedicata in `src/android/UsbUvcCamera.java`.
- Motivo tecnico:
  la webcam Logitech del totem era visibile via `UsbManager`, ma non via `CameraManager`, quindi il vecchio plugin non era una base affidabile.
- Stato:
  completato.

### 2. Dipendenza AUSBC / AndroidUSBCamera

- Richiesta/problema:
  serviva integrare una libreria UVC utilizzabile dal build server Cordova.
- Modifica fatta:
  in `src/android/build.gradle` e' stato configurato:

  ```gradle
  implementation('com.github.jiangdongguo:AndroidUSBCamera:3.2.7') {
      exclude group: 'com.gyf.immersionbar', module: 'immersionbar'
      exclude group: 'com.zlc.glide', module: 'webpdecoder'
  }
  ```

- Motivo tecnico:
  la versione `3.2.8` e il modulo `libausbc` non erano risolvibili correttamente sul build server; la `3.2.7` top-level invece si risolve, ma va ripulita da dipendenze demo che rompono la build.
- Stato:
  completato, build riuscita.

### 3. Allineamento API Java alla libreria 3.2.7

- Richiesta/problema:
  il file `UsbUvcCamera.java` usava classi e firme non compatibili con la `3.2.7`.
- Modifica fatta:
  sono stati corretti:
  - `CameraUVC` -> `MultiCameraClient.Camera`
  - `USBMonitor` -> `com.serenegiant.usb.USBMonitor`
  - `getDeviceList()` -> `getDeviceList(null)`
  - callback `onCameraState(...)`
  - rimozione di `CameraRequest.PreviewFormat`
- Motivo tecnico:
  la libreria realmente risolta dal server espone API diverse da quelle assunte nelle prime versioni del plugin.
- Stato:
  completato, build riuscita.

### 4. Implementazione lifecycle UVC minimo

- Richiesta/problema:
  serviva un flusso minimo reale per test runtime su totem.
- Modifica fatta:
  in `src/android/UsbUvcCamera.java` sono stati implementati:
  - `open`
  - `close`
  - `takePhoto`
  - `recoverCamera`
  - `listUsbDevices`
  - preview nascosta `TextureView` 1x1
- Motivo tecnico:
  serviva poter selezionare la webcam USB, aprirla e tentare uno scatto fuori dal vecchio stack `Camera2`.
- Stato:
  completato lato build, da validare a runtime.

### 5. Workaround Android 13+ per registerReceiver

- Richiesta/problema:
  a runtime compariva:
  `SecurityException: One of RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED should be specified`
- Modifica fatta:
  e' stato aggiunto un wrapper `safeRegisterCameraClient()` che tenta il `register()` normale e, in caso di `SecurityException`, esegue una registrazione compatibile Android 13+ via reflection sul `USBMonitor`, usando `Context.RECEIVER_NOT_EXPORTED`.
- Motivo tecnico:
  la libreria esterna usa una `registerReceiver(...)` legacy non compatibile con Android recenti.
- Stato:
  completato in codice, da validare a runtime.

### 6. Salvataggio foto in storage app-specifico

- Richiesta/problema:
  evitare dipendenze inutili da permessi media/storage lato Android.
- Modifica fatta:
  `takePhoto()` salva i file in `getExternalFilesDir(Environment.DIRECTORY_PICTURES)`.
- Motivo tecnico:
  riduce fragilita' sui permessi e semplifica il funzionamento in ambiente kiosk.
- Stato:
  completato.

### 7. Primo test runtime positivo su open UVC

- Richiesta/problema:
  verificare se il nuovo plugin UVC riusciva almeno ad aprire la Logitech sul totem.
- Modifica fatta:
  test eseguito lato app con `listUsbDevices()` + `openCamera()`.
- Motivo tecnico:
  serviva confermare che il backend UVC nuovo superasse il vecchio limite del `CameraManager`.
- Stato:
  completato con esito positivo.
  Risultato osservato:
  - `selected cameraId: uvc:1133:2093`
  - `openCamera` restituisce `backend: "uvc"` e i dati della Logitech C920.

### 8. Problema runtime su takePhoto subito dopo open

- Richiesta/problema:
  `takePhoto()` fallisce con `USB UVC camera not opened` anche dopo `openCamera()` riuscito.
- Modifica fatta:
  e' stato aggiunto un warmup/retry interno a `takePhoto()` nel plugin:
  - fino a 6 tentativi
  - attesa di 350 ms tra un tentativo e il successivo
  - se `currentCamera` e' `null`, viene tentata una riapertura automatica del device corrente prima di fallire
- Motivo tecnico:
  il callback di `open()` arriva prima che lo stato interno sia considerato abbastanza pronto per `takePhoto()`, oppure il plugin segnala open su un evento troppo anticipato rispetto alla preview attiva.
- Stato:
  fix applicato, da validare a runtime.

### 9. Tracing runtime su open/takePhoto

- Richiesta/problema:
  serviva capire dove si interrompe realmente il flusso tra `open()` e `takePhoto()`.
- Modifica fatta:
  sono stati aggiunti log mirati in `UsbUvcCamera.java` su:
  - selezione device
  - `onConnectDev`
  - creazione `currentCamera`
  - `onCameraState`
  - `attemptTakePhoto`
  - `captureImage`
  - release camera
- Motivo tecnico:
  il prossimo test deve chiarire se la camera viene persa, se non entra davvero in stato preview, oppure se il capture non completa.
- Stato:
  completato, in attesa di log runtime.

### 10. Mitigazione race su disconnect durante open

- Richiesta/problema:
  dai log e' emerso che durante `open()` arrivava un `USB disconnect callback` spurio che causava `releaseCamera()` prima del completamento vero dell'apertura.
- Modifica fatta:
  e' stato introdotto il flag `openingCamera` e i callback `onDetachDec` / `onDisConnectDec` ora ignorano detach/disconnect del device selezionato mentre l'apertura e' ancora in corso.
- Motivo tecnico:
  evitare che `currentCamera` venga azzerata subito prima di `State.OPENED`, generando reopen inutili su `takePhoto()` e warning di `Surface` / `UsbDeviceConnection` non rilasciate.
- Stato:
  completato in codice, da validare a runtime.
  Nota aggiuntiva:
  e' stato necessario separare il cleanup interno usato prima di un reopen (`closeCurrentCamera(false)`) dalla chiusura vera (`releaseCamera()`), per non resettare `openingCamera` troppo presto durante `openConnectedDevice()`.

### 11. Timeout esplicito su takePhoto

- Richiesta/problema:
  i log mostravano `UVC capture onBegin` ma non ancora `onComplete` o `onError`, con rischio di callback pendente.
- Modifica fatta:
  e' stato aggiunto un timeout di 6 secondi sullo scatto:
  - pianificato all'inizio di `takePhoto()`
  - cancellato su `onComplete`, `onError` e `releaseCamera()`
  - in caso di scadenza restituisce `UVC capture timeout`
- Motivo tecnico:
  evitare che il flusso applicativo resti bloccato se la libreria UVC non chiude il callback finale.
- Stato:
  completato in codice, da validare a runtime.

### 12. Fallback capture da preview frame

- Richiesta/problema:
  `captureImage()` della libreria entrava in `onBegin` ma sul totem non completava mai con `onComplete` o `onError`.
- Modifica fatta:
  `takePhoto()` non usa piu' il callback finale di `captureImage()`.
  Invece:
  - aggancia i frame preview NV21 con `addPreviewDataCallBack(...)`
  - conserva l'ultimo frame disponibile
  - risolve la dimensione reale del frame
  - salva direttamente il JPEG con `MediaUtils.saveYuv2Jpeg(...)`
- Motivo tecnico:
  evitare il punto di stallo interno alla libreria AUSBC/UVCCamera e usare un flusso di capture piu' controllabile dal plugin.
- Stato:
  completato in codice, da validare a runtime.
  Nota di compatibilita':
  da Java l'helper Kotlin `MediaUtils` va invocato come `MediaUtils.INSTANCE.saveYuv2Jpeg(...)`.

### 13. Primo successo end-to-end open + takePhoto

- Richiesta/problema:
  verificare se il nuovo plugin UVC riusciva a completare davvero il flusso applicativo completo.
- Modifica fatta:
  test eseguito lato app con:
  - `listUsbDevices()`
  - `openCamera()`
  - `takePhoto()`
- Motivo tecnico:
  serviva confermare che il fallback di scatto da preview frame eliminasse il blocco del vecchio `captureImage()`.
- Stato:
  completato con esito positivo.
  Risultato osservato:
  - `openCamera` riuscito con Logitech C920
  - `takePhoto` riuscito con path restituito:
    `/storage/emulated/0/Android/data/com.tesisquare.totemKK/files/Pictures/UsbUvcCamera/...jpg`

### 14. Reconnect automatico dopo detach/disconnect

- Richiesta/problema:
  quando la webcam viene staccata e riattaccata, il plugin perde il collegamento e lo scatto fallisce con camera non pronta.
- Modifica fatta:
  e' stato aggiunto un reconnect automatico:
  - attivato dopo `open()` / `recoverCamera()`
  - schedulato dopo `onDetachDec` / `onDisConnectDec`
  - ritenta `requestPermission(currentDevice)` dopo una breve attesa
- Motivo tecnico:
  migliorare il comportamento kiosk 24/7 senza richiedere riavvio app o dispositivo dopo unplug/replug della webcam.
- Stato:
  completato in codice, da validare a runtime.
  Nota aggiuntiva:
  il reconnect ora non si affida piu' solo al vecchio `currentDevice`, ma risolve di nuovo il device corrente tramite `vendorId/productId`, per gestire meglio unplug/replug dove il riferimento USB precedente diventa obsoleto.

### 15. takePhoto restituisce base64

- Richiesta/problema:
  serviva ricevere direttamente il contenuto immagine in base64 invece del path del file salvato.
- Modifica fatta:
  `takePhoto()` ora comprime il frame NV21 in JPEG e restituisce il base64 del JPEG come risultato della callback `success`.
- Motivo tecnico:
  semplificare l'integrazione lato app quando l'immagine deve essere mostrata subito o inoltrata senza ulteriore lettura dal filesystem.
- Stato:
  completato in codice, da validare a runtime.

### 16. Diagnostica capability UVC

- Richiesta/problema:
  serviva conoscere tutte le caratteristiche realmente esposte dalla webcam sul totem prima di implementare focus/exposure/zoom.
- Modifica fatta:
  e' stata aggiunta la funzione `getCameraCapabilities()`:
  - legge support flag UVC
  - legge i valori correnti
  - legge i range min/max/default principali via reflection su `UVCCamera`
- Motivo tecnico:
  implementare i controlli solo dopo aver verificato cosa la C920 espone davvero in questo ambiente specifico.
- Stato:
  completato in codice, da validare a runtime.

### 17. Esposizione setter UVC lato plugin JS

- Richiesta/problema:
  dopo la diagnostica delle capability serviva poter richiamare davvero dall'app i controlli UVC gia' presenti nel backend Java.
- Modifica fatta:
  nel bridge `www/usbUvcCamera.js` sono stati esposti:
  - `setAutoFocus`
  - `setFocus`
  - `setZoom`
  - `setBrightness`
  - `setContrast`
  - `setSharpness`
  - `setGain`
  - `setAutoWhiteBalance`
  - `setWhiteBalance`

  Inoltre e' stata aggiornata la documentazione in `README.md`.
- Motivo tecnico:
  il backend Android standalone aveva gia' i setter principali, ma senza wrapper JS non erano invocabili in modo pulito dall'app Cordova.
- Stato:
  completato in codice, da validare a runtime.

### 18. Setter exposure nel backend UVC

- Richiesta/problema:
  serviva poter cambiare esposizione e auto esposizione a runtime direttamente dall'app, come per focus e zoom.
- Modifica fatta:
  nel backend Android `UsbUvcCamera.java` sono stati aggiunti:
  - `setAutoExposure`
  - `setExposure`

  Inoltre `getCameraCapabilities()` ora restituisce anche:
  - `current.autoExposure`
  - `current.exposure`
  - `ranges.exposure`

  `applyStableCameraProfile()` e' stato esteso per gestire anche:
  - `autoExposure`
  - `exposure`

  Nel bridge `www/usbUvcCamera.js` sono stati aggiunti i wrapper JS corrispondenti.
- Motivo tecnico:
  la libreria UVC usata espone l'esposizione tramite metodi nativi interni, quindi il plugin la gestisce via reflection sul backend `UVCCamera` per restare coerente con il resto dei controlli runtime.
- Stato:
  completato in codice, da validare a runtime.

### 19. Preview nativa controllabile da app

- Richiesta/problema:
  serviva vedere in tempo reale cosa inquadra la webcam per regolare focus, zoom ed esposizione dalla UI applicativa.
- Modifica fatta:
  il plugin ora espone:
  - `showPreview(options)`
  - `hidePreview()`
  - `updatePreviewBounds(options)`

  La preview esistente, prima usata solo come `TextureView` quasi invisibile `1x1`, ora puo' essere resa visibile e posizionata con coordinate e dimensioni passate da JS.
- Motivo tecnico:
  la preview UVC e' nativa Android e viene sovrapposta alla WebView; questo approccio evita iframe o rendering base64 continuo e permette tuning live dei parametri camera.
- Stato:
  completato in codice, da validare a runtime.

### 20. Hardening setter focus con readback

- Richiesta/problema:
  durante i test `setFocus()` non mostrava variazioni leggendo subito dopo `getCameraCapabilities()`.
- Modifica fatta:
  `setFocus()` ora:
  - disattiva prima l'autofocus
  - applica il focus manuale
  - restituisce un oggetto con `requested`, `applied` e `autoFocus`

  Anche gli altri setter percentuali e `setAutoWhiteBalance()` ora restituiscono un readback del valore applicato quando disponibile.
- Motivo tecnico:
  molte webcam UVC ignorano il focus manuale se l'autofocus resta attivo; il readback aiuta a distinguere subito tra valore richiesto e valore realmente accettato dalla camera.
- Stato:
  completato in codice, da validare a runtime.

### 21. Setter UVC principali riallineati ai range reali del device

- Richiesta/problema:
  durante i test sembrava che `setFocus`, `setZoom` e altri setter non cambiassero davvero i valori letti poi da `getCameraCapabilities()`.
- Modifica fatta:
  i controlli principali ora non usano piu' i setter wrapper generici della libreria, ma:
  - aggiornano i limiti reali del controllo
  - convertono il valore percentuale `0-100` in valore assoluto UVC
  - applicano il valore via metodo nativo del backend `UVCCamera`
  - fanno readback nativo del valore risultante

  Questo e' stato applicato a:
  - focus
  - zoom
  - brightness
  - contrast
  - sharpness
  - gain
  - white balance
- Motivo tecnico:
  alcune webcam o alcune versioni della libreria non riflettono bene i setter ad alto livello; lavorare direttamente sui limiti e sui getter/setter nativi riduce il rischio di no-op silenziosi e rende coerente il modello con quello gia' usato per l'esposizione.
- Stato:
  completato in codice, da validare a runtime.

### 22. Hardening preview nativa visibile

- Richiesta/problema:
  nei primi test di `showPreview()` lato app non sembrava apparire nulla.
- Modifica fatta:
  la preview ora:
  - forza `View.VISIBLE` quando mostrata
  - forza `View.INVISIBLE` quando nascosta
  - porta esplicitamente la `TextureView` davanti agli altri child del container
  - imposta una `elevation` alta su Android 5+
  - logga i parametri applicati e ritorna al JS i bounds effettivamente usati
- Motivo tecnico:
  la `TextureView` nativa poteva essere aggiornata ma restare sotto la WebView o comunque non abbastanza visibile nei test; questi accorgimenti rendono il comportamento piu' diagnostico e piu' robusto.
- Stato:
  completato in codice, da validare a runtime.

### 23. Diagnostica setter runtime

- Richiesta/problema:
  durante i test i setter come `setFocus`, `setZoom` e `setExposure` sembravano restare a `0` anche quando dall'app venivano impostati valori diversi.
- Modifica fatta:
  il plugin ora:
  - logga esplicitamente il valore ricevuto da ogni setter principale
  - restituisce per `setExposure` e `setAutoExposure` un oggetto con readback, non solo `"ok"`
  - continua a restituire `requested/applied` per gli altri setter gia' strumentati
- Motivo tecnico:
  serve distinguere rapidamente tra:
  - valore errato/non passato dal bridge/app
  - valore accettato ma non applicato dal backend camera
  - build runtime non aggiornata
- Stato:
  completato in codice, da validare a runtime.

### 24. Hardening open quando la preview surface non e' ancora pronta

- Richiesta/problema:
  in alcuni run `openCamera()` restava appeso dopo `selected device for open`, con log:
  `maybeOpenPendingDevice skipped: previewReady=false`.
- Modifica fatta:
  `ensurePreviewView()` ora:
  - controlla subito `previewView.isAvailable()` se la view esiste gia'
  - se la surface e' disponibile, richiama immediatamente `maybeOpenPendingDevice()`
  - dopo l'attach della view, fa un controllo postato sul main thread per verificare se la surface e' diventata disponibile subito dopo l'aggiunta al layout
- Motivo tecnico:
  il flusso di open dipende dalla `TextureView` nativa; se la surface non viene intercettata nel timing giusto, l'apertura resta in sospeso pur avendo gia' selezionato il device USB corretto.
- Stato:
  completato in codice, da validare a runtime.

### 25. Correzione preview "hidden" ma ancora attiva

- Richiesta/problema:
  nei log `open()` restava fermo su `previewReady=false` anche se la `TextureView` era stata creata.
- Modifica fatta:
  la preview, quando nascosta, non viene piu' resa `INVISIBLE`; resta `VISIBLE`, con alpha minima e dimensione `1x1`.
- Motivo tecnico:
  una `TextureView` invisibile puo' non creare o non mantenere la `SurfaceTexture`, bloccando l'open della camera che dipende proprio da quella surface.
- Stato:
  completato in codice, da validare a runtime.

### 26. Retry automatico su open UVC con nativeConnect -99

- Richiesta/problema:
  in alcuni run `openCamera()` falliva con errore nativo del backend UVC:
  `nativeConnect returned -99`.
- Modifica fatta:
  il plugin ora intercetta gli errori di open contenenti:
  - `nativeConnect`
  - `result=-99`
  - `returned -99`

  In questi casi prova automaticamente un reopen pulito:
  - chiude l'istanza camera corrente
  - rinfresca il riferimento al device USB
  - richiede di nuovo il permesso
  - ritenta l'open fino a 2 volte
- Motivo tecnico:
  `-99` e' tipico di uno stato USB/UVC sporco o di un handle non piu' valido; prima il plugin falliva subito, ora prova a recuperare il device senza scaricare il problema sull'applicazione.
- Stato:
  completato in codice, da validare a runtime.

### 27. Diagnostica dedicata per il controllo zoom

- Richiesta/problema:
  dopo aver sistemato il passaggio dei valori dall'app, `focus` ed `exposure` risultano funzionanti ma `zoom` continua a non cambiare in modo visibile.
- Modifica fatta:
  `setZoom()` ora restituisce un payload piu' ricco con:
  - `requested`
  - `min`
  - `max`
  - `absoluteValue`
  - `appliedAbsolute`
  - `applied`

  Inoltre logga esplicitamente la conversione percentuale -> valore assoluto UVC.
- Motivo tecnico:
  serve capire se il backend:
  - riceve il valore giusto
  - lo converte correttamente nel range reale
  - lo applica davvero
  - oppure lo ignora nonostante il flag di supporto riportato dalla capability detection.
- Stato:
  completato in codice, da validare a runtime.

### 28. Diagnostica preview size reale per qualita' base64

- Richiesta/problema:
  il base64 della foto risultava di buona qualita' JPEG ma con risoluzione effettiva `640x480`, non `1280x720`.
- Modifica fatta:
  il plugin ora:
  - logga tutte le preview size disponibili quando la camera entra in `OPENED`
  - logga quale size viene effettivamente associata al frame preview usato per lo scatto
  - prova a privilegiare la size richiesta (`previewWidth` / `previewHeight`) quando coerente con il frame ricevuto
- Motivo tecnico:
  prima di tentare ulteriori ottimizzazioni serve capire se la webcam/backend stanno davvero offrendo `1280x720` come preview reale oppure se il frame usato per la foto continua a essere `640x480`.
- Stato:
  completato in codice, da validare a runtime.

### 29. Richiesta automatica della preview size piu' alta

- Richiesta/problema:
  la webcam espone preview size alte fino a `1920x1080`, ma il frame usato per `takePhoto()` risultava comunque `640x480`.
- Modifica fatta:
  durante `open()` il plugin ora:
  - legge subito le preview size disponibili dal backend camera
  - logga le size iniziali
  - seleziona automaticamente la size piu' alta disponibile quando `preferHighestResolution` e' attivo
  - usa quella size come `previewWidth` / `previewHeight` per la `CameraRequest`

  L'opzione `preferHighestResolution` e' abilitata di default.
- Motivo tecnico:
  prima il plugin chiedeva la size passata dall'app, ad esempio `1280x720`; ora prova attivamente a sfruttare la preview piu' alta che la libreria dichiara come disponibile, per capire se il callback preview puo' essere portato oltre `640x480`.
- Stato:
  completato in codice, da validare a runtime.

### 30. Tentativo di scatto high-res reale tramite file capture

- Richiesta/problema:
  il callback preview usato per `takePhoto()` resta a `640x480` anche quando la camera espone size molto piu' alte.
- Modifica fatta:
  `takePhoto()` ora prova prima un percorso high-res:
  - lancia `captureImage()` verso file JPEG
  - non si fida del callback finale della libreria
  - fa polling del file creato
  - se il file compare e ha dimensione valida, lo restituisce in base64

  Se il file non compare in tempo o il flusso high-res fallisce, mantiene il fallback precedente dal frame preview.
- Motivo tecnico:
  la libreria AUSBC sembrava bloccare i callback di `captureImage()` ma al tempo stesso mostrava segnali di compressione JPEG interna; il polling del file permette di verificare se il vero scatto high-res avviene comunque, senza dipendere dal callback di completamento.
- Stato:
  completato in codice, da validare a runtime.
  Nota di compatibilita':
  nella versione della libreria risolta dal build server la firma corretta di `captureImage` e' `captureImage(ICaptureCallBack, String)`, non `captureImage(String, ICaptureCallBack)`.

### 31. Scelta preview size resa piu' prudente

- Richiesta/problema:
  forzando sempre la size piu' alta disponibile, in alcuni run `openCamera()` falliva con `unsupported preview size`.
- Modifica fatta:
  la selezione della preview size ora segue questo ordine:
  1. size richiesta dall'app
  2. fallback noti e piu' realistici (`1280x720`, `960x720`, `1024x576`, `864x480`, `800x600`, `640x480`)
  3. solo in ultima istanza la size piu' alta dichiarata dal backend

  Inoltre il plugin conserva separatamente la size richiesta originaria (`requestedPreviewWidth` / `requestedPreviewHeight`).
- Motivo tecnico:
  alcune size compaiono nella lista del backend ma poi non risultano apribili davvero nel percorso preview usato; questa strategia riduce i falsi positivi senza rinunciare a provare risoluzioni piu' alte di `640x480`.
- Stato:
  completato in codice, da validare a runtime.

### 32. Log della risoluzione reale del JPEG high-res

- Richiesta/problema:
  dopo aver confermato la creazione del file high-res serviva sapere anche la risoluzione effettiva dell'immagine, non solo la dimensione in byte.
- Modifica fatta:
  il plugin ora decodifica i bounds del JPEG creato e logga:
  - size in byte
  - width
  - height
- Motivo tecnico:
  serve confermare in modo oggettivo se il percorso high-res sta producendo davvero un'immagine fotografica ad alta risoluzione o solo un file JPEG di dimensioni maggiori ma non necessariamente ad alta risoluzione.
- Stato:
  completato in codice, da validare a runtime.

### 33. Diagnostica e tolleranza maggiore per auto exposure

- Richiesta/problema:
  `setAutoExposure(true)` sembrava non cambiare mai lo stato, che restava a `false`, nonostante il controllo manuale dell'esposizione funzionasse.
- Modifica fatta:
  `setAutoExposure()` ora:
  - prova piu' candidati di mode UVC (`2`, `8`, `4` per auto; `1` per manuale)
  - ritorna nel risultato:
    - `requested`
    - `applied`
    - `mode`
    - `modeApplied`

  Anche `setExposure()` ora ritorna il `mode` raw letto dal backend.
- Motivo tecnico:
  diversi device/UVC stack usano codifiche leggermente diverse per `exposure mode`; provare piu' candidati e leggere il mode raw aiuta a capire se il problema e' nel valore del mode o nel getter che non riflette bene lo stato.
- Stato:
  completato in codice, da validare a runtime.

### 34. Impostazione architetturale per backend photo high-res separato

- Richiesta/problema:
  il requisito di qualita' fotografica reale non e' soddisfatto dallo stack AUSBC attuale, che produce ancora JPEG `640x480` anche nel percorso still capture.
- Modifica fatta:
  sono stati aggiunti i contratti Java per separare:
  - preview/control backend
  - photo capture backend high-res

  File aggiunti:
  - `HighResPhotoRequest.java`
  - `HighResPhotoResult.java`
  - `HighResPhotoCaptureBackend.java`
  - `PreviewControlBackend.java`
  - `HIGH_RES_BACKEND_PLAN.md`

  Inoltre `plugin.xml` e `README.md` sono stati aggiornati per riflettere questa nuova fase.
- Motivo tecnico:
  serve evitare di continuare ad accumulare logica sperimentale high-res dentro `UsbUvcCamera.java`; il prossimo backend di scatto deve poter essere sviluppato e sostituito in modo isolato rispetto al backend stabile di preview e controlli.
- Stato:
  completato come scaffolding architetturale.

### 35. Primo backend high-res concreto collegato a takePhoto

- Richiesta/problema:
  dopo lo scaffolding serviva un primo backend concreto, non solo interfacce, per spostare davvero il percorso high-res fuori da `UsbUvcCamera.java`.
- Modifica fatta:
  sono stati aggiunti:
  - `AusbcCameraHandleProvider.java`
  - `AusbcHighResPhotoCaptureBackend.java`

  Inoltre:
  - `HighResPhotoRequest` e' stato esteso con `outputPath` e `timeoutMs`
  - `UsbUvcCamera.takePhoto()` ora delega il percorso high-res al backend `AusbcHighResPhotoCaptureBackend`
  - il fallback preview resta invariato se il backend fallisce
- Motivo tecnico:
  in questo modo il backend high-res puo' essere sostituito in seguito senza riscrivere di nuovo tutta la logica di `takePhoto()`, mentre il plugin continua a mantenere lo stesso contratto verso l'app.
- Stato:
  completato in codice, da validare a runtime/build.

### 36. Placeholder del backend alternativo allo still capture AUSBC

- Richiesta/problema:
  i test runtime hanno confermato che `AusbcHighResPhotoCaptureBackend` resta fermo a `640x480`, quindi serve un backend alternativo per la vera qualita' fotografica.
- Modifica fatta:
  e' stato aggiunto:
  - `NativeStillCaptureBackend.java`

  e il progetto/plugin e' stato aggiornato per includerlo nello scaffolding della prossima fase.
- Motivo tecnico:
  il backend alternativo va trattato come sostituto pulito di `AusbcHighResPhotoCaptureBackend`, non come altra logica sparsa nel plugin principale.
- Stato:
  completato come placeholder architetturale.

### 37. Chain di backend high-res con priorita' al backend alternativo

- Richiesta/problema:
  il backend alternativo non doveva restare un semplice file placeholder scollegato dal plugin.
- Modifica fatta:
  e' stato aggiunto:
  - `CompositeHighResPhotoCaptureBackend.java`

  e `UsbUvcCamera` ora inizializza la chain high-res in questo ordine:
  1. `NativeStillCaptureBackend`
  2. `AusbcHighResPhotoCaptureBackend`

  Quindi il plugin e' gia' predisposto a dare priorita' al backend alternativo reale non appena verra' implementato, mantenendo AUSBC come fallback.
- Motivo tecnico:
  questo evita di dover riscrivere ancora `takePhoto()` quando il backend alternativo sara' pronto; bastera' implementare davvero `NativeStillCaptureBackend`.
- Stato:
  completato in codice come architettura di selezione backend.

### 38. Analisi del vendor upstream UVCCamera

- Richiesta/problema:
  serviva capire se l'integrazione di `UVCCamera` upstream avrebbe risolto automaticamente il limite `640x480`.
- Modifica fatta:
  e' stata aggiunta l'analisi documentata in:
  - `VENDOR_FINDINGS.md`

  La verifica del codice upstream ha mostrato che il loro still capture standard passa da:
  - `AbstractUVCCameraHandler.handleCaptureStill(...)`
  - `UVCCameraTextureView.captureStillImage()`

  cioe' da una cattura del contenuto della preview view.
- Motivo tecnico:
  questo significa che una semplice integrazione dei sample upstream "as is" non garantirebbe di superare il limite di risoluzione gia' osservato; la prossima implementazione di `NativeStillCaptureBackend` dovra' cercare un percorso non limitato alla preview view.
- Stato:
  completato come analisi tecnica documentata.

### 39. Diagnostica raw USB/UVC descriptors dal device reale

- Richiesta/problema:
  serviva continuare in modo concreto verso il backend high-res reale, senza andare a tentativi ciechi sul codice.
- Modifica fatta:
  e' stata aggiunta l'azione nativa:
  - `inspectUvcDescriptors()`

  con implementazione in:
  - `src/android/UsbUvcCamera.java`
  - `www/usbUvcCamera.js`

  e documentazione aggiornata in:
  - `README.md`
  - `HIGH_RES_BACKEND_PLAN.md`

  L'azione apre il device USB reale, legge i raw descriptors e restituisce:
  - interfacce video control / video streaming
  - presenza di `VS_STILL_IMAGE_FRAME`
  - formati e frame descriptors UVC
  - frame sizes dichiarate nei descriptor
- Motivo tecnico:
  questo ci permette di capire se la Logitech C920 sul totem espone davvero uno still-image path UVC separato oppure solo descriptor di streaming/preview, e quindi se un backend nativo high-res ha basi reali su cui lavorare.
- Stato:
  completato in codice, da testare a runtime sul totem.

### 40. Tentativo esplicito di stream MJPEG high-res nello stack AUSBC

- Richiesta/problema:
  dai raw UVC descriptors e' emerso che la C920 non espone still-image descriptors, ma espone stream `MJPEG` ad alta risoluzione fino a `1920x1080`.
- Modifica fatta:
  in `src/android/UsbUvcCamera.java` il plugin ora prova a costruire la `CameraRequest` con preferenza MJPEG via reflection:
  - `preferMjpeg` su `open(options)` default `true`
  - tentativo di `CameraRequest.Builder.setPreviewFormat(...)`
  - attivazione best-effort di `setRawPreviewData(true)`
  - attivazione best-effort di `setCaptureRawImage(true)`

  E' stata aggiornata anche la documentazione in `README.md`.
- Motivo tecnico:
  non avendo uno still endpoint UVC vero, l'unica strada realistica rimasta e' negoziare davvero uno stream MJPEG/high-res nello stack attuale invece di accettare il fallback `640x480`.
- Stato:
  completato in codice, da validare a runtime leggendo i log di open e la risoluzione reale di `takePhoto()`.

### 41. Introspezione runtime delle API reali della libreria AUSBC 3.2.7

- Richiesta/problema:
  i log runtime hanno mostrato che nella `3.2.7` realmente caricata non esistono:
  - `CameraRequest.PreviewFormat`
  - `setRawPreviewData`
  - `setCaptureRawImage`

  quindi non era piu' utile provare nomi di metodi "alla cieca".
- Modifica fatta:
  e' stata aggiunta l'azione:
  - `inspectBackendApi()`

  in:
  - `src/android/UsbUvcCamera.java`
  - `www/usbUvcCamera.js`

  L'azione restituisce i metodi e i field piu' rilevanti esposti a runtime da:
  - `CameraRequest.Builder`
  - `MultiCameraClient.Camera`
  - `UVCCamera`

  filtrati per keyword come `preview`, `format`, `raw`, `capture`, `frame`, `mjpeg`, `yuv`, `size`, `mode`.
- Motivo tecnico:
  questo ci permette di trovare eventuali leve reali ancora disponibili nella libreria integrata sul totem, prima di decidere se il ramo AUSBC e' definitivamente un vicolo cieco per l'high-res.
- Stato:
  completato in codice, da eseguire a runtime sul totem.

### 42. Tentativo di forzare MJPEG/high-res dal livello UVCCamera basso

- Richiesta/problema:
  l'introspezione runtime ha mostrato che `CameraRequest.Builder` e' troppo povero nella `3.2.7` realmente caricata, ma `UVCCamera` espone ancora:
  - `FRAME_FORMAT_MJPEG`
  - `FRAME_FORMAT_YUYV`
  - vari overload di `setPreviewSize(...)`
  - `startPreview()` / `stopPreview()`
- Modifica fatta:
  in `src/android/UsbUvcCamera.java`, quando la camera entra in `OPENED`, il plugin ora prova a:
  - recuperare `UVCCamera` sottostante
  - fermare la preview
  - chiamare `setPreviewSize(...)` con `FRAME_FORMAT_MJPEG` se `preferMjpeg=true`
  - riassociare la `TextureView`
  - riavviare la preview

  Il tutto con reflection e logging esplicito dell'overload scelto.
- Motivo tecnico:
  dato che la C920 non espone still descriptors ma dichiara stream MJPEG fino a `1920x1080`, questa e' la prima leva concreta rimasta per provare a negoziare davvero uno stream high-res nello stack esistente.
- Stato:
  completato in codice, da validare a runtime guardando:
  - log di `Configuring underlying UVCCamera preview stream ...`
  - overload `setPreviewSize(...)` usato
  - dimensione finale del JPEG di `takePhoto()`

### 43. Priorita' al preview frame quando la preview negoziata supera 640x480

- Richiesta/problema:
  dopo la riconfigurazione bassa di `UVCCamera`, i log hanno mostrato una preview negoziata a `960x720`, ma il percorso `captureImage()` andava in timeout e peggiorava il flusso.
- Modifica fatta:
  in `src/android/UsbUvcCamera.java`, `attemptHighResTakePhoto()` ora controlla prima la preview realmente negoziata da `UVCCamera`.

  Se la preview attiva e' gia' superiore a `640x480`, il plugin:
  - salta il backend `captureImage()`
  - usa direttamente `attemptTakePhoto()` dal frame preview

  La size corrente viene letta da `UVCCamera.getPreviewSize()` con reflection/fallback parsing.
- Motivo tecnico:
  una preview realmente negoziata a `960x720` e' gia' migliore del vecchio limite `640x480`; se `captureImage()` va in timeout, e' piu' sensato usare subito quel frame preview piuttosto che aspettare un backend che non chiude.
- Stato:
  completato in codice, da validare a runtime verificando che `takePhoto()` usi direttamente il frame `960x720` senza timeout.

### 44. Ladder di negoziazione preview high-res dal livello UVCCamera

- Richiesta/problema:
  serviva capire se fosse ancora possibile salire oltre `960x720`, invece di fermarsi alla prima size negoziata con successo.
- Modifica fatta:
  la riconfigurazione bassa di `UVCCamera` ora non prova una sola size, ma una ladder ordinata di candidate:
  - requested size
  - `1920x1080`
  - `1600x896`
  - `1280x720`
  - `1024x576`
  - `960x720`
  - `864x480`
  - `800x600`
  - `640x480`

  Per ogni tentativo il plugin:
  - ferma la preview
  - invoca `setPreviewSize(...)`
  - riaggancia la texture
  - riavvia la preview
  - legge la size realmente negoziata
- Motivo tecnico:
  la webcam dichiara size alte fino a `1920x1080`, ma il backend puo' degradare o rifiutare alcune risoluzioni. La ladder permette di trovare automaticamente la migliore size realmente accettata dal totem.
- Stato:
  completato in codice, da validare a runtime leggendo i log `Underlying preview negotiation attempt requested=... negotiated=...`.

### 45. Frame callback basso di UVCCamera per recuperare i frame high-res negoziati

- Richiesta/problema:
  dopo la negoziazione a `960x720`, `captureImage()` andava in timeout e il vecchio `addPreviewDataCallBack(...)` non consegnava piu' frame utili per `takePhoto()`.
- Modifica fatta:
  in `src/android/UsbUvcCamera.java` il plugin ora installa un frame callback basso direttamente su `UVCCamera` tramite reflection:
  - uso di `UVCCamera.setFrameCallback(...)`
  - preferenza per `PIXEL_FORMAT_NV21`
  - fallback a `PIXEL_FORMAT_YUV420SP`

  I frame ricevuti vengono copiati in `latestPreviewFrame`, cosi' `attemptTakePhoto()` puo' usare il frame realmente negoziato dalla preview bassa.
- Motivo tecnico:
  il forcing a `960x720` avviene sotto il livello AUSBC; per recuperare davvero quei frame serviva agganciarsi al callback nativo della `UVCCamera` sottostante, non piu' al callback legacy del wrapper superiore.
- Stato:
  completato in codice, da validare a runtime verificando che dopo la negoziazione high-res arrivino frame e che `takePhoto()` non fallisca piu' con `No preview frame available`.

### 46. Sostituzione del Proxy dinamico con IFrameCallback tipizzato per evitare crash JNI

- Richiesta/problema:
  il totem andava in crash subito dopo l'installazione del frame callback basso, con abort ART:
  `JNI DETECTED ERROR IN APPLICATION: the return type of CallVoidMethodV does not match void com.serenegiant.usb.IFrameCallback.onFrame(java.nio.ByteBuffer)`.
- Modifica fatta:
  in `src/android/UsbUvcCamera.java` il callback basso non viene piu' creato via `java.lang.reflect.Proxy`, ma come implementazione Java tipizzata di `com.serenegiant.usb.IFrameCallback`.

  In piu':
  - il campo `underlyingFrameCallback` e' stato tipizzato come `IFrameCallback`
  - `UVCCamera.setFrameCallback(...)` viene chiamato direttamente, senza reflection sul metodo
  - la rimozione del callback usa `uvcCamera.setFrameCallback(null, 0)`
- Motivo tecnico:
  il bridge JNI di `libUVCCamera.so` si aspetta un oggetto Java che implementi realmente `IFrameCallback`; il proxy dinamico risultava incompatibile con il controllo firma/return type eseguito da ART e causava il crash nativo.
- Stato:
  fix applicato in codice; da validare a runtime verificando che:
  - la preview continui a negoziare size alte come `960x720`
  - non compaia piu' l'errore JNI su `CallVoidMethodV`
  - `takePhoto()` riesca a usare i frame ricevuti dal callback basso.

### 47. Priorita' del frame callback basso e salvataggio esplicito della size reale del frame

- Richiesta/problema:
  dopo il fix JNI, i log mostravano ancora una discrepanza:
  - preview negoziata a `960x720`
  - encoding JPEG eseguito come `1280x720`

  Questo indicava che `takePhoto()` poteva ancora leggere un frame proveniente dal callback legacy AUSBC invece del frame basso realmente negoziato da `UVCCamera`.
- Modifica fatta:
  in `src/android/UsbUvcCamera.java` sono stati aggiunti metadati espliciti del frame:
  - `latestPreviewFrameWidth`
  - `latestPreviewFrameHeight`

  Inoltre:
  - il callback legacy `addPreviewDataCallBack(...)` non sovrascrive piu' il frame se il callback basso `IFrameCallback` e' attivo
  - il callback basso salva insieme ai byte anche width/height reali, presi dalla preview negoziata
  - `attemptTakePhoto()` usa prima i metadati del frame salvati, invece di dedurre la size solo da `frameLength`
  - la chiusura camera resetta anche width/height del frame memorizzato
- Motivo tecnico:
  un solo buffer condiviso tra due sorgenti diverse creava una race: il plugin poteva codificare come `1280x720` un frame che apparteneva alla pipeline legacy, mentre la preview bassa stava lavorando a un'altra size. Salvare sorgente implicita e size reale rende coerente il passaggio buffer -> JPEG.
- Stato:
  validato a runtime sul totem.

  Log confermati:
  - `Using stored preview frame size 960x720`
  - `Encoding preview frame as base64 JPEG using size 960x720`
  - `Preview frame base64 encoding complete`

  Quindi il flusso finale ora e':
  - preview bassa negoziata a `960x720`
  - frame ricevuto dal callback basso `UVCCamera`
  - codifica JPEG coerente a `960x720`
  - nessun crash JNI

  Residuo aperto:
  - resta il warning Cordova `THREAD WARNING: exec() call to UsbUvcCamera.takePhoto blocked the main thread`, da ottimizzare spostando l'intero avvio del flusso foto fuori dal main thread/plugin exec path.

### 48. Avvio async di takePhoto e normalizzazione chroma del frame basso prima della JPEG

- Richiesta/problema:
  dopo la validazione a `960x720`, restavano due problemi:
  - warning Cordova: `exec() call to UsbUvcCamera.takePhoto blocked the main thread`
  - foto visivamente "corrotte" o disturbate, pur con size corretta
- Modifica fatta:
  in `src/android/UsbUvcCamera.java`:
  - `takePhoto()` ora avvia `attemptHighResTakePhoto(...)` tramite `cordova.getThreadPool()`, invece di eseguire subito il flusso nel percorso `execute()`
  - il frame memorizzato salva anche la provenienza (`legacy` AUSBC vs callback basso `UVCCamera`)
  - se il frame arriva dal callback basso, prima della JPEG viene convertito da `NV12/YUV420SP` a `NV21` con swap dei byte UV a coppie
- Motivo tecnico:
  il warning Cordova nasceva dal fatto che il flusso foto partiva direttamente nel thread del plugin. Il disturbo visivo, invece, e' compatibile con un mismatch chroma: il buffer YUV semiplanare del callback basso probabilmente arriva in ordine `UV` (NV12/YUV420SP), mentre `YuvImage` si aspetta `VU` (`NV21`).
- Stato:
  fix applicato in codice; da validare a runtime verificando:
  - assenza del warning `THREAD WARNING: exec() call to UsbUvcCamera.takePhoto blocked the main thread`
  - presenza del log `Converting underlying preview frame from NV12/YUV420SP to NV21 before JPEG encoding`
  - resa visiva corretta della foto salvata.

### 49. Rilevamento formato reale del frame basso e conversione YUYV -> NV21

- Richiesta/problema:
  anche dopo la conversione `NV12 -> NV21`, la foto risultava ancora molto corrotta, con pattern compatibile con un frame packed interpretato come semiplanare.
- Modifica fatta:
  in `src/android/UsbUvcCamera.java` il plugin ora:
  - salva anche `latestPreviewFrameFormat`
  - rileva il formato del frame basso dalla lunghezza reale del buffer:
    - `width * height * 2` -> `yuyv`
    - `width * height * 3 / 2` -> `yuv420sp`
  - scrive nei log la size, la lunghezza del frame e il formato rilevato
  - se il formato e' `yuyv`, converte il buffer in `NV21` prima della JPEG
- Motivo tecnico:
  l'immagine corrotta osservata sul totem e' molto piu' compatibile con un frame `YUYV` interpretato come `NV21` che con un semplice swap `NV12/NV21`. La conversione packed -> semiplanar e' quindi il tentativo piu' plausibile e piu' strutturato.
- Stato:
  fix applicato in codice; da validare a runtime verificando se il log mostra:
  - `frameFormat=yuyv`
  - `Converting underlying preview frame from YUYV to NV21 before JPEG encoding`
  e se la foto diventa visivamente corretta.

### 50. Snapshot della TextureView come percorso foto primario per bypassare i raw frame corrotti

- Richiesta/problema:
  anche dopo i tentativi di conversione raw (`NV12/NV21`, `YUYV -> NV21`), la foto risultava ancora corrotta in modo evidente.
- Modifica fatta:
  in `src/android/UsbUvcCamera.java` `attemptTakePhoto()` ora prova prima a catturare un bitmap direttamente dalla `TextureView` della preview usando `previewView.getBitmap(width, height)` alla size negoziata, comprime quel bitmap in JPEG e restituisce il base64 risultante.

  Il vecchio percorso raw resta come fallback se lo snapshot della `TextureView` fallisce o va in timeout.
- Motivo tecnico:
  la `TextureView` contiene il frame gia' passato attraverso la pipeline grafica Android, quindi evita completamente i problemi di parsing manuale del `ByteBuffer` raw: pixel format, semiplanar/packed, stride e padding. Se la preview si vede correttamente, questo e' il modo piu' robusto per ottenere una foto coerente.
- Stato:
  fix applicato in codice; da validare a runtime verificando:
  - presenza del log `Preview TextureView bitmap encoding complete`
  - assenza di fallback al path raw
  - resa visiva corretta della foto finale.

### 51. Surface di cattura stabile a 1280x720 e defaultBufferSize esplicito della TextureView

- Richiesta/problema:
  l'obiettivo successivo era puntare in modo stabile ad almeno `1280x720`, invece di fermarsi sistematicamente a `960x720`.
- Modifica fatta:
  in `src/android/UsbUvcCamera.java`:
  - la `TextureView` usata come preview/capture surface, quando la preview utente e' nascosta, non resta piu' a `1x1`, ma mantiene almeno una dimensione stabile `1280x720`
  - viene impostato esplicitamente `SurfaceTexture.setDefaultBufferSize(...)`
  - durante ogni tentativo di negoziazione bassa, prima di riagganciare la texture alla `UVCCamera`, il `defaultBufferSize` viene riallineato alla candidate size (`1280x720`, `1920x1080`, ecc.)
- Motivo tecnico:
  una surface quasi nulla (`1x1`) puo' spingere la pipeline grafica o la libreria sottostante a degradare il buffer reale. Mantenere una capture surface coerente con il target e' il passo piu' sensato per tentare un `1280x720` stabile senza cambiare ancora backend.
- Stato:
  fix applicato in codice; da validare a runtime verificando:
  - `Updated preview SurfaceTexture default buffer size to 1280x720` o superiore
  - `Set preview SurfaceTexture default buffer size for negotiation to 1280x720`
  - preview finale negoziata a `1280x720` invece di `960x720`.

### 52. Negoziazione multi-formato MJPEG/YUYV e scelta della miglior size invece del primo successo

- Richiesta/problema:
  dai log successivi risultava che il plugin entrava correttamente nel path `TextureView`, ma la sorgente reale restava comunque a `960x720`.
- Modifica fatta:
  in `src/android/UsbUvcCamera.java` la riconfigurazione bassa ora:
  - non prova piu' un solo `frameFormat`
  - tenta sia `MJPEG` sia `YUYV` (in ordine coerente con `preferMjpeg`)
  - per ogni combinazione size/formato registra il risultato negoziato
  - non si ferma al primo successo sotto-target, ma conserva la miglior size ottenuta
  - esce subito solo se raggiunge almeno la size richiesta (`1280x720` o superiore)
- Motivo tecnico:
  una webcam puo' accettare una stessa risoluzione in un formato e rifiutarla o degradarla in un altro. Fermarsi al primo `960x720` impediva di scoprire se `1280x720` fosse disponibile su un secondo tentativo con formato diverso.
- Stato:
  validato a runtime sul totem.

  Log confermati:
  - `Underlying preview negotiation attempt requested=1280x720, frameFormat=1, negotiated=960x720`
  - `Underlying preview negotiation attempt requested=1920x1080, frameFormat=1, negotiated=1920x1080`
  - `Underlying UVCCamera preview stream configured at target or higher, previewSize=1920x1080, frameFormat=1`
  - `Using stored preview frame size 1920x1080`
  - `Preview TextureView bitmap encoding complete`

  Quindi l'obiettivo minimo `1280x720` e' stato superato: la sorgente reale ora arriva stabilmente a `1920x1080` sul path MJPEG basso.

  Residui ancora aperti:
  - warning `THREAD WARNING` ancora presenti su alcune exec Cordova
  - errori `BufferQueueProducer ... connect: already connected`
  - warning `A resource failed to call Surface.release`

### 53. Riduzione dei warning Cordova e pulizia del lifecycle preview/surface

- Richiesta/problema:
  dopo la validazione a `1920x1080`, nei log restavano ancora warning di pulizia:
  - `THREAD WARNING` su alcune exec Cordova (`takePhoto`, `listUsbDevices`, autofocus/autowhitebalance)
  - `BufferQueueProducer ... connect: already connected`
  - `A resource failed to call Surface.release`
- Modifica fatta:
  in `src/android/UsbUvcCamera.java`:
  - `takePhoto()` prepara file e timeout dal `threadPool` invece di farlo nel path `exec()`
  - `listUsbDevices()` viene eseguito nel `threadPool`
  - `setAutoFocus()` e `setAutoWhiteBalance()` vengono eseguiti nel `threadPool`
  - `closeCurrentCamera()` prova a fermare esplicitamente la preview bassa prima della chiusura
  - durante `configureUnderlyingPreviewStream(...)` non viene piu' riattaccata ogni volta la stessa `SurfaceTexture` con `setPreviewTexture(...)`; resta solo l'aggiornamento del `defaultBufferSize`
- Motivo tecnico:
  parte dei warning dipendeva da operazioni JNI/file/USB lanciate direttamente nel thread del plugin Cordova, mentre i messaggi `already connected` erano compatibili con ri-attach ripetuti della stessa `SurfaceTexture` durante i tentativi di negoziazione preview.
- Stato:
  fix applicato in codice; da validare a runtime verificando:
  - riduzione o scomparsa dei `THREAD WARNING` per `takePhoto`, `listUsbDevices`, autofocus e autowhitebalance
  - riduzione degli errori `BufferQueueProducer ... connect: already connected`
  - riduzione dei warning `Surface.release`.

### 54. Strategia focus da totem: autofocus iniziale, lock automatico e focus persistito

- Richiesta/problema:
  sul totem l'autofocus continuo portava a un comportamento instabile: a volte il volto era a fuoco, a volte no, per effetto di hunting della webcam.
- Modifica fatta:
  in `src/android/UsbUvcCamera.java` il plugin ora implementa una strategia focus piu' stabile:
  - all'apertura camera prova ad applicare subito l'ultimo focus manuale salvato
  - avvia un breve autofocus iniziale
  - dopo un delay configurabile blocca il focus in manuale
  - salva il focus bloccato in `SharedPreferences`
  - espone anche l'azione `refocus()` per rilanciare manualmente il ciclo autofocus+lock

  Sono stati aggiornati anche:
  - `www/usbUvcCamera.js`
  - `README.md`
- Motivo tecnico:
  in uno scenario kiosk la distanza soggetto-camera tende a essere abbastanza stabile. Lasciare autofocus sempre attivo e' spesso peggio che usarlo solo come fase di aggancio iniziale e poi bloccare il focus trovato.
- Stato:
  fix applicato in codice; da validare a runtime cercando log del tipo:
  - `Applied stored locked focus on open, focus=...`
  - `Smart focus autofocus pulse started, reason=camera-opened, lockDelayMs=...`
  - `Smart focus lock applied, reason=camera-opened, focus=...`

  API nuova disponibile:
  - `navigator.usbUvcCamera.refocus({ focusLockDelayMs: 1800 }, success, error)`

## Nota operativa

Da ora in poi, a ogni modifica importante, questo file va aggiornato con:

1. richiesta o problema
2. file toccati
3. spiegazione tecnica breve
4. stato finale

## Open Items

### Runtime da validare

- verificare che `listUsbDevices()` ritorni correttamente sul totem dopo il workaround Android 13+
- verificare che `open()` completi davvero l'apertura della Logitech
- verificare che `takePhoto()` produca un file leggibile e stabile
- verificare che `recoverCamera()` funzioni senza reboot del dispositivo

### Funzioni ancora incomplete

- `applyStableCameraProfile()` e' ancora un `noop`
- non e' ancora stata implementata una strategia finale per focus fisso / esposizione fissa / brightness

### Rischi tecnici aperti

- il workaround via reflection su `USBMonitor` dipende dalla struttura interna della libreria `AndroidUSBCamera 3.2.7`
- il lifecycle UVC e' compilante, ma non ancora validato su sessioni lunghe 24/7
- il wrapper applicativo sopra `navigator.usbUvcCamera` potrebbe ancora introdurre blocchi lato `yield`

### Failure Cases da testare

- avvio app senza webcam collegata
- webcam scollegata dopo `open()`
- webcam ricollegata senza riavvio app
- permesso USB non concesso o flusso permission interrotto
- `open()` chiamato piu' volte consecutive
- `takePhoto()` chiamato senza camera pronta
- `recoverCamera()` chiamato senza camera disponibile
- app lasciata aperta per molte ore con webcam collegata

## 2026-04-01 - Riallineamento takePhoto high-res

### Richiesta o problema

- i log runtime del totem mostravano ancora `Skipping captureImage backend because negotiated preview stream is already high-res: 1920x1080`
- questo faceva deviare `takePhoto()` direttamente sul fallback preview, che nel caso osservato non aveva frame pronti e finiva con `No preview frame available after retries`

### File toccati

- `src/android/UsbUvcCamera.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- in `attemptHighResTakePhoto()` e' stato rimosso il bypass che saltava il backend `captureImage` quando la preview negoziata superava `640x480`
- il plugin ora mantiene attivo il backend high-res anche con preview gia' a `1920x1080` e scrive un log esplicito di conferma invece di forzare il fallback preview

### Stato finale

- il prossimo build del plugin provera' davvero il backend high-res nel flusso `takePhoto()`
- resta da validare su device se il backend high-res completa lo scatto oppure se emerge il crash nativo separato dentro `updateCameraParams()`

## 2026-04-01 - Timeout e fallback foto riallineati ai log runtime

### Richiesta o problema

- i nuovi log runtime mostravano finalmente l'avvio del backend high-res con `Keeping captureImage backend enabled with negotiated preview stream 1920x1080`
- subito dopo pero' il backend `captureImage` andava in `Times out`, il timeout totale del plugin scadeva ancora dopo `6000 ms`, e il fallback preview non trovava alcun frame raw disponibile

### File toccati

- `src/android/UsbUvcCamera.java`
- `src/android/AusbcHighResPhotoCaptureBackend.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- il timeout totale di `takePhoto()` e' stato portato a `12000 ms`, mentre il backend high-res usa ora un timeout dedicato piu' corto (`3500 ms`) per lasciare tempo reale al fallback
- quando il backend high-res fallisce, il plugin rischedula il timeout generale prima di avviare `attemptTakePhoto()` e non lancia il fallback se il callback foto e' gia' stato chiuso
- `AusbcHighResPhotoCaptureBackend` ora interrompe subito il polling se `captureImage` richiama `onError`, invece di aspettare l'intero timeout file
- `attemptTakePhoto()` prova prima a catturare un JPEG dalla `TextureView` della preview e solo dopo, se fallisce, ricade sul frame raw memorizzato
- `capturePreviewTextureAsBase64()` aggiunge log piu' espliciti e tenta anche `previewView.getBitmap()` senza size fissa se la richiesta dimensionata restituisce `null`

### Stato finale

- il prossimo build dovrebbe evitare il race in cui il timeout totale scade prima del fallback preview
- se la `TextureView` e' davvero disponibile sul totem, il fallback dovrebbe riuscire anche senza `latestPreviewFrame`
- se anche cosi' resta il `Times out` del backend high-res, il problema residuo sara' isolato soprattutto dentro `captureImageInternal` della libreria AUSBC

## 2026-04-01 - Fix deadlock leggero nel fallback TextureView

### Richiesta o problema

- i log runtime mostravano che ogni fallback preview impiegava sempre circa `1500 ms`, con `Timed out while capturing preview TextureView bitmap`, `Skipped 90+ frames` e forte jank del main thread

### File toccati

- `src/android/UsbUvcCamera.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- `attemptTakePhoto()` viene richiamato sul main thread dopo il fallimento del backend high-res
- `capturePreviewTextureAsBase64()` pero' postava un task sul main thread e poi faceva `await()` sullo stesso thread: non era un deadlock totale, ma di fatto bloccava il main thread fino al timeout del latch
- il metodo ora rileva quando e' gia' sul main thread ed esegue la cattura `TextureView` in modo diretto e sincrono, senza `post + await`
- la logica comune di lettura bitmap/compressione JPEG e' stata spostata in un helper dedicato per mantenere identico il comportamento tra chiamata diretta e chiamata asincrona

### Stato finale

- il prossimo build non dovrebbe piu' mostrare timeout `1500 ms` sistematici del fallback `TextureView`
- se la preview esiste davvero, ora il fallback puo' completare subito invece di autosabotarsi bloccando il thread UI

## 2026-04-01 - Fix bitmap nero dal fallback TextureView

### Richiesta o problema

- dopo il fix del deadlock il fallback `TextureView` completava correttamente, ma la foto risultante era completamente nera

### File toccati

- `src/android/UsbUvcCamera.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- la preview "nascosta" veniva mantenuta con `alpha = 0.01f` a schermo; questo preservava il `SurfaceTexture`, ma rischiava di produrre bitmap quasi neri quando il fallback leggeva la `TextureView`
- il layout hidden ora tiene la preview opaca (`alpha = 1.0f`) e la sposta offscreen a sinistra, invece di attenuarla quasi a zero dentro il viewport
- la size hidden usa anche la preview realmente negoziata (`previewWidth` / `previewHeight`) oltre alle dimensioni richieste e alla size stabile di capture

### Stato finale

- il prossimo build dovrebbe continuare a permettere il fallback `TextureView`, ma senza generare un JPEG nero a causa dell'alpha quasi nullo della view

## 2026-04-01 - Preferenza al frame raw preview rispetto al bitmap TextureView

### Richiesta o problema

- anche dopo il fix della preview offscreen, il fallback `TextureView` completava ma la foto risultava ancora nera sul totem

### File toccati

- `src/android/UsbUvcCamera.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- `attemptTakePhoto()` provava sempre prima il bitmap della `TextureView`, anche quando il plugin aveva gia' un frame preview raw disponibile
- inoltre il callback `addPreviewDataCallBack()` smetteva di aggiornare `latestPreviewFrame` se era presente `underlyingFrameCallback`
- ora il plugin continua sempre a salvare l'ultimo frame `NV21` della preview e usa quel frame come sorgente primaria dello scatto fallback
- il bitmap `TextureView` resta disponibile solo come seconda scelta, quando non c'e' ancora nessun frame raw memorizzato

### Stato finale

- il prossimo build dovrebbe evitare le foto nere causate da un bitmap `TextureView` non affidabile, privilegiando il frame preview raw quando disponibile

## 2026-04-01 - Il bitmap TextureView diventa solo fallback finale

### Richiesta o problema

- i log mostravano ancora `Preview TextureView bitmap encoding complete`, quindi il plugin non stava usando nessun frame raw preview nonostante i cambi precedenti

### File toccati

- `src/android/UsbUvcCamera.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- se `latestPreviewFrame` e' assente, il plugin ora aspetta i retry previsti per dare al canale raw una possibilita' reale di popolarsi
- il fallback `TextureView` viene usato solo all'ultimo tentativo, come ultima spiaggia
- sono stati aggiunti log one-shot quando arriva il primo frame dal callback preview normale o dal callback basso `UVCCamera`, cosi' il prossimo test dira' subito quale canale produce davvero dati
- nella pulizia del callback basso viene azzerato anche `latestPreviewFrame`, per evitare di riusare frame obsoleti dopo reset/riaperture

### Stato finale

- il prossimo build dira' chiaramente se il totem sta consegnando frame raw preview
- se compare `Received first preview frame ...` e poi `Using stored preview frame size ...`, avremo finalmente uno scatto fallback indipendente dalla `TextureView`

## 2026-04-01 - Fallback TextureView con frame di render reale

### Richiesta o problema

- anche come ultima spiaggia, il fallback `TextureView` produceva ancora un JPEG nero, segno che `getBitmap()` stava leggendo una view nascosta/offscreen senza un frame utile renderizzato

### File toccati

- `src/android/UsbUvcCamera.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- l'ultimo fallback non usa piu' `getBitmap()` immediato nello stesso punto del flusso
- ora il plugin porta temporaneamente la preview in uno stato visibile con bounds validi, aspetta due frame di animazione via `postOnAnimation`, cattura il bitmap e poi ripristina subito il layout precedente
- questo serve a far leggere al `TextureView` un contenuto davvero renderizzato, invece di un buffer nero mantenuto mentre la preview e' nascosta/offscreen

### Stato finale

- il prossimo build dira' se il JPEG nero era causato proprio dalla cattura di una `TextureView` non renderizzata
- se anche cosi' il bitmap resta nero, il problema sara' quasi certamente nel contenuto della preview fornito dal backend UVC, non piu' nel timing/layout della cattura Android

## 2026-04-01 - Rotazione pixel format per il frame callback UVC

### Richiesta o problema

- i log continuavano a non mostrare nessun `Received first preview frame ...`, quindi il callback basso `UVCCamera.setFrameCallback(...)` non stava consegnando frame raw con il pixel format scelto finora

### File toccati

- `src/android/UsbUvcCamera.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- `installUnderlyingFrameCallback()` non forza piu' sempre un solo pixel format
- ora costruisce una lista di candidati supportati via reflection (`NV21`, `YUV420SP`, `YUV420P`, `NV12`, `YUYV`) e ruota il formato usato a ogni nuova installazione del callback
- il log di installazione ora riporta sia il `pixelFormat` scelto sia tutti i candidati disponibili, cosi' il prossimo test mostra subito se almeno uno sblocca il canale raw

### Stato finale

- il prossimo build ci dira' se il problema dei frame raw era semplicemente un mismatch del pixel format richiesto al callback basso UVC

## 2026-04-02 - Tentativo low-level one-shot del frame UVC durante lo scatto

### Richiesta o problema

- i log hanno confermato che:
  - `captureImage()` AUSBC resta a `640x480`
  - anche `latestPreviewFrame` alto livello resta `640x480`
  - mentre preview negoziata e `TextureView` risultano `1920x1080`
- quindi il punto aperto non era piu' il canvas finale, ma la sorgente frame realmente esposta dal wrapper AUSBC

### File toccati

- `src/android/UsbUvcCamera.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- e' stato introdotto un tentativo low-level molto difensivo e limitato al solo momento dello scatto:
  - viene armato un `IFrameCallback` su `UVCCamera` solo one-shot
  - parte solo se la preview negoziata e' piu' alta del frame AUSBC disponibile
  - usa il primo pixel format low-level disponibile
  - si spegne subito al primo frame o dopo un timeout molto breve (`300 ms`)
- se il frame one-shot arriva, il plugin lo codifica come sorgente foto
- se non arriva, il flusso torna al path corrente (`TextureView` e poi raw fallback)

### Stato finale

- il plugin prova finalmente un frame UVC reale oltre il limite `640x480` del wrapper alto livello, ma senza riattivare il vecchio callback continuo che in passato causava crash nativi

## 2026-04-02 - Disattivazione del tentativo low-level one-shot dopo crash nativo

### Richiesta o problema

- il test runtime del tentativo one-shot low-level ha confermato un esito netto:
  - il callback veniva armato correttamente (`PIXEL_FORMAT_NV21`)
  - ma il processo andava di nuovo in `SIGSEGV` dentro `UVCPreview::do_capture_callback`

### File toccati

- `src/android/UsbUvcCamera.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- anche in modalita' one-shot e con timeout breve, `UVCCamera.setFrameCallback(...)` riattiva sul totem lo stesso ramo nativo instabile gia' visto nei crash precedenti
- questo significa che il path low-level callback non e' affidabile su questa combinazione device/build, neppure se usato solo durante `takePhoto()`
- il tentativo e' stato quindi rimosso del tutto e il plugin torna al path stabile:
  - `captureImage()` best-effort
  - fallback preview `TextureView`
  - raw preview alto livello solo come ultima risorsa

### Stato finale

- il ramo low-level callback viene considerato chiuso per questo totem, perche' riproduce crash nativi strutturali e non semplici errori gestibili lato Java

## 2026-04-02 - Protezione crash nativo su applyStableCameraProfile

### Richiesta o problema

- il totem andava in crash nativo durante `applyStableCameraProfile()`, con stack che passava da `uvc_set_contrast` e `UVCCamera::setContrast`

### File toccati

- `src/android/UsbUvcCamera.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- il profilo stabile applicava automaticamente anche i controlli di processing immagine:
  - `brightness`
  - `contrast`
  - `sharpness`
- su questo device almeno `setContrast` entra in un ramo nativo instabile di `libUVCCamera.so` e puo' abortire il processo
- ora `applyStableCameraProfile()` continua a gestire focus, esposizione e white balance, ma non tocca piu' automaticamente brightness/contrast/sharpness

### Stato finale

- il profilo camera all'avvio dovrebbe tornare stabile sul totem
- i controlli di processing restano separati dal profilo stabile, per evitare nuovi abort nativi durante l'inizializzazione

## 2026-04-02 - Apertura preview riallineata alla massima risoluzione reale

### Richiesta o problema

- i log hanno mostrato una situazione incoerente:
  - preview negoziata bassa `1920x1080`
  - `TextureView` e bitmap catturato `1920x1080`
  - ma ultimo frame preview AUSBC ancora `640x480`

### File toccati

- `src/android/UsbUvcCamera.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- il metodo `resolveTargetPreviewSize()` con `preferHighestResolution=true` continuava comunque a privilegiare prima la size richiesta dall'app (`requestedPreviewWidth/requestedPreviewHeight`), che nel flusso attuale tendeva a essere `1280x720`
- questo puo' inizializzare la pipeline preview/render a una size inferiore, anche se in seguito la negoziazione low-level prova a salire a `1920x1080`
- ora, quando `preferHighestResolution` e' attivo, il plugin sceglie prima la miglior size disponibile da una ladder discendente (`1920x1080`, `1600x896`, `1280x720`, ecc.) e solo dopo ripiega sulla size richiesta originaria

### Stato finale

- l'apertura camera torna coerente con l'obiettivo "massima qualita'" gia' emerso nel worklog
- al prossimo test potremo verificare se anche il frame preview alto livello smette di restare a `640x480` quando l'intera pipeline viene aperta direttamente alla size piu' alta disponibile

## 2026-04-02 - Diagnostica one-shot delle sorgenti foto al momento dello scatto

### Richiesta o problema

- dopo aver confermato che `TextureView` e `Bitmap` sono davvero `1920x1080`, restava da capire se il contenuto renderizzato provenisse comunque da un frame preview a dettaglio piu' basso

### File toccati

- `src/android/UsbUvcCamera.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- e' stato aggiunto un log one-shot `Photo source metrics ...` che riporta insieme:
  - preview negoziata bassa (`UVCCamera.getPreviewSize()`)
  - size del `latestPreviewFrame`
  - formato del frame preview memorizzato
  - provenienza del frame (`underlying` vs callback alto livello)
  - size reale della `TextureView`

### Stato finale

- al prossimo test il log dira' se stiamo renderizzando dentro una `TextureView` `1920x1080` un frame realmente `1920x1080` oppure un contenuto preview piu' piccolo

## 2026-04-02 - Ripristino cattura TextureView sul frame renderizzato successivo

### Richiesta o problema

- i log hanno confermato che `TextureView` e `Bitmap` erano davvero `1920x1080`, quindi il problema qualita' non dipendeva piu' da un semplice upscale di dimensione
- incrociando il codice attuale con il worklog storico, mancava pero' la parte in cui la cattura veniva agganciata al prossimo `onSurfaceTextureUpdated`, cioe' a un frame realmente aggiornato della preview

### File toccati

- `src/android/UsbUvcCamera.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- `attemptTakePhoto()` non usa piu' una lettura sincrona immediata della `TextureView`
- ora arma una cattura one-shot asincrona legata al prossimo `onSurfaceTextureUpdated`, con timeout breve di sicurezza (`700 ms`)
- se il bitmap `TextureView` non arriva o non e' utilizzabile, il plugin ricade ancora sul percorso raw preview gia' esistente
- e' stata anche separata in un helper dedicato la codifica del fallback raw, per mantenere chiaro l'ordine:
  1. `TextureView` su frame renderizzato fresco
  2. raw preview fallback

### Stato finale

- il percorso foto torna coerente con il worklog che in passato aveva dato i risultati migliori sulla preview renderizzata
- al prossimo test potremo capire se la perdita di dettaglio era dovuta a un frame `TextureView` letto troppo presto/non aggiornato, pur con size `1920x1080` corretta

## 2026-04-02 - Diagnostica one-shot della TextureView di cattura

### Richiesta o problema

- i log recenti confermano il fallback dal backend high-res, ma non mostrano ancora la dimensione reale del bitmap catturato dalla `TextureView`

### File toccati

- `src/android/UsbUvcCamera.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- e' stato aggiunto un log one-shot `Texture capture metrics ...` che riporta:
  - dimensione reale della `TextureView`
  - target richiesto alla cattura
  - dimensione effettiva del `Bitmap` restituito
  - se e' stato possibile usare davvero `getBitmap(width, height)` oppure no

### Stato finale

- al prossimo test il log dira' in modo oggettivo se il fallback preview sta producendo un bitmap realmente allineato a `1920x1080` oppure un frame nativo piu' piccolo

## 2026-04-02 - Riallineamento layout TextureView dopo negoziazione high-res

### Richiesta o problema

- incrociando i log recenti con il worklog storico, la foto da fallback preview risultava ancora sgranata pur con preview negoziata a `1920x1080`

### File toccati

- `src/android/UsbUvcCamera.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- il worklog dei giorni scorsi aveva validato il path corretto: preview bassa negoziata fino a `1920x1080`, `TextureView` usata come sorgente foto e JPEG coerente con la size alta
- nel codice corrente, dopo `configureUnderlyingPreviewStream(...)` venivano aggiornati `previewWidth/previewHeight`, ma il layout hidden della `TextureView` non veniva riallineato subito a quella nuova size
- questo poteva lasciare la view fisicamente piu' piccola (per esempio `1280x720`) e poi farle chiedere `getBitmap(1920,1080)`, con effetto di upscale e perdita apparente di dettaglio
- ora, appena la preview low-level entra in `OPENED` e finisce la riconfigurazione high-res, il plugin richiama `applyPreviewLayout()`
- inoltre la cattura dimensionata `getBitmap(width, height)` viene fatta solo se la `TextureView` e' davvero grande almeno quanto il target richiesto; altrimenti il plugin usa il bitmap nativo della view per evitare upscale artificiale

### Stato finale

- il fallback preview torna coerente con il path storico validato nel worklog: size richiesta alta solo quando la `TextureView` e' realmente allineata alla preview negoziata
- se il totem riesce davvero a mantenere la view a `1920x1080`, la foto puo' tornare piena e nitida; se la view resta piu' piccola, il plugin evita almeno di mascherarlo con uno scaling artificiale

## 2026-04-02 - Protezione crash nativo su query gain

### Richiesta o problema

- il totem andava in crash nativo (`SIGABRT`) dentro `libUVCCamera.so` durante la lettura dei parametri camera, con stack che passava da `uvc_get_gain` e `UVCCamera::updateGainLimit`

### File toccati

- `src/android/UsbUvcCamera.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- il metodo `getCameraCapabilities()` eseguiva `uvcCamera.updateCameraParams()` e includeva anche `gain` in `support`, `current` e `ranges`
- su questo device quel percorso entra nel ramo nativo del gain e puo' abortire il processo
- ora il report capabilities evita sia `updateCameraParams()` sia la lettura diretta del `gain`

### Stato finale

- il plugin non dovrebbe piu' crashare quando l'app interroga le capabilities della camera
- il campo `gain` non viene piu' restituito nel report, per privilegiare la stabilita' rispetto alla completezza

## 2026-04-02 - Rimozione upscale artificiale dalla cattura TextureView

### Richiesta o problema

- la foto restituita dal fallback preview risultava visivamente sgranata e non sembrava una cattura nativa reale a `1920x1080`

### File toccati

- `src/android/UsbUvcCamera.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- il fallback preview usava `TextureView.getBitmap(width, height)` con la size negoziata, che puo' restituire un bitmap scalato alla dimensione richiesta invece del bitmap nativo realmente renderizzato
- adesso la cattura usa prima il bitmap nativo della `TextureView` (`getBitmap()` senza width/height), cosi' il plugin non gonfia piu' artificialmente un frame di dettaglio inferiore facendolo sembrare `1920x1080`

### Stato finale

- se la preview renderizzata e' davvero full-res, la foto manterra' quella qualita'
- se invece il frame reale e' piu' piccolo, il plugin smettera' di restituire un'immagine artificialmente upscalata e la qualita' percepita sara' coerente con la sorgente reale

## 2026-04-02 - Pulizia log diagnostici del flusso foto

### Richiesta o problema

- il flusso `takePhoto()` era ormai stabile, ma il logcat restava molto rumoroso per via dei log informativi usati durante il debug di preview, high-res e fallback

### File toccati

- `src/android/UsbUvcCamera.java`
- `src/android/AusbcHighResPhotoCaptureBackend.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- rimossi i log `info/debug` piu' verbosi del percorso foto: richieste di scatto, path file, snapshot reflection, dettagli della negoziazione preview, conferme intermedie del backend AUSBC e dell'encoding preview
- lasciati i `warn/error` che servono davvero a capire fallback o guasti reali, in particolare quando `captureImage()` non raggiunge la risoluzione richiesta o quando l'encoding fallisce

### Stato finale

- il plugin continua a usare il percorso gia' validato (`captureImage()` se sufficiente, altrimenti fallback preview `TextureView`), ma con logcat molto piu' pulito per uso normale

## 2026-04-01 - Reinstallazione del frame callback durante i retry foto

### Richiesta o problema

- i log mostravano ancora assenza totale di frame raw durante `takePhoto()`, e nel blocco runtime non appariva nessuna nuova installazione del callback basso mentre il plugin faceva i retry

### File toccati

- `src/android/UsbUvcCamera.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- la rotazione dei pixel format sul callback basso UVC era utile solo in apertura camera, ma non cambiava nulla dentro il ciclo retry di `attemptTakePhoto()`
- ora, a ogni retry senza `latestPreviewFrame`, il plugin prova a reinstallare subito `setFrameCallback(...)` con il prossimo pixel format candidato
- questo rende finalmente effettiva la rotazione dei pixel format durante il fallback foto, senza dover riaprire tutta la camera

### Stato finale

- il prossimo build dovrebbe mostrare durante `takePhoto()` nuove righe tipo `Installed underlying UVCCamera frame callback ...` e `Reinstalled underlying frame callback ...`
- se dopo questo non arriva ancora nessun `Received first preview frame ...`, il canale raw basso potra' considerarsi praticamente non disponibile su questa combinazione device/libreria

## 2026-04-01 - Cattura TextureView agganciata a onSurfaceTextureUpdated

### Richiesta o problema

- anche dopo i tentativi sul canale raw, il fallback finale restava il bitmap della `TextureView` e continuava a produrre un contenuto nero o non affidabile

### File toccati

- `src/android/UsbUvcCamera.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- il fallback `TextureView` non aspetta piu' due frame stimati con `postOnAnimation`
- ora arma una cattura one-shot legata al prossimo `onSurfaceTextureUpdated`, cioe' al primo frame realmente aggiornato della preview
- resta un timeout breve di sicurezza (`700 ms`) che forza comunque la cattura se l'update non arriva
- questo separa finalmente il problema del timing di cattura dal problema del contenuto renderizzato

### Stato finale

- il prossimo build dira' se il nero dipendeva dal fatto che il bitmap veniva letto prima di un vero update della `SurfaceTexture`

## 2026-04-02 - Ritorno alla priorita' preview high-res quando la negoziazione e' gia' alta

### Richiesta o problema

- il confronto con il commit `087f3fa` e con il worklog storico mostrava che il path piu' promettente per la qualita' reale era quello in cui `takePhoto()` saltava `captureImage()` quando la preview bassa era gia' negoziata ad alta risoluzione
- nei test successivi, invece, il backend `captureImage()` ha continuato a restituire `640x480`, aggiungendo complessita' e un possibile downgrade del flusso foto senza produrre dettaglio migliore

### File toccati

- `src/android/UsbUvcCamera.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- in `attemptHighResTakePhoto()` e' stato ripristinato il bypass del backend `captureImage()` quando la preview gia' negoziata e':
  - almeno pari alla size richiesta dall'app
  - oppure comunque superiore al vecchio limite VGA `640x480`
- in questo caso il plugin passa direttamente al path preview gia' negoziato, che e' il ramo piu' coerente con il risultato storico validato nel worklog (`1920x1080` sul path preview/TextureView)

### Stato finale

- il plugin torna a privilegiare la preview high-res reale gia' disponibile, invece di insistere su un backend still che su questo totem continua a chiudere a `640x480`

## 2026-04-02 - Riallineamento stato interno AUSBC dopo negoziazione low-level high-res

### Richiesta o problema

- i log mostravano una situazione incoerente:
  - preview negoziata bassa `1920x1080`
  - `TextureView` `1920x1080`
  - ma ultimo frame preview AUSBC ancora `640x480`
- l'analisi del sorgente `AndroidUSBCamera` ha mostrato che `MultiCameraClient.openCamera()` sceglie inizialmente la size tramite `getSuitableSize(...)`, e il callback preview/coda NV21 restano poi agganciati allo stato interno `mPreviewSize`/`mCameraRequest`

### File toccati

- `src/android/UsbUvcCamera.java`
- `WORKLOG.md`

### Spiegazione tecnica breve

- dopo la negoziazione high-res su `UVCCamera`, il plugin ora riallinea anche lo stato interno di `MultiCameraClient.Camera`:
  - aggiorna `mPreviewSize`
  - aggiorna `mCameraRequest.previewWidth/previewHeight`
  - svuota la coda NV21 interna
  - riaggancia il `frameCallBack` tipizzato gia' usato dalla libreria, ma dopo il resize negoziato
- in questo modo il path preview di AUSBC ha finalmente una chance concreta di smettere di consegnare frame `640x480` stantii o ancora legati alla size iniziale

### Stato finale

- implementato; il prossimo test dovra' dire se `Photo source metrics` smette di riportare `latestPreviewFrame=640x480` quando la preview negoziata e' gia' `1920x1080`
