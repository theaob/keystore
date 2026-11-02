# Publishing on Google Play

Everything Play Console asks for, and where it is in this repository.

## Release artifact

The **signed app bundle** comes from CI. Merge to `main` (or run Actions → **Build** → **Run
workflow**), then download **keystore-manager-release-<run>** and upload `app-release.aab`.
The version code is the workflow run number, so every run can be uploaded.

When you first upload, accept **Play App Signing** with a Google-generated app signing key. The
keystore in the GitHub secrets is then your **upload key**. If you lose it, Play support can reset
it, but keep its `.ksbackup` safe anyway.

## Store listing (Grow → Store presence → Main store listing)

| Field | Limit | File |
|---|---|---|
| App name | 30 | `fastlane/metadata/android/en-US/title.txt` |
| Short description | 80 | `fastlane/metadata/android/en-US/short_description.txt` |
| Full description | 4000 | `fastlane/metadata/android/en-US/full_description.txt` |
| App icon | 512×512, 32-bit PNG | `fastlane/metadata/android/en-US/images/icon.png` |
| Feature graphic | 1024×500, PNG/JPEG without alpha | `fastlane/metadata/android/en-US/images/featureGraphic.png` |
| Phone screenshots | 2–8, 9:16 | `fastlane/metadata/android/en-US/images/phoneScreenshots/*.png` (1080×1920) |
| Release notes | 500 | `fastlane/metadata/android/en-US/changelogs/default.txt` |

The phone screenshots are rendered by `ScreenshotTest` (`play*` tests). Regenerate them with
Actions → **Screenshots** → **Run workflow** on a feature branch.

The folder follows the fastlane `supply` layout, so it can later be uploaded automatically.

Suggested category: **Tools**. Tags: developer tools, security.

## App content (Policy → App content)

| Section | Answer |
|---|---|
| Privacy policy | URL of `PRIVACY.md` (see below) |
| Ads | **No**, the app contains no ads |
| App access | **All functionality is available without special access** (no login) |
| Content rating | Category **"All other app types"** (utility/tool); answer **No** to every question (no violence, sexual content, profanity, drugs, gambling, user interaction or sharing of location). Expected rating: Everyone / PEGI 3 |
| Target audience | **18 and over** (developer tool, not designed for children) |
| News app | No |
| Government app | No |
| Financial features | None |
| Health | None |
| Data safety | See below |

### Data safety

- Does your app collect or share any of the required user data types? **No.**
  - The app has no internet permission. Keystores, passwords, profiles and backups are stored only
    on the device, or in files the user saves through the system file picker. Google's guidance
    excludes data that is processed only on the device and never sent off it.
- Security practices:
  - Is all user data encrypted in transit? Not applicable, because nothing is transmitted. The
    form doesn't ask when nothing is collected.
  - Can users request deletion? Data is deleted in the app (delete button) or by uninstalling.
    Not required when no data is collected.

### Permissions

The app declares no permissions itself. Its libraries add `USE_BIOMETRIC` and `USE_FINGERPRINT`
(`androidx.biometric`, for the unlock prompt) and AndroidX's internal
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. None of these needs a Play declaration form. Check the
final list under **App bundle explorer → Permissions** after uploading.

## Privacy policy URL

**https://theaob.github.io/keystore/privacy/**

The `Pages` workflow renders `PRIVACY.md` into that page whenever it changes on `main`. Enable it
once under Settings → Pages → Build and deployment → Source: **GitHub Actions**. Edit
`PRIVACY.md` to change the policy; don't edit the generated HTML.

Play also requires a public developer contact email in the store listing (Store settings).

## Testing requirement for new personal accounts

Personal developer accounts created after 13 November 2023 must run a **closed test with at least
12 testers for 14 continuous days** before they can apply for production access. Upload the same
AAB to a closed testing track first. Organisation accounts are exempt.

## Before each release

1. Bump `versionName` in `app/build.gradle.kts` if this is a user-visible release.
2. Update `changelogs/default.txt`.
3. Merge to `main`, download the release artifact and upload the AAB.
