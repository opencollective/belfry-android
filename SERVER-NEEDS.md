# What the Android app needs from the belfry (server side)

Changes in `collective-belfry` (`services/onboarding/belfry_onboarding/`, the proxy) that this
app depends on or would benefit from. This repo never touches the servers. Nothing here was
tested against a live belfry: the app has only run in demo mode in CI.

## 1. `assetlinks.json` so guest links open in the app — DONE (2026-11-10)

`https://chat.belfry.sh/.well-known/assetlinks.json` and `https://chat.enspiral.eu/.well-known/assetlinks.json`
serve the statement for `sh.belfry.app` with the release certificate
(SHA-256 `65:96:93:BB:CF:F6:1D:36:90:1B:21:A8:12:28:5E:7B:5B:05:0D:4A:41:3B:CD:C9:A0:78:6C:DA:11:95:EC:0D:5A`,
v0.1.0 onward); Google's Digital Asset Links statements API verifies it. Only the **signed
release APK** (GitHub releases, `release.yml`) verifies: the CI debug APK is signed with the
debug key, so on it `https://chat.<domain>/join/<token>` opens the web chat (or the "open with"
chooser) instead of the app. Another community's chat host needs the same file and a `<data>`
line in the manifest. (iOS needs the twin `apple-app-site-association`.)

## 2. A name for the Android session (cosmetic)

Sign-in uses the redirect `sh.belfry.app://connect`, which `apps.NATIVE_APPS` already approves
by default, so sign-in should work as is. But that entry's name is "Belfry for iPhone", so a
session opened from Android shows under that name in a member's session list. Either rename it
to something neutral ("Belfry app"), or pick the name from `client_id` (`belfry-android` /
`belfry-ios`), or approve a second redirect for Android.

## 3. Push notifications (later milestone; not built in the app)

Nothing wakes the app when a message arrives; it syncs when opened. Proposal, the same shape as
the iOS one: `POST <api base>/me/push` (NIP-98) `{"platform":"fcm","token":"…",
"app":"sh.belfry.app","events":["mention","dm","mail","reply"]}`, `DELETE` at sign-out; the
belfry sends a content-free push (never the message text) through FCM, or through UnifiedPush
for communities that do not want Google in the path. Needs a Firebase project / server key: ask
the owner first.

## 4. Ending a session from the app (question)

`/connect/token` returns no refresh token, so there is nothing to revoke at
`/connect/revoke`. At sign-out the app calls the sessions API (`/me/sessions/revoke`) with the
bunker URL's pubkey, assuming that is the `bunker_pubkey` the server lists. If it is a different
key, sign-out still wipes the device but the server-side session stays until removed elsewhere.
Please confirm which key identifies a session, and what the signer answers once a session is
removed (so the app can ask the person to sign in again instead of retrying).

## 5. NIP-98 replay protection (note)

The server refuses an authorization it has already seen. Two identical requests in the same
second share an event id, so the second gets 401. The app avoids firing identical signed GETs
at once, but a nonce tag accepted by the server would remove the edge case.

## 6. belfry.json (settled / small)

- `api.base`, `api.channel_invites` and `api.follows` are now published by both live
  documents; SPEC open question 1 is settled. The document lives at
  `https://<domain>/.well-known/belfry.json` (`https://chat.<domain>/belfry.json` returns the
  chat's HTML).
- After `POST /admins` the app re-reads belfry.json for the admin list; if the document lags,
  an admin switch may flip back until it catches up. Returning the new admin list in the
  answer would avoid the second request.

## 7. Open questions carried from SPEC.md

- (Settled) Open proposals in the channel line: the web fixed its list in `belfry-chat` 956e3d2
  (draft, feedback, voting), as the app counts them.
- Pinning topics has no admin check on the web or in the relay; the app follows the web.

## 8. Invites and agents (from the 2026-11-10 update; small)

- `POST /channel-guests` answers `{"member": true}` for a member, and the client then sends the
  invite as a direct message itself. Fine, but nothing tells the client in advance whether a key
  is a guest: it derives that from guest joins (9000 `invited-by` + 1985 `belfry/guest` labels).
  A member that was a guest once and was promoted without the `member` label would be treated as
  a guest. Listing members' kinds in belfry.json (or an answer from `/channel-guests` that the
  client may use for anyone) would remove the guesswork.
- (Done, belfry-sh a6c9a69) `POST /agents` with `mine: true` now says why it refuses: 409
  `not_member`, `unknown`, `taken`, `not_guest_agent`, `not_inviter`, `limit` (`refused` stays
  as a fallback for older belfries). The app maps each to its own fixed message.
- belfry.json `agents` changes only when the document is re-read; after "Make it my agent" the
  app updates its own copy at once and re-reads the document. If the document is cached, the
  profile may flip back until it catches up (same as `admins`, 6).
- `/channel-invites` with `"agent": true` returns `days: 0`: the app does not show a duration for
  an own-agent link (it stays while its member does).

## 9. Events (from the Android events work, 2026-11-10)

- (Done, belfry-sh: `https://chat.<domain>/events/<link>` 302s to `/#/events/<link>`, link =
  `[A-Za-z0-9_-]{1,80}`, on the host that serves `assetlinks.json`.) The app claims the
  `/events/` path on `chat.belfry.sh` / `chat.enspiral.eu` and opens the event page (signed out:
  after sign-in). There is still no signed-out event screen (the web's join screen).
- **Guest RSVPs on the groups relay** (kind 31925 with `h`): zooid patch 0002 lets a guest write
  any non-moderation kind tagged `h` with one of its groups, so a 31925 with `h` in its `ev-`
  group should be accepted; nobody has tested it live yet.
- **Covers** come back from the API as `https://<domain>/events/<link>/cover` for guests; members
  read the Blossom URL from the event. Nothing to change; noted because the demo cannot show a
  real cover.

