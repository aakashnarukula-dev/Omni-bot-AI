# Clock alarm handoff — 0.1.16

Alarm requests remain in Chat, with the full interpreted date/time and a small alarm-icon Clock action. They do not receive a reminder receipt or reminder date picker. Valid new requests open the system Clock; the reply explicitly asks the owner to check that the alarm is enabled there. After handoff, the action opens the alarm list rather than creating another alarm. Time clarification uses the existing composer. No extra destination, alarm settings page or theme change.

# Composer and navigation spacing — 0.1.15

The composer uses the same full-round shape for its surface and outline; its radius scales with the field's height. Explicit 48dp attachment and mic/Send circles use 8dp side insets, matching the 8dp top and bottom clearance in the idle field. Controls remain vertically centered as the draft grows. The floating dock wraps three 48dp tabs with 18dp gaps and an 8dp outer inset on every side, for a compact 196 × 64dp pill.

# Backup and inline voice — 0.1.14

Backup & restore is one Settings row and one dedicated page using the existing charcoal palette and type scale. Create and Restore are clear actions. Password entry, progress, verified counts and result use the same scrollable layout. Restore copy explains that existing edits win. Secret fields use in-memory state only and clear on submission/exit.

Chat's trailing action is a microphone while the draft is empty and the existing gold Send arrow once text arrives. A quiet Listening/Stop row appears only during dictation. Speech writes into the same editable draft; manual editing invalidates late results. The floating navigation contains only Chat, Library and Cards. No separate voice destination.

# Omni bot AI design

A personal Android memory app with a quiet, CRED-inspired dark finish. The owner's latest feedback replaces the earlier pure-black/pure-white treatment: softer contrast, visible charcoal surfaces, restrained color and readable text hierarchy.

## Scanner, navigation and headers (0.1.12)

The Cards scanner uses a full camera preview, dark surrounding mask, rounded card-ratio guide, type pills below the guide and circular shutter. An empty wallet opens this scanner directly. A compact 64dp floating dock contains Voice, Chat, Library and Cards; selected destination uses an off-white circle. Chat keeps its brand header. Library and wallet each have a single title row with Settings; wallet also has Search. Reminders remain under the Library category.

Scanner controls reserve navigation and keyboard insets. Short viewports scroll without shrinking tap targets. The scanner closes its camera when navigating away. Review retains field corrections, both-side scans, manual capture and local OCR.

## Payment-card chip (0.1.13)

A rounded brushed-gold contact module replaces the flat inset block. Six side contacts and two center contacts are separated by dark engraved channels with fine edge highlights. A restrained diagonal metal gradient and very light machining lines add material depth. Live cards cache paths and paints; PNG export uses the same normalized artwork. The chip remains decorative and carries no personal information.

## Tokens

- Ink `#101214`: main canvas and system bars.
- Panel `#1A1D21`, Raised `#25292E`: receipts, composer, dialogs and owner messages.
- Paper `#E7E4DE`: headings, primary labels and stored card details.
- Body `#C3C5C8`, Muted `#999FA6`: chat replies, supporting labels and inactive navigation.
- Outline `#30353B`: subtle boundaries rather than white strokes.
- Accent `#C8B38A`, Mint `#91B9AE`: selected controls, reminder actions and success indicators.

Native Android sans serif stays fast and supports text scaling. Space, text weight and surface depth establish hierarchy. Secondary copy uses muted text instead of competing with headings. Category colors are desaturated gold, blue, lavender and teal.

The launcher uses the same rounded gold infinity mark and Raised charcoal background as the in-app OmniMark. Its vector path comes from the same AndroidX Rounded.AllInclusive icon. Adaptive foreground/background layers preserve the mark within Android's safe area; launchers choose the outer mask, and Android 13+ can use its monochrome layer when the owner enables themed icons. See [Android's adaptive icon guidance](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).

## Layout

Left-aligned assistant replies, right-aligned owner messages and one composer. Four destinations: Chat, Library, Reminders, Cards. Settings remains a focused full-screen page with key/model controls in separate dialogs.

Photo messages show the original image fitted without cropping. Multiple photos form a horizontal gallery. Captions sit below photos; PDFs and APKs use filename rows. Saved-item receipts show a 56dp photo thumbnail on the left, with title and category to the right. Thumbnail crops do not alter the stored original. Tapping either preview or receipt opens saved details.

The composer centers its attachment and send controls vertically, including multiline drafts. Both occupy matching circular 48dp surfaces with full-size touch targets. The attachment menu uses four icon-and-label rows: Camera, Photos, Documents, Cards.

The card scanner keeps the same Ink, Raised, Paper, Muted and Accent palette and native type. One gold-outlined camera frame is the focal point. Front/back instructions, detected-number status, optional light and a manual capture button sit below the page title. Repeated matching reads capture automatically. Review replaces the camera with the existing virtual card, editable fields, retake/back controls and a fixed Save card button. Scanner copy describes actions and results without exposing implementation details.

Wallet cards carry the richest color: restrained gradients sampled from physical photos, with deterministic graphite, blue, bronze and teal fallbacks. The same palette is used for PNG export. Finishes are decorative, not copies of issuer artwork. Full stored fields remain readable after device authentication; no image model generates financial details.

## Review against the brief

No pure-white control outlines or bright white logo tile. Supporting text is quieter; selected controls retain a small warm accent. Original photographs keep their colors. Fixed preview bounds prevent loading jumps. No ornamental charts, fake balances or unrelated dashboard content.

Reference: [CRED's official product presentation](https://cred.club/). Inspiration is limited to dark materials, hierarchy and restrained metallic color; Omni keeps its own content and navigation.


## Wallet and voice update

The wallet is the focal interaction: up to five cards overlap in the compact view, with the newest in front. Pull down or use Expand to open the complete scrollable list. Search and Cards/IDs filters remain above the stack. Identity cards show a decrypted, memory-only preview of their original front plus editable, full stored fields in details. Rounded surfaces and gold selections reuse the existing tokens; no theme reset.

Scanner type chips add Credit, Debit, PAN, Aadhaar, DL and Other ID. Each type gets its own reader and review fields. Moving between types replaces the camera analysis session; recognition remains tied to actual text rather than an animated scanning line.

Voice uses the existing native typography, a microphone control in Chat, amplitude bars driven by recognizer callbacks, an editable transcript, and a review list. Priority and topic choices sit beside reminder items. Explicit Save remains anchored below the list. Ordinary chat remains the landing destination.

## Navigation and deletion

Each main tab keeps its saved UI state. The composer belongs to Chat content; the scaffold bottom bar contains only navigation, keeping its height stable across tab changes. Chat opens at the newest message, restores its existing offset on tab return, and scrolls only for a newly appended message. Library search, filters and scroll offset survive tab changes.

Reminder deletion uses the existing dark dialog style. A bounded, scrollable list shows titles and schedule/status, with select-all, clear-selection and Keep reminders controls. The confirmation button includes the selected count and remains disabled when nothing is selected. Deletion effects are stated beside the list.

## Direct wallet manipulation (0.1.11)

Use the existing Ink #101214, Panel #1A1D21, Raised #25292E, Paper #E7E4DE, Muted #999FA6 and Accent #C8B38A. Photo-derived gradients remain the card material. Native sans-serif serves labels; compact monospace numbers follow the reference. Wallet cards use a 1.586 aspect ratio, an 18dp corner, a chip on the left, an uppercase type pill and issuer on the right, a labeled 15sp monospace number, and a concise holder/expiry/CVV/network row. Micro labels use monospace with restrained tracking. Full details remain available separately.

The stack is the focal point. Five recent cards form thin 18%-height peeks, each older layer 1.5% smaller. Drag progress controls position and scale directly; velocity above 600dp/s overrides the halfway threshold on release, then a spring settles. Positions preserve layer order through the fan. Older entries become lazy list items above the recent stack; card bodies scroll when expanded, and the persistent stack handle or any card's top edge drags to collapse. This preserves the reference's interaction without making older cards unreachable.

Search is a compact header action; filters remain. Tap a face to toggle a light Share pill at bottom-right and a quiet Details pill at bottom-left. Only one card shows actions. A raised translucent Add Card pill floats above bottom navigation; list padding keeps the last card reachable. Share uses Android's chooser only after an explicit tap. ID originals remain encrypted; sharing renders a temporary stream, not a permanent plaintext image file.

The design follows the user's specified wallet reference. No theme reset, balances, issuer artwork generation or unrelated navigation redesign.

## Clear details and gesture navigation (0.1.19)

Plan: retain Ink #101214, Panel #1A1D21, Paper #E7E4DE, Muted #999FA6 and Accent #C8B38A. Use the existing native sans-serif: a 25sp editable title, 14sp control text and quiet 11sp supporting labels. Category is a compact menu above the title. A single raised schedule group shows Date & time and Repeat daily; priority is a compact menu. Remove Topic from saved details. Original reminder wording stays available under a disclosure instead of competing with the corrected title. Completion and deletion sit in the bottom action bar, outside the scrolling body. Card/ID field editing and original-file export remain available.

Date changes begin from the saved schedule, then explicitly advance from date to time before saving. Cancelling either dialog preserves the current schedule. Repeating state comes from the saved reminder. Expired schedules must be moved into the future before enabling repetition.

Library receipts follow the finger horizontally and reveal labeled icons: rightward Delete, leftward Done. Short or cancelled drags return without action; crossing the threshold and releasing completes or requests deletion confirmation. Done stays visible as saved state. TalkBack exposes equivalent actions. Horizontal gestures beginning on a receipt belong to the receipt for the entire gesture, even if direction reverses or the item is already done. Page swipes belong to open page areas. Horizontal filter and photo strips consume their remaining horizontal scroll, preventing accidental page changes at either end. Vertical lists and the wallet's vertical stack keep their own axis. All remains first; categories follow descending counts, with stable ties and category selection keyed by identity.

Review: one title, one category control, one schedule group, no Topic grid, no pencil icon, no bottom category grid. Existing warmth and compact navigation remain. Page motion uses native Compose Pager; camera binds only on the active settled wallet page. Touch targets are at least 48dp. Verify cancellation, rapid direction changes, diagonal/vertical drags, page restoration, full font scale, scheduling, and persistence using synthetic data.

Research: [Android bottom sheets](https://developer.android.com/develop/ui/compose/components/bottom-sheets), [date pickers](https://developer.android.com/develop/ui/compose/components/datepickers), [accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults), [gesture consumption](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures), [Pager](https://developer.android.com/develop/ui/compose/layouts/pager), and [nested scrolling](https://developer.android.com/develop/ui/compose/touch-input/scroll/nested-scroll-modifiers).

### Visual review

Reviewed emulator captures of normal and enlarged text details and both in-progress swipe directions. The category stays above the editable name, the date/time group carries the main visual weight, and completion remains below the scrolling content. Removed the square focus treatment by clipping the category control to its own rounded touch area. Swipe captures keep the page header and dock stationary while the receipt moves. Automated native picker checks verify the actual saved date/time, cancellation, repeat changes and past-time rejection.
