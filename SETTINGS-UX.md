# Settings refresh

Purpose: make everyday AI and reminder controls easy to understand on a personal Android phone.

The latest owner feedback replaces the black-and-white theme with soft charcoal: `#101214` canvas, `#1A1D21` panels, `#E7E4DE` primary text and `#999FA6` supporting text. Warm `#C8B38A` accents and subtle `#30353B` borders give controls structure without harsh white outlines. Native sans serif, 25sp page title, 16sp row titles and 14sp supporting text. Full-screen navigation and focused dialogs keep the existing clean settings flow.

```
Back   Settings

Gemini AI                          switch
On / On-device mode
Short explanation

API key                       Saved >
AI model                  3.8 Flash >
[ Check connection ]
Connection result

Reminders
Notifications                    On >
Precise reminders           Allowed >

Privacy & security                  >
Lock Omni now
```

Review against brief: avoid making every control a separate decorative card. Only the AI switch gets a prominent surface. Do not show raw model IDs, empty replacement-key fields, setup documentation, or destructive actions in the main view. Key management, model selection and privacy explanations each have a focused dialog. Model discovery and manual IDs remain available inside model selection. Settings save as used, without a global Save button.

Connection status must distinguish a saved key from a successful API response. Testing sends only the synthetic cable example. Changing the key or model invalidates the old result; browsing models cannot overwrite it. Key replacement preserves the old key until Save succeeds; removing it turns cloud AI off. Permission state refreshes after returning from Android settings. MainActivity owns notification permission requests and notification/exact-alarm settings launches, tracking their results with the unlocked session so a normal Back return does not prompt for authentication again. Screen-off and process recreation still invalidate the session.

## Verified outcome

Version 0.1.6 passed 60 JVM tests, all 11 targeted physical-phone UI checks, and ten signed-release session checks on Android 14. Native notification permission grant, denial and cancellation, notification-settings Back, and exact-alarm settings grant/Back all return without another authentication prompt. Permission status refreshes. Ordinary Home/reopen and screen lock still require authentication; photo-picker cancellation remains unlocked. The signed-release Settings screenshot after these returns was reviewed.

Version 0.1.5 remains the earlier broad baseline: 59 JVM tests and all 63 main physical-phone checks, including Settings flows and 1.5× text. Settings, model dialog, wallet, Chat photo and Library thumbnail screenshots were reviewed in the softer theme. Connection failures preserve the useful provider reason and redact any echoed key. The raw REST enum mismatch causing HTTP 400 was reproduced and corrected. The installed personal app then reported “Connection verified”: Gemini 3.8 Flash sorted the synthetic sample correctly in 1.8 seconds using the existing saved key. This live probe was not repeated for the permission-navigation change.


## Backup and first-open changes — 2026-09-10

Backup & restore uses a single-column status-first layout: phone/cloud completion dates and size, one Back up button, Google account, daily schedule, cellular toggle and encryption status. Restore is secondary; manual file selection is in the overflow menu. No sign-out action or Android scheduling paragraph appears here. Log out lives in the drawer and confirms that scheduled backups pause while data remains.

First-open setup requests microphone, notifications and precise alarms. Denial or Not now permits Google sign-in. Camera is requested only by camera/scanner features. Missing permissions remain actionable in Settings. Signed-out welcome never opens biometric authentication; auth changes remain observed while a system authentication screen covers MainActivity.

Chat reserves the floating dock's height plus system/keyboard insets. Library and normal Cards content retain the floating overlay, allowing content to scroll behind it. Card/ID scanner hides the dock so instructions and shutter remain unobstructed.
