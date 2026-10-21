# Building VoidTube

Requirements: JDK 17 and Android SDK 37 (the app runs on Android 7 / API 24 and later).
Point `local.properties` at your SDK (`sdk.dir=...`), and clone with submodules:

```bash
git clone --recurse-submodules https://github.com/DevXShiva/VoidXTube.git
```

`MediaServiceCore` and `SharedModules` are VoidTube's forks of SmartTube's modules
(`upstream` = yuliskov/<name>).

```bash
# Debug APK (use this for testing on a device)
./gradlew :smarttubetv:assembleStmobileDebug

# Signed distribution build (needs the private keystore.properties)
./gradlew :smarttubetv:assembleStmobileRelease

# Output, per ABI plus universal:
#   smarttubetv/build/outputs/renamed_apks/stmobileDebug/
#   smarttubetv/build/outputs/renamed_apks/stmobileRelease/
```

Install on a connected device, always naming the device:

```bash
adb -s DEVICE_SERIAL install -r smarttubetv/build/outputs/renamed_apks/stmobileDebug/VoidTube_<version>_universal.apk
```

On an x86_64 emulator, add `-PemulatorAbi` so Cronet and J2V8 run natively.

Without the private signing key, debug builds use a separate `.debug` application ID, so
they install next to a release build. Never uninstall a daily-driver install to work
around a signature mismatch: you would lose its data.

## Where things are

- `smarttubetv/src/stmobile/`: the phone interface (Android Views + Material Components)
  and the Media3 player.
- `common/`: SmartTube's presenters, settings and account plumbing, shared with the phone UI.
- `docs/mobile-port/STATUS.md` and `HANDOFF.md`: current state and the deep context behind
  the player and network stack. Read HANDOFF before touching either.

The TV flavors were removed in the phone-only port. The pre-port source is kept at the
`tv-legacy` tag and in the `upstream` remote (yuliskov/SmartTube).
