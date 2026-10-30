"""Вузол у пакеті ніколи не стартує з порожнім секретом токенів.

Оболонка ПК (src-tauri) не передає JWT_SECRET_KEY, а `.env` у пакеті нема —
до 01.10 вузол піднімався з порожнім ключем підпису. Тепер `_phantom_entry`
створює секрет у теці даних і тримає його між запусками.
"""
from __future__ import annotations

import os
import stat

import pytest


@pytest.fixture
def env(monkeypatch, tmp_path):
    for k in ("JWT_SECRET_KEY", "PHANTOM_DATA_DIR", "PHANTOM_MODELS_DIR", "DATABASE_URL", "CHROMA_PATH"):
        monkeypatch.delenv(k, raising=False)
    monkeypatch.setenv("PHANTOM_DATA_DIR", str(tmp_path / "data"))
    return monkeypatch


def test_порожній_секрет_замінюється_власним_і_живе_між_запусками(env, tmp_path):
    import _phantom_entry

    _phantom_entry._bootstrap_env(tmp_path)
    first = os.environ["JWT_SECRET_KEY"]
    assert len(first) >= 32
    path = tmp_path / "data" / "run" / "jwt.secret"
    assert path.read_text() == first
    if os.name == "posix":
        assert stat.S_IMODE(path.stat().st_mode) == 0o600

    # Другий запуск — той самий секрет, інакше зашифровані дані стали б нечитабельні.
    env.delenv("JWT_SECRET_KEY")
    _phantom_entry._bootstrap_env(tmp_path)
    assert os.environ["JWT_SECRET_KEY"] == first


def test_порожній_рядок_у_env_не_рахується_секретом(env, tmp_path):
    import _phantom_entry

    env.setenv("JWT_SECRET_KEY", "   ")
    _phantom_entry._bootstrap_env(tmp_path)
    assert len(os.environ["JWT_SECRET_KEY"].strip()) >= 32


def test_короткий_секрет_із_env_зупиняє_пакет(env, tmp_path):
    import _phantom_entry

    env.setenv("PHANTOM_PACKAGED", "1")
    env.setenv("JWT_SECRET_KEY", "changeme")
    with pytest.raises(SystemExit):
        _phantom_entry._bootstrap_env(tmp_path)


def test_у_розробці_короткий_тестовий_секрет_дозволено(env, tmp_path):
    import _phantom_entry

    env.delenv("PHANTOM_PACKAGED", raising=False)
    env.setenv("JWT_SECRET_KEY", "test")
    _phantom_entry._bootstrap_env(tmp_path)
    assert os.environ["JWT_SECRET_KEY"] == "test"


def test_довгий_секрет_із_env_має_пріоритет(env, tmp_path):
    import _phantom_entry

    given = "x" * 40
    env.setenv("JWT_SECRET_KEY", given)
    _phantom_entry._bootstrap_env(tmp_path)
    assert os.environ["JWT_SECRET_KEY"] == given
    assert not (tmp_path / "data" / "run" / "jwt.secret").exists()


def test_зіпсований_файл_секрету_перегенеровується(env, tmp_path):
    import _phantom_entry

    path = tmp_path / "data" / "run" / "jwt.secret"
    path.parent.mkdir(parents=True)
    path.write_text("")
    _phantom_entry._bootstrap_env(tmp_path)
    assert len(path.read_text()) >= 32


def test_телеметрію_chromadb_вимкнено_до_імпорту(env, tmp_path):
    import _phantom_entry

    env.delenv("ANONYMIZED_TELEMETRY", raising=False)
    env.delenv("ORT_DISABLE_TELEMETRY", raising=False)
    _phantom_entry._bootstrap_env(tmp_path)
    assert os.environ["ANONYMIZED_TELEMETRY"] == "False"
    # ONNX Runtime шле подію вже на import — змінна мусить стояти до нього.
    assert os.environ["ORT_DISABLE_TELEMETRY"] == "1"
    from chromadb.config import Settings

    assert Settings().anonymized_telemetry is False


def test_onnxruntime_телеметрію_вимикає_вхід(monkeypatch):
    import _phantom_entry
    import onnxruntime

    calls = []
    monkeypatch.setattr(onnxruntime, "disable_telemetry_events", lambda: calls.append(1))
    _phantom_entry._silence_onnxruntime()
    assert calls == [1]
