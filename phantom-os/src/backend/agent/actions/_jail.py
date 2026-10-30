"""Дочірня програма дії — лише в bwrap (`compute`: без мережі, корінь лише для
читання, записувана тільки робоча тека як /workspace) і з чистим середовищем.
Без bwrap — відмова, не запуск навпростець."""
from __future__ import annotations

import os
from pathlib import Path

from ..operations.safety.sandbox import (
    SandboxProfile,
    assert_env_safe,
    bwrap_available,
    clean_env,
    wrap_argv,
)

JAIL_ROOT = "/workspace"


class JailRefused(Exception):
    def __init__(self, error_class: str, message: str) -> None:
        super().__init__(message)
        self.error_class = error_class


def workspace_root(workspace_dir: str) -> Path:
    return Path(os.path.expanduser(str(workspace_dir))).resolve()


def inside(path: str, workspace_dir: str) -> str:
    """Шлях хоста всередині робочої теки → той самий шлях у пісочниці."""
    ws = workspace_root(workspace_dir)
    p = Path(os.path.expanduser(path))
    if not p.is_absolute():
        p = ws / p
    p = p.resolve()
    try:
        rel = p.relative_to(ws)
    except ValueError:
        raise JailRefused("path_outside_workspace", f"{path} поза робочою текою {ws}") from None
    return str(Path(JAIL_ROOT) / rel)


def jail(argv: list[str], workspace_dir: str) -> tuple[list[str], dict[str, str]]:
    if not bwrap_available():
        raise JailRefused("sandbox_unavailable", "bwrap немає — без пісочниці не запускаю")
    wrapped, sandboxed = wrap_argv(
        SandboxProfile.compute, argv, workspace_dir=workspace_dir, memory_limit_bytes=None,
    )
    if not sandboxed:
        raise JailRefused("sandbox_unavailable", "пісочниця не стала — не запускаю")
    env = clean_env(workspace_dir=workspace_dir)
    assert_env_safe(env)
    return wrapped, env
