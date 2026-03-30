# Implementation Plan

## Goal

Build a Cordova Android plugin that opens the Logitech USB webcam directly from the USB/UVC stack when `CameraManager` does not expose it.

The target environment is a kiosk app that:

1. opens the webcam at startup
2. keeps the app alive for long sessions
3. takes still photos on demand
4. reapplies a stable focus/exposure profile after recovery
5. avoids requiring full device reboots

## Why a separate plugin

The current plugin is based on `Camera2`.

Diagnostics already proved that on the kiosk device:

- the Logitech webcam is visible through `UsbManager`
- the same webcam is **not** visible through `CameraManager.getCameraIdList()`

That means the current plugin cannot be made reliable only by improving camera selection logic.

## API Compatibility Target

The new plugin should keep the app integration as close as possible to the current bridge:

- `open(options, success, error)`
- `takePhoto(success, error)`
- `recoverCamera(success, error)`
- `applyStableCameraProfile(options, success, error)`
- `listUsbDevices(success, error)`
- optional: `close(success, error)`

Target option shape:

```json
{
  "width": 1280,
  "height": 720,
  "fps": 10,
  "previewFrames": false,
  "jpegQuality": 60,
  "recoverDelayMs": 2500,
  "takePhotoRetryDelayMs": 1500,
  "maxPhotoAttempts": 2
}
```

## Phases

### Phase 1 - Dependency Validation

Pick and validate the UVC backend against the kiosk device.

Candidate direction:

- AUSBC / AndroidUSBCamera family

Validation points:

- Gradle dependency resolves in Cordova Android
- native libs package correctly in the final APK
- permission flow works on the target device
- preview can run on a hidden or minimal surface

Exit criteria:

- demo plugin builds successfully
- `listUsbDevices()` still works
- app can request/open the Logitech USB device

### Phase 2 - Open / Close Lifecycle

Implement:

- hidden render surface or offscreen preview
- USB attach / detach handling
- permission request and connect callback
- explicit device selection preferring Logitech

Exit criteria:

- `open()` reports success
- webcam can be reopened after `close()`
- detach/reattach events are observable

### Phase 3 - Still Capture

Implement:

- `takePhoto()`
- deterministic file path return
- scoped or app-safe storage strategy

Exit criteria:

- still image can be captured repeatedly
- repeated captures do not corrupt camera state

### Phase 4 - Stable Camera Profile

Implement:

- autofocus off
- fixed focus
- auto exposure off
- fixed exposure
- brightness if supported

Exit criteria:

- profile can be applied after open
- profile can be reapplied after recovery
- visible focus blinking is reduced or eliminated

### Phase 5 - Recovery

Implement:

- `recoverCamera()`
- reopen sequence after USB disconnect / preview failure
- watchdog-friendly state transitions

Exit criteria:

- recover works without full kiosk reboot
- repeated recover cycles do not leak resources

## Test Matrix

### Smoke

- app startup with webcam already connected
- one capture after open
- close and reopen

### Soak

- app kept open for hours
- periodic captures
- forced recover after simulated failure

### USB

- unplug and replug webcam
- reboot kiosk with webcam connected
- reboot kiosk with delayed webcam power

### Optical Stability

- open + apply stable profile
- repeated captures with same scene
- verify focus/exposure do not oscillate visibly

## Logging Requirements

Add structured log tags for:

- USB attach / detach
- permission request / result
- device selected
- camera opened / closed
- preview started / stopped
- capture started / completed / failed
- recover started / completed / failed

## Integration Strategy

The main `Camera2` plugin should stay untouched while this plugin matures.

App-side migration path:

1. keep current plugin for production
2. install experimental UVC plugin in a test branch
3. add a parallel bridge such as `app.device.webCamUvc`
4. validate open/capture/recovery on the kiosk
5. switch production only after soak tests pass
