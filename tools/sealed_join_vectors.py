#!/usr/bin/env python3
"""
Adds the "join" section (collective-belfry docs/sealed.md §5.7 "A device that signed in adds itself", as
tightened at belfry-sh 1440e79) to vectors/sealed.json, in the style of the rest of the file (keys made
from their names, fixed nonces, 32 zero bytes of Schnorr auxiliary randomness; the crypto is
tools/sealed_confirm_vectors.py's). Only adds: every existing section is written back byte for byte.
Every `expected` is computed by the small reference below (a port of the server's sealed.chain / follow /
answer_without_prompt with §5.7's rules), never typed by hand. Python 3, no dependency:

    python3 tools/sealed_join_vectors.py [vectors/sealed.json] [--check]
"""
import hashlib, json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.dont_write_bytecode = True
from sealed_confirm_vectors import compact, nip44_encrypt, rumor, sign_event, pubkey, key

# ---- the reference: a chain with joins (sealed.md §3.3 + §5.7) ---------------------------------------

class R:
    def __init__(s, e):
        t = e['tags']
        one = lambda n: [x[1] for x in t if len(x) >= 2 and x[0] == n]
        s.id, s.signer, s.at = e['id'], e['pubkey'], e['created_at']
        s.account = one('p')[0]; s.n = int(one('n')[0]); s.prev = (one('prev') or [None])[0]
        s.lb = one('lb')[0]; s.devices = one('device'); s.ready = any(x and x[0] == 'ready' for x in t)
        s.endorses = one('endorse')
        s.ok = len(one('p')) == 1 and len(one('n')) == 1 and len(one('lb')) == 1 and len(one('prev')) <= 1 \
            and 1 <= len(s.devices) <= 20 and len(set(s.devices)) == len(s.devices)

def follows(prev, lbs, r):
    """§3.3 "Roster n": signed by a device of the roster before."""
    if r.n != prev.n + 1 or r.signer not in prev.devices: return False
    if any(d not in r.devices for d in prev.devices): return r.lb not in lbs
    return r.lb == prev.lb

def joins(prev, lbs, had, r):
    """§5.7: signed by the new device, prev's devices in their order + the signer last, a letterbox the
    chain never had, a device the chain never had, dated not before its prev."""
    return (r.n == prev.n + 1 and r.signer not in prev.devices and r.signer not in had
            and r.devices == prev.devices + [r.signer] and r.lb not in lbs and r.at >= prev.at)

def unendorsed(path):
    """§5.7: the devices of PATH that added themselves and that no later roster endorses, each with the
    devices of the roster before its join. An endorsement is a roster's ["endorse", <device>] tag, signed by
    a device that was in the roster BEFORE that device's join. A device that left the head waits for nothing."""
    waiting = {}
    for before, r in zip(path, path[1:]):
        if r.signer not in before.devices:
            waiting[r.signer] = before.devices; continue
        for dv in r.endorses:
            if dv in waiting and r.signer in waiting[dv]: del waiting[dv]
        for dv in [x for x in waiting if x not in r.devices]: del waiting[dv]
    return waiting

def lifted(path, i):
    """The joiners that roster I of PATH endorses (they were waiting before it and are not after)."""
    a, b = unendorsed(path[:i]), unendorsed(path[:i + 1])
    return [dv for dv in a if dv not in b and dv in path[i].devices]

class Chain:
    def __init__(s, account, rosters, anchored):
        s.account, s.rosters, s.anchored = account, rosters, anchored
        s.ids = [r.id for r in rosters]; s.head = rosters[-1]; s.root = rosters[0]; s.devices = s.head.devices
    def added_by(s, device):
        """The roster that last ADDED the device (None for a device of the root never dropped)."""
        by = None
        for a, b in zip(s.rosters, s.rosters[1:]):
            if device in b.devices and device not in a.devices: by = b
        return by
    def added_at(s, device):
        return next((r.at for r in s.rosters if device in r.devices), None)

def anchor_of(a):
    t = a['tags']; one = lambda n: [x[1] for x in t if len(x) >= 2 and x[0] == n]
    heads = one('head')
    return one('root')[0], (heads[0] if len(heads) == 1 else None)

def chain(anchor, rosters, pinned=None, seen_anchored=None):
    """The valid chain. A join counts only on the anchored path (root → the anchor's head), and in a pinned
    path only up to SEEN_ANCHORED (the furthest roster this client saw anchored); never in the tail. A normal
    roster signed by a device that added itself is invalid, on the path and in the tail, until a roster after
    its join carries ["endorse", it] signed by a device that was in the roster before that join."""
    if anchor is None: return None
    account = anchor['pubkey']; root_id, head_id = anchor_of(anchor)
    parsed = {}
    for e in rosters:
        r = R(e)
        if r.ok and r.account == account: parsed[r.id] = r
    root = parsed.get(root_id)
    if root is None or root.n != 1 or root.prev is not None or root.signer not in root.devices: return None

    def step(path, lbs, had, r, join_ok):
        if follows(path[-1], lbs, r):
            if r.signer in unendorsed(path): return False
        elif not (join_ok and joins(path[-1], lbs, had, r)):
            return False
        path.append(r); lbs.add(r.lb); had.update(r.devices)
        return True

    def path_to(target, joins_until=None):
        """joins_until None: a join is accepted anywhere on the way (the anchored path); else only up to
        that roster id (a pinned path: what was seen anchored)."""
        back = []; at = parsed.get(target or '')
        while at is not None and at.id != root.id:
            back.append(at); at = parsed.get(at.prev or '')
            if len(back) > len(parsed): return None
        if at is None: return None
        ids = [root.id] + [r.id for r in reversed(back)]
        limit = len(ids) if joins_until is None else (ids.index(joins_until) + 1 if joins_until in ids else 1)
        st = ([root], {root.lb}, set(root.devices))
        for i, r in enumerate(reversed(back), start=1):
            if not step(*st, r, i < limit): return None
        return st

    st = path_to(head_id) or ([root], {root.lb}, set(root.devices))
    anchored = st[0][-1].id
    if pinned:
        held = path_to(pinned, seen_anchored or root.id)
        if held and len(held[0]) > len(st[0]) and held[0][len(st[0]) - 1].id == anchored: st = held
    path, lbs, had = st
    kids = {}
    for r in parsed.values():
        if r.prev is not None: kids.setdefault(r.prev, []).append(r)
    while True:
        ok = [r for r in kids.get(path[-1].id, []) if follows(path[-1], lbs, r) and r.signer not in unendorsed(path)]
        if not ok: break
        step(path, lbs, had, min(ok, key=lambda r: r.id), False)
    return Chain(account, path, anchored)

def summary(c):
    if c is None: return {'valid_ids': [], 'head': None, 'devices': [], 'lb': None, 'ready': False}
    return {'valid_ids': c.ids, 'head': c.head.id, 'devices': c.devices, 'lb': c.head.lb, 'ready': any(r.ready for r in c.rosters)}

# ---- the pin (the server's follow, with reading (1) of §5.7) -----------------------------------------

def _pin(c, seen, was=None, **ch):
    was = was or {}
    tr = ch.get('trusted', was.get('trusted'))
    return {'root': c.root.id, 'head': c.head.id, 'path': c.ids, 'anchored': seen == c.head.id, 'anchored_head': seen,
            'trusted': None if tr is None else sorted(tr), 'switch_head': ch.get('switch_head', was.get('switch_head')),
            'prev_root': ch.get('prev_root', was.get('prev_root'))}

def _trust_step(trusted, c, i):
    """One roster after a switch: drops leave trust; a device it adds, and a joiner it endorses (§5.7), is
    trusted only when its signer was."""
    before, r = c.rosters[i - 1], c.rosters[i]
    vouches = r.signer in trusted
    trusted.difference_update(d for d in before.devices if d not in r.devices)
    if vouches:
        trusted.update(d for d in r.devices if d not in before.devices)
        trusted.update(lifted(c.rosters, i))

def _carry(pin, c, seen):
    ids = c.ids
    if seen not in ids or ids.index(c.anchored) > ids.index(seen): seen = c.anchored
    tr = pin.get('trusted')
    if tr is not None and pin.get('switch_head') in ids:
        known = 0
        while known < min(len(ids), len(pin['path'])) and ids[known] == pin['path'][known]: known += 1
        tr = set(tr)
        for i in range(max(known, ids.index(pin['switch_head']) + 1), len(ids)): _trust_step(tr, c, i)
    return _pin(c, seen, pin, trusted=tr)

def _cut(pin, rosters):
    """Reading (1): a pinned part this client never saw anchored holds no join. A pin that has one there (it
    cannot come from follow: only from a store written by something else) is cut back to before that join."""
    by = {e['id']: R(e) for e in rosters}
    path = pin['path']; seen = pin.get('anchored_head') or pin['root']
    start = path.index(seen) + 1 if seen in path else 1
    for i in range(start, len(path)):
        a, b = by.get(path[i - 1]), by.get(path[i])
        if a is not None and b is not None and b.signer not in a.devices:
            return {**pin, 'path': path[:i], 'head': path[i - 1], 'anchored': path[i - 1] == seen}
    return pin

def follow(anchor, rosters, pin):
    c = chain(anchor, rosters)
    if c is None: return None, pin, 'none'
    if pin is None: return c, _pin(c, c.anchored), 'none'
    if c.root.id != pin['root']: return c, _pin(c, c.anchored, trusted=None, switch_head=None, prev_root=pin['root']), 'fresh_start'
    was = pin; pin = _cut(pin, rosters)
    changed = lambda new: 'moved' if new['head'] != was['head'] else 'none'
    path, seen = pin['path'], pin.get('anchored_head') or pin['root']
    if c.anchored in path[:-1]:
        kept = chain(anchor, rosters, pin['head'], seen)
        back = seen in path and path.index(c.anchored) < path.index(seen)
        if kept is None or pin['head'] not in kept.ids: return None, was, 'ignored_back' if back else 'none'
        new = _carry(pin, kept, seen)
        return kept, new, 'ignored_back' if back else changed(new)
    if pin['head'] in c.ids:
        new = _carry(pin, c, seen); return c, new, changed(new)
    if seen in c.ids: return c, _carry(pin, c, seen), 'moved'
    held = next((R(e) for e in rosters if e['id'] == pin['head']), None)
    before = set(held.devices) if held else set()
    if pin.get('trusted') is not None: before &= set(pin['trusted'])
    return c, _pin(c, c.anchored, pin, trusted=before & set(c.devices), switch_head=c.head.id), 'switch'

def answer_without_prompt(c, pin, device, live, request_at, added_at=None, last_handover_at=None):
    """§5.5, with reading (3): for a device that added itself the request may be dated in the same second as
    its own join roster (>=); for any other device it must be strictly newer."""
    if c is None or pin is None or c.head.id != pin['head'] or c.root.id != pin['root']: return False
    end = next((r for r in c.rosters if r.id == c.anchored), None)
    if end is None or device not in end.devices or device not in c.devices: return False
    if pin.get('trusted') is not None and device not in pin['trusted']: return False
    if device not in live: return False
    if added_at is None: added_at = c.added_at(device)
    if added_at is None: return False
    by = c.added_by(device)
    self_added = by is not None and by.signer == device
    if request_at < added_at or (request_at == added_at and not self_added): return False
    return last_handover_at is None or request_at > last_handover_at

def accept_confirm(rum, seal_pubkey, c, unanswered):
    """§5.2 step 2b, plus §5.7 "8563": 'later' when the signer's own added-notice is unanswered on this device."""
    one = lambda n: [x[1] for x in rum['tags'] if len(x) >= 2 and x[0] == n]
    if rum['kind'] != 8563 or rum['pubkey'] != seal_pubkey or rum['content'] != '': return 'no'
    if len(one('p')) != 1 or len(one('device')) != 1 or len(one('e')) != 1 or one('p')[0] != c.account: return 'no'
    added, e = one('device')[0], one('e')[0]
    if e not in c.ids: return 'later'
    i = c.ids.index(e)
    if i == 0 or added not in c.rosters[i].devices or added in c.rosters[i - 1].devices: return 'no'
    if seal_pubkey not in c.rosters[i - 1].devices or seal_pubkey not in c.devices: return 'no'
    if seal_pubkey in unanswered: return 'later'
    return 'yes'

def older_keys_without_prompt(a):
    """§5.7 "Older keys": exactly §5.2 step 2's conditions, for a device that added itself and asks (8562)."""
    return (a['session_live_chat_read_fresh'] and a['request_created_at'] >= a['session_created_at'] - 120
            and a['request_created_at'] >= a['now'] - 30 * 86400 and a['this_device_in_pinned_anchored_head']
            and a['this_device_trusted'] and a['asker_trusted'] and not a['asker_in_chain_before_its_join']
            and a['others_let_in_last_24h'] < 3)

# ---- the section ---------------------------------------------------------------------------------------

def build(d):
    k = dict(d['keys']); k.update(d['follow']['keys'])
    own = {}
    for n in ['alice_lb4', 'alice_lb5'] + ['alice_d%02d' % i for i in range(1, 20)]:
        own[n] = {'secret': key(n), 'pubkey': pubkey(key(n))}
    k.update(own)
    sec = lambda n: k[n]['secret']
    pub = lambda n: k[n]['pubkey']
    name_of = {v['pubkey']: n for n, v in k.items()}
    T0 = d['fixed']['T0']
    A = pub('alice'); P, L, T, N, X = 'alice_phone', 'alice_laptop', 'alice_tablet', 'alice_new', 'alice_extra'

    def roster(signer, n, prev, lb, devices, at, ready=False, endorse=()):
        tags = [['p', A], ['n', str(n)]] + ([['prev', prev['id']]] if prev else []) + [['lb', pub(lb)]] + [['device', pub(x)] for x in devices] + ([['ready']] if ready else []) + [['endorse', pub(x)] for x in endorse]
        return sign_event(sec(signer), at, 8560, tags, '')

    def anchor(root, head, at):
        return sign_event(sec('alice'), at, 30078, [['d', 'belfry:sealed'], ['root', root['id']]] + ([['head', head['id']]] if head else []), '')

    base = next(c for c in d['chains'] if c['name'] == 'a device is added')
    r1 = next(r for r in base['rosters'] if ['n', '1'] in r['tags'])
    r2 = next(r for r in base['rosters'] if ['n', '2'] in r['tags'])
    assert roster(P, 1, None, 'alice_lb1', [P], T0) == r1 and roster(P, 2, r1, 'alice_lb1', [P, L], T0 + 100) == r2
    a2 = anchor(r1, r2, T0 + 101)
    assert a2 == base['anchor']

    J = roster(T, 3, r2, 'alice_lb2', [P, L, T], T0 + 200)          # the tablet adds itself
    aJ = anchor(r1, J, T0 + 201)

    def cc(name, note, anc, rosters, **more):
        c = chain(anc, rosters)
        exp = summary(c); exp['unendorsed'] = [x for x in (c.devices if c else []) if x in unendorsed(c.rosters)]; exp.update(more)
        return {'name': name, 'note': note, 'anchor': anc, 'rosters': rosters, 'expected': exp}

    chains = []
    c1 = chain(aJ, [r1, r2, J])
    chains.append(cc('an anchored join', "Root [phone]; roster 2, signed by the phone, adds the laptop. Roster 3 is signed by the tablet itself: the head's devices in their order, then the tablet, a new letterbox (alice_lb2). The anchor names it: valid. 'added' = for each device of the head, the roster that added it: the tablet's is its own roster.",
                     aJ, [r1, r2, J], added={pub(P): r1['id'], pub(L): r2['id'], pub(T): J['id']}))
    assert c1.ids == [r1['id'], r2['id'], J['id']] and c1.added_by(pub(T)).id == J['id']
    chains.append(cc('the same roster, not anchored', 'The anchor still names roster 2. The join is nothing: anyone can sign one.', a2, [r1, r2, J]))
    # a normal sibling whose id is HIGHER than the join's: were the join a candidate of the tail, it would win
    at = T0 + 210
    while True:
        S = roster(P, 3, r2, 'alice_lb1', [P, L], at, ready=True)
        if S['id'] > J['id']: break
        at += 1
    chains.append(cc('a join is never taken in the provisional tail', "After the anchored end (roster 2) come the join and a normal roster of the phone (same prev). The join's id is the lower one; it is not a candidate: the phone's roster is the head for now.", a2, [r1, r2, J, S]))
    assert chains[-1]['expected']['head'] == S['id']

    def bad(name, note, r, rosters=None, anc_root=r1):
        rs = (rosters or [r1, r2]) + [r]
        c = cc(name, note, anchor(anc_root, r, r['created_at'] + 1), rs)
        assert r['id'] not in c['expected']['valid_ids'], name
        return c
    def tail_bad(name, note, r, rosters):
        """The anchor names the last good roster; R comes after it and is not taken."""
        c = cc(name, note, anchor(r1, rosters[-1], rosters[-1]['created_at'] + 1), rosters + [r])
        assert c['expected']['head'] == rosters[-1]['id'], name
        return c
    chains.append(bad('a join that keeps the old letterbox', 'The same devices, but lb is still alice_lb1. Invalid; the anchor names an invalid roster, so the anchored path is the root alone and roster 2 follows as a valid step.', roster(T, 3, r2, 'alice_lb1', [P, L, T], T0 + 200)))
    r3out = roster(P, 3, r2, 'alice_lb2', [P], T0 + 200)           # the phone removes the laptop
    chains.append(bad('a join that goes back to an earlier letterbox', 'Roster 3 removed the laptop (alice_lb2). The tablet joins with alice_lb1, a letterbox the chain had.', roster(T, 4, r3out, 'alice_lb1', [P, T], T0 + 300), [r1, r2, r3out]))
    chains.append(bad('a join that drops a device', 'The laptop is missing.', roster(T, 3, r2, 'alice_lb2', [P, T], T0 + 200)))
    chains.append(bad('a join that reorders the devices', 'Laptop, phone, tablet.', roster(T, 3, r2, 'alice_lb2', [L, P, T], T0 + 200)))
    chains.append(bad('a join that adds two devices', 'The tablet adds alice_extra with itself.', roster(T, 3, r2, 'alice_lb2', [P, L, X, T], T0 + 200)))
    chains.append(bad('a join whose signer is not the last device', 'Phone, tablet, laptop.', roster(T, 3, r2, 'alice_lb2', [P, T, L], T0 + 200)))
    chains.append(bad('a join whose n skips a number', 'n = 4 after roster 2.', roster(T, 4, r2, 'alice_lb2', [P, L, T], T0 + 200)))
    many = [P] + ['alice_d%02d' % i for i in range(1, 20)]
    r20 = roster(P, 2, r1, 'alice_lb1', many, T0 + 100)
    c20 = cc('twenty devices, then a join', 'Roster 2 has 20 devices. A join would make 21: not a roster at all (§3.1). The device asks instead (§5.2).', None, [])
    j21 = roster(T, 3, r20, 'alice_lb2', many + [T], T0 + 200)
    c20 = bad('twenty devices, then a join', 'Roster 2 has 20 devices. A join would make 21: not a roster at all (§3.1). The device asks instead (§5.2).', j21, [r1, r20])
    chains.append(c20)
    back = roster(L, 4, r3out, 'alice_lb3', [P, L], T0 + 300)
    chains.append(bad('a join by a device the chain had and dropped', 'Roster 3 removed the laptop. Its session outlived the removal and it adds itself again: invalid (its signer is in an earlier roster of the path). It asks (§5.2).', back, [r1, r2, r3out]))
    chains.append(bad('a join dated before its prev', "The tablet's roster is dated before roster 2. Invalid: the joiner dates its own roster, never before the one it follows.", roster(T, 3, r2, 'alice_lb2', [P, L, T], T0 + 99)))
    Jsame = roster(T, 3, r2, 'alice_lb2', [P, L, T], T0 + 100)
    chains.append(cc('a join dated in the same second as its prev', 'Not before: valid.', anchor(r1, Jsame, T0 + 101), [r1, r2, Jsame]))
    assert chains[-1]['expected']['head'] == Jsame['id']

    out = roster(P, 4, J, 'alice_lb3', [P, L], T0 + 300)
    chains.append(cc('after a join, an older device removes the joiner', 'The phone (in the head before the join) signs roster 4 without the tablet, a new letterbox the tablet never gets. A normal step.', anchor(r1, out, T0 + 301), [r1, r2, J, out]))
    assert chains[-1]['expected']['head'] == out['id']

    # ---- a device that added itself changes nothing until it is endorsed
    jadd = roster(T, 4, J, 'alice_lb2', [P, L, T, X], T0 + 300)
    chains.append(bad('the joiner adds a device before it is endorsed', 'Roster 4, signed by the tablet, adds alice_extra. Invalid while no roster after the join carries ["endorse", tablet] signed by a device of the roster before the join. The anchor names an invalid path: the root, then roster 2 as a valid step; the join is not in a tail.', jadd, [r1, r2, J]))
    chains.append(bad('the joiner removes a device before it is endorsed', 'Roster 4, signed by the tablet, drops the phone (new letterbox). Invalid.', roster(T, 4, J, 'alice_lb3', [L, T], T0 + 300), [r1, r2, J]))
    chains.append(bad('the joiner only restates the roster before it is endorsed', 'Roster 4, signed by the tablet: same devices, same letterbox, with ready. Invalid as well: a joiner signs nothing that counts.', roster(T, 4, J, 'alice_lb2', [P, L, T], T0 + 300, ready=True), [r1, r2, J]))
    chains.append(bad('the joiner endorses itself', 'Roster 4, signed by the tablet, carries ["endorse", tablet]. The tablet was not in the roster before its join, and its roster counts for nothing anyway.', roster(T, 4, J, 'alice_lb2', [P, L, T], T0 + 300, endorse=[T]), [r1, r2, J]))
    chains.append(cc("the joiner's roster in the provisional tail", "The anchor names the join; the tablet's roster 4 (adds alice_extra) comes after the anchored end. Not taken there either: the head stays the join.", aJ, [r1, r2, J, jadd]))
    assert chains[-1]['expected']['head'] == J['id'] and chains[-1]['expected']['unendorsed'] == [pub(T)]
    E = roster(P, 4, J, 'alice_lb2', [P, L, T], T0 + 300, endorse=[T])   # "It was me" on the phone: the endorsement
    aE = anchor(r1, E, T0 + 301)
    chains.append(cc('the endorsement: "It was me" on the phone', "Roster 4 = the join's devices, the join's letterbox, one [\"endorse\", tablet] tag, signed by the phone (in roster 2, the one before the join), with its anchor. From then on the tablet is a device like the others: 'unendorsed' is empty.", aE, [r1, r2, J, E]))
    assert chains[-1]['expected']['unendorsed'] == []
    add5 = roster(T, 5, E, 'alice_lb2', [P, L, T, X], T0 + 400)
    chains.append(cc('endorsed, then the joiner adds a device', 'After the endorsement the tablet signs roster 5, which adds alice_extra: valid.', anchor(r1, add5, T0 + 401), [r1, r2, J, E, add5]))
    assert chains[-1]['expected']['head'] == add5['id']
    Etail = cc('the endorsement in the provisional tail', "The anchor still names the join; the phone's endorsement (4) and then the tablet's roster 5 come after the anchored end. Both are taken for now: an endorsement is a normal roster, and it counts where it stands.", aJ, [r1, r2, J, E, add5])
    assert Etail['expected']['head'] == add5['id']
    chains.append(Etail)
    plain = roster(P, 4, J, 'alice_lb2', [P, L, T], T0 + 300, ready=True)   # what a device publishes by itself
    chains.append(bad('a plain roster of an older device does not endorse', "The phone's roster 4 (ready; no endorse tag) is what devices publish by themselves. The tablet is still limited: its roster 5 is invalid. Only the tag endorses, so the limits lift only when the person taps.", roster(T, 5, plain, 'alice_lb2', [P, L, T, X], T0 + 400), [r1, r2, J, plain]))
    assert chain(anchor(r1, plain, T0 + 301), [r1, r2, J, plain]).head.id == plain['id']
    rm4 = roster(P, 4, J, 'alice_lb3', [P, T], T0 + 300)            # the phone removes the laptop (its session is gone)
    chains.append(bad("an older device's removal of another device does not endorse", "The phone's roster 4 removes the laptop and says nothing of the tablet. The tablet's roster 5 is invalid.", roster(T, 5, rm4, 'alice_lb3', [P, T, X], T0 + 400), [r1, r2, J, rm4]))
    c = cc('a roster that keeps the joiner, without the tag: still limited', "The same roster 4 (the phone removes the laptop) as the head: valid, and 'unendorsed' still names the tablet.", anchor(r1, rm4, T0 + 301), [r1, r2, J, rm4])
    assert c['expected']['head'] == rm4['id'] and c['expected']['unendorsed'] == [pub(T)]
    chains.append(c)
    rmE = roster(P, 4, J, 'alice_lb3', [P, T], T0 + 300, endorse=[T])
    add5b = roster(T, 5, rmE, 'alice_lb3', [P, T, X], T0 + 400)
    chains.append(cc('the tag counts on any roster of a device that was there before the join', "The phone's roster 4 removes the laptop AND carries [\"endorse\", tablet]. Validity asks only for the tag and its signer: the tablet's roster 5 is valid. (\"It was me\" itself publishes the same devices and the same letterbox.)", anchor(r1, add5b, T0 + 401), [r1, r2, J, rmE, add5b]))
    assert chains[-1]['expected']['head'] == add5b['id']
    odd = roster(P, 4, J, 'alice_lb2', [P, L, T], T0 + 300, endorse=[X, L])
    c = cc('an endorse tag for a device that is not waiting', "The phone's roster 4 names alice_extra (never in the chain) and the laptop (not a joiner). The tags are ignored, the roster is valid, the tablet is still limited.", anchor(r1, odd, T0 + 301), [r1, r2, J, odd])
    assert c['expected']['head'] == odd['id'] and c['expected']['unendorsed'] == [pub(T)]
    chains.append(c)
    gone = roster(P, 4, J, 'alice_lb3', [P, L], T0 + 300)
    c = cc('a joiner that left the head is no longer waiting', "The phone removes the tablet (roster 4, a new letterbox). Nothing is waiting: 'unendorsed' is empty. (It can never add itself again: the chain had it.)", anchor(r1, gone, T0 + 301), [r1, r2, J, gone])
    assert c['expected']['unendorsed'] == [] and c['expected']['head'] == gone['id']
    chains.append(c)
    J2 = roster(N, 4, J, 'alice_lb3', [P, L, T, N], T0 + 300)       # alice_new adds itself after the tablet
    c = cc('two devices add themselves, one after the other', 'The tablet (roster 3, alice_lb2), then alice_new (roster 4, alice_lb3). Both valid on the anchored path, both limited.', anchor(r1, J2, T0 + 301), [r1, r2, J, J2])
    assert c['expected']['head'] == J2['id'] and c['expected']['unendorsed'] == [pub(T), pub(N)]
    chains.append(c)
    chains.append(bad('a joiner does not endorse an earlier joiner', "After both joins alice_new signs roster 5 with [\"endorse\", tablet]. It was not in the roster before the tablet's join, and it is itself not endorsed: its roster is invalid, nobody is endorsed.", roster(N, 5, J2, 'alice_lb3', [P, L, T, N], T0 + 400, endorse=[T]), [r1, r2, J, J2]))
    chains.append(bad('a joiner that is not endorsed does not endorse a later one', "After both joins the tablet signs roster 5 with [\"endorse\", alice_new]. It was in the roster before alice_new's join, but it is itself not endorsed: its roster is invalid.", roster(T, 5, J2, 'alice_lb3', [P, L, T, N], T0 + 400, endorse=[N]), [r1, r2, J, J2]))
    E2 = roster(L, 5, J2, 'alice_lb3', [P, L, T, N], T0 + 400, endorse=[T, N])
    t6 = roster(T, 6, E2, 'alice_lb3', [P, L, T, N, X], T0 + 500)
    n7 = roster(N, 7, t6, 'alice_lb4', [P, L, T, N], T0 + 600)
    chains.append(cc('several joiners, one endorsement after both', "The laptop's roster 5 carries one endorse tag per joiner (it was in the roster before each join): both are endorsed. The tablet adds alice_extra (6), alice_new removes it (7, alice_lb4): valid.", anchor(r1, n7, T0 + 601), [r1, r2, J, J2, E2, t6, n7]))
    assert chains[-1]['expected']['head'] == n7['id']
    E1 = roster(L, 5, J2, 'alice_lb3', [P, L, T, N], T0 + 400, endorse=[T])
    t6o = roster(T, 6, E1, 'alice_lb3', [P, L, T, N], T0 + 500, ready=True)
    chains.append(tail_bad('several joiners, the endorsement names one', "The laptop's roster 5 endorses the tablet only. The tablet's roster 6 is valid; alice_new's roster 7, after the anchored end, is not taken: it is still limited.", roster(N, 7, t6o, 'alice_lb3', [P, L, T, N, X], T0 + 600), [r1, r2, J, J2, E1, t6o]))
    assert chains[-1]['expected']['unendorsed'] == [pub(N)]
    EN = roster(P, 5, J2, 'alice_lb3', [P, L, T, N], T0 + 400, endorse=[N])
    n6 = roster(N, 6, EN, 'alice_lb3', [P, L, T, N], T0 + 500, endorse=[T])
    chains.append(tail_bad('the tag signed by a device that joined after', "alice_new (which joined after the tablet) is endorsed by the phone (5), then signs roster 6 with [\"endorse\", tablet]. Its roster is valid, but it was not in the roster before the tablet's join: the tag does nothing, and the tablet's roster 7, after the anchored end, is not taken.", roster(T, 7, n6, 'alice_lb3', [P, L, T, N, X], T0 + 600), [r1, r2, J, J2, EN, n6]))
    assert chains[-1]['expected']['unendorsed'] == [pub(T)]
    J2e = roster(N, 5, E, 'alice_lb3', [P, L, T, N], T0 + 400)       # alice_new joins after the tablet was endorsed
    t6e = roster(T, 6, J2e, 'alice_lb3', [P, L, T, N], T0 + 500, endorse=[N])
    n7e = roster(N, 7, t6e, 'alice_lb3', [P, L, T, N, X], T0 + 600)
    chains.append(cc('an endorsed joiner endorses a later one', "The tablet joined (3) and was endorsed by the phone (4). alice_new joins (5). The tablet was in the roster before THAT join and is a device like the others: its roster 6 with [\"endorse\", alice_new] is valid and endorses alice_new, whose roster 7 is valid.", anchor(r1, n7e, T0 + 601), [r1, r2, J, E, J2e, t6e, n7e]))
    assert chains[-1]['expected']['head'] == n7e['id']
    chains.append(bad('an endorsement is used once: a later joiner needs its own', "The phone endorsed the tablet (4). alice_new joins (5) and signs roster 6 at once: invalid, no roster after ITS join endorses it.", roster(N, 6, J2e, 'alice_lb3', [P, L, T, N, X], T0 + 500), [r1, r2, J, E, J2e]))
    chains.append(tail_bad('an endorsement before the join it names does nothing', "The phone's roster 3 carries [\"endorse\", tablet] before the tablet has joined; the tablet then joins (4) and signs roster 5 after the anchored end: not taken, the tag must come after the join.", roster(T, 5, roster(T, 4, roster(P, 3, r2, 'alice_lb1', [P, L], T0 + 150, endorse=[T]), 'alice_lb2', [P, L, T], T0 + 200), 'alice_lb2', [P, L, T, X], T0 + 300),
                      [r1, r2, roster(P, 3, r2, 'alice_lb1', [P, L], T0 + 150, endorse=[T]), roster(T, 4, roster(P, 3, r2, 'alice_lb1', [P, L], T0 + 150, endorse=[T]), 'alice_lb2', [P, L, T], T0 + 200)]))
    assert len(chains[-1]['expected']['valid_ids']) == 4 and chains[-1]['expected']['unendorsed'] == [pub(T)]

    # ---- races
    Jn = roster(N, 3, r2, 'alice_lb3', [P, L, N], T0 + 200)          # alice_new joins on the same prev as the tablet
    aJn = anchor(r1, Jn, T0 + 202)
    chains.append(cc('two joins on the same prev: the anchor names one', "The tablet and alice_new both add themselves after roster 2. The anchor names alice_new's: the tablet's is nothing.", aJn, [r1, r2, J, Jn]))
    assert chains[-1]['expected']['head'] == Jn['id']
    Jt2 = roster(T, 4, Jn, 'alice_lb4', [P, L, N, T], T0 + 300)
    chains.append(cc('the loser joins again after the winner', "The tablet read the chain again and adds itself after alice_new's join, with a NEW letterbox (alice_lb4): the one of its lost join (alice_lb2) is never reused.", anchor(r1, Jt2, T0 + 301), [r1, r2, J, Jn, Jt2]))
    assert chains[-1]['expected']['head'] == Jt2['id']
    al = roster(P, 3, r2, 'alice_lb1', [P, L, T], T0 + 200)          # the phone allowed the tablet at the same moment
    chains.append(cc('a join that lost to a roster that already holds the device', "The phone added the tablet the usual way (roster 3, same letterbox) while the tablet was adding itself. The anchor names the phone's roster: the join is discarded, the tablet is in the head and asks for the keys (§5.5).", anchor(r1, al, T0 + 202), [r1, r2, J, al]))
    assert chains[-1]['expected']['head'] == al['id']
    chains.append(cc('a join, then an anchor without a head, read without a pin', "The anchor that named the join was replaced by one with no head. A reader with no pin (the server, a new correspondent, a new device) has the root alone as anchored path and then only normal steps: roster 2, never the join. A reader that pinned the join keeps it (see follow).", anchor(r1, None, T0 + 400), [r1, r2, J, E]))
    assert chains[-1]['expected']['head'] == r2['id']

    # ---- the pin
    def run(pin, steps):
        out = []; c = None
        for s in steps:
            c, pin, ev = follow(s['anchor'], s['rosters'], pin)
            for dv in s.get('allow_locally') or []:
                if pin.get('trusted') is not None: pin = {**pin, 'trusted': sorted({*pin['trusted'], dv})}
            o = {'anchor': s['anchor'], 'rosters': s['rosters']}
            if 'allow_locally' in s: o['allow_locally'] = s['allow_locally']
            o['expected'] = {'event': ev, 'pin': pin, 'chain': summary(c)}
            out.append(o)
        return out, c, pin

    def fc(name, note, pin, steps, want=None):
        out, c, p = run(pin, steps)
        if want: assert [s['expected']['event'] for s in out] == want, (name, [s['expected']['event'] for s in out])
        return {'name': name, 'note': note, 'pin': pin, 'steps': out}, c, p

    st = lambda anc, rs, **kw: {'anchor': anc, 'rosters': rs, **kw}
    _, _, pin2 = fc('', '', None, [st(a2, [r1, r2])])
    _, _, pinJ = fc('', '', None, [st(aJ, [r1, r2, J])])
    follows_ = []
    f, _, p = fc('the pin follows a join', 'Pinned on roster 2 (anchored). The anchor now names the join: moved, the join is the head and was seen anchored. No switch ever happened: trusted stays null (every device of the head is answered).', pin2, [st(aJ, [r1, r2, J])], ['moved'])
    assert p == pinJ and p['anchored_head'] == J['id'] and p['trusted'] is None
    follows_.append(f)
    aBack = anchor(r1, r2, T0 + 250)
    f, c, p = fc('a pinned join, then the anchor goes back behind it', 'The join was pinned as anchored. A newer anchor names roster 2 again: not followed, the join stays (no going back holds for it like any roster).', pinJ, [st(aBack, [r1, r2, J])], ['ignored_back'])
    assert c.head.id == J['id']
    follows_.append(f)
    stored = {**pin2, 'head': J['id'], 'path': [r1['id'], r2['id'], J['id']], 'anchored': False}
    f, c, p = fc('a stored pin whose join was never seen anchored', "A pin as no client writes it (anchored_head = roster 2, yet its path goes on to the join). The anchor names roster 2: the join is not accepted, the pin is cut back to roster 2. Reading (1): never in a pinned part the client had not itself seen anchored.", stored, [st(a2, [r1, r2, J])], ['moved'])
    assert c.head.id == r2['id'] and p['head'] == r2['id']
    follows_.append(f)
    aS = anchor(r1, S, T0 + 260)
    f, c, pinS = fc('a switch away from a join seen anchored', "The anchor leaves the join for the phone's own roster 3 (another branch of the same root): a switch. Trusted = the devices of the head held before that the new head still has: the phone and the laptop.", pinJ, [st(aS, [r1, r2, J, S])], ['switch'])
    assert pinS['trusted'] == sorted([pub(P), pub(L)])
    follows_.append(f)
    JS = roster(T, 4, S, 'alice_lb3', [P, L, T], T0 + 300)
    aJS = anchor(r1, JS, T0 + 301)
    sw = [st(aS, [r1, r2, J, S]), st(aJS, [r1, r2, J, S, JS])]
    f, c, p = fc('after a switch, a join: the joiner is not trusted', 'After the switch the tablet adds itself (roster 4). The join alone gives no trust: the device keeps the Allow card for it and hands it nothing (reading (2)).', pinJ, sw, ['switch', 'moved'])
    assert pub(T) not in p['trusted']
    follows_.append(f)
    S2 = roster(P, 3, r2, 'alice_lb1', [P, L, N], T0 + 260)          # the other branch: the phone had added alice_new
    aS2 = anchor(r1, S2, T0 + 261)
    JX = roster(X, 4, S2, 'alice_lb3', [P, L, N, X], T0 + 300)       # alice_extra adds itself after the switch
    aJX = anchor(r1, JX, T0 + 301)
    sw2 = [st(aS2, [r1, r2, J, S2]), st(aJX, [r1, r2, J, S2, JX])]
    EP = roster(P, 5, JX, 'alice_lb3', [P, L, N, X], T0 + 400, endorse=[X])
    XA = roster(X, 6, EP, 'alice_lb3', [P, L, N, X, T], T0 + 500)
    f, c, p = fc('after a switch, endorsed by a trusted device: trusted', "The switch left this device trusting the phone and the laptop (alice_new, which the new branch added, is not). alice_extra adds itself (4): not trusted. The phone, trusted here, publishes the endorsement (5): alice_extra is trusted from then on, and the tablet it then adds (6) is too.", pinJ,
                 sw2 + [st(anchor(r1, EP, T0 + 401), [r1, r2, J, S2, JX, EP]), st(anchor(r1, XA, T0 + 501), [r1, r2, J, S2, JX, EP, XA])], ['switch', 'moved', 'moved', 'moved'])
    assert p['trusted'] == sorted([pub(P), pub(L), pub(X), pub(T)]) and c.head.id == XA['id']
    follows_.append(f)
    EU = roster(N, 5, JX, 'alice_lb3', [P, L, N, X], T0 + 400, endorse=[X])
    XB = roster(X, 6, EU, 'alice_lb3', [P, L, N, X, T], T0 + 500)
    f, c, p = fc('after a switch, endorsed by a device that is not trusted here: the card stays', "The same, but the endorsement (5) is signed by alice_new, which this device does not trust. The chain takes it (alice_new was in the roster before the join): alice_extra may sign, and adds the tablet (6). On this device neither is trusted: the Allow card stays.", pinJ,
                 sw2 + [st(anchor(r1, EU, T0 + 401), [r1, r2, J, S2, JX, EU]), st(anchor(r1, XB, T0 + 501), [r1, r2, J, S2, JX, EU, XB])], ['switch', 'moved', 'moved', 'moved'])
    assert p['trusted'] == sorted([pub(P), pub(L)]) and c.head.id == XB['id']
    follows_.append(f)
    f, c, p = fc('after a switch, the joiner allowed on this device', 'The person taps Allow for the tablet on this device: trusted from then on.', pinJ, [sw[0], st(aJS, [r1, r2, J, S, JS], allow_locally=[pub(T)])], ['switch', 'moved'])
    assert pub(T) in p['trusted']
    follows_.append(f)

    # ---- requests of a joiner already in the head (§5.5)
    live_all = [pub(x) for x in (P, L, T, N, X)]
    def ask(device, request_at, added_at, c, p, live=None, last=None):
        lv = live_all if live is None else live
        by = c.added_by(pub(device)) if c else None
        return {'device': pub(device), 'device_name': device, 'live_chat_devices': lv, 'request_created_at': request_at, 'added_at': added_at,
                'last_handover_at': last, 'self_added': bool(by is not None and by.signer == pub(device)),
                'expected': answer_without_prompt(c, p, pub(device), lv, request_at, added_at, last)}
    def rq(name, note, pin, steps, asks):
        f, c, p = fc(name, note, pin, steps)
        f['asks'] = [ask(*(a[:3]), c, p, **(a[3] if len(a) > 3 else {})) for a in asks]
        return f
    jt = J['created_at']
    requests = [
        rq("the joiner's request in the same second as its join", "The tablet dates its 8562 at its own join roster's second. For a device that added itself that counts (>=, reading (3)).", None, [st(aJ, [r1, r2, J])], [(T, jt, jt)]),
        rq("the joiner's request dated before its join", 'One second before its roster: not answered.', None, [st(aJ, [r1, r2, J])], [(T, jt - 1, jt)]),
        rq('a device added by another device, same second', 'The laptop was added by the phone (roster 2). Its request in that roster\'s second is not newer: not answered, as before. Only a device that added itself gets >=.', None, [st(aJ, [r1, r2, J])], [(L, T0 + 100, T0 + 100), (L, T0 + 101, T0 + 100)]),
        rq('a later request after one that was refused', 'The first request (dated before the join) is refused; that is remembered for that request only. The next one is judged afresh and answered.', None, [st(aJ, [r1, r2, J])], [(T, jt - 1, jt), (T, jt + 30, jt)]),
        rq('the joiner is not a live chat session', 'Its session is not in the list: not answered (and, §5.7, a device that follows the join removes it at once).', None, [st(aJ, [r1, r2, J])], [(T, jt + 30, jt, {'live': [pub(P), pub(L)]})]),
        rq("the joiner's request must be newer than the last hand-over to it", 'As for any device: a request dated at the last hand-over is not answered again, a newer one is.', None, [st(aJ, [r1, r2, J])], [(T, jt + 30, jt, {'last': jt + 30}), (T, jt + 31, jt, {'last': jt + 30})]),
        rq('a pinned join the anchor went back behind', "The pin keeps the join, but the anchored end is roster 2 again, which does not hold the tablet: not answered until the anchor names the join's branch.", pinJ, [st(aBack, [r1, r2, J])], [(T, jt + 30, jt)]),
        rq('after a switch the joiner goes through Allow', 'Not trusted by its join (reading (2)): never answered without a prompt, whatever its request. Once allowed on this device, it is.', pinJ, sw, [(T, T0 + 330, T0 + 300), (P, T0 + 330, T0)]),
        rq('after a switch, allowed here: answered', 'The person allowed the tablet on this device.', pinJ, [sw[0], st(aJS, [r1, r2, J, S, JS], allow_locally=[pub(T)])], [(T, T0 + 300, T0 + 300)]),
    ]
    want = [[True], [False], [False, True], [False, True], [False], [False, True], [False], [False, True], [True]]
    assert [[a['expected'] for a in r['asks']] for r in requests] == want, [[a['expected'] for a in r['asks']] for r in requests]

    # ---- older keys for a joiner (§5.7 "Older keys" = §5.2 step 2's conditions)
    now = T0 + 1000
    ok = {'now': now, 'request_created_at': jt + 5, 'session_created_at': jt - 20, 'session_live_chat_read_fresh': True,
          'this_device_in_pinned_anchored_head': True, 'this_device_trusted': True, 'asker_trusted': True,
          'asker_in_chain_before_its_join': False, 'others_let_in_last_24h': 0}
    def ok_case(name, note, **ch):
        a = {**ok, **ch}
        return {'name': name, 'note': note, 'given': a, 'expected': 'keys' if older_keys_without_prompt(a) else 'card'}
    older = [
        ok_case('every condition holds', 'The session list was read for this request and holds the joiner as a live chat session; the request is not older than the session; this device is in the head it pinned, on the anchored end, trusted; the chain never had the joiner before its join; no other device was let in today. The older letterboxes go without a prompt (what the giver holds).'),
        ok_case('the session list could not be read, or does not hold a live chat session for it', 'No hand-over. (A joiner that is no live chat session is removed at once by a device that follows its join.)', session_live_chat_read_fresh=False),
        ok_case('the request is older than the session', 'More than 120 s before the session was opened.', request_created_at=jt - 141),
        ok_case('the request is more than 30 days old', '', request_created_at=now - 30 * 86400 - 1, session_created_at=now - 31 * 86400),
        ok_case('this device is not on the anchored end of the head it pinned', 'A head not anchored yet, or an anchor that went back.', this_device_in_pinned_anchored_head=False),
        ok_case('after a switch, this device is not trusted', '', this_device_trusted=False),
        ok_case('after a switch, the joiner is not trusted', 'Reading (2): the card, until the person allows it here.', asker_trusted=False),
        ok_case('three other devices were let in within 24 hours', "Devices added to my head in the last 24 hours, the joiner itself not counted; a join counts from the moment THIS device first saw it, never from the roster's own date; any other roster from its created_at. A fourth gets the card.", others_let_in_last_24h=3),
        ok_case('two other devices were let in within 24 hours', 'Still under the limit.', others_let_in_last_24h=2),
    ]
    assert [o['expected'] for o in older] == ['keys', 'card', 'card', 'card', 'card', 'card', 'card', 'card', 'keys']

    # ---- the hand-over from the joiner (§5.5)
    def handover(name, note, pairs, ch, accepted, kept=False):
        at = T0 + 220
        content = compact({'v': 1, 'account': A, 'keys': [{'lb': lb, 'secret': s} for lb, s in pairs]})
        rum = rumor(pub(T), at, 8561, [['p', A]], content)
        nonce = lambda what: hashlib.sha256(('belfry sealed vectors/nonce/' + what + '/join: ' + name).encode()).digest()
        once = hashlib.sha256(('belfry sealed vectors/once/join: ' + name).encode()).hexdigest()
        seal = sign_event(sec(T), at + 1, 13, [], nip44_encrypt(sec(T), pub(P), compact(rum), nonce('seal')))
        wrap = sign_event(once, at + 2, 1059, [['p', pub(P)], ['silent', '1']], nip44_encrypt(once, pub(P), compact(seal), nonce('wrap')))
        good = [{'lb': lb, 'secret': s} for lb, s in pairs if pubkey(s) == lb]
        c = chain(ch['anchor'], ch['rosters'])
        assert (pub(T) in {x for r in c.rosters for x in r.devices}) == accepted
        return {'name': name, 'note': note, 'wrap': wrap, 'device': P, 'device_secret': sec(P), 'account': A, 'chain': ch,
                'expected': {'giver': pub(T), 'rumor': rum, 'accepted': accepted, 'kept_for_later': kept, 'keys': good if accepted else []}}
    with_join = {'anchor': aJ, 'rosters': [r1, r2, J]}
    before_join = {'anchor': a2, 'rosters': [r1, r2]}
    handovers = [
        handover('the joiner hands the new secret to the phone', "One 8561 with the join's letterbox (alice_lb2), silent, to the phone's device key. The phone follows the join: the giver is a device of its chain. Taken.", [(pub('alice_lb2'), sec('alice_lb2'))], with_join, True),
        handover("the same hand-over before the phone has the join's roster", "The phone's chain still ends at roster 2: the giver is not in it. Not taken now, and KEPT: tried again when the chain moves (it is then the case above).", [(pub('alice_lb2'), sec('alice_lb2'))], before_join, False, kept=True),
        handover("a secret that is not its lb's", "The joiner sends alice_lb2 with another key's secret, and alice_lb4 rightly: only the pair that checks out is taken (and an lb the phone already holds is never replaced).", [(pub('alice_lb2'), sec('alice_lb3')), (pub('alice_lb4'), sec('alice_lb4'))], with_join, True),
    ]

    # ---- "It was me" (8563) with joiners
    at = T0 + 600
    def conf(name, note, ch, signer, added, roster_id, unanswered):
        rum = rumor(pub(signer), at, 8563, [['p', A], ['device', pub(added)], ['e', roster_id]], '')
        c = chain(ch['anchor'], ch['rosters'])
        v = accept_confirm(rum, pub(signer), c, [pub(x) for x in unanswered])
        return {'name': name, 'note': note, 'chain': ch, 'rumor': rum, 'seal_pubkey': pub(signer), 'unanswered_on_this_device': [pub(x) for x in unanswered],
                'expected': {'accepted': v == 'yes', 'judged_again_later': v == 'later'}}
    ch_add = {'anchor': anchor(r1, add5, T0 + 401), 'rosters': [r1, r2, J, E, add5]}
    confirms = [
        conf('an older device confirms the joiner', "The phone was in roster 2, the one before the join, and is in the head: accepted, the notice for the tablet goes. (The phone also publishes the endorsement roster; see 'endorsement'.)", with_join, P, T, J['id'], [T]),
        conf('the joiner confirms itself', 'The tablet is not in the roster before its own join.', with_join, T, T, J['id'], [T]),
        conf("a joiner whose own notice is unanswered here confirms a device it added", "The tablet joined (3), the phone's roster 4 endorsed it (the tag), the tablet added alice_extra (5) and says 'It was me' for it. On THIS device the notice for the tablet is still unanswered (its 8563 has not been accepted here): the tablet's word does not count yet. Kept and judged again; the notice for alice_extra stays.", ch_add, T, X, add5['id'], [T, X]),
        conf('the same once the notice for the joiner is answered here', 'This device accepted an 8563 for the tablet (or the person tapped here): now the confirmation counts.', ch_add, T, X, add5['id'], [X]),
    ]
    assert [(c['expected']['accepted'], c['expected']['judged_again_later']) for c in confirms] == [(True, False), (False, False), (False, True), (True, False)]

    endorsement = {
        'note': "\"It was me\" endorses (§5.7): besides the 8563, the device that is tapped publishes roster n+1 = the head's devices in their order, the head's lb, `ready` by the usual rule, then one [\"endorse\", <device>] tag per joiner it confirms (after `ready`, the last tags), signed by itself, with its anchor (§3.2). Only that tag endorses. 'before' is my chain with the unanswered join as its head; 'roster' and 'anchor' are what the phone publishes; 'after' is the chain then. Remove needs no endorsement: it is a roster of an older device already.",
        'tapped_device': pub(P), 'joiner': pub(T),
        'before': {'anchor': aJ, 'rosters': [r1, r2, J], 'expected': summary(chain(aJ, [r1, r2, J]))},
        'roster': E, 'anchor': aE,
        'after': {'expected': summary(chain(aE, [r1, r2, J, E]))},
        'confirmation': confirms[0]['rumor'],
    }
    assert [t for t in E['tags'] if t[0] in ('lb', 'device')] == [t for t in J['tags'] if t[0] in ('lb', 'device')] and E['tags'][-1] == ['endorse', pub(T)]
    endorsement['endorses'] = [pub(T)]

    return {
        'note': "sealed.md §5.7 \"A device that signed in adds itself\", with its \"Readings fixed before the first build\" and \"Tightened after Android's review\" (belfry-sh 1440e79). Added by belfry-android (tools/sealed_join_vectors.py, which writes this file's other sections back byte for byte, and computes every 'expected' with its own port of the reference) on 2026-11-23; to be taken into services/onboarding/tools/sealed_vectors.py. A JOIN is a roster signed by a device that is not in its prev: valid only when n = prev's + 1, its devices are exactly prev's in their order plus the signer last, its lb is a letterbox the chain never had, its signer is in no earlier roster of the path, its created_at is not before its prev's, AND it lies on the anchored path (root → the anchor's head); a client also keeps one it pinned as anchored. It is never taken in the provisional tail. A normal roster signed by a device that added itself is invalid (on the path and in the tail) while no roster after its join carries [\"endorse\", <that device>] signed by a device that was in the roster BEFORE that join; only that tag endorses (a roster's endorse tags come last, after `ready`), and a joiner that left the head waits for nothing. After a switch, an endorsed joiner is trusted when the endorsing device is trusted on that device. Sections: 'chains' (shape of the top-level 'chains'; 'added' where given = device → the roster that added it; 'unendorsed' = the devices of the head still limited, in the head's order), 'follow' (shape of 'follow'), 'requests' (shape of 'trust': after the steps each of 'asks' is answer_without_prompt(chain, pin, device, live_chat_devices, request_created_at, added_at, last_handover_at); 'self_added' says the roster that last added the device is signed by it, which is what makes >= apply), 'older_keys' (the decision to hand a joiner the earlier letterboxes without a prompt, from plain facts: 'keys' or 'card'), 'handover' (shape of 'keys_handover', the chain given whole; 'kept_for_later' = not taken now but kept and tried again), 'confirm' (shape of 'confirm', each case with its own chain and the devices whose added-notice is unanswered on the judging device) and 'endorsement'. 'keys' holds the test keys this section adds (made from their names like every other).",
        'keys': own,
        'chains': chains,
        'follow': follows_,
        'requests': requests,
        'older_keys': older,
        'handover': handovers,
        'confirm': confirms,
        'endorsement': endorsement,
    }

if __name__ == '__main__':
    path = next((a for a in sys.argv[1:] if not a.startswith('--')), 'vectors/sealed.json')
    raw = open(path, encoding='utf-8').read()
    d = json.loads(raw)
    had = d.pop('join', None)
    section = build(d)
    if '--check' in sys.argv:
        print('up to date' if had == section else 'DIFFERENT'); sys.exit(0 if had == section else 1)
    assert json.dumps(d if had is None else {**d, 'join': had}, indent=1, ensure_ascii=False) + '\n' == raw, 'the file is not in the generator\'s format, or join is not its last section'
    d['join'] = section
    open(path, 'w', encoding='utf-8').write(json.dumps(d, indent=1, ensure_ascii=False) + '\n')
