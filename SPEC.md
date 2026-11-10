# Belfry chat client specification

Version 0.1 (2026-11-09). Platform-neutral: what any client (iOS, Android, web) must do to be a
correct chat client for a **belfry**, a community's self-hosted server. Derived from the web chat
(the reference implementation: `village-app`, branch `belfry-chat`, files named below) and the
belfry's server code (`collective-belfry`, `services/onboarding/belfry_onboarding/`).

Conformance is checked with the shared test vectors in [`vectors/`](vectors/) (section 17).
Where the web pattern and a platform's conventions differ, section 16 lists the choice each
native app made; the protocol rules (sections 1 to 15) are the same everywhere.

Words: MUST / SHOULD / MAY as in RFC 2119. "Group" and "channel" mean the same thing (a NIP-29
group). Times are Unix seconds. Keys are 64-char lowercase hex unless written `npub…`.

Open questions are collected in section 18 and marked **(Q n)** where they arise.

---

## 1. Discovery

A client asks the person for one thing: the community's **domain** (e.g. `belfry.sh`). An app
built for one community MAY ship with its domain.

1. `GET https://<domain>/.well-known/belfry.json` (no auth; CORS open). Cache it; on failure use
   the cached copy (the app MUST open offline).
2. Endpoints, with the fallback when a field is missing (`src/config.ts`):

| What | belfry.json | Fallback |
|---|---|---|
| Community name | `name` | the domain |
| Groups relay (NIP-29) | `relays.groups` | `wss://groups-relay.<domain>` |
| Community relay | `relays.community` | `wss://relay.<domain>` |
| Signer (bunker) relays | `relays.signer[]` | `wss://bunker.<domain>` |
| File server (Blossom) | `blossom` | `https://blossom.<domain>` |
| API base (NIP-98) | `api.base` | `https://belfry.<domain>/api/v1` |
| Guest links | `api.channel_invites` | `<api base>/channel-invites` |
| Sign-in authorize | `sign_in.connect.authorize` | `https://belfry.<domain>/connect/authorize` |
| Sign-in token | `sign_in.connect.token` | `https://belfry.<domain>/connect/token` |
| Sign-in logout | `sign_in.connect.logout` | `https://belfry.<domain>/connect/logout` |
| Community admins | `admins[]` (64-hex only; drop anything else) | none |
| The chat on the web | `apps[]` entry named `Chat` → `url` | `https://<domain>/chat/` |

   Today's live documents (belfry.sh, enspiral.eu) have no `api.base` or `api.channel_invites`
   and their `api.*` entries point at `pass.<domain>/api`. The web chat uses
   `https://belfry.<domain>/api/v1` regardless; clients SHOULD do the same until `api.base`
   is published **(Q1)**.
3. Well-known keys, from NIP-05 on the apex (`https://<domain>/.well-known/nostr.json?name=…`),
   cached, refreshed at start:
   - `_smtp`: the **mail bridge** key (sends/receives e-mail as Nostr Mail, section 10).
   - `_groups`: the **groups key**, which posts e-mail cards in shared inboxes and guest joins.
   - `<username>`: a member's key (resolving `name@<domain>` when starting a DM).

## 2. Sign-in (OAuth 2.0 code + PKCE → NIP-46)

The member's secret key never lives on the device. Sign-in yields a **bunker URL**: a NIP-46
remote signer session on the belfry's signer (Keycast) that signs as the member.
(`src/core/auth.ts`, server `connect.py`.)

1. Make `state` (24 random bytes) and `code_verifier` (48 random bytes), both base64url without
   padding. `code_challenge = base64url(SHA-256(ASCII(code_verifier)))`, method `S256` (the
   server rejects challenges shorter than 43 chars).
2. Open, in the system browser session (not an embedded web view):
   `GET <authorize>?client_id=<id>&redirect_uri=<uri>&scope=policy:belfry-event&state=<state>&code_challenge=<challenge>&code_challenge_method=S256`
   Optional `prompt=none` for a silent attempt (the server answers `error=login_required`).
3. The person types their e-mail and a mailed 6-digit code (or is already signed in to
   `belfry.<domain>` in that browser). The server redirects to
   `<redirect_uri>?code=…&state=…` or `?error=…&state=…`.
4. Check `state` equals the one sent. `error=access_denied` → "Sign-in was cancelled."; any
   other error → a fixed "Not signed in" message (never echo the URL's text).
5. `POST <token>` with JSON `{grant_type:"authorization_code", code, client_id, redirect_uri,
   code_verifier}`. `client_id` and `redirect_uri` MUST be byte-identical to step 2.
   Answer: `{"bunker_url":"bunker://<signer pubkey>?relay=wss://…&secret=…", "token_type":"bunker",
   "pubkey":"<member hex>", "npub":"…"}`. Errors: `{"error":"invalid_grant" | "app_not_approved"
   | "own_key" | …}` with 4xx; map each to a fixed message.
6. Generate a **client key** (random secp256k1 secret) for this device, kept in secure storage
   and reused for this session. Connect to the bunker (NIP-46, section 3).
7. Cache the member's pubkey (`pubkey` from the token answer, else `get_public_key`) so the
   app opens offline.

**Which apps may sign in.** `connect.py` gives a session only to approved apps: the belfry's own
web apps (`https://<domain>/<app>/`) and addresses an admin approved. A return address MUST be
`https://` (or `http://localhost`). A native app's custom-scheme redirect is therefore refused
today: see `SERVER-NEEDS.md` **(Q2)**.

**Sign-out**: forget the bunker URL and client key, `POST <revoke>` `{token, client_id}` (best
effort, 3 s; the connect endpoint just answers ok), wipe all local data (events, outbox,
decrypted messages), optionally open `<logout>?return_to=<redirect_uri>` to end the browser
session.

## 3. The remote signer (NIP-46)

- Parse `bunker://<remote-signer-pubkey>?relay=<wss>&relay=…&secret=<s>`.
- Requests are kind 24133 events from the client key, `p`-tagged to the remote signer, content
  = NIP-44 v2 encrypted JSON-RPC `{"id","method","params"}`; answers likewise
  `{"id","result"|"error"}`. Subscribe on the bunker relays to `{kinds:[24133], "#p":[client
  pubkey]}`. (nostr-tools' `BunkerSigner` also accepts NIP-04 answers; clients SHOULD try
  NIP-44 first and MAY fall back to NIP-04 for decrypting answers.)
- First `connect` with params `[remote-signer-pubkey, secret]`; answer `"ack"` (or the secret).
- Methods used: `get_public_key`, `sign_event` (param: the unsigned event JSON as a string, with
  `pubkey` = member), `nip44_encrypt [peer, plaintext]`, `nip44_decrypt [peer, payload]`,
  `nip04_decrypt [peer, payload]` (old DMs only).
- Every request has a **20 s timeout**. On a timeout the connection is rebuilt and the request
  fails (the outbox retries later). A phone that slept may hold a dead socket without knowing.

## 4. Relays

Two relays carry the chat (`src/core/sync.ts`):

- **groups** (NIP-29, zooid): channels and everything in them.
- **main** = the community relay (members only): profiles (kind 0), calendar, proposals' texts,
  and the member's DMs and e-mail (gift wraps), delivery reports.

Connection rules:

- NIP-42: when a relay sends `["AUTH", challenge]`, sign kind 22242 with tags
  `[["relay", url], ["challenge", c]]` and send `["AUTH", event]`; after `["OK", id, true]`
  re-send the subscriptions. A publish answered `auth-required:` → authenticate, publish once more.
- Reconnect with backoff `min(30 s, 0.8 s × 2^attempt)`; reset attempts on wake-ups.
- Wake-ups: network back online, app back in the foreground, a **15 s watchdog** while signed
  in (reconnect closed relays; flush if anything is queued). Going offline drops the sockets.

Subscriptions (resent after every (re)connect and AUTH; `since` overlaps to catch late events):

| Relay | Filter |
|---|---|
| groups | `{kinds: KINDS_GROUPS, since: latest(KINDS_GROUPS) − 3600, limit: 2000}` |
| main | `{kinds: [0, 31922, 31923, 31925, 30023, 5], since: latest − 3600, limit: 2000}` |
| main | `{kinds: [1059], "#p": [me], since: latest(1059) − 3 days, limit: 1000}` (wraps are back-dated up to 2 days) |
| main | `{kinds: [4], "#p": [me], since: latest(4) − 3600, limit: 500}` and the same with `authors: [me]` |

`KINDS_GROUPS = [0, 39000, 39001, 39002, 9, 1111, 7, 9007, 9002, 9008, 9000, 9001, 9005, 9021, 9022, 1068, 1018, 5, 1985, 30078, 2100, 2101, 2102, 2103, 8534, 8535, 8536, 8538]`.
A chat-only client MUST subscribe at least to `0, 39000-39002, 9, 1111, 7, 5, 9000, 9001, 9002, 9005, 9007, 9008, 9021, 9022, 1985, 30078`.

Access: the groups relay decides whether someone is in. A `CLOSED` or refusal whose reason starts
with `restricted` or `blocked`, or mentions `pass`/`member`, means "no access (yet)"; after the
groups relay's first EOSE the person is in. Guests (section 13) are in on the groups relay and
refused by the community relay: only the groups relay's verdict counts.

First channel: if after the groups relay's EOSE there is no 39000 at all, the client creates
`general` (9007 then 9002 `name=general`, `about="Everything about <name>. Say hi 👋"`), once.

Events are stored locally by id (dedupe) and every view is derived from the local log (section 6).

## 5. The outbox

Every write is saved locally **before** anything goes on the network, and shows at once
(`src/core/sync.ts`, `store.ts`):

- An outbox item: `localId`, `target` (`groups` | `main`), `kind`, `content`, `tags`,
  `created_at`, attachments, `status` (`queued` | `failed`), `tries`, `error`, optionally a
  pre-signed event (`signed`, gift wraps) and the DM it carries (`dm`).
- `created_at` MUST strictly increase per client: `max(last + 1, now)`.
- Every queued event gets the client tag appended: web `["client","osv-village"]`; native apps
  `["client","belfry-ios"]` / `["client","belfry-android"]`.
- Unsent items show in the feed as if published, marked: "Sending…" (never tried), "Waiting for
  connection…" (tried), or "Not sent: <reason> · Retry" (failed). Retry → `queued`, flush.
- **Flush** (one at a time; a request during a flush runs it again after): walk items by
  `created_at`; skip `failed`; skip items whose target is **stalled** in this pass; sign (if not
  pre-signed), upload attachments, publish (15 s for the connection, 15 s for `OK`).
  - `OK true` → remove the item; the signed event joins the log.
  - Error starting `duplicate:` → counts as sent; remove.
  - **Permanent** (`^(blocked|invalid|restricted|pow|duplicate|error):` or
    `/not allowed|denied|unauthori[sz]ed/i`) → `failed`, `tries+1`, keep the reason; does not
    block later items.
  - Anything else (timeouts, `auth-required:`, `rate-limited:`, no connection, upload errors) →
    stays `queued`, `tries+1`, and its target is stalled for this pass (order kept per relay;
    other relays continue).
- Flush runs on: enqueue, relay connected, online, foreground, and every 15 s while something is
  queued. The outbox and its attachments MUST survive app restarts.
- Pre-signed gift wraps (DMs, e-mail) go through the same outbox (`enqueueSigned`). A DM counts
  as sent once no wrap for **someone else** is left (the self-copy may follow).
- Attachments (Blossom BUD-02): `PUT <blossom>/upload` with the bytes, `Content-Type`, and
  `Authorization: Nostr base64(kind 24242 {content:"Upload <name>", tags:[["t","upload"],
  ["x",sha256hex],["expiration",now+600]]})`. Then add
  `["imeta","url <url>","m <mime>","size <n>","x <sha256>","dim <w>x<h>","alt <name>"]` and
  append the URL on its own line to the content.

Vectors: `outbox.json` (error classes, a multi-round sequence).

## 6. Event log and derived state

Everything is a pure function of the local event log plus queued outbox items.
Ordering everywhere: `created_at` ascending, ties by `id` ascending (string compare).

**Deletion** (`deleted()` in `model.ts`): an event id is deleted if a kind 5 by **the same
author** lists it in an `e` tag (or its address in an `a` tag, `kind:pubkey:d`), or a kind 9005
(moderator removal; the relay only accepts those from moderators) lists it in an `e` tag.

**Profiles** (kind 0, latest per pubkey): `name = display_name || name`, `picture`, `nip05`.
A guest's name comes from the 9000 that admitted them (`name` tag, ≤100 chars) until they have
a profile. Display name: "You" for me; else name, else the nip05's local part, else the first 12
chars of the npub + "…". `realName` (for e-mail From lines and mentions): never "You".

## 7. Channels (NIP-29)

Derivation (`groups()`), processing events in log order; the group id is the `d` tag for
39000-39003, the `h` tag otherwise:

- Metadata `name`, `about`, `picture` from 9007, 9002 and 39000: per field, the latest wins.
  Access `closed` / `open` tags and `prompt` likewise. Default name = the id.
- Admins: the author of any 9007 (the creator; Belfry's relay patch) plus every `p` of 39001.
- Members: the `p` tags of the latest-processed 39002 replace the set; then 9000 adds `p`, 9001
  removes `p`; any kind 9 author is added; 9021 adds its author (open group) or files an
  application (closed group); 9022 removes its author.
- Applications (closed groups): the latest 9021 per person, unless they are a member or a 9000 /
  9001 for them is at least as recent.
- Type by id prefix: `ws-` or `ev-` = event, `pod-` = pod, `prop-` = proposal, else channel.
- Parent (pods, proposals): the `h` of the first kind 9 carrying `["pod", id]` or
  `["proposal", id]` outside the group itself; default `general`.
- `lastActivity` = latest kind 9 in it (else its first event).
- A 9008 (delete group) with that `h` removes the group.
- Order: `general` first, then by `lastActivity` descending.

Actions (vectors `actions.json`): create = 9007 `[["h",id]]` then 9002 `[["h",id],["name",…],
["about",…]]`, id = `slug(name) + "-" + 4 hex`; edit = 9002; join = 9021 (content = answer to
the prompt for a closed group); leave = 9022.

Vectors: `groups.json`.

## 8. Messages, reactions, threads

**Messages**: kind 9 with `["h", group]`, not deleted, by log order. Media: each `imeta` tag
(fields `"key value"`); keep only `url` starting with `http:`/`https:`; the URLs are cut out of
the text, which is then trimmed. Topics: `t` tags, lowercased, deduplicated.
Special posts: a message with an `a` tag of a calendar event (31922/31923) is an event card; with
`["pod",id]` a pod card; with `["proposal",id]` a proposal card; `["client","belfry-mail"]` is a
member's e-mail posted by the bridge ("via e-mail", `subject`, the leading `**<subject>**` line
removed from the text); from the groups key with `mail-from` an inbox card; from the groups key
with `["client","belfry-mail-out"]` an answer sent as the channel. A native client MAY show
cards as plain messages at first (they carry readable text).

**Posting** (`postMessage`): `kind 9`, content = text, tags `["h",g]`, then `["t",x]` for: the
open topic (if any), `announcement` (admins only), and each hashtag in the text (deduplicated,
in that order); then mention `p` tags (section 9).

**Day separators**: a separator before the first message and whenever the local calendar day
changes. **Grouping**: a message starts a new block (avatar + name + time) after a day change, a
different author, or more than 300 s since the previous message.

**Reactions** (NIP-25): kind 7 in the group, `["h",g],["e",message],["p",author],["k","9"]`,
content = the emoji. Interpretation: target = the **last** `e` tag; `+` or empty = 👍;
content longer than 16 UTF-16 code units is ignored; per message, per emoji, the set of
reacting keys; sorted by count descending (ties: first seen first). Tapping my reaction again
removes it: kind 5 `[["h",g],["e",<my reaction id>],["k","7"]]` (if my reaction is still in the
outbox, it is not sent at all). Default emoji row `👍 ❤️ 😂 🎉 🙏 👀`, the person's most used first.
Vectors: `reactions.json`.

**Threads** (NIP-22, `postThreadReply`): kind 1111 in the group:
`["h",g],["E",root.id,"",root.pubkey],["K","9"],["P",root.pubkey],["e",root.id,"",root.pubkey],
["k","9"],["p",root.pubkey]`, then hashtags, then mention `p` tags (skipping the root author).
A reply belongs to the root named by its **`E`** tag. Replies never show as channel messages.
Per root: count, latest time, distinct authors in order.

- **Inline thread**: long-press a message → react or **Reply**. Reply unfolds the thread under
  the message: the **latest 3 replies**, "Show N earlier replies" for the rest, a reply box, and
  Hide. A message with replies shows a chip: up to 3 faces, "N replies", time of the latest.
- **Resurfacing**: an older thread shows again in the feed **at its latest reply** when at least
  one channel message came strictly between the root and that latest reply: a line "<last
  author> replied to <root author>: “<root snippet, 80 chars>”" with the reply's first 140 chars.
  Tapping it unfolds the thread there. Feed order: by time; at equal times messages first.
- Guest joins (section 13) are feed items too.

Vectors: `threads.json`, `messages.json`.

**Delete my message**: kind 5 `[["h",g],["e",id]]`.

## 9. Mentions, hashtags, Markdown

**Mentions** (NIP-27): in the text as `nostr:npub1…`. On send, every valid npub in the text
becomes a `["p",hex]` tag (deduplicated, first appearance order; invalid ones skipped).
Belfry e-mails mentioned people (`mentions.py`). Composer: typing `@` plus up to 30
letters/digits/`_.-` right after a space or at the start opens a picker of people whose name has
a word starting with the query; a pick inserts `@Name ` and on send each picked `@Name` (longest
names first) becomes `nostr:<npub>`. Shown as `@<realName>` (highlighted when it is me).

**Hashtags**: `(^|[^\p{L}\p{N}_&/])#([\p{L}\p{N}_][\p{L}\p{N}_-]{0,48})`, lowercased,
deduplicated. Pinned topics of a channel: the latest kind 1985 with `["L","#t"]` in the group;
its `["l",topic,"#t"]` tags in order.

**Markdown subset** (never rendered as HTML): blocks = paragraphs (single newlines are line
breaks), `#`..`###` headings, `-`/`*`/`+` and `1.`/`1)` lists, `>` quotes, ``` fences; inline =
`**bold**` `__bold__` `*italic*` `_italic_` `` `code` `` `~~strike~~` `[text](url)` (only
`https?://` and `mailto:` become links; others stay literal) and bare `https?://` URLs (trailing
`.,;:!?'")` not included). The exact algorithm (regexes, precedence, the quirks it keeps, e.g. a
fence right after a paragraph line stays text, `snake_case` italicizes) is pinned by
`markdown.json`; compare after merging adjacent text nodes. Plain text nodes in chat messages
are further split into mentions and #topics (same file).

Vectors: `mentions.json`, `markdown.json`.

## 10. Direct messages and e-mail

(`src/core/dm.ts`, `mime.ts`.) All on the **community relay**; decrypted messages are kept on
the device only and wiped at sign-out.

**Receiving**: for each kind 1059 `p`-tagged to me: decrypt the content with NIP-44
(`peer = wrap.pubkey`) → a seal (kind 13; MUST verify its signature) → decrypt its content
(`peer = seal.pubkey`) → the rumor. Drop it if `rumor.pubkey ≠ seal.pubkey`. Kind 4 (NIP-04,
read only) from or to me: peer = the other side. Remember processed wrap ids. If decryption
times out (signer away) stop and try again later; other failures mark the wrap as seen.

**Conversations** (`Conv` key):
- Nostr: `n:` + the sorted pubkeys of everyone in the rumor (author + `p` tags) minus me,
  comma-joined (`n:<me>` for notes to self).
- E-mail: `m:<address>|<subject key>`; `m:<address>` alone = a new e-mail not yet sent.

**NIP-17 send** (kind 14 rumor: `pubkey=me`, `tags=[["p",peer]…]`, `content=text`, id computed,
unsigned): seal = kind 13 signed by me (through the signer), content =
`nip44_encrypt(recipient, rumor JSON)`, `created_at` random up to 2 days in the past, no tags;
wrap = kind 1059 signed by a fresh one-time key, `["p",recipient]`, content = NIP-44 of the seal
with the one-time key, `created_at` back-dated likewise. One wrap per recipient **and one for
me** (other devices see what I sent). All wraps go to the outbox (target main); the message
shows at once as pending.

**E-mail (Nostr Mail)**, through the bridge (`_smtp` key):
- Incoming: a 1301 rumor from the bridge with `mail-from` (sender address), `email-id`,
  `subject`, `belfry-auth` (`pass` = DKIM/SPF aligned, shown as verified), content = the RFC 2822
  message, or empty with `x`, `url`, `decryption-key`, `decryption-nonce` for a large one stored
  on Blossom (AES-256-GCM; key and nonce base64).
- Text = the new part of the mail (section 10.1); `fromName` from the `From` header.
- **Thread key**: base subject = the subject with leading `re fw fwd aw sv tr wg antw rif r`
  prefixes removed (case-insensitive, optional `[n]`, any spaces before `:`, repeated); subject
  key = `encodeURIComponent(lowercase(base) with whitespace runs → one space)`, or `-` when
  empty. A mail joins the thread that its `In-Reply-To` (first) or `References` (last to first)
  names among known mails **with the same address**; else the thread of its subject key.
- Sending: needs my address (`<username>@<domain>`, from my nip05) and the bridge key. Build the
  mail (section 10.1); rumor kind 1301 tags `[["email-id",uuid],["mail-from",me@domain],
  ["rcpt-to",to],["subject",s]]`, content = the raw mail; wrap to the bridge and to me. In a
  thread: answer its last mail with `Re: <base subject>`, `In-Reply-To` = that mail's
  Message-ID, `References` = its references + its id. A new mail needs a subject and becomes
  the thread `mailThreadOf(to, subject)`.
- Delivery reports: kind 7679 from the bridge on the community relay, `["r", email-id]`,
  `ephemeral-pubkey` tag; content = NIP-44 (with that ephemeral key as peer) of
  `{status: delivered|failed|delayed|queued, details?, smtp_response?}`. Query
  `{kinds:[7679], authors:[bridge], "#r":[open ids]}` 3, 10, 30 and 90 s after a send is out.
- Unread: per conversation, messages not mine newer than the last read time (local only).
- Starting a conversation: a member's name or nip05, a 64-hex key, `name@<domain>` (resolved
  by NIP-05; else e-mail), or any other address (e-mail).

### 10.1 MIME

Reading: headers (first occurrence wins, folded lines joined), RFC 2047 encoded words (B and
Q), `base64` / `quoted-printable`, `multipart/*` (depth ≤ 8), attachments (a disposition of
`attachment`, or a name on a non-text part). Text = the plain part, else the HTML part as text;
then the new part only: stop at a line `On … wrote:`, `Le … a écrit :` or `----- Original
Message -----`, drop lines starting with `>`, collapse 3+ newlines. Writing: UTF-8,
`multipart/alternative` with the text as written (`text/plain`) and the Markdown rendered as
escaped HTML (`text/html`), both base64 (76-char lines); headers `From: "<name>" <addr>`,
`To: <addr>`, `Subject` (encoded word if not ASCII), `Date`, `Message-ID: <uuid@domain>`,
optional `In-Reply-To`/`References`, `MIME-Version: 1.0`.

Vectors: `mail-threads.json`, `mime.json`, `events.json` (gift wraps).

## 11. Seen events and "active this month"

Opening a channel publishes, **at most every 6 hours per channel** (checked against my latest
seen event and the last one sent from this device), kind 30078 `[["d","seen:<id>"],["h",<id>]]`,
empty content. A closed pod I'm not a member of is not marked.

`recentlyActive(id)`: the people with a seen event for that channel, or a kind 9, 1111 or 7 in
it, in the **last 30 days**; with each one's latest time; most recent first; if the channel has a
member list (members ∪ admins non-empty) only those people. The channel header shows "N active
members this month" (or "Nobody active this month yet") and their avatars. Vectors:
`recently-active.json`. See **(Q4)** about card-posting keys counting as members.

## 12. Admin rights and channel settings

`canAdmin(channel)` = I am one of its admins (section 7) **or** a community admin: a key in
belfry.json `admins` (64-hex only) or in `access.admins`. Unknown channel → false. Vectors:
`can-admin.json`.

Admins see **Settings**: name and description (9002 with `name`, `about`), and the **shared
inbox** switch: kind 30078 `[["d","belfry:inbox"],["h",id]]` content `{"on":true|false}`; the
setting counts only from the channel's own admins (latest wins). With it on, anyone can e-mail
`<id>@<domain>`; otherwise members can, and their mail shows as their message.
Announcements: admins may tag a message `["t","announcement"]` ("📣 Announcement", e-mailed to
followers); the tag counts only from the channel's admins.

## 13. Guests and invites

- **Member link**: the web chat's address for the channel, `<chat url>#/c/<encoded id>`. Shown
  as text, QR code and share sheet.
- **Guest link** (30 days, this channel only): `POST <api base>/channel-invites` with JSON
  `{"channel": id}` and a NIP-98 header (`Authorization: Nostr base64(kind 27235 event)`, tags
  `[["u", exact url], ["method","POST"], ["payload", sha256hex(body)]]`, `created_at` now).
  Answer `{url, days, expires_at}`; errors `{error, message}` with `error` in `invalid`,
  `unsupported`, `guest` (guests can't invite), `no_channel`, `not_allowed`, `refused`,
  `unauthorized`: show a fixed message per `error`. Any member may make one (Belfry checks).
- **Guest joins**: a kind 9000 in the group with `invited-by` (from the groups key, `guests.py`),
  tags `["p",guest,"member"]`, `["name",…]`, `["guest",<end date>]`. The feed shows "<name>
  joined as a guest, invited by <inviter>" at its time. A 9001 with `["guest","ended"]` removes
  them.
- An empty channel shows "<name> is new · No messages yet. Bring the people who belong here,
  then say hi." with an **Invite people** button.

## 14. Search

Every word of the query (accents and case folded) must appear in the message text, the author's
display name or an attachment name. Scope: this channel or all channels. Newest first, at most
200. A result opens its channel on that message.

## 15. Errors, privacy, security

- **Never render text taken from a URL** (query, fragment, redirect parameters): show fixed
  messages chosen by a key. Server error bodies are mapped by their `error` code too.
- Never log or display secrets (bunker URL secret, client key, codes).
- The client key and bunker URL live in the platform's secure storage (Keychain / Keystore).
- Verify signatures of relay events before storing them (a relay could forge); verify seals
  always.
- Links in messages: only `http(s)` and `mailto` open; everything else stays text.
- Images from `imeta` load only over `https` (http allowed for local development).

## 16. Platform choices (where native differs from the web)

| Web chat | iOS app | Why |
|---|---|---|
| Hash routes, ☰ drawer, laptop sidebar ≥ 900 px | `NavigationSplitView`: channel list (sidebar) → feed; on iPhone a stack with swipe-back | System navigation |
| Custom pull-down header (peek / full) | Tapping the title opens a **sheet with medium and large detents** (about, active members, members, settings) | Sheets with detents are the iOS idiom |
| Long-press opens a bottom sheet with emojis | `.contextMenu` with a reaction row and Reply / Copy / Delete | System menu, haptics |
| "Search" screen | `.searchable` on the channel list (all channels) and on a channel | System search field |
| Copy / `navigator.share` | `ShareLink`, `UIPasteboard`, CoreImage QR | System share sheet |
| Redirect back to the page | `ASWebAuthenticationSession` with the app's URL scheme | Needs SERVER-NEEDS item 1 |
| IndexedDB | SQLite (SwiftData not used: plain tables, testable) | Offline, survives restarts |
| Enter sends on keyboards | Return adds a line; the send button sends | iOS messaging convention |
| Pull to refresh: none (live) | `.refreshable` reconnects and re-subscribes | Expected gesture |

Android should make its own platform choices against the same protocol rules.

## 17. Test vectors

`vectors/*.json` are generated from the web chat's own code by
`tools/vectors/build.mjs` (needs a village-app checkout); where the logic lives in a component
or private state, `tools/vectors/gen.ts` copies it line for line. Each file has `about`,
`people` (names → pubkey, secret, npub; deterministic test keys) and cases:

| File | Checks |
|---|---|
| `nip44.json` | the official NIP-44 v2 vectors (sha256 `269ed0f6…5040`) |
| `events.json` | event id, BIP-340 verify, bech32, NIP-59 unwrap (DM, e-mail, forged rumor) |
| `groups.json` | channel derivation |
| `messages.json` | feed, deletion, media |
| `reactions.json` | reaction aggregation and undo |
| `threads.json` | thread grouping, inline window, feed with resurfacing and guest joins |
| `mail-threads.json` | base subjects, thread keys, thread assignment by references |
| `mentions.json` | mention tags, picker, substitution, hashtags |
| `actions.json` | the exact events each action queues |
| `recently-active.json` | active this month, my seen |
| `can-admin.json` | admin rights |
| `outbox.json` | error classes, per-relay order, retry, duplicate |
| `markdown.json` | the Markdown subset as a tree |
| `mime.json` | reading e-mails |

Rules for comparing: ids and keys are hex; sets are given sorted; `_label` fields are for humans.

## 18. Open questions

1. **API base.** Live `belfry.json` documents have no `api.base`/`api.channel_invites`; the web
   chat hard-codes `https://belfry.<domain>/api/v1`. Will belfry.json publish `api.base`, and is
   `pass.<domain>/api` still valid for `channel-invites`?
2. **Native redirect URI.** `/connect` accepts only `https://` return addresses of approved apps.
   A native app needs either a custom scheme (`belfry://connect`) approved by default, or a
   universal link on a domain Belfry controls. See `SERVER-NEEDS.md`.
3. **Refresh / expiry.** The token answer has no refresh token and no expiry; the web stores
   `refresh_token` if present. How long does a bunker session live, and how does a client learn
   it was revoked (signer answers an error? which one)?
4. **Card keys as members.** `recentlyActive` says it excludes "the keys that post e-mail or
   drop-box cards", but `groups()` adds every kind 9 author to the members, so the groups key
   counts as an active member once it posted a card (`recently-active.json` pins today's
   behaviour). Should 9 authors who are the groups key or bridge be excluded?
5. **Member link for native apps.** The member invite link is the web chat's URL. Should a native
   app claim it (universal links on `<domain>/chat/`) so links open in the app?
6. **Markdown quirks.** `snake_case_word` renders italic and a fence right after a paragraph
   line stays text. Keep (all clients identical) or fix in all clients?
7. **Push notifications.** None today. A native app needs a push relay (APNs/FCM) fed by the
   belfry for mentions, DMs and e-mail. See `SERVER-NEEDS.md`.
8. **NIP-46 encryption.** Does Keycast answer in NIP-44 only, or may it answer NIP-04?
9. **Client tag.** Should native apps send `["client","belfry-ios"]` or reuse the web's value?

---

## 19. Additions since the iOS spec

Written for the Android app (2026-11-10) from the web chat's source at `belfry-chat` 85ccbd4
(`src/core/model.ts`, `actions.ts`, `people.ts`, `ui/Chat.tsx`, `ui/MailPost.tsx`). Everything
here is protocol, not layout, and is meant to be ported back into sections 7 to 13. The Android
core implements it in `core/src/main/kotlin/sh/belfry/core/model/` (`World`, `Actions`).

`KINDS_GROUPS` (section 4) now also has **1010** (edits).

### 19.1 Message edits (kind 1010)

- Edit **my own** message (kind 9) or thread reply (kind 1111): a kind 1010 event in the group,
  `[["h",g],["e",<message id>],["k","<kind of the message>"], <kept imeta tags…>, ["t",topic]…,
  <mention p tags of the new text>]`, content = the new text, then the kept media URLs, one per
  line (`[text, ...urls].filter(non-empty).join("\n")`).
  - Kept from the **original** event: every `imeta` tag, and every `t` tag that the text shown
    before the edit did not spell as a #hashtag (the topic it was sent in, `announcement`).
    `t` tags = those kept ones, then the hashtags of the new text (deduplicated, lowercased).
- Reading: per message id, the edit with the greatest `created_at` (ties: greatest id) that is
  not deleted and **whose author is the message's author**. The message is then shown with the
  edit's content, `imeta` and `t` tags (parsed exactly like a message), keeps its own id, author
  and time (reactions, threads and links hang off the original id), and is marked "edited"
  (with the edit's time). Clients that do not know kind 1010 show the first version.
  (The web picks the latest 1010 of any author and then drops it if the author differs, so a
  later foreign 1010 hides the author's own edit there; Android ignores foreign edits first.
  Suggested for all clients.)
- An edit can be deleted by its author (kind 5): the previous edit, or the original, shows again.
- Only a plain message can be edited: mine, already sent (not in the outbox), with text, and not
  an event / pod / proposal card, an e-mail, an inbox card or an answer sent as the channel.

### 19.2 Deleting my message

Kind 5 `[["h",g],["e",id]]` as before. The client MUST ask first, in the app (no system
dialog), showing the message: author name and the first 240 characters of the text with
`nostr:npub…` runs replaced by `@…` (or "A photo or file" / "N photos or files" when it has no
text), and "It disappears for everyone, with its reactions. This can't be undone."

### 19.3 Guests: labels, agents, lines that disappear

- The join event (kind 9000 with `invited-by`, section 13) may carry `["agent","1"]` (an agent
  invited by a member: "joined as an agent") and `["unsaved","1"]` (an account without an e-mail
  yet: "guest · not saved").
- The belfry labels a guest with kind 1985 events in the same group:
  `["h",g],["L","belfry/guest"],["l","saved","belfry/guest"],["p",guest]` once they proved an
  e-mail address (clears "not saved"), and `["l","member","belfry/guest"]` once an admin made
  them a member (`POST /admins`): from then on they are **not called a guest** anywhere (chips,
  profile, members list); their join line stays in the feed.
- A guest who was removed (9001 for them, at or after the join) **without ever writing** in the
  group (no kind 9 or 1111 by them) leaves no join line.
- "Am I a guest": some join of mine, in any group, without the `member` label. Guests cannot
  create guest links, are not offered "Message", and cannot be made admin while every join of
  theirs is unsaved ("This guest has no e-mail yet…"); an agent never ("An agent cannot be an
  admin."). The server answers the same with `guest_not_saved` / `agent` / `not_member`.

### 19.4 Guest links without a code

_(Filled in from the sign-in work: `https://chat.<domain>/join/<token>`,
`GET <api>/channel-invites/<token>`, `POST …/join`.)_

### 19.5 Profiles: tags and places (kind 30078, `d` = `belfry:profile`)

- One replaceable event per member on the **community relay** (members only):
  `[["d","belfry:profile"]]`, content JSON
  `{"tags":[…],"share":"city"|"country"|"none","place":{"id","name","region","country","lat","lon"}}`.
  The newest per author counts.
- Tags: trimmed, lowercased, leading `#` removed, whitespace runs → `-`, only letters, digits,
  `_`, `-` kept, at most 32 characters each, deduplicated, at most 30.
- `share`: anything but `country` / `none` reads as `city`. `none` → no place. `country` → only
  `{country}`. A place needs `country` matching `^[A-Z]{2}$`; `id` ≤ 40, `name`/`region` ≤ 80
  characters, `lat` (|x| ≤ 90) and `lon` (|x| ≤ 180) rounded to 2 decimals. A place is a city's
  point from the belfry's list (`GET <api>/places?q=&limit=8`, no auth; answer
  `{places:[…], attribution}`) or one a member added (`POST <api>/places` NIP-98
  `{name,country,near:<id of the nearest listed city>}` → `{place}`; error `bad_country`).
  Never the device's GPS.
- Suggestions when adding tags: every member's tags by use (ties alphabetical).
- Name, about (Markdown), picture stay in kind 0 on the community relay: saving keeps the other
  fields of my latest kind 0 and sets `name` = `display_name` (≤ 100) and `about` (≤ 2000);
  a new picture is uploaded (resized to 512 px, JPEG) and set as `picture`.
- "Admin of the belfry" switch on a profile (belfry admins only): `POST <api>/admins` NIP-98
  `{pubkey, admin}`; errors `not_member`, `guest_not_saved`, `agent`, `last_admin`,
  `not_admin`, each with a fixed message. Afterwards reload `belfry.json` (its `admins`).

### 19.6 Special posts (precise rules)

A kind 9 message is, in this order of precedence:

1. **Inbox card**: author = the groups key, has `mail-from` and `["client","belfry-mail"]`.
   Tags: `mail-from`, `mail-name`, `subject`, `message-id`, `belfry-auth` (`pass` = verified),
   optional `["mail-original", https url, key, nonce]` (the whole mail, AES-256-GCM on Blossom).
2. **Sent as the channel**: author = the groups key with `["client","belfry-mail-out"]`; tags
   `e` (the card it answers), `mail-to`, `subject`, `sent-by` (the member who wrote it).
3. **Event card**: first `a` tag starts with `31922:` or `31923:`.
4. **Pod card**: `["pod", id]` with id starting `pod-`. 5. **Proposal card**: `["proposal", id]`
   with id starting `prop-`.
6. **Member's e-mail**: `["client","belfry-mail"]` (not from the groups key): "via e-mail",
   `subject`, optional `mail-original`.

For 1 and 6 the text shown drops a leading `**<subject>**` line. Cards 3 to 5 always show
their author with the line "Created an event / a pod / a proposal". A message with
`["client","belfry-drop"]` from a key without a profile is named "Drop box".

**Inbox card state** (per card id = the `e` tag of the events below, in log order, deleted ones
skipped): kind 1985 with `["L","belfry/inbox"]` and `["l",x,"belfry/inbox"]`: `open` / `closed`
set the status, `assigned` sets the assignee to its `p` tag (none = nobody); each "sent as the
channel" post counts as an answer and turns `open` into `waiting` (shown "Answered").
Actions: `[["h",g],["L","belfry/inbox"],["l","open"|"closed","belfry/inbox"],["e",card]]`;
assigning adds `["l","assigned","belfry/inbox"],["e",card],["p",assignee]`.

### 19.7 Feed layout rules (shared by clients)

- With a topic open the feed has only that topic's messages (no joins, no resurfaced threads);
  the topic `announcement` shows admins' announcements only.
- A message starts a block (avatar, name, time) on a new day, after a non-message item, after
  another author, after more than 300 s, right after a message whose thread is unfolded, and
  always when it is an announcement, an e-mail or a card.
- "Topics a message carries but does not spell" (sent inside an open topic) are shown as chips
  under it, except the open topic and `announcement`.

### 19.8 Pods, events, proposals, drop links, task list (events as the web writes them)

- **Pod**: id `pod-` + slug(name)[:24] + `-` + 4 hex. 9007 `[["h",id]]`; 9002
  `[["h",id],["name",…],["about",…],["open"]]` or `…,["closed"],["prompt",question]`
  (default question "Why do you want to join, and what would you bring?"); then in the parent
  kind 9 `⬡ New pod: <name>[ · <about>]` with `[["h",parent],["pod",id]]`. Admit an applicant:
  9000 `[["h",id],["p",pk,"member"]]`; decline / remove: 9001 `[["h",id],["p",pk]]`.
- **Event**: `x` = 8 hex; chat `ws-<x>` (9007 + 9002, about = first line of the description or
  "Chat for this event"); on the **community relay** kind 31923 (timed) or 31922 (all-day):
  `[["d","session-<x>"],["title",…],["h","ws-<x>",<groups relay>],["start",…],["start_tzid",tz],
  ["end",…],["end_tzid",tz],["location",…],["r",https link]]` (all-day: ISO dates, no tzid),
  content = description; then in the channel kind 9 `📅 <title> · <when>[ · <location>]` with
  `[["h",channel],["a","<kind>:<me>:session-<x>",<community relay>]]`.
  Reading: latest per coordinate; deleted by kind 5 `a`; `channel` = the `h` of the first
  non-deleted kind 9 linking it outside `ws-` groups (default `general`); group = its `h` tag or
  `ws-` + d without `session-`. RSVP: kind 31925 on the community relay
  `[["d",sha256hex("<me>:<author>:<d>")[:30]],["a",coord],["e",event id],["status",
  "accepted"|"tentative"|"declined"],["p",author]]`, latest per person. Delete: kind 5
  `[["a",coord],["e",id]]` on the community relay. Upcoming = end (or start) ≥ today 00:00 local.
- **Proposal**: `x` = 6 hex; chat `prop-` + slug(title)[:24] + `-<x>`; text = kind 30023 on the
  community relay `[["d","proposal-<x>"],["title",…],["summary",…],["t","proposal"],
  ["h",chat,<groups relay>],["version","1"],["status",s],["published_at",now]]`; card in the
  parent: kind 9 `📝 Proposal: <title>[ · <summary>]` with `[["h",parent],["proposal",chat],
  ["a",coord,<community relay>]]`. New version: same `d`, `version` + 1, and in its chat kind 9
  `📝 Version N[: note]` with `[["h",chat],["a",coord,relay],["version","N"]]`. Status: the
  latest kind 1985 in its chat `[["h",chat],["L","belfry/proposal-status"],["l",s,
  "belfry/proposal-status"],["a",coord]]` by the author or an admin of the chat or of its
  parent (`draft feedback voting passed rejected closed`; before any label, the text's `status`
  tag). Vote: kind 1068 in its chat `[["h",chat],["a",coord],["version",N],["polltype",
  "singlechoice"],["option","yes","Yes"],["option","no","No"],["option","abstain","Abstain"]]`,
  content `<title> (version N): do you approve?`, opened when the status becomes `voting` (and
  again for a new version under vote); answers kind 1018 `[["h",chat],["e",poll id],
  ["response",option]]`, latest per person; the latest poll counts and voting is open only
  while its version is the text's version.
- **Drop box link**: kind 30078 in the group `[["d","drop:<token>"],["h",g],["expiration",ts],
  ["login"]?,["alt",…]]`, token = 16 random bytes base64url; live while not deleted (kind 5 by
  its author with `e` and `a` = `30078:<author>:drop:<token>`) and `expiration` > now. The page
  is `https://<domain>/drop/<token>`. Choices: 1 day / 1 week / 1 month; anyone / signed in.
- **Pinned topics**: `[["h",g],["L","#t"],["l",topic,"#t"]…]` (kind 1985), the whole list each
  time; any member may pin.
- **Member labels** (per channel): kind 1985 `[["h",g],["L","osv/member-label"],
  ["l",label,"osv/member-label"]…,["p",member]]`, latest per member. (The namespace keeps the
  web's historical name.)
- **Task list** (tasklist.sh): list id = `sha256hex("osv-village:"+group)[:16]`; kinds 2100
  (task, content = title ≤ 300), 2101 (`["e",task],["action","claim|unclaim|done|undone|delete"]`),
  2103 (comment), each with `["t",list id],["h",group]`.
- **Following a channel by e-mail** is not an event: `GET/POST <api>/me/follows` (NIP-98).
- The web chat has **no unread count per channel** (only for direct messages and e-mail).
