"""Підпис світових пакетів: те, що спекли ми, і ніщо інше.

До 07.09 і каталог, і манiфест їхали без підпису, а телефон звіряв лише
sha256, оголошений тим САМИМ манiфестом. Дзеркало могло підмінити дорожній
граф разом із самоузгодженим описом — і телефон прийняв би це за своє.

Що доводиться тут:

  1. підписувач кладе `sig`, і власна ж перевірка його приймає;
  2. один змінений байт після підпису вбиває підпис — інакше «підписано»
     означало б лише «поле є»;
  3. ключ НЕ створюється мовчки: без ключа скрипт відмовляється, а не
     генерує якір довіри, якого немає в жодній збірці;
  4. `--check` червоніє на непідписаному дереві — тобто ворота вміють
     почервоніти, а не лише сказати «готово»;
  5. вектор відтворюється з насіння RFC 8032, і той самий файл байт у байт
     лежить у дереві телефона. Формула, переписана з Kotlin у Python і
     перевірена сама на собі, підтверджувала б лише, що вона дорівнює собі.
"""
import json
import sys
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import (
    Encoding,
    NoEncryption,
    PrivateFormat,
)

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

import sign_world_packs as signer  # noqa: E402  — після sys.path
from license_crypto import SigningKey, sign_certificate, verify_certificate  # noqa: E402

#: RFC 8032 §7.1 TEST 1 — той самий детермінований ключ, що й у вектора
#: довідника. Приватна половина тут відома всім НАВМИСНО: це вектор, а не ключ.
SEED = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"

VECTOR_NAME = "world_pack_vector.json"
VECTOR = Path(__file__).resolve().parent / "vectors" / VECTOR_NAME
PHONE_VECTORS = sorted(
    SERVER_DIR.parents[1].glob(f"*/core-net/src/test/resources/{VECTOR_NAME}")
)

CATALOG = {
    "packs": [
        {
            "id": "road-mesh-ua",
            "kind": "ROAD_MESH",
            "country": "UA",
            "labelUa": "Україна — дороги й транспорт",
            "bytes": 41000000,
            "bakedAt": "2026-09-01T00:00:00Z",
            "minAppBuild": 140,
        }
    ]
}
MANIFEST = {
    "id": "road-mesh-ua",
    "version": 3,
    "bytes": 41000000,
    "sha256": "9f2c0f3ac7b8e51d4a6b90c2e7d3f815a0b4c6d8e9f0a1b2c3d4e5f60718293a",
    "minAppBuild": 140,
}


@pytest.fixture()
def key(tmp_path) -> SigningKey:
    """Ключ кладеться файлом, як його покладе власник, а не робиться скриптом."""
    private = Ed25519PrivateKey.from_private_bytes(bytes.fromhex(SEED))
    path = tmp_path / "packs.pem"
    path.write_bytes(private.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption()))
    return SigningKey.load(path)


@pytest.fixture()
def baked(tmp_path) -> Path:
    root = tmp_path / "baked"
    (root / "road-mesh-ua").mkdir(parents=True)
    (root / "world-catalog.json").write_text(json.dumps(CATALOG, ensure_ascii=False), "utf-8")
    (root / "road-mesh-ua" / "manifest.json").write_text(json.dumps(MANIFEST), "utf-8")
    return root


def test_підписувач_знаходить_каталог_і_кожен_манiфест(baked):
    found = [p.relative_to(baked).as_posix() for p in signer.documents(baked)]
    assert found == ["world-catalog.json", "road-mesh-ua/manifest.json"]


def test_підписане_проходить_власну_ж_перевірку(baked, key):
    for path in signer.documents(baked):
        assert signer.sign_file(path, key, resign=False) == "підписано"
        ok, verdict = signer.check_file(path, key.public_hex)
        assert ok, verdict


def test_один_змінений_байт_після_підпису_вбиває_підпис(baked, key):
    path = baked / "road-mesh-ua" / "manifest.json"
    signer.sign_file(path, key, resign=False)
    document = json.loads(path.read_text("utf-8"))
    document["bytes"] = 410000
    path.write_text(json.dumps(document, ensure_ascii=False), "utf-8")

    ok, verdict = signer.check_file(path, key.public_hex)
    assert not ok, verdict


def test_чужий_ключ_не_приймається(baked, key):
    stranger = Ed25519PrivateKey.generate()
    path = baked / "world-catalog.json"
    path.write_text(json.dumps(sign_certificate(CATALOG, stranger), ensure_ascii=False), "utf-8")

    ok, _ = signer.check_file(path, key.public_hex)
    assert not ok


def test_check_червоніє_на_непідписаному_і_зеленіє_після_підпису(baked, key, capsys, monkeypatch, tmp_path):
    """Ворота, які не вміють почервоніти, — не ворота."""
    pem = str(tmp_path / "packs.pem")
    monkeypatch.setattr(sys, "argv", ["sign", str(baked), "--key", pem, "--check"])
    assert signer.main() == 1
    assert "БЕЗ ПІДПИСУ" in capsys.readouterr().out

    monkeypatch.setattr(sys, "argv", ["sign", str(baked), "--key", pem])
    assert signer.main() == 0
    assert key.public_hex in capsys.readouterr().out

    monkeypatch.setattr(sys, "argv", ["sign", str(baked), "--key", pem, "--check"])
    assert signer.main() == 0


def test_без_ключа_скрипт_відмовляється_а_не_вигадує_свій(baked, capsys, monkeypatch, tmp_path):
    missing = tmp_path / "немає.pem"
    monkeypatch.setattr(sys, "argv", ["sign_world_packs.py", str(baked), "--key", str(missing)])

    assert signer.main() == 66
    assert "немає ключа" in capsys.readouterr().out
    assert not missing.exists(), "скрипт створив якір довіри сам — саме те, чого не можна"


def test_вектор_відтворюється_з_насіння(key):
    """Ed25519 детермінований, тож вектор — не знімок, а обчислення."""
    vector = json.loads(VECTOR.read_text("utf-8"))
    assert vector["ed25519"]["pub_hex"] == key.public_hex

    for field, document in (("world_catalog_json", CATALOG), ("manifest_json", MANIFEST)):
        signed = sign_certificate(document, key.private)
        text = json.dumps(signed, ensure_ascii=False, sort_keys=True, indent=2) + "\n"
        assert vector[field] == text
        assert verify_certificate(json.loads(vector[field]), key.public_hex)


def test_вектор_лежить_у_дереві_телефона_байт_у_байт():
    if not PHONE_VECTORS:
        pytest.skip(
            f"поруч немає жодного дерева телефона з {VECTOR_NAME}: "
            "крос-мовність цим запуском не доведено"
        )
    for copy in PHONE_VECTORS:
        assert copy.read_text(encoding="utf-8") == VECTOR.read_text(encoding="utf-8"), copy
