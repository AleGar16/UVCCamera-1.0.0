# Worklog

Questo file tiene traccia delle modifiche richieste e applicate al plugin `UVCCamera-1.0.0`.

Formato usato:

- data
- richiesta/problema
- modifica fatta
- motivo tecnico
- stato

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
