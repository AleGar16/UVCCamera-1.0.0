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
