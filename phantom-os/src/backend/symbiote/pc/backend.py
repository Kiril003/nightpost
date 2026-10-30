"""ОС-шар тіла ПК: один інтерфейс, три світи і підробка для тестів.

Кожен метод або робить справу, або чесно кидає `PcUnsupported` з людським
поясненням, чого бракує («немає wl-paste»). Ніяких тихих порожніх відповідей:
порожній список вікон означає «вікон немає», а не «я не вмію дивитись».
"""
from __future__ import annotations

import sys
from dataclasses import asdict, dataclass, field
from typing import Any, Optional


class PcUnsupported(RuntimeError):
    """Цього на цьому комп'ютері зробити нема чим (людською мовою)."""


class PcRefused(RuntimeError):
    """Сторож не пустив: секрет, пароль, чужа тека, покупка… (людською мовою)."""


@dataclass
class Window:
    id: str
    title: str
    app: str = ""
    pid: int = 0
    focused: bool = False
    visible: bool = True
    workspace: str = ""

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class Element:
    """Вузол доступності (AT-SPI / UI Automation)."""

    role: str
    name: str = ""
    app: str = ""
    pid: int = 0
    bbox: Optional[tuple[int, int, int, int]] = None  # x, y, w, h
    text: Optional[str] = None
    states: list[str] = field(default_factory=list)

    @property
    def is_password(self) -> bool:
        return "password" in self.role.lower() or "protected" in self.states

    def to_dict(self) -> dict[str, Any]:
        out = asdict(self)
        if self.is_password:
            out["text"] = None  # вміст поля пароля не покидає машину ніколи
        return out


@dataclass
class Clip:
    text: str
    types: list[str] = field(default_factory=list)


class OsBackend:
    """Базовий клас: усе «не вмію». Платформи перекривають, що вміють."""

    name = "none"

    def _no(self, what: str) -> PcUnsupported:
        return PcUnsupported(f"На цьому комп'ютері ({self.name}) поки не вмію: {what}")

    def capabilities(self) -> dict[str, bool]:
        """Що реально доступне зараз (для вікна і PC_BODY.md)."""
        return {}

    async def screenshot(self) -> bytes:
        raise self._no("знімок екрана")

    async def windows(self) -> list[Window]:
        raise self._no("список вікон")

    async def active_window(self) -> Optional[Window]:
        raise self._no("активне вікно")

    async def clipboard_read(self) -> Clip:
        raise self._no("читання буфера")

    async def processes(self) -> list[dict[str, Any]]:
        return _psutil_processes()

    async def open_url(self, url: str) -> None:
        raise self._no("відкрити посилання")

    async def launch_app(self, app: str) -> str:
        raise self._no("запуск програм")

    async def type_text(self, text: str) -> None:
        raise self._no("набір тексту")

    async def hotkey(self, keys: list[str]) -> None:
        raise self._no("сполучення клавіш")

    async def click(self, x: int, y: int, button: str = "left") -> None:
        raise self._no("клацання")

    async def ui_find(
        self, *, label: str = "", role: str = "", app: str = "", limit: int = 40,
    ) -> list[Element]:
        raise self._no("дерево доступності")

    async def ui_press(self, *, label: str, role: str = "", app: str = "") -> Optional[Element]:
        """Натиснути елемент дією доступності. None — такого немає."""
        raise self._no("натискання елемента")

    async def ui_focused(self) -> Optional[Element]:
        """Елемент у фокусі (щоб не набрати текст у поле пароля)."""
        return None


def _psutil_processes() -> list[dict[str, Any]]:
    import psutil

    out: list[dict[str, Any]] = []
    for proc in psutil.process_iter(["pid", "name", "username", "memory_info", "cpu_percent"]):
        info = proc.info
        mem = info.get("memory_info")
        out.append({
            "pid": int(info.get("pid") or 0),
            "name": str(info.get("name") or ""),
            "user": str(info.get("username") or ""),
            "mem_mb": round((mem.rss if mem else 0) / 1_048_576, 1),
            "cpu": float(info.get("cpu_percent") or 0.0),
        })
    out.sort(key=lambda p: p["mem_mb"], reverse=True)
    return out


_override: Optional[OsBackend] = None
_cached: Optional[OsBackend] = None


def platform_name() -> str:
    if sys.platform.startswith("win"):
        return "windows"
    if sys.platform == "darwin":
        return "macos"
    return "linux"


def get_backend() -> OsBackend:
    global _cached
    if _override is not None:
        return _override
    if _cached is None:
        plat = platform_name()
        if plat == "windows":
            from symbiote.pc.windows import WindowsBackend

            _cached = WindowsBackend()
        elif plat == "macos":
            from symbiote.pc.macos import MacBackend

            _cached = MacBackend()
        else:
            from symbiote.pc.linux import LinuxBackend

            _cached = LinuxBackend()
    return _cached


def use_backend(backend: Optional[OsBackend]) -> None:
    """Тести (і лише вони) підставляють свою ОС. None — повернути справжню."""
    global _override
    _override = backend


__all__ = [
    "Clip",
    "Element",
    "OsBackend",
    "PcRefused",
    "PcUnsupported",
    "Window",
    "get_backend",
    "platform_name",
    "use_backend",
]
