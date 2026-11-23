#!/usr/bin/env python3
"""
Adds the "v2" section (collective-belfry docs/sealed-v2.md at belfry-sh df53525: one list of devices per
account, a copy of every message for each device) to vectors/sealed.json, in the style of the rest of the file
(keys made from their names, fixed nonces, 32 zero bytes of Schnorr auxiliary randomness; the crypto is
tools/sealed_confirm_vectors.py's). Only adds: every byte of the file before "v2" stays as it was. Every
`expected` is computed by the reference below, never typed by hand. Python 3, no dependency:

    python3 tools/sealed_v2_vectors.py [vectors/sealed.json] [--check]

FIRST PART (2026-11-23): lists (§3.1), wraps (§4.1, §4.2), accepting (§4.2), sealed or not (§5). The notice,
8563, history and the move from version 1 (§6, §8) are added by a later run, after these.
"""
import hashlib, json, os, re, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.dont_write_bytecode = True
from sealed_confirm_vectors import compact, nip44_encrypt, nip44_decrypt, rumor, sign_event, pubkey, key, schnorr_verify, event_id

HEX = re.compile(r'^[0-9a-f]{64}$')
D = 'belfry:devices:'
AHEAD = 600            # a list dated more than 10 minutes ahead of the reader's clock is ignored
KEEP = 30 * 86400      # a wrap that cannot be judged yet is kept 30 days
MAX_DEVICES = 20       # per list
MAX_TOTAL = 100        # devices of all participants together

# ---- the reference -------------------------------------------------------------------------------------

def valid_event(e):
    try:
        return e['id'] == event_id(e['pubkey'], e['created_at'], e['kind'], e['tags'], e['content']) and schnorr_verify(bytes.fromhex(e['id']), e['pubkey'], e['sig'])
    except Exception:
        return False

def read_list(e, belfry, account, now):
    """§3.1: the devices of ACCOUNT's list event E as a reader at NOW takes it, or None when it is not one.
    (the readings marked R are listed in the section's 'readings')"""
    if e['kind'] != 30078 or e['pubkey'] != belfry or not valid_event(e): return None
    one = lambda n: [t[1] for t in e['tags'] if len(t) >= 2 and t[0] == n]
    if one('d') != [D + account] or one('p') != [account]: return None        # R1: exactly one d and one p, both this account
    if e['content'] != '': return None                                         # R2
    if e['created_at'] > now + AHEAD: return None                              # more than 10 minutes ahead
    devices = []
    for t in e['tags']:
        if t and t[0] == 'device':
            if len(t) >= 2 and HEX.match(t[1] or ''):                          # R3: a malformed device tag is skipped
                if t[1] not in devices: devices.append(t[1])                   # R4: a device given twice counts once
    if len(devices) > MAX_DEVICES: return None                                 # R5: more than 20 is not a list
    return devices

def newest(events, belfry, account, now):
    """The list a reader holds: the newest valid one (created_at, then the lower id)."""
    ok = [e for e in events if read_list(e, belfry, account, now) is not None]
    return min(ok, key=lambda e: (-e['created_at'], e['id'])) if ok else None

def classify(wrap, my_account, my_device):
    """§4.2: what a kind 1059 is to this device, from its tags alone."""
    one = lambda n: [t[1] for t in wrap['tags'] if len(t) >= 2 and t[0] == n]
    ds, lbs, ps = one('d'), one('lb'), one('p')
    if not ds: return 'v1' if lbs else 'old'          # a letterbox wrap (version 1), or the old NIP-17 kind
    if lbs or len(ds) != 1 or len(ps) != 1: return 'invalid'   # R6
    if ds[0] != my_device: return 'not_mine'
    return 'v2' if ps[0] == my_account else 'invalid'  # R7

def open_wrap(wrap, device_secret):
    try:
        seal = json.loads(nip44_decrypt(device_secret, wrap['pubkey'], wrap['content'])[0])
        if seal['kind'] != 13 or seal['tags'] != [] or not valid_event(seal): return None
        r = json.loads(nip44_decrypt(device_secret, seal['pubkey'], seal['content'])[0])
        r = rumor(r['pubkey'], r['created_at'], r['kind'], r['tags'], r['content'])   # the id is always computed by the opener
        return seal['pubkey'], r
    except Exception:
        return None

def accept(r, signer, seen, belfry, me, now, wrap_first_seen):
    """§4.2: 'accept', 'keep' (judged again when the author's list is read) or 'drop', and why. SEEN: the lists
    of the rumor's author this app has seen, each {event, first_seen}. ME: this reader's account."""
    author = r['pubkey']
    if signer == belfry or author == belfry:
        if signer == belfry and author == belfry and r['kind'] == 1301: return 'accept', 'a mail from the belfry'
        return 'drop', "the belfry's key seals only its own mail (kind 1301)"            # R8
    if not any(len(t) >= 3 and t[0] == 'list' and t[1] == me and HEX.match(t[2] or '') for t in r['tags']):
        return 'drop', "no list tag for this reader's account"                             # R9
    lists = []
    for s in seen:
        dev = read_list(s['event'], belfry, author, now)
        if dev is not None: lists.append((s['event'], dev, s['first_seen']))
    lists.sort(key=lambda x: (x[0]['created_at'], [255 - b for b in bytes.fromhex(x[0]['id'])]))   # oldest first; of two in one second the lower id is the newer
    if lists and signer in lists[-1][1]: return 'accept', "the signer is on the author's newest list"
    last = max((i for i, l in enumerate(lists) if signer in l[1]), default=None)
    if last is not None:
        gone_seen = lists[last + 1][2]   # R10: when this app first saw the list that LAST dropped it
        if r['created_at'] <= gone_seen: return 'accept', 'the signer was on a list seen here, and the rumor is not dated after this app first saw it gone'
        why = 'dated after this app first saw the signer gone'
    else:
        why = "the signer is on no list of the author seen here"
    if now - wrap_first_seen < KEEP: return 'keep', why
    return 'drop', why + ', for 30 days'

def sealed_or_not(people, sender_account, sender_device, sticky):
    """§5: how a message is sent. PEOPLE: per participant {account, answer: 'complete'|'incomplete', devices:
    [..] or None (no list), seen_non_empty: a non-empty list was seen for it before}. STICKY: this device saw
    the conversation sealed."""
    if any(p['answer'] != 'complete' for p in people): return 'hold', 'a list could not be read'
    for p in people:
        if not p['devices']:
            if p['seen_non_empty']: return 'hold', 'a non-empty list was seen for someone who has none now'
            if sticky: return 'hold', 'sealed here before: never the old way again'
            return 'old_way', 'someone has no device list (a complete answer says so)'
    total = sum(len(p['devices']) for p in people)
    if total > MAX_TOTAL:
        if sticky: return 'hold', 'sealed here before, and more than 100 devices now'    # R11
        return 'not_sealed_too_many', 'more than 100 devices'
    mine = next(p for p in people if p['account'] == sender_account)
    if sender_device not in mine['devices']: return 'not_on_my_list', "this device isn't on its account's list: it seals nothing"
    return 'seal', 'every participant has a device, %d in all' % total

# ---- the section ---------------------------------------------------------------------------------------

def build(d):
    k = dict(d['keys']); k.update(d['follow']['keys']); k.update(d['join']['keys'])
    own = {}
    for a in range(1, 5):
        names = ['v2_acct%d' % a] + ['v2_acct%d_d%02d' % (a, i) for i in range(1, 21)]
        for n in names: own[n] = {'secret': key(n), 'pubkey': pubkey(key(n))}
    k.update(own)
    sec = lambda n: k[n]['secret']
    pub = lambda n: k[n]['pubkey']
    T0 = d['fixed']['T0']; T = T0 + 2000
    B = pub('belfry'); A = pub('alice'); BOB = pub('bob')
    P, L, TB, BP = 'alice_phone', 'alice_laptop', 'alice_tablet', 'bob_phone'

    def lst(account, devices, at, signer='belfry', tags=None, content='', kind=30078):
        """A device list event: signed by the belfry's key, d = belfry:devices:<account>, p = the account, one device tag each."""
        t = tags if tags is not None else [['d', D + pub(account)], ['p', pub(account)]] + [['device', pub(x)] for x in devices]
        return sign_event(sec(signer), at, kind, t, content)

    def lc(name, note, events, account='alice', now=T + 100):
        n = newest(events, B, pub(account), now)
        return {'name': name, 'note': note, 'account': pub(account), 'belfry': B, 'now': now, 'events': events,
                'expected': {'valid_ids': [e['id'] for e in events if read_list(e, B, pub(account), now) is not None],
                             'list': n['id'] if n else None, 'devices': read_list(n, B, pub(account), now) if n else None}}

    two = lst('alice', [P, L], T)
    three = lst('alice', [P, L, TB], T + 10)
    twenty = ['alice_phone'] + ['alice_d%02d' % i for i in range(1, 20)]
    bad_sig = dict(two); bad_sig['sig'] = two['sig'][:-2] + ('00' if two['sig'][-2:] != '00' else '01')
    # two lists in one second: which id is lower is found, not assumed
    same_a = lst('alice', [P, L], T + 20); same_b = lst('alice', [P, TB], T + 20)
    lower = min(same_a, same_b, key=lambda e: e['id'])
    lists = [
        lc('a list with two devices', "Signed by the belfry's key; d = belfry:devices:<alice>, p = alice, the devices in the belfry's order (oldest session first). content \"\".", [two]),
        lc('an empty list', 'No device tag: a valid list with no device (signed out everywhere). Not the same as no list.', [lst('alice', [], T)]),
        lc('twenty devices', 'The cap.', [lst('alice', twenty, T)]),
        lc('twenty-one devices', 'More than 20 is not a list (R5): ignored; the reader keeps the one it had.', [two, lst('alice', twenty + [TB], T + 10)]),
        lc('a device given twice', 'Counted once, at its first place (R4).', [lst('alice', [P, L, P], T)]),
        lc('a malformed device tag', 'A device tag that is not 64 lower-case hex characters is skipped; the rest is kept (R3).', [lst('alice', [], T, tags=[['d', D + A], ['p', A], ['device', pub(P)], ['device', pub(L).upper()], ['device', 'laptop'], ['device'], ['device', pub(TB)]])]),
        lc('signed by the account, not the belfry', "A session of the account (or another app connected to it) signs as the account: never a list. Readers take the list from the belfry's key only.", [lst('alice', [P, 'mallory_phone'], T, signer='alice')]),
        lc("signed by someone else's key", "Mallory's key.", [lst('alice', [P, 'mallory_phone'], T, signer='mallory')]),
        lc("another account's list", "The belfry's list for Bob is not Alice's: d and p name Bob.", [lst('bob', [BP], T)]),
        lc('d names the account, p another', 'p must be the account that d names (R1).', [lst('alice', [], T, tags=[['d', D + A], ['p', BOB], ['device', pub(P)]])]),
        lc('no p tag', 'Exactly one p (R1).', [lst('alice', [], T, tags=[['d', D + A], ['device', pub(P)]])]),
        lc('two d tags', 'A tag given twice makes it invalid (R1).', [lst('alice', [], T, tags=[['d', D + A], ['d', D + BOB], ['p', A], ['device', pub(P)]])]),
        lc('content that is not empty', 'content "" (R2).', [lst('alice', [P], T, content='{}')]),
        lc('another kind', 'kind 30078 only.', [lst('alice', [P], T, kind=30079)]),
        lc('a bad signature', '', [bad_sig]),
        lc('the newest of two', 'The tablet signed in: the list of T + 10 wins.', [three, two]),
        lc('two lists in the same second', 'The lower id wins (ids compared as lower-case hex), as for any replaceable event.', [same_a, same_b]),
        lc('dated eleven minutes ahead', "A list dated more than 10 minutes ahead of the reader's clock is ignored: the reader keeps the older one.", [two, lst('alice', [P], T + 100 + 660)]),
        lc('dated exactly ten minutes ahead', 'Not MORE than 10 minutes: taken.', [two, lst('alice', [P], T + 100 + 600)]),
        lc('no list at all', 'Nothing from the belfry for this account (an agent, an account that holds its own key): no list.', []),
    ]
    assert [c['expected']['list'] is not None for c in lists] == [True, True, True, True, True, True, False, False, False, False, False, False, False, False, False, True, True, True, True, False]
    assert lists[3]['expected']['list'] == two['id'] and lists[4]['expected']['devices'] == [pub(P), pub(L)]
    assert lists[5]['expected']['devices'] == [pub(P), pub(TB)] and lists[1]['expected']['devices'] == []
    assert lists[15]['expected']['list'] == three['id'] and lists[16]['expected']['list'] == lower['id']
    assert lists[17]['expected']['list'] == two['id'] and lists[18]['expected']['devices'] == [pub(P)]

    # ---- wraps
    la = two                                  # alice: phone, laptop
    lb_ = lst('bob', [BP], T)                 # bob: his phone

    def with_lists(tags, by_account):
        """§3.3: one ["list", account, list id] per participant, after the other tags, by account (lower-case hex order) (R12)."""
        return tags + [['list', a, by_account[a]['id']] for a in sorted(by_account)]

    def wraps_of(case, r, sender_device, by_account, at):
        """§4.1: one seal and one wrap per device of every participant's list, the sending device included."""
        sender_account = r['pubkey']
        out = []
        for a in sorted(by_account, key=lambda x: (x != sender_account, x)):          # the sender's own devices first (any order is right)
            for dev in read_list(by_account[a], B, a, at + 100):
                who = next(n for n, v in k.items() if v['pubkey'] == dev)
                nonce = lambda what: hashlib.sha256(('belfry sealed vectors/nonce/' + what + '/v2: ' + case + '/' + who).encode()).digest()
                once = hashlib.sha256(('belfry sealed vectors/once/v2: ' + case + '/' + who).encode()).hexdigest()
                seal = sign_event(sec(sender_device), at + 1, 13, [], nip44_encrypt(sec(sender_device), dev, compact(r), nonce('seal')))
                tags = [['p', a], ['d', dev]] + ([['silent', '1']] if a == sender_account else [])
                w = sign_event(once, at + 2, 1059, tags, nip44_encrypt(once, dev, compact(seal), nonce('wrap')))
                got = open_wrap(w, sec(who)); assert got == (pub(sender_device), r)
                out.append({'to_account': a, 'to_device': dev, 'device_name': who, 'device_secret': sec(who), 'silent': a == sender_account, 'wrap': w})
        return out

    def sending(name, note, r_kind, r_tags, content, sender_device, by_account, at):
        r = rumor(A, at, r_kind, with_lists(r_tags, by_account), content)
        w = wraps_of(name, r, sender_device, by_account, at)
        return {'name': name, 'note': note, 'sender_device': pub(sender_device), 'lists': list(by_account.values()), 'rumor': r,
                'expected': {'wraps': len(w), 'seal_pubkey': pub(sender_device)}, 'wraps': w}

    both = {A: la, BOB: lb_}
    s1 = sending('a message between two people', "Alice (phone, laptop) writes to Bob (one phone) from her phone. The rumor carries one list tag per participant (§3.3), after its other tags, by account. Three wraps: to her own phone (the sending device itself: a device's state is what the relay holds for it), to her laptop, both silent, and to Bob's phone. Each: seal kind 13 signed by the phone, content = NIP-44 conv(phone secret, that device) of the rumor JSON; wrap kind 1059 by a one-time key, content = NIP-44 conv(one-time secret, that device) of the seal; tags p = that device's account, d = that device, and silent when the account is the sender's own.",
                 14, [['p', BOB]], 'Are you coming to the kitchen on Sunday?', P, both, T + 100)
    s2 = sending('a note to myself on my only device', 'One participant, one device: one wrap, to the sending device itself, silent.', 14, [['p', A]], 'Buy flour for Sunday, 2 kg', P, {A: lst('alice', [P], T)}, T + 200)
    s3 = sending('a reaction', 'Reactions and take-backs are wrapped like messages: one wrap per device, the list tags in the rumor.', 7, [['e', s1['rumor']['id'], '', A], ['p', BOB], ['k', '14']], '👍', L, both, T + 300)
    assert [s['expected']['wraps'] for s in (s1, s2, s3)] == [3, 1, 3]
    assert [t for t in s1['rumor']['tags'] if t[0] == 'list'] == [['list', a, both[a]['id']] for a in sorted(both)]

    # what a device does with a 1059, from its tags (the reader: Alice's laptop)
    r1 = s1['rumor']
    def odd(name, note, tags, to=L, signer_secret=None, break_seal=False):
        nonce = lambda what: hashlib.sha256(('belfry sealed vectors/nonce/' + what + '/v2: ' + name).encode()).digest()
        once = hashlib.sha256(('belfry sealed vectors/once/v2: ' + name).encode()).hexdigest()
        seal = sign_event(sec(P), T + 101, 13, [], nip44_encrypt(sec(P), pub(to), compact(r1), nonce('seal')))
        if break_seal: seal = dict(seal); seal['sig'] = seal['sig'][:-2] + ('00' if seal['sig'][-2:] != '00' else '01')
        w = sign_event(once, T + 102, 1059, tags, nip44_encrypt(once, pub(to), compact(seal), nonce('wrap')))
        kind = classify(w, A, pub(L))
        opened = open_wrap(w, sec(L)) if kind == 'v2' else None
        if kind == 'v2' and opened is None: kind = 'invalid'
        return {'name': name, 'note': note, 'reader_account': A, 'reader_device': pub(L), 'reader_device_secret': sec(L), 'wrap': w,
                'expected': {'kind': kind, 'opens': opened is not None}}
    mine = next(w for w in s1['wraps'] if w['to_device'] == pub(L))['wrap']
    receiving = [
        {'name': 'a wrap for this device', 'note': "p = my account, d = my device: version 2. It opens with this device's key.", 'reader_account': A, 'reader_device': pub(L), 'reader_device_secret': sec(L), 'wrap': mine,
         'expected': {'kind': classify(mine, A, pub(L)), 'opens': True}},
        odd('a wrap for another device of mine', "d names my phone: not this device's. Not opened, not kept (the filter #d never returns it; a client that sees it anyway leaves it).", [['p', A], ['d', pub(P)], ['silent', '1']], to=P),
        odd('no d and no lb', 'The old NIP-17 kind: for the signer, as before sealing.', [['p', A]]),
        odd('an lb and no d', 'A version 1 wrap, to a letterbox: opened with the letterbox secret when this device holds it (docs/sealed.md §4).', [['p', A], ['lb', pub('alice_lb1')]]),
        odd('both d and lb', 'Invalid (R6): dropped.', [['p', A], ['d', pub(L)], ['lb', pub('alice_lb1')]]),
        odd('two d tags', 'A tag given twice makes it invalid (R6).', [['p', A], ['d', pub(L)], ['d', pub(P)]]),
        odd('d is my device, p another account', "Invalid (R7): a wrap names the account of the device it is for.", [['p', BOB], ['d', pub(L)]]),
        odd('a seal with a bad signature', 'The tags are right and the wrap opens, but the seal inside is not signed by its pubkey: dropped at once.', [['p', A], ['d', pub(L)], ['silent', '1']], break_seal=True),
    ]
    assert [c['expected']['kind'] for c in receiving] == ['v2', 'not_mine', 'old', 'v1', 'invalid', 'invalid', 'invalid', 'invalid']

    # ---- accepting (the reader: Bob's phone, judging rumors of Alice)
    def acc(name, note, r, signer, seen, now, wrap_first_seen, me=BOB, then=None):
        v, why = accept(r, pub(signer), seen, B, me, now, wrap_first_seen)
        c = {'name': name, 'note': note, 'reader_account': me, 'belfry': B, 'rumor': r, 'seal_pubkey': pub(signer), 'seen_lists': seen,
             'now': now, 'wrap_first_seen': wrap_first_seen, 'expected': {'verdict': v, 'why': why}}
        if then:
            v2, why2 = accept(r, pub(signer), then['seen_lists'], B, me, then['now'], wrap_first_seen)
            c['then'] = {**then, 'expected': {'verdict': v2, 'why': why2}}
        return c
    seen = lambda *pairs: [{'event': e, 'first_seen': t} for e, t in pairs]
    msg = lambda at, tags=None, kind=14, author=A, content='hello': rumor(author, at, kind, tags if tags is not None else with_lists([['p', BOB]], both), content)
    l_pl = two                                   # T: phone, laptop
    l_p = lst('alice', [P], T + 500)             # T + 500: the laptop is gone
    l_pl2 = lst('alice', [P, L], T + 900)        # T + 900: the laptop is back
    l_p2 = lst('alice', [P], T + 1300)           # T + 1300: gone again
    l_early = lst('alice', [P], T - 500)         # before the laptop ever signed in
    S1, S2 = T + 520, T + 1330                   # when Bob's phone first saw each list without the laptop
    now = T + 2000
    mal = lst('mallory', ['mallory_phone'], T)
    accepting = [
        acc("the signer is on the author's newest list", '', msg(T + 50), L, seen((l_pl, T + 5)), now, now),
        acc('a removed device, a rumor dated before this app saw it gone', "The laptop left Alice's list (the list of T + 500), which Bob's phone first saw at T + 520. A rumor dated T + 400: accepted.", msg(T + 400), L, seen((l_pl, T + 5), (l_p, S1)), now, now),
        acc('a removed device, a rumor dated between the list that dropped it and this app seeing that list', 'Dated T + 510: after the new list was written, before this app first saw it (T + 520). The bound is what THIS app saw: accepted.', msg(T + 510), L, seen((l_pl, T + 5), (l_p, S1)), now, now),
        acc('a removed device, a rumor dated in the second this app saw it gone', 'Not dated AFTER: accepted.', msg(S1), L, seen((l_pl, T + 5), (l_p, S1)), now, now),
        acc('a removed device, a rumor dated after this app saw it gone', 'A removed device cannot go on writing as Alice. Kept (30 days), judged again when her list is read.', msg(S1 + 1), L, seen((l_pl, T + 5), (l_p, S1)), now, now),
        acc('the same, thirty days after the wrap was first seen', 'Dropped.', msg(S1 + 1), L, seen((l_pl, T + 5), (l_p, S1)), now + KEEP, now),
        acc('removed, then on the list again', "On the newest list: accepted whatever the rumor's date.", msg(S1 + 100), L, seen((l_pl, T + 5), (l_p, S1), (l_pl2, T + 905)), now, now),
        acc('removed, back, removed again: the last time counts', 'The bound is when this app first saw the list that LAST dropped the device (T + 1330). This rumor is dated T + 700, in the gap between its two stays: accepted (R10).', msg(T + 700), L, seen((l_pl, T + 5), (l_p, S1), (l_pl2, T + 905), (l_p2, S2)), now, now),
        acc('removed, back, removed again: after the last time', 'Dated T + 1331: kept.', msg(S2 + 1), L, seen((l_pl, T + 5), (l_p, S1), (l_pl2, T + 905), (l_p2, S2)), now, now),
        acc('a list from before the device signed in is not a removal', 'Bob saw a list without the laptop (T − 500), then the one with it. The laptop is on the newest list: accepted.', msg(T + 50), L, seen((l_early, T - 400), (l_pl, T + 5)), now, now),
        acc('a signer never seen on a list, then the list arrives', "The tablet seals before Bob's phone has the list that names it: kept. When the list is read, accepted.", msg(T + 1500), TB, seen((l_pl, T + 5)), now, now,
            then={'seen_lists': seen((l_pl, T + 5), (lst('alice', [P, L, TB], T + 1400), now + 5)), 'now': now + 5}),
        acc('a signer never seen on a list, thirty days later', 'Dropped.', msg(T + 1500), TB, seen((l_pl, T + 5)), now + KEEP, now),
        acc('no list of the author seen at all', 'Kept: the message can arrive before the list.', msg(T + 50), L, [], now, now),
        acc("the signer is on someone else's list", "Mallory's phone is on Mallory's list and seals a rumor whose pubkey is Alice. Bob judges by ALICE's lists: kept, then dropped; never accepted.", msg(T + 50), 'mallory_phone', seen((l_pl, T + 5)), now, now),
        acc('a list signed by the account names the signer', "The only list that has Mallory's phone as a device of Alice is signed by Alice's own key, not the belfry's: it is no list.", msg(T + 50), 'mallory_phone', seen((l_pl, T + 5), (lst('alice', [P, 'mallory_phone'], T + 600, signer='alice'), T + 605)), now, now),
        acc('the signer is only on a list dated eleven minutes ahead', 'That list is ignored: kept.', msg(T + 50), TB, seen((l_pl, T + 5), (lst('alice', [P, L, TB], now + 660), now)), now, now),
        acc('the signer is on a list dated nine minutes ahead', 'Taken: accepted.', msg(T + 50), TB, seen((l_pl, T + 5), (lst('alice', [P, L, TB], now + 540), now)), now, now),
        acc("no list tag for the reader's account", "A version 2 rumor carries a list tag for every participant (§3.3). One that has none for THIS reader's account is not accepted (R9): dropped.", msg(T + 50, tags=[['p', BOB], ['list', A, la['id']]]), L, seen((l_pl, T + 5)), now, now),
        acc('a mail sealed by the belfry', "pubkey and seal signer are both the belfry's key, kind 1301: accepted (§7).", msg(T + 50, tags=with_lists([['p', BOB], ['subject', 'Street party permit']], {BOB: lb_}), kind=1301, author=B, content='From: office@city.example\r\n\r\nGranted.'), 'belfry', [], now, now),
        acc("the belfry's key seals a kind 14 of its own", 'Only kind 1301: dropped at once (R8).', msg(T + 50, tags=with_lists([['p', BOB]], {BOB: lb_}), author=B), 'belfry', [], now, now),
        acc("the belfry's key does not speak for a member", "The belfry's key seals a rumor whose pubkey is Alice: dropped at once (R8).", msg(T + 50), 'belfry', seen((l_pl, T + 5)), now, now),
    ]
    want = ['accept', 'accept', 'accept', 'accept', 'keep', 'drop', 'accept', 'accept', 'keep', 'accept', 'keep', 'drop', 'keep', 'keep', 'keep', 'keep', 'accept', 'drop', 'accept', 'drop', 'drop']
    assert [c['expected']['verdict'] for c in accepting] == want, [c['expected']['verdict'] for c in accepting]
    assert accepting[10]['then']['expected']['verdict'] == 'accept'
    assert mal['pubkey'] == B

    # ---- sealed or not (§5)
    big = {'alice': twenty}
    for a in range(1, 5): big['v2_acct%d' % a] = ['v2_acct%d_d%02d' % (a, i) for i in range(1, 21)]
    big_lists = {n: lst(n, devs, T) for n, devs in big.items()}
    def person(account, event, answer='complete', seen_non_empty=False):
        return {'account': pub(account), 'answer': answer, 'list': event, 'seen_non_empty_before': seen_non_empty}
    def so(name, note, people, sender_device=P, sticky=False):
        ref = [{'account': p['account'], 'answer': p['answer'], 'devices': (read_list(p['list'], B, p['account'], T + 100) if p['list'] else None), 'seen_non_empty': p['seen_non_empty_before']} for p in people]
        dec, why = sealed_or_not(ref, A, pub(sender_device), sticky)
        return {'name': name, 'note': note, 'belfry': B, 'sender_account': A, 'sender_device': pub(sender_device), 'sealed_here_before': sticky, 'participants': people,
                'expected': {'decision': dec, 'why': why, 'devices': sum(len(r['devices'] or []) for r in ref)}}
    empty_bob = lst('bob', [], T + 50)
    sealed = [
        so('every participant has a device', "Sealed: no `ready`, no waiting. 3 wraps.", [person('alice', la), person('bob', lb_)]),
        so('someone has no list, a complete answer', "Bob has no list (an agent, an account that holds its own key) and the relay's answer is complete: the old way, as before sealing. Only on knowledge.", [person('alice', la), person('bob', None)]),
        so('someone has no list, the answer is not complete', 'Silence, a CLOSED, an answer from before authentication: hold (shown as sending), never the old way.', [person('alice', la), person('bob', None, answer='incomplete')]),
        so('an empty list, never seen with a device', 'Bob is signed out everywhere and this device never saw a device of his: the old way.', [person('alice', la), person('bob', empty_bob)]),
        so('an empty list, seen with a device before', 'Once a non-empty list was seen for an account, a missing or empty one means hold, never the old way.', [person('alice', la), person('bob', empty_bob, seen_non_empty=True)]),
        so('no list now, seen with a device before', 'The same when the list is gone altogether.', [person('alice', la), person('bob', None, seen_non_empty=True)]),
        so('sealed here before, someone has no list', 'Sticky per device: once sealed, never the old way again. Hold.', [person('alice', la), person('bob', None)], sticky=True),
        so("this device is not on its account's list", "Alice's tablet is signed in but not on her list (the cap, or the list not written yet): it seals nothing and says so in one line with Retry.", [person('alice', la), person('bob', lb_)], sender_device=TB),
        so('a hundred devices', 'Five people with 20 devices each: 100 in all, sealed (100 wraps).', [person(n, e) for n, e in big_lists.items()], sender_device=P),
        so('a hundred and one devices', "The same five and Bob's one phone: over 100. Not sealed, and the conversation says so in one line (\"Too many devices to encrypt for everyone here\"); never a silent downgrade.", [person(n, e) for n, e in big_lists.items()] + [person('bob', lb_)], sender_device=P),
        so('a hundred and one devices in a conversation sealed here before', 'Sticky wins (R11): hold, with the same line; never the old way.', [person(n, e) for n, e in big_lists.items()] + [person('bob', lb_)], sender_device=P, sticky=True),
        so('a note to myself', 'One participant: sealed as soon as I have a list with this device.', [person('alice', lst('alice', [P], T))]),
    ]
    want = ['seal', 'old_way', 'hold', 'old_way', 'hold', 'hold', 'hold', 'not_on_my_list', 'seal', 'not_sealed_too_many', 'hold', 'seal']
    assert [c['expected']['decision'] for c in sealed] == want, [c['expected']['decision'] for c in sealed]
    assert sealed[8]['expected']['devices'] == 100 and sealed[9]['expected']['devices'] == 101

    readings = [
        "R1. A list has exactly one d (belfry:devices:<account>) and exactly one p, and p is the account that d names. Anything else is not a list.",
        "R2. A list's content is \"\". Anything else is not a list.",
        "R3. A device tag whose value is not 64 lower-case hex characters is skipped; the other devices count.",
        "R4. A device given twice counts once, at its first place.",
        "R5. A list with more than 20 devices (after R3 and R4) is not a list: the reader keeps the one it had.",
        "R6. A kind 1059 with a d tag is a version 2 wrap only when it has exactly one d, exactly one p and no lb; with a d and anything else it is invalid and dropped.",
        "R7. A version 2 wrap whose d is this device and whose p is not this device's account is invalid.",
        "R8. A rumor whose seal signer or pubkey is the belfry's key is accepted only when both are and the kind is 1301; anything else with that key is dropped at once (as in version 1). Reports (7679) have no vector yet: their per-device form is not written in the doc.",
        "R9. A version 2 rumor without a [\"list\", <the reader's account>, <id>] tag is not accepted: dropped. (For a note to myself and an 8563 the reader's account is the author's.)",
        "R10. \"This app first saw a list of A without S\" (§4.2) is read as: when this app first saw the list that LAST dropped S, taking A's lists in order (created_at, then the higher id first). A list from before S was ever listed is not a removal; a rumor dated while S was off the list between two stays is accepted too.",
        "R11. Over 100 devices in a conversation this device saw sealed before: hold (with the one line), never the old way. Over 100 in one never sealed here: not sealed, said in the one line.",
        "R12. The list tags are the rumor's last tags, one per participant, in the order of the accounts' lower-case hex. Readers do not depend on the order or the place.",
        "R13. \"Not dated after\" and \"more than 10 minutes ahead\" are inclusive bounds: the same second is accepted, exactly 10 minutes ahead is taken.",
    ]
    return {
        'note': "docs/sealed-v2.md at belfry-sh df53525 (version 2: one list of devices per account, signed by the belfry's key; a copy of every sealed message for each device). Added by belfry-android (tools/sealed_v2_vectors.py, which leaves every byte of this file before \"v2\" as it was and computes every 'expected' with its own port of the rules) on 2026-11-23. FIRST PART: 'lists' (§3.1: what a reader at 'now' takes as the account's list from 'events'; expected.valid_ids = the events that are lists, expected.list = the id of the one it holds, expected.devices = its devices in order, null = no list), 'sending' (§4.1 with §3.3: the rumor with its list tags and every wrap, each with the secret of the device it is for, so a reader can open it: wrap → seal (signature checked, tags []) → rumor, both NIP-44 with that device's key; expected.seal_pubkey = the sending device), 'receiving' (§4.2: what a kind 1059 is to the reader's device from its tags: v2, v1 (a letterbox wrap), old (NIP-17, for the signer), not_mine, invalid; and whether it opens), 'accepting' (§4.2: accept, keep or drop for an opened rumor and the key that signed its seal, given the lists of the rumor's author this app has seen, each with when it first saw it; 'then' = judged again with more lists), 'sealed' (§5: seal, old_way, hold, not_sealed_too_many or not_on_my_list, from each participant's list as the relay answered it). 'readings' are the places where the doc left a choice and the stricter one was taken, for the lead to confirm. Still to come in this section: the notice and §3.3's alarm, 8563, history, and sending both ways while the belfry says version 1. Every key is a test key made from its name ('keys' holds the ones this section adds; the others are in the file's 'keys', 'follow.keys' and 'join.keys').",
        'contract': 'collective-belfry docs/sealed-v2.md at belfry-sh df53525',
        'readings': readings,
        'belfry': B,
        'keys': own,
        'lists': lists,
        'sending': [s1, s2, s3],
        'receiving': receiving,
        'accepting': accepting,
        'sealed': sealed,
    }

if __name__ == '__main__':
    path = next((a for a in sys.argv[1:] if not a.startswith('--')), 'vectors/sealed.json')
    raw = open(path, encoding='utf-8').read()
    d = json.loads(raw)
    had = d.pop('v2', None)
    section = build(d)
    if '--check' in sys.argv:
        print('up to date' if had == section else 'DIFFERENT'); sys.exit(0 if had == section else 1)
    assert json.dumps(d if had is None else {**d, 'v2': had}, indent=1, ensure_ascii=False) + '\n' == raw, "the file is not in the generator's format, or v2 is not its last section"
    d['v2'] = section
    out = json.dumps(d, indent=1, ensure_ascii=False) + '\n'   # made whole before the file is opened
    open(path, 'w', encoding='utf-8').write(out)
