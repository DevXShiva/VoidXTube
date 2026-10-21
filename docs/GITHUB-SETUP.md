# Publishing VoidTube on GitHub

This guide takes the rebranded source and turns it into a public, open-source GitHub
repository whose **GitHub Actions pipeline builds the APKs and publishes them to GitHub
Releases** automatically whenever you push a version tag.

Everything here is run by you, in your own terminal and GitHub account. Nothing in this
repository pushes anywhere on its own.

---

## 1. Create the empty repository on GitHub

1. Go to <https://github.com/new>.
2. **Owner**: your account (e.g. `VoidTube` or your username). **Repository name**: `VoidTube`.
3. **Public**. Do **not** add a README, .gitignore or license — the repo already has them.
4. Click **Create repository**. Copy the URL, e.g. `https://github.com/<you>/VoidTube.git`.

## 2. Push this code

From the folder that contains this project (the one with `build.gradle`, `smarttubetv/`, etc.):

```bash
git init
git add -A
git commit -m "VoidTube: initial rebrand"
git branch -M main
git remote add origin https://github.com/<you>/VoidTube.git
git push -u origin main
```

### 2a. Add the submodules (required)

> **This is the step that most often breaks the first CI run.** A ZIP of the source cannot
> carry Git submodules, so after unzipping you must register them. If you skip this, Actions
> fails with `Could not read script '…/SharedModules/core_settings.gradle'`.

Run the helper (or the three commands it wraps) from the repo root, **before** the first push:

```bash
bash tools/add-submodules.sh
# -> adds SharedModules + MediaServiceCore, pins them to the commits this project expects,
#    and commits them
```

Manually, if you prefer:

```bash
git submodule add https://github.com/aleixrodriala/SharedModules SharedModules
git submodule add https://github.com/yashoncode/MediaServiceCore MediaServiceCore
# pin to the commits this project was built against (recommended)
git -C SharedModules    checkout 11b65d83fc527ecef3ecf2ee9e842a760f41c989
git -C MediaServiceCore checkout 3ef8fd7426def8a613fb77ba29f4a270523ece9f
git add .gitmodules SharedModules MediaServiceCore
git commit -m "Add SharedModules + MediaServiceCore submodules"
```

Then push (`git push -u origin main`). `git submodule status` should now list both with a
`^`/space prefix (not `-`), and the two directories should be non-empty in the GitHub tree.

Both submodule repositories are **public**, so the CI checkout fetches them without any
extra credentials.

## 3. Create a signing keystore (do this once)

A public release must be signed with a key you keep. Create one and **back it up** — losing
it means you can never update the app in place again.

```bash
keytool -genkeypair -v \
  -keystore voidxdevs.jks \
  -alias voidxdevs \
  -keyalg RSA -keysize 2048 -validity 10000

# base64-encode it for the GitHub secret (Linux; on macOS use: base64 -i voidxdevs.jks)
base64 -w0 voidxdevs.jks > voidxdevs.jks.b64
```

> **Never commit `voidxdevs.jks`, `voidxdevs.jks.b64` or `keystore.properties`.** They are
> already covered by `.gitignore`. Keep the keystore somewhere safe.

## 4. Add the signing secrets

In the repo: **Settings → Secrets and variables → Actions → New repository secret**.
Add all four (names must match exactly):

| Secret name | Value |
|:--|:--|
| `SIGNING_KEY` | contents of `voidxdevs.jks.b64` (the base64 text) |
| `KEY_STORE_PASSWORD` | the store password you chose |
| `ALIAS` | `voidxdevs` |
| `KEY_PASSWORD` | the key password you chose |

If you skip this, the release workflow still runs but produces **debug-signed** APKs — fine
for testing, not for public release. The run prints a warning in that case.

Optional, used by the build/lint workflow: `VIRUS_TOTAL_API_KEY` (VirusTotal scan of the APK).

## 5. Cut a release

The release workflow triggers on any tag matching `v*`. The tag must equal the app's
`versionName` (currently `3.0.1` in `smarttubetv/build.gradle`) — the pipeline checks this
against `CHANGELOG.md` and fails early if they disagree.

```bash
# bump versionName/versionCode in smarttubetv/build.gradle and add a CHANGELOG entry first,
# then:
git tag v3.0.1
git push origin v3.0.1
```

That push starts **Release VoidTube APK**, which:

1. checks out the repo **with submodules**,
2. signs with your keystore (from the secrets),
3. runs `./gradlew clean assembleStmobileRelease`,
4. copies the renamed APKs (`VoidTube_3.0.1_arm64-v8a.apk`, `…_armeabi-v7a.apk`,
   `…_x86.apk`, `…_universal.apk`) into `dist/`,
5. generates `voidx.json` — the manifest the in-app updater reads,
6. creates the GitHub Release and attaches every file in `dist/`.

Watch it under the repo's **Actions** tab; the finished release appears under **Releases**.

### Run it by hand instead

**Actions → Release VoidTube APK → Run workflow**, enter a tag such as `v3.0.1`.

## 6. Polish the repo page

- **About** (top-right gear on the repo): description and topics.

  > **Description:** `VoidTube — Your Media. Your Control. A premium, dark YouTube client for Android (fork of NewTube/SmartTube). Auto-built APKs on every release.`

  > **Topics:** `android` `youtube` `youtube-client` `newtube` `smarttube` `media3` `apk` `open-source` `dark-theme`

- **Releases** are created by the pipeline; pin the latest if you like.
- Optionally enable **Discussions**, and a **branch ruleset** on `main` requiring the
  `Build VoidTube APK` check to pass before merge.

## 7. Keeping up to date with upstream

VoidTube tracks its upstream, [**yashoncode/metube**](https://github.com/yashoncode/metube),
via `.github/workflows/sync-upstream.yml`. Every Monday (and on demand) it:

1. fetches upstream's `main`,
2. if there are new commits, merges them on a branch `upstream-sync` and opens a **pull
   request** — or, if the merge conflicts, opens an **issue** asking for a manual merge,
3. never pushes to `main` directly, so VoidTube's branding and UI are only changed when a
   maintainer reviews and merges.

**Why a PR and not a silent auto-merge?** Upstream edits the same files we rebranded
(strings, colours, icons, README, build config). A blind merge would bring the old red
accent and old name back. The PR gives you a diff to review, and the branding is preserved
as long as you keep our versions of those files.

> For the cleanest, lowest-conflict syncing, make your GitHub repo an actual **fork** of
> `yashoncode/metube` (then upstream shares history and merges are usually trivial). A repo
> created from a ZIP has unrelated history, so the first sync is a larger merge.

To sync by hand at any time:

```bash
git remote add upstream https://github.com/yashoncode/metube.git   # once
git fetch upstream main
git merge upstream/main        # resolve conflicts, keeping VoidTube's branding
git push
```

## 8. How the two workflows differ

| Workflow | File | Trigger | What it does |
|:--|:--|:--|:--|
| **Build VoidTube APK** | `.github/workflows/CI.yml` | push to `main`, manual | lints + builds a debug/release APK and uploads it as a workflow artifact (not a Release) |
| **Release VoidTube APK** | `.github/workflows/release.yml` | push a `v*` tag, manual | builds the signed release APKs, generates `voidx.json`, and **publishes a GitHub Release** |
| **Sync upstream** | `.github/workflows/sync-upstream.yml` | weekly cron, manual | opens a **PR** (or an issue) with new commits from `yashoncode/metube`; never touches `main` |

## 9. Troubleshooting

- **`Could not read script '…/SharedModules/core_settings.gradle'`** — the submodules were
  never registered (the classic symptom of unzipping the source and pushing it). Fix it once:
  `bash tools/add-submodules.sh && git push`. See §2a. The checkout step already uses
  `submodules: recursive`; the gitlinks just have to exist in your repo.
- **`CHANGELOG.md starts at X, not Y`** — the tag does not match the newest version in
  `CHANGELOG.md`. Add a `## <version>` entry and bump `versionName` to match the tag.
- **Unsigned / debug-signed APKs** — the four signing secrets aren't all set.
- **`SDK location not found`** — GitHub's `ubuntu-latest` runner ships the Android SDK; this
  only bites on your own machine, where you set `ANDROID_HOME` or `local.properties`.
