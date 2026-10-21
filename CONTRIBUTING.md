# Contributing to VoidTube

Thanks for helping improve VoidTube — a phone-first YouTube client for Android.
**Your Media. Your Control.**

VoidTube is a reworked fork of [NewTube](https://github.com/aleixrodriala/newtube)
(which is built on [SmartTube](https://github.com/yuliskov/SmartTube)). Please keep that
lineage and its notices intact in anything you add.

## Before you start

- Read [`README.md`](README.md) and [`docs/BUILDING.md`](docs/BUILDING.md).
- Clone with submodules: `git clone --recurse-submodules <repo>`.
- Build the debug APK: `./gradlew :smarttubetv:assembleStmobileDebug`.

## Reporting bugs and requesting features

Use the issue templates in [`.github/ISSUE_TEMPLATE`](.github/ISSUE_TEMPLATE). For a bug,
include the app version (Settings › About), your device and Android version, whether you
were signed in, and — if useful — a diagnostic log (Settings › About › Send diagnostic log).
Diagnostic logs are redacted on-device before they leave your phone, but review one before
posting it publicly.

## Pull requests

- Keep changes focused; one concern per pull request.
- Match the existing code style in the module you touch.
- Don't rename the Java packages (`com.newtube.*`, `com.liskovsoft.*`) or the
  `applicationId` — those are compatibility-sensitive.
- If your change touches user-visible text, add the string to the default
  `values/strings*.xml` and, where you can, the matching `values-es/`.
- Never commit secrets, keystores or `keystore.properties`.

## Translations

See [`TRANSLATING.md`](TRANSLATING.md).

## Branding

User-facing brand values (name, developer credit, tagline, Telegram) live in one place and
are overridable at build time — see the Configuration table in [`README.md`](README.md).
Keep them out of hard-coded literals; read the `voidx_*` resources instead.

## License

By contributing you agree your contributions are licensed under the project's MIT license
(see [`LICENSE`](LICENSE)).
