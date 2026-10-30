"""Offline license verification. The device trusts ONLY the embedded/env
public key — never a key delivered alongside the certificate."""
from __future__ import annotations

import base64
import json
import os
from dataclasses import dataclass
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

from licensing.fingerprint import device_fingerprint

#: Production licence-signing identity, as published by the licence server
#: (GET https://phantom-license.fly.dev/healthz → "public_key").
PRODUCTION_PUBLIC_KEY_HEX = "960032eb2835e1b17eb590930e147a994d3d3bd19827b305a638bd64ddc2c198"

#: Dev/staging signing identity. Trusted ONLY with PHANTOM_LICENSE_DEV=1 and
#: never in a packaged build — otherwise a dev-signed certificate would
#: unlock a shipped app.
DEV_PUBLIC_KEY_HEX = "c55c57a15eb8647efdcf3ae6d3dde6b7c3ec713ed3b1af6492b6234e1b0b409f"


def _packaged() -> bool:
    return os.environ.get("PHANTOM_PACKAGED") == "1"


def trusted_public_key_hex() -> str:
    """The one key licence certificates are checked against.

    Packaged builds: always the pinned production key — no environment
    variable can swap it. Source/dev runs: ``PHANTOM_LICENSE_PUBKEY`` (an
    explicit key, e.g. a local test server) or ``PHANTOM_LICENSE_DEV=1``
    (the dev key); otherwise production as well.
    """
    if _packaged():
        return PRODUCTION_PUBLIC_KEY_HEX
    explicit = os.environ.get("PHANTOM_LICENSE_PUBKEY", "").strip()
    if explicit:
        return explicit
    if os.environ.get("PHANTOM_LICENSE_DEV") == "1":
        return DEV_PUBLIC_KEY_HEX
    return PRODUCTION_PUBLIC_KEY_HEX


def license_file_path() -> Path:
    return Path(os.environ.get("PHANTOM_LICENSE_FILE", "~/.phantom/license.json")).expanduser()


@dataclass
class LicenseStatus:
    valid: bool
    reason: str
    tier: str | None = None
    license_id: str | None = None
    serial: str | None = None
    updates_until: str | None = None
    fingerprint: str | None = None

    def as_dict(self) -> dict:
        return {
            "valid": self.valid,
            "reason": self.reason,
            "tier": self.tier,
            "license_id": self.license_id,
            "serial": self.serial,
            "updates_until": self.updates_until,
            "fingerprint": self.fingerprint,
        }


def verify_certificate(cert: dict, public_key_hex: str | None = None) -> bool:
    key_hex = public_key_hex or trusted_public_key_hex()
    try:
        unsigned = {k: v for k, v in cert.items() if k != "sig"}
        payload = json.dumps(unsigned, sort_keys=True, separators=(",", ":")).encode()
        public = Ed25519PublicKey.from_public_bytes(bytes.fromhex(key_hex))
        public.verify(base64.b64decode(cert["sig"]), payload)
        return True
    except Exception:
        return False


def license_status() -> LicenseStatus:
    fp = device_fingerprint()
    path = license_file_path()
    if not path.exists():
        return LicenseStatus(valid=False, reason="not_activated", fingerprint=fp)
    try:
        cert = json.loads(path.read_text())["certificate"]
    except (OSError, KeyError, json.JSONDecodeError):
        return LicenseStatus(valid=False, reason="corrupt_license_file", fingerprint=fp)
    if not verify_certificate(cert):
        return LicenseStatus(valid=False, reason="bad_signature", fingerprint=fp)
    if cert.get("device_fingerprint") != fp:
        return LicenseStatus(valid=False, reason="device_mismatch", fingerprint=fp)
    return LicenseStatus(
        valid=True,
        reason="ok",
        tier=cert.get("tier"),
        license_id=cert.get("license_id"),
        serial=cert.get("serial"),
        updates_until=cert.get("updates_until"),
        fingerprint=fp,
    )


def store_certificate(cert: dict) -> None:
    path = license_file_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"certificate": cert}, indent=2))
    path.chmod(0o600)


def remove_certificate() -> None:
    path = license_file_path()
    if path.exists():
        path.unlink()
