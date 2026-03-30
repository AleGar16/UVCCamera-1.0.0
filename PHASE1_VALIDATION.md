# Phase 1 Validation

## Decision

For the experimental UVC plugin, the first dependency candidate is:

```gradle
implementation 'com.github.jiangdongguo:AndroidUSBCamera:3.2.7'
```

## Why this coordinate

Two different coordinate styles exist in public docs:

1. GitHub README style:

```gradle
implementation 'com.github.jiangdongguo.AndroidUSBCamera:libausbc:latest_tag'
```

2. JitPack published artifact style:

```gradle
implementation 'com.github.jiangdongguo:AndroidUSBCamera:3.2.7'
```

The main plugin build already failed with an unresolved dependency when using another coordinate/version.

The JitPack package page for `jiangdongguo/AndroidUSBCamera` explicitly shows `3.2.7` under the published dependency snippet, so this is the safest first validation target.

## Source references

- Official GitHub repo:
  https://github.com/jiangdongguo/AndroidUSBCamera
- JitPack package page:
  https://jitpack.io/p/jiangdongguo/AndroidUSBCamera

## Phase 1 objective

This phase only validates that:

1. Cordova Android can resolve the dependency
2. Gradle can package the library in the experimental plugin
3. The kiosk build environment can fetch the artifact reliably

## Not yet included

Phase 1 does **not** mean the plugin is functionally integrated.

Still pending:

- Java/Kotlin API binding to the library
- preview surface strategy
- capture flow
- recovery flow
- focus/exposure mapping

## Success criteria

Phase 1 is considered complete when:

- adding the experimental plugin to a Cordova app does not fail dependency resolution
- Android build completes successfully
- no unresolved classes remain when starting Phase 2 implementation
