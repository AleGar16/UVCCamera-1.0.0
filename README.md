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
- `navigator.usbUvcCamera.setAutoFocus(enabled, success, error)`
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
