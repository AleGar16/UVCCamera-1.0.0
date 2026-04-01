# Cordova USB UVC Camera

Plugin Cordova Android standalone per webcam USB-UVC in scenari kiosk, pensato per i casi in cui la webcam e' visibile via `UsbManager` ma non via `CameraManager`.

## Stato

Questa cartella e' indipendente dal vecchio plugin `Camera2` ed e' pronta per essere pubblicata come repository dedicato.

Gia' presenti:

- `open()` con backend AUSBC / AndroidUSBCamera
- preview nativa `TextureView`
- `takePhoto()`
- `recoverCamera()`
- `listUsbDevices()`
- `close()`

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
- `navigator.usbUvcCamera.refocus(options, success, error)`
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

## Profilo Stabile

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

## Stato Corrente

Il flusso consigliato per il totem e':

- `open()` con `preferHighestResolution: true` e `preferMjpeg: true`
- `applyStableCameraProfile()` subito dopo l'apertura
- `takePhoto()` per lo scatto
- `refocus()` solo quando serve rilanciare l'aggancio del fuoco
