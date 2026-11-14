English | [Русский](RELEASING.ru.md)

# Releasing and publishing

Pipeline overview (all workflows live in `.github/workflows/`):

| Workflow | Trigger | What it does |
|---|---|---|
| `build-native.yml` | called by the others, or manually | Builds `libeasytier_android_jni.so` for `arm64-v8a`, `armeabi-v7a`, `x86_64` with the Gradle task `:app:buildRustJniLibs`. Cached by core commit + Rust version + NDK version + build-script hash; on an exact cache hit nothing is compiled. Uploads the `native-jni-libs` artifact. |
| `build-app.yml` | pull requests, pushes to `main` | Native libs from the cache/artifact, unit tests, lint, **debug** APKs (artifact). |
| `release.yml` | tag `v*` | Checks the tag equals `version.txt`, builds **signed release** APKs (one per ABI), attaches them and `SHA256SUMS.txt` to the GitHub Release. |
| `fdroid.yml` | after a successful `Release` run, or manually (`workflow_dispatch`) | Adds the release APKs to the F-Droid repository, signs the index with the repo key, publishes `fdroid/` to the `gh-pages` branch. Keeps the last N versions (default 5). |

## Secrets

Add them in *Settings → Secrets and variables → Actions → Repository secrets*. Never commit them and never print them.

| Secret | Used by | Content |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | `release.yml` | base64 of the **APK signing** keystore (`.jks`) |
| `ANDROID_KEYSTORE_PASSWORD` | `release.yml` | keystore password |
| `ANDROID_KEY_ALIAS` | `release.yml` | key alias inside the keystore |
| `ANDROID_KEY_PASSWORD` | `release.yml` | key password |
| `FDROID_KEYSTORE_BASE64` | `fdroid.yml` | base64 of the **F-Droid repository** keystore (PKCS12, alias `repokey`) |
| `FDROID_KEYSTORE_PASS` | `fdroid.yml` | keystore password |
| `FDROID_KEY_PASS` | `fdroid.yml` | key password (for a PKCS12 keystore this is the same value as the keystore password) |

The two keys are deliberately different: the **APK key** signs the app (users' installed copies can only be updated by APKs signed
with it), the **repo key** signs the F-Droid index (clients pin its fingerprint). Keep offline backups of both keystores;
losing the APK key means no more updates for existing installs, losing the repo key means every user has to re-add the repository.

## Generating the keystores

Do this on your own machine, outside the repository, and store the files in a password manager or offline backup.

APK signing keystore:

```bash
keytool -genkeypair -v -keystore easytier-release.jks -alias easytier \
  -keyalg RSA -keysize 4096 -validity 10000
# choose a strong password when prompted; the alias is what you store in ANDROID_KEY_ALIAS
```

F-Droid repository keystore (the alias must be `repokey`, see `fdroid/config.yml`):

```bash
keytool -genkeypair -v -keystore easytier-fdroid-repo.p12 -storetype PKCS12 -alias repokey \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=EasyTier Compose F-Droid repository, O=maksimkurb"
```

Store the secrets with the GitHub CLI (it reads the value from stdin, so nothing lands in the shell history):

```bash
base64 -w0 easytier-release.jks        | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEYSTORE_PASSWORD     # paste, Enter, Ctrl-D
gh secret set ANDROID_KEY_ALIAS             # easytier
gh secret set ANDROID_KEY_PASSWORD

base64 -w0 easytier-fdroid-repo.p12    | gh secret set FDROID_KEYSTORE_BASE64
gh secret set FDROID_KEYSTORE_PASS
gh secret set FDROID_KEY_PASS               # same value as FDROID_KEYSTORE_PASS for PKCS12
```

Local release builds use `keystore.properties` (see `keystore.properties.example`), which is git-ignored.

## One-time GitHub setup

1. **Pages**: *Settings → Pages → Build and deployment → Source: Deploy from a branch*, branch `gh-pages`, folder `/ (root)`.
   The branch is created by the first `fdroid.yml` run. The resulting URL is
   `https://maksimkurb.github.io/EasyTierCompose/fdroid/repo`.
2. **Workflow permissions**: *Settings → Actions → General → Workflow permissions* can stay "Read repository contents";
   the jobs that need to write (`release.yml`, `fdroid.yml`) request `contents: write` themselves.
3. Add the seven secrets above.

## Making a release

1. Set the new version in `version.txt` (for example `1.0.11`) and commit it to `main`
   (`release.ps1` automates bump, commit, tag and push if you use PowerShell).
2. Tag and push: `git tag v1.0.11 && git push origin v1.0.11`. The tag must equal `v` + the content of `version.txt`.
3. `release.yml` builds the signed APKs and creates the GitHub Release. When it succeeds, `fdroid.yml` starts automatically,
   adds the APKs to the repository and publishes it. The job summary prints the repository URL and its **fingerprint**
   (also written to `fdroid/repo-info.md` on Pages: `https://maksimkurb.github.io/EasyTierCompose/fdroid/repo-info.md`).
4. Compare that fingerprint with the one shown by F-Droid when you add the repository.

To republish or rebuild the repository without a new release, run *F-Droid repository* manually
(Actions → F-Droid repository → Run workflow). Inputs: `tag` (default: latest release) and `keep` (default 5).

`versionCode` is the number of commits in the history (`git rev-list --count HEAD`), so workflows check out with
`fetch-depth: 0`. Do not rewrite history after a release; a lower count would stop clients from updating.

## Notes on the F-Droid repository

* Repository state lives in the `gh-pages` tree (`fdroid/repo/*.apk`). Each publish replaces the branch with a single orphan commit
  (force-push), so the branch does not accumulate old APKs in history. Do not store anything else on `gh-pages`.
* GitHub Pages sites are limited to about 1 GB; with three ABI APKs per version, 5 versions is far below that.
* `fdroid/build-repo.sh` can be run locally (needs `fdroidserver`, a JDK for `keytool`, and `ANDROID_HOME` with build-tools):
  ```bash
  FDROID_KEYSTORE_FILE=easytier-fdroid-repo.p12 FDROID_KEY_STORE_PASS=... FDROID_KEY_PASS=... \
    fdroid/build-repo.sh --apks path/to/apks --pages /tmp/pages --keep 5
  ```
* Localized store texts (English and Russian) are in `fdroid/metadata/cc.ptoe.easytier.compose/`; the icon and screenshots are
  taken from `app/src/main/ic_launcher-playstore.png` and `docs/`.
* Inclusion in the main F-Droid repository is separate (a recipe in `fdroiddata`). The Gradle build is compatible with it: no binaries
  are committed, and the native library is built from the submodule (needs `submodules: true`, `protobuf-compiler`, Rust 1.95 with the Android targets and `cargo-ndk`
  in the recipe's `prebuild`/`sudo`/`init` steps). That recipe has not been written or tested.
