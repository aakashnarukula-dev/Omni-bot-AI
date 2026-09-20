# Omni bot AI

## Version 0.1.19 — personal categories and clearer interactions

- Manual category choices become encrypted preference examples. “Order oats” and “Order seeds” both sort as Want to buy; explicit reminder wording or a time still creates a reminder. Existing explicit category corrections are recognized, and future corrections persist across restarts and encrypted backup/restore. Similar wording can follow one correction; broader action patterns require two consistent examples. Conflicting examples do not silently establish a broad rule. Deleting the source item removes its preference. This is personal classification memory, not Gemini model fine-tuning.
- Learning uses short, non-sensitive text only. It excludes wallet cards, government IDs, attachment contents and detected private information. If Gemini is enabled and an uncertain message needs it, at most four relevant confirmed text examples accompany that request. Full conversation history is not uploaded for learning.
- Item details show a category menu above the title. Tap the title to rename. Reminders have one Date & time row, a saved Repeat daily switch and a compact priority menu. Topic is removed from this sheet; previous metadata remains intact. Original wording stays available in a disclosure. Mark done and Delete item remain in the bottom action bar. Card fields retain a separate Edit card details action.
- Date/time editing starts with the saved future schedule. Choose date, tap Next, choose time, then Save. Cancelling either step preserves the schedule; past times stay in the picker with an explicit message.
- Swipe saved-item receipts left to mark Done or right to request deletion. Icons and labels appear behind the moving receipt. Short, cancelled and reversed drags do not commit actions. Done items remain saved and searchable. Deletion requires confirmation. Equivalent accessibility actions are available.
- All stays first in Library; category pills follow descending item counts with stable ties. Selection stays with its category when counts change.
- Swipe between Chat, Library and Cards. A gesture starting on a receipt controls that receipt; open page areas navigate. Horizontal photo/filter strips do not turn the page at their ends. Vertical lists and the wallet stack keep their own axis. Drafts, filters and scroll positions survive navigation. Leaving Chat stops dictation; the scanner binds only on the active settled Cards page.

## Version 0.1.18 — Google account backup and recovery

First setup offers microphone, notification and precise-reminder permissions, then a minimal Google sign-in page. Camera permission is requested only when a camera feature is opened. The device unlock gate is used only after verified Google sign-in. No phone number, SMS OTP or new recovery password is used. Google sign-in also provides the persistent account session for daily Firebase Storage backups; there is no second backup login.

After sign-in, Omni checks cloud archives, its accessible automatic phone backups and previously granted backup files. It compares authenticated archive dates and offers the newest fully verified backup with **Restore** or **Skip restore**. If both checks succeed and no matching backup exists, setup enters Chat automatically. Failed checks and corrupt archives do not count as an empty result. Restore adds missing records and preserves existing edits. Android can require selecting surviving phone files again after reinstall/data clearing; **Backup options > Choose backup file** grants that access.

New cloud and exported file backups use account-bound streaming encryption. A random recovery key is stored in owner-protected Firebase Storage and cached under this phone's Keystore encryption. This enables password-free recovery after signing in with the same Google account. **Recovery is backend-managed, not zero-knowledge encryption:** the Firebase project controls the recovery key. Verified Google identity and Firebase UID enforce ownership; email is the visible account label, never an editable authorization credential. Deleting the Firebase user or recovery key can prevent recovery. Legacy password-format archives still require their original password; their codec remains supported internally, but the legacy import entry is not shown in the account backup UI.

Daily backups default to **3 AM local time**, editable under Automatic backups. Both scheduled and manual runs create an encrypted phone copy before uploading to Firebase. Automatic phone files are published atomically under **Downloads/Omni** on Android 10+, or app-specific external storage on Android 8–9. The newest three automatic versions are retained per account. Cellular uploads are off by default. Manual backup starts immediately without waiting for the daily battery constraint; an offline or disallowed-network run keeps its phone copy and reports cloud retry status. The screen shows progress, phone/cloud completion times, size, empty-vault feedback and errors. **Log out** is in the side menu; it pauses scheduled backups while keeping saved items and existing backups. The Gemini API key is excluded; the selected model is portable. Card/ID originals remain encrypted, privacy flags survive restore, and backup content never goes to AI.

The dedicated Firebase backend is on Blaze with Google authentication and private Storage in Mumbai. See [backend/README.md](backend/README.md), [BACKUP.md](BACKUP.md) and [VERIFICATION.md](VERIFICATION.md). A successful emulator run does not establish that the owner has completed a first personal cloud backup; check the **Cloud** timestamp under **Last backup** on the phone before relying on recovery.

## Version 0.1.17 — Historical phone-based backup

Introduced daily encrypted cloud snapshots. Its phone OTP and recovery-password setup were replaced by the Google account flow in 0.1.18.

## Version 0.1.16 — Ringing alarms through Clock

Explicit requests such as `set an alarm for 12:55 am` or `wake me up at 7 am` now open the phone's Clock app with the requested time. Check its confirmation and enabled state there. These requests stay local and do not create Omni reminder notifications. Missing or unclear times receive a question in Chat; `every day at 7 am` requests a daily alarm. Regular tasks such as `remind me to call Sujatha in 20 minutes` keep using Omni reminders.

The chat action becomes **Open Clock** after a successful handoff, so revisiting history does not create another alarm. A failed handoff offers retry without claiming success. Old messages are not converted automatically. Clock owns ringing, snooze, volume, dismissal and deletion; `show my alarms` or `delete alarms` opens Clock for management. Clock alarms are not included in Omni's encrypted backup, although their chat history is.

The public Clock action supports a time of day and repeating weekdays, not an arbitrary future calendar date. Unsupported dates, conflicting times and nonlocal time zones require correction or setup in Clock. Relative times round up to the next minute. Pending one-time requests are checked again before launch so expired times cannot silently move to tomorrow. Integration follows [Android's alarm intents](https://developer.android.com/guide/components/intents-common#Clock).

## Version 0.1.15 — Composer and navigation spacing

The chat field uses a full pill shape that follows the circular attachment and mic/Send controls. The floating navigation now wraps its three tabs with an even 8dp inset around the outer circles, removing the extra space at either end.

## Version 0.1.14 — Encrypted backup and chat dictation

Settings > Backup & restore creates a portable, password-protected `.omnibak` file and restores missing records after validation and confirmation. Includes chats, original files, cards, IDs, reminders and model preference; excludes the Gemini API key and cloud consent. Existing records and edits win during restore.

Voice input now lives in Chat. An empty composer shows the microphone; typed or transcribed text replaces it with Send. Partial speech results fill the editable draft. Nothing is sent until you tap Send. Editing, sending, attaching or leaving Chat stops dictation. The separate voice page and dock microphone are removed; the floating dock contains Chat, Library and Cards.

## Version 0.1.13 — Natural card chip

Payment-card chips now use a brushed gold contact pattern with engraved separators and restrained metallic highlights. Wallet cards and shared/exported PNGs use the same vector artwork. Rendering stays on-device.

## Version 0.1.12 — Scanner, navigation and wallet privacy

- Camera-first Cards screen with the reference-style guide, type pills below the frame and circular shutter. An empty wallet opens the scanner directly.
- Floating navigation provides Chat, Library and Cards. Reminders live in Library's Reminder category; open one to schedule, complete or edit it.
- Library and wallet use one header with Settings beside the title. Chat attachments offer Camera, Photos and Documents.
- Cards and IDs saved through Cards are excluded from Gemini requests, including after renaming or recategorizing. Chat retrieves these records locally by their labels, then displays the full saved details after unlock. Stored originals remain encrypted.
- Generic uploads still use OCR-based sensitive-content detection; those heuristics cannot guarantee recognition of every sensitive image. Dedicated Cards scanning always stays local.

Native Android personal memory app, built with Kotlin and Jetpack Compose. A soft charcoal interface with warm off-white text, muted supporting labels, subtle metallic accents and richly shaded wallet cards. Captures text, photos, screenshots, links, PDFs, APKs and other files; sorts them into a searchable private library; delivers reminders; renders a card wallet with full details after device authentication.


## Direct wallet interaction (0.1.11)

- Drag the compact stack down: cards follow your finger, with thin peeking strips and depth scaling. Slow releases settle based on distance; quick flicks choose direction. The stack springs into place.
- Drag the handle or a card's top edge up to close. Expanded card bodies scroll, keeping every card reachable in larger collections. Expand/Collapse also works by tap.
- Cards and IDs use consistent 1.586 proportions and compact typography. Tap a card to toggle **Share** and **Details**; Details opens full fields, editing and originals. Only one action strip appears at a time.
- **Add Card** floats above navigation and opens the existing scanner. Search sits in the wallet header; All/Cards/IDs filters remain.
- **Share** opens Android's share sheet with a deterministic PNG of the selected card, including full stored fields. IDs use their original front plus corrected stored fields. Returning or canceling does not ask for authentication again; screen-off and ordinary app switching still lock.

Shared images are rendered on the device into memory and sent through temporary, read-only content grants. No plaintext share-cache files are created; encrypted originals stay unchanged. Grants expire after 10 minutes, when the process ends, or after newer shares evict them (at most four retained). A recipient that already received the image keeps its own copy. Sharing does not send anything until you select a destination in Android.

## Navigation and reminder commands (0.1.10)

Chat and Library keep their scroll positions when switching tabs. Library search/filter choices and the wallet's expanded state are retained. Returning to unchanged chat history no longer animates through old messages; new messages still become visible.

Type **delete reminders** to see saved reminders, select the ones to remove, then confirm with **Delete N reminders**. **Keep reminders** cancels without changes. Named requests such as **delete reminder for Suresh** narrow the list. No matches produce an explicit response without creating another memory. Deletion cancels alerts and removes the selected reminder's saved files and linked chat entries. The list is a snapshot: newly added or changed reminders cannot be silently included in an earlier confirmation.

Commands are handled locally before Gemini. Voice deletion requests also require the separate confirmation in Chat. **Remind me to delete reminders tomorrow at 8 pm** remains an ordinary scheduled task. Unsupported qualifiers are not treated as “delete all”; use the selection list for precise control.

## Wallet and voice (0.1.9)

- **Cards:** collapsed wallet stack, pull down or tap Expand to see every card, search, and Cards/IDs filters. Tap a card, then Details, to open full details and originals.
- **Photo-matched finishes:** sampled on the device, darkened for readable text, stored with the encrypted record, and reused for PNG export. Existing cards are updated when the wallet opens. No image-generation service creates card details.
- **Identity documents:** select PAN, Aadhaar, DL or Other ID in the scanner. Recognized number, name and date of birth remain editable. Scan the optional back, then Save ID. IDs appear in both wallet and Documents; ask “Show my PAN” to retrieve one. These are OCR candidates, not identity verification.
- **Voice:** tap the microphone in an empty chat composer and allow microphone access. Live transcripts appear in the same field when supported by Android's speech service. Tap Stop to finish, edit if needed, then tap Send. Recognition uses the phone's language. Voice input never automatically sends a message.
- **Tasks:** Urgent/Normal/Low priority and Personal/Work/Home/Health/Bills/Finance/Ideas/Engineering topics. Reminders can be filtered by either; edit metadata from item details. Local hints prefill metadata for new messages.

ID fields and original attachment bytes use the existing encrypted vault. No durable plaintext ID photos or disk thumbnail cache are introduced. Camera review temporarily holds private capture files; cancel/save removes them, and the next cold start removes captures abandoned by process termination. Scanned and detected identity documents bypass Gemini.

Voice input prefers Android's on-device recognizer when available. Otherwise the UI explains that Android's speech service may process audio online. Recognition language/model availability depends on the phone. Omni does not retain raw recordings. Recordings stop when the screen closes or the app leaves the foreground. [Android SpeechRecognizer contract](https://developer.android.com/reference/android/speech/SpeechRecognizer).

Local multi-item splitting and spoken-time normalization target English. Eligible uncertain text classifications can use the existing Gemini settings; full utterance understanding and arbitrary compound-language splitting are not guaranteed. Every transcript remains editable before sending. Missing or ambiguous reminder times remain unscheduled until the owner chooses a time. The existing phone authentication, attachment permission-return behavior, reminder engine and screenshot-testing preference remain enabled.

## Install and use

1. Copy `dist/Omni-bot-AI.apk` to your Android phone and open it. Allow installation from the app used to open the APK if Android asks. Android 8.0 or newer is required.
2. Open **Omni bot AI**, review the initial permissions and sign in with Google. Only after sign-in, unlock with your device fingerprint, PIN, pattern or password. If the phone has no screen lock, set one in Android security settings first.
3. In Omni **Settings**, enable notifications and precise alarms. Both are needed for timely background alerts.
4. For cloud AI, get a [Gemini API key](https://aistudio.google.com/apikey), open **Settings > API key**, save it and turn on **Gemini AI**. The default model is `gemini-3.8-flash` and can be changed. Provider usage limits and charges apply to your key.

No bundled Gemini API key or AI subscription. Optional cloud backup uses a separately configured Firebase project. Without a key, local sorting, OCR, storage, search and reminders still work. Harder visual classification needs Gemini or a category choice.

## Try these flows

| Input | Result |
| --- | --- |
| `USB Type-C to Type-C cable` | Saved under Want to buy |
| An Amazon product URL | Saved as a shopping link, with an Open saved link action |
| `Do puja` | Asks when to remind |
| `angiogram discharge summary update` | Asks when to remind |
| `call Sujatha madam` | Asks when to remind |
| `tomorrow at 8 pm`, after a reminder question | Schedules the pending task; confirms full date, time and timezone |
| `in 20 minutes` | Schedules a pending task relative to now |
| `every day at 8 am` | Schedules a daily reminder |
| A product-page screenshot | Asks UX design or Want to buy when intent is unclear |
| Card front and back photos | Combines photos into one encrypted card record; shows full extracted details |
| `send me IndusInd debit card` | Returns its virtual card in chat, or explicitly says it is missing |
| A bank PDF | Stores the original and indexes local OCR from its first five pages |
| An APK | Saved under APK, with original export; never automatically executed |

Use Android's Share menu from other apps to send text, links or files to Omni. In Chat, the centered circular **+** button opens **Camera**, **Photos** and **Documents**. Documents accepts PDFs, APKs and arbitrary files. The dedicated Cards section opens a live camera scanner: hold one card steady for automatic capture, or capture manually. Review the extracted details, optionally scan the back, correct any field, and tap **Save card**. Both originals are stored with one card record. The wallet's **Add Card** and saved cards' **Scan other side** use this same scanner. Use the dedicated Cards section for cards and IDs so their contents always stay local.

Photos appear directly in chat, including existing saved photo messages. Saved-item cards in Chat and Library show a photo thumbnail on the left when an image is available. Tap a preview to open its saved details and original files; swipe sideways through multiple photos. Captions stay below the photos, while PDFs, APKs and other files keep filename rows. Previews load locally before AI finishes and remain available when Gemini is unavailable. The original colors and image contents are preserved.

The Library searches titles, message text, filenames, categories and OCR. Open an item to view images, export originals, correct its title/category, edit card fields, schedule it or delete it. **Save card image** writes a deterministic PNG containing stored card details to your chosen destination.

## Privacy and data behavior

- Android BiometricPrompt gates content at launch and on ordinary returns from the background. Photo/file pickers, camera capture, card share sheets, export destination pickers, notification permission dialogs, and Android notification/exact-alarm settings started inside an unlocked session return without another prompt, including cancellation. Screen-off invalidates that session; authentication is never restored across process recreation.
- Full card numbers, expiry, cardholder and CVV are visible after unlock. Card numbers failing a Luhn checksum are left blank for correction. General imports require labeled names and CVVs. In the explicit card scanner, one plausible printed name beside a valid number and one standalone three-digit code on a scanned back can populate review fields. These are OCR hints, not guarantees; check every value before saving. Unknown card type remains “Payment card.”
- Messages, metadata, OCR, settings and card fields are encrypted with AES-256-GCM before entering SQLite. A per-installation record key is encrypted by the non-exportable Android Keystore key and cached in process memory for fast record operations. Attachments retain direct Keystore encryption. Older records remain readable. Record IDs, types and timestamps are plaintext.
- Encryption keys are not bound to an interactive biometric operation because the background receiver must read reminders while the app is closed. Device authentication gates interactive access; this is not a hardware-auth-bound password manager.
- The Gemini key is encrypted locally and sent only to Google's API. Cloud mode is opt-in. Recognized cards and sensitive material bypass Gemini. Images without readable OCR require a category choice before cloud description. Detection is heuristic and can miss sensitive content: use dedicated Card import for cards, or keep cloud mode off for fully local handling.
- Card images use Android Canvas, not a generative image model. Stored details and corrections remain exact.
- Screenshots and app-switcher previews are temporarily enabled for this testing build at the owner's request. Build with `-PomniAllowScreenshots=false` to restore screenshot protection. Reminder text remains private on the lock screen. Camera cache files are removed after processing or camera cancellation. Leaving or locking the live scanner releases its camera and discards unsaved scan photos; ordinary chat drafts and tab selection are preserved.
- Android system backup and device transfer remain disabled. Omni’s optional daily encrypted cloud backup requires its dedicated backend, verified phone account and recovery password. Manual file backup is also available. Uninstalling or clearing app storage destroys this phone’s vault; recovery needs a completed external backup and its password.

## Encrypted backup and restore

1. Open **Settings > Backup & restore > File backup & restore > Create backup** and choose a destination in Android's file picker. Use a location outside the phone if you need protection against losing the phone.
2. Enter and confirm a unique 12–256 character backup password. Spaces and Unicode are supported exactly; leading/trailing spaces are significant. Acknowledge that a forgotten password cannot be recovered, then tap **Save encrypted backup**. Keep Omni open until it reports success.
3. On this or another Android phone, install Omni, unlock it, choose **Restore backup**, select the complete `.omnibak` file and enter its password.
4. Review the verified date and counts, then tap **Restore missing items**. Existing IDs and edits are retained. Repeating a restore does not duplicate records. A record deleted after a backup can be added again by restoring that older backup.
5. Check notification and precise-alarm permissions. Pending past reminders may alert immediately; future reminders are scheduled again. The current phone's Gemini key and cloud preference are kept. A fresh installation starts with cloud AI off and needs its own API key.

Backups use [Tink streaming authenticated encryption](https://developers.google.com/tink/streaming-aead), AES-256-GCM-HKDF with 1 MiB segments, plus PBKDF2-HMAC-SHA256 with 600,000 iterations and a fresh 32-byte salt. The format header is authenticated. Manual backup passwords are not persisted. Enabling daily backup stores its recovery password encrypted in this phone's vault for background work; the password is excluded from the archive and is never uploaded or sent to Gemini. See [OWASP's PBKDF2 guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#pbkdf2) and [Android cryptography guidance](https://developer.android.com/privacy-and-security/cryptography). This is a documented implementation, not an independent security audit.

No plaintext archive is written. Original bytes pass through streaming encryption. Restore stages files under the destination's vault key and commits records only after authenticated end-of-stream and reference validation. A crash journal contains only opaque file IDs; cleanup removes unreferenced restored files while retaining committed ones. Wrong passwords, modified/truncated files and invalid manifests cannot partially import records. Restoring does not overwrite current items or source archives. Model preference is the only portable app setting; API keys and device permissions are not backed up.

Current backup limits: 16 MiB manifest, 50,000 records, 10,000 attachment references, 10 GiB total originals, and the existing 100 MiB per-file limit. Restore needs free space for encrypted staging plus 32 MiB headroom. Each backup is a snapshot; later changes appear only in a subsequent manual or daily backup. If a process is killed while saving to an external provider, an incomplete encrypted file can remain there; it cannot pass restore validation. Delete that incomplete file and create another backup. The app can report the provider's successful write, but cannot independently verify a cloud provider's remote durability.

## Current boundaries

- Arbitrary files can be stored, up to 100 MB each and 10 per message. Content decoding covers images, supported text and the first five PDF pages. Audio/video transcription, Office document parsing and APK execution are not implemented.
- Image previews decode files up to 24 MB and downsample to at most 1,400 pixels per side. Unsupported, corrupt or larger images show a fallback; their original files remain exportable. Preview caching uses memory only, with no plaintext thumbnail files.
- Shopping URLs are stored and detected by domain; the app does not scrape pages, fetch prices or purchase anything. Image AI describes the first image of a multi-image message; OCR scans all images. Mixed unrelated files become one memory, so send separate messages for different subjects.
- Retrieval matches local words across stored content, rather than using embeddings. It supports the documented request forms and category searches. It does not answer arbitrary general-knowledge questions.
- Time parsing supports relative minutes/hours/days, AM/PM or 24-hour times, tomorrow, weekdays, ISO dates and daily repetition. Ambiguous dates, unsupported recurrence and vague times need clarification or the date/time picker.
- Stored reminders restore after normal process death, reboot and package updates. After reboot, the first device unlock must make encrypted storage available. Android force-stop disables delivery until reopening the app. Exact-alarm permission, notifications, OEM battery rules and device availability affect delivery. Denied precise alarms use a potentially delayed fallback with an explicit notice.
- SQLite, encryption, OCR and network operations run off the main thread. Inputs persist before AI processing; images are downsampled; file copying is bounded. Search decrypts records on the IO dispatcher; very large libraries will need an encrypted search index and pagination.
- Live Gemini 3.8 Flash text classification passed on the phone in 1.8 seconds for the synthetic connection-check sample. Broader multimodal accuracy and sustained latency remain unmeasured. Local phone measurements appear in VERIFICATION.md.

## Build

Open this folder in Android Studio, or use JDK 17 and an Android SDK with platform/build-tools 34:

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

`app/build/outputs/apk/debug/app-debug.apk` is installable for development. The prepared `dist/Omni-bot-AI.apk` is a signed release with R8 code/resource shrinking. It contains no sample data or Gemini API key. Firebase public client configuration is included only after backend activation.

The private release keystore is `.signing/omni-personal.jks`; credentials are in the excluded `signing.properties`. Both stay local with restricted permissions. Back them up privately: future updates must use the same key to preserve installed app data. The source ZIP excludes all signing secrets. Without `signing.properties`, `assembleRelease` produces an unsigned APK.

```sh
./gradlew :app:assembleRelease
```

Do not install the development APK over the personal release; their signing keys differ.

## Verification

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
# Separate package only: this script cannot reset the personal app's vault.
ANDROID_HOME=/path/to/android-sdk bash scripts/verify-device.sh DEVICE_SERIAL
```

Unit coverage: supplied intent examples, retrieval boundaries, card extraction, malformed model output, invalid/past times, recurrence and DST gaps. Android coverage includes paired-card OCR, PDF OCR, original byte recovery, partial-import rollback, missing retrieval, notification permissions, actual alarm delivery, settings/model discovery, card editing/deletion, large text and a 1,000-item vault.

Instrumented tests require `-PomniVerification=true`, which uses `com.gyftalala.omni.verification`. Destructive test setup checks this exact package before touching data. The synthetic UI test activity exists only in debug builds. The device script also runs denied-permission and process-recovery checks separately. It never uninstalls or clears `com.gyftalala.omni`.

See [GEMINI-MODELS.md](GEMINI-MODELS.md) for the current official model review, prices, API contracts, privacy terms and retirement dates. Settings shows your saved-key status, a model chooser and **Check connection**. Settings save immediately. **AI model > Browse available models** lists compatible models for your key. **Check connection** sends one synthetic cable message and consumes API quota; it does not send saved content.

## Code map

- `MainActivity.kt`: device authentication, Android share/import/camera/export.
- `OmniViewModel.kt`: capture, classification, clarification, retrieval and state.
- `ai/`: intent rules, bounded ML Kit OCR, card extraction, time parsing and Gemini.
- `data/`: encrypted SQLite repository and domain models.
- `security/`: Keystore and encrypted attachment vault.
- `sharing/`: memory-only card PNGs and temporary read-only content grants.
- `reminders/`: AlarmManager, notifications and reboot recovery.
- `cloud/`: verified phone login, encrypted cloud transfer, recovery and WorkManager scheduling.
- `backend/`: owner-only Storage rules and emulator access tests.
- `ui/`: Compose screens and deterministic card PNG rendering.

Implementation references: [Android biometrics](https://developer.android.com/identity/sign-in/biometric-auth), [Android alarms](https://developer.android.com/develop/background-work/services/alarms), [Gemini models](https://ai.google.dev/gemini-api/docs/models), [Gemini image understanding](https://ai.google.dev/gemini-api/docs/image-understanding).
