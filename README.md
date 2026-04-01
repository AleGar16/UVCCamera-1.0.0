# Cordova USB UVC Camera

Plugin Cordova Android standalone per webcam USB-UVC in scenari kiosk, pensato per i casi in cui la webcam e' visibile via `UsbManager` ma non via `CameraManager`.

## Stato

Questa cartella e' indipendente dal vecchio plugin `Camera2` ed e' pronta per essere pubblicata come repository dedicato.

Gia' presenti:

- `open()` con backend AUSBC / AndroidUSBCamera
- preview nascosta `TextureView` 1x1
- `takePhoto()`
- `recoverCamera()`
- `listUsbDevices()`
- `close()`

Ancora da rifinire:

- validazione runtime completa sul totem
- `applyStableCameraProfile()`
- hardening del recover per test 24/7
- backend separato per foto high-res reale oltre `640x480`

## Installazione

### Da path locale

```bash
cordova plugin add C:/Users/Ansel002/Documents/GitHub/UVCCamera-1.0.0-standalone
```

### Da repository Git dedicato

Se pubblichi questa cartella come root di un repo GitHub dedicato:

```bash
cordova plugin add https://github.com/<user>/<repo>.git#main
```

## API

- `navigator.usbUvcCamera.open(options, success, error)`
- `navigator.usbUvcCamera.takePhoto(success, error)`
- `navigator.usbUvcCamera.recoverCamera(success, error)`
- `navigator.usbUvcCamera.close(success, error)`
- `navigator.usbUvcCamera.showPreview(options, success, error)`
- `navigator.usbUvcCamera.hidePreview(success, error)`
- `navigator.usbUvcCamera.updatePreviewBounds(options, success, error)`
- `navigator.usbUvcCamera.listUsbDevices(success, error)`
- `navigator.usbUvcCamera.getCameraCapabilities(success, error)`
- `navigator.usbUvcCamera.inspectUvcDescriptors(success, error)`
- `navigator.usbUvcCamera.setAutoFocus(enabled, success, error)`
- `navigator.usbUvcCamera.refocus(options, success, error)`
- `navigator.usbUvcCamera.setFocus(value, success, error)`
- `navigator.usbUvcCamera.setZoom(value, success, error)`
- `navigator.usbUvcCamera.setBrightness(value, success, error)`
- `navigator.usbUvcCamera.setContrast(value, success, error)`
- `navigator.usbUvcCamera.setSharpness(value, success, error)`
- `navigator.usbUvcCamera.setGain(value, success, error)`
- `navigator.usbUvcCamera.setAutoExposure(enabled, success, error)`
- `navigator.usbUvcCamera.setExposure(value, success, error)`
- `navigator.usbUvcCamera.setAutoWhiteBalance(enabled, success, error)`
- `navigator.usbUvcCamera.setWhiteBalance(value, success, error)`
- `navigator.usbUvcCamera.applyStableCameraProfile(options, success, error)`

L'`open(options)` accetta anche:

- `preferHighestResolution: true|false`
- `preferMjpeg: true|false`

Esempio:

```javascript
navigator.usbUvcCamera.open({
  cameraId: "uvc:1133:2093",
  width: 1280,
  height: 720,
  preferHighestResolution: true,
  preferMjpeg: true
}, console.log, console.error);
```

## Controlli UVC

I setter numerici attuali accettano valori percentuali `0-100`.

Il plugin li rimappa ai metodi UVC della libreria sottostante per:

- focus
- zoom
- brightness
- contrast
- sharpness
- gain
- exposure
- white balance

I controlli booleani disponibili sono:

- autofocus on/off
- auto white balance on/off

Per scenari kiosk il plugin ora usa una strategia focus piu' stabile:

- quando la camera apre, prova ad applicare subito l'ultimo focus buono salvato
- avvia un breve autofocus iniziale
- dopo un delay blocca il focus in manuale
- salva il focus bloccato per le aperture successive

Puoi anche forzare un nuovo ciclo autofocus+lock con:

```javascript
navigator.usbUvcCamera.refocus({
  focusLockDelayMs: 1800
}, console.log, console.error);
```

Puoi configurare la strategia dentro `applyStableCameraProfile()`:

```javascript
navigator.usbUvcCamera.applyStableCameraProfile({
  smartFocus: true,
  focusLockDelayMs: 1800,
  autoExposure: true,
  autoWhiteBalance: true,
  brightness: 50,
  contrast: 50,
  sharpness: 50
}, console.log, console.error);
```

Esempio:

```javascript
navigator.usbUvcCamera.setAutoFocus(false, console.log, console.error);
navigator.usbUvcCamera.setFocus(40, console.log, console.error);
navigator.usbUvcCamera.setZoom(0, console.log, console.error);
navigator.usbUvcCamera.setAutoExposure(false, console.log, console.error);
navigator.usbUvcCamera.setExposure(35, console.log, console.error);
navigator.usbUvcCamera.setAutoWhiteBalance(false, console.log, console.error);
navigator.usbUvcCamera.setWhiteBalance(55, console.log, console.error);
```

## Diagnostica descriptor UVC

Per capire se la webcam espone davvero descriptor still-image UVC separati dal preview stream:

```javascript
navigator.usbUvcCamera.inspectUvcDescriptors(console.log, console.error);
```

Il risultato include:

- `stillImageDescriptorCount`
- `hasStillImageDescriptor`
- `canAttemptNativeStillPath`
- `frameFormats`
- `frameSizes`
- `descriptorSubtypes`

Questo serve a capire se vale la pena investire in un backend still nativo vero oppure se il device espone solo il classico percorso preview/video.

## Preview nativa

La preview e' una `TextureView` Android nativa sovrapposta alla WebView, quindi non va inserita dentro un `iframe`.

Puoi mostrarla e riposizionarla cosi':

```javascript
navigator.usbUvcCamera.showPreview({
  x: 20,
  y: 120,
  width: 320,
  height: 240
}, console.log, console.error);

navigator.usbUvcCamera.updatePreviewBounds({
  x: 40,
  y: 140,
  width: 400,
  height: 300
}, console.log, console.error);

navigator.usbUvcCamera.hidePreview(console.log, console.error);
```

## Roadmap high-res

Il plugin e' stato impostato per una nuova fase architetturale:

- il backend attuale resta il riferimento per preview e controlli
- il photo capture high-res verra' separato in un backend dedicato
- e' gia' presente il placeholder [NativeStillCaptureBackend.java](C:/Users/Ansel002/Documents/GitHub/UVCCamera-1.0.0/src/android/NativeStillCaptureBackend.java) per la prossima integrazione reale
- il plugin usa gia' una catena di backend high-res, pronta per dare priorita' al backend alternativo e fare fallback su AUSBC solo se necessario
- l'analisi del vendor upstream e' documentata in [VENDOR_FINDINGS.md](C:/Users/Ansel002/Documents/GitHub/UVCCamera-1.0.0/VENDOR_FINDINGS.md)

Dettagli operativi:

- [HIGH_RES_BACKEND_PLAN.md](C:/Users/Ansel002/Documents/GitHub/UVCCamera-1.0.0/HIGH_RES_BACKEND_PLAN.md)
