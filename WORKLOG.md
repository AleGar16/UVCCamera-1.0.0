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
- Motivo tecnico:
  il callback di `open()` arriva prima che lo stato interno sia considerato abbastanza pronto per `takePhoto()`, oppure il plugin segnala open su un evento troppo anticipato rispetto alla preview attiva.
- Stato:
  fix applicato, da validare a runtime.

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
