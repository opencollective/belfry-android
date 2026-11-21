#!/usr/bin/env python3
"""Builds vectors/dm-files.json: files, pictures and recordings in direct messages (the lead's
contract, 2026-11-21). Deterministic: fixed keys, nonces and clocks. Run: python3 -I tools/vectors/dm_files.py"""
import hashlib, json, os, sys
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ME = "a2aaecacffe6376d9826d889e5e2985381edab77924eeec51b8e2c25373a5d27"
ANA = "959c24202b2b46602cf8614c4d930f571518551fbf121930b371a2ca321beddd"
BEN = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
BLOSSOM = "https://blossom.example.org/"
UNREADABLE = "This file can't be opened"


def sha(b): return hashlib.sha256(b).hexdigest()


def seal(plain, key_hex, nonce_hex):
    """AES-256-GCM, no associated data; the 16-byte tag appended to the ciphertext."""
    return AESGCM(bytes.fromhex(key_hex)).encrypt(bytes.fromhex(nonce_hex), plain, None)


def rumor_id(r):
    data = json.dumps([0, r["pubkey"], r["created_at"], r["kind"], r["tags"], r["content"]], separators=(",", ":"), ensure_ascii=False)
    return sha(data.encode())


def key(n): return hashlib.sha256(b"dm-files key %d" % n).hexdigest()  # 32 bytes
def nonce(n): return hashlib.sha256(b"dm-files nonce %d" % n).hexdigest()[:24]  # 12 bytes


def plain(n, size):
    out = b""
    i = 0
    while len(out) < size:
        out += hashlib.sha256(b"dm-files plain %d %d" % (n, i)).digest()
        i += 1
    return out[:size]


def file_rumor(n, *, mime, data, others, at, name=None, dim=None, blurhash=None, thumb=None, duration=None, group=None):
    k, iv = key(n), nonce(n)
    ct = seal(data, k, iv)
    url = BLOSSOM + sha(ct)
    tags = [["p", p] for p in others]
    tags += [["file-type", mime], ["encryption-algorithm", "aes-gcm"], ["decryption-key", k], ["decryption-nonce", iv],
             ["x", sha(ct)], ["ox", sha(data)], ["size", str(len(data))]]
    if name: tags.append(["name", name])
    thumb_case = None
    if dim: tags.append(["dim", dim])
    if blurhash: tags.append(["blurhash", blurhash])
    if thumb is not None:
        tiv = nonce(100 + n)
        tct = seal(thumb, k, tiv)
        turl = BLOSSOM + sha(tct)
        tags += [["thumb", turl], ["thumb-nonce", tiv]]
        thumb_case = {"url": turl, "nonce": tiv, "plain_hex": thumb.hex(), "ciphertext_hex": tct.hex()}
    if duration is not None: tags.append(["duration", str(duration)])
    if group: tags.append(["group", group])
    r = {"kind": 15, "pubkey": ME, "created_at": at, "content": url, "tags": tags}
    r["id"] = rumor_id(r)
    case = {"plain_hex": data.hex(), "ciphertext_hex": ct.hex(), "rumor": r}
    if thumb_case: case["thumb"] = thumb_case
    return case


def main():
    out = {
        "about": (
            "Files, pictures and audio recordings in direct messages (the lead's contract, 2026-11-21; NIP-17 kind 15). "
            "Each file = one kind 15 rumor, sealed and gift-wrapped like a kind 14 to every participant and to yourself "
            "(in a sealed conversation through the device seal, as for 14). Before upload the device encrypts the file with "
            "AES-256-GCM, a fresh random 32-byte key and 12-byte nonce per file, no associated data, the 16-byte tag appended "
            "to the ciphertext; it uploads the CIPHERTEXT to the belfry's Blossom (Content-Type application/octet-stream; same "
            "limits as channels). Rumor content = the blob URL. Tags in this order: p per other participant; [\"file-type\", mime]; "
            "[\"encryption-algorithm\", \"aes-gcm\"]; [\"decryption-key\", hex]; [\"decryption-nonce\", hex]; [\"x\", sha256 of the "
            "ciphertext]; [\"ox\", sha256 of the plain file]; [\"size\", bytes of the plain file]; then optional [\"name\", file name] "
            "(sent for files; inside the sealed rumor, so the server never sees it; shown as in the names section: the last path "
            "segment, no control characters or direction marks, trimmed, at most 200 UTF-16 units, else File / Picture / Audio recording), [\"dim\", \"WxH\"], "
            "[\"blurhash\", ...], [\"thumb\", url] + [\"thumb-nonce\", hex] (the thumbnail encrypted with the file's key and its own "
            "nonce), [\"duration\", seconds] (audio: m4a/AAC as in channels), [\"group\", 16 hex]. Several pictures sent together: "
            "kind 15s in order sharing one group tag, created_at strictly increasing (the outbox rule max(last + 1, now)); a caption "
            "= a kind 14 right after with the same group tag. Readers show a group (same author, same group) as one bubble: files by "
            "created_at (ties: rumor id), the caption under them. Reactions on a file: dm-reactions.json with [\"k\", \"15\"]. "
            "Deleting: as for 14. Readers: download, check x, decrypt, check ox; any mismatch or a missing / malformed tag shows "
            "\"This file can't be opened\", never the bytes. Decrypted files are cached only in the app's private storage. Sending "
            "goes through the outbox like a direct message: the upload is a step of the item (in the send trace); a refused upload "
            "(4xx except 401 / 408 / 429) fails the item (outbox.json)."
        ),
        "generated_by": "tools/vectors/dm_files.py (belfry-android)",
        "people": {"me": ME, "ana": ANA, "ben": BEN},
        "unreadable": UNREADABLE,
    }

    # 1. the cipher alone
    crypto = []
    for n, size, note in [(1, 0, "an empty file"), (2, 31, "shorter than a block"), (3, 1000, "several blocks")]:
        p = plain(n, size)
        ct = seal(p, key(n), nonce(n))
        crypto.append({"note": note, "key": key(n), "nonce": nonce(n), "plain_hex": p.hex(), "ciphertext_hex": ct.hex(), "x": sha(ct), "ox": sha(p)})
    out["crypto"] = crypto

    # 2. what a sender makes
    at = 1_800_000_000
    picture = file_rumor(10, mime="image/jpeg", data=plain(10, 600), others=[ANA], at=at, dim="1200x800",
                         blurhash="LEHV6nWB2yk8pyo0adR*.7kCMdnj", thumb=plain(11, 120))
    pdf = file_rumor(12, mime="application/pdf", data=plain(12, 900), others=[ANA, BEN], at=at + 10, name="Garden plan 2027.pdf")
    audio = file_rumor(13, mime="audio/mp4", data=plain(13, 700), others=[ANA], at=at + 20, duration=42)
    group = "5f0c3a9e1b7d2c48"
    g1 = file_rumor(14, mime="image/png", data=plain(14, 300), others=[ANA], at=at + 30, dim="640x480", group=group)
    g2 = file_rumor(15, mime="image/png", data=plain(15, 310), others=[ANA], at=at + 31, dim="480x640", group=group)
    caption = {"kind": 14, "pubkey": ME, "created_at": at + 32, "content": "From Saturday's market", "tags": [["p", ANA], ["group", group]]}
    caption["id"] = rumor_id(caption)
    out["send"] = {
        "picture": {**picture, "wrapped_to": [ANA, ME]},
        "file": {**pdf, "wrapped_to": [ANA, BEN, ME]},
        "audio": {**audio, "wrapped_to": [ANA, ME]},
        "group": {"files": [g1, g2], "caption": caption, "wrapped_to": [ANA, ME]},
        "reaction": {
            "note": "a reaction to the picture: as dm-reactions.json, k = 15",
            "rumor": {"kind": 7, "pubkey": ANA, "content": "❤️", "tags": [["e", picture["rumor"]["id"], "", ME], ["p", ME], ["k", "15"]]},
        },
    }

    # 3. what a reader makes of it
    base = picture["rumor"]
    ct = bytes.fromhex(picture["ciphertext_hex"])

    def with_tag(name, value):
        return [[t[0], value] if t[0] == name else t for t in base["tags"]]

    def without(name):
        return [t for t in base["tags"] if t[0] != name]

    flipped = bytearray(ct); flipped[5] ^= 1
    reads = [
        {"note": "as sent", "tags": base["tags"], "downloaded_hex": ct.hex(), "expect": {"ok": True, "plain_hex": picture["plain_hex"], "mime": "image/jpeg"}},
        {"note": "a changed byte: x does not match", "tags": base["tags"], "downloaded_hex": bytes(flipped).hex(), "expect": {"ok": False}},
        {"note": "x matches but the key is wrong: GCM's tag fails", "tags": with_tag("decryption-key", key(99)), "downloaded_hex": ct.hex(), "expect": {"ok": False}},
        {"note": "decrypts, but ox is another file's", "tags": with_tag("ox", sha(b"another file")), "downloaded_hex": ct.hex(), "expect": {"ok": False}},
        {"note": "a nonce of 16 bytes", "tags": with_tag("decryption-nonce", nonce(10) + "00000000"), "downloaded_hex": ct.hex(), "expect": {"ok": False}},
        {"note": "a key of 16 bytes", "tags": with_tag("decryption-key", key(10)[:32]), "downloaded_hex": ct.hex(), "expect": {"ok": False}},
        {"note": "another algorithm", "tags": with_tag("encryption-algorithm", "aes-cbc"), "downloaded_hex": ct.hex(), "expect": {"ok": False}},
        {"note": "no decryption-key", "tags": without("decryption-key"), "downloaded_hex": ct.hex(), "expect": {"ok": False}},
        {"note": "no x", "tags": without("x"), "downloaded_hex": ct.hex(), "expect": {"ok": False}},
        {"note": "no ox", "tags": without("ox"), "downloaded_hex": ct.hex(), "expect": {"ok": False}},
        {"note": "a truncated download", "tags": base["tags"], "downloaded_hex": ct[:-1].hex(), "expect": {"ok": False}},
        {"note": "the thumbnail: the file's key, its own nonce", "tags": base["tags"], "thumb": True,
         "downloaded_hex": picture["thumb"]["ciphertext_hex"], "expect": {"ok": True, "plain_hex": picture["thumb"]["plain_hex"]}},
    ]
    out["read"] = reads

    # 4. bubbles: a group shows as one
    other_group = "0123456789abcdef"
    h1 = file_rumor(16, mime="image/png", data=plain(16, 50), others=[ME], at=at + 40, group=other_group)
    h1["rumor"]["pubkey"] = ANA; h1["rumor"]["tags"][0] = ["p", ME]; h1["rumor"]["id"] = rumor_id({k: v for k, v in h1["rumor"].items() if k != "id"})
    plain14 = {"kind": 14, "pubkey": ANA, "created_at": at + 25, "content": "Nice", "tags": [["p", ME]]}
    plain14["id"] = rumor_id(plain14)
    timeline = [g2["rumor"], caption, audio["rumor"], g1["rumor"], plain14, h1["rumor"], pdf["rumor"], picture["rumor"]]
    out["bubbles"] = {
        "note": "rumors as they arrived (any order) → the bubbles in time order (a bubble's time = its first rumor's); "
                "same group from another author is another bubble",
        "rumors": [{"id": r["id"], "kind": r["kind"], "pubkey": r["pubkey"], "created_at": r["created_at"],
                    "group": next((t[1] for t in r["tags"] if t[0] == "group"), None)} for r in timeline],
        "expect": [
            {"files": [picture["rumor"]["id"]], "caption": None},
            {"files": [pdf["rumor"]["id"]], "caption": None},
            {"files": [audio["rumor"]["id"]], "caption": None},
            {"files": [], "caption": plain14["id"]},
            {"files": [g1["rumor"]["id"], g2["rumor"]["id"]], "caption": caption["id"]},
            {"files": [h1["rumor"]["id"]], "caption": None},
        ],
    }

    # 5. the name shown (the lead, 2026-11-21): the last path segment (after / or \\), without control
    # characters (Unicode Cc) and direction marks (U+200E, U+200F, U+202A-202E, U+2066-2069), trimmed, at most 200
    # characters (UTF-16 units, never splitting a pair); none or empty: "File", a picture "Picture", a recording "Audio recording"
    out["names"] = [
        {"name": "Garden plan 2027.pdf", "mime": "application/pdf", "shown": "Garden plan 2027.pdf"},
        {"name": "../../etc/passwd", "mime": "text/plain", "shown": "passwd"},
        {"name": "C:\\Users\\ana\\Desktop\\notes.txt", "mime": "text/plain", "shown": "notes.txt"},
        {"name": "bad\u0000na\u0007me\n.pdf", "mime": "application/pdf", "shown": "badname.pdf"},
        {"name": "a" * 250 + ".pdf", "mime": "application/pdf", "shown": "a" * 200},
        {"name": "", "mime": "application/pdf", "shown": "File"},
        {"name": None, "mime": "application/zip", "shown": "File"},
        {"name": None, "mime": "image/jpeg", "shown": "Picture"},
        {"name": None, "mime": "audio/mp4", "shown": "Audio recording"},
        {"name": "dir/", "mime": "video/mp4", "shown": "File"},
        {"name": "photo\u202egnp.exe", "mime": "application/octet-stream", "shown": "photognp.exe"},
        {"name": "\u202e\u0001", "mime": "image/png", "shown": "Picture"},
        {"name": "  spaced  .txt  ", "mime": "text/plain", "shown": "spaced  .txt"},
    ]

    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "vectors", "dm-files.json")
    with open(path, "w") as f:
        json.dump(out, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print("wrote", os.path.normpath(path), os.path.getsize(path), "bytes")


if __name__ == "__main__":
    main()
