# Craffa Drive — Android

Native Android client for the Craffa notes API (`docs/craffa-notes-api-spec.md`, API 0.1).
Kotlin + Jetpack Compose, Material 3 Expressive. Online-only in v1: the server is the
source of truth, nothing is cached on the phone.

## Build

```bash
cd android
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # document model, editor logic, JSON, reminder planning
```

Needs JDK 17+ (CI uses 21) and the Android SDK (platform 37 is fetched automatically).
CI: `.github/workflows/android.yml` at the repo root runs the tests and uploads the debug APK.

Run the API tests against a real server too (they skip without these variables):

```bash
NOTES_TOKEN=testtoken uvicorn app.main:app --port 8471      # repo root
CRAFFA_TEST_URL=http://127.0.0.1:8471 CRAFFA_TEST_TOKEN=testtoken ./gradlew testDebugUnitTest
```

## Stack

| | |
|---|---|
| Toolchain | AGP 9.4 (built-in Kotlin), Kotlin 2.4.10, Gradle 9.6, compileSdk 37, minSdk 26 |
| UI | Compose 1.12, Material3 1.5.0-beta01 (Expressive: `MaterialExpressiveTheme`, expressive motion, FAB menu, wavy progress, `LoadingIndicator`, `MaterialShapes`) |
| Network | OkHttp 5 behind a tiny `HttpTransport` interface; JSON hand-mapped (no reflection) |
| Images | Coil 3 sharing the OkHttp client (auth header added for your server only) |
| Reminders | WorkManager (15-min sync) + AlarmManager exact alarms + local notifications |
| Secrets | API token encrypted with an Android Keystore AES-256-GCM key; backups off |

No DI framework: `AppContainer` in `CraffaApp.kt` wires everything.

## Layout

```
app/src/main/java/com/raiden/craffa/
  data/json      Json.kt — small JSON reader/writer
  data/model     Doc.kt (document model + server plaintext/title rules), Note.kt
  data/api       CraffaApi.kt (every endpoint, 409 → Conflict(serverCopy)), transports
  data/settings  SettingsStore.kt (server URL + encrypted token)
  editor         RichText (per-character marks), EditorDoc (lines ⇄ doc, Enter/Backspace), DocDiff
  reminders      ReminderPlan (pure), ReminderScheduler/Workers, alarm + boot receivers
  ui/            notes · editor · archive · history · reminders · trash · settings · components · theme
```

`data/` (except OkHttpTransport/SettingsStore), `editor/` and `ReminderPlan` are plain
Kotlin with no Android imports, which is why they're unit-testable on the JVM.

## How the editor works

The note is a list of *lines*: one per paragraph/heading/quote/code block, one per list
item, plus image and divider rows. List items of the same kind regroup into one list
block on save, so `bullet_list`/`ordered_list`/`check_list` round-trip exactly.

Each text line is a `BasicTextField` whose text lives in the ViewModel as `RichText`
(each character carries its own mark set). Formatting is painted by a
`VisualTransformation`, so the platform field can never drop it. Typing inherits the
marks of the previous character (links don't extend past their end). IME recomposition and
autocorrect keep marks positionally, and toggling a mark with no selection applies to what
you type next.

- **Enter** splits the line (continues lists and quotes; on an empty item it leaves the list).
- **Backspace at the start** first removes list/heading/quote formatting, then merges into the
  line above, or deletes an image/divider above. Soft keyboards don't report Backspace at
  position 0, so each field starts with a hidden hair-space sentinel. Deleting it is the signal.
  It's whitespace to Android, so auto-capitalize still works.
- Hardware keyboards: Ctrl+B / Ctrl+I / Ctrl+U.
- Unknown block types and unknown keys are preserved and written back unchanged.

## Saving and conflicts

Autosave runs 1.2 s after you stop typing, and again on leaving the note or backgrounding
the app. A PUT sends `title` (null = server derives it), `body` and `base_revision`. Metadata is
omitted, so the server keeps it. Favorite, folder and reminder go through PATCH with
`base_revision`, after any pending text is saved.

On a 409 you get the conflict dialog with three views (Changes diff / Server / Mine) and two
choices: **Keep mine** (re-PUT on the server's revision) or **Use server version**.

One case doesn't show the dialog: the server copy's body and title are exactly what the
editor started from, so only metadata changed elsewhere (e.g. snoozing a reminder from a
notification). The app then re-saves on the new revision. That isn't a merge, since no
content differs.

## Pin, archive, history

- **Pinned** notes come first (server order). When browsing, the list splits into
  *Pinned* / *Others*; search results stay relevance-ranked. Pin from a card's long-press
  menu or the pin icon in the editor.
- **Archive** (Keep-style): long-press → Archive, or the editor menu, with Undo. The
  Archive screen (⋮ → Archive) lists archived notes with Unarchive. Archived notes still
  get their reminders: the app queries archived and non-archived notes separately, because
  `has_reminder` alone skips archived ones.
- **Version history** (editor ⋮ → Version history): the app saves pending edits first,
  then lists the server's revisions. Open one to see it rendered or as a diff against now,
  and **Restore** it, which makes a new revision. The editor reloads when you come back.

## Reminders

The server only stores `reminder_at`, so delivery is done in the app:

- Every ~15 min, and on app start or any reminder change, the app fetches
  `has_reminder=true&reminder_before=now+2d`.
- It posts overdue reminders that haven't been shown yet (keyed by note id + time, so a
  moved reminder fires again).
- It sets an exact alarm for each upcoming one. Without the exact-alarm permission, Android
  may fire them a few minutes late; Settings has a button to grant it.

Notifications have **Done** (clears the reminder) and **Snooze 1 h**. The Reminders screen
lists everything grouped as Overdue / Today / Tomorrow / Later.

## Network

`res/xml/network_security_config.xml` allows plain HTTP only to `100.104.161.86`, `*.ts.net`
(MagicDNS) and local dev hosts (`10.0.2.2`, `localhost`). If the server moves to another
Tailscale IP, add it there. Everything else must be HTTPS.

## Not in v1

Offline cache/sync, tags UI (tags round-trip untouched), undo/redo, nested lists, tables,
text colour/size.
