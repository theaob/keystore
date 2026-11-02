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
| Ads | **Yes**, the app contains ads (AdMob banner on the home screen) |
| App access | **All functionality is available without special access** (no login) |
| Content rating | Category **"All other app types"** (utility/tool); answer **No** to every question (no violence, sexual content, profanity, drugs, gambling, user interaction or sharing of location). Expected rating: Everyone / PEGI 3 |
| Target audience | **18 and over** (developer tool, not designed for children; keeps the app out of the Families ad requirements) |
| Advertising ID | **Yes**, used for **Advertising or marketing** (AdMob). The `AD_ID` permission comes from the AdMob SDK |
| News app | No |
| Government app | No |
| Financial features | None |
| Health | None |
| Data safety | See below |

### Data safety

The app's own data (keystores, passwords, profiles, backups) is processed only on the device, so
it is not "collected" under Play's definition. The **AdMob SDK** does collect and share data.
Declare what Google lists for the Mobile Ads SDK
([AdMob data disclosure](https://developers.google.com/admob/android/privacy/play-data-disclosure)),
which currently is:

| Data type | Collected | Shared | Purposes |
|---|---|---|---|
| Location → Approximate location (from IP address) | Yes | Yes | Advertising or marketing, Analytics, Fraud prevention, security and compliance |
| App activity → App interactions | Yes | Yes | Advertising or marketing, Analytics, Fraud prevention, security and compliance |
| App info and performance → Crash logs, Diagnostics | Yes | Yes | Analytics, Fraud prevention, security and compliance |
| Device or other IDs | Yes | Yes | Advertising or marketing, Analytics, Fraud prevention, security and compliance |

- Is data collection required or optional? **Required** (ads are always shown). Personalisation
  is consent-based in the EEA, UK and Switzerland.
- Is all user data encrypted in transit? **Yes.**
- Do you provide a way for users to request deletion? Ad data is held by Google. Users can reset
  or delete their advertising ID in Android Settings. Answer per Google's current guidance on
  the disclosure page above.

Recheck that page before submitting, since Google updates it with SDK releases.

### Permissions

The app declares no permissions itself. Its libraries add:
- `INTERNET`, `ACCESS_NETWORK_STATE` and `AD_ID` (AdMob): covered by the Advertising ID answer above
- `USE_BIOMETRIC` and `USE_FINGERPRINT` (`androidx.biometric`, for the unlock prompt)
- AndroidX's internal `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`

Check the final list under **App bundle explorer → Permissions** after uploading.

## AdMob

- **IDs:** set `admob.appId` (`ca-app-pub-…~…`) and `admob.bannerId` (`ca-app-pub-…/…`) in
  `gradle.properties`. Debug builds always use Google's test IDs. Release builds use test IDs,
  with a build warning, until both are set.
- **Link the app:** in AdMob → Apps → App settings, link it to the Play listing once it's
  published.
- **app-ads.txt:** AdMob verifies the app through `app-ads.txt` at the root of the developer
  website set in Play Console. Use `https://theaob.github.io` as the developer website and
  publish `app-ads.txt` at `https://theaob.github.io/app-ads.txt`. That means a
  `theaob/theaob.github.io` repository containing an `app-ads.txt` file with the line AdMob
  shows you (AdMob → Apps → app-ads.txt). It looks like:
  `google.com, pub-XXXXXXXXXXXXXXXX, DIRECT, f08c47fec0942fa0`
- **Consent:** in AdMob → Privacy & messaging, create a **European regulations** (GDPR) message
  for this app. The app shows it through Google's User Messaging Platform. Without a published
  message, users in the EEA/UK won't be asked and won't receive personalised ads.

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
