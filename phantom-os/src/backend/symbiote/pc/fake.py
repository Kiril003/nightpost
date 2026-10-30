"""Підробна ОС для тестів: усе в пам'яті, кожен виклик записано в `calls`."""
from __future__ import annotations

from typing import Any, Optional

from symbiote.pc.backend import Clip, Element, OsBackend, Window

PNG_1PX = (
    b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00"
    b"\x1f\x15\xc4\x89\x00\x00\x00\rIDATx\x9cc\xf8\xff\xff?\x00\x05\xfe\x02\xfe\xa75\x81\x84"
    b"\x00\x00\x00\x00IEND\xaeB`\x82"
)


class FakeBackend(OsBackend):
    name = "fake"

    def __init__(self) -> None:
        self.calls: list[tuple[str, Any]] = []
        self.wins: list[Window] = [
            Window(id="w1", title="Нотатки — main.md", app="code", pid=101, focused=True),
            Window(id="w2", title="Mozilla Firefox", app="firefox", pid=102),
        ]
        self.clip = Clip(text="привіт з буфера", types=["text/plain;charset=utf-8"])
        self.elements: list[Element] = [
            Element(role="push button", name="Зберегти", app="code", pid=101, bbox=(10, 20, 80, 30)),
            Element(role="text", name="", app="code", pid=101, text="рядок у редакторі"),
            Element(role="password text", name="Пароль", app="code", pid=101, text="hunter2"),
        ]
        self.focus: Optional[Element] = None
        self.apps = {"kitty": "kitty", "firefox": "Firefox"}
        self.procs = [{"pid": 101, "name": "code", "user": "me", "mem_mb": 300.0, "cpu": 1.0},
                      {"pid": 102, "name": "firefox", "user": "me", "mem_mb": 900.0, "cpu": 4.0}]

    def capabilities(self) -> dict[str, bool]:
        return {k: True for k in ("screenshot", "windows", "clipboard_read", "type", "hotkey",
                                  "click", "accessibility", "open_url", "launch_app", "processes")}

    async def screenshot(self) -> bytes:
        self.calls.append(("screenshot", None))
        return PNG_1PX

    async def windows(self) -> list[Window]:
        self.calls.append(("windows", None))
        return list(self.wins)

    async def active_window(self) -> Optional[Window]:
        return next((w for w in self.wins if w.focused), None)

    async def clipboard_read(self) -> Clip:
        self.calls.append(("clipboard_read", None))
        return self.clip

    async def processes(self) -> list[dict[str, Any]]:
        return list(self.procs)

    async def open_url(self, url: str) -> None:
        self.calls.append(("open_url", url))

    async def launch_app(self, app: str) -> str:
        self.calls.append(("launch_app", app))
        from symbiote.pc.backend import PcUnsupported

        if app.lower() not in self.apps:
            raise PcUnsupported(f"не знаю встановленої програми «{app}»")
        return self.apps[app.lower()]

    async def type_text(self, text: str) -> None:
        self.calls.append(("type", text))

    async def hotkey(self, keys: list[str]) -> None:
        self.calls.append(("hotkey", "+".join(keys)))

    async def click(self, x: int, y: int, button: str = "left") -> None:
        self.calls.append(("click", (x, y, button)))

    def _match(self, label: str, role: str, app: str) -> list[Element]:
        out = []
        for e in self.elements:
            if role and role.lower() not in e.role.lower():
                continue
            if app and app.lower() not in e.app.lower():
                continue
            if label and label.lower() not in e.name.lower():
                continue
            out.append(e)
        return out

    async def ui_find(self, *, label: str = "", role: str = "", app: str = "", limit: int = 40) -> list[Element]:
        self.calls.append(("ui_find", (label, role, app)))
        return self._match(label, role, app)[:limit]

    async def ui_press(self, *, label: str, role: str = "", app: str = "") -> Optional[Element]:
        found = self._match(label, role, app)
        if not found:
            return None
        self.calls.append(("ui_press", found[0].name))
        return found[0]

    async def ui_focused(self) -> Optional[Element]:
        return self.focus


__all__ = ["FakeBackend", "PNG_1PX"]
