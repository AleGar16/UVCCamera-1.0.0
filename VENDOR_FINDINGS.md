# Vendor Findings

Questo file raccoglie i risultati dell'analisi del codice upstream vendorizzato in:

- `vendor/UVCCamera-upstream`

## Punto chiave

Lo still capture mostrato nei sample `UVCCamera` upstream non e' un vero still endpoint separato dal preview stream.

## Evidenze

### 1. `AbstractUVCCameraHandler.handleCaptureStill`

File:

- `vendor/UVCCamera-upstream/usbCameraCommon/src/main/java/com/serenegiant/usbcameracommon/AbstractUVCCameraHandler.java`

Nel metodo `handleCaptureStill(...)` il sample fa:

- `final Bitmap bitmap = mWeakCameraView.get().captureStillImage();`

e poi salva quel `Bitmap` su file.

Quindi lo "scatto" deriva dal contenuto renderizzato della camera view, non da un canale still separato.

### 2. `UVCCameraTextureView.captureStillImage`

File:

- `vendor/UVCCamera-upstream/usbCameraCommon/src/main/java/com/serenegiant/widget/UVCCameraTextureView.java`

Il metodo:

- `captureStillImage()`

aspetta semplicemente che la texture corrente venga catturata come `Bitmap`.

Anche questo conferma che il percorso still standard upstream e' preview-based.

## Implicazione pratica

Integrare `usbCameraCommon` / `UVCCameraTextureView` "as is" non basta, da solo, a garantire una foto oltre `640x480`.

Per ottenere qualita' fotografica reale bisogna individuare uno di questi percorsi:

1. preview stream realmente negoziato ad alta risoluzione
2. still endpoint UVC vero del device
3. backend vendor-specific del totem/dispositivo

## Conseguenza per il plugin

Il placeholder:

- `NativeStillCaptureBackend`

non dovrebbe limitarsi a replicare il sample `captureStillImage()` upstream, perche' rischierebbe di riprodurre lo stesso limite gia' osservato.
