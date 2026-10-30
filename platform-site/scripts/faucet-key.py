#!/usr/bin/env python3
"""Новий DEVNET-ключ для SOLANA_FAUCET_KEY: файл у форматі solana-keygen (JSON-масив 64 байтів), режим 600.

    python3 scripts/faucet-key.py [шлях]      типово ~/nightpost-secrets/faucet-devnet.json

Друкує лише адресу гаманця. Існуючий файл не перезаписує, а друкує адресу з нього. Потрібні python3 і openssl.
"""
import json
import os
import subprocess
import sys
from pathlib import Path

ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"


def b58(raw: bytes) -> str:
    n = int.from_bytes(raw, "big")
    out = ""
    while n:
        n, r = divmod(n, 58)
        out = ALPHABET[r] + out
    return "1" * (len(raw) - len(raw.lstrip(b"\0"))) + out


def openssl(*args: str, data: bytes | None = None) -> bytes:
    return subprocess.run(["openssl", *args], input=data, capture_output=True, check=True).stdout


def main() -> None:
    path = Path(sys.argv[1] if len(sys.argv) > 1 else "~/nightpost-secrets/faucet-devnet.json").expanduser()
    if path.exists():
        print(f"address {b58(bytes(json.loads(path.read_text()))[32:])}")
        print(f"key file {path} already exists, kept")
        return
    der = openssl("genpkey", "-algorithm", "ed25519", "-outform", "DER")
    public = openssl("pkey", "-inform", "DER", "-pubout", "-outform", "DER", data=der)[-32:]
    secret = der[-32:] + public
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(json.dumps(list(secret), separators=(",", ":")))
    print(f"address {b58(public)}")
    print(f"key file {path} (mode 600)")


main()
