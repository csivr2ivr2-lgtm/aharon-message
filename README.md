# Aharon Message

Aharon Message is an Android-first, offline messenger that sends short encrypted messages through near-ultrasonic audio instead of Internet, Wi-Fi, Bluetooth or a cellular data connection.

## Current target

The active development branch is `feat/user-v1`. The target is a daily-usable Android build with:

- foreground ultrasonic receiver;
- local identity and contact pairing;
- end-to-end encrypted text messages;
- delivery acknowledgements and retries;
- strict-silent frequency profile plus hardware diagnostics;
- local-only storage;
- no server and no Internet permission.

## Build baseline

- Android Gradle Plugin 9.4.0
- Gradle 9.6.0
- JDK 17
- compileSdk 37 / targetSdk 36 / minSdk 31
- Jetpack Compose BOM 2026.08.00

CI builds the debug APK on every push and pull request.
