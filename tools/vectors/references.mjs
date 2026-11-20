// NOTE 2026-11-20: vectors/references.json now comes from the web (commonshub/collective-belfry branch chat-source,
// tools/vectors/check.mjs, 38 cases): copy it from there; this script writes only the first 20.
//
// Writes vectors/references.json: references in a message's text (SPEC 19.44; canvas
// channels/link-preview-end + -inline, to-do c92a9f45). This file is also the reference
// implementation of the rule (parts()), so the three apps port the same steps:
//
//   NODE_PATH=~/agents/belfry/work/village-app/node_modules node tools/vectors/references.mjs
import { createRequire } from 'node:module'
import { writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const require = createRequire(path.join(process.env.NODE_PATH ?? '', 'x.js'))
const { nip19 } = require('nostr-tools')

const domain = 'riverside.example'
const relayKey = '959c24202b2b46602cf8614c4d930f571518551fbf121930b371a2ca321beddd'
const author = 'a2aaecacffe6376d9826d889e5e2985381edab77924eeec51b8e2c25373a5d27'
const relay = 'wss://groups-relay.riverside.example'
const naddr = (kind, pubkey, identifier, relays = [relay]) => 'nostr:' + nip19.naddrEncode({ kind, pubkey, identifier, relays })

// ---- the rule ----------------------------------------------------------------------------------
const URL = /https?:\/\/[^\s<>()]+[^\s<>().,;:!?'"]/g
const NADDR = /nostr:(naddr1[02-9ac-hj-np-z]{20,400})/g
const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const D = esc(domain)
const CHANNEL = new RegExp(`^https://(?:chat\\.${D}|${D}/chat)/#/c/([^/?#]{1,120})/?$`)
const EVENT = new RegExp(`^https://(?:chat\\.${D}/(?:#/)?events|${D}/events)/([A-Za-z0-9_-]{1,80})/?$`)

/** What a token points to, or null when it isn't a reference (it stays as written). */
function target(token) {
  if (token.startsWith('nostr:')) {
    let d; try { d = nip19.decode(token.slice(6)) } catch { return null }
    if (d.type !== 'naddr') return null
    const { kind, pubkey, identifier } = d.data
    if (kind === 39000) return { type: 'group', id: identifier }
    if (kind === 31922 || kind === 31923) return { type: 'event', coord: `${kind}:${pubkey}:${identifier}` }
    if (kind === 30023) return { type: 'proposal', coord: `${kind}:${pubkey}:${identifier}` }
    return null
  }
  let m = CHANNEL.exec(token)
  if (m) { try { return { type: 'group', id: decodeURIComponent(m[1]) } } catch { return null } }
  m = EVENT.exec(token)
  if (m) return { type: 'event', link: m[1] }
  return null
}

/** TEXT as parts: {text}, {ref} (inside the text: a name), {box} (the last one, at the very end). */
function parts(text) {
  const found = []
  for (const re of [NADDR, URL]) for (const m of text.matchAll(re)) {
    const t = target(m[0]); if (t) found.push({ start: m.index, end: m.index + m[0].length, t })
  }
  found.sort((a, b) => a.start - b.start)
  const out = []
  const push = (s) => { if (!s) return; const l = out.at(-1); if (l && 'text' in l) l.text += s; else out.push({ text: s }) }
  const trimmedEnd = text.replace(/\s+$/u, '').length
  let last = 0
  found.forEach((f, i) => {
    if (f.start < last) return
    push(text.slice(last, f.start))
    if (i === found.length - 1 && f.end === trimmedEnd) {
      const l = out.at(-1)
      if (l && 'text' in l) { l.text = l.text.replace(/\s+$/u, ''); if (!l.text) out.pop() }
      out.push({ box: f.t })
    } else out.push({ ref: f.t })
    last = f.end
  })
  if (!out.length || !('box' in out.at(-1))) push(text.slice(last))
  return out
}

// ---- cases -------------------------------------------------------------------------------------
const garden = naddr(39000, relayKey, 'garden')
const crew = naddr(39000, relayKey, 'pod-garden-crew')
const ev = naddr(31923, author, '2026-ab12cd')
const prop = naddr(30023, author, 'quiet-hours')
const article = naddr(30001, author, 'list')
const cases = [
  ['a channel link at the end', 'Seed talk carries on here https://chat.riverside.example/#/c/garden'],
  ['a channel naddr at the end', `Seed talk carries on here ${garden}`],
  ['a group (pod) naddr, trailing spaces and a new line', `If you want to help, ask to join ${crew}  \n`],
  ['the event page people share (slug)', 'Bring anything broken! https://riverside.example/events/repair-cafe'],
  ['the event page by its d', 'https://riverside.example/events/2026-ab12cd'],
  ['the chat host event link', 'See https://chat.riverside.example/events/repair-cafe'],
  ['an event naddr', `Bring anything broken! ${ev}`],
  ['a proposal naddr', `Please vote before Friday ${prop}`],
  ['only a reference: the box alone', `  ${garden} `],
  ['inside the text: names', `Ask ${crew} about the seeds, then add them to ${ev} and vote on ${prop} by Friday.`],
  ['two references: the last one is the box', `Moved from ${garden} to https://chat.riverside.example/#/c/pod-garden-crew`],
  ['a message link (/m/) is not a reference', 'Read this https://chat.riverside.example/#/c/garden/m/' + 'ab'.repeat(32)],
  ['another belfry stays a link', 'Look https://chat.elsewhere.example/#/c/garden'],
  ['a ticket link stays a link', 'My ticket https://chat.riverside.example/events/repair-cafe/t/abcdefghijklmnop'],
  ['the /chat/ path of the main host', 'Here https://riverside.example/chat/#/c/garden'],
  ['an encoded channel id', 'Ici https://chat.riverside.example/#/c/g%C3%A9n%C3%A9ral'],
  ['punctuation after a link: not at the end', 'Is it https://chat.riverside.example/#/c/garden?'],
  ['another kind of naddr stays as written', `My list ${article}`],
  ['a reference then more text', `${garden} is where it happens`],
  ['no reference', 'Just words, https://example.org/page and #garden typed by hand'],
]
const out = {
  about: 'References in a message (SPEC 19.44): this belfry\'s chat link https://chat.<domain>/#/c/<id> (also https://<domain>/chat/#/c/<id>, never with /m/<event>), its event pages https://chat.<domain>/events/<link> and https://<domain>/events/<slug-or-d> (not /t/<code>), and nostr:naddr1… of kind 39000 (group id), 31922/31923 (event coord), 30023 (proposal coord). parts: {text}, {ref} = shown inside the text as its name, {box} = the last reference when the text, trimmed of trailing white space, ends with it: taken out (with the white space before it) and shown as a preview box under the text; a text that is only a reference = the box alone. A group is typed when shown (channel, group, an event\'s chat = its event, prop-… = its proposal). Anything else stays as written.',
  generated_by: 'tools/vectors/references.mjs (nostr-tools from village-app)',
  domain,
  cases: cases.map(([name, text]) => ({ name, text, parts: parts(text) })),
}
writeFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), '../../vectors/references.json'), JSON.stringify(out, null, 2) + '\n')
console.log('ok', out.cases.length)
