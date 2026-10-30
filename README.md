# Keystore Generator

An Android app that creates the signing keystore you need to sign an Android App Bundle (AAB)
for Google Play, without a computer or `keytool`. Everything happens on the device; the app has
no internet permission.

It produces the same thing as:

```sh
keytool -genkeypair -v -storetype JKS -keystore upload.jks -alias upload \
        -keyalg RSA -keysize 2048 -validity 9125 \
        -dname "CN=Jane Doe, O=Example, C=US"
```

## Features

- **JKS or PKCS#12** keystore, RSA 2048/3072/4096 or EC P-256 key, self-signed X.509 v3 certificate
- Certificate fields (CN, OU, O, L, ST, C), validity in years, with a warning if the certificate
  would expire before Google Play's 22 October 2033 minimum
- Random strong password generator; optional separate key password for JKS
- Saves the keystore through the system file picker (Drive, Downloads, USB…)
- Shows what `keytool -list -v` would: owner, serial, validity, **SHA-1 / SHA-256 / MD5 fingerprints**
  for Firebase, Google Sign-In and API-key restrictions
- Exports the **upload certificate** as PEM (`keytool -export -rfc`)
- Ready-to-paste `keystore.properties`, `build.gradle.kts` `signingConfig`, `jarsigner` command
  and a Base64 copy for CI secrets
- Screenshots blocked (`FLAG_SECURE`), backups disabled, sensitive clipboard entries hidden on Android 13+

## Project layout

| Module          | What it is                                                                 |
|-----------------|----------------------------------------------------------------------------|
| `keystore-core` | Pure Kotlin/JVM: key generation, certificate, JKS/PKCS#12 writer, snippets |
| `app`           | Jetpack Compose UI (minSdk 26, targetSdk 36)                               |

Android's runtime has no JKS `KeyStore` implementation, so `JksWriter` serialises the format
directly, following OpenJDK's `JavaKeyStore`/`KeyProtector`. The tests load the output with the
JDK's own JKS implementation, list it with `keytool`, and sign and verify a bundle with `jarsigner`.

## Build

Requires JDK 17+ and the Android SDK (platform 36).

```sh
./gradlew :keystore-core:test      # core logic, no Android SDK needed
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

The GitHub Actions workflow runs both and uploads the debug APK as an artifact.

## Using the generated keystore

1. Copy the saved `.jks` file into your project root (and **back it up** with the passwords).
2. Create `keystore.properties` from the snippet the app shows; add it to `.gitignore`.
3. Add the `signingConfigs` block from the app to `app/build.gradle.kts`.
4. `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.

With Play App Signing, this keystore is your **upload key**: Google re-signs the app with the app
signing key it manages. Use the app-signing key's fingerprints from Play Console (not this one's)
for Firebase/Sign-In on production builds installed from Play.
