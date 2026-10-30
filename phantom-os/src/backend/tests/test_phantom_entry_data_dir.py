"""
Вузол у пакеті пише дані туди, куди каже оболонка, а не поруч із бінарником.

Встановлений пакет лежить у теці лише для читання (/usr/lib/…, Program
Files). Запускач `_phantom_entry` раніше ставив базу, памʼять і моделі в
`<тека бінарника>/data` поверх `PHANTOM_DATA_DIR` від оболонки — прогін 26.09
показав Chroma всередині теки застосунку.
"""
from __future__ import annotations

import os

import pytest

KEYS = ("PHANTOM_DATA_DIR", "PHANTOM_MODELS_DIR", "DATABASE_URL", "CHROMA_PATH",
        "PHANTOM_FRONTEND_DIST", "HF_HOME", "HUGGINGFACE_HUB_CACHE",
        "TRANSFORMERS_CACHE", "SENTENCE_TRANSFORMERS_HOME")


@pytest.fixture
def clean_env(monkeypatch):
    for k in KEYS:
        monkeypatch.delenv(k, raising=False)
    return monkeypatch


def test_shell_data_dir_wins(clean_env, tmp_path):
    import _phantom_entry

    app = tmp_path / "usr-lib-phantom"
    app.mkdir()
    app.chmod(0o555)  # як встановлений пакет
    try:
        data = tmp_path / "home" / "PHANTOM"
        clean_env.setenv("PHANTOM_DATA_DIR", str(data))
        _phantom_entry._bootstrap_env(app)
        assert os.environ["PHANTOM_DATA_DIR"] == str(data)
        # база й Chroma — з paths.py усередині даної теки, не поруч із бінарником
        assert "DATABASE_URL" not in os.environ
        assert "CHROMA_PATH" not in os.environ
        assert os.environ["HF_HOME"].startswith(str(data))
        assert not (app / "data").exists() and not (app / "models").exists()
    finally:
        app.chmod(0o755)


def test_portable_folder_keeps_data_beside_binary(clean_env, tmp_path):
    import _phantom_entry

    _phantom_entry._bootstrap_env(tmp_path)
    assert os.environ["PHANTOM_DATA_DIR"] == str(tmp_path / "data")
    assert os.environ["CHROMA_PATH"] == str(tmp_path / "data" / "chroma")
    assert "PHANTOM_FRONTEND_DIST" not in os.environ  # теки frontend нема — не вигадуємо
