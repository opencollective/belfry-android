# Parity with the web chat

Reference: `village-app`, branch `belfry-chat`, at commit ef9e1cc (2026-11-10), Belfry theme. Belfry events (605d9c7 and later) are in their own section below.
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
| Sign in with e-mail + code through `/connect` (code + PKCE → bunker) | done, never run live | Custom Tab → `sh.belfry.app://connect`. Reads only `code` / `state` / `error`; fixed messages. The happy path (token exchange then bunker connect) has no automated test. |
| Opens offline from the saved session | done | Session key and bunker URL encrypted with an Android Keystore key (cannot run under Robolectric; untested). |
| Guest link `https://chat.<domain>/join/<token>`: join as a new guest with a name | done, never run live | One session key per invite kept until the join succeeds. `assetlinks.json` is served (SERVER-NEEDS 1): links open in the app with the signed release APK only, not the debug APK; hosts fixed to `chat.belfry.sh`, `chat.enspiral.eu`. |
| Guest link opened by an existing account: joins as itself | done, never run live | Confirmation sheet → `joinWithAccount` → opens the channel. |
| Closed / expired link, no-access screen | done | Fixed messages. |
| Save your account (guest: e-mail + code) | done | Bar above the composer after the first action, and a card on Me. Only layouts tested. |
| Sign out | partial | Wipes the device and revokes the session through the sessions API (see SERVER-NEEDS 4). Does not open the belfry's logout address, so the browser session stays signed in (the web ends it). |
| Demo mode (`?backend=demo`) | done | "Try the demo" on the sign-in screen: a seeded local community, no network. |

## Layout and navigation

| Web feature | Status | Notes |
|---|---|---|
| ☰ channel drawer (push drawer), Messages with unread count, you at the bottom | done | `ModalNavigationDrawer`; channel tree with pod shapes; "All events"; "Your events". |
| Laptop layout (≥ 900 px: list stays) | partial | Permanent drawer at ≥ 840 dp: compiled, never rendered or tried. |
| Thread in a third column on large screens (≥ 1200 px) | partial | ≥ 1200 dp: 400 dp column with a close button, another thread replaces it. Compiled, never rendered (no wide screenshot). The web's "Open thread" caret and active-row mark are not here. |
| Back arrows are hierarchical, never push | done | `popBackStack` only; predictive back enabled in the manifest (untried on a device). |
| One summary line under the channel name + pull-down (Pinned, Coming up, To decide, To do, Pods, Topics, people) | done | Priority order and counts as the web, unit-tested. Differences: in a proposal's chat the vote is a pill that opens the proposal page (the web votes inside the pull-down); "Review" / "All to-dos" open channel info (no deep link to a tab); day labels are fixed English, 24 h. |
| Pod marks: a polygon with one side per member | partial | In the drawer, the line and the pull-down. Channel info, pod page and cards still use the generic pod glyph. |
| Connection states (offline, waiting, signer trouble) | done | Banner from the engine's status. |

## Channel feed

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
| Guest join lines ("joined as a guest / an agent, invited by …"), promoted members not called guests, "not saved" | done | Kind 1985 `belfry/guest` labels. A member's own agent: "joined as <owner>'s agent" ("your agent" when mine; the web writes "You's agent"). |
| "Created a pod / an event / a proposal" line + card | done | See cards below. |
| Empty channel with "Invite people" | done | |
| ＋ menu: Photo, Event, Pod, Proposal, To-do, Drop box link | done | |
| Seen events (every 6 h) and "active this month" | done | |
| Search (this channel / all, accent-folded, marks, opens on the message) | done | Behaviour test. Snippets keep `*italic*` marks. |

## Channel info

| Web feature | Status | Notes |
|---|---|---|
| About: description, e-mail address, active in the last 30 days, events, pods, proposals | done | A page behind the title (the web's full pull-down view). |
| Members: list, guests / agents / not saved, invited by, admin mark, labels editor | done | |
| Members map (Leaflet) | not yet | A list with each person's place instead (per channel and community-wide, grouped by country). |
| To-dos: add, tick, take / let go, comments, delete own | done | Screenshots only, no driven test. |
| Files: photos grid, files, drop box links | partial | Photos open in the viewer; other files open in the browser (no in-app download). |
| Settings (admins): name, description, pod open/closed + question, "E-mails from anyone" | done | |
| Join / ask to join / leave a pod; admit / decline applications | done | |
| Invite: member link, QR, copy, share | done | System share sheet. |
| Invite someone who is here already (members, guests, agents with kind labels; DM with the channel's link; admit in a closed pod I administer; "Add as a guest" → `/channel-guests`) | done, never run live | Behaviour tests: the button locks (one call), a member gets exactly one DM with `https://chat.<domain>/#/c/<id>`. `/channel-guests` only ever ran in the demo. |
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
| Notifications | not yet | Later milestone; needs the server (SERVER-NEEDS 3). The app syncs when opened; there is no background service. |
| OSV access grants (kinds 8534-8538), the OSV admin screen | n/a | Hidden in the Belfry theme on the web too. |
| Release build: signing key, R8 rules, Play listing | not yet | CI builds a debug APK (artifact `belfry-debug-apk`). |

## Known rough edges seen in the renders

- The seeded photo is a grey placeholder (network pictures are off in tests).
- `AvatarStack` leaves a small gap at its right (thread chip, About).
- Vote rows have no leading icon until one is mine.
- Emoji and text render in Robolectric; spinners show as a dot (frozen animation clock).
