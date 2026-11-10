# What the Android app needs from the belfry (server side)

Changes in `collective-belfry` (`services/onboarding/belfry_onboarding/`, the proxy) that this
app depends on or would benefit from. This repo never touches the servers. Nothing here was
tested against a live belfry: the app has only run in demo mode in CI.

## 1. `assetlinks.json` so guest links open in the app (needed for App Links)

The app declares an auto-verified intent filter for `https://chat.<domain>/join/<token>`
(today for `chat.belfry.sh` and `chat.enspiral.eu`). Android only hands those links to the app
when the host serves, without redirects, as `application/json`:

`https://chat.<domain>/.well-known/assetlinks.json`

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "sh.belfry.app",
    "sha256_cert_fingerprints": ["<SHA-256 of the app's signing certificate>"]
  }
}]
```

The fingerprint does not exist yet: the app has no release signing key (the owner creates it,
or Play App Signing does). Until then the link opens the web chat, and the person can still
join from the web. The Caddy `chat` host must answer this path before its
`/join/<token>` → `/#/join/<token>` rewrite. (iOS needs the twin file
`apple-app-site-association` for the same links.)

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

- Open proposals in the channel line: the web lists status `review`, which is not a status
  (draft / feedback / voting / passed / rejected / closed); the app counts draft, feedback and
  voting as open (SPEC 19.13).
- Pinning topics has no admin check on the web or in the relay; the app follows the web.
