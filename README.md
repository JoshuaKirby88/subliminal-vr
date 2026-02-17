# Subliminal VR Tester

A Meta Quest VR app for testing subliminal message perception using the Meta Spatial SDK.

## Core Features

- **Configurable flash duration**: 15–200ms (displayed as ms + frame count)
- **Background environments**: Indoor room, Black/White void, Landscape, Complex (animated distractors), Passthrough
- **Display types**: Black void/white letters, White void/black letters, No change/black letters, No change/white letters
- **Forward/backward masking**: Mondrian pattern masks before/after stimulus to prevent afterimages and ensure true subliminal perception
- **Repetition control**: 1–10 flashes per trial
- **3-choice guessing**: Select the flashed message from three options
- **Trial logging**: CSV export of all trial data to device storage

## Build

```bash
./gradlew :app:compileDebugKotlin
```

Deploy via Android Studio to Meta Quest device.

## Architecture

- `CustomComponentsStarterActivity.kt` — Main activity, scene setup, UI bindings
- `ExperimentSystem.kt` — Trial state machine, timing, head-locked stimulus
- `TrialLogger.kt` — CSV logging for experiment data
- `LookAtSystem.kt` — Head pose tracking
