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
Actions build (`gh run watch <id> --exit-status`, `gh run view <id> --log-failed`,
`gh run download <id> -n screenshots`). Two jobs:

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
  - cards: `ui/cards/CardsRoutes.kt` → events, event, pod, proposal, the four create screens;
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
- Guest links `https://chat.<domain>/join/<token>` open in the app (App Links intent filter;
  `/.well-known/assetlinks.json` is served by chat.belfry.sh and chat.enspiral.eu for the release
  certificate: only the signed release APK verifies, not the debug APK; SERVER-NEEDS.md 1).
- Plain SQLite (`SQLiteOpenHelper`) rather than Room: three tables, no annotation processing.
- The session key (NIP-46 client key) and bunker URL are encrypted with an Android Keystore AES
  key; the member's identity key never leaves the belfry's signer.
- Relay events are signature-checked before storage; seals always.
- Never render text from a URL; server errors are mapped to fixed messages by their `error` code.
- Push (SPEC 19.19, collective-belfry docs/push.md): FCM data-only messages decrypted in
  `app/.../push/` (`AndroidPush`, `BelfryMessagingService`), the device key in `Secrets`;
  `core/push/Push.kt` reads payloads. Firebase is optional: the google-services plugin is applied
  only when `app/google-services.json` exists (CI / release.yml write it from the
  `GOOGLE_SERVICES_JSON` secret when set); `BuildConfig.PUSH` says which. The permission is asked
  only from Me → Notifications or a channel's bell, never at launch.

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

- Nothing has run on a device or emulator yet: only JVM tests (core) and Robolectric renders
  (app). First thing to do with a phone: sign in on a real belfry, send, go offline/online.
- The toolchain is pinned to late-2024 versions (AGP 8.7.3, Kotlin 2.0.21, Compose BOM
  2024.12.01, target SDK 35) because they were known to work together without a local build.
  Upgrade in one step with CI before any store release (Play requires a recent target SDK).
- `World` is rebuilt from the whole log on each change (coalesced to one per 100 ms) and some
  views (`tasks`, `pinned`, `search`, `proposals`) scan the full log. Fine for a community;
  index per group if logs pass tens of thousands of events.
- The engine tests poll with time limits and could flake on a slow runner.
- App Links hosts are fixed in the manifest (`chat.belfry.sh`, `chat.enspiral.eu`): a manifest
  placeholder cannot hold a list. Another community = another `<data>` line, or a per-community
  build.
- Day and time labels in the channel line are fixed English / 24 h, not the device locale.
- No minification (R8) rules. Push has never run end to end (no Firebase project yet; SERVER-NEEDS 3).
- `Secrets` (Keystore AES-GCM) cannot run under Robolectric; its test skips there.
