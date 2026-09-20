# Omni encrypted backup formats

Filename convention: `.omnibak`; Android storage picker MIME: `application/octet-stream`.

Clock alarms created through the phone's Clock app are owned by that app and are not part of this backup. Alarm-related chat messages and their actions are backed up, but restoring history never launches or recreates a Clock alarm automatically.

## Current account format (0.1.18)

Header (52 bytes, big-endian): ASCII `OMNIACC!` (8), version 1 (4), SHA-256 of `projectId + "\n" + verified Firebase UID` (32), archive creation milliseconds (8). The header is an untrusted discovery hint until full AEAD verification. Its timestamp must equal the authenticated manifest timestamp. A changed owner tag cannot transfer an archive to another account because the header is AEAD associated data. Email and phone strings are not used to authorize access.

Encryption uses a random 32-byte account key and Tink `AesGcmHkdfStreaming` (HmacSha256, AES-256, 1 MiB segments, offset 0). Tink randomizes each stream's salt/nonces. The shared archive body, bounds and additive restore procedure below apply to both formats. Entire authenticated EOF is required before preview; no DB changes occur until Restore.

The immutable key is `keys/<uid>/backup-key-v1` in Firebase Storage. Rules permit only verified Google-owner reads and a single exactly-32-byte octet-stream creation; listing, replacement, metadata changes and deletion are denied. The backend controls this key, so recovery is backend-managed rather than zero-knowledge. Local cached key bytes use the existing Keystore vault encryption and are excluded from backups. A corrupt local cache can be recovered from the verified account. A missing remote key with existing remote archives fails closed instead of silently generating a new key.

No user recovery password is needed for this format. Recovery after reinstall requires Google account access, connectivity to fetch its key and a complete archive. A phone PIN is only the local app gate. Legacy password archives remain importable, but cannot be opened without their original password or safely matched to an email from their old headers.

## Legacy password format (0.1.14–0.1.17)

Header (48 bytes, big-endian integers): ASCII `OMNIBAK!` (8), version 1 (4), PBKDF2 work factor 600000 (4), random salt (32). No names, titles, counts, card details or credentials appear in the header. Reject an unknown version/work factor before deriving a key. Feed the whole header to Tink as associated data.

Derive 32 bytes using PBKDF2WithHmacSHA256 and the exact password, 600000 iterations. Password length 12–256 UTF-16 code units, not all whitespace; do not trim or normalize. Clear caller-owned CharArrays on completion/cancellation and PBEKeySpec immediately after derivation. Tink receives a copy of the key material; derived temporary bytes are cleared. Managed-runtime objects cannot provide a guarantee of physical RAM erasure.

Tink 1.23.0 `AesGcmHkdfStreaming` parameters: HmacSha256, derived AES key 32 bytes, ciphertext segments 1048576 bytes, first segment offset 0. Tink creates per-stream salt/nonces and binds each segment's position and final flag. No custom nonce or AEAD framing implementation.

Authenticated plaintext: manifest length (4), UTF-8 JSON manifest, file count (4), exact original bytes for each distinct attachment in manifest order, footer 0x4F4D4E49 (4), EOF. Stream through authenticated EOF before a restore preview can be returned. Reject missing/trailing/reordered/tampered segments, footer mismatch, unsafe attachment IDs, wrong file count, bad references and unsupported schema. No ZIP, filesystem paths, compression or executable content extraction. Maximum JSON nesting depth 32; manifest <=16 MiB, records <=50000, attachment references <=10000, originals <=10 GiB total, individual files <=100 MiB.

The manifest contains schema 1, creation timestamp and portable records {id,kind,created,json}. Only memory/message/reminder/settings kinds are accepted. Settings contain only model, never key/cloud consent. Export snapshots records in a DB transaction; the caller serializes attachment-changing app work for the export duration. Reminder receivers can continue afterward without invalidating the original snapshot.

Restore stages attachment ciphertext under this installation's Keystore key, retaining an unoccupied original ID or assigning a new UUID when needed. No plaintext archive exists on disk. Validated metadata is held in memory until confirmation. Restore reads current record IDs within a transaction and inserts only absent records. It preserves current settings and existing memory/reminder edits. Remap restored memory/chat file references when IDs change. An existing memory's missing reminder is not resurrected. A fresh vault receives the backed-up model with cloud disabled and no key.

Before file promotion, fsync an AtomicFile journal of opaque target IDs. Promote staged ciphertext into the vault and fsync that directory before committing the database. On failure or process recovery, delete promoted files only if no committed memory references them. Delete remaining stage directories. Interrupted exports may leave an invalid encrypted file in the external provider; the provider owns remote durability. The archive codec itself performs no network calls. Manual file backup uses the selected Android storage provider, which may sync ciphertext. Optional cloud backup uploads only the completed encrypted archive to Firebase Storage.

References:
- https://developers.google.com/tink/streaming-aead
- https://raw.githubusercontent.com/tink-crypto/tink-java/main/src/main/java/com/google/crypto/tink/subtle/AesGcmHkdfStreaming.java
- https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#pbkdf2
- https://developer.android.com/privacy-and-security/cryptography
- https://developer.android.com/training/data-storage/shared/documents-files

## Daily phone/cloud transport and discovery (0.1.19)

`CloudLocal` stores UID, account-key readiness and schedule under local vault encryption in `noBackupFilesDir`. The Firebase session, recovery key and schedule are excluded from portable records. The worker can access its encrypted local account key without a new interactive device prompt, consistent with the existing background reminder trust model.

WorkManager schedules the next chosen local time and queues the following day after success or exhausted retries. Scheduled runs require non-low battery and storage; manual runs do not wait for the battery constraint. Network constraints do not block the encrypted phone copy. Cloud upload requires connectivity and respects the cellular setting (off by default). Offline/disallowed-network runs report a retained phone copy and retry cloud upload. Each attempt has a nine-minute budget and exponential backoff, up to five attempts. Large archives or slow connections may exceed that budget. Reboot, clock/time-zone changes and startup reconcile the schedule. Force-stop delays work until app reopening. Cancelling one transfer retains the next daily run; switching Daily backup off stops the schedule.

A transfer mutex serializes temporary encrypted archives and restores; a vault mutex protects attachment snapshots. Uploads use immutable UUID names under the verified Google owner's UID. Upload size is checked before recording completion; pruning retains three versions only after a successful upload. Empty installations never create or upload an empty snapshot over an existing backup history; the manual action reports that there is nothing to back up. Completed ciphertext is also published to MediaStore Downloads/Omni using IS_PENDING on Android 10+, or an app-specific external backup folder on Android 8–9. Failed local copies are removed. Local retention keeps three automatic archives per owner without deleting user-selected exports. Cloud failure preserves the new local file. Encrypted local preferences retain both completion timestamps, size and the last transfer outcome. WorkManager observation exposes live manual/daily activity to the UI.

First sign-in checks cloud version headers, app-owned automatic phone files and already granted phone files, sorts their archive dates, then fully verifies candidates newest-first. Corrupt/unreadable candidates are reported and older valid candidates can be offered. No restoration occurs before explicit Restore. A complete cloud check with no matching accessible files and no unreadable candidates finishes setup automatically. Errors never count as a confirmed absence of backups. Skip discards staged work and finishes onboarding; unavailable networking does not prevent skipping once signed in. Restore commits additively, re-encrypts originals with the new installation key and schedules undelivered reminders. Clock-owned alarms are not recreated.

Known local file URIs are encrypted in `mobile-backup-sources`, with at most ten user-selected files. Automatic discovery also queries up to 100 accessible automatic archives in Downloads/Omni (app-specific files on Android 8–9). It never requests all-files access or silently scans arbitrary documents. Android revokes grants and app ownership on uninstall/data clearing: surviving Downloads files may need **Backup options > Choose backup file** before the same account/date/integrity checks can apply. Locking or cancellation discards staged restores. Publication failures remove pending local copies; abrupt process termination can leave an unpublished pending MediaStore row rather than an offered backup.

Backend activation and isolated test commands: [backend/README.md](backend/README.md).

## Personal classification preferences (0.1.19)

Owner-confirmed labels are stored as `categoryConfirmedAt` inside the encrypted memory record. The existing portable record format carries this optional field without a schema change. Restored records and explicit legacy category-change messages rebuild the same bounded preference examples. The backup contains neither Gemini training weights nor a new external learning service. Existing additive-restore and account ownership rules are unchanged.
