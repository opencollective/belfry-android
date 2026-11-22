#!/usr/bin/env python3
"""
Adds the "confirm" section (sealed.md §5.2 step 2b, kind 8563) to vectors/sealed.json, in the style of
the rest of the file (collective-belfry services/onboarding/tools/sealed_vectors.py: keys made from
their names, fixed nonces, 32 zero bytes of Schnorr auxiliary randomness). Only adds: every existing
case is written back byte for byte. Python 3, no dependency:

    python3 tools/sealed_confirm_vectors.py [vectors/sealed.json] [--check]
"""
# Minimal pure-Python secp256k1 / BIP-340 / NIP-44 v2 / NIP-01, for test vectors only (not constant time).
import hashlib, hmac, json, struct, base64

P = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F
N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
G = (0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
     0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8)

def add(a, b):
    if a is None: return b
    if b is None: return a
    if a[0] == b[0] and (a[1] + b[1]) % P == 0: return None
    if a == b: l = 3 * a[0] * a[0] * pow(2 * a[1], P - 2, P) % P
    else: l = (b[1] - a[1]) * pow(b[0] - a[0], P - 2, P) % P
    x = (l * l - a[0] - b[0]) % P
    return (x, (l * (a[0] - x) - a[1]) % P)

def mul(p, n):
    r = None
    while n:
        if n & 1: r = add(r, p)
        p = add(p, p); n >>= 1
    return r

def lift_x(x):
    y2 = (pow(x, 3, P) + 7) % P
    y = pow(y2, (P + 1) // 4, P)
    assert y * y % P == y2
    return (x, y if y % 2 == 0 else P - y)

def b32(i): return i.to_bytes(32, 'big')
def sha(b): return hashlib.sha256(b).digest()
def tagged(tag, m):
    t = sha(tag.encode()); return sha(t + t + m)

def pubkey(secret_hex): return b32(mul(G, int(secret_hex, 16))[0]).hex()

def schnorr_sign(msg, secret_hex, aux=b'\x00' * 32):
    d0 = int(secret_hex, 16); Pt = mul(G, d0)
    d = d0 if Pt[1] % 2 == 0 else N - d0
    t = b32(d ^ int.from_bytes(tagged('BIP0340/aux', aux), 'big'))
    k0 = int.from_bytes(tagged('BIP0340/nonce', t + b32(Pt[0]) + msg), 'big') % N
    R = mul(G, k0); k = k0 if R[1] % 2 == 0 else N - k0
    e = int.from_bytes(tagged('BIP0340/challenge', b32(R[0]) + b32(Pt[0]) + msg), 'big') % N
    return (b32(R[0]) + b32((k + e * d) % N)).hex()

def schnorr_verify(msg, pub_hex, sig_hex):
    Pt = lift_x(int(pub_hex, 16)); sig = bytes.fromhex(sig_hex)
    r = int.from_bytes(sig[:32], 'big'); s = int.from_bytes(sig[32:], 'big')
    e = int.from_bytes(tagged('BIP0340/challenge', sig[:32] + bytes.fromhex(pub_hex) + msg), 'big') % N
    R = add(mul(G, s), mul(Pt, N - e))
    return R is not None and R[1] % 2 == 0 and R[0] == r

def compact(o): return json.dumps(o, separators=(',', ':'), ensure_ascii=False)

def event_id(pub, created_at, kind, tags, content):
    return sha(compact([0, pub, created_at, kind, tags, content]).encode()).hex()

def rumor(pub, created_at, kind, tags, content):
    return {"id": event_id(pub, created_at, kind, tags, content), "pubkey": pub, "created_at": created_at, "kind": kind, "tags": tags, "content": content}

def sign_event(secret_hex, created_at, kind, tags, content):
    pub = pubkey(secret_hex); i = event_id(pub, created_at, kind, tags, content)
    return {"kind": kind, "created_at": created_at, "tags": tags, "content": content, "pubkey": pub, "id": i, "sig": schnorr_sign(bytes.fromhex(i), secret_hex)}

# ---- NIP-44 v2 ----
def rotl(v, c): return ((v << c) & 0xffffffff) | (v >> (32 - c))
def chacha20(key, nonce, data, counter=0):
    def block(c):
        s = list(struct.unpack('<4I', b'expand 32-byte k')) + list(struct.unpack('<8I', key)) + [c] + list(struct.unpack('<3I', nonce))
        w = s[:]
        def qr(a, b, c_, d):
            w[a] = (w[a] + w[b]) & 0xffffffff; w[d] = rotl(w[d] ^ w[a], 16)
            w[c_] = (w[c_] + w[d]) & 0xffffffff; w[b] = rotl(w[b] ^ w[c_], 12)
            w[a] = (w[a] + w[b]) & 0xffffffff; w[d] = rotl(w[d] ^ w[a], 8)
            w[c_] = (w[c_] + w[d]) & 0xffffffff; w[b] = rotl(w[b] ^ w[c_], 7)
        for _ in range(10):
            qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15)
            qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14)
        return struct.pack('<16I', *[(w[i] + s[i]) & 0xffffffff for i in range(16)])
    out = bytearray()
    for i in range(0, len(data), 64):
        ks = block(counter + i // 64)
        out += bytes(a ^ b for a, b in zip(data[i:i + 64], ks))
    return bytes(out)

def hkdf_extract(salt, ikm): return hmac.new(salt, ikm, hashlib.sha256).digest()
def hkdf_expand(prk, info, n):
    out = b''; t = b''; i = 1
    while len(out) < n:
        t = hmac.new(prk, t + info + bytes([i]), hashlib.sha256).digest(); out += t; i += 1
    return out[:n]

def conv_key(secret_hex, pub_hex):
    shared = mul(lift_x(int(pub_hex, 16)), int(secret_hex, 16))[0]
    return hkdf_extract(b'nip44-v2', b32(shared))

def padded_len(n):
    if n <= 32: return 32
    np2 = 1 << ((n - 1).bit_length())
    chunk = 32 if np2 <= 256 else np2 // 8
    return chunk * ((n - 1) // chunk + 1)

def nip44_encrypt(secret_hex, pub_hex, text, nonce):
    ck = conv_key(secret_hex, pub_hex)
    keys = hkdf_expand(ck, nonce, 76); ckey, cnonce, hkey = keys[:32], keys[32:44], keys[44:]
    pt = text.encode(); padded = struct.pack('>H', len(pt)) + pt + b'\x00' * (padded_len(len(pt)) - len(pt))
    ct = chacha20(ckey, cnonce, padded)
    mac = hmac.new(hkey, nonce + ct, hashlib.sha256).digest()
    return base64.b64encode(b'\x02' + nonce + ct + mac).decode()

def nip44_decrypt(secret_hex, pub_hex, payload):
    raw = base64.b64decode(payload); assert raw[0] == 2
    nonce, ct, mac = raw[1:33], raw[33:-32], raw[-32:]
    ck = conv_key(secret_hex, pub_hex)
    keys = hkdf_expand(ck, nonce, 76); ckey, cnonce, hkey = keys[:32], keys[32:44], keys[44:]
    assert hmac.compare_digest(mac, hmac.new(hkey, nonce + ct, hashlib.sha256).digest())
    padded = chacha20(ckey, cnonce, ct); n = struct.unpack('>H', padded[:2])[0]
    return padded[2:2 + n].decode(), nonce


# ---- the "confirm" section of vectors/sealed.json (sealed.md §5.2 step 2b, kind 8563) ----------

def key(name): return hashlib.sha256(('belfry sealed vectors/key/' + name).encode()).hexdigest()

def build(d):
    k = dict(d['keys']); k.update(d['follow']['keys'])
    sec = lambda n: k[n]['secret']
    pub = lambda n: k[n]['pubkey']
    T0 = d['fixed']['T0']
    alice = pub('alice')
    base = next(c for c in d['chains'] if c['name'] == 'a device is added')
    root = next(r for r in base['rosters'] if ['n', '1'] in r['tags'])
    r2 = next(r for r in base['rosters'] if ['n', '2'] in r['tags'])
    devs = lambda names: [['device', pub(n)] for n in names]
    r3 = sign_event(sec('alice_phone'), T0 + 300, 8560, [['p', alice], ['n', '3'], ['prev', r2['id']], ['lb', pub('alice_lb1')]] + devs(['alice_phone', 'alice_laptop', 'alice_tablet', 'alice_extra']), '')
    r4 = sign_event(sec('alice_phone'), T0 + 400, 8560, [['p', alice], ['n', '4'], ['prev', r3['id']], ['lb', pub('alice_lb2')]] + devs(['alice_phone', 'alice_tablet', 'alice_extra']), '')
    anchor = sign_event(sec('alice'), T0 + 401, 30078, [['d', 'belfry:sealed'], ['root', root['id']], ['head', r4['id']]], '')
    at = T0 + 500

    def r(signer, added, roster, p=alice, content='', extra=None, rumor_pub=None):
        tags = [['p', p], ['device', pub(added)], ['e', roster]] + (extra or [])
        return rumor(rumor_pub or pub(signer), at, 8563, tags, content)

    def case(name, note, signer, rum, accepted):
        exp = {'accepted': accepted}
        if accepted:
            exp['device'] = next(t[1] for t in rum['tags'] if t[0] == 'device')
            exp['roster'] = next(t[1] for t in rum['tags'] if t[0] == 'e')
        return {'name': name, 'note': note, 'rumor': rum, 'seal_pubkey': pub(signer), 'expected': exp}

    cases = [
        case('the phone confirms the tablet', 'The phone was in roster 2, the one before roster 3 that added the tablet, and is in the head (roster 4). Accepted: the notice for the tablet goes.',
             'alice_phone', r('alice_phone', 'alice_tablet', r3['id']), True),
        case('the added device confirms itself', 'The tablet signs the seal. It is not in the roster before the one that added it.',
             'alice_tablet', r('alice_tablet', 'alice_tablet', r3['id']), False),
        case('a device added in the same roster', 'alice_extra came in with the tablet (roster 3): two devices added together cannot confirm each other.',
             'alice_extra', r('alice_extra', 'alice_tablet', r3['id']), False),
        case('the signer is no longer in the head', 'The laptop was in roster 2, before the tablet came, but roster 4 removed it.',
             'alice_laptop', r('alice_laptop', 'alice_tablet', r3['id']), False),
        case('the signer was never in the chain', "Mallory's phone, sealed to Alice's letterbox.",
             'mallory_phone', r('mallory_phone', 'alice_tablet', r3['id']), False),
        case('a roster that did not add the device: the one before', 'e names roster 2, which added the laptop, not the tablet.',
             'alice_phone', r('alice_phone', 'alice_tablet', r2['id']), False),
        case('a roster that did not add the device: a later one that keeps it', 'e names roster 4: the tablet is in it, and was in roster 3 already.',
             'alice_phone', r('alice_phone', 'alice_tablet', r4['id']), False),
        case('the root as the roster', 'e names the root: there is no roster before it, it adds nobody.',
             'alice_phone', r('alice_phone', 'alice_phone', root['id']), False),
        case('a roster that is not in the chain', "e is an id no roster of the chain has. Not accepted (a client may keep the wrap and judge it again when its chain moves, like any sealed rumor whose chain isn't loaded; it never clears a notice meanwhile).",
             'alice_phone', r('alice_phone', 'alice_tablet', hashlib.sha256(b'belfry sealed vectors/confirm/no such roster').hexdigest()), False),
        case('the wrong device for that roster', 'Roster 3 is named, but the device is the laptop, which roster 2 added.',
             'alice_phone', r('alice_phone', 'alice_laptop', r3['id']), False),
        case('p names another account', 'The rumor is tagged for Bob.',
             'alice_phone', r('alice_phone', 'alice_tablet', r3['id'], p=pub('bob')), False),
        case("the rumor's pubkey is the account, not the device", 'Step 2b: pubkey = the confirming device. Read strictly: a rumor whose pubkey is not the key that signed its seal is ignored.',
             'alice_phone', r('alice_phone', 'alice_tablet', r3['id'], rumor_pub=alice), False),
        case('two device tags', 'A tag given twice makes it invalid (as for a roster, §3.3).',
             'alice_phone', r('alice_phone', 'alice_tablet', r3['id'], extra=[['device', pub('alice_extra')]]), False),
        case('two e tags', 'The same for e.',
             'alice_phone', r('alice_phone', 'alice_tablet', r3['id'], extra=[['e', r2['id']]]), False),
        case('content that is not empty', 'Step 2b: content "". Read strictly.',
             'alice_phone', r('alice_phone', 'alice_tablet', r3['id'], content='it was me'), False),
    ]

    # one real wrap: the 8563 of the first case, sealed (§4) to Alice's own head letterbox by the phone
    wname = 'confirm: the phone confirms the tablet'
    rum = cases[0]['rumor']
    nonce = lambda what: hashlib.sha256(('belfry sealed vectors/nonce/' + what + '/' + wname).encode()).digest()
    once = hashlib.sha256(('belfry sealed vectors/once/' + wname).encode()).hexdigest()
    lb = pub('alice_lb2')
    seal = sign_event(sec('alice_phone'), at + 1, 13, [], nip44_encrypt(sec('alice_phone'), lb, compact(rum), nonce('seal')))
    wrap = sign_event(once, at + 2, 1059, [['p', alice], ['lb', lb]], nip44_encrypt(once, lb, compact(seal), nonce('wrap')))

    # an 8563 inside a shared history (8564) is never taken: not as a message, not as a confirmation
    return {
        'note': "sealed.md §5.2 step 2b, kind 8563: \"It was me\" answered once for all my devices. Added by belfry-android (tools/sealed_confirm_vectors.py, which reproduces this file's other events byte for byte) on 2026-11-22; to be taken into services/onboarding/tools/sealed_vectors.py. The rumor: kind 8563, pubkey = the confirming device (not the account), tags p (the account), device (the added device), e (the id of the roster that added it), content \"\"; sealed like any rumor (§4) to my own head letterbox, the seal signed by that device. A device clears its notice for the added device when the seal's signer (a) is in the roster BEFORE the one named by e, (b) is in the head now, and (c) e names the roster of my chain that added that device (in it, not in the roster before). Each case gives the opened rumor and the key that signed its seal; 'chain' is my chain for every case: root [phone], 2 adds the laptop, 3 adds the tablet and alice_extra together, 4 removes the laptop (new letterbox), anchored on 4. The cases marked 'read strictly' are Android's reading of the shape, for the lead to confirm. An 8563 is never shown as a message and never part of a shared history (8564): history.added_ids never holds one (kinds 14, 7 and 5 only).",
        'account': alice,
        'chain': {'anchor': anchor, 'rosters': [root, r2, r3, r4]},
        'cases': cases,
        'wrap': {
            'name': wname,
            'note': "The first case's rumor in its wrap, to Alice's head letterbox (alice_lb2). The opener computes the rumor's id; its pubkey is the phone, which also signed the seal.",
            'wrap': wrap, 'letterbox': 'alice_lb2', 'letterbox_secret': sec('alice_lb2'),
            'expected': {'seal_pubkey': pub('alice_phone'), 'rumor': rum, 'accepted': True},
        },
    }

if __name__ == '__main__':
    import sys
    path = sys.argv[1] if len(sys.argv) > 1 else 'vectors/sealed.json'
    raw = open(path, encoding='utf-8').read()
    d = json.loads(raw)
    had = d.pop('confirm', None)
    section = build(d)
    if '--check' in sys.argv:
        print('up to date' if had == section else 'DIFFERENT'); sys.exit(0 if had == section else 1)
    d['confirm'] = section
    open(path, 'w', encoding='utf-8').write(json.dumps(d, indent=1, ensure_ascii=False) + '\n')
