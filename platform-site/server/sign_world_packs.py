"""Підписати спечені світові пакети — основу мапи й дорожній граф.

    python sign_world_packs.py --key keys/packs.pem /шлях/до/спеченого
    python sign_world_packs.py --key keys/packs.pem /шлях/до/спеченого --check

Дерево, яке чекає телефон:

    <корінь>/world-catalog.json      ← перелік, який видно ДО завантаження
    <корінь>/<id>/manifest.json      ← те, чому телефон вірить перед качанням

До 07.09 обидва файли їхали БЕЗ підпису, а телефон звіряв лише sha256, узятий
із того самого манiфеста. Манiфест, який сам собі оголошує суму власного
файла, доводить рівно одне: що дзеркало вміє рахувати sha256. Поки роздача йде
з нашої машини, ціна цього мала; дзеркала на чужих машинах без підпису
неможливі.

Канонізація тут не своя: `license_crypto.canonical_payload` — той самий
sort_keys + separators, який на телефоні читає
`core-license/SignedDocument.canonicalBytes`. Дві незалежні реалізації
розійшлися б мовчки, і першою про це дізналась би людина з мертвим каталогом.

КЛЮЧ ЦЕЙ СКРИПТ НЕ СТВОРЮЄ. Ключ пакетів — окрема пара від ліцензійної (складач
пакетів не має ставати видавцем ліцензій), і робить її власник своєю рукою:

    openssl genpkey -algorithm ed25519 -out keys/packs.pem && chmod 600 keys/packs.pem

Публічну половину скрипт друкує — саме її кладуть у `local.properties`
телефона як `phantom.packKey` (64 шістнадцяткові символи, нижній регістр).
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from license_crypto import (  # noqa: E402  — після sys.path
    SigningKey,
    canonical_payload,
    sign_certificate,
    verify_certificate,
)

CATALOG = "world-catalog.json"
MANIFEST = "manifest.json"


def documents(root: Path) -> list[Path]:
    """Каталог і кожен манiфест пакета. Порядок сталий — щоб вивід читався."""
    found = []
    if (root / CATALOG).is_file():
        found.append(root / CATALOG)
    found.extend(sorted(root.glob(f"*/{MANIFEST}")))
    return found


def sign_file(path: Path, key: SigningKey, *, resign: bool) -> str:
    document = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        return "не JSON-об'єкт — підписувати нічого"
    if "sig" in document and not resign:
        if verify_certificate(document, key.public_hex):
            return "уже підписано цим ключем"
        return "уже підписано ЧУЖИМ ключем — потрібен --resign, якщо це навмисно"
    signed = sign_certificate({k: v for k, v in document.items() if k != "sig"}, key.private)
    path.write_text(
        json.dumps(signed, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )
    return "підписано"


def check_file(path: Path, public_hex: str) -> tuple[bool, str]:
    document = json.loads(path.read_text(encoding="utf-8"))
    if "sig" not in document:
        return False, "БЕЗ ПІДПИСУ — телефон відмовить"
    if not verify_certificate(document, public_hex):
        return False, "ПІДПИС НЕ СХОДИТЬСЯ з цим ключем"
    return True, "підпис сходиться"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, help="каталог зі спеченими пакетами")
    parser.add_argument("--key", type=Path, required=True, help="приватний ключ Ed25519 (PEM)")
    parser.add_argument("--check", action="store_true", help="лише звірити, нічого не писати")
    parser.add_argument("--resign", action="store_true", help="перепідписати вже підписане")
    args = parser.parse_args()

    if not args.root.is_dir():
        print(f"немає каталогу: {args.root}")
        return 66
    if not args.key.is_file():
        # Свій ключ тут не робиться НІКОЛИ: мовчки згенерований якір довіри —
        # це якір, якого немає в жодній збірці, і про це дізнаються пізно.
        print(f"немає ключа: {args.key}\n\nЗробити його — рукою власника:\n")
        print("    openssl genpkey -algorithm ed25519 -out", args.key, "&& chmod 600", args.key)
        return 66

    key = SigningKey.load(args.key)
    found = documents(args.root)
    if not found:
        print(f"у {args.root} немає ні {CATALOG}, ні жодного */{MANIFEST}")
        return 65

    print(f"публічний ключ (phantom.packKey): {key.public_hex}\n")
    bad = 0
    for path in found:
        if args.check:
            ok, verdict = check_file(path, key.public_hex)
            bad += not ok
        else:
            verdict = sign_file(path, key, resign=args.resign)
        print(f"  {path.relative_to(args.root)}: {verdict}")
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())


__all__ = ["canonical_payload", "documents", "sign_file", "check_file", "main"]
