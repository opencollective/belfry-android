# Parity with the web chat

Reference: the web chat, `commonshub/collective-belfry` branch `chat-source` (= `village-app`), at commit c6579a0 (2026-11-12), Belfry theme; guest mode from b2e968b (2026-11-12). Still missing from that commit: the members map, "New meeting…" at the end of a recording, a few small things named in the rows ("not yet", "partial"). The tables below were written against ef9e1cc (2026-11-10); what the web changed since is in "Since the web's 2026-11-11 changes" and "Web changes of 2026-11-11 and 12 (up to c6579a0)". Belfry events (605d9c7 and later) are in their own section.
Status words: **done** (built, rendered in demo mode in CI, covered by a screenshot and, where
noted, a behaviour test), **partial** (what is missing is named), **not yet**.

Read this first: **nothing has run on a phone or an emulator, and nothing has talked to a real
belfry.** "Done" means the code is written against the protocol contract (SPEC.md + the shared
vectors, which the core passes), compiles, passes lint, and renders correctly with the seeded
demo community under Robolectric. Sign-in against a live belfry, the signer (NIP-46), real
relays, uploads, the photo picker, the keyboard, the share sheet and back gestures are untested
in the real world.

## Protocol core (`core/`, JVM tests)

| Area | Status | Notes |
|---|---|---|
| Events, ids, BIP-340, bech32, NIP-44 v2, NIP-59 unwrap | done | All of `events.json` and `nip44.json` (except the 10 MB invalid-length case). |
| Channels, messages, reactions, threads, mentions, actions, active members, admin rights | done | Every case of groups / messages / reactions / threads / mentions / actions / recently-active / can-admin `.json`. |
| Markdown subset, MIME, mail threads, outbox pass | done | Every case of markdown / mime / mail-threads / outbox `.json` (outbox: refused uploads are permanent, SPEC 19.17). `htmlText` is regex-based (the web uses the browser's parser). |
| Channel references (NIP-19 naddr, kind 39000) | done | Every case of `channel-refs.json` (made with the web's nostr-tools). |
| Relay (NIP-42, backoff, re-subscribe), remote signer (NIP-46), discovery, PKCE sign-in, HTTP API (NIP-98) | done | Tested against fakes (MockWebServer, a fake signer) only. |
| Sync engine: subscriptions, access verdict, outbox that survives restarts and flaky relays, attachments upload | done | Engine tests against a fake relay. Not covered by a test: `CLOSED restricted` on a subscription, the 15 s watchdog, online/offline flapping. |
| DMs (NIP-17), old DMs (kind 4, read), e-mail (Nostr Mail), delivery reports, large mails | done | Round trips with local signers. Large mails (AES-GCM from Blossom), kind 4 and `sendAsChannel` have no test. |

## Sign-in and joining

| Web feature | Status | Notes |
|---|---|---|
| Native sign-in (Android / iOS only; the web has none): `name@domain` → 6-digit code by e-mail → session, no browser | done, never run live | SPEC 19.20, the belfry's `POST /session` + `/session/verify` (sessions.py) signed by this device's session key. The default. Fixed messages per `error` key; the masked address is shown only when well-formed; the signer's key must be the one the belfry named; the pending request survives the app being killed. Tested against fakes (MockWebServer + a fake signer) and as screenshots; no real code was ever mailed to this app. No SMS / e-mail code autofill hint (needs a newer Compose). Since 2026-11-12: belfry.json's `sign_in.sessions.api` says a belfry has it (the address called; absent → the browser, nothing asked), and 503 `signer` (the belfry's signer is not answering, the code is spent) keeps screen 2 with "Send a new code" unlocked at once. |
| Sign in with e-mail + code through `/connect` (code + PKCE → bunker) | done, never run live | Now the second way: the row "Sign in with the browser instead" (domain taken from the address typed), and the main button on an older belfry without `/session`. Custom Tab → `sh.belfry.app://connect`. Reads only `code` / `state` / `error`; fixed messages. The happy path (token exchange then bunker connect) has no automated test. |
| Opens offline from the saved session | done | Session key and bunker URL encrypted with an Android Keystore key (cannot run under Robolectric; untested). |
| Guest link `https://chat.<domain>/join/<token>` with no account here: **guest mode** (web b2e968b, SPEC 19.26) — no name screen, one loading screen "Opening # channel…" until the relay sent the channel, then the channel read-only with "Guest mode · You’re reading # … as a guest." + Join | done, never run live | `POST …/join` `{}` signed by the new session key; the answer's `pubkey` is used (signer not asked); `session_taken` → once more with a fresh key. Every write is refused in `Session` (send / enqueueSigned / API writes / uploads) and opens the Join sheet; no seen mark, no first #general. Long-press and "add a reaction" open the Join sheet. Join sheet: name + e-mail → "Check your e-mail" with the address typed, six boxes, Send a new code (30 s), Use another address; `taken` → "Sign in instead" (the guest account is dropped, its session revoked unless `moved`; the native sign-in opens, prefilled with the answer's `nip05` (collective-belfry d2b4c52) or the typed address when either is a `name@domain` of this belfry, else a hint; a wrong code says "N tries left" from `tries_left`; the channel opens once signed in). Behaviour tests in demo (long-press, join with a wrong then the right code, moved), engine tests against MockWebServer. Before: a name screen. One session key per invite kept until the join succeeds. `assetlinks.json` is served (SERVER-NEEDS 1): links open in the app with the signed release APK only, not the debug APK; hosts fixed to `chat.belfry.sh`, `chat.enspiral.eu`. |
| Guest link opened by an existing account: joins as itself | done, never run live | Confirmation sheet → `joinWithAccount` → opens the channel. |
| Closed / expired link, no-access screen | done | Fixed messages. |
| Save your account (guest with a name: e-mail + code) | done | Bar above the composer after the first action, and a card on Me; both open the guest-mode Join sheet without the name field (since b2e968b). Never shown in guest mode (the Me card says Join there). |
| Scan an invitation (Android only): a QR scanner on the sign-in screen | done, never on a device | Row "Scan an invitation" on the sign-in screen (and so in "Add an account"). CameraX frames read by ZXing: no Google Play services. The camera is asked when the scanner opens; refused / no camera / camera busy each have a fixed text. Only what `InviteLink.parse` takes (`https://chat.<domain>/join/<token>`, also `/#/join/`) counts and goes where an opened link goes; any other code: "This isn’t a Belfry invitation." and it keeps scanning. Tested: the decoder on drawn codes, the parser, the screens. Not tested: a camera. |
| Several accounts on one device (Android only; the web has one per browser profile) | done, never on a device (SPEC 19.24) | The drawer's bottom-left opens the accounts: switch, "Add an account" (the sign-in screens over the app, a back arrow returns), Me. Two identities in one belfry or accounts in different ones. Everything is per account (session key and bunker URL, database, outbox, drafts, settings, push device key); the one account from before is migrated in place (same names: nobody is signed out, the outbox keeps its items). **Limits**: one account is live at a time, so what a non-open account's outbox holds stays queued until it is opened; the dot on another account only comes from a push notification; in one belfry only one account (the open one, else the first) gets pushes, because the belfry keeps one owner per device token (SERVER-NEEDS 12). Guest and event links go to the account of their belfry (asked when several; none: the join flow adds an account). |
| Sign out | partial | Signs out of the open account only (another account of the device opens next). Wipes that account's copy and revokes the session through the sessions API (see SERVER-NEEDS 4). Does not open the belfry's logout address, so the browser session stays signed in (the web ends it). |
| Demo mode (`?backend=demo`) | done | "Try the demo" on the sign-in screen: a seeded local community, no network. |

## Layout and navigation

| Web feature | Status | Notes |
|---|---|---|
| ☰ channel drawer (push drawer), Messages with unread count, you at the bottom | done | `ModalNavigationDrawer`; channel tree with pod shapes; "All events"; "Your events". |
| Laptop layout (≥ 900 px: list stays) | partial | Permanent drawer at ≥ 840 dp. Tablets (2026-11-11): one APK, the layout follows the window; `TabletShotsTest` renders 10" landscape, 7" landscape and 10" portrait (a portrait tablet gets the phone's drawer). Not tried on a device. |
| Thread in a third column on large screens (≥ 1200 px) | partial | ≥ 1200 dp: 400 dp column with a close button, another thread replaces it. Compiled, never rendered (no wide screenshot). The web's "Open thread" caret and active-row mark are not here. |
| Back arrows are hierarchical, never push | done | `popBackStack` only; predictive back enabled in the manifest (untried on a device). |
| One summary line under the channel name + pull-down (Pinned, Coming up, To decide, To do, Pods, Topics, people) | done | Priority order and counts as the web, unit-tested. Differences: in a proposal's chat the vote is a pill that opens the proposal page (the web votes inside the pull-down); day labels are fixed English, 24 h. |
| Pod marks: a polygon with one side per member | partial | In the drawer, the line and the pull-down. Channel info, pod page and cards still use the generic pod glyph. |
| Connection states (offline, waiting, signer trouble) | done | Banner from the engine's status. |

## Channel feed

Since the web's 2026-11-11 changes:

| Web feature | Status | Notes |
|---|---|---|
| Replies always open the thread (no inline replies) | done | |
| Drawer: Upcoming events, @Mentions (new screen), Direct messages; Public channels, Members only | done | |
| Proposal A: replies are one line with the start of the last reply; a reply to an older message quotes it in one grey line | done | The to-do mark before the reactions is gone again (web 9768c93: "the ⏳ / ☑️ reactions say it"), here too. |
| To-do steps in a thread (made, taken, started, done; merged when together) | done | `TaskSteps` (core, unit-tested); lines between the replies of the thread screen. |
| Public channels show a globe | done | `World.webPage` reads the `belfry:web:<channel>` setting (its admins' or the relay key's). |
| #proposals / #events / #bookings: one type of post (a New proposal / New event bar instead of the composer) | done | A new event made from #events does not default to Public yet. |
| #requests: a member turns a request into a proposal | done | Message menu → the new-proposal form starts from the request and links back; the request's thread says so. |
| Branding (logo, cover, colours from belfry.json) | done | See the next table. |

Web changes of 2026-11-11 and 12 (up to c6579a0), each checked against the web's code:

| Web feature (commit) | Status | Notes |
|---|---|---|
| To-dos: the one being worked on now (e597816, d6cfae1: 2101 `working` heartbeat fresh for 5 min, `idle` stops it; a ring filling with the minutes of the stretch, "1h" past an hour) | done | `Task.beat` / `since` / `isWorking` (core, unit-tested); `WorkRing` in the pull-down and the To-dos tab, "X is working on it · 14 min". The demo seeds one. The screen's clock moves every 30 s. |
| To-do steps in the thread, done/total (91c1b6b) | done | Was done on 2026-11-11; the pull-down's title now says done/total too. |
| Live updates: probe the sockets, reopen a closed subscription (49d378d) | done | OkHttp pings every 25 s find a dead socket by themselves; on top: a probe when the app comes to the foreground (a tiny REQ, 6 s, else drop and reconnect), and a live subscription the relay CLOSED for a reason that is not a refusal is opened again (2 s, then longer). Fakes only: a silent socket cannot be simulated in the tests. |
| Sending after a dropped connection (b86d662) | done | A stalled outbox tries again after 1.5 / 3 / 6 / 12 s (not the 15 s watchdog); a publish that gets no answer drops the socket and reconnects at once; the signer is asked once more on a fresh connection (10 s each); "Sending again…" while the relay is connected. |
| Drafts per channel and per thread (f845d47) | done | Kept in the device's store (`draft:c:<id>`, `draft:t:<root>`), with the picked @people and #channels, 30 days; sending clears it; an edit in progress leaves it alone, and the draft is back in the box once the edit is sent or cancelled (behaviour test). Core + session tests; untested on a device. |
| Drafts of the forms: new event, proposal, todo, channel (5ccc71f) | done | `FormDrafts` (core) + `rememberFormDraft` (kit): the fields are kept in the device's store under the web's keys (`form:event:<visibility>`, `form:proposal:<parent>`, `form:todo:<channel>`, `form:channel`), 30 days, cleared once sent; not for an edit or a proposal made from a request. Core + session tests, one behaviour test (new channel). The event form keeps its dates too, as the web (an old draft opens with old dates). |
| Images open in a lightbox (dabedd9, 9768c93: swipe through a message's pictures, swipe down closes) | done, never on a device | The viewer swipes through the pictures of one message (and the pull-down's photos), "2 / 5". Pinch to zoom (to 5×), drag a zoomed photo, double-tap zooms in on that spot and back, swipe down closes (the black fades as it is pulled), a tap / ✕ / back too. Zoomed: no page turn, a tap shows the photo whole first. Behaviour test (double-tap, swipe down) under Robolectric; pinch itself is untested. Zoom changes are not animated. |
| E-mail threads: the subject in the header, not in every bubble (4bde2b2) | done | Was so already. |
| Branding: admins set logo, cover and two colours, Me → Branding (ab57a9d) | done, never run live | Logo before the community's name and the cover at the top of the drawer; primary = accent, secondary = count badges; `POST <api>/branding` with fixed messages for `not_admin` / `invalid` / `no_key`; uploads through the file server (logo ≤ 512 px, cover ≤ 1600 px). Colours: a hex field and eight swatches (the web uses the browser's picker). Kept in the saved session, so the colours are there before the belfry answers. No live preview while picking. |
| Channel settings: Visibility (Public / Members only), threads from the web with a page at `<domain>/<slug>` (23c9b83, e139d5f) | done, never run live | In the settings sheet (not for pods); the same NIP-78 event as the web (`posts` kept); the address is checked like the web (two letters, not the belfry's own paths, not another channel's). About shows the page's link. |
| A channel's e-mail address is its plain slug, `bugs@` (9768c93) | done | Shown in About and in the settings; answers still go out as the full id. |
| Event form: "Hidden" instead of "Link" (7504d77) | done | Was so already. |
| Channel pull-down: description first, latest photos, members as faces (9768c93); the second level, "pulled all the way" (91c1b6b) | done | The web's order (description, pinned, coming up, to decide, to do, pods, photos, members, topics). Two levels as the web's header: the handle (tap or drag), "More" and "All todos" pull it all the way: it then takes the feed's whole room and shows the latest 20 todos (done ones after the open ones, tick to reopen) with a field to add one, a mosaic of 12 photos, up to 24 bigger faces with names, then the rest about the channel (whole description when it has several lines, Invite people · Settings, a pod's Leave / Join, the e-mail line, its web page, its id). Back goes one level up. Behaviour test (handle → all the way → add a todo → close). The photo row shows six (the web's row scrolls sideways through 24); the levels do not follow the finger while dragging. |
| Meetings: ＋ → Meeting, people grid, add someone by name or e-mail, hand an account over, check-in (78d7424, 2215f2a, 6029788) | done, never run live | SPEC 19.23. ＋ → Meeting (not for guests): topic, people picked by tapping faces (the channel's most active first), "+" for a name or an e-mail (members who match are offered; anyone else gets an account), when, how long, where (last place, recent places, a video link); kept as a draft. Made as the web makes it (`Meetings`, core, unit-tested): the event with `t` meeting / `channel` / `p … invited`, a closed `ev-<d>` chat, a 9000 per member; a later version keeps those tags. The event's page says "Meeting · members only" and lists its people with their answer; hosts check people in (`belfry/attendance` labels, one per change), add someone by name and hand over an account they hold. Rows say "Meeting · N people"; "Your events" includes meetings I am invited to; the Log has its words. The two belfry calls (`POST /meeting-guests`, `/meeting-guests/transfer`) ran only in the demo, where the app plays the belfry; their errors are fixed messages. Behaviour tests: one tap makes one meeting; a check-in sends one mark. Not here: a meeting's card in a channel is the ordinary event card ("N going"); the picker's faces are not searchable beyond the "+". |
| Audio files play in the feed (59c4b10) | done, never on a device | An attachment with an `audio/…` type is a player in the message: play / pause, position and length, one recording at a time, streamed from the file server only when played (`MediaPlayer`; WebM Opus and MP4 AAC are both formats Android plays). No seeking; the player is let go when the row scrolls away (playing stops); what the phone can't play opens in the browser. Rendered idle in CI; no sound was ever played. |
| Audio recording: ＋ → Audio recording, pause / resume, saved as it records (59c4b10) | done, never on a device (SPEC 19.25) | ＋ → Audio recording → Start (the microphone is asked then). `MediaRecorder` writes AAC-LC (one channel, 32 kHz, 48 kbit/s) in ADTS to `files/recordings/<id>.aac`: a plain run of frames, whole up to the last one written when the app is killed. A foreground service of type `microphone` (notification: Pause / Resume, Stop) and a wake lock keep it going with the screen off; a call pauses it and it goes on after; another app taking the audio pauses it until Resume; under 15 MB free it stops and is kept. Posted as the web posts it: one message "🎙 Recording · 12:34[ · N parts]" with the files attached, named `recording-YYYY-MM-DD-HHMM[-partN].m4a`, through the outbox. **Differences from the web**: the file is .m4a (AAC, `audio/mp4`: what Safari's web recorder posts) made on the phone from the .aac without re-encoding, so it plays in Chrome, Firefox and Safari; should making it fail, the .aac itself is posted (`audio/aac`: plays in Chrome and Safari, in Firefox only where the system has an AAC decoder, and without a reliable length); a part is 30 minutes (about 11 MB), not an hour; over three parts (1 h 30) each part is its own message ("· part 2 of 5") so no message holds more than a phone's memory takes; "New meeting…" at the end is not there (pick an event or the channel; make the meeting first). A recording left on the device (stopped, or cut by a kill) is offered above the composer: "Post it" / "Discard". Recordings belong to the account they were made in. Tested: the rules, the file formats (byte for byte against a writer checked with ffmpeg), finding and posting a cut file. Not tested: a microphone, the service, the notification, focus changes, a real upload. |
| Channel info: tabs About, Members, Todos, Files, Log; About without boxes; settings behind a gear; a Log of every event (c6579a0) | done | The web's tabs in its order. About: the description, "2/12 active members · Invite", what a pod is in and how one joins (Leave / Join / Ask to join), the e-mail line (a tap copies the address), its web page, its id; plus three links the web has elsewhere (Topics, Search, Announcements by e-mail). The gear (admins) opens the settings sheet. Members and Todos start with a line that sums them up. Log: `ChannelLog` (core, the web's `logItem` word for word, unit-tested): one line per event with the channel's `h`, newest first, at most 1000, a day per section with the days as chips to jump; a tap unfolds the detail, the full date and "Go there" (the message in the channel, the thread, the Todos or Members tab). Developer mode (Me → Developer mode, shown to people who administer something; kept on the device): a channel's admins get "Inspect the Nostr event" (the event as indented JSON, Copy). Behaviour tests for both. `Routes.channelInfo(id, tab)` opens a tab (the pull-down's All members / Todos / Log, the line's Review). The members map is still a list; the white-screen fix is a web matter. |
| Sheets scroll on phones (5ccc71f), full pull-down swipe (1bbbb05) | n/a | Web layout fixes. |


| Web feature | Status | Notes |
|---|---|---|
| Messages, day separators, grouping, Markdown, links (http/https/mailto only) | done | |
| Mentions (@ picker, highlight when it is me, tap → profile) | done | The picker marks "not in this channel"; a note above the field says a picked person can't read the channel, with Invite / Add as a guest (locks while it runs). |
| #channel references (# picker → `nostr:naddr1…`, "#name" that opens the channel, previews) | done | Behaviour test (pick → sent as the reference, no topic). Edit shows them as "#name" again. An unknown channel reads "#channel", another naddr "Nostr address" (the web shows the id / the raw text). Not offered in direct messages (the web's DM box has no picker either). |
| #topics in text (tap filters the channel) | done | Hold a #hashtag to pin/unpin: not yet (pinning is on the Topics page instead). |
| Photos in messages, full-screen viewer | done, never run live | System photo picker, downscaled to ≤ 2048 px JPEG. Orientation is not corrected on Android 8.0 / 8.1. |
| Other files as attachments | not yet | The composer attaches photos only; received files show as rows that open in the browser. |
| Reactions (quick row, most used first, add, undo) | done | |
| Threads: chip, inline unfold (latest 3, show earlier, reply box), "Open thread" screen, resurfacing lines | done | |
| Edit my message (kind 1010), "edited" mark | done | Behaviour test. Foreign edits are ignored before picking the latest (the web lets one hide the author's own: SPEC 19.1). |
| Delete my message, with an in-app confirmation showing the message | done | Behaviour test. |
| Message menu as a list (Reply, Open thread, Edit, Copy text, Copy link, Message the author, Delete) | done | Bottom sheet, Discord-style rows. |
| Pin to the channel / Unpin (admins), pinned mark | done | Behaviour test. The web shows no mark on the message; here it does. |
| Turn into a to-do; the mark on the message ticks it off | done | Behaviour test (one 2101 per tick). |
| "Make this the website" (admins, messages with an nsite link) | done, never run live | Row in the menu → `publishWebsite`. `nsite:` links render as links to the preview host. |
| Outbox states on messages: Sending… / Waiting for connection… / Not sent · Retry, Discard | done | Send cannot fire twice (behaviour test). A file the storage refuses (4xx but 401/408/429) fails its message without blocking the queue (engine test). A message that could not be saved keeps its text, photos and picks in the box. |
| Announcements (admins), marked in the feed | done | |
| Guest join lines ("joined as a guest / an agent, invited by …"), promoted members not called guests, "not saved" | done | Kind 1985 `belfry/guest` labels. Guest mode (b2e968b): a key whose latest 9000 carries `reading` has no line and is left out of members (also the relay's 39002), counts, "active", the channel line and mention / invite pickers until a later 9000 without it, a `saved` / `member` label or a 9001 (`vectors/guestmode.json`); Members says "N guests are reading". Held meeting guests are not hidden. A member's own agent: "joined as <owner>'s agent" ("your agent" when mine; the web writes "You's agent"). |
| "Created a pod / an event / a proposal" line + card | done | See cards below. |
| Empty channel with "Invite people" | done | |
| ＋ menu: Photo, Event, Pod, Proposal, Todo, Drop box link | done | The word is "Todo" everywhere now, as the web ("New todo", "Turn into a todo", "Add a todo…", "made a todo"). |
| Seen events (every 6 h) and "active this month" | done | |
| Search (this channel / all, accent-folded, marks, opens on the message) | done | Behaviour test. Snippets keep `*italic*` marks. |

## Channel info

| Web feature | Status | Notes |
|---|---|---|
| About: description, active / all members with Invite, a pod's parent and access, the e-mail line, the web page, the id | done | As the web since c6579a0 (no boxes). Events, pods and proposals are in the pull-down, not here. |
| Members: list, guests / agents / not saved, invited by, admin mark, labels editor | done | |
| Members map (Leaflet) | not yet | A list with each person's place instead (per channel and community-wide, grouped by country). |
| Todos: add, tick, take / let go, comments, delete own | done | Screenshots only, no driven test (adding one from the pull-down is driven). |
| Log: everything that happened in the channel | done | See "Web changes of 2026-11-11 and 12". |
| Files: photos grid, files, drop box links | partial | Photos open in the viewer; other files open in the browser (no in-app download). |
| Settings (admins): name, description, pod open/closed + question, "E-mails from anyone" | done | Behind the gear in the top bar (and "Settings" under the pull-down pulled all the way). |
| Join / ask to join / leave a pod; admit / decline applications | done | |
| Invite: member link, QR, copy, share | done | System share sheet. |
| Invite someone who is here already (members, guests, agents with kind labels; DM with the channel's link; admit in a closed pod I administer; "Add as a guest" → `/channel-guests`) | done, never run live | Behaviour tests: the button locks (one call), a member gets exactly one DM with `https://chat.<domain>/#/c/<id>`. `/channel-guests` only ever ran in the demo. Guest / agent / member comes from the belfry's `/whois` when it answers (events otherwise); the web still reads events only. |
| "My own agent" link (`agent: true`, shows `agent_url`) | done, never run live | Behaviour test (asks for an agent link, people 1; switching to a guest link asks again). Hidden when the belfry gives no limit; off with the reason when it is reached. |
| Guest link: one person / up to 50; 1 day / 1 week / 1 month; QR | done | Behaviour test (button locks; one request). Controls lock while a link is made (the web lets a newer choice win). |
| Follow (bell): off / as they happen / monthly, my other follows | done, never run live | |
| Drop box links: new (duration, anyone / members), QR, share, end | done | The drop page itself is the belfry's web page. |
| Topics: list with counts, pin / unpin, filter the channel | done | A page (the web uses a hold menu). No admin check for pinning, as on the web. |

## Pods, events, proposals

| Web feature | Status | Notes |
|---|---|---|
| Older events (`ws-` chats, 📅 post): card, page, RSVP, delete | done | Still read and answered; new events are made as Belfry events. |
| Pods, proposals, create channel / proposal | see below | |

## Belfry events (web belfry-chat 605d9c7 → ef9e1cc; SPEC 19.18)

| Web feature | Status | Notes |
|---|---|---|
| Event model: `d` = year-6, group `ev-<d>`, rule B (newest version by a host), status label, versions, visibility, slug, cover, channels its card is in | done | `core/model/Events.kt` + `World.calendar`; JVM tests `EventsTest` (rule B, holders, applications, declined, places, status, still going, cards from URLs, API answer). |
| Cards from URLs: a message with `https://<domain>/events/<link>` gets the event's `a` tag when sent and shows up to three cards; receivers resolve URLs without the tag (guests through the belfry's API) | done | `EventCards` under the message text; the older 📅 post stays the card alone. Going / Maybe in one tap on the card for the plain RSVP, else an "RSVP" / "Apply" mark. Behaviour test: one tap, one answer. |
| All events: Upcoming / Mine (includes hosting) / Hosting / Past; New event | done | Rows with cover or date chip, "Cancelled" badge, my state · N going. |
| "Your events" in the drawer (replaces "Event chats") | done | Hosting or holding a place, upcoming and up to 30 days after. Older `ws-` chats are no longer listed in the drawer (as on the web); they open from their event. |
| Event page: cover, the one line (cancelled / changed · still going? Yes-No / N people ask · Review / my state / RSVP), when, where, online link (or "shows once you have a place"), my RSVP (Going / Maybe / Can't go, my application + Withdraw, declined), hosts, tickets, description, URL (copy, share, QR), guest list, the event's chat, edit | done | Android shows it as one page; the web pulls it down over the event's chat. The event's chat is its own channel one tap away, with a bar under its name back to the event. Behaviour test: RSVP on the line fires once. |
| Take a ticket / apply with the ticket's question / waitlist when full / members-only tickets | done | Member + implicit ticket = RSVP; other open tickets: `POST /events/<d>/join` (demo: the groups key issues the 9000); applications: a 9021 to the group (a member) or signed and handed in through `POST /events/<d>/apply` (a guest). Behaviour test: one application. |
| Hosts: guest list (Going / Asking / Waitlist / Others), approve / decline, make co-host | done | Members decide on the relay (9000 with `ticket` / 9001), a guest co-host through `POST /events/<d>/decide`, as the web. Approve and Decline share one lock. Behaviour test: one decision. |
| Tickets sheet: name, summary, places, open / needs an application + question, members only; delete | done | A closing ticket closes the event's group (NIP-29 `closed`), as the web. |
| Co-host invitation link (channel invite, `role: host`) with QR | done, never run live | Demo returns a fixed link. |
| Co-host link opened by someone new: save-your-account bar at once with its reason, then the link is used again with the account | done, never run live | `email_needed` in the join answer → `GuestInfo.coHost`; after the code is verified, `joinWithAccount(token)`. |
| Cancel (with a line for the guests) / reopen; delete (creator) | done | |
| Create / edit: title, cover upload, all day, start / end, where (suggestions), online + Jitsi, Markdown description, who sees it (members / link / public), who can come (anyone / by application + question), URL checked as typed (`GET /events/slug`, `PUT /events/<d>/slug`) | partial | No time-zone picker: times are in the device's zone (written as `start_tzid`). Made from a channel's ＋, the URL goes into that channel's composer, nothing is posted (as the web). Behaviour test: one event, URL handed to the composer. |
| Opening an event's address from outside the app | done, never run live | `https://chat.<domain>/events/<link>` (the belfry redirects it to the web's `#/events/<link>`) is claimed by the App Links filter (`/events/`, hosts `chat.belfry.sh`, `chat.enspiral.eu`); only the domain and the link are read. Signed in: the event page (unknown → "There is no such event here."); another community: a fixed message; signed out: opens after sign-in (kept in memory only). `https://<domain>/events/<link>` itself opens in the browser. |
| Signed-out event page (RSVP with a name / apply with an e-mail, new guest identity) | not yet | The web's `EventJoinScreen`; Android asks to sign in first. |
| Event read through the API (guests): page, card, RSVP on the groups relay with `h` | done, never run live | Cached a minute; `members_only` / `not_found` → fixed messages. |
| Prices, payments, check-in | not in v1 | Neither on the web. |
| Pod card, pod page (about, access, requests, members, join / apply / leave), create pod | done | The pod page is Android's own (the web shows it in channel info). |
| Proposal card and page: text, status, tally, vote, change status, new version | done | Behaviour test: one vote. Open proposals = draft, feedback, voting (SPEC 19.13 question). |
| Create channel, create proposal | done | Create proposal also offers "Votes" as a start (the web: Draft, Feedback). |

## E-mail in channels (shared inbox)

| Web feature | Status | Notes |
|---|---|---|
| A member's e-mail shown as their message ("via e-mail", subject) | done | |
| "Full e-mail" (original from Blossom, AES-GCM) | partial | Coded, never run against a real mail; fails with a fixed message in the demo. |
| Inbox cards: sender, verified, subject, status (Open / Answered / Closed), take / let go, close / reopen | done | |
| Thread chip on an inbox card ("Start a thread / Thread · N") | not yet | Threads work on ordinary messages. |
| Reply by e-mail as the channel, 8 s Undo send, delivery line | done, never run live | Admins only, as the web. Behaviour tests for Undo (sends nothing) and elapse (sends once). |
| Not on the web either: review drafts, rules, team notification e-mails | n/a | |

## People and messages

| Web feature | Status | Notes |
|---|---|---|
| Profile: picture, name, address, about (Markdown), tags, place, guest chips, shared channels, Message | done | |
| Agents on profiles: "agent" chip, "agent of <owner>", "Make it my agent", "Remove this agent…" with an in-app confirmation | done, never run live | Behaviour tests in the demo (make mine, remove after confirming; a member's agent says whose). `POST /agents` never ran against a belfry. Owner and admins only, as the web. |
| Edit profile: name, username (`POST /username`), about, picture, tags with suggestions, place (city search, add a place, country only, nothing) | done, never run live | Full screen instead of a sheet. Picture: system picker → ≤ 512 px. Never device GPS. |
| "Admin of the belfry" switch, with the guest rules (`guest_not_saved`, `agent`, `not_member`, `last_admin`) | done, never run live | Behaviour test: disabled with the reason for an unsaved guest. |
| Me: my card, username, key copy, devices, outbox counts, sign out | done | My address follows my profile (each snapshot reads it; the web fixed this in cf55613). |
| Connections (signed-in devices): list, this device, end one | done, never run live | Titled "Signed-in devices". |
| Messages list (DMs + e-mail threads, unread) | done | |
| New conversation (name, name@domain, npub / hex, e-mail address) | done | |
| Conversation (NIP-17): Markdown, state line, Retry | done | Behaviour test: a DM cannot be sent twice. The @ picker shows but a pick stays plain text. |
| E-mail threads: by subject / references, verified sender, delivery state, new e-mail needs a subject | done | |
| E-mail attachments | partial | Listed with sizes; a tap saves through the system file saver (never run). No preview. |

## Not in the web chat, or out of scope

| Item | Status | Notes |
|---|---|---|
| Push notifications (docs/push.md; the web has none yet) | built, never run | FCM data-only, NIP-44 under the device key, five notification channels, tap opens the channel at the message (host checked); register on every start, remove at sign-out; Me → Notifications (switches, my channels' levels, this phone's state, "Turn on notifications") and the bell's "Push to this phone" level. Core vectors (`push.json`), demo shots, behaviour tests (switches and levels lock and send once). Needs a Firebase project + the `GOOGLE_SERVICES_JSON` secret (SERVER-NEEDS 3); CI builds with push off. No devices list. |
| OSV access grants (kinds 8534-8538), the OSV admin screen | n/a | Hidden in the Belfry theme on the web too. |
| Release build: signing key, R8 rules, Play listing | not yet | CI builds a debug APK (artifact `belfry-debug-apk`). |

## Known rough edges seen in the renders

- The seeded photo is a grey placeholder (network pictures are off in tests).
- `AvatarStack` leaves a small gap at its right (thread chip, About).
- Vote rows have no leading icon until one is mine.
- Emoji and text render in Robolectric; spinners show as a dot (frozen animation clock).
