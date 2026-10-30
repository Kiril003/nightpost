"""
PHANTOM OS — Nuitka onefile entrypoint.

Compiled by ``scripts/build/nuitka_build.py`` into a single executable.
``main.py`` remains the dev/test entrypoint; this module exists solely to
sequence multiprocessing-freeze, env defaults, and ``sys.path`` priming
**before** any backend module loads. Order matters here — moving any block
will break Nuitka onefile on Windows or the data-path resolver on Linux.

Layout next to the produced binary (created at first launch):

    phantom-os(.exe)
    models/        — downloaded by ``download-models.py`` or first run
    data/          — sqlite + chroma runtime data
    frontend/      — vite build (if shipped alongside)
"""
from __future__ import annotations

import multiprocessing as _mp
import os
import sys
from pathlib import Path


def _binary_dir() -> Path:
    # Nuitka onefile sets ``sys.argv[0]`` to the user-facing binary while
    # ``__file__`` points into the (temporary) extracted bundle. We want the
    # *outer* directory the user sees, so prefer ``sys.argv[0]`` and fall
    # back to ``sys.executable`` for safety on edge cases (symlinks, sudo).
    candidates = [Path(sys.argv[0]).resolve(), Path(sys.executable).resolve()]
    for cand in candidates:
        if cand.is_file():
            return cand.parent
    return Path.cwd()


#: Найкоротший секрет, який приймаємо з env. Коротший — це вписане руками
#: «test»/«changeme», яким підписані токени підробляються перебором.
_MIN_SECRET = 32


def _ensure_jwt_secret(data_dir: Path) -> None:
    """Секрет підпису токенів і ключ шифрування PII (HKDF від нього).

    Оболонка ПК його не передає, а `.env` поруч із пакетом немає — вузол
    стартував із ПОРОЖНІМ `JWT_SECRET_KEY`: токени підписувались порожнім
    ключем, а шифрування PII падало на першому записі. Тепер секрет
    народжується при першому запуску й живе в теці даних користувача
    (`run/jwt.secret`, 0600), тож переживає оновлення застосунку, і вже
    зашифровані дані лишаються читабельними.

    Явно заданий у env секрет має пріоритет, але порожній чи коротший за
    32 знаки не допускається: краще не стартувати, ніж стартувати з ключем,
    який знає кожен.
    """
    given = os.environ.get("JWT_SECRET_KEY", "")
    if given.strip():
        # Розробка й тести живуть із JWT_SECRET_KEY=test (CLAUDE.md); заслін —
        # для пакета, який оболонка запускає з PHANTOM_PACKAGED=1.
        if len(given.strip()) < _MIN_SECRET and os.environ.get("PHANTOM_PACKAGED") == "1":
            raise SystemExit(
                f"JWT_SECRET_KEY коротший за {_MIN_SECRET} знаків — вузол не стартує з ключем, "
                "який можна вгадати. Приберіть змінну: тоді вузол створить власний секрет."
            )
        return
    import secrets

    path = data_dir / "run" / "jwt.secret"
    secret = path.read_text(encoding="utf-8").strip() if path.is_file() else ""
    if len(secret) < _MIN_SECRET:
        path.parent.mkdir(parents=True, exist_ok=True)
        secret = secrets.token_urlsafe(48)
        # Створюємо одразу з правами 0600 — не «записати, потім chmod», бо між
        # двома кроками файл був би видимий іншим користувачам машини.
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(secret)
    os.environ["JWT_SECRET_KEY"] = secret


def _bootstrap_env(root: Path | None = None) -> Path:
    root = root or _binary_dir()
    given = os.environ.get("PHANTOM_DATA_DIR", "").strip()
    if given:
        # Оболонка ПК (src-tauri/src/main.rs) дає теку даних користувача.
        # Встановлений пакет лежить у теці лише для читання (/usr/lib/…,
        # Program Files) — писати базу чи пам'ять поруч із бінарником там
        # не можна, тож усе живе в даній теці, а шляхи всередині неї
        # розкладає paths.py, як і в розробці.
        data_dir = Path(given).expanduser()
        models_dir = Path(os.environ.get("PHANTOM_MODELS_DIR") or data_dir / "models")
    else:
        # Портативна тека без оболонки: дані поруч із бінарником.
        data_dir = root / "data"
        models_dir = root / "models"
        os.environ["PHANTOM_DATA_DIR"] = str(data_dir)
        os.environ.setdefault(
            "DATABASE_URL",
            f"sqlite+aiosqlite:///{(data_dir / 'phantom.db').as_posix()}",
        )
        os.environ.setdefault("CHROMA_PATH", str(data_dir / "chroma"))
        frontend_dir = root / "frontend"
        if frontend_dir.is_dir():
            os.environ.setdefault("PHANTOM_FRONTEND_DIST", str(frontend_dir))
    data_dir.mkdir(parents=True, exist_ok=True)
    models_dir.mkdir(parents=True, exist_ok=True)
    _ensure_jwt_secret(data_dir)
    os.environ.setdefault("PHANTOM_MODELS_DIR", str(models_dir))

    # Hugging Face / transformers caches — keep them inside our models dir
    # so the portable folder is fully self-contained.
    hf_cache = models_dir / "hf_cache"
    hf_cache.mkdir(parents=True, exist_ok=True)
    os.environ.setdefault("HF_HOME", str(hf_cache))
    os.environ.setdefault("HUGGINGFACE_HUB_CACHE", str(hf_cache))
    os.environ.setdefault("TRANSFORMERS_CACHE", str(hf_cache))
    os.environ.setdefault("SENTENCE_TRANSFORMERS_HOME", str(models_dir / "st_cache"))

    # Жодної телеметрії з вузла людини. Прогін встановленого пакета 01.10
    # показав два мовчазні канали назовні: ChromaDB шле PostHog-подію при
    # кожному клієнті, де не передали Settings(anonymized_telemetry=False),
    # а ONNX Runtime — 1DS-події на mobile.events.data.microsoft.com.
    # Перший вимикає змінна середовища (її читає chromadb.config.Settings),
    # другий — ORT_DISABLE_TELEMETRY нижче і _silence_onnxruntime() у main().
    os.environ["ANONYMIZED_TELEMETRY"] = "False"
    # ONNX Runtime 1.30 на Linux шле подію вже на сам `import` і ще й
    # зберігає на диску «ідентифікатор пристрою» для телеметрії; виклик
    # disable_telemetry_events() цього НЕ зупиняє (виміряно 01.10 на проксі),
    # зупиняє лише ця змінна — і мусить стояти до першого імпорту.
    os.environ["ORT_DISABLE_TELEMETRY"] = "1"

    # Reproducible logs + safe defaults
    os.environ.setdefault("PYTHONUTF8", "1")
    os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
    os.environ.setdefault("OMP_NUM_THREADS", "4")
    os.environ.setdefault("MKL_NUM_THREADS", "4")
    return root


def _silence_onnxruntime() -> None:
    """Другий пояс до ORT_DISABLE_TELEMETRY (див. _bootstrap_env): на Windows
    ONNX Runtime пише події через ETW, і їх вимикає саме цей виклик. Імпорт
    коштує ~0,1 с."""
    try:
        import onnxruntime

        onnxruntime.disable_telemetry_events()
    except Exception:  # немає onnxruntime у цій збірці — і телеметрії від нього нема
        pass


def main() -> None:
    # Nuitka onefile + multiprocessing on Windows: ``freeze_support`` MUST run
    # before the first import that may spawn workers (sentence-transformers,
    # faster-whisper, chromadb HNSW indexer all spawn). Failing this turns
    # the binary into a fork bomb on Windows.
    _mp.freeze_support()
    if sys.platform == "win32":
        try:
            _mp.set_start_method("spawn", force=True)
        except RuntimeError:
            pass  # already set by an earlier import

    _bootstrap_env()
    _silence_onnxruntime()

    # Make backend package layout (api, core, ai, ...) importable as
    # top-level modules — matches the dev layout used in ``main.py``.
    backend_dir = Path(__file__).resolve().parent
    if str(backend_dir) not in sys.path:
        sys.path.insert(0, str(backend_dir))

    # Lazy imports AFTER env bootstrap so config picks up our paths.
    import uvicorn

    from config import config

    uvicorn.run(
        "main:app",
        host=config.host,
        port=config.port,
        reload=False,
        log_level=config.log_level.lower(),
        access_log=False,
        loop="auto",
        http="auto",
        workers=1,
        # XFF читає лише security/client_ip.py і лише від довіреного проксі.
        proxy_headers=False,
    )


if __name__ == "__main__":
    main()
