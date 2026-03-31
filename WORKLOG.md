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
