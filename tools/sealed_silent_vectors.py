#!/usr/bin/env python3
"""
Adds the "silent" section (collective-belfry docs/sealed.md §4 "Silent wraps", belfry-sh 27e149a; SPEC
19.67) to vectors/sealed.json: which wraps carry ["silent", "1"], which tags count, and wraps a reader
opens exactly as before. Only adds: every existing case is written back byte for byte. Python 3:

    python3 tools/sealed_silent_vectors.py [vectors/sealed.json] [--check]
"""
import hashlib, json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.dont_write_bytecode = True
from sealed_confirm_vectors import compact, nip44_encrypt, rumor, sign_event

def build(d):
    k = d['keys']
    sec = lambda n: k[n]['secret']
    pub = lambda n: k[n]['pubkey']
    T0 = d['fixed']['T0']
    alice, bob = pub('alice'), pub('bob')
    root = next(c for c in d['chains'] if c['name'] == 'a valid root')
    lb = root['expected']['lb']
    at = T0 + 900
    S = ['silent', '1']

    def carry(what, why, p, sealed, expected):
        return {'what': what, 'note': why, 'p': p, 'sealed': sealed, 'expected': {'silent': expected}}

    carries = [
        carry('sealed message: the copy to myself', 'Kind 14 / 15 / 7 / 5 sealed to my own head letterbox beside the wrap for the other side.', 'my account', True, True),
        carry('sealed message: the wrap for the other person', 'The same rumor sealed to their head letterbox. Never silent.', 'their account', True, False),
        carry('old-style message: the copy to myself', 'NIP-17 through the signer, p = my account.', 'my account', False, True),
        carry('old-style message: the wrap for the other person', 'NIP-17 through the signer, p = their account. Never silent.', 'their account', False, False),
        carry('a sealed note to myself', 'The only wrap of a note (the notes section).', 'my account', True, True),
        carry('an old-style note to myself', 'On an account that cannot seal: one NIP-17 wrap to my own account.', 'my account', False, True),
        carry('an 8563', '"It was me", sealed to my own head letterbox (the confirm section).', 'my account', True, True),
        carry('keys for one of my devices', 'The 8561 hand-over (§5.5): a NIP-59 wrap whose p is my receiving device.', 'my device', False, True),
        carry('shared history for someone else', 'An 8564 sealed to their head letterbox (§8). Never silent.', 'their account', True, False),
        carry('a mail for the belfry', 'A 1301 wrapped to the belfry\'s mail key (§7). Never silent; its copy to myself is.', "the belfry's key", False, False),
    ]

    def tc(name, tags, silent):
        return {'name': name, 'tags': tags, 'expected': {'silent': silent, 'valid': True}}
    base = [['p', alice], ['lb', lb]]
    tags = [
        tc('the tag', base + [S], True),
        tc('no tag', base, False),
        tc('another value', base + [['silent', '0']], False),
        tc('another value: true', base + [['silent', 'true']], False),
        tc('no value', base + [['silent']], False),
        tc('one more element', base + [['silent', '1', 'x']], False),
        tc('another case', base + [['Silent', '1']], False),
        tc('given twice', base + [S, S], True),
        tc('the tag first', [S] + base, True),
        tc('an old-style wrap with the tag', [['p', alice], S], True),
        tc('an unknown tag beside it', base + [['x-later', 'anything'], S], True),
        tc('only an unknown tag', base + [['x-later', 'anything']], False),
    ]

    note = rumor(alice, at, 14, [['p', alice]], 'Where the shed key hangs')
    def sealed_wrap(name, extra):
        nonce = lambda what: hashlib.sha256(('belfry sealed vectors/nonce/' + what + '/' + name).encode()).digest()
        once = hashlib.sha256(('belfry sealed vectors/once/' + name).encode()).hexdigest()
        seal = sign_event(sec('alice_phone'), at + 1, 13, [], nip44_encrypt(sec('alice_phone'), lb, compact(note), nonce('seal')))
        return sign_event(once, at + 2, 1059, [['p', alice], ['lb', lb]] + extra, nip44_encrypt(once, lb, compact(seal), nonce('wrap')))
    def old_wrap(name, extra):
        nonce = lambda what: hashlib.sha256(('belfry sealed vectors/nonce/' + what + '/' + name).encode()).digest()
        once = hashlib.sha256(('belfry sealed vectors/once/' + name).encode()).hexdigest()
        seal = sign_event(sec('alice'), at + 1, 13, [], nip44_encrypt(sec('alice'), alice, compact(note), nonce('seal')))
        return sign_event(once, at + 2, 1059, [['p', alice]] + extra, nip44_encrypt(once, alice, compact(seal), nonce('wrap')))

    def wc(name, why, wrap, sealed, silent, seal_pub):
        return {'name': name, 'note': why, 'sealed': sealed, 'wrap': wrap,
                'expected': {'silent': silent, 'opens': True, 'seal_pubkey': seal_pub, 'rumor': note}}
    wraps = [
        wc('silent: a sealed note', 'Opened with alice_lb1 exactly as a wrap without the tag.', sealed_wrap('silent: a sealed note', [S]), True, True, pub('alice_phone')),
        wc('silent: a sealed note with tags a reader does not know', 'An unknown tag and a silent tag of another value: both ignored, the wrap opens, and it is not silent.',
           sealed_wrap('silent: a sealed note with tags a reader does not know', [['x-later', 'anything'], ['silent', '0']]), True, False, pub('alice_phone')),
        wc('silent: an old-style note', "NIP-17: sealed by Alice's account to itself, opened with her account key. No lb.", old_wrap('silent: an old-style note', [S]), False, True, alice),
        wc('silent: an old-style note with a tag a reader does not know', 'Opens as before.', old_wrap('silent: an old-style note with a tag a reader does not know', [['x-later', 'anything']]), False, False, alice),
    ]
    return {
        'note': "Silent wraps (collective-belfry docs/sealed.md §4 \"Silent wraps\", belfry-sh 27e149a). Added by belfry-android (tools/sealed_silent_vectors.py, which writes this file's other sections back byte for byte) on 2026-11-23; to be taken into services/onboarding/tools/sealed_vectors.py. A wrap (kind 1059) that addresses only its own sender carries one more tag, [\"silent\", \"1\"], on the wrap only: never in the seal or the rumor. 'carries': shape only, which wraps a client makes with it ('p' says whose key the wrap's p tag is). 'tags': which tag lists are silent; only the exact two-element tag counts, anything else is read as absent and the wrap stays valid (Android reads [\"silent\", \"1\", \"x\"] strictly as not silent; for the lead to confirm). 'wraps': real wraps of one note of Alice's, sealed (alice_lb1, the chain 'a valid root') and old-style: a reader opens each exactly as a wrap without the tag and ignores tags it does not know.",
        'tag': S,
        'carries': carries,
        'tags': tags,
        'letterbox': 'alice_lb1', 'letterbox_secret': sec('alice_lb1'), 'account': 'alice',
        'wraps': wraps,
    }

if __name__ == '__main__':
    path = next((a for a in sys.argv[1:] if not a.startswith('--')), 'vectors/sealed.json')
    raw = open(path, encoding='utf-8').read()
    d = json.loads(raw)
    had = d.pop('silent', None)
    section = build(d)
    if '--check' in sys.argv:
        print('up to date' if had == section else 'DIFFERENT'); sys.exit(0 if had == section else 1)
    assert had is not None or json.dumps(d, indent=1, ensure_ascii=False) + '\n' == raw, "the file is not in the generator's format"
    d['silent'] = section
    open(path, 'w', encoding='utf-8').write(json.dumps(d, indent=1, ensure_ascii=False) + '\n')
