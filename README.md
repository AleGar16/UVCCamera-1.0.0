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
- `navigator.usbUvcCamera.listUsbDevices(success, error)`
- `navigator.usbUvcCamera.getCameraCapabilities(success, error)`
- `navigator.usbUvcCamera.applyStableCameraProfile(options, success, error)`
