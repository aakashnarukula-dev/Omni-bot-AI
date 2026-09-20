# Verification — 0.1.19 / version code 20

Release APK SHA-256: `f5c1efd1f4522408677954b8a374f6a66747e92bea8a5348c190346d70dea850`.

## Full-screen card scanner — 2026-09-10

Card and ID scanning now hides the floating navigation dock for the complete scanner state, including camera-permission and review states. Scanner back control remains available. This removes the dock overlap with status instructions and shutter and prevents accidental page switches while CameraX is capturing. Six scanner/navigation instrumentation tests passed, including hidden-dock exit and preserved Chat draft. Release build, lint and 132 JVM tests passed. Exact audited APK was installed in place on connected Samsung at 18:56:22; app data and original installation timestamp were preserved.

## Backup UX, local copies, permissions and composer — 2026-09-10

The installed release places Chat's composer above the floating dock, including keyboard insets, while Library and normal Cards content can still scroll behind the dock. Scanner mode hides it completely. Backup & restore now has last phone/cloud backup times, size, a single Back up action, Google account, daily time, cellular setting and encryption status. Logout is in the drawer. The Android scheduling paragraph and redundant backup-page buttons are removed.

Manual backup no longer waits for daily battery/network constraints before creating a phone copy. Complete encrypted files publish to Downloads/Omni on Android 10+, with three automatic versions per account; Android 8–9 uses app-specific external storage. The worker uploads the same ciphertext to Firebase, records separate completion times, and preserves the phone copy if offline or cloud upload fails. Empty vaults explicitly report that nothing is available to back up. WorkManager activity and persisted outcomes drive visible progress/results. Initial discovery compares accessible local and cloud archives, verifies newest-first, and bypasses the empty restore page only after a complete, error-free check.

First-open setup offers microphone, notifications and precise alarms. Camera remains on demand. Denial/Not now permits sign-in. Firebase auth state remains observed while a system authentication screen covers MainActivity, and final activity destruction cancels its prompt; this closes the stale-unlock gap found during verification.

Validation of this release:

- Release assembly and lint passed; 132 release JVM tests passed (0 failures, errors or skips).
- The targeted instrumentation run reported OK (29 tests): 27 active checks passed, with 2 separately gated portability entry points skipped. Active checks comprised 17 cloud integration tests, 2 layout tests and 8 startup/permission tests. They cover encrypted local/cloud creation, manual transfer completion, offline local survival, account exclusion, newest verified selection, empty-vault preservation, automatic empty-setup completion, restore, sign-out, daily scheduling, real first-open runtime prompts excluding camera, login lifecycle and composer bounds with a multiline keyboard draft.
- Two earlier backup assertions assumed selected-file-only discovery and server metadata time; they were corrected to check the new automatic copy and authenticated archive time. An intermediate auth run exposed a stale system credential prompt; auth-listener lifetime and destruction cleanup were fixed, and the complete targeted run passed afterward. The final run uses controlled Google-picker responses in an account-free emulator; it does not claim a live production Google consent test.
- Release signature, production Google client and absence of emulator fixtures were verified. The exact audited APK was installed in place on the Samsung at 18:09:55; first-install timestamp is unchanged and launch reported no fatal exception.
- The connected phone visibly shows the new backup layout and the explicit empty-vault result. No synthetic note or card was inserted in the personal app, and no production backup completion is claimed. Composer/layout screenshots and successful/offline transfer tests use only isolated synthetic data.

Evidence: `dist/checks/backup-ux/`. Personal phone screenshots remain local and are excluded from distribution.

Local discovery is constrained by Android storage access: after uninstall/data clearing, a surviving Downloads file may require Backup options > Choose backup file. Automatic cloud discovery remains available after Google login. API keys remain excluded from archives.

## Google sign-in presentation — 2026-09-10

The credential host no longer draws a second welcome page or uses activity slide animations. The original page remains beneath the Google picker. Cancellation and failures return to that page; errors appear inline and another sign-in attempt remains available.

The connected Samsung also revealed a separate Android CredentialSelectorActivity with an empty bottom sheet behind Google's account dialog. Its edge caused the reported line above the gesture bar. The request now uses GetGoogleIdOption with all accounts eligible and automatic selection disabled, so accounts appear in Credential Manager's own sheet. If no usable credential exists, the explicit Google flow remains available for adding or re-authenticating an account. Cancellation does not trigger that fallback. This follows the two supported flows in [Android's sign-in guide](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation).

- Release build, lint and all 132 JVM tests passed.
- Six startup tests passed with the real MainActivity and credential-host lifecycle, Firebase Auth emulator, and controlled external-picker responses in the separate verification app. They cover cancellation, retry, failure returned inline, absence of a duplicate welcome view, successful account return, sign-out, and signed-out/signed-in startup. The test runner and scripted picker subclass are only in the androidTest APK.
- Earlier attempts exposed nondeterministic emulator-provider responses; the final run controls that external boundary instead of depending on real Google account UI on an account-free emulator.
- Samsung confirmed real account-picker opening, Back cancellation, and no pre-login fingerprint prompt after removing the duplicated page. The final single-sheet build was installed in place after USB reconnection, and the installed APK hash matches the audit. The app is now signed in; final picker visual verification awaits permission to sign out, which pauses scheduled backups until the owner signs in again. No personal Google account was selected during testing.

Evidence: `dist/checks/sign-in-transition/`. Phone screenshots/videos containing the owner's account list are retained locally, not included in the distribution.

Run the startup tests after building with `-PomniVerification=true -PomniCloudEmulator=true`, starting the Firebase Auth emulator, forwarding port 9099, and unlocking the test emulator:

```sh
adb -s emulator-5554 shell am instrument -w \
  -e class com.gyftalala.omni.StartupAuthenticationTest \
  -e controlledGoogleSignIn true \
  com.gyftalala.omni.verification.test/com.gyftalala.omni.OmniTestRunner
```

## Startup authentication fix — 2026-09-10

Signed-out startup, resume and the Google sign-in launcher no longer call biometric authentication. Google account state is read from verified Firebase ownership; obsolete local sign-in flags do not unlock or lock onboarding. Successful Google sign-in still requires device authentication before vault access and backup discovery.

- All 132 release JVM tests passed, together with release build and lint.
- Five new `StartupAuthenticationTest` checks passed against the real `MainActivity` in the isolated verification app and local Firebase Auth emulator. They cover cold launch, resume/recreation, legacy flags/local records, opening and cancelling Google sign-in, a successful Google return, sign-out, and a returning signed-in account with an empty vault.
- Connected Samsung showed Google sign-in without a biometric prompt after both cold launch and returning from Home. The signed APK was installed in place; no personal app data was cleared.
- These checks do not validate a live personal Google sign-in or a production backup. Earlier Android results below remain historical, not a rerun of every UI flow for this fix.

Evidence: `dist/checks/startup-authentication/`.

## Current checks

- **132 JVM tests passed**, including 13 classification-preference checks for consistent shopping labels, explicit time/reminder precedence, similar versus unrelated wording, conflicting examples, legacy corrections and sensitive-data exclusion.
- **64 distinct Android checks have passing final results**, using only the separate `com.gyftalala.omni.verification` application on an Android emulator. Nine learning checks cover restart, legacy category history, bounded Gemini examples, removal of old scheduled reminders, correction/deletion precedence, voice classification and encrypted account-backup round trips. The UI/regression checks cover both swipe actions, cancellation/reversal, horizontal strips at their ends, vertical/diagonal scrolling, count sorting, page navigation, camera ownership, editable titles, saved date/time, picker cancellation, rejection of past times, daily repeat, fixed completion controls, large text, dictation, a 60-card wallet, wallet privacy and Gemini request contracts.
- Initial interaction run exposed a Chat-return crash caused by a forced child layout during Pager measurement. Replaced it with a queued list-position update; repeated new-message/tab-return checks now pass. Three test assertions also needed updating: one assumed arbitrary ordering of equal timestamps, and two tried to scroll controls deliberately moved into a fixed footer. Final targeted invocation passed **29/29**; earlier unaffected checks remain recorded separately. `verification-summary.json` merges each test's latest result rather than counting failed attempts as passes.
- Debug and signed release builds passed. Android lint reports **zero errors** and 48 maintenance warnings, mainly dependency/SDK updates and KTX suggestions. No production Firebase/Storage tests ran and no real Gemini key was extracted or used for test traffic.
- Visual review covered the reminder sheet at normal/enlarged text and both swipe directions. Source research and layout decisions are in `DESIGN.md`.
- Release package/version/signature match the existing personal app. The APK includes the expected production Google client and backup bucket, with no emulator fixtures, fake API keys or phone-login activity.

## Installation

Version 0.1.19 installed and launched on the connected Samsung SM-S908E. The in-place update preserved the original installation timestamps and used the existing signing certificate; no personal data was cleared or uninstalled. The installed APK hash matches the audited distribution, and no fatal error appeared at launch. Functional interaction checks used the isolated emulator app; ongoing physical-device gesture feel remains subject to owner use.


## Scope and limits

Personal category learning uses saved, owner-confirmed short text examples, not model-weight training or automatic learning from every AI prediction. Cards, government IDs and attachment contents are excluded. Explicit reminders/times take precedence, and uncertain conflicting patterns are not generalized. New choices and existing recognized corrections survive encrypted backup; deleting a source item removes its example.

No account/backup backend configuration changed in this release. Backup recovery retains the limits verified in the previous release below: Android file grants must be renewed after reinstall and a completed personal cloud backup must be confirmed before relying on recovery.

Evidence: `build/label-learning/`, distributed under `dist/checks/label-learning/`.

# Previous release baseline — 0.1.18 / version code 19

The Google account backup update is installed in place on Samsung SM-S908E. The package remains `com.gyftalala.omni`, first-install timestamp is unchanged, and installed APK hash matches the signed release. No personal app data was cleared or uninstalled. Release SHA-256: `5dd28d5b954ed9e49da87b692bdd2ea5128c0550f2c9b085c9b308c88821e674`.

## Current checks

- **119 JVM tests passed**, including eight account-cipher checks for randomized segmented encryption, wrong key/account/project rejection, modified headers/ciphertext, truncation, appended bytes, unread plaintext and empty payloads. Legacy password-format tests also pass.
- **17 Storage rule tests passed** against disposable `demo-omni-backup`: verified Google ownership, cross-account/anonymous/phone denial, immutable archives, valid paths/MIME/size, and immutable owner-only 32-byte account keys.
- **14 Android cloud integration tests passed**: encrypted worker upload and key/API-secret exclusion; account separation; stale-worker rejection; password-free validated preview followed by explicit Restore; daily scheduling/cancellation; local-vs-cloud creation-date selection; foreign local-file exclusion; corrupted-newest fallback; Skip preserving cloud copies when the vault is empty; local key-cache repair; no-backup onboarding; sign-out cleanup; corrupt local-catalog fallback; missing key failing closed while Skip still completes setup. Two separately gated portability methods are excluded from this count.
- **15 additional Android checks passed** across Google welcome/restore UI, legacy backup storage/UI and wallet privacy. The initial combined invocation had a test-runner initialization error because one newly added Kotlin test returned Boolean. Its return was corrected, and the complete cloud class passed separately afterward. This was a test declaration error, not a suppressed app failure.
- **Two fresh-install recovery checks passed**, plus seed upload: both uninstall/reinstall and clearing verification-app data find and restore the same Google account's cloud archive without a recovery password. Private card fields and original bytes survive under the new installation key; daily backup is enabled after restoration.
- **Native first-open checks passed**: a clean verification install reaches the minimal Google welcome after device PIN; no phone field appears; cancelling Google account selection returns without relocking. Google welcome and Restore/Skip screenshots were visually inspected.
- Debug/release builds and Android lint passed. Release certificate matches the prior installation. Package/version, live Firebase project/bucket/web client, and absence of emulator fixtures/fake API keys/PhoneSignInActivity were checked in the release artifact.
- Backend readback confirms Google enabled, phone/email-password/anonymous disabled, Blaze billing enabled, Mumbai Storage, public access prevention, no public IAM principals and deployed rules matching tested source byte-for-byte. Production checks were administrative/read-only except the explicitly authorized Google/phone/rules configuration changes. No automated production backup writes or personal credential extraction occurred.

Evidence: `build/google-backup/`, copied into `dist/checks/google-backup/` with the distribution.

## Remaining practical limits

The owner must choose their Google account and complete the first personal cloud backup. Emulator recovery tests do not establish live Google consent or a completed production backup. Confirm **Last complete backup** in Settings before relying on recovery. No claim of perfect reliability or independent cryptographic audit is made.

New backups need no recovery password because Firebase holds an owner-protected recovery key; this is backend-managed recovery, not zero-knowledge encryption. Account/key deletion can block recovery. Older password-format files still require their original password. Android requires granting access to surviving phone backup files after reinstall/data clearing, so arbitrary local files cannot be discovered silently. Workers may be delayed by Android and large snapshots can exceed the nine-minute transfer budget. Cloud provider soft-deleted objects remain for seven days after pruning.

## Earlier regression baseline

Version 0.1.17 passed 131 emulator regression checks for OCR, imports, chat dictation, navigation, Clock handoff, reminders, retrieval, encryption and wallet privacy. Those historical results remain in distribution evidence; they do not substitute for the current backup-specific checks above.
