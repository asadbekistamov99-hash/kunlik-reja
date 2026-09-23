# Release process

The `Release` workflow (`.github/workflows/release.yml`) builds, tests, signs and publishes the APK and AAB.
It runs when a `v*` tag is pushed or when it's started manually (`workflow_dispatch`).

## Signing key (one-time)

```bash
keytool -genkeypair -v -keystore jarvis-release.jks -storetype PKCS12 -alias jarvis \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 jarvis-release.jks > jarvis-release.jks.b64
```

Add these repository secrets (**Settings → Secrets and variables → Actions**):

| Secret | Value |
|---|---|
| `JARVIS_KEYSTORE_BASE64` | contents of `jarvis-release.jks.b64` |
| `JARVIS_STORE_PASSWORD` | keystore password |
| `JARVIS_KEY_ALIAS` | `jarvis` |
| `JARVIS_KEY_PASSWORD` | key password |

Keep the `.jks` file and its passwords backed up offline. If they are lost, you can't publish updates.

If the secrets are missing, the workflow still produces installable artifacts. It signs them with a one-off
key generated in CI and says so in the release notes. Builds signed that way can't be upgraded in place
by builds signed with the permanent key.

## Cutting a release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts` and add a `CHANGELOG.md` section.
2. `git tag v1.0.0 && git push origin v1.0.0`, or run **Actions → Release → Run workflow**.
3. The workflow attaches `JarvisUltra-<tag>.apk`, `.aab`, `mapping-<tag>.txt`, `SHA256SUMS.txt`,
   `CHANGELOG.md`, `README.md` and `INSTALL.md` to the GitHub Release. It also verifies the signatures with
   `apksigner` and `jarsigner`.

## Local release build

```bash
export JARVIS_KEYSTORE_PATH=$PWD/jarvis-release.jks JARVIS_STORE_PASSWORD=... JARVIS_KEY_ALIAS=jarvis JARVIS_KEY_PASSWORD=...
./gradlew :app:assembleRelease :app:bundleRelease
```
