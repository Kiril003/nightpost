"""Крипто кадру PHANTOM/1, написане вдруге — і саме тому небезпечне.

Застосунок шифрує через BouncyCastle, перевірка доставки — цим файлом. Це друга
копія одного протоколу, а дві копії розходяться тихо: примітив, що помиляється
на байт, не падає з помилкою, а спокійно «доводить» те, чого насправді немає.
Тому тут немає жодного примітиву без опублікованого вектора, і `_selftest`
падає раніше, ніж перевірка встигне збрехати.

Чистий пітон, нуль залежностей: перевірка, яка потребує `pip install`, не
запуститься на чужій машині саме тоді, коли знадобиться.
"""
import hashlib
import hmac
import os
import struct

# ── X25519, RFC 7748 ─────────────────────────────────────────────────────────
P25519 = 2 ** 255 - 19
A24 = 121665
BASE_U = b"\x09" + b"\x00" * 31


def _clamp_scalar(raw):
    # Молодші три біти геть (кофактор 8), старший виставлений жорстко — драбина
    # має бути однакової довжини для будь-якого ключа.
    k = bytearray(raw)
    k[0] &= 248
    k[31] &= 127
    k[31] |= 64
    return int.from_bytes(k, "little")


def x25519(private_key, public_key):
    if len(private_key) != 32 or len(public_key) != 32:
        raise ValueError("X25519: ключі мають бути рівно по 32 байти")
    k = _clamp_scalar(private_key)
    # Старший біт u відкидається завжди, навіть коли надійшов виставленим:
    # реалізація, що його враховує, дала б інший секрет на тих самих ключах.
    u = int.from_bytes(public_key, "little") & ((1 << 255) - 1)
    x1, x2, z2, x3, z3, swap = u, 1, 0, u, 1, 0
    for t in range(254, -1, -1):
        bit = (k >> t) & 1
        swap ^= bit
        if swap:
            x2, x3 = x3, x2
            z2, z3 = z3, z2
        swap = bit
        a, b = (x2 + z2) % P25519, (x2 - z2) % P25519
        aa, bb = a * a % P25519, b * b % P25519
        e = (aa - bb) % P25519
        c, d = (x3 + z3) % P25519, (x3 - z3) % P25519
        da, cb = d * a % P25519, c * b % P25519
        x3 = (da + cb) ** 2 % P25519
        z3 = x1 * (da - cb) ** 2 % P25519
        x2 = aa * bb % P25519
        z2 = e * (aa + A24 * e) % P25519
    if swap:
        x2, x3 = x3, x2
        z2, z3 = z3, z2
    return (x2 * pow(z2, P25519 - 2, P25519) % P25519).to_bytes(32, "little")


def x25519_base(private_key):
    return x25519(private_key, BASE_U)


def ecdh(private_key, public_key):
    """Те саме, що [x25519], але з відмовою на точці малого порядку.

    Саме так поводиться BouncyCastle на боці застосунку. Без цього два вузли
    розійшлися б рівно там, де це найдорожче: телефон кинув би виняток і
    відмовив, а перевірка тут порахувала б спільний секрет із 32 нулів —
    ключ, який знає кожен, — і оголосила б доставку успішною."""
    shared = x25519(private_key, public_key)
    if shared == bytes(32):
        raise ValueError("X25519: точка малого порядку — спільного секрету немає")
    return shared


# ── AES, FIPS 197 ────────────────────────────────────────────────────────────
def _make_sbox():
    box = [0] * 256
    p = q = 1
    while True:
        p = (p ^ (p << 1) ^ (0x1B if p & 0x80 else 0)) & 0xFF
        q ^= (q << 1) & 0xFF
        q ^= (q << 2) & 0xFF
        q ^= (q << 4) & 0xFF
        if q & 0x80:
            q ^= 0x09
        rot = [((q << s) | (q >> (8 - s))) & 0xFF for s in (1, 2, 3, 4)]
        box[p] = q ^ rot[0] ^ rot[1] ^ rot[2] ^ rot[3] ^ 0x63
        if p == 1:
            break
    box[0] = 0x63
    return box


SBOX = _make_sbox()
# Стан лежить у порядку входу, тож ShiftRows — це перестановка індексів, а не зсув.
SHIFT = [(i % 4) + 4 * (((i // 4) + (i % 4)) % 4) for i in range(16)]


def _xtime(a):
    a <<= 1
    return (a ^ 0x1B) & 0xFF if a & 0x100 else a


def _expand_key(key):
    if len(key) not in (16, 24, 32):
        raise ValueError("AES: ключ має бути 16, 24 або 32 байти")
    nk = len(key) // 4
    rounds = nk + 6
    words = [list(key[i * 4:i * 4 + 4]) for i in range(nk)]
    rcon = 1
    for i in range(nk, 4 * (rounds + 1)):
        t = list(words[i - 1])
        if i % nk == 0:
            t = [SBOX[t[1]] ^ rcon, SBOX[t[2]], SBOX[t[3]], SBOX[t[0]]]
            rcon = _xtime(rcon)
        elif nk > 6 and i % nk == 4:
            t = [SBOX[b] for b in t]
        words.append([words[i - nk][j] ^ t[j] for j in range(4)])
    keys = [sum(words[r * 4:r * 4 + 4], []) for r in range(rounds + 1)]
    return keys, rounds


def _mix_columns(s):
    out = []
    for c in range(4):
        col = s[c * 4:c * 4 + 4]
        t = col[0] ^ col[1] ^ col[2] ^ col[3]
        out += [col[i] ^ t ^ _xtime(col[i] ^ col[(i + 1) % 4]) for i in range(4)]
    return out


def _encrypt_block(keys, rounds, block):
    s = [block[i] ^ keys[0][i] for i in range(16)]
    for r in range(1, rounds + 1):
        s = [SBOX[b] for b in s]
        s = [s[SHIFT[i]] for i in range(16)]
        if r != rounds:
            s = _mix_columns(s)
        s = [s[i] ^ keys[r][i] for i in range(16)]
    return bytes(s)


# ── GCM, NIST SP 800-38D ─────────────────────────────────────────────────────
def _gf_mul(x, y):
    # У GCM порядок бітів обернений до звичного: старший біт числа — це перший
    # біт блоку, тож множення зсуває вправо, а редукція чіпляє 0xE1 згори.
    z, v = 0, y
    for i in range(128):
        if (x >> (127 - i)) & 1:
            z ^= v
        v = (v >> 1) ^ (0xE1 << 120) if v & 1 else v >> 1
    return z


def _pad16(raw):
    return raw + b"\x00" * (-len(raw) % 16)


def _ghash(h, data):
    y = 0
    for i in range(0, len(data), 16):
        y = _gf_mul(y ^ int.from_bytes(data[i:i + 16], "big"), h)
    return y


def _gcm_setup(key, nonce):
    if len(nonce) != 12:
        raise ValueError("AES-GCM: nonce має бути рівно 12 байтів")
    keys, rounds = _expand_key(key)
    h = int.from_bytes(_encrypt_block(keys, rounds, b"\x00" * 16), "big")
    return keys, rounds, h, nonce + b"\x00\x00\x00\x01"


def _inc32(block):
    # Переповнюються лише молодші 32 біти, решта J0 стоїть нерухомо.
    counter = int.from_bytes(block, "big")
    return ((counter & ~0xFFFFFFFF) | ((counter + 1) & 0xFFFFFFFF)).to_bytes(16, "big")


def _gctr(keys, rounds, counter_block, data):
    out = bytearray()
    for i in range(0, len(data), 16):
        stream = _encrypt_block(keys, rounds, counter_block)
        out += bytes(a ^ b for a, b in zip(data[i:i + 16], stream))
        counter_block = _inc32(counter_block)
    return bytes(out)


def _gcm_tag(keys, rounds, h, j0, aad, ciphertext):
    lengths = struct.pack(">QQ", len(aad) * 8, len(ciphertext) * 8)
    s = _ghash(h, _pad16(aad) + _pad16(ciphertext) + lengths)
    mask = _encrypt_block(keys, rounds, j0)
    return bytes(a ^ b for a, b in zip(mask, s.to_bytes(16, "big")))


def aes_gcm_encrypt(key, plaintext, nonce, aad=b""):
    keys, rounds, h, j0 = _gcm_setup(key, nonce)
    # Лічильник даних починається з 2: перше значення витрачене на маску тега.
    ciphertext = _gctr(keys, rounds, _inc32(j0), plaintext)
    # Тег дописується в той самий буфер — так само віддає Java `Cipher.doFinal`.
    return ciphertext + _gcm_tag(keys, rounds, h, j0, aad, ciphertext)


def aes_gcm_decrypt(key, ciphertext, nonce, aad=b""):
    if len(ciphertext) < 16:
        raise ValueError("AES-GCM: буфер коротший за тег")
    body, tag = ciphertext[:-16], ciphertext[-16:]
    keys, rounds, h, j0 = _gcm_setup(key, nonce)
    if not hmac.compare_digest(_gcm_tag(keys, rounds, h, j0, aad, body), tag):
        raise ValueError("AES-GCM: тег не збігається")
    return _gctr(keys, rounds, _inc32(j0), body)


# ── HKDF, RFC 5869 ───────────────────────────────────────────────────────────
def hkdf_sha256(ikm, salt, info, length=32):
    if length > 255 * 32:
        raise ValueError("HKDF-SHA256: більше за 255 блоків не видається")
    prk = hmac.new(salt or b"\x00" * 32, ikm, hashlib.sha256).digest()
    out, block = b"", b""
    counter = 1
    while len(out) < length:
        block = hmac.new(prk, block + info + bytes([counter]), hashlib.sha256).digest()
        out += block
        counter += 1
    return out[:length]


# ── Вектори ──────────────────────────────────────────────────────────────────
def _selftest():
    scalar = bytes.fromhex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
    point = bytes.fromhex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
    assert x25519(scalar, point) == bytes.fromhex(
        "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"
    ), "X25519: перший вектор RFC 7748 §5.2 розійшовся"
    scalar = bytes.fromhex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d")
    point = bytes.fromhex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493")
    assert x25519(scalar, point) == bytes.fromhex(
        "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957"
    ), "X25519: другий вектор RFC 7748 §5.2 розійшовся"

    alice = bytes.fromhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    alice_pub = bytes.fromhex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
    bob = bytes.fromhex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    bob_pub = bytes.fromhex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
    shared = bytes.fromhex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
    assert x25519_base(alice) == alice_pub, "X25519: публічний ключ Аліси розійшовся з RFC 7748 §6.1"
    assert x25519_base(bob) == bob_pub, "X25519: публічний ключ Боба розійшовся з RFC 7748 §6.1"
    assert x25519(alice, bob_pub) == shared, "X25519: секрет Аліси розійшовся з RFC 7748 §6.1"
    assert x25519(bob, alice_pub) == shared, "X25519: секрет Боба розійшовся з RFC 7748 §6.1"
    print("X25519: збігається з RFC 7748 §5.2 і §6.1")

    key = bytes.fromhex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
    nonce = bytes.fromhex("cafebabefacedbaddecaf888")
    plain15 = bytes.fromhex(
        "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72"
        "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255")
    sealed15 = bytes.fromhex(
        "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa"
        "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662898015ad"
        "b094dac5d93471bdec1a502270e3cc6c")
    assert aes_gcm_encrypt(key, plain15, nonce) == sealed15, \
        "AES-256-GCM: шифротекст або тег розійшлися з Test Case 15 (без aad)"
    assert aes_gcm_decrypt(key, sealed15, nonce) == plain15, \
        "AES-256-GCM: розшифрування Test Case 15 не повернуло відкритий текст"

    aad = bytes.fromhex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
    plain16 = plain15[:60]
    sealed16 = bytes.fromhex(
        "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa"
        "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662"
        "76fc6ece0f4e1768cddf8853bb2d551b")
    assert aes_gcm_encrypt(key, plain16, nonce, aad) == sealed16, \
        "AES-256-GCM: шифротекст або тег розійшлися з Test Case 16 (з aad)"
    assert aes_gcm_decrypt(key, sealed16, nonce, aad) == plain16, \
        "AES-256-GCM: розшифрування Test Case 16 не повернуло відкритий текст"
    print("AES-256-GCM: збігається з Test Case 15 і 16, тег дописаний у кінець")

    ikm = bytes.fromhex("0b" * 22)
    okm = hkdf_sha256(ikm, bytes.fromhex("000102030405060708090a0b0c"),
                      bytes.fromhex("f0f1f2f3f4f5f6f7f8f9"), 42)
    assert okm == bytes.fromhex(
        "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
        "34007208d5b887185865"), "HKDF-SHA256: Test Case 1 з RFC 5869 розійшовся"
    okm = hkdf_sha256(ikm, b"", b"", 42)
    assert okm == bytes.fromhex(
        "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d"
        "9d201395faa4b61a96c8"), "HKDF-SHA256: Test Case 3 (порожні salt та info) розійшовся"
    print("HKDF-SHA256: збігається з RFC 5869, Test Case 1 і 3")

    key = os.urandom(32)
    nonce = os.urandom(12)
    aad = b"PHANTOM/1 MSG"
    body = "лист, який має доїхати".encode() * 5
    sealed = aes_gcm_encrypt(key, body, nonce, aad)
    assert aes_gcm_decrypt(key, sealed, nonce, aad) == body, \
        "AES-256-GCM: власний round-trip не повернув вихідний текст"
    broken = bytearray(sealed)
    broken[3] ^= 0x01
    for what, args in (("зіпсований байт шифротексту", (key, bytes(broken), nonce, aad)),
                       ("підмінений aad", (key, sealed, nonce, aad + b"!"))):
        try:
            aes_gcm_decrypt(*args)
        except ValueError:
            continue
        raise AssertionError(f"AES-256-GCM: {what} пройшов перевірку тега")
    print("AES-256-GCM: round-trip тримає, підробка падає з ValueError")


if __name__ == "__main__":
    _selftest()
