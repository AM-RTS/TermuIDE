# Building and releases

[README](../README.md) · [Architecture and verification](architecture/multilanguage-runtime.md)

## Build from source

Use JDK 17 and Android SDK platform/build tools 34. Configure the SDK through `local.properties` or your Android SDK environment. The app targets API 34 and requires Android 8.1/API 27. Sora is pinned to 0.24.4.

From the repository root (use `gradlew.bat` on Windows):

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

The local release APK is unsigned unless signed separately. Optional Java server packaging uses JDK 25 and `bash tools/java_lsp/build.sh <output-directory>`; the source revision is pinned in `tools/java_lsp/revision`. Host tests that exercise real pylsp/Java servers need the environments configured in [java-tooling.yml](../.github/workflows/java-tooling.yml).

## Automated prereleases

[release.yml](../.github/workflows/release.yml) runs installer/launcher checks, packages the optional Java server, runs JVM/Robolectric regressions, and builds, aligns, signs and verifies the release APK. Successful pushes to `master` publish a prerelease with the APK, optional Java binary/source archives, SHA-256 checksums and change notes. Failed checks block publication. Manual runs are supported on `master`; reruns have distinct tags. Test reports are uploaded when generated. PR debug builds cannot publish releases or access signing secrets.

For a repository adopting this workflow, configure **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | Base64-encoded release keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Signing alias |
| `KEY_PASSWORD` | Private-key password |

Use a persistent release key and keep a secure backup. Never commit the key or its passwords. The workflow refuses missing secrets and never publishes an unsigned APK. Publication uses `GITHUB_TOKEN` with `contents: write`; no personal access token is needed.

CI adds `GITHUB_RUN_NUMBER` to the source version code and uses `0.2.4-ci.<run number>` as the version name. Keep version codes increasing for updates. The branch integration workflow retains debug/release APKs and Java tooling artifacts without publishing a release. It also compiles the device-test APK; device tests are not executed by that workflow.

Host checks and signature verification do not establish phone behavior. Device checks are separate; use an isolated package/data set for destructive test fixtures.
