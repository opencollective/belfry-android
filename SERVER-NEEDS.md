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

## 2. A name for the Android session — DONE

The server names sessions by `client_id`: "Belfry for Android" for `belfry-android`.

## 3. Push notifications — contract agreed (collective-belfry docs/push.md), app built 2026-11-11

The app follows docs/push.md as written (SPEC 19.19): FCM data-only messages through the gateway,
NIP-44 v2 under the device key, `POST /push/devices` add on every start (once allowed) and remove
at sign-out before the revoke, `POST /notifications` for the settings. What the app still needs:

- **A Firebase project** for `sh.belfry.app` (the owner's Google account) and its
  `google-services.json` as the repository secret `GOOGLE_SERVICES_JSON` (CI and release.yml
  write it to `app/google-services.json`; without it the build has push off). The gateway's
  `fcm_service_account.json` must come from the same project.
- Nothing has run end to end: no gateway, no Firebase project, no device. The payload format is
  checked against vectors made with nostr-tools (`vectors/push.json`); the server's payloads were
  checked to decrypt with them in both directions (2026-11-11). The gateway answers 503
  `not_configured` until the FCM credentials exist.
- Questions: (1) is `POST /notifications` `{}` allowed with a `set` that names a channel I am not
  in (the app only offers my channels)? (2) A DM has no `event`: the app opens the conversation
  list; a future `conv` hint (without the sender) would let it open the conversation.

## 4. Ending a session from the app (question)

`/connect/token` returns no refresh token, so there is nothing to revoke at
`/connect/revoke`. At sign-out the app calls the sessions API (`/me/sessions/revoke`) with the
bunker URL's pubkey, assuming that is the `bunker_pubkey` the server lists. If it is a different
key, sign-out still wipes the device but the server-side session stays until removed elsewhere.
Please confirm which key identifies a session, and what the signer answers once a session is
removed (so the app can ask the person to sign in again instead of retrying).

## 4b. Native sign-in (`POST /session`, `/session/verify`) — built in the app 2026-11-12, not run live

The app follows sessions.py's docstring (SPEC 19.20). Small things that would help, none blocking:

- **Telling an older belfry apart — DONE.** belfry.json's `sign_in.sessions.api` is the full
  address of `…/session`: the app signs in natively only when it is there (and calls that
  address), and offers the browser otherwise. A 404 / 405 *without an `error` key* from a
  belfry that advertises it is still read as "no such API here".
- **`unknown` means two things** (no such member; the belfry answers for another domain). One
  message covers both ("No member has this address"). Fine as is.
- **`tries_left: 0`**: the app says "Ask for a new one." and assumes the code is dead; if the
  belfry answers `expired` on the next try that is handled too.
- **`own_key` vs a signer outage — DONE** (collective-belfry 60c82bf): a signer that does not
  answer is 503 `signer`; `own_key` now means the member holds their own key. The app says
  "The belfry’s signer isn’t answering. Try again in a moment." and offers a new code at once
  (the code is spent).
- **Rate limit per session key / IP on `POST /session`** for unknown names is the belfry's to
  decide: the app cannot enumerate members itself, but the endpoint tells `unknown` from
  `code_sent` to anyone with a key.
- Sign-out revokes the session through `/me/sessions/revoke` as before (item 4); a session made
  by `/session` is listed like the browser's ("Belfry for Android").

## 5. NIP-98 replay protection (note)

The server refuses an authorization it has already seen. Two identical requests in the same
second share an event id, so the second gets 401. The app avoids firing identical signed GETs
at once, but a nonce tag accepted by the server would remove the edge case. The native sign-in
calls (4b) use a `created_at` that only grows (`max(last + 1, now)`), so a resend is never the
same event.

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

- (Done, belfry-sh 5cbab04) Who is a guest: `POST /whois` `{"pubkeys": […]}` (NIP-98, at most
  500) → `{"people": {key: "member" | "agent" | "guest"}}`, keys without an active pass left out.
  The app asks it once per session for everyone brought in by a link or as an agent (batched),
  and again when a profile or a members list opens; the register's answer overrules join and
  label events, which stay the fallback on an old belfry (404) or offline.
- (Done, belfry-sh a6c9a69) `POST /agents` with `mine: true` now says why it refuses: 409
  `not_member`, `unknown`, `taken`, `not_guest_agent`, `not_inviter`, `limit` (`refused` stays
  as a fallback for older belfries). The app maps each to its own fixed message.
- (Done, belfry-sh 5cbab04) belfry.json is sent with `Cache-Control: no-cache` and an ETag.
  The app always asks again (`Cache-Control: no-cache`, `If-None-Match` with the stored ETag) at
  start, after "Make it my agent" / an admin change, and when a profile or members list opens (at
  most every 30 s); a 304 keeps its copy, including a change it made itself and the document has
  not caught up with yet.
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


## 10. From the 2026-11-12 parity pass (small)

- **Branding**: `POST /branding` answers free text in `message` for `invalid` (which field); the
  app shows one fixed sentence. A `field` key (`logo` | `cover` | `primary` | `secondary`) would
  let it mark the field.
- **Meetings** (`/meeting-guests`, `/meeting-guests/transfer`) and **audio recordings** are not
  in the app yet (PARITY): nothing needed from the server until they are.
- **A channel's slug address** (`bugs@` for `bugs-c3e5`): the app shows it when exactly one
  channel answers to the slug, as the web does; it trusts `mail.channel_by_slug` to resolve it
  the same way.

## 11. Meetings (from the Android meetings work, 2026-11-12; none blocking)

- **`POST /meeting-guests` right after the meeting is made** answers `no_channel` / `not_host`
  until the relay has the chat and its admin list: the app tries six times over ~15 s, as the
  web does. An answer that says "not yet" apart from "never" (e.g. 425) would let it wait
  exactly as long as needed.
- **A held account's name.** The app shows the `name` of the 9000 the belfry publishes; the
  answer of `/meeting-guests` could carry it too (`{"pubkey","name"}`) so the row is right
  before the relay delivers the join.
- **Who may check people in** is every host on the client (labels by the chat's admins and its
  creator count). The relay accepts kind 1985 from any member of the chat; fine as long as
  readers filter by host, which both clients do.
- **Old belfries**: a 404 without an `error` key reads "This belfry can’t make accounts for
  people yet." (members can still be invited by their faces).

## 12. Several accounts on one device (Android, 2026-11-12; SPEC 19.24)

- **One push device row per (token, owner).** docs/push.md: "the same token again replaces the
  old row (a new key, a new owner)". A phone with two identities in one belfry has one FCM
  token, so only the identity registered last gets pushes. The app registers one account per
  belfry (the open one, else the first). Asked: key the row by token + owner, and have
  `{"remove": token}` remove only the signer's own row. The app already tries every account's
  key K on a payload, so nothing else changes on the phone.
- **`{"remove": token}` today**: the app calls it at sign-out of one identity; if it removes the
  row whatever its owner, the other identity re-registers at the next launch (it does so anyway).
- Nothing is needed for accounts in different belfries.

## 13. Audio recordings from Android (2026-11-12; SPEC 19.25)

- The app posts `audio/mp4` (.m4a, AAC-LC) parts of about 11 MB (30 minutes at 48 kbit/s), up to
  three in a message. Blossom's 25 MB limit holds each. Nothing to change; noted so the limit is
  not lowered under ~12 MB without telling the apps.

## 14. Guest mode (Android, 2026-11-12; SPEC 19.26, collective-belfry docs/guests.md)

Built against docs/guests.md as it stands; nothing new is needed to run it. Asks, none blocking:

- **"Sign in instead" with an e-mail.** After `/guest/save/verify` answers 409 `taken`, the app
  drops the guest account and opens the native sign-in (SPEC 19.20), which takes `name@domain`
  only (`sessions.py` `request` reads a NIP-05). The address the person just proved is an
  e-mail, often not on the belfry's domain, so the field is left empty with a hint (the browser
  sign-in takes the e-mail). Either let `POST <api>/session` take `{"email"}` for an address that
  has exactly one account here (same code mail), or put the account's `nip05` in the 409 body
  (`{"error":"taken","moved":true,"nip05":"ana@belfry.sh"}`) so the app can prefill it. The
  second is smaller; the app would read `nip05` only when it parses as `name@<this domain>`.
- **`wrong_code` tries left.** The number is only in `message` (text the app never shows). A
  `tries_left` field, as `/session/verify` has, would let the sheet say "2 tries left".
- **A guest-mode key the relay would still store.** Read-only is enforced by the client only
  (docs/guests.md says so). A relay-side refusal for keys whose latest 9000 carries `reading`
  would make other clients (and a modified app) unable to post as a nameless guest.
