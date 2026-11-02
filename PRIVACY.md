# Keystore Manager privacy policy

_Last updated: 2 November 2026_

Keystore Manager ("the app") creates, stores and backs up Android app signing keystores. This
policy explains what happens to your data.

## Summary

Your keystores, passwords, backup passphrases and certificate profiles never leave your device
unless you save a file somewhere yourself. The app has no accounts and no analytics of its own.

The app shows a banner ad on its home screen, provided by Google AdMob. To serve and measure ads,
the AdMob SDK collects some device and usage data and sends it to Google. This is described under
**Advertising** below. The ad SDK has no access to your keystores or passwords: they are kept in
app storage that only the app's own code reads, and ads appear only on the home screen.

## Data the app handles, and where it stays

- **Keystores, passwords and backup passphrases.** These are created by you, or opened from files
  you choose. Keystores you keep in the app are stored only in the app's private storage on your
  device. They are encrypted with keys held in the Android Keystore and can be opened only after
  you unlock with your fingerprint or screen lock.
- **Certificate profiles** (name, organisation, city, state, country). These are stored only in
  the app's private storage on your device.
- **Files you save or open.** Keystore files, certificates and encrypted backups are written or
  read only when you pick a location with the Android file picker. They go wherever you choose,
  for example your device, a USB drive or a cloud storage app. Any upload to cloud storage is
  done by that app under its own privacy policy.
- **Clipboard.** Values are copied only when you tap a copy button. Passwords are marked as
  sensitive so Android hides them in clipboard previews.
- **Biometrics.** Fingerprint and screen-lock checks are performed by Android. The app never
  receives or stores biometric data.

The app turns off Android cloud backup of its data. Data kept in the app is therefore deleted when
you uninstall the app, and it cannot be recovered from Google backups. Use the app's encrypted
backup feature to keep copies elsewhere.

## Advertising

The home screen shows banner ads from **Google AdMob** (Google LLC). To serve, personalise (where
you allow it), limit and measure ads, and to prevent fraud, the AdMob SDK may collect and send to
Google:

- your device's advertising ID and other device identifiers
- your IP address, from which approximate location is derived
- information about ad interactions and app usage
- diagnostic information, such as crash and performance data

This data is sent encrypted. Google processes it under its own privacy policy:
[policies.google.com/privacy](https://policies.google.com/privacy). Google's page
[How Google uses information from sites or apps that use our services](https://policies.google.com/technologies/partner-sites)
explains more.

**Your choices.** In the European Economic Area, the UK and Switzerland, the app asks for your
consent before ads are personalised, using Google's consent tool. You can change your choice at
any time with the privacy settings button on the home screen. On any device you can reset or
delete your advertising ID, or opt out of personalised ads, in Android Settings → Privacy → Ads.

## Children

The app is a developer tool and is not directed at children.

## Changes

Changes to this policy will be published at this address with a new "Last updated" date.

## Contact

Questions about this policy: open an issue at
[github.com/theaob/keystore/issues](https://github.com/theaob/keystore/issues).
