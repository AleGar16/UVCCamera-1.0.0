# High-Res Photo Backend Plan

Questo documento separa in modo esplicito due responsabilita' che oggi nel plugin convivono nello stesso stack AUSBC:

- `preview/control backend`
- `photo capture backend`

## Stato attuale

Con il backend attuale:

- preview e controlli UVC funzionano
- `takePhoto()` funziona
- il JPEG restituito viene davvero generato dalla libreria

Ma i log runtime mostrano che anche lo scatto "high-res" del percorso `captureImage()` produce ancora:

- `width=640`
- `height=480`

Quindi il limite non e' piu' nel plugin Cordova, ma nel backend `AndroidUSBCamera/AUSBC` usato oggi per preview e still capture.

## Obiettivo della nuova architettura

Tenere separati:

1. `PreviewControlBackend`
   usa il backend attuale, che ormai e' stabile per:
   - open/close
   - preview
   - controlli UVC
   - reconnect

2. `HighResPhotoCaptureBackend`
   backend dedicato esclusivamente allo scatto fotografico ad alta risoluzione reale.

## Contratti preparati

Nel modulo Android del plugin sono gia' stati creati i contratti:

- `PreviewControlBackend`
- `HighResPhotoCaptureBackend`
- `HighResPhotoRequest`
- `HighResPhotoResult`

E' stato aggiunto anche il placeholder concreto:

- `NativeStillCaptureBackend`

Questi servono a evitare che il prossimo backend high-res venga incollato direttamente dentro `UsbUvcCamera.java`.

## Implementazione consigliata

### Fase A

Creare `AusbcPreviewControlBackend` usando il codice gia' stabile dell'attuale plugin.

### Fase B

Creare `StillCaptureBackend` separato, con una di queste strategie:

- backend UVC nativo piu' basso livello
- percorso still-image capture vero se il device/driver lo supporta
- eventuale backend proprietario/vendor se il totem lo espone

Il placeholder operativo di questa fase e':

- `NativeStillCaptureBackend`

che oggi e' volutamente non implementato ma e' gia' agganciato al progetto come destinazione della prossima integrazione reale.

Per supportare la migrazione graduale e' stato aggiunto anche:

- `CompositeHighResPhotoCaptureBackend`

che permette di provare prima il backend alternativo e, se non disponibile o non implementato, fare fallback sul backend AUSBC attuale.

### Fase C

Agganciare `UsbUvcCamera.takePhoto()` al nuovo `HighResPhotoCaptureBackend`, mantenendo:

- stessa API Cordova
- stesso ritorno base64
- stesso fallback preview come rete di sicurezza iniziale

## Criterio di successo

Il nuovo backend high-res e' da considerare riuscito solo se i log mostrano un JPEG con dimensioni reali superiori a `640x480`, ad esempio:

- `1280x720`
- `1600x896`
- `1920x1080`

## Nota importante

La preview puo' restare a risoluzione piu' bassa. L'obiettivo non e' alzare per forza la preview, ma ottenere un percorso still capture separato che produca una foto realmente ad alta risoluzione.
