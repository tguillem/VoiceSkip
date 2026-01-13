# VoiceSkip

Android app for audio/video transcription using whisper.cpp.

## Features

- Audio recording transcription (30-seconds delay)
- Audio file transcription (via share or file picker, also extracts audio from
  video files)
- Multiple Whisper model sizes (base, small)
- GPU acceleration via Vulkan (Starting Vulkan 1.1)
- Turbo mode (GPU + CPU) can achieve arround 3x realtime speed with the larger
  model (ggml-small-q8_0)
- Background transcription with foreground service
- Fully offline and open source

## Supported Languages

Arabic, Chinese, Dutch, English, French, German, Italian, Japanese, Korean,
Polish, Portuguese, Russian, Spanish, Swedish, Turkish

## Requirements

- Android 13+ (API 33)

## Build

```bash
./gradlew assembleDebug
```

Use `-PtargetAbi=<abi>` to build for a specific architecture (e.g.,
`arm64-v8a`, `armeabi-v7a`, `x86_64`).

## Test

```bash
./gradlew testDebugUnitTest
./gradlew connectedAndroidTest -PtargetAbi=arm64-v8a
```

## Benchmark

Run transcription benchmarks and print results to console:

```bash
./gradlew benchmark -PtargetAbi=arm64-v8a
```

By default runs all configurations. With custom args, runs 1 test.

Optional arguments:
- `gpu` - enable Vulkan GPU acceleration (default: true)
- `foreground` - launch activity for GPU foreground mode (default: false)
- `turbo` - enable CPU and GPU

Example with custom settings:
```bash
./gradlew benchmark -PtargetAbi=arm64-v8a \
    -Pandroid.testInstrumentationRunnerArguments.gpu=true \
    -Pandroid.testInstrumentationRunnerArguments.foreground=true

./gradlew benchmark -PtargetAbi=arm64-v8a \
    -Pandroid.testInstrumentationRunnerArguments.turbo=true \
    -Pandroid.testInstrumentationRunnerArguments.foreground=true
```

## Project Structure

- `app/` - Android app (Kotlin, Jetpack Compose)
- `lib/` - whisper.cpp JNI bindings
  - `lib/src/main/jni/` - C/JNI code
  - whisper.cpp fetched via CMake FetchContent

## AI

This kotlin code was developed with AI assistance. All code has been reviewed,
tested, and refined by the author.

## License

Copyright (C) 2025-2026 Thomas Guillem

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version.

See [LICENSE](LICENSE) for details.
