# Belfry for Android: notes for agents

Native Android client (Kotlin, Jetpack Compose, Material 3) for a community's belfry
(self-hosted community server). The protocol contract is [SPEC.md](SPEC.md) + `vectors/`
(copied from opencollective/belfry-ios at commit ca5fea3; additions made here are in SPEC.md's
"Additions since the iOS spec" section). The web chat (`village-app`, branch `belfry-chat`; also
`commonshub/collective-belfry` branch `chat-source`) is the reference implementation: read it,
don't guess. What is done and what is not: [PARITY.md](PARITY.md). What the server must add:
[SERVER-NEEDS.md](SERVER-NEEDS.md).

## Building (CI only)

The dev box has no Android SDK and little memory: **never run Gradle there**. Push and let GitHub
Actions build; poll `gh run view <id> --json status,conclusion,jobs` every 4–5 minutes (never
`gh run watch`), then `gh run view <id> --log-failed` or `gh run download <id> -n screenshots`. Two jobs:

- `core`: `./gradlew :core:test` (JVM unit tests, the shared vectors).
- `app`: `:app:assembleDebug`, `:app:lintDebug`, `:app:recordRoborazziDebug` (Robolectric +
  Roborazzi render the screens in demo mode → artifact `screenshots`; the debug APK is an
  artifact too).

Toolchain (pinned, known to work together): Gradle 8.11.1 (wrapper jar checked against
gradle.org's sha256), AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, compileSdk/targetSdk 35,
minSdk 26, JDK 17.

## Layout

- `core/` — pure Kotlin (JVM), package `sh.belfry.core`. No Android imports. JSON is
  kotlinx.serialization **without the compiler plugin**: `JsonElement` trees and the small
  readers in `nostr/Event.kt` (`str()`, `obj()`, `s("key")`…).
  - `crypto/`: hex, sha256, base64, bech32; `Keys` (secp256k1 through fr.acinq.secp256k1-kmp:
    BIP-340, ECDH), NIP-44 v2, NIP-04 (read only).
  - `nostr/`: `Event`, `EventTemplate`, `Signer` (interface), `LocalSigner`, signature checks,
    NIP-98 headers.
  - `model/`: the derived state. One immutable snapshot class, **`World`** (the event log +
    queued outbox items + who I am + community settings), with every view of the web's
    `src/core/model.ts` as a lazily computed member (channels, messages, reactions, threads,
    feed, profiles, people, pods, events, proposals…). `Actions`: the events each action queues
    (pure functions returning `EventTemplate`s). `Text`: hashtags, mentions.
  - `markdown/`, `mail/` (MIME, mail threads), `outbox/` (items, error classes, one flush pass),
    `store/` (`Store` interface + `MemoryStore`), `dm/` (gift wraps, conversations),
    `net/` (OkHttp: `Relay` with NIP-42, `BunkerSigner` NIP-46, discovery, sign-in, the belfry's
    HTTP API), `engine/` (`Session`: relays + subscriptions + outbox + DMs behind one object that
    exposes `StateFlow`s and suspend actions; the app talks only to this).
- `app/` — the Compose app, package `sh.belfry.app`. `data/` (SQLite `Store`, Keystore-encrypted
  secrets), `ui/` (theme, kit, screens). The seeded demo community is `core/.../engine/Demo.kt`.
- `screenshots/` (git-ignored): the last CI screenshots, downloaded with `gh run download`.
- `app/src/test`: Robolectric screenshot tests (demo mode). PNGs land in
  `app/build/outputs/roborazzi/`.

## App structure and seams

`app/src/main/kotlin/sh/belfry/app/`:

- `BelfryApp` (one `Onboarding` + `AppState`, lifecycle and connectivity wiring), `AppState`
  (phase: loading / signed out / joining a guest link / signed in; fixed-message errors),
  `MainActivity` (the `sh.belfry.app://connect` callback and `https://chat.<domain>/join/<token>`
  links; reads only `code` / `state` / `error` and the link's domain + token).
- `ui/AppRoot.kt`: which phase shows; signed in → `SessionScope` + `AppNav` (+ the "join with
  your account" sheet, + the no-access screen when the relay refuses).
- `ui/nav/`: `Routes.kt` (EVERY route: pattern constants + builder functions), `Locals.kt`
  (`LocalSession`, `LocalWorld`, `LocalDms`, `LocalStatus`, `LocalNav`, `LocalSignOut`;
  `SessionScope` provides them), `AppNav.kt` (the graph; one registration function per owner),
  `NotBuiltYet` (placeholder screen).
- A screen is a `@Composable` that reads `LocalWorld.current` (derived state),
  `LocalSession.current` (suspend actions, `session.now()`), and moves with `LocalNav.current`:
  `nav.go(Routes.profile(pk))`, `nav.back()` (the only thing a back arrow does),
  `nav.openChannel(id, messageId?)` (shows a channel in Home; never pushes).
- Owners (a package and its routes file; nobody edits another owner's files):
  - shell: `ui/shell/` (Home = drawer + channel, `ShellRoutes.kt`), `ui/chat/` (channel screen,
    feed rows, composer, inline thread, thread screen, message menu and delete confirmation,
    photo viewer), `ui/signin/`.
  - info: `ui/info/InfoRoutes.kt` → search, channel info, invite, topics, files, drop link, follow.
  - people: `ui/people/PeopleRoutes.kt` → profile, me, sessions, members, messages,
    conversation, new conversation; and `ui/people/SaveAccountBar.kt` (shown above the composer).
  - cards: `ui/cards/CardsRoutes.kt` → events, event, pod, proposal, the create screens
    (`MeetingScreens.kt`: the meeting form and a meeting's people on its event page);
    `ui/cards/SpecialCards.kt` (`SpecialCard(groupId, message)`: what a card / e-mail post shows
    in the feed) and `ui/cards/ChannelBars.kt` (`ChannelBars(groupId)`: bars under a channel's
    top bar: only the Join / Ask to join bar of a pod I am not in; the summary line and its pull-down are `ui/chat/ChannelLineView.kt`).
- Shared pieces outside the kit gallery: `ui/kit/MarkdownText.kt` (`MarkdownText`,
  `LocalNetworkImages`, `isSafeLink`), `ui/chat/ChatFormat.kt` (`ChatFormat.time/day/short`,
  `World.face(pubkey)`, `Group.glyph()`), `ui/chat/MessageRow.kt` + `ChatState` +
  `MessageSheets` (reuse them to show messages anywhere), `ui/chat/Composer.kt`,
  `ui/signin/SignInScreen.kt` (`openInBrowser`, `SignedOutPage`, `Logo`).
- Tests: `app/src/test/.../DemoShots.kt` gives each test a seeded demo `session` (fixed clock,
  UTC, no network) and `demoShot(name) { Screen() }` / `demoScreenShot` (sheets, dialogs);
  `InDemo { }` for interaction tests; `myMessage(text)` posts as me; `started()` runs the outbox.

## Decisions

- Package / applicationId `sh.belfry.app`; sign-in redirect `sh.belfry.app://connect` (already
  approved server-side for the iPhone app); client tag `["client","belfry-android"]`.
- Sign-in is native by default (SPEC 19.20): `name@domain`, a 6-digit code by e-mail, `POST
  <api>/session` + `/session/verify` signed by this device's session key (`core/net/NativeSignIn`,
  `Onboarding.requestCode / verifyCode`, vault key `native-signin`). The browser flow (SPEC 2) is
  the second row and what an older belfry gets. Never call the live endpoints with a real
  member's name from a test or by hand: each call mails that person a code.
- Guest links `https://chat.<domain>/join/<token>` open in the app (App Links intent filter;
  `/.well-known/assetlinks.json` is served by chat.belfry.sh and chat.enspiral.eu for the release
  certificate: only the signed release APK verifies, not the debug APK; SERVER-NEEDS.md 1).
- Plain SQLite (`SQLiteOpenHelper`) rather than Room: three tables, no annotation processing.
- The session key (NIP-46 client key) and bunker URL are encrypted with an Android Keystore AES
  key; the member's identity key never leaves the belfry's signer.
- Relay events are signature-checked before storage; seals always.
- Never render text from a URL; server errors are mapped to fixed messages by their `error` code.
- Forms keep a draft on the device (`ui/kit/FormDraft.kt`: `rememberFormDraft(key) { restore }`,
  `draft.Keep(…)`, `draft.clear()` once sent; keys in `core/model/Drafts.kt` `FormDrafts`). A new
  create screen should do the same.
- A count badge is `CountBadge` (kit); never draw one inside an `IconButton` (it clips to a 40 dp
  circle) and never give one a fixed size (`BadgeShotsTest` renders them at font scale 2).
- A `BelfryTextField`'s test tag is on its frame, not on the editable node: tests type with
  `compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)))`.
- Channel info opens on a tab with `Routes.channelInfo(id, InfoTab.KEY_…)`. The Log's words are
  `core/model/ChannelLog.kt` (the web's `logItem`): a new kind of event gets its line there.
- Meetings are Belfry events with three extra kinds of tags (`core/model/Meetings.kt`, SPEC
  19.23); `EventActions.edit` keeps them. Audio attachments play in `ui/chat/AudioPlayer.kt`;
  recording is `core/audio/` (rules, ADTS, the .m4a writer) + `app/.../rec/` (`Recorder`, the thin
  `RecordingService`) + `ui/chat/RecorderSheet.kt` (SPEC 19.25). Record ADTS, never MP4; the
  .m4a is made at posting. A change to `M4a.kt` must keep `tools/adts2m4a.py` and the fixture in
  step (run the script, check with ffprobe, replace `sample.m4a`).
- Several accounts (SPEC 19.24): `core/engine/Accounts.kt` (`AccountList`, slots, `StoreProvider`),
  `Onboarding.accounts / resume(id) / asAccount`, `AppState.switchTo / addAccount / adding`,
  the switcher `ui/shell/AccountsSheet.kt`, `LocalAccounts`. A session knows its `accountId` and
  `slot`; anything kept outside its `Store` must be named with `Slots.key(name, slot)`. One
  session is live at a time. Never rename the empty slot's names (`session`, `belfry.db`,
  `push-key`): they are what a single-account install has.
- Guest mode (SPEC 19.26, web b2e968b): a guest link with no account here goes straight in
  (`Onboarding.openInvite`, `AppState.openInvite`; the loading screen is `AppState.opening`, drawn
  over everything by `AppRoot` and cleared by the joined session's SignedIn when the groups relay
  answered or after 10 s). `GuestInfo.reading` (saved with the session) = read-only: every write
  passes `Session.guard()` (`send`, `enqueueSigned`, `uploadPicture`, the API writes,
  `apiCall(write = true)`); a new write path must call it too. A refused write sets
  `Session.joinAsked`, which `GuestJoinHost` (`ui/people/GuestJoin.kt`) shows as the Join sheet;
  `GuestBar` stands where the composer would be. Others' reading guests are left out in `World`
  (`Group.reading`, `readingOnly`, `readingCount`; `core/model/GuestMode.kt` has the fixed texts).
  Never auto-send anything from a `LaunchedEffect` without a try: `guard()` throws.
- The invitation scanner is `app/.../scan/` (CameraX + ZXing, no Play services); it only hands
  over what `InviteScan.read` (= `InviteLink.parse`) takes.
- Push (SPEC 19.19, collective-belfry docs/push.md): FCM data-only messages decrypted in
  `app/.../push/` (`AndroidPush`, `BelfryMessagingService`), the device key in `Secrets`;
  `core/push/Push.kt` reads payloads. Firebase is optional: the google-services plugin is applied
  only when `app/google-services.json` exists (CI / release.yml write it from the
  `GOOGLE_SERVICES_JSON` secret when set); `BuildConfig.PUSH` says which. The permission is asked
  only from Me → Notifications or a channel's bell, never at launch.

- Read state (SPEC 19.27, vectors/readstate.json): the pure rules are `core/read/ReadState.kt`
  (a port of the web's `core/readcore.ts`; change both together), unread counts `core/read/Unread.kt`,
  the sync `core/engine/ReadSync.kt` (store keys `read-state`, `read-state-remote`; publishes only
  after the community relay's EOSE, 3 s debounce / 10 s gap, `Session.flushRead()` on background;
  guests, guest mode and the demo keep it local). `Session.readState`, `markChannelRead`,
  `markThreadRead`, `markMentionsSeen`; `markRead(conv)` goes through it. DM reactions never count
  or move a mark. Screens mark only while started (`ui/kit/Started.kt`).

- Self-update (#issues 2026-11-13): `core/update/AppUpdate.kt` reads GitHub's releases of this
  repository (only its `releases/download/` links, versions compared as numbers, drafts skipped,
  403/429 → wait 6 h); `app/.../update/Updater.kt` checks on every start in the foreground (at
  most hourly, Me → "Check for updates" turns it off), downloads the APK, checks the release's
  .sha256 (a broken download, not a security check: Android only installs an update signed with
  the same key) and commits it through `PackageInstaller` (`UpdateReceiver` opens the system's
  confirmation). Release builds only (`Updater.enabled`). `UpdateBar` stands at the top of Home,
  `UpdateRows` in Me. Only the version number is shown, never a release's text.
  Update goes through `Updater.tap` (`AppUpdate.installStep`): install when Android lets Belfry
  install packages; else `UpdateSheetHost` (in `AppRoot`) says why before it opens Android's
  "Install unknown apps" setting, and `Updater.resume` (every start) goes on once the person is
  back with it on (the release waits in the preferences: the app may be killed meanwhile); still
  off, no such setting, or the installer failed → belfry.sh/android in the browser (`AppUpdate.WEB`).

- Parity with the iPhone app (2026-11-17, branch `parity`; SPEC 19.28–19.35, PARITY.md's first
  table). Core: `model/Archive.kt` (deleted / archived, `GroupLock`, presence questions),
  `Charter.kt` (**`PlaceNames`: every display name of the town in one table** — only
  general → "Townsquare" now; never write them back), `Categories.kt` (SPEC 19.34),
  `MailInbox.kt` + `World.mailThreads` (SPEC 19.33; `FeedItem.MailThread`), `GroupRoles.kt`
  (19.29), `Membership.kt` (vouches), `Places.kt` (Home / Calendar / Groups), `net/Members.kt`
  (roles, membership, requests, directory, member links `JoinsAs`, guest apply, public charter /
  events, group delete), `engine/Members.kt` (`Session.members`: those calls with stored copies,
  the charter's actions, categories, archiving, group roles, check-in; demo answers). Admins of a
  group = latest 39001 ∪ creator ∪ admins by role (`Group.relayAdmins` = who may moderate on the
  relay). `Session` takes `placeNames` (the app sets `Onboarding.placeNames = true`; tests keep ids).
- App structure: `ui/shell/HomeScreen.kt` = the five places (`ShellState.place`, `LocalShell`),
  `ChannelDrawer.kt` = the Town drawer (`DrawerActions`; `wide` = the tablet column), `PlaceCounts`;
  `ui/places/` (Home, Calendar, Groups, Create sheet, My to-dos, Archived); `ui/chat/ChannelMenu.kt`
  (the pull-down as rows) + `ChannelHeader.kt` (subtitle, archived bar, deleted screen);
  `ui/info/ChannelPlaceScreen.kt` (`Routes.channelPlace(id, ChannelPlaceKey.…)`: the screens the
  pull-down pushes; `Routes.channelInfo` maps onto them); `ui/cards/MailThreadScreen.kt`,
  `EventParts.kt` (event levels, viewer, ticket, tabs, check-in); `ui/people/ProfileRoles.kt`
  (Role, In <group>, Add an agent); `ui/belfry/` (Belfry settings, charter, apply, welcome).
  `openChannel` always switches to Town (Android's choice; iOS pushes a chat outside Town);
  `Routes.chat(id)` pushes a channel with a back arrow (archived groups, links).

- Round 5 (2026-11-17, SPEC 19.36 / 19.37): buttons on messages are `core/model/Buttons.kt` +
  `World.buttons / buttonState` + `Session.pressButton` (published at once, never queued) +
  `ui/chat/MessageButtons.kt` (in `MessageRow`). Links: `core/model/Links.kt` (finder, in-app
  chat links, Markdown masking); `ui/kit/LinkText.kt` (`LinkText` for plain text,
  `rememberOpenLink` — every link tap goes through it). Outbox items with `Target.API` are queued
  HTTP calls (`path` + `body`, `Session.queueApi`); `World.failedSettings` drives the header's
  error mark (`SettingsFailedMark`). The pull-down's rows hide by `ChannelCounts.showsRow`.

- Round 6 (2026-11-18, SPEC 19.41 / 19.42): `core/model/Round6.kt` (visibility with periods,
  closed channels, `NotifyChoice`, proposal options / turnout / versions); `ui/info/NotifySheet.kt`
  (the pull-down's one row that opens a sheet); `ChannelSettingsForm` = Who can read it;
  `JoinBar` / `ApplySheet` for closed channels too (`needsJoinBar`, `readOnlyClosed`);
  `MailThreadScreen` with Reply / Comment (`Composer(tint =)`, `MessageRow(tag =)`);
  `ui/cards/ProposalTop.kt` inside `ChannelScreen` for proposal chats (`proposalStart`).
  Screenshots `R6ShotsTest`. Demo: closed `Demo.TOOLS`.

- No link (2026-11-18): signed out = `ui/signin/NoLink.kt` `SignedOutFlow` (Find → the belfry's
  Welcome → Log in / Apply / invite code → Thank you); `ApplyForm` + `BelfryApi.applyToJoin` /
  `plainPost` (core `net/Members.kt`), `Onboarding.charterFor / applyTo`. Closed requests:
  `Session.members.decideGroupRequest` (`POST /group-requests/decide`; the demo writes 9000 / 9001).

- About Belfry (2026-11-18): the account switcher's last row → `ui/shell/AboutScreen.kt` (version,
  `UpdateRows`, belfry.sh, the belfry of Belfry: switch to an account there, else `ApplyFlow` for
  belfry.sh through `AccountsUi.discover / charterOf / applyTo`). The updater downloads a newer APK
  ahead on an unmetered connection (`Updater.prefetch`), so Update opens the installer at once. The
  drawer has no search field (search is in each channel's header).

- Connections (SPEC 19.50, 2026-11-21): `core/net/Connections.kt` owns the session's sockets (groups,
  main, the signer's `BunkerSigner.relay`); `Session.wake / background / setOnline / sendNow /
  diagnostics`. `Relay.publish` asks a quiet socket first and drops one that gave no `OK`
  (`probeMs` / `sendQuietMs` are test seams). The outbox holds back by lane (`Outbox.lane`), not
  by relay. `SendTrace` = `Session.sending` (the lines under unsent messages, `rememberSendLine`)
  and About Belfry → Diagnostics. A new socket for a session goes through `Connections.add`.

## UI rules (the owner's, not negotiable)

- Every button disables itself the moment it is tapped and shows progress until its work is done.
  Use the shared `BusyButton` / `rememberBusy` helpers; never a bare `Button(onClick = { launch… })`.
- Menus and sheets are Discord-style lists: grouped rows, a line icon on the left. Never stacked
  bordered buttons.
- Discord is the reference for layout and behaviour.
- No horizontal scrolling of a screen. A back arrow never pushes a screen.
- The Belfry look: near-black / white surfaces (`ui/theme`), dynamic colour off.

## Rules

- Push freely to `opencollective/belfry-android`. Ask before: GitHub secrets, store submissions,
  other repos, anything on the Belfry servers. Never write to the live communities (belfry.sh,
  enspiral.eu) from tests: use the demo mode.
- Never print or commit secrets. Commit messages end with the Co-Authored-By / Claude-Session
  lines.

## Known debt

- The owner runs it on an e-ink tablet since 2026-11-12, but nothing here was checked on a device by
  an agent: only JVM tests (core) and Robolectric renders (app). Written blind and waiting for his
  word: the camera scanner, switching accounts on a real belfry, the recorder (microphone, the
  foreground service, focus, the notification's buttons), push with several accounts.
- The toolchain is pinned to late-2024 versions (AGP 8.7.3, Kotlin 2.0.21, Compose BOM
  2024.12.01, target SDK 35) because they were known to work together without a local build.
  Upgrade in one step with CI before any store release (Play requires a recent target SDK).
- `World` is still made anew on each change (coalesced to one per 100 ms), but built on the
  snapshot before (SPEC 19.22): the per-channel views of untouched channels, profiles, calendar
  and proposals are taken over when only everyday events arrived; `tasks`, `pinned` and
  `webPage` read an index. Still whole-log work per snapshot: the indexes themselves (`byH`,
  `byKind`, the sort), `groups`, `deleted`, `edits`; `search` scans every message; `proposals`
  scans its channels when it is worked out. `core/.../model/PerfTest` times a 30 000-event log
  on every CI run (artifact `perf`; a runner's JVM, not a phone).
- The channel screen draws its rows from a snapshot held until `World.feedKey` changes
  (`rememberFeedWorld`): if a row ever shows something stale, the thing it reads is missing from
  the key (or from `World.isEveryday`). The thread screen and the other lists still redraw on
  every snapshot.
- The release APK is not minified. CI builds a `minified` variant (R8 + resource shrinking,
  `app/proguard-rules.pro`, debug-signed, artifact `belfry-minified-apk`) so the rules are
  checked on every push; nobody has run it on a phone. Turn minification on for `release` after
  that.
- The engine tests poll with time limits and could flake on a slow runner.
- App Links hosts are fixed in the manifest (`chat.belfry.sh`, `chat.enspiral.eu`, `chat.opensourcevillage.org`): a manifest
  placeholder cannot hold a list. Another community = another `<data>` line, or a per-community
  build.
- Day and time labels in the channel line are fixed English / 24 h, not the device locale.
- Push has never run end to end (no Firebase project yet; SERVER-NEEDS 3).
- `Secrets` (Keystore AES-GCM) cannot run under Robolectric; its test skips there.
- Gestures written blind: the photo viewer (pinch, double-tap, swipe down inside a pager) and the
  pull-down's handle. Robolectric drives a double-tap and a swipe; a real finger may differ.
- The audio player has never made a sound; `POST /meeting-guests` never met a belfry.
