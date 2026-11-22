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
- Reconnect with backoff `min(30 s, 0.8 s × 2^attempt)`; reset attempts on wake-ups. (Android since 19.50: 1 s, 2 s, 4 s, 8 s, 16 s, then 30 s; dead sockets: 19.50.)
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
    (Android since 19.50: only its lane waits, see there.)
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
inbox** switch: kind 30078 `[["d","belfry:inbox:<id>"],["h",id]]` content `{"on":true|false}`; the
setting counts only from the channel's own admins (latest wins). Until 2026-11-21 the `d` was
`belfry:inbox` alone, so one admin's switch for a channel replaced theirs for every other (a 30078
is keyed on kind + author + `d`; OSV's #hello lost its inbox): readers take the new `d`, or the old
`d` whose `h` is the channel, latest wins; writers write only the new `d` (docs/mail.md @73a414f). Per author + kind + `d` only the newest copy counts, and only then its `h`: a stale copy kept locally (old `d`, h=hello) after the relay replaced it (h=sponsors) leaves #hello off (OSV, 2026-11-22). This holds for every replaceable and addressable event (kinds 0, 3, 10000–19999 per author + kind; 30000–39999 per author + kind + `d`; NIP-01 ties: the lowest id), except calendar events, proposals and RSVPs, whose versions are their history; a per-channel setting always carries the channel in its `d` (Xavier, 2026-11-22: settings for different channels never override each other). Save sends the switch whenever it was touched. With it on, anyone can e-mail
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
| `outbox.json` | error classes (refused uploads: 19.17, an Android addition), per-relay order, retry, duplicate |
| `markdown.json` | the Markdown subset as a tree |
| `mime.json` | reading e-mails |
| `channel-refs.json` | naddr encode / decode, channel references in previews (19.14; Android addition, made by `tools/vectors/channel-refs.mjs` with the web's nostr-tools) |

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
  create guest links; a guest's profile has no "Message" button; a guest cannot be made admin while every join of
  theirs is unsaved ("This guest has no e-mail yet…"); an agent never ("An agent cannot be an
  admin."). The server answers the same with `guest_not_saved` / `agent` / `not_member`.

### 19.4 Guest links without a code

(`src/core/auth.ts` inviteInfo / joinAsNew, `src/core/sync.ts` joinWithAccount, `src/ui/Login.tsx`
JoinScreen, `src/ui/Invite.tsx`; server `guests.py`.) This replaces the hand-off code of earlier
versions: there is no `/api/guest-sessions` any more.

- **The link**: `https://chat.<domain>/join/<token>` (the web chat also reads `…/#/join/<token>`).
  `<token>` matches `[A-Za-z0-9_-]{20,}`. A native app registers for these links (Android App
  Links, iOS universal links) and MUST take only the domain and the token from the address;
  nothing else of the address is ever shown.
- **Making one** (any member, not a guest): `POST <api.channel_invites>` (NIP-98) with
  `{"channel": id, "people": 1 | 50, "link_days": 1 | 7 | 30}` (defaults 1 and 1). Answer
  `{url, agent_url?, days, people, link_days, expires_at}`; `days` is how long a guest stays
  (30). Errors as in section 13. The invite sheet offers "How many: one person / up to 50" and
  "Link works for: 1 day / 1 week / 1 month", shows the link as text, QR code and share sheet.
- **Reading one** (no sign-in): `GET <api.channel_invites>/<token>` →
  `{community, channel, channel_name, about, inviter, state, days}`; `state` is `open`, `used`,
  `expired` or `revoked` (fixed messages: "This invite was used already / has expired / was
  withdrawn. Ask for a new one."). 404 → "This invite link is not known here. Ask for a new one."
- **Joining as someone new** (not signed in): the device makes a **session key** (a fresh
  secp256k1 secret), and sends `POST <api.channel_invites>/<token>/join` with body
  `{"name": "<their name>"}` and a NIP-98 header **signed by that session key** (not by a
  member key: there is none yet). Answer `{bunker_url, channel, pubkey?}`: the belfry made the
  account in its signer and bound its first NIP-46 session to the session key, which is from
  then on the client key of section 3. The guest is in `channel` only, `saved: false`.
  - **One session key per invite**, kept in secure storage until the join succeeds: the server
    is idempotent per signing key, so a second tap, a retry after a timeout or an app restart
    gets the **same** account, never a second one. Forget the key after success.
  - `409` (the key is another account's in the signer): forget the key; the next try makes a new
    one. Other errors by `error` code: `name_needed`, `closed`, `unsupported`, `relay`, `failed`.
- **Joining with an account** (signed in, member or guest): the same `POST …/join` with body
  `{}` and a NIP-98 header signed **as the member** (through the bunker). Answer
  `{channel, already, guest?}`: the account gets the channel (a member: a 9000 in the group; a
  guest: one more guest pass on the same person); `already: true` when they were in (the link
  is not used up). No second account is ever made. A link opened while signed out is kept
  (pending invite) through sign-in and used right after.
- A guest saves their account with `POST <api>/guest/save` `{email}` then
  `POST <api>/guest/save/verify` `{email, code}` (both NIP-98 as the guest); the groups key then
  labels them `saved` (19.3).

### 19.5 Profiles: tags and places (kind 30078, `d` = `belfry:profile`)

- One replaceable event per member on the **community relay** (members only):
  `[["d","belfry:profile"]]`, content JSON
  `{"tags":[…],"share":"city"|"country"|"none","place":{"id","name","region","country","lat","lon"}}`.
  The newest per author counts. Other keys may be there (the server and website write
  `headline`, a string, and `links`, `[{url, label}]`): saving starts from my latest event's
  parsed content and overwrites only `tags`, `share`, `place` (`place` removed when not shared);
  every other key is kept as it is (lead, 2026-11-21; iOS told).
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

### 19.9 Engine rules the Android app adds or pins down

- **Subscriptions** follow today's web chat: `KINDS_GROUPS` includes `1010`, and the community
  relay gets one more filter, `{kinds:[30078], "#d":["belfry:profile"], since: latest − 3600,
  limit: 2000}` (19.5). After a disconnect the `since` values are computed again from the log.
- **Mail threads do not depend on arrival order.** Wraps are back-dated at random and read newest
  first, so an answer can be decrypted before the mail it answers. After each batch every
  incoming mail is placed again, oldest first, with the rule of section 10 (the web places a
  mail once, when it is read).
- **Demo mode** (`localOnly`): no relay and no HTTP at all. A flush signs with the local key and
  adds the event to the log; a 9007 / 9002 also yields the 39000 / 39002 a relay would write;
  attachments stay on the device and are addressed as `https://blob.local/<key>` (never
  fetched); e-mails report "Demo mode sends no e-mail."
- **Sign-out**: the app ends its own session with `POST <api>/me/sessions/revoke`
  `{bunker_pubkey: <the bunker URL's key>}` (NIP-98, best effort, 3 s): `/connect/token` gives
  no refresh token to revoke.
- **Signed GETs** to the same address are spaced by more than a second: the belfry refuses a
  NIP-98 event id it has seen, and two identical GETs in one second share an id.


### 19.10 Pinned messages (kind 1985, `L` = `belfry/pin`)

(Web: `model.ts` `pinned`, `actions.ts` `pinMessage`, village-app 30e775b.)

- Pin or unpin: kind 1985 in the group, tags
  `[["h",g],["L","belfry/pin"],["l","pinned"|"unpinned","belfry/pin"],["e",<message id>]]`, empty
  content.
- A label counts only when its author is one of the channel's admins (section 7) or a community
  admin (section 12). Per message the label with the latest `created_at` wins (ties: the one
  processed later). Labels with another `l` value are ignored.
- The pinned messages of a channel are its kind 9 messages (not deleted) whose winning label is
  `pinned`, most recently pinned first. Only channel messages (kind 9) can be pinned, not thread
  replies; a queued message cannot be pinned.
- "Got it, hide" is local to the device (a list of at most 200 message ids): it only stops the
  pin from being the summary line; the pull-down still lists it.

### 19.11 To-dos made from messages

The task list of 19.8 is called **To-dos** in the interface. "Turn into a to-do" on a message
queues a kind 2100 with the list's tags plus `["q", <message id>]`; its title is the message's
text without `nostr:npub…` mentions, whitespace runs collapsed, 300 characters at most (nothing
is offered when that is empty, when the message is still queued, or when a to-do with that `q`
exists already). The message then shows a mark: "To do", "To do · <assignee>" or "Done"; tapping
it queues a 2101 `done` (or `undone` when it was done).

### 19.12 A pod's mark

A regular polygon with one side per member: `max(3, members)` sides, point up; 13 members and
more draw a circle. (`icons.tsx` `PodShape`; in a 24-unit box: radius 9.5 around (12, 12), a
triangle around (12, 13.5); the circle has radius 9.)

### 19.13 The one line under a channel's name

(`ui/ChannelLine.tsx`.) Facts of a channel: its pods and its **open** proposals (sub-channels
whose parent it is; open = status draft, feedback or voting — the web's list says `draft`,
`review`, `voting`, and `review` is not a status, so a proposal open for feedback is missing
from the web's line until `belfry-chat` 956e3d2, which fixed it), its upcoming events (ending today or later), its pinned
messages, its to-dos not done, the people active this month (section 11).

The line is the first of these that applies:

1. The feed is filtered on a topic: "Showing **#topic** only" (or "announcements"), pill "Show all".
2. The channel is a proposal's chat: while voting on the current version "**Voting** · N yes ·
   N no" with my vote or a "Vote" pill; else "**<Status>** · version N · by <author>".
3. A pod I may administer with applications: "**N people ask to join** · N members", "Review".
4. An open proposal I have not voted on (voting, poll of the current version): its title, "Vote".
5. An upcoming event I have not answered (no state, 19.18; not cancelled) that starts within 7
   days: "**<day> <time>** · <title>", "RSVP".
6. A pinned message I did not hide: its first line without Markdown marks, mentions as "@…".
7. The next event: as 5, with my state ("Going", "Maybe", "Can't go", "Applied", "Hosting"…) or "RSVP".
8. An open proposal: "**<title>** · <status>".
9. A pod: "N members · open to join" / "by application" (shown muted).
10. "N people active this month" / "1 person active this month" / "Nobody active this month
    yet" (shown muted).

At the right, counts above zero: events (minus one and written "+N" when the line shows an
event), open proposals (likewise), pods, to-dos. With a topic filter no counts.

Pulled down, in this order: (pod or proposal) "Pod in # <parent>"; a proposal's own state; the
people asking to join; **Pinned** (each with author, day, "Show in the channel", "Got it,
hide"); **Coming up** (3 events, then "All N events"); **To decide** (open proposals, "Vote" /
"Voted"); **To do** (4 to-dos with a box that ticks them off and who is on it, then "All N
to-dos"); **Pods** (shape, name, members); **Topics**; the people line leading to members,
files and settings; the first line of the description.

### 19.14 Channel references (`nostr:naddr1…`)

(Web `belfry-chat` 4d5682d: `model.ts` channelRef / channelOfNaddr / plainRefs / NADDR,
`Topics.tsx` ChannelRef, the composer's # suggestions. Vectors: `vectors/channel-refs.json`.)

- A channel picked after "#" in the composer is written in the text as `nostr:` + the NIP-19
  `naddr` of the group's metadata: kind **39000**, author = the key that signed the group's
  latest kind 39000 (the relay's), identifier = the group id, one relay hint = the groups relay.
  TLVs 0 identifier (UTF-8), 1 relay, 2 author (32 bytes), 3 kind (u32 big-endian); nostr-tools
  writes them in the order 3, 2, 1, 0 (clients should too, so both produce the same string).
  A channel whose 39000 was never seen has no reference (not offered by the picker).
- Found in text by `nostr:(naddr1[02-9ac-hj-np-z]{20,400})`. It is a channel reference when it
  decodes to kind 39000; the group is its identifier. It is no topic: a picked "#Name" is
  replaced by the reference before hashtags (`t` tags) are read; a "#word" typed by hand stays
  a topic.
- Picker: opens on `(^|[^\p{L}\p{N}_&/])#([\p{L}\p{N}_-]{0,30})$` before the caret (not while the
  @ picker is open); offers channels that are not events' chats, whose id starts with the query
  or whose name has a word (split on spaces, `_`, `-`) that does; most recently active first; 6.
  A pick writes "#<name> " and remembers name → reference; sending replaces each picked
  "#<name>" (longest first) by its reference. Editing shows each reference again as "#<name>".
- Shown in a message: "#<name>" that opens the channel. The web shows the id for a channel it
  does not know and the raw text for an naddr that is not a group's; Android shows "#channel"
  (muted, not tappable) and "Nostr address" instead: a raw naddr is never shown.
- One-line previews (thread lines, delete confirmation, pinned line, to-do titles, search,
  drawer, Messages): `plainRefs` = mentions as "@…" (or the caller's text), references as
  "#<name>", "#<id>" when the channel is unknown, other naddrs as written.

### 19.15 Inviting someone who is here already

(Web `core/invite.ts`, `ui/Invite.tsx`, `ui/Chat.tsx`, commits 1834584 and 453684b; server
`guests.py` `/api/channel-guests`.)

- Who someone is: a **member's own agent** when belfry.json `agents` maps their key to a member
  (`{agent key: member key}`, absent when there are none); a **guest** when they have a guest
  join (19.3) that is not "member" and are not a member's agent; an **agent** when either a
  member's agent or a guest join carries `["agent","1"]`.
- Can read channel G: its members and admins; nobody else in a closed group; in an open one
  everyone but guests (of other channels).
- The invite search lists everyone with a name (members, guests, agents), me left out, a word of
  the name starting with the query, 5 at most, each marked "<owner>'s agent", "Agent, a guest",
  "Guest" (or nothing for a member) and "Already in" / "Can already open it" / "Not in this
  channel".
- A **guest** is added: `POST <api>/channel-guests` `{"channel","pubkey"}` (NIP-98) →
  `{"added","already","member"}`; when the answer says `member`, carry on as for a member. Errors:
  `invalid` 422, `unsupported` 409, `guest` / `agent` 403 (the caller is one), `no_channel` 404,
  `not_allowed` 403 (`may_invite`: only people who have the channel), `unknown` 404.
- A **member** gets one NIP-17 DM: "<my name> invited you to <# name | pod/event/proposal name>:
  https://chat.<domain>/#/c/<id>" (my name as others know it, else "A member"). In a closed group
  I administer they are admitted first (9000 `["p",key,"member"]`); in a closed group I do not:
  "<my name> thinks you should join <name>. It takes an application: <link>".
- The composer marks "not in this channel" in the @ picker and, for each picked person who cannot
  read the channel, says so above the field with Invite ("Add as a guest" for a guest).
- **The register decides when it can** (belfry-sh 5cbab04): `POST <api>/whois` `{"pubkeys":
  [hex…]}` (NIP-98, at most 500 per call) → `{"people": {hex: "member" | "agent" | "guest"}}`;
  keys holding no active pass are left out; errors `not_here` 403 (the caller holds no pass),
  `invalid` 400. Ask it for every key brought in by a link (9000 with `invited-by`) or listed in
  belfry.json `agents`: once per session in batches, again for the people of a profile or members
  list when it opens. Its answer overrules the events: "member" or "agent" = not a guest (a guest
  join counts as "member since"), "guest" = a guest even if a label says member; a key it leaves
  out, an old belfry (404) or no connection: the events decide as above.
- **belfry.json is revalidated, never trusted from a cache**: request it with `Cache-Control:
  no-cache` and `If-None-Match: <the ETag of the copy held>`; 304 = the copy is current. Since
  5cbab04 the belfry sends `Cache-Control: no-cache` and an ETag itself (it used to allow a
  300 s cache). Clients re-read it at start, after changing admins or agents, and when a profile
  or members list opens (at most every 30 s).

### 19.16 Members' own agents

(Web `access.ts` agentOwner, `invite.ts` myAgents / setMyAgent, `Profile.tsx`, `Invite.tsx`;
server `agents.py`.)

- `POST <api>/agents` (NIP-98): `{}` → `{"agents":[{"pubkey","name","since"}],"limit":n}`;
  `{"pubkey","mine":true}` makes a guest agent mine (only the member who invited it, or an admin
  for themselves, within the limit); `{"pubkey","mine":false}` removes my agent (its member or an
  admin). Errors: `not_member` 403 / 409; making it mine, 409 with the reason (belfry-sh
  a6c9a69): `unknown` (nobody has that key), `taken` (someone's agent already),
  `not_guest_agent` (did not join as a guest agent), `not_inviter` (invited by someone else),
  `limit`; `refused` on older belfries; `not_agent` 404, `not_yours` 403, `invalid` 400,
  `unsupported` 409. The answer is the new list; belfry.json `agents` follows.
- Profile: a member's agent shows the chip "agent" and "agent of <owner | you>"; no admin
  switch. "Make it my agent" when it is a guest agent, I am a member (not a guest, not an agent)
  and I invited it or am an admin. "Remove this agent…" (its member or an admin) asks in the app.
- Join line of a member's agent (its 9000 has `agent` and a `member` label and `invited-by`):
  "<name> joined as <owner>'s agent" (no "invited by").
- "My own agent" in the invite sheet (when `limit` > 0; off when the limit is reached):
  `POST <api>/channel-invites` with `"agent": true` (people 1) → the link to hand over is
  `agent_url` (the instructions, `…/invite/<token>.md`); it works once. Error `agent`: agents
  can't invite.

### 19.17 Outbox: refused uploads

A file the storage refuses with a 4xx other than 401, 408 or 429 (error text
`upload failed (<status>…)`) is **permanent**: the message is failed ("Not sent", Retry /
Discard) and does not hold back later items to its relay. 401, 408, 429, 5xx and network errors
stay retryable. (Web `sync.ts`, 1834584; `vectors/outbox.json` classify cases.)

### 19.18 Belfry events (web belfry-chat ef9e1cc; server events.py; PROTOCOL.md)

New events are **Belfry events**; the older `ws-` ones above are still read and answered.

- **Ids.** `d` = `<year in UTC>-<6 of a-z0-9>` (matches `^\d{4}-[a-z0-9]{6}$`), never recomputed.
  Its chat is the NIP-29 group `ev-<d>`; the creator is its admin, co-hosts are admins added
  with 9000 `[["h",g],["p",pk,"admin"]]`. URL: `https://<domain>/events/<link>`, link = the
  custom name (`slug` tag, `^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$`, not shaped like a `d`, not
  `new|slug|feed|past|mine`) or `d`.
- **Make.** 9007 `[["h",g]]`; 9002 `[["h",g],["name",title],["about",first line[:200] or "The
  event’s chat"],["picture",cover]?,["open"|"closed"]]`; on the community relay 31923 / 31922
  `[["d",d],["title",…],["h",g,<groups relay>],["visibility","members"|"link"|"public"],
  ["start",…],["start_tzid",tz]?,["end",…]?,["end_tzid",tz]?,["location",…]?,["r",https]?,
  ["image",cover]?,["slug",…]?]`, content = Markdown description. No post in any channel: the
  URL is sent like any message. Then `PUT <api>/events/<d>/slug {"slug"}` (409 `taken`).
  Android adds, when "by application" is picked, one ticket type (below) `closed` with the
  question and the group `closed`.
- **Rule B.** Versions are grouped by `d` (any author). Hosts = the group's creator (first 9007)
  ∪ its admins (39001, 9007, 9000 with role `admin`). Versions not signed by a host are ignored;
  the newest host version counts; the coordinate stays `<kind of the creator's>:<creator>:<d>`.
  A host's edit = a new version (keeps `status cancelled` while cancelled) + a 9002 when the
  name or picture changed.
- **Status.** NIP-32 kind 1985 in the group `[["h",g],["L","belfry/event-status"],["l",
  "cancelled"|"scheduled","belfry/event-status"],["a",coord]]`, content = a line for the guests
  (500), plus a new version with / without `["status","cancelled"]`. Reading: the latest label by
  a host wins when it is as new as the version, or when the version says scheduled.
- **Ticket types.** NIP-99 kind 30402 on the community relay `[["d","<d>:<id>"],["a",coord,relay],
  ["h",g],["title",…],["t","ticket"],["position",n],["summary",…]?,["quantity",n]?,
  ["closed"]+["prompt",q]?,["members"]?]`, no price in v1; latest per `d` by a host; deleted by a
  host's kind 5 `a` ending in `:<d:id>`. None: the implicit open ticket `<d>:free`. A closed ticket
  closes the group (9002 `closed`), the last one gone opens it.
- **People.** In the group, by time: 9000 with `ticket` puts a holder, a later 9001 takes them out
  (declined when out after the last decision); 9021 (`ticket`, `waitlist`?, content = answer) is an
  application until a decision or a 9022 after it. Going = holders ∪ accepted RSVPs, minus those
  whose RSVP says otherwise. A 9021 with `ticket` or `waitlist` is not a join of the group.
- **Where I stand** (first match): declined; holder (Maybe / Can't go by my RSVP, else going);
  applied / waitlist; my RSVP; host; none. API events add `me` from the answer.
- **RSVP.** 31925 as above plus `["ticket",d]` when one is held; a guest's (or one for an API
  event) goes on the groups relay with `["h",g]` and no `e`. "Still going?": my RSVP's `e` (else the
  version before it) differs from the current one in start, end or location.
- **Take / apply / decide.** Member + implicit ticket: RSVP. Else `POST <api>/events/<d>/join
  {"ticket"}` (codes `full`, `needs_application`, `members_only`, `cancelled`, `ended`,
  `no_ticket`, `too_many`, `relay`). Apply: a member sends the 9021; a guest signs it and `POST
  <api>/events/<d>/apply {"ticket","request":<signed 9021>}`. Decide: a member host 9000
  `[["h",g],["p",pk,"member"],["ticket",t],["paid","free"],["a",coord],["e",application]?]` /
  9001; a guest host `POST <api>/events/<d>/decide {"pubkey","action":"approve"|"decline",
  "ticket"?}` (403 `not_host`).
- **Read through the API.** `GET <api>/events/<link>` (NIP-98): 403 `members_only`, 404
  `not_found`; the answer has `d, coord, kind, group, link, url, title, description, all_day,
  start, end (ISO), tz, location, online, links (only to those in it), cover (the page's
  /cover), visibility, status, status_reason, hosts [{pubkey,name}], tickets [{d,id,title,
  summary,quantity,left,closed,prompt,members,position,implicit}], going, community, me`.
  Cached a minute. `GET <api>/events/slug?name=&event=` → `{free, suggestion, problem?}`.
- **Cards.** A message's cards = its `a` tags naming 31922/31923 (up to three) and the URLs of
  this belfry's events in its text, deduplicated by `d`; sending a text with such URLs adds
  `["a",coord,<community relay>]` for the ones known. An event's channels = every channel a
  non-deleted kind 9 with its `a` tag or URL is in (not its own chat). The older "📅 …" post
  with only the `a` tag stays the card alone.
- **Co-host link.** `POST <channel invites> {"channel":g,"role":"host","people":1,
  "link_days":1}`. Used by an account: it becomes an admin of `g`. Used by someone new: they join
  as a guest and the answer has `email_needed: true`; once their e-mail is saved and proven, the
  client posts `…/channel-invites/<token>/join` again with the account.
- **Lists.** Past = ends before today 00:00 local; Mine = not past and my state is not none /
  declined / can't go (includes hosting); Your events (drawer) = Belfry events I host or hold /
  ask a place at, ending at most 30 days ago.

### 19.19 Push notifications (collective-belfry `docs/push.md`, 2026-11-11)

The contract is `docs/push.md` in collective-belfry (server, gateway and both apps build against
it; the server is built, its *(built)* notes are folded in here); this section only says what a
client does. The same section is in the iPhone app's SPEC (19.19). Vectors: `vectors/push.json`
(made with the web's nostr-tools by `tools/vectors/push.mjs`; the server's payloads decrypt with
them).

- **Device key.** The device makes a random 32-byte key `K` per belfry and keeps it where its
  notification code can read it (iOS: the keychain group shared with the Notification Service
  Extension; Android: the Keystore-encrypted `Secrets`, read by the FCM service). `K` is used
  directly as the NIP-44 v2 conversation key (no ECDH).
- **Register** on every start once the person allowed notifications (never ask at launch: ask
  where it explains itself, e.g. a "Get notified" row the first time a channel opens, or Me →
  Notifications; Android: Me → Notifications or a push level picked in a channel's bell; the
  Android 13+ `POST_NOTIFICATIONS` permission): `POST <api>/push/devices` (NIP-98)
  `{"add":{"platform":"apns"|"fcm","token":<hex APNs token | FCM token>,"env":"production"|"sandbox",
  "key":<base64 K>,"app":"belfry-ios"|"belfry-android","name":<device model>}}` → `{"id"}`. The
  same token again replaces the old row (Android also registers on a new FCM token). The belfry
  drops a device whose pass closed, on a gateway 410, or after 60 days without a call.
  **Sign-out**: `{"remove":<token>}` before the session is revoked; forget `K`.
- **Payload.** APNs: `aps.alert` is a placeholder, `mutable-content: 1`, `aps.thread-id` =
  collapse, custom key `b` = base64 NIP-44 v2 ciphertext. FCM: data-only, `data.b`, high priority
  when urgent (dm, mention, reply, announcement; not pin, message). Plaintext:
  `{"v":1,"domain","type":"dm"|"mention"|"reply"|"announcement"|"pin"|"message","title","body",
  "channel"?,"event"?,"from"?,"url"}`; a client shows `title` and `body` (≤ 180), groups by
  `dm` / the channel, and ignores a payload whose `v` is not 1, whose `domain` is not the belfry
  the key belongs to, or that does not open (the placeholder stays). As built:
  - dm: `title` = the belfry's name, `body` = "New message", no `from` / `channel` / `event`
    (the belfry can't read gift wraps); `url` = `https://chat.<domain>/#/dm`.
  - pin: `title` = "📌 #channel", `body` = the pinned message's author and text; `event` and
    `from` are the pinned message's. announcement: `title` = "📣 #channel".
  - A thread reply's `url` is its thread, `#/c/<id>/thread/<root id>` (`event` = the reply's
    own id). `title` = the channel's label (`#name`, or a pod's / event's own name). A message
    without text says "📷 Photo" (or "…").
- **Channels / grouping.** Android notification channels: dm → Direct messages; mention, reply →
  Mentions and replies; announcement → Announcements; pin → Pinned; message (and an unknown type)
  → All messages. One notification per conversation (`dm`, else the group id).
- **Own copies.** NIP-17 senders also wrap a copy to themselves, which the belfry can't tell
  apart: a "dm" arriving within 30 s of this phone sending a DM or e-mail is not shown, nor one
  arriving while Messages or a conversation is on screen in the foreground.
- **Tap.** Only when `url`'s host is `chat.<domain>` of the signed-in belfry (and `domain` is
  that belfry): `#/c/<id>/m/<event>` opens the channel at the message, `#/c/<id>/thread/<root>`
  that thread, `#/c/<id>` the channel, `#/dm` Messages; anything else opens the app's home. Ids
  are checked (`[A-Za-z0-9_.-]{1,64}`, 64 hex). `url` is never shown.
- **Settings.** `POST <api>/notifications` `{}` reads, `{"set":{…}}` merges: `dms`, `mentions`,
  `announcements`, `pins` (booleans, default true; `mentions` covers replies too, `dms` covers
  e-mail threads) and `channels` = {group id: `all` | `default` | `mentions` | `off`} (absent =
  `default`). all = every message and thread reply plus what default notifies; default =
  mentions of me, replies to me, announcements, pinned messages (each subject to its switch);
  mentions = mentions and replies only; off = nothing. Me → Notifications shows the four switches
  and each of my channels' level; a channel's bell shows its push level next to the e-mail follow.
- A build without push (Android: no `google-services.json`) says so; the settings can still be
  changed (they apply to the person's other devices).

### 19.20 Signing in without a browser (collective-belfry `sessions.py`, 2026-11-12)

The default sign-in of the phone apps. Section 2 (the browser, OAuth code + PKCE) stays as the
second way ("Sign in with the browser instead") and is what the app offers by itself on an older
belfry. The contract is the module docstring of `services/onboarding/belfry_onboarding/sessions.py`.

- **Screen 1.** One field, the member's address `name@domain` (e-mail keyboard, no
  capitalisation, ≥ 16 pt). Accept it only as: trimmed, lower-cased, exactly one `@`, name
  `[a-z0-9][a-z0-9._-]{0,63}`, domain a hostname (section 1's rule). Fetch
  `https://<domain>/.well-known/belfry.json`; no document → "There is no belfry at <domain>."
  (the domain is the one typed and validated; nothing from an answer is shown).
- **Does this belfry have it?** belfry.json says so: `sign_in.sessions.api` is the full address
  of `…/session` (https only; e.g. `https://belfry.belfry.sh/api/v1/session`). Present → call
  that address (and `<that>/verify`). Absent → an older belfry: nothing is asked of it, the
  main button becomes the browser sign-in of section 2. A 404 / 405 without an `error` key
  from a belfry that does advertise it means the same (below).
- **Session key.** The app makes the NIP-46 client key *before* asking (a fresh 32-byte key)
  and keeps it with the address in secure storage until the sign-in ends: the same key signs
  the resend, the verify, and is the client key of the session afterwards (the belfry binds the
  connection to it when it creates it). Keep the pending request across a process death: the
  person leaves the app to read the e-mail. A new address, or `key_taken`, makes a new key.
- **Ask for a code.** `POST <sign_in.sessions.api>` (= `<api.base>/session`), NIP-98 signed **by the session key**, body
  `{"nip05":"name@domain","app":"sh.belfry.app://connect","client":"belfry-android"}` (iOS:
  `belfry-ios`; `client` names the session, `app` is the approved native app address).
  - 200 `{"state":"code_sent","to":"x•••@example.org","nip05":"name@domain"}` → screen 2. Show
    `to` only when it matches `^[^\s@•]•••@<hostname>$` (≤ 260 characters); otherwise say "your
    e-mail address".
  - `{"error": key}` → a fixed message per key: 404 `unknown` (no such member here, or the
    belfry answers for another domain), 409 `no_email`, 422 `invalid` (also `app_address`), 429
    `too_many` ("try again in half an hour"), 401 `unauthorized` (a wrong clock is the usual
    cause).
  - 404 / 405 **without an `error` key** (FastAPI's `{"detail":"Not Found"}`, a proxy's page):
    an older belfry → say so and turn the main button into the browser sign-in of section 2.
- **NIP-98 `created_at`.** The belfry accepts each signed event once. Use
  `max(last + 1, now)` for these calls so a resend or a retry in the same second is a different
  event.
- **Screen 2.** Six digit boxes over one numeric field (number pad; typing advances, backspace
  goes back; whatever is typed or pasted is reduced to its first six ASCII digits, so "123 456"
  fills them); the sixth digit submits. "Send a new code" repeats the first request with the same
  key and is locked for 30 s after each send. Back (the system's and a "Use another address"
  row) returns to screen 1 with the address still in the field. The request lives 15 minutes on
  the belfry (a code 10): after that the app forgets it.
- **Verify.** `POST <api.base>/session/verify` `{"code":"123456"}`, signed by the same key.
  - 200 `{"state":"open","bunker_url":"bunker://…","pubkey":"<hex>","npub":"…","nip05":"…"}`:
    accept only with `state` = `open`, a parsable bunker URL and a 64-hex `pubkey`. Connect as
    in section 3 with the session key, then ask the signer `get_public_key` (do not trust the
    answer's `pubkey` for that): it **must equal** `pubkey`, else disconnect, forget the request
    and show the `mismatch` message. Store the session exactly like section 2's. Keep the bunker
    URL with the pending request until the signer has answered: the code is spent, so "Try
    again" after a timeout reconnects without a second verify.
  - 422 `wrong_code` with `tries_left` (int): empty the boxes, "That code is not right. N tries
    left." (0 → "Ask for a new one."). 410 `expired` → screen 1 with a fixed note. 409
    `key_taken` → forget the key, screen 1 (the next try makes a new key). 409 `own_key` (the
    member really holds their own key, since collective-belfry 60c82bf: the app cannot sign
    them in yet), 409 `mismatch`, 403 `app_not_approved` → screen 1 with their messages. 429,
    no connection → stay on screen 2 with the digits and a "Try again" button.
  - 503 `signer` (60c82bf): the belfry's signer is not answering. The code is **spent**: stay on
    screen 2, empty the boxes, say "The belfry’s signer isn’t answering. Try again in a
    moment.", no "Try again" with the same digits, and unlock "Send a new code" at once (no
    30 s wait). The request and its key are kept, so the new code is asked with the same key.
    (Not the same as this phone failing to reach the signer *after* a 200: there the session
    is open and "Try again" reconnects without a second verify.)
- The server's `message` field is never shown. Guest links (19.4) are unchanged.
- Android: `core/net/NativeSignIn.kt` (the two calls, the checks), `Onboarding.requestCode /
  resendCode / verifyCode / pendingCode` (vault key `native-signin`), `ui/signin/SignInScreen.kt`
  (`SignInContent`, `CodeContent`, `CodeBoxes`); tests `NativeSignInTest`.

### 19.21 Web changes of 2026-11-11 / 12 as the Android app reads them (chat-source c6579a0)

Short, for the iPhone app to port; the web's code is the reference for each.

- **A to-do being worked on now.** Kind 2101 with `action` = `working` is a heartbeat (agents
  send one every 2 minutes while busy), `idle` ends it. Per person, the latest heartbeat counts,
  on the task it names, and only while that person is the task's assignee: `beat` = its time. A
  to-do `isWorking` when not done and `now - beat < 300`. The stretch began at the first
  heartbeat of the run (a `working` after `idle` / `done`, or more than 300 s after the task's
  last heartbeat, starts a new one): the ring fills clockwise with its minutes (6° a minute, a
  sliver at least), "1h", "2h" inside past an hour; the text says "X is working on it · 14 min"
  ("1 h 05" past an hour). No to-do mark on the message any more.
- **Drafts.** Unsent text per composer, on the device only: key `c:<group id>` (a channel),
  `t:<root id>` (a thread); `{text (≤ 20 000), picked: [[name, pubkey]], chans: [["#Name",
  "nostr:naddr…"]], at}`; older than 30 days = none; an empty box removes it. Not while editing a
  message.
- **A channel's shown e-mail address** = `slugify(name)` (lower case, NFKD without marks, runs of
  anything but `a-z0-9` → `-`, trimmed, 40) when exactly one channel has that slug or that id
  without its `-xxxx` tail (`-[0-9a-z]{3,8}$`); else the full id. Answers go out as the full id.
- **Visibility and the web page**: one NIP-78 event per channel on the groups relay, `d` =
  `belfry:web:<channel>`, `h`, `t` = `belfry-web`, content `{"on", "slug", "visibility":
  "public"|"members", "posts"?}`; the latest by a channel admin or by the key that signed the
  channel's first 39000 counts. A slug must be ≥ 2 characters, not one of the belfry's own paths
  (the web's `WEB_RESERVED`), not another channel's page.
- **Branding**: belfry.json `branding` `{logo, cover, primary, secondary}` (https addresses,
  `#rrggbb`; anything else = not set); `POST <api>/branding` (NIP-98, admins) with any of the
  four ("" clears) answers the four as saved; errors `not_admin`, `invalid`, `no_key`. Primary =
  the accent (text on it white or near-black by luminance > 0.4), secondary = count badges, the
  logo before the community's name, the cover (96 pt high) at the top of the channel list.
- **Staying connected**: when the app comes to the foreground, ask each relay something tiny
  (`{"kinds":[39000],"limit":1}`); no EOSE in 6 s = the socket is dead: drop it and reconnect.
  A live subscription the relay CLOSED (not a refusal, not `auth-required`) is opened again
  after 2 s. A publish without an answer drops the socket. A stalled outbox retries after 1.5,
  3, 6, then every 12 s. A signer request without an answer is asked once more on a fresh
  connection (10 s each). A queued item that was tried says "Sending again…" while the groups
  relay is connected, "Waiting for the connection…" when it is not.
- **The line and the pull-down**: the line's fallback is "N members"; its counts are events,
  proposals, pods, photos, then to-dos (done/total). The pull-down starts with the first
  non-empty line of the description (Markdown marks removed, 200 characters).

### 19.22 Engine rule: a snapshot built on the one before (Android)

`World(events, outbox, ctx, prev)`: when the log is `prev`'s plus only *everyday* events (kind
9 without `pod` / `proposal` / `a` tags or an event address, 7, 1111, a `seen:` 30078, 2100–2103,
all in an ordinary channel; 1059 and 4 anywhere), the outbox's unsent items are the same and the
context is equal, the per-channel views of the channels nothing arrived in, and profiles,
extras, calendar and proposals, are `prev`'s own objects. Anything else: everything is worked out
again. `World.feedKey(channel)` is equal across such snapshots for an untouched channel; the
channel screen draws its rows from the snapshot it has until the key changes. The result must be
identical with and without `prev` (`WorldCarryTest`: 400 random steps compared view by view).

### 19.23 The second pass of 2026-11-12 (chat-source c6579a0, collective-belfry 60c82bf)

For the iPhone app to port; the web's code is the reference for each.

- **Form drafts** (web `useFormDraft`, 5ccc71f). A new event, proposal, todo, channel and meeting
  keep their fields on the device until sent: store key `form:<key>`, value
  `{"at": <unix s>, "v": {field: text}}`, thirty days. Keys: `event:<visibility>`,
  `proposal:<parent>`, `todo:<channel>`, `channel`, `meeting:<channel>`. Not for an edit, not
  for a proposal made from a request. The fields as the form opened are not written; only what
  changes after. Sending forgets them.
- **An edit and the channel's draft.** Editing borrows the message box. When the edit is sent
  or cancelled, empty the box *while the edit is still open*, then end the edit: the draft's
  keeper is paused during an edit, so it never takes the emptied box for an emptied draft, and
  the channel's own draft comes back into the box.
- **"Todo"**, not "To-do", in everything a person reads ("New todo", "Turn into a todo", "Add a
  todo…", "made a todo", the Todos tab). The to-do mark on a message is gone (the ⏳ / ☑️
  reactions say it); the web's own code has no clock reaction besides ⏰ in the emoji picker.
- **Photo viewer.** Pinch zooms (1× to 5×), one finger moves a zoomed photo, a double tap zooms
  to 2.5× on the spot tapped and back, a pull down of more than 120 pt closes (the black fades
  with the pull). Zoomed: no page turn; a single tap shows the photo whole before it closes.
- **The pull-down's second level** (web ChannelHeader level 2, ChannelLine `full`). Three
  levels: closed, open, all the way. The handle steps closed → open → all the way → closed; a
  drag down goes a level further, up a level back; "More" and "All todos" go all the way; back
  goes one level up. All the way = the same summary, as tall as the feed: the latest 20 todos
  (newest first; open ones, then done ones; a done one can be reopened) with "Add a todo…",
  12 photos as a mosaic, up to 24 faces with names, then About in its compact form (the whole
  description when it has more than one line, Invite people · Settings, a pod's Leave / Join,
  the e-mail line, the web page, the id). Order of the summary: description, parent, pinned,
  coming up, to decide, to do, pods, photos, members and its links (All members, N files,
  Todos, Log, More), topics.
- **Channel info** (web ChannelInfo.tsx). Tabs About, Members, Todos, Files, Log. A gear in the
  top bar for the channel's admins opens the settings. About has no boxes: description;
  "**active/members** active members · Invite" (faces of the six most recently active; a tap
  opens Members); a pod: "Pod in <parent> · closed: apply, admins admit | open: any member
  joins", its question, Leave pod / Join pod; "✉️ Members can e-mail **<slug>@<domain>**: their
  mail shows as their message." (inbox on: "Anyone can e-mail …: mails land here as cards, and
  the channel's admins answer as the channel."); "Public | Members only · web page:
  <domain>/<slug>"; "Channel id <id>".
- **Log.** Every event with the channel's `h`, newest first, at most 1000, a section per day,
  the days as chips that jump. A line: an icon, **who** what, a short detail, the time; a tap
  unfolds the whole detail, the full date and "Go there". Who = the member's name, or the
  community's name for the key that signed the channel's 39000 (the relay). Words by kind
  (Android: `core/model/ChannelLog.kt`, tested line by line):
  9 → "posted a message" 💬 (with `pod`: "created a pod" ⬡; `proposal`: "made a proposal" 📄;
  imeta `m audio/…`: "posted a recording" 🎙; `image/…`: "posted a photo" / "posted N photos"
  🖼; other imeta: "posted a file" 📎; `mail-from`: "an e-mail came in" ✉️); 1111 "replied in a
  thread" ↩️; 1010 "edited a message" ✏️; 7 "reacted" (its emoji when ≤ 4 UTF-16 units and not
  "+", else 👍); 5 / 9005 "deleted something" 🗑 (`k` = 7: "took back a reaction"); 2100 "made a
  todo" ☑️; 2101 by `action`: claim "took a todo", unclaim "let go of a todo", done "marked a
  todo done" ✅, undone "reopened a todo", delete "deleted a todo" (working / idle: no line);
  2103 "commented on a todo"; 9000 "added someone" 👋 (a `p` with role admin: "made someone an
  admin"); 9001 "removed someone" 🚪; 9021 "asked to join" 🙋; 9022 "left" 🚪; 9002 "changed the
  channel" ⚙️; 9007 "created the channel" ✨; 1985 by its `l`: pinned / unpinned 📌 "pinned |
  unpinned a message", attended ✅ "checked someone in", absent "marked someone not there",
  saved "saved their account", member "became a member", cancelled "cancelled the event",
  scheduled "reopened the event", assigned "assigned a mail", done "closed a mail", open
  "reopened a mail", `L` = `#t` "changed the pinned topics", else "labelled something <l>" 🏷;
  30078 "changed a setting" ⚙️ (`d` = `seen:…`: no line); 31922 / 31923 "set the event" 📅 (`t`
  = meeting: "set the meeting" 👥); 31925 "answered going | can't go | maybe"; anything else
  "kind <n>" •. "Go there": the message in the channel (9, 1010, 7 and labels with an `e`), the
  thread (1111: its `E`), the Todos tab (2100–2103), the Members tab (9000, 9001).
- **Developer mode** (Me, for people who administer something; a flag on the device): in the
  Log a channel's admins get "Inspect the Nostr event": the event as indented JSON, and Copy.
- **Meetings** (web core/meetings.ts, ui/Meeting.tsx; 78d7424, 2215f2a, 6029788). ＋ → Meeting
  (not for guests). A meeting is a Belfry event (19.18) whose NIP-52 event also carries
  `["t","meeting"]`, `["channel",<the channel it was made from>]` and one
  `["p",<pubkey>,"","invited"]` per person invited; visibility `members`; no slug. Its chat
  `ev-<d>` is made `closed` (no ticket) and each member invited is put in it:
  `9000 [["h","ev-<d>"],["p",<pubkey>,"member"]]` by the maker. **Every later version of the
  event must keep those three kinds of tags** (an edit from the event form included).
  - Form: topic (≤ 200), people, when (the next full hour), how long (30, 45, 60, 90, 120 min;
    "45 min", "1 h", "1 h 30"), where (default: the place of my last meeting, else of my last
    event; chips: the last five meeting places; "Add a video link" = a Jitsi link from the
    topic). People: a grid of faces, the 12 first of: most recently active in the channel,
    then in its parent, then their members, never me; a tap picks or unpicks. "+" asks "Name or
    e-mail", lists up to six members whose name matches ("Already here:"), then asks for the
    other of the two (optional). An address alone takes the part before its @ as the name.
  - People who are not on the belfry: after the meeting's events are queued,
    `POST <api>/meeting-guests {"channel":"ev-<d>","name","email"?}` (NIP-98, a member who is an
    admin of that chat) → `{"pubkey","held"?,"member"?}`; the belfry puts them in the chat
    (a 9000 with `invited-by`, `name`, and `unsaved` when there is no e-mail). Tried up to six
    times, 1 s to 5 s apart, while the belfry answers `no_channel`, `not_host`, `signer` or
    there is no connection (the chat may not be on the relay yet). Then one new version of the
    event with their `p … invited` tags. Errors (fixed messages): 422 `invalid`, 403 `not_host`
    / `guest`, 404 `no_channel`, 409 `unsupported`, 503 `signer`.
  - On the event's page, under when and where: "People · N · M there": the maker, the invited
    and whoever answered; under each name "There", else their RSVP, else "Host", else
    "Invited", plus "· account held by you" for a name-only account I made (a guest join in
    that chat with `unsaved`, `invited-by` = me). A host sees per person a button "Confirm"
    ("Check in" from an hour before the start; "✓ There" once marked): kind 1985 in the chat,
    `[["h",<chat>],["L","belfry/attendance"],["l","attended"|"absent","belfry/attendance"],
    ["p",<pubkey>],["a",<coord>]]`; the latest label per person by a host counts. Hosts also
    add someone by name (the call above, then 9000 + a new version) and "Hand over" an account
    they hold: `POST <api>/meeting-guests/transfer {"pubkey","email"}` → `{"transferred":true}`
    (409 `not_yours` / `has_email` / `taken`).
  - Elsewhere: the page's subtitle "Meeting · members only"; rows say "Meeting" and "N people"
    (maker + invited + who answered) instead of "N going"; "Your events" includes meetings I am
    invited to; made from a channel's ＋, its URL goes into that channel's message box.
- **Audio in messages** (59c4b10). An attachment whose imeta `m` starts with `audio/` is a
  player in the feed: play / pause, position / length, one at a time, nothing fetched before
  play. The web records Opus in WebM (Chrome, Firefox) or AAC in MP4 (Safari): play both.
  Recording on Android: 19.25.

### 19.24 Several accounts on one device (Android, 2026-11-12)

Not in the web chat (a browser profile holds one account). For the iPhone app to port.

- **An account** is one identity in one belfry: id `<pubkey hex>@<domain>`. Two identities in
  one belfry are two accounts; so is the same person in two belfries. The demo is an account.
- **Everything is kept per account**, named after the account's *slot* (`""`, `a2`, `a3`…; a
  numbered slot is never given twice): the saved session (session key, bunker URL, community
  document, guest state) under `session` / `session@<slot>`, the push device key K under
  `push-key` / `push-key@<slot>`, and a store of its own (event log, outbox with its files,
  drafts, settings, last channel): `belfry.db` / `belfry-<slot>.db`. The list itself
  (`accounts`: current id, next slot number, and per account id, slot, domain, pubkey, and what
  the switcher shows: name, address, community name, picture, guest) holds no secret.
- **Migration.** A device with the one saved session of earlier versions gets a list of one
  account in the empty slot, whose names are the old ones. Nothing is moved or signed again:
  the person stays signed in, queued outbox items stay queued. An entry whose saved session
  cannot be read is dropped when it is opened.
- **Signing in** to an identity that is on the device keeps its slot and store (a new session
  key replaces the old); a new one gets an emptied store. It becomes the open account.
- **One account is live**: switching stops the open session (relays, subscriptions, signer
  connection) and starts the other from its store. A non-open account's outbox waits until it
  is opened. (A client may do better; say so.)
- **The switcher** is behind "you" at the drawer's bottom-left: the accounts (face, name,
  `name@domain` or "Guest · community" or the community's name; a check on the open one; a dot
  when a notification came for it since it was last open), "Add an account", "Me". Add an
  account = the sign-in screen over the app with a back arrow (address + code, browser, scan
  an invitation, demo); the open account is untouched until the new one is in.
- **Push.** Each account registers the device with its own belfry: the same APNs/FCM token, its
  own K. A payload is tried with each account's K; the one that opens it says whose it is (the
  tap rule of 19.19 then uses that account's domain). The notification carries the account id;
  a tap opens that account first, then the target. With several accounts the notification
  names the account (its address, else its community). Because a belfry keeps one owner per
  token, one account per belfry registers: the open one, else the first; the open account
  registers first, the others once per launch through a short-lived signer connection.
  Signing out removes that account's row; the token itself is deleted with the last account.
- **Links.** A guest link (`chat.<domain>/join/<token>`), opened or scanned: no account in that
  belfry → the join screen, adding an account ("Already have an account? Sign in" leads to Add
  an account and the link is offered after); one account there → it is opened if needed and
  offered "Join with your account?"; several → "Join with which account?", then the same. An
  event link opens in an account of its belfry (the open one first); none: a fixed message.
- **Sign out** signs out of the open account only (revoke, wipe its store, forget its session
  and K) and opens the next account in the list (the one after it, else the one before); the
  sign-in screen only when none is left.

**Scan an invitation** (same date). On the sign-in screen, a row opens a camera scanner. The
camera permission is asked there. Only a QR code whose text the guest-link parser takes
(`https://chat.<domain>/join/<token>`, `/#/join/` too; at most 512 characters) counts; it then
goes exactly where an opened link goes. Anything else: the fixed text "This isn’t a Belfry
invitation." for three seconds, scanning goes on. No text of a code is ever shown. Android:
CameraX + ZXing, no Play services.

### 19.25 Audio recording (Android, 2026-11-12; web 59c4b10 `core/recorder.ts`, `ui/Recorder.tsx`)

What is posted is the web's: a kind 9 message in the channel (or the event's chat) with content
`🎙 Recording · <clock>` (+ ` · N parts`), the files as attachments through the outbox (imeta
`url`, `m`, `size`, `x`, `alt <file name>`; the URLs appended to the content). Clock: `m:ss`,
`h:mm:ss`. File names: `recording-YYYY-MM-DD-HHMM[-partN].<ext>` in the device's time zone.

- **Format.** Record AAC-LC, one channel, 32 kHz, 48 kbit/s as ADTS (`.aac`): frames with their
  own headers, written as they come, so a file cut anywhere is whole up to its last full frame.
  Never record straight to MP4 (its index is written at the end; a cut file does not play).
  To post, make an `.m4a` (`audio/mp4`) from the frames without re-encoding, per part, in one
  go: `ftyp` (M4A / mp42 / isom), `moov` first (mvhd, one trak: tkhd, mdhd with timescale =
  sample rate, hdlr `soun`, smhd, dref, stsd/mp4a/esds with the 2-byte AudioSpecificConfig,
  stts one entry of 1024, stsc 32 frames a chunk, stsz, stco), then `mdat`. `tools/adts2m4a.py`
  is that writer; `core/src/test/resources/audio/` holds a sample and its expected output. If
  making it fails, post the `.aac` (`audio/aac`). iOS may record AAC in a CAF or ADTS file the
  same way, or use fragmented MP4, as long as a kill loses seconds only.
- **Parts.** 30 minutes each (56 250 frames; about 11 MB; Blossom takes 25 MB), each a file
  that plays alone. Up to three parts: one message. More: one message a part, content
  `🎙 Recording · <clock of the whole> · part K of N`.
- **Rules** (`core/audio/Recorder.kt`, `RecorderTest`): one recording at a time. Pause / resume
  by the person. An interruption (a call, an alarm: transient loss of audio focus) pauses and it
  resumes by itself when that ends, unless the person paused meanwhile. Another app taking the
  audio for good pauses until the person resumes. Not started under 50 MB free; stopped and
  kept under 15 MB. A microphone error stops and keeps. Each has a fixed text (`RecNotice`).
- **Keeping it going.** Android: a foreground service of type `microphone` started while the
  app is in front, with Pause / Resume and Stop in its notification, and a partial wake lock.
  The microphone permission is asked at Start. iOS: the `audio` background mode.
- **At the end** (not in an event's own chat, `ev-…`, where it is posted at once): "Which event
  or meeting was it?": Belfry events not cancelled that start within 12 h and ended less than
  36 h ago, the one going on first, then the nearest, six at most; or the channel it was
  started in. (The web also offers "New meeting…"; Android does not yet.)
- **Left on the device.** Each recording has a small state file (id, account, channel, start,
  recorded ms, state). One not posted is offered in the chat, above the message box: "An
  unfinished recording · <clock> · <day>", "Post it" / "Discard" (Discard asks). One whose
  state still says recording was cut short: its length is measured from its frames. A
  recording is deleted from the device only once the outbox holds its files.


### 19.26 Guest mode: opening an invitation reads the channel at once (2026-11-12)

(collective-belfry `docs/guests.md` "Opening it: guest mode", `guests.py`; web village-app
b2e968b `core/guestmode.ts`, `core/guestjoin.ts`, `ui/GuestJoin.tsx`.) Replaces the name screen
of 19.4 for people who have no account on that belfry. Joining with an account (19.4, 19.24's
"which account?") is unchanged.

- **Opening the link, no account here.** No screen asks anything. The device makes its session
  key (one per invitation, kept until the join succeeds: a retry signs with the same key and the
  belfry answers with the same guest) and sends `POST <api.channel_invites>/<token>/join` with
  body `{}` (NIP-98 by that key). 200 →
  `{"channel","new":true,"guest":true,"reading":true,"bunker_url","pubkey"}`. The session's
  pubkey is the answer's `pubkey` (do not ask the signer for it first). `reading` absent or
  `true` → guest mode; `false` (a belfry that still names guests) → a plain unsaved guest.
  Errors by code, fixed texts: 410 `closed` "This invite can’t be used any more. Ask for a new
  one.", 410 `for_agent` "This link is for an AI agent, not a person.", 409 `unsupported` "This
  belfry does not take guests.", 422 `name_needed` (an older belfry) "This belfry is being
  updated. Try again in a few minutes.", 409 `session_taken`: forget the key, make a new one and
  send once more (then give up). The invite's info (`GET …/<token>`) is read alongside for the
  loading line and, when the join fails, to say why (`state` used / expired / revoked → its
  fixed text, 19.4).
- **One loading screen** from the moment the link is read until the channel can be drawn in
  full: a spinner and "Opening # <channel_name>…" ("Opening…" until the info is in). It stays
  while the session is made and until the groups relay has sent its stored events (EOSE) or
  refused the key, at most 10 s after the session exists. Nothing else is drawn in between: no
  empty channel ("… is new · Invite people"), no home. A failure replaces it with the fixed
  "This invite can’t be used" screen (Try again when not closed, "I have an account: sign in",
  Close).
- **Guest mode** (the device's guest record says `reading`; kept with the saved session): the
  channel shows; where the composer would be, a bar "**Guest mode** · You’re reading
  # <channel> as a guest." with **Join**. The thread screen and inline threads show the same bar
  (no reply box). An empty channel says "No messages here yet." (no Invite button).
  **Nothing is sent**: every write (messages, replies, reactions, edits, RSVPs, votes, to-dos,
  profile, invites, follows, the first channel of an empty belfry, any belfry API call that
  changes something) is refused on the device where events are queued and API writes are made,
  and opens the Join sheet instead. No `seen` mark (19.21 / 11) is sent. Allowed: reading,
  `GET`s, push registration, `/guest/save`, `/guest/save/verify`, revoking the own session,
  using another link. Long-press / "add a reaction" on a message opens the Join sheet. The
  relay does not enforce this: it would store what such a key signs.
- **The Join sheet** ("Join # <channel>"): "Your name and e-mail address, and you can write
  here. We’ll send a 6-digit code to your e-mail to confirm it." Fields name (required, ≤100,
  spaces folded) and e-mail (required, `\S+@\S+\.\S+`), Continue, then "Already have an account
  on <domain>? Use its e-mail address." Continue → `POST <api>/guest/save {"email","name"}` →
  202. The code step: title **"Check your e-mail"**, "We sent a 6-digit code by e-mail to
  <the address as typed>. It can take a minute.", six boxes (the sign-in's), the sixth digit
  sends `POST <api>/guest/save/verify {"email","code","name"}` → 200
  `{"saved":true,"joined":bool}`; the guest record becomes saved and not reading, the bar goes
  and the composer is there (the channel then gets the guest's second 9000 with `name`: the join
  line). Under the boxes "Checking the code…" / the error / "Not there? Look in your spam
  folder."; "Send a new code" (locked 30 s after each send; "A new code is on its way to your
  e-mail.") and "Use another address" (back to the fields).
- **Errors** (never the server's text): `invalid` "That doesn’t look like an e-mail address.",
  `name_needed` "Your name is needed to join.", `too_many` "Too many codes for now. Try again in
  half an hour.", `wrong_code` with `tries_left` (int, collective-belfry d2b4c52) "That code is
  not right. N tries left." / "… 1 try left." / (0) "That code is not right. Send a new one.",
  without it "That code is not right. Check the e-mail and try again." (boxes emptied), `expired` "That code has expired. Send a new one.", `not_guest` "This guest access
  has ended. Ask for a new invite.", no connection "No connection. Try again.", else "That did
  not work. Try again.". 409 `taken` (body `{"error":"taken","moved":bool,"nip05"?}`; `nip05` =
  that account's name@domain when the belfry can read it, d2b4c52): a step titled
  "You have an account here": "<address> already has an account on <domain>." + (moved) "Sign
  in with it: # <channel> is waiting there." / (not moved) "Sign in with it instead." and
  **Sign in instead** (+ "Use another address" when not moved). Sign in instead: the guest
  account is dropped from the device (its session revoked unless `moved`, where the belfry closed
  it already), the native sign-in (19.20) starts, and once signed in the channel opens. The
  native sign-in takes `name@domain`: its address field gets the answer's `nip05` when that is a
  `name@domain` of this belfry (lower-cased), else the typed e-mail when it is one; otherwise the
  field is empty with the fixed hint
  "Sign in with your address here (name@<domain>), or with the browser using the e-mail you
  typed." (The web's browser sign-in takes the e-mail itself.)
- **The save-your-account bar** of a guest who has a name but no e-mail (19.3: after their first
  message / reply / reaction, or a co-host link) opens the same sheet without the name field:
  title "Save your account", "Add your e-mail address to come back from any device. We’ll send a
  6-digit code to it.", body `{"email"}` / `{"email","code"}`. Done: "Saved. Sign in with
  <address> on any device." The bar is never shown in guest mode.
- **Other people's reading guests** (how everyone else's client reads the events). A key is
  **reading** in a channel when its latest kind 9000 there (in time order) carries
  `["reading","1"]`; a later 9000 without it, a kind 1985 `L belfry/guest` with `l saved` or
  `l member` for that `p`, or a 9001 ends it. For reading keys (except me): no join line ("…
  joined as a guest", 19.3), not in the channel's members (also when the relay's 39002 lists
  them), member counts, "active this month", the channel line's faces or mention / invite
  pickers. The Members tab adds one line "1 guest is reading" / "N guests are reading: they
  opened an invite link and have not joined yet." The join line comes from the later 9000
  without `reading` (which carries the name). A guest put in with a name and `unsaved` (a
  meeting's held guests, older links) never carries `reading` and is **not** hidden.
- Vectors: `vectors/guestmode.json` (events → reading keys, members, join lines per channel).

### 19.27 Read state across devices (kind 30078, `d` = `belfry:read`, 2026-11-15)

(web village-app branch `readstate`: `core/readcore.ts` = the pure rules below, `core/readstate.ts`
= the I/O.) What a person has read — per channel, per thread and per direct-message / e-mail
conversation, and how far the @Mentions list was seen — is the same on all their devices. There
is no NIP for read state (2026-11): like other clients (Jumble, Discordia…) Belfry uses NIP-78
app data, encrypted to oneself. Nobody else learns what you read. Not to be confused with the
public presence mark `seen:<channel>` (19.21 / 11, "N active members"), which is unchanged.

- **The event.** One replaceable event per person: kind **30078**, tags `[["d","belfry:read"]]`
  (+ the client tag), `created_at` = now (at least one more than the newest `belfry:read` event this
  device has seen), content = **NIP-44 v2** of the JSON below with the conversation key of the
  person's own secret and own pubkey (through the signer: `nip44_encrypt(own pubkey, json)`;
  Keycast allows it, the DM self-copies do the same). Published on the **community relay** (the
  members-only relay, NIP-42) by members and agents. Read with
  `{"kinds":[30078],"authors":[me],"#d":["belfry:read"]}` — always the exact `d` (other clients'
  bugs came from filtering by kind and author only). Ignore an event not by me, with another `d`,
  or that does not decrypt / parse to an object.
- **The JSON** (`v` 1): `{"v":1,"s":<unix s>,"m":<unix s>,"c":{"<group id>":<unix s>},
  "t":{"<thread root event id>":<unix s>},"d":{"<conversation key>":<unix s>}}`. Every number =
  read **up to and including** that second. `c` keys = the channel's group id (`h`), any kind of
  group (channel, pod, event chat, proposal); `t` keys = the id of the message a NIP-22 thread
  hangs off (`E`); `d` keys = the conversation key exactly as SPEC's direct messages and
  `vectors/mail-threads.json` name it (`n:<sorted pubkeys>`, `m:<address>|<subject key>`); `s` =
  the start (below); `m` = the @Mentions list seen up to. Keys over 200 characters are not stored.
  Unknown top-level fields are kept as they are (a newer client's), never dropped. Canonical text
  (so equal states give equal content): unknown fields first (keys sorted), then
  `v, s, m, c, t, d`, map keys sorted, no spaces (`serialize` in the vectors).
- **Reading it.** `floor(sec)` = now − 90 days, and for `c`, `t` and `m` also at least `s`
  (`s` does **not** apply to `d`: direct messages had per-conversation marks before this).
  `readUpTo(sec, key)` = max(the key's mark, floor). Garbage in a field (negative, not a number,
  a string, null, an array where a map belongs) is dropped; fractions are floored.
- **What is unread** (every client counts the same things):
  - channel `c[h]`: its top-level messages (kind 9 with that `h`, not deleted, the feed's own
    rules) by someone else with `created_at` > readUpTo. Thread replies do not count here.
  - thread `t[root]`: its replies (kind 1111, `E` = root, not deleted) by someone else after
    readUpTo, shown only when I am in it: I wrote the message or a reply, or `t[root]` holds a
    mark above its floor (I opened it before).
  - conversation `d[conv]`: its messages and e-mails (kind 14 / 15 / 4 / 1301 items) by someone
    else with their time (`at`) > readUpTo. **Reactions in direct messages (kind 7 / 5 rumors)
    never count and never move a mark.**
  - a mention is new when its `created_at` > max(readUpTo(`m`), readUpTo of its channel for a
    kind 9 / of its thread for a 1111).
- **Marking.** Only forward (`mark` never lowers a number). A channel on screen, app in the
  foreground, scrolled to the newest message: `c[h]` = the `created_at` of its newest kind 9 (mine
  included); again when a new one arrives while it stays so. A thread on screen: `t[root]` = its
  newest reply's `created_at` (the root's when there is none). A conversation on screen: `d[conv]`
  = its newest item's `at`. Opening the @Mentions list: `m` = the newest mention's `created_at`.
- **Merge.** Every number (`s`, `m`, each mark) is the highest of the two; unknown fields: the
  relay copy's, then the local one's. Commutative; an older event arriving late changes nothing.
  Every `belfry:read` event received (any order, any device) is merged into the local state, which
  the device keeps across restarts.
- **Publishing.** Never before this device has asked the relay this session (the request above
  answered with EOSE, whatever it held): a new device must not overwrite what others marked. After
  that, publish `prune(merge(local, newest remote))` when `covers(newest remote, local)` is false
  ("newest remote" = the received event with the highest `created_at`; nothing yet = the empty
  state). Debounced: 3 s after the last change, at most one every 10 s; flushed when the app goes
  to the background / the page is hidden. Through the outbox (it survives offline); a newer
  read-state item replaces one still waiting there.
- **Pruning** (before every publish): drop marks at or under their floor; then, while the
  entries weigh more than 40 000 (each = UTF-8 bytes of its key + 16), drop the oldest `c` / `t`
  marks first (ties: `c` before `t`, then key by code points), raising `s` to the newest one
  dropped (so nothing dropped turns unread), and only then the oldest `d` marks. `s` and `m` stay.
- **The start `s`, migrating.** First run with no stored state: `s` = now; this device's old
  marks are seeded in (the web's per-conversation DM marks → `d`, its "@Mentions seen at" → `m`;
  Android's DM marks → `d`), then merged with whatever the relay holds, then published if the relay
  lacked any of it. Channels had no marks before, so they start read (no flood of old unread).
- **Guests** (and guest mode) cannot use the community relay: their read state stays on the
  device (same rules, never published). The groups relay was not used: an event there needs an
  `h` (one channel's), is readable by that channel's members and would be lost with that channel.
- **Notifications.** When a mark arrives (from this or another device), badges and counts follow
  at once; the apps also remove their delivered notifications of that channel / conversation /
  thread whose event time is ≤ the new readUpTo. The belfry's push watcher does not read this
  event (it cannot decrypt it): a push for something already read elsewhere can still arrive.
- Vectors: `vectors/readstate.json` (merge of two devices both ways, out of order, unknown fields,
  parse, serialize, readUpTo, mark, prune by age and by weight, covers → publish or not).


### 19.28 The iPhone app's look and the web's latest (iOS, 2026-11-15)

Not protocol; how the iPhone app follows the web chat's phone layout, for anyone comparing.

- **Drawer.** ☰ (count: unread DMs + new @mentions + requests) or a swipe from the left edge
  (at the root of the screen's stack) slides the page right and shows the channel list under it:
  the belfry's name (its admins: opens Belfry settings), its logo and cover (`branding`), search
  over all channels, Requests (when something waits or I decide things), Upcoming events,
  @Mentions, Direct messages, Public channels and Members only in the admins' order
  (`belfry:channel-order`), groups nested under the channel they were made in, Your events, and
  me at the bottom (Me). The page dims; a tap on it or a drag to the left closes.
- **Pull-down.** The channel's one line pulls down from the top (short level, full level; the
  handle follows a finger and snaps; up past the short level or a tap outside closes). The
  description comes first, then the visibility line.
- **Composer.** iMessage's shape: a ＋ circle the height of the field on its centre line, the
  field a capsule growing to six lines with Send inside it. ＋ is the system menu with icons.
- **Message menu.** Holding a message opens the system's context menu: four quick reactions and
  More in a row, then the web's message menu; Delete opens the confirmation showing the message.
- **Look.** Neutral (ink on white, near-white on black) unless `branding.primary` is set: then
  that colour tints buttons and links, with white or near-black text on it (luminance > 0.4).
- **Group page, Requests, Roles, Membership, Branding** follow collective-belfry
  `docs/membership.md`, `docs/roles.md`; vouches in groups are kind 1985 `belfry/vouch`
  (core-tested).


### 19.29 The event screen, tickets, check-in and roles in a group (2026-11-15)

The belfry's contract of 2026-11-15 (collective-belfry `services/onboarding`, events.py; built
on the server in parallel), Xavier's request: one screen per event with its chat, photos and
people; tickets with a QR; checking people in at the door; roles per channel, group and event.

- **Roles in a group.** `admin` = NIP-29 admin (39001; in an event the creator is "Host", other
  admins "Co-host"). `steward` (events only: checks people in, nothing else) = kind 1985
  `["L","belfry/group-role"]`, `["l","steward"|"none","belfry/group-role"]`, `["p",<key>]`,
  `["h",<group>]`, `["by",<who asked>]` **signed by the groups key** (`_groups@domain`); the
  latest per `p` wins, labels by any other key are ignored, an admin is never a steward.
  `member` = everyone else ("Attendee" in an event). Changing one: `POST <api>/group-roles
  {"channel","pubkey","role":"admin"|"steward"|"member"}` by an admin of that group or a belfry
  admin → `{"role"}`; errors `not_allowed` (403), `not_in_group`, `last_admin`,
  `guest_not_saved`, `agent`, `events_only`, `always_admin` (409: the creator and the belfry's
  admins stay admins), `invalid` (422), `relay` (503). `"role":"removed"` takes them out of the
  group (an older belfry: 404 → the client's own 9001). Clients read roles from the relay.
- **A group's admins** (the server's change 3; web branch `checkin` model.ts): the relay's latest
  39001 (zooid lists only its own keys, the belfry's admins and the group's creator, never a
  co-host) ∪ the creator (first 9007) ∪ admins by role: per person the latest 9000 / 9001 for
  them in the group; a 9000 whose `p` tag has `admin` third → admin, a later 9000 tagged
  `["role","member"]` or a 9001 → not (a ticket's 9000, without `role`, changes nothing).
  "Co-host" / "Admin" in lists = such an admin who is not one of the keys that administer every
  group (the belfry's admins, the relay's key = the 39000's author, the groups key). May check
  people in: host, co-host, steward, any admin of the group, a belfry admin.
- **Free events**: on an event without ticket types a member's own going RSVP holds `<d>:free`
  (a code, in the check-in list).
- **Tickets.** Everyone holding a ticket (9000 with `ticket` in `ev-<d>`) has a code: 16
  lowercase base32 characters, the same every time. `GET /events/<link>` has in its viewer
  block (`viewer`, else `me` of events v1; both are read, `viewer` wins): `ticket_code`,
  `ticket_url` = `https://chat.<domain>/events/<link>/t/<code>` (what the QR holds; used only
  when it is https and ends with `/t/<code>`), `checked_in` (unix time or null), `roles`
  (subset of host, cohost, steward), `may_check_in`. Kept on the phone per event (offline:
  the QR shows without a connection). An older belfry sends none of these: no ticket button.
- **Check-in.** `POST /events/<d>/attendees {}` → `{"people":[{"pubkey","name","ticket",
  "ticket_title","rsvp","checked_in","role"}],"going","checked_in"}` (hosts, co-hosts,
  stewards); `POST /events/<d>/checkin {"code"}` or `{"pubkey"}`, optional `"undo":true` →
  `{"person":{…},"already"}`; `no_ticket` (404, also another event's code), `not_allowed`
  (403). The belfry records it as kind 1985 `["L","belfry/ticket"]`,
  `["l","checked-in"|"not-checked-in","belfry/ticket"]`, `p`, `h`, `by`, signed by the groups
  key; the latest per `p` wins. The list is drawn from the last answer with newer labels laid
  over it (every checker's phone updates live), else from the ticket holders on the relay.
  A 404 without one of these codes (or a 405) means the belfry is older: "This belfry needs an
  update."
- **A scanned text** counts only when it is an http(s) URL whose path (or fragment) ends
  `/t/<16 base32>`; only the code is sent, nothing of the text is shown. The same text is
  ignored for 3 s. Results: green "<Name> · <ticket> · checked in", amber "Already checked in at
  14:02", red "Not a ticket for this event" (2 s, with the success / warning / error haptic).
- **The link** `https://chat.<domain>/events/<link>/t/<code>` (also after `#`): the event
  screen; for those who may check in, a card on top: who it is, the ticket and whether they are
  in, asked with `"peek": true` on `/checkin` (nothing recorded by opening a link), then "Check
  in" → "Checked in at …" with Undo ("Already checked in at …" when they were). An older belfry
  without peek: the card shows "Check in" only. The scanner checks in at once.
- **"Check people in"** shows on the header from 00:00 of the event's first day to the end of its
  last day (the device's zone), for host, co-host and steward (the API's `may_check_in`, else
  the relay's roles); always in the screen's menu and the People tab for them.
- **The event screen** (iOS): pushed from Events, a card, a universal link and Your events. The
  header has three levels with the channel pull-down's handle: half (opens there: cover, title,
  when, where, my status as the main control, who is going), up (the bar names the event and
  its date, the content takes the screen; typing in the chat goes up), full (hosted by, asking,
  going, maybe, the description, the address with Share, the hosts' tools). Under it Chat ·
  Photos · People: the group's feed and composer; its photos newest first with "Add photos" (one
  message through the outbox); hosts / going / maybe with a message button. Someone not in the
  group (no ticket yet; a guest reading through the API) sees the header and the description only.
- **A group's metadata** (name, description, picture, access): a client publishes its own 9002
  only when the relay lets it (in the latest 39001, the creator, a belfry admin), and then carries
  every other tag of the current metadata (the relay replaces it all: a 9002 with only a name
  dropped closed / prompt / vouches / picture); otherwise `POST /group-meta {channel, name?,
  about?, picture?, closed?, prompt?, vouches?}`. Accept / Decline of a closed group's 9021 only
  for those the relay lets moderate (an admin by role vouches); an event's applications go
  through `POST /events/<d>/decide` for co-hosts by role.
- **People lists** (channel Members, a group's page, an event's People): face, name, the role
  there as secondary text when it isn't the default; no label editor. A tap pushes the profile,
  which shows "In <group>" with the role there: a picker (Member / Admin; Attendee / Co-host /
  Steward) for that group's admins and belfry admins, saved with `/group-roles`, and "Remove
  from <group>" (9001). The Host is never changed there.
- Core-tested in `GroupRolesTests` (stewards, the groups key only, latest wins, admins replace,
  check-ins, event days, scans, the viewer block, the three calls against a stub belfry).

### 19.30 Deleted and archived groups (the belfry's contract of 2026-11-16)

Xavier's request, after a card in #demo on belfry.sh ("⬡ New pod: Canal", group
`pod-canal-41ec`, about which the groups relay returns nothing at all) opened "This group isn't
here yet. It shows once the relay sends it.": a deleted group says so and can't be tapped; a
link to it says so; and a group can be **archived** as a softer way to delete. "Group" here is
anything with a NIP-29 group id that people open from the channel list or a card: channels and
groups (ids `pod-…`). Events and proposals keep their own cancel / status mechanisms.

- **Deleted.** A group is deleted when (a) a kind 9008 for it is known, or (b) the groups relay
  was asked for its metadata and answered that it has none. Until the relay answered, a group
  nothing is known about is **unknown**: its card shows as an open one's (the name from the
  card), never "deleted" on a guess, and the sentence "It shows once the relay sends it" is gone.
  - (iOS) How (b) is decided: after each EOSE of the groups relay's live subscription (at most
    once a minute), and when a card or a link names a group this device knows nothing about, the
    app asks `{"kinds":[39000],"#d":[general, <up to 100 ids>]}` for the channels and groups it
    knows (not events, not proposals, not one it is still creating) and the groups its cards
    name. An answer counts only when it names `general` (always asked along, always there: a
    timeout, a refused or an empty answer proves nothing). Each asked id the answer does not
    name is marked absent with the newest 9007 / 9002 / 39000 this device held for it (0: none);
    one it names loses the mark. A marked group is deleted until something newer about it
    arrives. The marks are stored (`groups-absent`), so the state holds offline. This also
    catches a group deleted while this device was away and whose 9008 the relay did not keep
    (the device still holds its old metadata). Never asked as a guest (the relay shows a guest
    only their own channels: absence proves nothing) and never in guest mode. `general` is never
    deleted by absence.
  - The card in the parent: the group's name if still known (from the card's text "⬡ New pod:
    <name> · …" / "⬡ New group: …", else "A group"), "This group has been deleted", greyed, not a
    button (no chevron). The card is never removed with the group.
  - A route to a deleted group (the channel, its page, a link, a notification, a search result,
    a `#` reference): a plain screen "This group has been deleted" with the way back. No
    composer, no pull-down, no settings.
  - A deleted group is in no list (channel list, pickers, `#` references, @Mentions, search).
- **Archived.** `["archived","1"]` on the group's kind 9002, read like `prompt` / `vouches`: the
  latest 9002 that says anything about it wins; `["archived","0"]` unarchives. Every 9002 keeps
  the group's other settings (name, about, picture, closed, prompt, vouches) and, while it is
  archived, `["archived","1"]`. `general` is never archived (a tag on its 9002 is ignored; the
  belfry answers `always_open`).
  - Who: whoever may edit the group (its admins, admins by role, the belfry's admins). Those the
    relay lets edit (its 39001, the creator, a belfry admin) publish the 9002 themselves; an
    admin by role sends `POST <api>/group-meta {"channel","archived":true|false}`. While
    archived the belfry refuses every other change with 409 `archived` (`/group-meta`,
    `/group-roles`, `/channel-invites`, `/channel-guests`). A belfry from before archiving
    answers ok and changes nothing: (iOS) the app looks for the change for 5 s (or trusts an
    `archived` in the answer), then says "This belfry can't archive yet: it needs an update."
  - An archived group is in no list (channel list, pickers, `#` references, groups of a
    channel, requests, the drawer's counts) but still opens from its card, a link and search:
    everything is readable. Under the header: "Archived · read only" with Unarchive for those
    who may. No composer, reactions, replies, edits, to-dos, pins, invite, follow, roles or
    settings. Its card in the parent: name, "Archived", greyed, still a button (it opens the
    group read only).
  - Archive sits in the group's settings with a confirmation: "Archive <name>? It leaves the
    channel list and nobody can post in it. You can unarchive it later."
  - A row "Archived" at the very bottom of the channel list, only while there is at least one I
    can open (one I am in or run; for a member every archived channel), opens a screen listing
    them; a tap opens the group read only.
  - The relay enforces none of this: an older client can still post in an archived group, and
    those messages show. (iOS) The app itself queues nothing for an archived or deleted group
    (`GroupLock`: the last check in the outbox; only the unarchiving 9002 passes), whatever a
    screen forgot to hide.
- (iOS) A group's page (its card, a request) is a screen pushed on the main stack, never a
  sheet; an archived group's card pushes the group itself, read only.
- Core-tested in `ArchiveTests` (deleted by 9008; unknown until the relay answered, then
  deleted; a stale copy; coming back; one being created; archived by the latest 9002, unarchive,
  `general`; the 9002 keeps every setting; the outbox lock; the card's name; the questions).

### 19.31 Five places, on-brand names, three roles (iOS, 2026-11-16; the owner's canvas "One belfry, five places")

Layout first (each client draws it its own way), then the parts other clients must agree on.

- **The places.** A bottom bar: Home · Calendar · Create (＋) · Groups · Town. Home = what needs
  me today on the open belfry (requests for stewards and vouching members, a new agreement of
  the charter, to-dos on me, new mentions, unread direct messages, events of the next 7 days I'm
  part of or invited to, unread channels and groups); Calendar = My calendar (hosting, going,
  maybe, applied) · Invited (a meeting's `invited` with no answer) · Discover (the rest); Groups =
  My groups (pods I'm a member or admin of) · Active (mine with a message in the last 30 days) ·
  All (every listed pod; not mine: Join when open, "by application" when closed); Town = the
  Square and a drawer with only the town's places; Create = Message, Event, Meeting, To-do, Group,
  Proposal into a chosen channel (default: the one on screen, else the Square). Rows never carry
  action buttons for events: the state in words ("you host · check-in today", "going",
  "invited · not answered yet"). Chats, events and profiles push over the places; the bar shows
  only at a place's root. Guests: Home · Calendar · Town.
- **The drawer's sections** (by group id): The town = `general`, Members (the members and their
  requests; called "the Gates" until 2026-11-17), the Charter (19.32: shown when the `charter`
  group exists, or to stewards); then the categories of 19.34 (without them: Building together =
  `proposals` and `website`; Community life = every other channel). The stewards' order
  (`belfry:channel-order`) holds inside each section. Pods are not in the drawer (their own
  place); the `charter` group is never listed as a channel. (iOS, 2026-11-17) Above The town a
  row **Today** opens Home; Home has the same ☰ and drawer. The drawer's header has a gear for
  Belfry settings (stewards); each category's header a ＋ for a new channel in it.
- **On-brand names** (display only; ids, mail addresses, links and wire values never change):
  `general` → "Square", `events` → "Fairground", `proposals` → "Assembly", `website` →
  "Scriptorium", only while the group's own name is still its default (empty, its id, or `#` +
  id, any case: a renamed channel keeps its name). The members' directory with its requests is
  called "Members" (it was "Gates" until 2026-11-17). (iOS) `WorldContext.placeNames` = true in the app, false in the shared vectors;
  `PlaceNames` in `Model/Charter.swift`.
- **Three roles** as people read them: Steward (the built-in `admin` role, the stewards group) ·
  Member · Guest. A belfry's `GET /roles` label "Admin" for the `admin` role is shown as
  "Steward"; a custom label stays. The per-event / per-group check-in role (wire value `steward`
  in the `belfry/group-role` label and `POST /group-roles`, 19.29) is shown as **Door**.
- **Add an agent** (19.16): on my own profile, members only (not guests, not agents): a short
  explanation and "Add an agent", which makes a one-agent link (`POST /api/v1/channel-invites`
  `{"channel": "general", "agent": true}`) and shows one line to paste to the agent:
  `Join <belfry name> as my agent: read <agent_url> and follow it.` (`agent_url` from the
  answer, https only, never built from typed text). With my agent there (one per member by
  default: `POST /agents {}` → `limit`), the section shows it with "Remove this agent…" instead;
  stewards (unlimited) see "Add another agent".

### 19.32 The charter (iOS, 2026-11-16; `vectors/charter.json`)

A short list of agreements that every person ticks for themselves. It reuses the to-do list
(19.8, 19.11) of one NIP-29 group so no new kind is needed.

- **Where.** The group `charter` on the groups relay (a fixed id), made by the steward who writes
  the first agreement: 9007 `["h","charter"]`, then 9002 `["h","charter"],["name","Charter"],
  ["about",…],["open"]`. Clients never list it as a channel or group.
- **An agreement** = kind 2100 in that group: content = its title (trimmed, ≤ 300 UTF-16
  units), tags = the to-do list's `["t", <list id of "charter">]` (sha256hex("osv-village:charter")
  [:16] = `d93ecde52eff27c7`), `["h","charter"]`, plus `["each","1"]` (a to-do each person does
  for themselves), `["order","<int>"]` and an optional `["about","<one line ≤ 200>"]`. Only a
  2100 by a **belfry admin** (belfry.json `admins`) with `each` = 1 counts.
- **A change** = kind 2101 `["e",<agreement>],["action","edit"]` + the same `t`, `h`, `each`,
  with content = the full title and the full `about` / `order` tags (latest wins; a missing
  `about` empties it; a missing `order` keeps the place). Only a belfry admin's edit counts. If
  the title or the explanation changed, that edit's `created_at` becomes the agreement's
  **text time**. A reorder alone changes nothing else. **Removal** = 2101 `action` `delete` by a
  belfry admin (not only the author, unlike ordinary to-dos).
- **A person's agreement** = their own 2101 `action` `done` (and `undone` to take it back),
  same tags. Counted **per author**, latest tick wins (an ordinary to-do's `done` is once for
  everyone; here it never is). A person **has agreed** when their latest tick is `done` and its
  `created_at` ≥ the text time; a `done` older than the text time is **stale**: that person is
  asked again, for that rule only (the owner's decision).
- **Order**: `order` ascending (none = last), then `created_at` of the 2100, then id.
- **What members see.** The charter page: "You agreed · <date>" (the newest of my current ticks,
  once all are ticked), else how many wait; each rule with my tick and "N of M members" (people
  in `general` who are not guests or agents). Stewards also: New agreement, edit (changing the
  words says it asks everyone again), reorder or remove, and a count opens Agreed / Not yet. A
  rule I haven't agreed to is highlighted ("NEW", or "CHANGED" when my tick went stale) and Home
  shows "1 new agreement to read" until I tick it. A new or changed rule never stops anyone from
  posting (the owner's decision): a reminder only. No agreement yet: stewards see "No agreements
  yet · Write the first agreement"; everyone else sees no Charter row, and joining skips the step.
- **Older clients** see the `charter` group as a channel named "Charter" with to-dos marked done
  by whoever ticked first. Clients that know this section hide it.
- **People who can't read the group** (guests, applicants): `GET https://belfry.<domain>/api/v1/charter`
  (no auth) → `{"agreements": [{"id", "title", "about", "order" (null = none), "added", "text_at"}]}`;
  belfry.json `charter: true|false`. A guest's request carries the ids ticked (`agreed`); once a
  member, the belfry signs those ticks for them (collective-belfry docs/charter.md), so a
  just-accepted member is not asked again.
- **A new member's first minutes** (web `welcomeDue`): shown once on a device that saw the
  account's guest become a member, when a charter exists and the person never ticked an agreement
  (any 2101 of theirs in the list), not to a member's agent or a steward: Welcome (mark, name,
  description, who let them in) → each agreement ticked ("4 of 6 agreed" until all) → name and
  photo, with Skip.
- Core-tested in `CharterTests` (the vector: admins only, `each` only, reorder without asking
  again, a text change making a tick stale, outsiders' edits and removals ignored, removal by
  another steward; the actions' tags; the names; the charter group out of the channel list).

### 19.33 E-mail threads in a channel (iOS, 2026-11-16; web mailthreads.ts at mail-chat 338d296; collective-belfry docs/mail.md; `vectors/mailthreads.json`)

Nothing new on the wire; each client joins a shared inbox's e-mails into threads.

- **An e-mail from outside** = a kind 9 in the group **by the groups key** with `client belfry-mail`
  and `mail-from` (plus `mail-name`, `subject`, `message-id`, `in-reply-to`, `references` (at most
  10 ids), `belfry-auth` = `pass` when DKIM or SPF aligned, `mail-original` url / key / nonce).
  **What went out** = a kind 9 by the groups key with `client belfry-mail-out`, `mail-to`,
  `subject`, `message-id`, `sent-by` and `e` = the e-mail it answers. Anyone else's kind 9 with
  `client belfry-mail` is **a member's own mail**: an ordinary message with a small "via e-mail"
  beside its time and "Full e-mail" in its menu (the original on Blossom, AES-GCM).
- **Joining**, oldest first: an e-mail whose References + In-Reply-To (taken newest first) name the
  `message-id` of one already in a thread joins it; else the same sender (address, lower case) with
  the same base subject (Re:, Fwd:, AW:, SV:, TR:, WG:, Antw:, Rif:, R: and `[n]` aside; spaces
  folded; lower case) joins theirs; else it starts a thread and is its **root** (the thread's id:
  route, labels, replies). What went out joins the thread of its `e`. Subject of a thread = the
  root's base subject; its name = the first `mail-name` given; `lastIn` = the latest e-mail received;
  `refs` = every Message-ID in it, oldest first.
- **The team's talk** = NIP-22 replies (kind 1111) under any e-mail of the thread (older clients
  hung them under a later e-mail): counted, and shown in the thread with the e-mails.
- **State**: walk the thread's e-mails and its `belfry/inbox` labels (kind 1985, `e` = any of its
  e-mails) by time (then id): `l open` / `l closed` set it; `l assigned` + `p` sets who has it (no
  `p` = nobody); an outgoing e-mail counts an answer and turns Open into **Answered** (`waiting`);
  an incoming one other than the root turns it back to Open (they wrote back).
- **The feed** shows one row per thread at its latest activity (the latest e-mail either way or
  team reply): sender, subject, a line of the latest e-mail, state, who has it, counts. Its e-mails
  are not shown as messages, and its replies don't resurface as thread activity.
- **The thread**: the e-mails in frames (received light, sent solid, "unverified sender" when
  `belfry-auth` isn't `pass`, "Full e-mail"), the team's replies, the state with Take / Close for
  the channel's admins, "Team only: <sender> sees only what is sent by e-mail." ONE composer with
  a line above it: "Message will be sent in this thread" (a NIP-22 reply under the root) and, for the
  channel's admins while the inbox is on, "Reply by e-mail instead"; then "This will be sent to
  <name> by e-mail", "From <address> · Re: <subject>", "Only send in this thread instead", the
  composer in the e-mail colour. Sending by e-mail waits 8 seconds for Undo (leaving the thread
  sends at once), then goes back to thread mode.
- **Answering by e-mail**: a kind 1301 (Nostr Mail) to the bridge: `mail-from` = the channel's
  address, `rcpt-to` = `lastIn`'s address, `subject` = "Re: " + `lastIn`'s base subject, `e` =
  `lastIn`'s event id; the MIME From = "<my name> for <channel name>" <address>, In-Reply-To =
  `lastIn`'s Message-ID, References = the thread's other Message-IDs (the last 10) then In-Reply-To.
- **The channel's address** (web model.ts `mailLocal`, server `mail.channel_by_slug`): its plain
  alias `slug(raw name)` (else the id without a `-xxxx` suffix of 3–8 [0-9a-z]) when a belfry admin
  (or the relay's own key) made it (its first 9007; none known: yes) and exactly one group answers
  to that alias (by its suffix-less id or its slugged raw name); else its id. slug = lower case,
  accents folded, runs of anything else → "-", trimmed, 40 at most. The raw name is the
  metadata's, before the on-brand names of 19.31.
- (iOS) `Model/MailInbox.swift` (`mailThreads`, `mailLocal`), `MailUI.swift`, core-tested in
  `MailInboxTests` (the vector: joining by references, by subject, the answer, the team reply,
  labels on a later e-mail, the feed; the address rules).

### 19.34 Channel categories (iOS, 2026-11-17; `vectors/categories.json`, reference `tools/vectors/categories.py`)

The stewards sort the town's channels into categories. Same event as the channel order: kind
30078, `d` = `belfry:channel-order`, on the community relay, by a belfry admin; the latest by a
belfry admin wins (anyone else's is ignored). Its content gains `categories`:

    {"order": ["general", "garden-3f2a", "kitchen-7d10", "repair-cafe-91bc", "proposals"],
     "categories": [{"id": "life", "channels": ["garden-3f2a", "kitchen-7d10"]},
                    {"id": "making-4b1c", "name": "Making & mending", "channels": ["repair-cafe-91bc"]},
                    {"id": "building", "channels": ["proposals"]}]}

- **Built-in ids**: `life` ("Community life", the default) and `building` ("Building together",
  default members `proposals`, `website`). The town (`general`, Members, the Charter) is never a
  category; an entry with id `town` is ignored. A custom id is a short slug of its name and 4
  random characters ("making-4b1c"). A missing or empty `name` on a built-in means its default
  title; on a custom one, its id.
- **Reading** (the town's listed channels, `general` left out, in their natural order sorted by
  `order`): without `categories` exactly as before (life = everything but proposals and website,
  then building = those two). With it: each entry in its order; its channels are the ids it
  names that are listed now, a channel named twice counting in the first, unknown ids ignored;
  entries that aren't objects, have no string id, or repeat an id are skipped (100 entries,
  1000 ids each at most). A built-in left out still exists: `building` (appended last) with its
  default members not named elsewhere, `life` (inserted first). Every channel no category names
  goes to the end of `life`, in `order`'s order.
- **Showing**: `building` only when it has channels; every other category to belfry admins
  always (empty, with its ＋), to everyone else only with a channel.
- **Writing** (a belfry admin): `order` = `general` then every category's channels, flattened (so
  older clients, which read only `order`, keep the same order); each category with `id`, `name`
  (left out on a built-in whose name is its default) and `channels`; an empty `building` is left
  out (it hides anyway; its default members are named in `life` when they were moved there, so
  they don't fall back). Ids the writer can't see (an archived channel) stay at the end of their
  category from the previous event.
- **Editing**: moving a channel = a drag in the flat list of headers and channels (a channel
  belongs to the header above it; above the first header: the first category); deleting a
  custom category moves its channels to the end of `life` (built-ins can't be deleted); a
  channel made with a category's ＋ by a belfry admin is followed by the order event putting it
  at the end of that category (a member's new channel lands in `life` by the reading rule).
- (iOS) `Model/Categories.swift` (`ChannelLayout.parse`, `Categories.arrange / shown / encode /
  rebuild / put / remove / newId`, `Derive.channelCategories`, `Actions.channelLayout`), core-tested
  in `CategoriesTests`. The drawer's channel rows are one `List` section with the headers as
  rows (`onMove` only for belfry admins); Belfry settings → Channels edits the categories.

### 19.35 Place names after the web's change of 2026-11-17 (Android note)

The web shipped a single on-brand name: `general` shows as **Townsquare** (same rule as 19.31:
only while its own name is the default — empty, its id or `#` + id, any case; the id and the mail
alias stay `general`; never written back). `events`, `proposals` and `website` show their own
names (Square, Fairground, Assembly and Scriptorium are gone). The town's fixed rows read
Townsquare, Members, Charter. New belfries get `#general` alone: no client may assume `events`,
`proposals` or `website` exist; Community life shows (empty, with its ＋) to any non-guest so a
member can make the first channel. Belfry settings → Channels: a channel's menu ends with "Delete
the channel" when it holds no content (kinds 9 10 11 12 20 1063 1068 1111 9802 30023 31922 31923)
or "Archive the channel" otherwise, each behind a confirmation; delete = `POST /api/v1/group-delete
{"channel"}` (NIP-98, a group admin or belfry admin; 409 `not_empty`, 409 `always_open`, 422 for
ev- / ws- / prop- ids; the belfry publishes the 9008). (Android) The names live in one table,
`core/model/Charter.kt` `PlaceNames`.

### 19.36 Buttons on messages, links in text, the pull-down's rows (2026-11-17; collective-belfry `docs/actions.md`, `vectors/message-actions.json`)

**Buttons.** A kind 9 or 1111 in a group may carry up to four `["button", id, label, style?,
action?]` tags: `id` = `[a-z0-9-]{1,32}` unique in the message, `label` 1–40 characters after
trimming (shown as is, never as Markdown), `style` `primary` | `danger` | anything else = plain,
`action` empty = the message's author handles it, `belfry:<name>` = the belfry does. A tag that
breaks a rule, a fifth button and a repeated id are ignored; an edit (1010) never changes them.
Buttons show under the text, in tag order (feed and threads).

- **Press** (published at once on the groups relay, never through the outbox; kept in the log so
  the state survives a restart): kind 1985, content "", tags `["L","belfry/action"]`,
  `["l",<button id>,"belfry/action"]`, `["e",<message id>]`, `["p",<message author>]`,
  `["h",<group>]` (+ the client tag). When it can't be sent: "Couldn't send. Check your connection
  and try again." and the buttons unlock. A reading guest gets the Join sheet instead.
- **Answer**: kind 1985 `["L","belfry/action-result"]`, `["l","ok"|"error","belfry/action-result"]`,
  `e`, `["press",<press id>]`, `p` (who pressed), `h`, `["code",…]` on errors, `["closed","1"]`
  optional on ok; content = one sentence. It counts only from the handler of the button its press
  named: the author (no action) or the belfry key (belfry.json `nostr.pubkey`) / its groups key
  (belfry.json `groups_key.pubkey`; nostr.json `_groups` for a belfry not yet updated). A press
  this device hasn't seen: the handler of any of the message's buttons.
- **State** (`Buttons.state`): the latest trusted ok + closed answer to anyone's press closes the
  buttons **for everyone**, replaced by its content. Otherwise, for my latest press: no answer and
  younger than 20 s → every button locked, a spinner on the one pressed; the latest trusted answer
  to it → ok: its text under the buttons (none when empty); error: its text, else the fixed
  sentence for its code (unknown code "That didn't work."), in error colours, buttons unlocked; no
  answer after 20 s: "No answer yet." (an answer arriving later still shows).
- **Asking first**: a `belfry:` action asks with the app's own text (`belfry:website-publish`:
  "Publish this version as the website? Everyone will see it at https://<domain>." · Publish /
  Cancel); a danger button handled by its author asks "<label>?". A `belfry:` action this app
  doesn't know: disabled, "Update the app to use this.".
- (Android) Material `Button` for primary, `OutlinedButton` for plain and danger (error colours),
  `AlertDialog` for the question. `core/model/Buttons.kt`, `World.buttons / buttonState`,
  `Session.pressButton`, `ui/chat/MessageButtons.kt`.

**Links.** Everywhere people's text is printed (messages, thread replies, DMs, e-mail frames,
descriptions, to-do titles and comments, event descriptions, profiles, requests, the log's
details) an address is `https?://` then anything up to white space, `<`, `>`, `(` or `)`, not
ending on `. , ; : ! ? ' "` (`core/model/Links.kt`; the same rule is the URL branch of the
Markdown pattern). A bare host is not a link. Markdown emphasis never starts or ends inside an
address (the marks `_ * ~` and backtick inside one are masked before the inline pattern runs:
"my_file https://a.org/b_c_d" keeps the whole address; the web's markdown.tsx still splits it).
This belfry's chat links open in the app: `https://chat.<domain>/#/c/<id>[/m/<event>]` (also
`https://<domain>/chat/#/c/…`) opens the channel, its `/events/<link>` and `/join/<token>` go
where an opened link goes; anything else opens in the browser. Long addresses wrap.

**Pull-down rows.** Pinned, Events (coming up), To decide, To-dos, Photos, Files, Groups and
Topics show only when they have something (`ChannelCounts.showsRow`); Members, Notifications, Log
and Settings (channel admins) always show.

### 19.37 Channel settings save at once, through the outbox (Android, 2026-11-17)

Saving a channel's settings applies at once and goes back to the channel: the change is queued
in the outbox (kept through restarts and offline, sent in order). Those the relay lets edit queue
the 9002 (every other setting kept); an admin by role queues the call `POST <api>/group-meta` as
an outbox item with target `api` (`path`, JSON `body`; NIP-98 signed when it is sent; it shows
meanwhile as the 9002 it asks for). The e-mail switch (30078 `belfry:inbox`) and the web page
setting (`belfry:web:<id>`) are events in the same queue. An API answer: 2xx sent; a 4xx other
than 401 / 408 / 429 is refused for good (`invalid: http <status> <code>`); anything else is
tried again. A change refused for good (relay or API) keeps showing, and an error mark stands
next to the channel's name; a tap says "Your change to this channel's settings was refused, so
others don't see it." with Try again (back in the queue) and Discard (what the relay has shows
again). `World.failedSettings`, `Session.retrySettings / discardSettings`. The Settings screen:
Save is the filled button; "Archive this channel" is a centred text button under it with its one
line.

### 19.38 Deleted by absence: when a client may say so (2026-11-17; web `src/core/groupstate.ts`)

An iPhone build showed a proposal made a minute before as "This group has been deleted". The
rules every client follows: events (`ev-`, `ws-`) and proposals (`prop-`) are never deleted by
absence, only by a 9008 newer than every 9007 (the same rule for channels and groups: a group
made again lives). A group this device holds any metadata of (39000, 9007, 9002; my own queued
or just-sent creation included) is live: it is never asked about and a stored "absent" mark for
it is ignored, so its own events arriving later clear the mark. Only channels and groups known
from a card alone are asked about, never while the outbox holds anything for them, never in the
first two minutes after this device made them. The question is `{"kinds":[39000],"#d":[…]}` +
`{"kinds":[9007,9002,9008],"#h":[…]}` with #general alongside; only an answer that ends in EOSE
and names #general counts (no answer = unknown = the normal screen); a 39000, 9007 or 9002 for
the id means it is there. (Android) `core/model/Archive.kt` `presenceAskable`, `presenceAfter`;
`Relay.queryUntilEose`.

### 19.39 Member links, and a guest who becomes a member (Android, 2026-11-18)

(collective-belfry `guests.py` / `roles.py` "member links"; web `core/auth.ts` `memberLinkOf`,
`core/guestjoin.ts` `becameMember`.) A member link is `https://chat.<domain>/join/<token>` like a
guest link; only the belfry's answers tell them apart.

- **Which kind.** `GET <api.channel_invites>/<token>` answers `"as": "member" | "guest"`, and for
  a member link `member_role`, `member_role_label` (cut to 40; empty → "Member") and `membership`
  (`month` | `year`, anything else = permanent). The join answer (`POST …/join`) carries the
  same fields and `"email_needed": true`. A member link is **never** kept as a co-host token: it
  needs no second use.
- **Opened with no account here.** The info is read first (at most 6 s; no answer → the guest
  link flow of 19.26, unchanged). A member link that is open shows one screen, nothing behind
  it: "You’re invited to join <community> as <a member | Role>" (+ "<inviter> invited you.",
  "Sign up with your name and e-mail address [and you are a member for a year | month], or log in
  if you have an account here already."), **Sign up**, **Log in**, Close. Log in: the sign-in
  starts and the link waits; once signed in the "Join with your account?" sheet uses it with the
  account. Sign up: the link is used as in 19.26 (`{}` → a reading guest; the device keeps
  `member` and the token with its guest record), but instead of the channel the app shows the
  member page: the same title, the Join form with "Your name and e-mail address, and you are a
  member of <community>[ for a year | for a month]. We’ll send a 6-digit code…", the code step,
  and "I have an account: log in" (drops the placeholder guest, revokes its session, keeps the
  link pending). The channel is never drawn while the guest record says `member` + `reading`.
- **The code.** `POST /guest/save/verify` → `{"saved": true, "joined", "member": true, "role"}`
  when the link made them a member. Then, and also when an account's `POST …/join` answers
  `"member": true`, or `whois` says `member` for a device that still keeps a guest record: the
  guest record goes, the register's answer for me becomes `member` at once (so `amGuest` is
  false), the groups relay is asked for everything once (below), and the account is opened
  again (the web reloads), landing in the link's channel. `member: false` (the belfry could not
  make them a member): a saved guest, as before.
- **Never an application from a member.** "Become a member" (guestapply.py) shows only when
  `World.mayApply`: not when whois says `member` / `agent`, else when I am a guest here. A
  `409 not_guest` to `POST /applications {"apply": …}` asks whois about me: `false` (a guest, or
  no pass at all: 401/403) → "You are no longer a guest here."; otherwise it is a member: the
  member path above runs and the form closes without an error.
- **The groups relay subscription** (the cause of "a new member sees only #general" and of a
  channel named by its id, "#issues-5fce"). Two filters: the groups' metadata kinds 39000–39003
  **without `since`** (limit 1000; replaceable, one per group, usually older than anything
  else), and every other kind since the newest event here less an hour (limit 2000). A device
  whose store never had a member's view (store key `groups-full-1` missing: a fresh sign-in, or
  a guest who just became a member) asks the second filter from 0 once; the key is set at that
  subscription's EOSE. A member's channel list is every live, non-archived channel from the
  metadata (open or closed, like the web's `townCategories`), not the relay's 39002 lists; a
  guest's is the channels they are in. A channel known only from its messages is named by its id
  until its 39000 arrives (a non-everyday event: the next snapshot is built anew).
- Tests: `core/.../model/MemberLinkTest.kt`.

### 19.40 To-dos: claim, assign, unassign; a row opens the to-do (Android, 2026-11-18)

(tasklist.sh kinds 2100–2103 as the web writes them, `core/model.ts` `tasklist`,
`core/actions.ts` `taskAction`.) The web has only `claim` / `unclaim` by the person themself
(assignee = the claim's author). Giving a to-do to someone else is new here; it is written so
that the web's reader ignores it rather than misreads it (an unknown `action` changes nothing
there), until the web reads it too.

- **Claim** (to me) = kind 2101 `["e", task] ["action", "claim"] ["t", list] ["h", group]`, as
  today. **Assign to someone else** = the same with `["action", "assign"] ["p", <their key>]`:
  the assignee becomes that key (a `p` that is not a 64-hex key is ignored). **Unassign** my own
  = `unclaim`; someone else's = `["action", "unassign"] ["p", <the assignee>]`: nobody is on it
  when that `p` is the assignee then, else nothing changes. Latest event in time order wins, as
  for every 2101. Anyone who can write in the channel may assign or unassign (like the web's
  "Take over").
- History steps `assign` ("gave it to someone") and `unassign` ("took someone off it") join the
  thread's step lines and the channel Log.
- **Lists**: every to-do row (the channel's To-dos screen, the pull-down, Home's My to-dos) is
  the tick box (its own tap target) · title and who is on it · a chevron; tapping the row opens
  the to-do's own screen. No per-row comment, delete or take buttons.
- **The to-do's screen**: the tick box, title, who is on it, who made it; rows Claim (or "Take it
  over"), Assign to… (a sheet: me first, then the channel's people and the belfry's members, not
  reading guests), Unassign me / Unassign <name>, Open the message it came from (its `q`),
  Delete the to-do (its author only); then the discussion (kind 2103 comments) with a comment
  field. Editing a to-do's title has no event in the protocol: not offered.
- Tests: `core/.../model/TodoAssignTest.kt`.


### 19.41 Who reads a channel, closed channels, a channel's notifications (round 6; = iOS SPEC 19.38, web c0a1cc2 / chat 8d31c30; `vectors/visibility.json`)

Taken as the iPhone app wrote it (belfry-ios 8cc6e78, SPEC 19.38; read it there for every word).
In short: Settings → "Who can read it" = Members only / Public (asks first: "Make #<name> public?")
/ Closed (never #general; for a group also its question and Requests are accepted: by its
stewards or when 1–5 members vouch). Public / members only = the whole web-settings object
written back with `visibility`, `slug`, `on` (true when going public) and `periods`
(`[[from, to | null]]`, never retroactive); closed / open = the 9002's `closed` + `prompt` or
`open` (going closed from public writes both, members only first). Reading: public wins over
closed, else members only. A closed channel I'm not in and don't run (not a belfry admin): read
only, the bar "People ask to join this channel." + Ask to join (a 9021 with the answer), then
"Pending · The channel's stewards will answer." The pull-down's Notifications row opens ONE sheet
"Notify me about #<name>": Everything (`all`) / When it's for me (`default`; `mentions` shows
here too; "(the default here)" after a stored `default`) / Nothing (`off`), applied at once; and
"Also e-mail me its announcements" (follow `asap` / `stop`; `monthly` shows on).
- Android: `core/model/Round6.kt` (`PublicPeriod`, `ChannelAccess`, `World.webContent /
  publicPeriods / channelAccess / needsJoinBar / readOnlyClosed`, `mayClose`,
  `Actions.webPage / setVisibility`, `NotifyChoice`), tests `Round6Test`. The old "Threads from
  the web" switch and page address left Settings, as on iOS.

### 19.42 A proposal at the top of its chat (round 6; = iOS SPEC 19.39)

As iOS 19.39: the event screen's three levels (opens at half; a proposal's card opens it at
full; the keyboard folds it): half = status, "Proposal by <name> · version N", title, the start
of the summary, "Read the full proposal", the vote buttons (the poll's own `option` tags, with
counts, mine filled), "You haven't voted yet · X of Y members have" (X = votes on the current
poll, Y = the chat's members ∪ the voters); full = the whole text, Change the status, New
version; up = one bar with title, status, tally ("12 yes · 2 no · 1 abstain") and Vote / "Voted
<option>". Tabs Chat · Versions (once a version 2 exists: version 1 = the card, then each "📝
Version N" kind 9 with `version` ≥ 2 and `a` = the proposal) · Votes (once someone voted).
- Android: `Poll.options`, `Proposal.options`, `World.voteTurnout / votesByOption /
  proposalVersions` (core `Round6.kt`); `ui/cards/ProposalTop.kt`, in `ChannelScreen` for a
  proposal's chat.

E-mail threads in round 6 (iOS 19.33's round-6 bullet, boards channels/mail-*): every entry a
message row ("guest, by e-mail" / "sent as <alias>@" / "team only"), the composer's two modes
Reply to <first name> (blue, an e-mail) / Comment (yellow, a NIP-22 reply), the line under them;
the feed row shaped like a message. Android: `ui/cards/MailThreadScreen.kt`, `ui/chat/MailRows.kt`.

### 19.43 Names, never ids (Xavier, 2026-11-18; the three platforms' shared rule — iOS / web SPEC 19.39)

- Never show an npub, a group id or an event's `d` as text: names only. Someone whose name isn't
  known is "unknown" (several: "and one other").
- A channel's / group's / event's / proposal's name that hasn't arrived: (a) in a list row or a
  header no text but a plain grey bar where the name will be (~40% of the row, the text's height,
  "Loading" for screen readers; static on e-ink), with the kind's icon as usual; (b) inside a
  sentence or title the neutral words "this channel" / "this group" / "this event" / "this
  proposal"; (c) never any part of the id; (d) re-drawn the moment the metadata arrives; still
  unknown after ~5 s, the bar becomes "Channel" / "Group" / "Event" / "Proposal".
- My own key is copied for other apps ("Your key"), not shown.
- Every event link the app builds or shares uses the event's custom slug when it has one
  (`CalEvent.link`).
- Android: `Group.named` + `World.kindWord / kindPhrase / UNKNOWN` (core), `NameSkeleton` /
  `rememberNameLoading` (kit), `TopBar(titleLoading =)`; tests `NamesTest`.

### 19.44 References in a message: a name inside the text, a box at the end (2026-11-19; the lead's rule; canvas channels/link-preview-end + -inline, to-do c92a9f45; grown 2026-11-20 after Xavier asked that a link to something in the belfry never shows as a raw link; `vectors/references.json`, 38 cases, now kept by the web: commonshub/collective-belfry branch chat-source, `src/core/refs.ts`, `tools/vectors/check.mjs`)

A **reference** is any address of this belfry's chat, `https://chat.<domain>/#/…` or
`https://<domain>/chat/#/…` (segments percent-encoded or not), that names:

- `#/c/<id>` and its pages (`/group`, `/info`, `/info/members`, `/about`, `/members`, `/images`,
  `/photos`, `/files`, `/todos`, `/log`, `/events`, `/t/<topic>`) = **group** `{id}`;
- `#/c/<id>/todo/<task>` = **todo** `{group, task}`;
- `#/c/<id>/m/<event>` and `#/c/<id>/thread/<event>` (64 hex; `/reply` after it allowed) =
  **message** `{group, id}` (a message link was not a reference before 2026-11-20);
- `#/cal/<31922|31923 coord>` (the older event address) = **event** `{coord}`;
  `#/events/<link>` = **event** `{link}`; `#/events/<link>/m/<event>` = **message** `{link, id}`;
- `#/u/<npub or hex>` = **person** `{pubkey}`;

also its event pages `https://chat.<domain>/events/<link>` and `https://<domain>/events/<slug-or-d>`
= event `{link}` (a ticket, `/t/<code>`, stays a link), its drop box links
`https://<domain>/drop/<token>` = **drop** `{token}`, and `nostr:naddr1…` of kind 39000 (group id),
31922 / 31923 (event coord) or 30023 (proposal coord). Another belfry's link, the app's places
(`#/calendar`, `#/home`), a message id that isn't 64 hex and any other address stay links.
A group is typed when shown: a channel, a group (pod), an event's chat (shown as the event), a
proposal's chat `prop-…` (shown as the proposal). Every event's own address is
`#/events/<slug or d>` (never a coordinate with a key); old `#/cal/` links still open. Share and
Copy link give the slug address.

- **At the end**: when the text, trimmed of trailing white space, ends with a reference, the last
  one comes out of the text (with the white space before it) and shows as a preview box under it;
  a message that is only a reference shows the box alone. The box: the channel or group (name,
  "Channel · N members", its about), the event (its card), the proposal (title, "Proposal in
  #parent"), the to-do (its title, "To-do in #channel · Done | Nobody on it yet | <name> is on
  it"), the message (its author, "Message in #channel", two lines of it), the person (face, name),
  the drop box ("Drop box", its channel, until when); a tap opens it (a drop box: its page in the
  browser). Same in direct messages.
- **Inside the text**: its name, tappable: "#" + name for a channel or group, a calendar mark for
  an event, a page mark for a proposal, "☐ <title>" for a to-do, "a message from <name>",
  "@<name>" for a person. Never the id, the key or the slug.
- **Not here yet**: a grey bar, then after 5 s the kind's word: "#channel" (as
  `vectors/channel-refs.json`), "#group", "Event", "Proposal", "To-do", "Message", "Someone",
  "Drop box" (lower case "#channel" / "#group", as the web).
- **Can't open it** (a closed group I'm not in, a members-only channel for a guest): no name: a
  lock box at the end ("A members-only channel" / "A closed group" / "A to-do in a closed
  channel" / "A message in a closed channel", "Only members of <belfry> can open it"), grey words
  inside the text. **Deleted**: "a deleted channel" (group, event, proposal, to-do, message), "an
  ended drop box" (deleted or past its expiration), grey.
- **As plain text** (one-line previews: the channel list, notifications, push texts built on the
  phone, the pinned line, reply quotes, search results, the DM list; the lead 2026-11-20,
  `references.json` `plain` + `known`): a `nostr:npub` as "@…" (or the caller's name); each
  reference, inside or at the end, as the thing's name when it is known here — a channel or group
  "#<name>", an event or proposal its title, a to-do "☐ <title>" ("☑" when done), "a message from
  <name>", "@<name>", "the drop box of #<channel>"; locked and deleted as above — else its generic
  word, never the address: "#channel", "#group", "an event", "a proposal", "a to-do", "a message",
  "someone", "a drop box" (an event's chat `ev-` / `ws-` = "an event", `prop-` = "a proposal").
  A box at the end follows the text after one space. `refKey`: `group:<id>`, `event:<coord or
  link>`, `proposal:<coord>`, `todo:<group>/<task>`, `message:<id>`, `person:<pubkey>`,
  `drop:<token>`. (vectors/channel-refs.json since 2026-11-20: a 30023 naddr reads "a proposal".)
- **On send**: an `a` tag for each event or proposal coord in the text (as the web does for event
  links); readers never depend on it, they parse the text.
- Direct actions on the boxes (Going / Maybe / Can't go, Claim / Mark done, Join / Apply, Vote:
  canvas v69 channels/card-*) are a draft for Xavier: not built.
- (Android: `core/model/References.kt` — `References.parts / endBox`, `World.refSeen`;
  `ui/kit/RefBox.kt`, `MarkdownText` for the names.)

### 19.45 Reactions to direct messages (canvas home/conversation, Xavier's to-dos 2026-11-19; the web's dm.ts; `vectors/dm-reactions.json`)

A reaction to a direct message is an unsigned kind 7 rumor, content = the emoji, tags
`["e", <the kind 14 rumor's id>, "", <its author>]`, `["p", <its author>]`, `["k", "14"]`, then
`["p", …]` for the other participants, gift-wrapped (NIP-59) to every participant and to
yourself. Taking it back: a kind 5 rumor `["e", <the reaction rumor's id>]`, `["k", "7"]`,
`["p", …]` for the others, wrapped the same way. It counts only from the reaction's author, and
also when it is read before the reaction (wraps are back-dated at random and read newest first).
Readers key on the last `e` tag; one per person per emoji; `+` or empty = 👍; more than 16 UTF-16
units = ignored. A reaction never moves a conversation or its unread count. Never on e-mail
(1301). The chips sit under the bubble on its side, the same as in channels: tap = add / take back
mine; a long-press on a bubble opens the menu with the reactions row. The header of a
one-to-one conversation shows the person's face and name; a tap opens their profile.
(Android: `DmReaction.apply`, `DmSnapshot.reactionsOf`, `DirectMessages.react`,
`ui/people/DmReactions.kt`.)

### 19.46 When an event is over (2026-11-20; the lead, after an OSV member's bug "Upcoming events show events of the current day that have already passed"; web chat 39a2b85 and the server)

One rule for every list of upcoming and past events (Events Upcoming / Mine / Hosting / Past,
Calendar and its day view, Home "Coming up", the channel's pull-down Events, the attention line,
the drawer's next-event line, the public events a guest reads from the API) and for RSVP:

- **past = now ≥ end**. A timed event ends at its `end`; with none (or an end not after its
  start), one hour after its start. An all-day event ends at the start of the day after its last
  day (its `end` date is inclusive; none = its start date), in the event's time zone
  (`start_tzid`), the viewer's when it has none.
- An event going on now is **upcoming** (and stays "next"); RSVP closes at its end.
- In a day view an event shows on each day from its start's day to the day of its last moment.
- (Android: `core/model/Events.kt` `endOf / isPast / onDay`, `World.upcoming`; test `PastEventsTest`.)

### 19.47 Sign in to another app, the belfry as its remote signer (2026-11-20; the lead's contract, Xavier: Android first; live 2026-11-20)

The phone only scans and confirms: the belfry's server becomes the NIP-46 remote signer of a
third-party Nostr app on the app's relays; the phone answers nothing and holds no key for it.

- **The code**: the app's `nostrconnect://…` QR (or pasted). Only the prefix is recognised here;
  the server validates the rest. It holds a secret: sent as read, never logged, shown or kept.
  `bunker://` codes are not part of this.
- **API** (`<api>/remote-apps`, NIP-98 by the account's key): `POST /preview {"uri"}` → `{name, url,
  host, image, asks: {sign, decrypt}, known}` (the app's own claim, unchecked: the image is never
  loaded, the host shown as "It says it is …"); `POST / {"uri", "decrypt"}` → 201 `{app}`; `GET /` →
  `{apps: [{id, name, url, host, image, decrypt, created, used, expires}]}`; `POST /<id>/disconnect`.
  Errors by code, fixed texts: 422 `invalid_uri` (`reason` bad_scheme | bad_key | no_relay |
  bad_relay | no_secret), 403 `guest` | `agent`, 409 `own_key` | `key_taken` | `limit` (10) |
  `unsupported`, 429 `too_many`, 502 `unreachable` (shown as "The app didn't answer. Check it's still open, show a new code there and scan again."), 503 `signer` | `relays_full` ("Couldn't connect just now. Try
  again later."), 403 `no_pass`, 422 `invalid` (a non-boolean decrypt). Live 2026-11-20 on OSV and
  belfry.sh (Belfry 41d5903, docs/remote-apps.md). `decrypt` is sent false when the switch isn't
  shown; `known: true` = already connected to this account: "Already connected. Sign in again?",
  connecting replaces it. POST /remote-apps can take ~8 s (button locked with a spinner). A connection ends after 30 days without use and with the membership.
- **Flow**: account switcher → "Sign in to another app" → Connected apps (the list, each can be
  disconnected) → "Sign in to an app" → scan (or "Paste a code instead") → the belfry's preview →
  confirm (canvas v71 home/signer-*-android, note n-signer, DRAFT for Xavier: "Sign in to
  <name>?", "The app says it is “<name>”, at <host>" — no name: "An app at <host>", neither: "An
  app" —, "Name not verified by Belfry", As <me>, It will be able to: "Post and sign as you"
  and, only when the app asks to decrypt, a "Read your private messages" switch that starts OFF)
  → Sign in ("Signing in…", Cancel locked; no answer in 15 s: "The app didn't answer. Check it's still open, show a new code there and scan again.") → "You're signed in to <app>" (Done / Connected apps). Refusals
  shown in place of the confirm screen, each with one way on: "Belfry can't use this code" (invalid_uri
  bad_key | no_relay | bad_relay | no_secret), not a sign-in code (bad_scheme or the prefix),
  connected to another account (`key_taken`), a guest ("How to join"). The server can't tell a
  code expired or was used (its secret and lifetime are the other app's): the Done screen says
  "If <app> didn't sign you in, show a new code there and scan again." Entry: Accounts, under
  "Link a phone or tablet"; "Connected apps · N" only once one exists. "Paste a link instead"
  reads the clipboard. Never shown: a key, an npub, a relay address, the code.
- (Android: `core/net/RemoteApps.kt`, `engine/RemoteApps.kt` `Session.remoteApps`;
  `ui/shell/RemoteApps.kt`.)

### 19.48 End-to-end encrypted conversations: the screens (canvas "Messages · encryption", approved 2026-11-20, v80–v82)

The screens, on the sealed engine since v0.7.6 (contract collective-belfry docs/sealed.md + vectors;
`core/sealed/`, `core/engine/Encryption.kt`, words in `core/model/Sealed.kt` `SealedText`); the demo
fills them on the device. A guest, or a session without a device key, has nothing sealed.

- A sealed conversation (direct, group, notes to self, an e-mail thread) has a small lock after its
  title; it opens the Encryption screen. Channels never have a lock. A conversation that isn't
  sealed shows no lock (the "Not locked yet" line is FOR XAVIER, not built).
- Device lines among the messages, in the style of a channel's join line: "Lina added a device",
  "Lina removed a device" (no device name), mine with it: "You added a device · Safari on Mac".
  The amber "Lina signed in on a new device that can't read earlier messages yet" opens the share
  sheet; once shared (or allowed by another of her devices) it becomes the plain "Lina added a
  device" (v81).
- Encryption screen: "Only these devices can read this conversation"; my devices in full (name,
  "Added 12 Aug · from Pixel 8", "This device"); each other person one row with "N devices" and
  a grey change line in the last 30 days; "You see only how many devices others have, not which.";
  then the sentences. Groups: "5 people · 11 devices", only You unfolds. E-mail: "<name> · by
  e-mail" / "not on this belfry", no count, the mail sentence.
- A device not yet allowed reads no sealed message, old or new (v84): the banner "Messages are
  locked on this device" (+ Show how, three steps), every sealed conversation's row "Locked on this
  device"; in the thread, messages from before it was sealed read as usual, then one amber block
  ("Messages from <date> on can be read once another device of yours allows this one." + Show
  how), and "You can write here once this device is allowed." in place of the composer.
- History shared with this device (v86): "Shared by <name>" above each sharer's block (opens one
  sentence in a sheet, OK closes it); the shared messages carry a subtle rule on their left.
- Allow / Not me on my other devices (a dialog over any screen; the first to answer wins); the
  linked screen inside Link a phone or tablet; Remove a device in Me → Devices ("Where you're signed
  in", shown once this device has a device list, v87) (asks
  first, then the row shows progress, no other row removable; this device is Sign out's).

### 19.49 A member's agents fold under the member (canvas v93, Xavier 2026-11-20) and brand colours per mode

- Members: an agent whose member is in the list has no row; the member's row gets a grey second
  line "+ 1 agent" / "+ N agents", the role on the right. An agent that belongs to nobody here
  keeps its own row. Counts ("N people") never count agents. Searching an agent's name shows its
  member's row with "+ <agent name>", the match in bold. Owners come from belfry.json `agents` and
  the directory. `core/model/MembersFold.kt`. Unchanged: mention pickers, "who's here", channel lists.
- Profile: an "Agents" section under Role, one row per agent opening its profile; on my own
  profile "Add an agent" comes last in it.
- Branding: belfry.json / GET /branding `branding.colors` = {light, dark: {accent, on_accent}}
  (the lead, live on OSV and belfry.sh). The accent and Material's primary / onPrimary are the
  current mode's, following the system live; no `colors` = the raw primary as before. Never the raw
  primary or secondary for text or icons when `colors` is there.

### 19.50 One owner for every socket; a send never waits on a dead one (2026-11-21; Xavier via the lead, after DMs stuck on "Sending…" on iOS; the same on Android; first written here, belfry-ios told)

What was wrong (both apps): the NIP-46 signer's socket lived on its own, was never asked whether
it lived, and after a sleep looked open and was dead: each signer request waited its whole
timeout (a direct message = 4 requests: two seals signed, two encryptions). The outbox stalled a
whole relay behind its oldest retrying item, so a stuck read marker held back direct messages.
Android also did nothing when the phone changed network while online (Wi-Fi to mobile).

- **One manager** (`core/net/Connections.kt`) holds the session's sockets: groups, main AND the
  signer's (`BunkerSigner.relay`, made once and opened again as needed). Nothing else opens a
  socket for a session (sign-in flows before a session have their own signer, closed after).
  Signer requests queue while its socket (re)connects.
- **Foreground and every network change** (also a new network while online): every open socket is
  asked at once (a tiny REQ; any frame counts as an answer); no answer within **5 s** = dead:
  dropped and opened again now (backoff forgotten); then the signer is said hello to ahead of the
  next send. In the foreground, a socket quiet for **25 s** is asked the same way. Before a send
  (a publish, a signer request), a socket quiet for more than **10 s** is asked first.
- A publish with no `OK` in time, or a signer request with no answer, **drops its socket** (the
  next try goes on a fresh one; the signer's request is asked once more on it).
- Reconnects and transient retries: **1 s, 2 s, 4 s, 8 s, 16 s, then every 30 s**.
- **Lanes** (`Outbox.lane`): a direct message's wraps by recipient, a group's events and API calls
  by `h`, anything else by kind (+ `d`); per relay. A transient error holds back its own lane for
  the pass. A signer that did not answer holds back every item still to sign (pre-signed wraps
  go). A relay that did not answer (`timeout:`, `connection closed`) holds back that relay's other
  items, except: never a direct message for a non-DM item's sake, and not once the item failed
  transiently **3** times (then only its lane). Order within a lane is kept. `vectors/outbox.json`
  passes unchanged.
- **The line under a message not out yet** (the same words on the three apps, the lead
  2026-11-21; belfry-ios writes them in its SPEC): "Sending…"; after **5 s** "Still sending… ·
  connecting" / "· encrypting" / "· uploading 2.2 of 4.8 MB" / "· waiting for the server" (a
  signer's or a relay's answer pending: never say signer or relay to people); after **30 s** "Not
  sent yet · Retry" (Retry: every socket asked now, the backoff forgotten, a fresh 30 s, a pass at
  once). Offline: "Waiting for the connection…" (the three apps; the web "Saved · waiting for the connection"). Refused: "Not sent: <reason> · Retry" as before.
  `SendState.shown` maps the trace's exact steps to these words; `rememberSendLine` (app).
- **Send trace** (`SendTrace`): per attempt `HH:mm:ss <kind> · <step> 0.3s · … → OK|retry (<class>)|refused 1.2s`,
  plus a line for a signer request of 1 s or more and for a socket dropped after a publish timeout;
  the last 20, never any content, name or key. About Belfry → Diagnostics shows them under each
  socket's state and quiet time, with Copy.
- Later (server, not yet): the signer will listen on the community relay too; then the signer's
  relay is taken from the bunker URL and the manager can share that socket.

### 19.51 Typing indicators (2026-11-21; Xavier via the lead; contract collective-belfry docs/typing.md @7750419; canvas v110 messages/typing-*)

- Kind 20100, empty content, ephemeral (never stored, never through the outbox, never retried).
  Channels, groups, event chats, proposals: signed by the person, on the groups relay, `["h", group]`,
  and `["e", root]` inside a thread. DMs: the 20100 is an unsigned rumor tagged `p` for every other
  participant, sealed (13) and put in a kind 21059 gift wrap to each of them on the community relay,
  wrap and seal with the real `created_at`; no copy to oneself; never for e-mail or notes to self.
  Optional `["status","stop"]`.
- Reading: the groups relay is asked `{"kinds":[20100],"#h":[groups on screen],"since":now-5}` while
  a channel or thread of them shows; the community relay `{"kinds":[21059],"#p":[me],"since":now-5}`.
  A conversation shows events without `e`; a thread only those whose `e` is its root. A DM's place is
  the rumor's author + `p` tags, as for kind 14.
- Sending: on a keystroke that leaves the field non-blank, at most every 4 s per place; never for a
  restored draft or an edit; a stop when the field is emptied without sending and a typing event
  went out in the last 6 s; none after sending. Not in archived groups, not from reading guests,
  not when this device's "Show others when I'm typing" is off. The signer gets 3 s; a relay that
  answers `rate-limited` gets none for 30 s.
- Showing: from an event until 6 s after the latest, a stop, or a message of theirs in that place made
  at or after it. Ignore events more than 10 s off, my own (any device), reading guests, people not
  in a closed group. "Lina is typing…" / "Lina and Tom are typing…" / "Several people are typing…"
  ("Somebody" with no profile), names semibold, three dots (still when animations are off), 13 sp in
  the secondary colour, one line, drawn over the feed's bottom so the feed never moves; a polite
  live region. A one-to-one DM's row in the list says "typing…" in place of its preview.
- Setting: Me → Privacy → "Show others when I'm typing", per device, on by default.

### 19.52 Sealed conversations: files, typing, only device, Start fresh (2026-11-21; Xavier via the lead; canvas v84, v117)

- A file (kind 15) in a sealed conversation is sealed by the device like a kind 14; its plan is
  decided before the upload, waiting retries in the outbox, never the old way. Not in Share history
  (§8 shares 14 / 7 / 5).
- No typing events in a sealed conversation: none sent, none shown.
- Only device (canvas v117 messages/encryption-only-device): Sign out on my only device for sealed
  conversations asks first, as a dialog: Link a device / Sign out anyway / Cancel. Android has no
  Forget, and This device is never removed (Sign out), so sign-out is the one place.
- Start fresh (canvas v84 messages/encryption-start-fresh + -confirm; sealed.md §5.4): only on a
  device out of my existing chain, my chain read complete on this connection. A new letterbox, a
  root n = 1 (this device), the anchor replaced; the old chain's letterboxes stay mine. A device of
  the old chain, allowed in again, hands its old key chain to the device that let it in, once.
- An old device after a fresh start elsewhere (canvas v122 messages/encryption-fresh-elsewhere +
  -done, approved by the lead): a dialog over any screen, back at every start until a button is
  tapped (no Cancel, no tap-outside): "Sealed conversations were started fresh on “<device>”",
  "Today at 9:41 PM", "This device can't read new sealed messages until it's allowed again.",
  **"If this wasn't you, sign out every other device."** Sign out every other device (red; every
  session but this one, /me/sessions/revoke; a failure: "Couldn't sign out every device. Try again.")
  → "Every other device is signed out", "<names> can't open your account any more.", the e-mail
  line, "To read sealed conversations here again, start fresh on this device." Show how / Done.
  It was me closes it (the locked state and the ask, as before). The device name is the session
  row's (the new root's device), else "a new device" (no quotes; canvas v124); Android keeps no e-mail: "your e-mail".
- **Known limit:** a second fresh start forgets the first chain (only the previous pinned chain is
  remembered), so devices of the first chain can no longer hand their keys back.

### 19.53 One shape: navigation (2026-11-22; Xavier via the lead; contract collective-belfry docs/one-shape.md §1; canvas nav2/*)

- **Bottom bar (phones):** Today · Calendar · Messages · Groups · Town; no ＋. Messages counts the
  conversations with new messages, Groups the new messages in my groups, Town nothing. Guests:
  Today · Calendar · Town. Every place's root has ☰ (the menu); pushed screens only ‹.
- **The menu (☰):** crest, name, search; Today · Messages · Mentions (red count) · Calendar; GROUPS
  (my groups, ＋ = new group, anyone); CHANNELS (＋ = Add a channel) = `OneShape.townList`: `general`
  always first, then the channels pinned for everyone, mine, then recent (I posted, reacted or was
  mentioned in the last 30 days; queued events count), newest activity first. No categories and no
  "All channels" row. Members, Charter, Archived stay as quiet rows under the channels (Android:
  nothing else reaches the Charter). ☰'s badge = new messages in the listed channels + in my groups +
  conversations with new messages. A wide window's column: the same, plus a Groups row.
- **Pinned for everyone:** `"pinned": [ids]` in the admins' newest kind 30078
  `d=belfry:channel-order` (read with `OneShape.parsePins(content, known, "pinned")`); an admin
  writes it with `OneShape.withEveryonePins` (every other key kept). Changing the categories now keeps
  every key it does not write too. The community relay subscription asks for that `d`.
- **Pinned for me:** kind 30078 `d=belfry:pins` by me on the community relay, NIP-44 v2 to myself,
  `{"v":1,"c":[ids]}`; newest wins (`core/engine/PinSync.kt`, store keys `pins`, `pins-remote-at`;
  published through the outbox 1.5 s after a change, only after the community relay's EOSE, a newer
  one replacing a queued one). Guests, guest mode and the demo keep it on the device.
  `Session.pins`, `togglePin`, `setEveryonePin`.
- **Add a channel** (pushed): one field; the channels filtered on the device, typed part bold and
  underlined; one in my menu opens in Town, one not in it opens pushed as a preview; "Create #name"
  last (any member; hidden when the name exists exactly).
- **Preview:** a town channel not in my list shows a light bar above the composer; once writing,
  reacting, a mention or a pin brings it in, the bar goes and "#x is in your menu" shows ~3 s.
- **Channel settings** (the pull-down's Settings, now for every member): Pin for me, Pin for
  everyone (belfry admins), Notify me of everything (push level `all` / `default`); `general`'s pin
  rows are on and locked. Admins then see the channel's form as before.
- **The composer's ＋:** Photos, File (a channel attachment, 25 MB at most), Audio recording,
  Announcement (admins); then "Create in #channel": Event, Meeting, To-do, Proposal. Group and Drop
  box link left it (GROUPS ＋; the Files screen).

<<<<<<< HEAD
### 19.54 One shape: the e-mail thread (2026-11-22; contract collective-belfry docs/one-shape.md 4; canvas one-shape/email-*)

- **Who it was for.** Inbox and sent cards are read for `["to", address, name]` and
  `["cc", address, name]` (`MailInbox.people`: at most 20, first of each address, lower case).
  A card with a Cc shows "Cc Dev Patel, park@example.org" on its second line.
- **Reply all is the only reply** (a round icon on each received e-mail). Recipients =
  `OneShape.replyAll(mail-from of the latest received e-mail, its tags, domain)`. The kind 1301 has
  one `rcpt-to` per recipient (sender first, no duplicates, max 20), `["e", <the e-mail answered>]`,
  and `["draft", <id>]` when it sends an internal draft. MIME: `To: <sender>`, `Cc: <a>, <b>`,
  In-Reply-To / References as in 19.33.
- **Answered is automatic.** After the 1301's delivery report (7679) says `delivered`, the sending
  device queues the `belfry/inbox` label `answered` on the thread's first card, once per e-mail
  (`Session.answerOnDelivery`; kept in memory: a device that dies before the report arrives does
  not label it). Readers: `answered` → Answered; a received e-mail newer than the newest
  answered / closed label reopens (`MailInbox.state`, `OneShape.threadState`). Anyone may also
  "Mark answered" by hand.
- **Internal drafts**: kind 1111 on the thread's first card with `["mail-draft", "1"]`, by a
  channel admin (`Actions.mailDraft`); a non-admin's is a plain team comment. Its author edits
  (1010) or deletes (5) it. Hidden once a sent card names it (`["draft", id]`) or deleted
  (`InboxThread.drafts`, `OneShape.visibleDrafts`). Drafts never count as comments.
- **Undo**: Send waits 8 s in memory (`ReplyQueue`, "Sending to Lou and 2 more… Undo · 8")
  before the 1301 is wrapped; it is not an outbox item (gift-wrapped mail never was), and leaving
  the thread sends at once.
- App: three states (`MailLevel` TINY / MID / FULL; MID while open, TINY once answered or
  closed); the composer is the team's ("Comment for the team"), no Reply / Comment switch.
=======
### 19.54 One shape: the to-do (2026-11-22; canvas one-shape/todo-conversation, -mid, -pulled, -done; collective-belfry docs/one-shape.md 3; the same rules as belfry-ios `TodoShape.swift`)

- **Due date**: `["due", "<unix seconds>"]` on the kind 2100 (`OneShape.due`). The author sets,
  changes or clears it with a kind 1010 naming the to-do: content = the title, tags = every tag
  of the 2100 except `due` (t, h, q…), then `["e", <2100 id>]`, `["k", "2100"]` and the new
  `["due", …]` (none = cleared) (`Actions.setTaskDue`). Readers take the author's newest 1010
  (created_at, then the higher id; deleted ones and anyone else's ignored): its due wins, one
  without a due clears it (`Task.due`). Set with Material's date and time pickers, never typed.
  The Log says "set a to-do's due date" / "cleared a to-do's due date".
- **Header**: two lines: the round tick + the title (struck when done); the icon row: "✓ Done
  <day>" (when done), 👤 the assignee's name (text colour) or "Unassigned" (grey), 💬 comments,
  📁 files, 🖼 photos (links in comments; each left out at 0). No due date and no "from…" in it.
- **Done, quietly**: no Done button; the tick fills green with a white check mark (the mark, not
  the colour, tells an e-ink screen), using the existing 2101 done / undone. Tapping again reopens.
- **The conversation** (`World.todoLines`): comments (2103) and quiet lines, oldest first, by day:
  "made this to-do (from <name>'s message)", "took it", "let go of it", "gave it to <name>",
  "took <name> off it", "marked it done", "reopened it", "set the due date to Sat 28 Nov, 9 AM"
  (each author edit that changed it; "cleared the due date"). Heartbeats make no line.
- **Levels**: tiny (the header), mid (the due line, two lines of the description + More), full
  (also "From <name>'s message ›" and the rows Claim / Assign to… / Unassign / due date / Delete).
  Opens mid while it is open and on me or on nobody (`todoNeedsMe`), tiny otherwise. Tap the
  title or drag the header to change level. The description is the text of the message it was
  made from when that says more than the title (`todoDescription`). Lists show "· Due …" after who.
>>>>>>> origin/one-shape
