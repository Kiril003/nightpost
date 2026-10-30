"""Контракт задачі і оцінювач.

Контракт — це тест, який задача має зробити зеленим. Модель його ПИШЕ, але не
вирішує, чим і як його міряти: команду запуску і тлумачення коду виходу
задає цей модуль. Інакше модель, що «доводить» власну роботу, могла б
підсунути команду `true`.

Коди виходу pytest (документовані): 0 — усі пройшли; 1 — є провалені;
2 — перервано (зокрема помилка збору: тест імпортує те, чого ще нема —
звичайний TDD-червоний); 3 — внутрішня помилка; 4 — помилка виклику;
5 — не зібрано жодного тесту. Червоним, що ДОВОДИТЬ відсутність поведінки,
вважаються лише 1 і 2; зеленим — лише 0 з хоча б одним пройденим тестом.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import os
import re
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath

RED_EXIT_CODES = frozenset({1, 2})
_PASSED_RE = re.compile(r"(\d+) passed")


class ContractError(ValueError):
    """Відповідь моделі не складається в перевірний контракт."""


@dataclass(frozen=True)
class Contract:
    goal: str
    test_path: str
    test_source: str
    # Тека, з якої запускається pytest (відносно кореня репозиторію). У моно-
    # репозиторії PHANTOM бекенд тестується з `phantom-os/src/backend`.
    workdir: str = "."

    @property
    def repo_test_path(self) -> str:
        return str(PurePosixPath(self.workdir) / self.test_path)

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.test_source.encode("utf-8")).hexdigest()

    def command(self) -> list[str]:
        return [sys.executable, "-B", "-m", "pytest", "-q", "-p", "no:cacheprovider", self.test_path]


def safe_relpath(raw: str) -> str:
    """Шлях усередині worktree: відносний, без `..`, без абсолютного кореня."""
    if not isinstance(raw, str) or not raw.strip():
        raise ContractError("порожній шлях")
    p = PurePosixPath(raw.strip().replace("\\", "/"))
    if p.is_absolute() or ".." in p.parts or p.parts[:1] == (".git",):
        raise ContractError(f"шлях поза worktree: {raw!r}")
    return str(p)


def _json_object(raw: str) -> dict:
    text = raw.strip()
    fence = re.search(r"```(?:json)?\s*(\{.*\})\s*```", text, re.S)
    if fence:
        text = fence.group(1)
    try:
        obj = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ContractError(f"не JSON: {exc}") from exc
    if not isinstance(obj, dict):
        raise ContractError("очікував JSON-об'єкт")
    return obj


def parse_contract(goal: str, raw: str) -> Contract:
    """`{"test_path": "tests/test_x.py", "test_source": "..."}` → Contract."""
    obj = _json_object(raw)
    path = safe_relpath(obj.get("test_path", ""))
    name = PurePosixPath(path).name
    if not (name.startswith("test_") and name.endswith(".py")):
        raise ContractError(f"контракт має бути pytest-файлом test_*.py, а не {path!r}")
    source = obj.get("test_source")
    if not isinstance(source, str) or "def test_" not in source:
        raise ContractError("у контракті нема жодної функції test_*")
    workdir = safe_relpath(obj.get("workdir") or ".")
    return Contract(goal=goal, test_path=path, test_source=source, workdir=workdir)


def parse_patch(raw: str, contract: Contract) -> dict[str, str]:
    """`{"files": {"path": "повний вміст"}}` → {відносний шлях: вміст}.

    Зміна, що чіпає сам контракт, відкидається тут, до запису: підігнати тест
    під код — це не доказ, а його підробка.
    """
    obj = _json_object(raw)
    files = obj.get("files")
    if not isinstance(files, dict) or not files:
        raise ContractError("зміна без жодного файла")
    out: dict[str, str] = {}
    for raw_path, content in files.items():
        path = safe_relpath(raw_path)
        if path == contract.repo_test_path:
            raise ContractError(f"зміна переписує контракт {path!r}")
        if not isinstance(content, str):
            raise ContractError(f"вміст {path!r} не рядок")
        out[path] = content
    return out


@dataclass(frozen=True)
class Evidence:
    """Результат одного запуску контракту — те, що йде в журнал як доказ."""

    exit_code: int
    passed: int
    test_sha256: str
    elapsed_ms: int
    output_tail: str = field(default="", repr=False)

    @property
    def red(self) -> bool:
        return self.exit_code in RED_EXIT_CODES

    @property
    def green(self) -> bool:
        return self.exit_code == 0 and self.passed > 0

    def as_dict(self) -> dict:
        return {
            "exit_code": self.exit_code,
            "passed": self.passed,
            "test_sha256": self.test_sha256,
            "elapsed_ms": self.elapsed_ms,
            "red": self.red,
            "green": self.green,
            "output_tail": self.output_tail,
        }


def _child_env(cwd: Path) -> dict[str, str]:
    """Код контракту і зміни написала модель — секретів демона він не бачить.

    Середовище будується з нуля білим списком пісочниці ядра (`clean_env`) і
    проходить її ж аудит (`assert_env_safe`). JWT_SECRET_KEY тут — тестове
    значення: бекенд PHANTOM без нього не імпортується, а справжній ключ
    дочірньому процесу не належить.
    """
    from agent.operations.safety.sandbox import assert_env_safe, clean_env
    env = clean_env(str(cwd))
    assert_env_safe(env)
    env["JWT_SECRET_KEY"] = "proof-contract-not-a-secret"
    return env


async def evaluate(worktree: Path, contract: Contract, *, timeout_s: float) -> Evidence:
    """Запускає контракт у worktree. Тест читається з ДИСКА, не з пам'яті:
    хеш у доказі — це хеш того, що справді виконалось."""
    cwd = worktree / contract.workdir
    on_disk = (cwd / contract.test_path).read_text(encoding="utf-8")
    sha = hashlib.sha256(on_disk.encode("utf-8")).hexdigest()
    env = _child_env(cwd)
    t0 = time.monotonic()
    proc = await asyncio.create_subprocess_exec(
        *contract.command(), cwd=str(cwd), env=env,
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT,
    )
    try:
        out, _ = await asyncio.wait_for(proc.communicate(), timeout=timeout_s)
        code = int(proc.returncode or 0)
    except asyncio.TimeoutError:
        proc.kill()
        out, _ = await proc.communicate()
        code = 124
    text = (out or b"").decode("utf-8", errors="replace")
    m = _PASSED_RE.findall(text)
    passed = int(m[-1]) if m else 0
    return Evidence(
        exit_code=code, passed=passed, test_sha256=sha,
        elapsed_ms=int((time.monotonic() - t0) * 1000), output_tail=text[-1500:],
    )
