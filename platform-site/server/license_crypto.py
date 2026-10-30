"""Ed25519 license certificates + license key generation.

Key format: PHTM-XXXXX-XXXXX-XXXXX-XXXXX (Crockford base32, last group carries
a checksum so typos fail fast client-side). Keys are stored ONLY as SHA-256.
"""
from __future__ import annotations

import base64
import hashlib
import json
import secrets
from dataclasses import dataclass
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
)
from cryptography.hazmat.primitives.serialization import (
    Encoding,
    NoEncryption,
    PrivateFormat,
    load_pem_private_key,
)

CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def _checksum(body: str) -> str:
    digest = hashlib.sha256(body.encode()).digest()
    return "".join(CROCKFORD[b % 32] for b in digest[:5])


def generate_license_key() -> str:
    body = "".join(secrets.choice(CROCKFORD) for _ in range(15))
    groups = [body[i : i + 5] for i in range(0, 15, 5)]
    return "PHTM-" + "-".join(groups) + "-" + _checksum(body)


def normalize_key(raw: str) -> str:
    return raw.strip().upper().replace(" ", "")


def key_is_well_formed(raw: str) -> bool:
    key = normalize_key(raw)
    parts = key.split("-")
    if len(parts) != 5 or parts[0] != "PHTM":
        return False
    body = "".join(parts[1:4])
    if len(body) != 15 or any(c not in CROCKFORD for c in body):
        return False
    return parts[4] == _checksum(body)


def key_hash(raw: str) -> str:
    return hashlib.sha256(normalize_key(raw).encode()).hexdigest()


def canonical_payload(cert: dict) -> bytes:
    unsigned = {k: v for k, v in cert.items() if k != "sig"}
    return json.dumps(unsigned, sort_keys=True, separators=(",", ":")).encode()


def sign_certificate(cert: dict, private_key: Ed25519PrivateKey) -> dict:
    signature = private_key.sign(canonical_payload(cert))
    return {**cert, "sig": base64.b64encode(signature).decode()}


def verify_certificate(cert: dict, public_key_hex: str) -> bool:
    try:
        public = Ed25519PublicKey.from_public_bytes(bytes.fromhex(public_key_hex))
        public.verify(base64.b64decode(cert["sig"]), canonical_payload(cert))
        return True
    except Exception:
        return False


@dataclass
class SigningKey:
    private: Ed25519PrivateKey
    public_hex: str

    @classmethod
    def load(cls, path: Path) -> "SigningKey":
        private = load_pem_private_key(path.read_bytes(), password=None)
        if not isinstance(private, Ed25519PrivateKey):
            raise ValueError("signing key must be Ed25519")
        raw = private.public_key().public_bytes_raw()
        return cls(private=private, public_hex=raw.hex())

    @classmethod
    def generate(cls, path: Path) -> "SigningKey":
        private = Ed25519PrivateKey.generate()
        pem = private.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption())
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(pem)
        path.chmod(0o600)
        raw = private.public_key().public_bytes_raw()
        return cls(private=private, public_hex=raw.hex())
