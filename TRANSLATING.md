# Translating VoidTube

VoidTube is translated through pull requests on GitHub: edit the string files for your language and
open a pull request in this repository. Merged translations ship in the next release.

## What to translate

The project has two parts:

| Part | What it covers | Where it lives | Status |
|:--|:--|:--|:--|
| **App** | Everything built for the phone: the home feed, the player, comments, downloads, casting, sign-in, updates | `smarttubetv/src/stmobile/res/values-*/strings_mobile.xml` | English and Spanish only: **start here** |
| **Shared settings and messages** | Settings screens, player menus and messages inherited from [SmartTube](https://github.com/yuliskov/SmartTube) | `common/src/main/res/values-*/strings.xml` | About 45 languages, some incomplete |

Some shared strings belong to SmartTube's TV interface and never appear on a phone. You can skip
them.

## Style

- Match the tone of YouTube's own app in your language. If YouTube calls it "Watch later", use its
  translation of "Watch later". People recognise those words.
- Keep it short. Most strings are buttons, menu items and one-line messages on a phone screen.
- Keep **VoidTube**, **SmartTube**, **YouTube**, **SponsorBlock**, **DeArrow** and **Return YouTube
  Dislike** as they are.
- Keep placeholders such as `%1$s` and `%2$d` exactly as written. You can move them within the
  sentence.
- `%%` is a literal percent sign. Keep both characters.
- For counts ("1 reply", "5 replies"), fill in every plural form your language uses.

## Trying your translation

Merged translations ship in the next release, and every release is on the
[releases page](https://github.com/DevXShiva/VoidXTube/releases).

To have your language added, copy the English file into a new `values-<language>` folder and
translate it. If you think a string is unclear or wrong in English,
[open an issue](https://github.com/DevXShiva/VoidXTube/issues/new/choose).

## For developers

- Add new user-visible text to `smarttubetv/src/stmobile/res/values/strings_mobile.xml` (English),
  and add the Spanish version to `values-es/strings_mobile.xml`. Translators pick up new strings
  from `main`.
- Anything the user should not translate (formats such as `0:00`, brand names, glyph paths) gets
  `translatable="false"`.
- Use `<plurals>` and `getQuantityString` for counts, and one format string with numbered
  placeholders rather than joining pieces of a sentence in code.
- To check a translation pull request locally, run
  `./gradlew :smarttubetv:lintStmobileDebug -PtranslationLint`. This runs only the lint checks a
  translation can break (placeholders, plurals, strings missing from the English file).
  `.github/workflows/translations.yml` runs the same check on every pull request that changes
  string resources.
