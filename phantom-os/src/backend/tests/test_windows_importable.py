"""
Вузол PHANTOM стартує на Windows. Прогін pc-windows 26.09: вузол із пакета
падав на першому ж імпорті — `linux/executor.py` тягнув `import pwd` на
верхньому рівні, а main.py імпортує його через api.routes_linux.

Тут імітуємо Windows: POSIX-модулів нема — модуль мусить імпортуватись, а
спроба пісочниці — не падати винятком, що валить запит.
"""
from __future__ import annotations

import importlib
import sys


def test_executor_imports_without_posix_modules(monkeypatch):
    for name in ("pwd", "resource"):
        monkeypatch.setitem(sys.modules, name, None)  # import → ImportError
    monkeypatch.delitem(sys.modules, "linux.executor", raising=False)
    mod = importlib.import_module("linux.executor")
    assert mod.pwd is None and mod.resource is None
    assert mod._drop_to_sandbox_user() is None
    monkeypatch.delitem(sys.modules, "linux.executor", raising=False)
