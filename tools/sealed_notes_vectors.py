#!/usr/bin/env python3
"""
Adds the "notes" section (collective-belfry docs/one-shape.md "Notes to myself"; SPEC 19.66) to
vectors/sealed.json, in the style of the rest of the file (keys made from their names, fixed nonces,
32 zero bytes of Schnorr auxiliary randomness; the crypto is tools/sealed_confirm_vectors.py's). Only
adds: every existing case is written back byte for byte. Python 3, no dependency:

    python3 tools/sealed_notes_vectors.py [vectors/sealed.json] [--check]
"""
import hashlib, json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.dont_write_bytecode = True
from sealed_confirm_vectors import compact, nip44_encrypt, rumor, sign_event

def h(what): return hashlib.sha256(('belfry sealed vectors/notes/' + what).encode()).hexdigest()

def build(d):
    k = d['keys']
    sec = lambda n: k[n]['secret']
    pub = lambda n: k[n]['pubkey']
    T0 = d['fixed']['T0']
    alice, bob = pub('alice'), pub('bob')
    chain = lambda name: next(c for c in d['chains'] if c['name'] == name)
    root = chain('a valid root')            # roster 1: [phone], alice_lb1, no `ready`
    ready = chain('ready, then a roster without it: still ready')
    assert root['expected']['ready'] is False and ready['expected']['ready'] is True
    at = T0 + 700

    note = rumor(alice, at, 14, [['p', alice]], 'Bring the extension reel, 2 plugs')
    reply = rumor(alice, at + 60, 14, [['p', alice], ['e', note['id'], '', 'root']], 'And the long ladder')
    picture = rumor(alice, at + 120, 15, [
        ['p', alice], ['file-type', 'image/jpeg'], ['encryption-algorithm', 'aes-gcm'],
        ['decryption-key', h('file key')], ['decryption-nonce', h('file nonce')[:24]],
        ['x', h('file x')], ['ox', h('file ox')], ['size', '48213'], ['dim', '1200x900'],
    ], 'https://files.belfry.example/' + h('file x'))
    reaction = rumor(alice, at + 180, 7, [['e', note['id'], '', alice], ['p', alice], ['k', '14']], '✅')
    takeback = rumor(alice, at + 240, 5, [['e', reaction['id']], ['k', '7'], ['p', alice]], '')
    old = rumor(alice, at - 86400, 14, [], 'A note from before the p tag')
    to_bob = rumor(alice, at + 300, 14, [['p', bob]], 'See you Sunday!')
    with_bob = rumor(alice, at + 360, 14, [['p', alice], ['p', bob]], 'For the two of us')
    from_bob = rumor(bob, at + 420, 14, [['p', alice]], 'Thanks for the soup!')

    def rc(name, why, r, notes):
        people = sorted(set([r['pubkey']] + [t[1] for t in r['tags'] if t[0] == 'p']))
        return {'name': name, 'note': why, 'rumor': r, 'expected': {'participants': people, 'notes': notes}}

    rumors = [
        rc('a note', 'Kind 14, pubkey = my account, exactly one p tag = my account.', note, True),
        rc('a reply to a note', 'As in any direct conversation: ["e", <root>, "", "root"] after the p tag.', reply, True),
        rc('a picture', 'Kind 15 the same way: the one p tag first, then the file tags (dm-files.json).', picture, True),
        rc('a reaction to a note', 'Kind 7: e (the note, its author), p (its author = me), k.', reaction, True),
        rc('a reaction taken back', 'Kind 5: e (the reaction), k, and the one p tag.', takeback, True),
        rc('a note with no p tag', 'What older apps wrote. Read as a note: the participants of a rumor are its author and its p tags, here only me. Never written this way any more.', old, True),
        rc('a message to Bob', 'One p tag, but not mine: the conversation with Bob.', to_bob, False),
        rc('p names me and Bob', 'Two participants: the conversation with Bob, not Notes.', with_bob, False),
        rc("Bob's message to me", "One p tag = my account, but the author is Bob: the conversation with Bob. Nobody else can write into my Notes.", from_bob, False),
    ]

    # the single wrap: sealed (§4) by the phone to Alice's own head letterbox, on a chain that is NOT ready
    wname = 'notes: a note'
    nonce = lambda what: hashlib.sha256(('belfry sealed vectors/nonce/' + what + '/' + wname).encode()).digest()
    once = hashlib.sha256(('belfry sealed vectors/once/' + wname).encode()).hexdigest()
    lb = root['expected']['lb']
    assert lb == pub('alice_lb1')
    seal = sign_event(sec('alice_phone'), at + 1, 13, [], nip44_encrypt(sec('alice_phone'), lb, compact(note), nonce('seal')))
    wrap = sign_event(once, at + 2, 1059, [['p', alice], ['lb', lb]], nip44_encrypt(once, lb, compact(seal), nonce('wrap')))

    def plan(name, why, device, ch, answer, can_seal, expected):
        return {'name': name, 'note': why, 'device': device, 'can_seal': can_seal, 'answer': answer,
                'chain': None if ch is None else {'anchor': ch['anchor'], 'rosters': ch['rosters']}, 'expected': expected}

    sealed_to = lambda c: {'decision': 'seal', 'wraps': 1, 'lb': c['expected']['lb'], 'signer_calls': 0, 'old_way': False, 'makes_chain': False}
    plans = [
        plan('my chain is not ready, this device is in its head',
             'The not-ready case: any other conversation would wait for `ready` (§6); Notes does not. One wrap, to my own head letterbox, the seal signed by this device, no signer call.',
             pub('alice_phone'), root, 'complete', True, sealed_to(root)),
        plan('my chain is ready', 'As any sealed conversation, with one participant: still one wrap (the self-copy is the message).',
             pub('alice_phone'), ready, 'complete', True, sealed_to(ready)),
        plan('I have a chain, this device is not in its head',
             'A device of mine not added yet: the note waits with the usual waiting banner until the device is added. Never the old way.',
             pub('alice_tablet'), root, 'complete', True,
             {'decision': 'wait_for_device', 'wraps': 0, 'signer_calls': 0, 'old_way': False, 'makes_chain': False}),
        plan('no chain, a definite answer',
             'A complete answer after authentication shows no anchor: the device makes the chain now (first-device path, §5.1) and holds the note, shown as sending, until the chain is anchored; then it is sealed as in the first case. Nothing unsealed goes out.',
             pub('alice_phone'), None, 'complete', True,
             {'decision': 'make_chain_then_seal', 'wraps': 0, 'wraps_once_anchored': 1, 'signer_calls': 0, 'old_way': False, 'makes_chain': True}),
        plan('no chain known, the answer is not complete',
             'Silence, a CLOSED, an answer from before authentication: no chain is made and nothing is sent, sealed or not. The note is held, shown as sending, and decided at the first complete answer.',
             pub('alice_phone'), None, 'incomplete', True,
             {'decision': 'hold', 'wraps': 0, 'signer_calls': 0, 'old_way': False, 'makes_chain': False}),
        plan('an account that cannot seal',
             'An agent, an account that holds its own key, a guest: no device key, so no chain. One NIP-17 wrap to my own account through the signer (p = me, no lb), as old direct messages. No lock, and never the "nobody else" sentence.',
             None, None, 'complete', False,
             {'decision': 'old_way', 'wraps': 1, 'lb': None, 'old_way': True, 'makes_chain': False}),
    ]

    return {
        'note': "Notes to myself (collective-belfry docs/one-shape.md \"Notes to myself\", ec048e3). Added by belfry-android (tools/sealed_notes_vectors.py, which writes this file's other sections back byte for byte) on 2026-11-23; to be taken into services/onboarding/tools/sealed_vectors.py. Notes is the ordinary direct conversation whose only participant is me: 'conversation' gives its participants (the conversation id is each platform's DM id of that set). 'rumors': what is and is not a note (expected.participants = the author and the p tags, sorted; notes = that set is exactly me). 'wrap': the first rumor sealed (§4) to my own head letterbox by my phone, on the chain 'a valid root' (not ready): the ONLY wrap of a note, since the recipient's copy and the self-copy are the same. 'plans': how a note is sent by 'device' given what the relay answered about my chain ('chain' null = no anchor; 'answer' complete = after authentication and to EOSE). Notes never waits for `ready`, and on a device that can seal a note is never sent the old way. No typing event (kind 21059 / 20002) is ever sent for this conversation. The row is the first of Messages, never unread.",
        'account': alice,
        'conversation': {'participants': [alice], 'android_id': 'n:' + alice},
        'rumors': rumors,
        'wrap': {
            'name': wname,
            'note': "Alice's phone seals the note to her own head letterbox (alice_lb1). The chain has no `ready`; the rumor is accepted all the same, the seal's signer being in the head.",
            'wrap': wrap, 'letterbox': 'alice_lb1', 'letterbox_secret': sec('alice_lb1'),
            'chain': {'anchor': root['anchor'], 'rosters': root['rosters']},
            'expected': {'seal_pubkey': pub('alice_phone'), 'rumor': note, 'accepted': True, 'chain_ready': False, 'wraps': 1},
        },
        'plans': plans,
    }

if __name__ == '__main__':
    path = next((a for a in sys.argv[1:] if not a.startswith('--')), 'vectors/sealed.json')
    raw = open(path, encoding='utf-8').read()
    d = json.loads(raw)
    had = d.pop('notes', None)
    section = build(d)
    if '--check' in sys.argv:
        print('up to date' if had == section else 'DIFFERENT'); sys.exit(0 if had == section else 1)
    assert json.dumps(d, indent=1, ensure_ascii=False) + '\n' == (raw if had is None else json.dumps({**d, 'notes': had}, indent=1, ensure_ascii=False) + '\n'), 'the file is not in the generator\'s format'
    d['notes'] = section
    open(path, 'w', encoding='utf-8').write(json.dumps(d, indent=1, ensure_ascii=False) + '\n')
