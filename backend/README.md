# Omni personal cloud backup

Dedicated project: `omni-bot-ai-personal`. Never deploy this configuration to the parent storefront's Firebase project. No Firestore, Analytics or Gemini integration is needed for backups.

## Activation status — 10 September 2026

The owner upgraded to Blaze. The Cloud Billing API now confirms billing is enabled on **Gyftalala Billing Account**, `01EC63-9FC325-72953F`. The earlier billing association blocker is resolved; a separate quota-support approval has not been independently confirmed.

Firebase Authentication uses Google sign-in. Phone, email/password and anonymous sign-in are disabled. The release certificate SHA-1/SHA-256 and Android OAuth client are registered; Google tokens are verified by Firebase. The web OAuth client is the Credential Manager server client ID. `firebase deploy --only auth --project omni-bot-ai-personal` provisions the configured Google provider; disabling an existing Phone provider is a separate Identity Platform admin configuration change.

The default bucket is **`omni-bot-ai-personal.firebasestorage.app`**, Standard storage in **`asia-south1` (Mumbai)**. Public access prevention is enforced and bucket IAM has no public principals. Deployed Storage rules were downloaded and verified byte-for-byte against `storage.rules`. The release configuration now contains this bucket and the registered Android app's public client values; no service-account key or Gemini key is bundled.

The owner must choose their Google account on the personal device and complete a first backup. Configuration checks and isolated emulator tests do not prove personal Google consent or an authenticated production transfer. No automated test harness is permitted against production Storage or Firestore.

For future rule changes, run `firebase deploy --only storage --project omni-bot-ai-personal` explicitly from this directory and verify the deployed source afterward. Never deploy to an implicit/default project.

## Isolated checks

Use Node.js compatible with Firebase CLI and JDK 21 for the emulators. All test projects are `demo-omni-backup`; the code refuses to use live project configuration in verification builds.

```sh
npm ci
firebase emulators:exec --only auth,storage --project demo-omni-backup "npm test"
```

For Android cloud integration, keep these emulators running, then from the Android project root:

```sh
./gradlew -PomniVerification=true -PomniCloudEmulator=true :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 reverse tcp:9099 tcp:9099
adb -s emulator-5554 reverse tcp:9199 tcp:9199
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r -e class com.gyftalala.omni.CloudBackupIntegrationTest com.gyftalala.omni.verification.test/androidx.test.runner.AndroidJUnitRunner
```

With the cloud verification APKs installed, `ANDROID_HOME=/path/to/android-sdk python3 scripts/verify-cloud-portability.py emulator-5554` from the Android root runs the destructive checks against the emulator-only verification package.

Portability methods require separate `cloudPhase=seed` / `cloudPhase=recover` invocations, with uninstall/reinstall or data clearing of **only** `com.gyftalala.omni.verification` between them. Never clear the personal app to test recovery.

## Data and access

Archives use `backups/<verified Firebase uid>/<random UUID>.omnibak`. A stable Firebase Google account UID owns its backups across installs. The email appears as an account label; a matching arbitrary email string grants no access. Only verified `google.com` sessions can access owner paths. Phone sessions, anonymous identities and other accounts are denied.

The random 32-byte recovery key is `keys/<uid>/backup-key-v1`. Its owner can create it once and read it; clients cannot overwrite, change metadata, list or delete it. The Android app caches it with Keystore encryption. This removes recovery passwords but makes recovery backend-managed: privileged Firebase administrators control the key. It is not zero-knowledge or a promise that the backend cannot decrypt. Losing/deleting the account or key can block recovery.

Archive content is encrypted on Android before upload; no saved labels, card fields or API keys appear in object names/Storage metadata. The account header discloses an opaque owner tag and archive date; Storage exposes size and upload time. The app does not request or publish download URLs. Immutable backups permit bounded owner create/read/list/delete; overwrite and cross-account access are denied. Full verification finishes before showing Restore, and only confirmation commits missing records. Card/ID privacy flags and encrypted originals survive restore. No backup content is sent to AI.

Three completed full snapshots are retained. Google Storage's default seven-day soft-delete policy can retain pruned objects in provider recovery storage and incur storage charges. Every upload is a full snapshot; transfer volume grows with vault size. Google account login removes the app's SMS OTP usage. Storage/operations/network charges still depend on actual use. Project quota is not a spending cap; see [Firebase pricing](https://firebase.google.com/pricing).
