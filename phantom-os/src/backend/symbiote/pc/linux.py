"""Linux: Wayland (Hyprland у першу чергу) і X11.

Знімок — `vision.screen_capture` (grim / mss / scrot / import). Вікна —
`hyprctl clients -j` на Hyprland, `wmctrl -lp` на X11. Буфер — `wl-paste`
або `xclip`. Клавіатура — `wtype` на Wayland (уміє і модифікатори), інакше
`input.desktop_control` (xdotool / ydotool). Доступність — AT-SPI
(`a11y_atspi.py`: у процесі, коли venv бачить `gi`, інакше системним python).
Програми — лише встановлені, з `.desktop`-записом: ніяких довільних бінарників.
"""
from __future__ import annotations

import asyncio
import configparser
import json
import logging
import os
import shlex
import shutil
import sys
from pathlib import Path
from typing import Any, Optional

from symbiote.pc.backend import Clip, Element, OsBackend, PcUnsupported, Window

logger = logging.getLogger(__name__)

_TIMEOUT_S = 6.0
_A11Y_SCRIPT = Path(__file__).with_name("a11y_atspi.py")

#: Імена клавіш, які розуміє wtype/xkb, — і лише вони.
MODIFIERS = {"ctrl": "ctrl", "control": "ctrl", "shift": "shift", "alt": "alt",
             "super": "logo", "win": "logo", "meta": "logo", "logo": "logo"}
NAMED_KEYS = {
    "enter": "Return", "return": "Return", "tab": "Tab", "esc": "Escape", "escape": "Escape",
    "space": "space", "backspace": "BackSpace", "delete": "Delete", "del": "Delete",
    "home": "Home", "end": "End", "pageup": "Prior", "pagedown": "Next",
    "up": "Up", "down": "Down", "left": "Left", "right": "Right", "insert": "Insert",
    **{f"f{i}": f"F{i}" for i in range(1, 13)},
}


def parse_keys(keys: str) -> tuple[list[str], str]:
    """«ctrl+shift+t» → (['ctrl','shift'], 't'). Невідоме — ValueError."""
    parts = [p.strip().lower() for p in keys.replace(" ", "").split("+") if p.strip()]
    if not parts:
        raise ValueError("порожнє сполучення клавіш")
    *mods, key = parts
    norm_mods: list[str] = []
    for m in mods:
        if m not in MODIFIERS:
            raise ValueError(f"невідомий модифікатор «{m}»")
        norm_mods.append(MODIFIERS[m])
    if key in NAMED_KEYS:
        key = NAMED_KEYS[key]
    elif not (len(key) == 1 and (key.isalnum() or key in "-=[];',./`\\")):
        raise ValueError(f"невідома клавіша «{key}»")
    return norm_mods, key


def wtype_argv(mods: list[str], key: str) -> list[str]:
    argv = ["wtype"]
    for m in mods:
        argv += ["-M", m]
    argv += ["-k", key] if len(key) > 1 else [key]
    for m in reversed(mods):
        argv += ["-m", m]
    return argv


def session() -> str:
    if os.environ.get("WAYLAND_DISPLAY") or (os.environ.get("XDG_SESSION_TYPE") or "").lower() == "wayland":
        return "wayland"
    return "x11" if os.environ.get("DISPLAY") else "none"


def hyprland() -> bool:
    return bool(os.environ.get("HYPRLAND_INSTANCE_SIGNATURE")) and shutil.which("hyprctl") is not None


async def run(argv: list[str], *, stdin: Optional[bytes] = None, timeout: float = _TIMEOUT_S) -> bytes:
    """Запустити без оболонки; ненульовий код — PcUnsupported з причиною."""
    try:
        proc = await asyncio.create_subprocess_exec(
            *argv,
            stdin=asyncio.subprocess.PIPE if stdin is not None else asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
    except FileNotFoundError as exc:
        raise PcUnsupported(f"немає програми {argv[0]}") from exc
    try:
        out, err = await asyncio.wait_for(proc.communicate(stdin), timeout=timeout)
    except asyncio.TimeoutError:
        proc.kill()
        raise PcUnsupported(f"{argv[0]} не відповів вчасно")
    if proc.returncode != 0:
        raise PcUnsupported(f"{argv[0]}: {(err or b'').decode(errors='replace').strip()[:160] or proc.returncode}")
    return out or b""


def parse_hypr_clients(raw: str, active_address: str = "") -> list[Window]:
    try:
        clients = json.loads(raw or "[]")
    except ValueError:
        return []
    out: list[Window] = []
    for c in clients:
        if not c.get("mapped", True):
            continue
        out.append(Window(
            id=str(c.get("address") or ""),
            title=str(c.get("title") or ""),
            app=str(c.get("class") or c.get("initialClass") or ""),
            pid=int(c.get("pid") or 0),
            focused=(c.get("focusHistoryID") == 0) or (bool(active_address) and c.get("address") == active_address),
            visible=not c.get("hidden", False),
            workspace=str((c.get("workspace") or {}).get("name") or ""),
        ))
    return out


def parse_wmctrl(raw: str, active_id: str = "") -> list[Window]:
    out: list[Window] = []
    for line in (raw or "").splitlines():
        parts = line.split(None, 4)
        if len(parts) < 4:
            continue
        wid, desk, pid = parts[0], parts[1], parts[2]
        title = parts[4] if len(parts) > 4 else ""
        try:
            focused = bool(active_id) and int(wid, 16) == int(active_id, 16)
        except ValueError:
            focused = False
        out.append(Window(
            id=wid, title=title, pid=int(pid) if pid.isdigit() else 0,
            focused=focused, workspace=desk,
        ))
    return out


def desktop_dirs() -> list[Path]:
    data_home = Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local/share")
    dirs = [data_home] + [Path(p) for p in (os.environ.get("XDG_DATA_DIRS") or "/usr/local/share:/usr/share").split(":") if p]
    dirs.append(Path("/var/lib/flatpak/exports/share"))
    dirs.append(Path.home() / ".local/share/flatpak/exports/share")
    return [d / "applications" for d in dirs]


def find_desktop_entry(app: str, dirs: Optional[list[Path]] = None) -> Optional[tuple[str, Path, str]]:
    """(id, шлях, назва) встановленої програми за назвою або id. Лише
    `Type=Application`, не `NoDisplay`, не `Hidden`."""
    want = app.strip().lower()
    if not want:
        return None
    best: Optional[tuple[int, str, Path, str]] = None
    for base in dirs if dirs is not None else desktop_dirs():
        if not base.is_dir():
            continue
        for path in base.rglob("*.desktop"):
            parser = configparser.ConfigParser(interpolation=None, strict=False)
            try:
                parser.read(path, encoding="utf-8")
            except (configparser.Error, OSError, UnicodeDecodeError):
                continue
            if "Desktop Entry" not in parser:
                continue
            entry = parser["Desktop Entry"]
            if entry.get("Type", "Application") != "Application":
                continue
            if entry.get("NoDisplay", "false").lower() == "true" or entry.get("Hidden", "false").lower() == "true":
                continue
            app_id = path.name[: -len(".desktop")]
            name = entry.get("Name", app_id)
            names = {app_id.lower(), name.lower(), app_id.lower().split(".")[-1]}
            score = 0 if want in names else (1 if any(want in n for n in names) else None)
            if score is None:
                continue
            if best is None or score < best[0]:
                best = (score, app_id, path, name)
    return None if best is None else (best[1], best[2], best[3])


def desktop_exec_argv(path: Path) -> list[str]:
    parser = configparser.ConfigParser(interpolation=None, strict=False)
    parser.read(path, encoding="utf-8")
    raw = parser["Desktop Entry"].get("Exec", "")
    argv = [a for a in shlex.split(raw) if not (len(a) == 2 and a.startswith("%"))]
    if not argv:
        raise PcUnsupported("у програми немає команди запуску")
    return argv


class LinuxBackend(OsBackend):
    name = "linux"

    def capabilities(self) -> dict[str, bool]:
        wl = session() == "wayland"
        return {
            "screenshot": bool(shutil.which("grim") if wl else (shutil.which("scrot") or shutil.which("import"))) or _has_module("mss"),
            "windows": hyprland() or bool(shutil.which("wmctrl")),
            "clipboard_read": bool(shutil.which("wl-paste") if wl else (shutil.which("xclip") or shutil.which("xsel"))),
            "type": bool(shutil.which("wtype") or shutil.which("xdotool") or shutil.which("ydotool")),
            "hotkey": bool(shutil.which("wtype") or shutil.which("xdotool")),
            "click": bool(shutil.which("ydotool") or shutil.which("xdotool")),
            "accessibility": _a11y_python() is not None,
            "open_url": bool(shutil.which("xdg-open")),
            "launch_app": True,
            "processes": True,
        }

    async def screenshot(self) -> bytes:
        from vision import screen_capture

        frame = await screen_capture.capture()
        if isinstance(frame, screen_capture.ScreenCaptureError):
            raise PcUnsupported(f"знімок екрана: немає чим ({', '.join(frame.tried) or 'grim/mss'})")
        return frame.png_bytes

    async def windows(self) -> list[Window]:
        if hyprland():
            active = ""
            try:
                active = json.loads((await run(["hyprctl", "activewindow", "-j"])).decode() or "{}").get("address", "")
            except (PcUnsupported, ValueError):
                pass
            return parse_hypr_clients((await run(["hyprctl", "clients", "-j"])).decode(), active)
        if shutil.which("wmctrl"):
            active = ""
            if shutil.which("xdotool"):
                try:
                    active = hex(int((await run(["xdotool", "getactivewindow"])).decode().strip()))
                except (PcUnsupported, ValueError):
                    active = ""
            return parse_wmctrl((await run(["wmctrl", "-lp"])).decode(errors="replace"), active)
        raise PcUnsupported("список вікон: потрібен Hyprland або wmctrl")

    async def active_window(self) -> Optional[Window]:
        if hyprland():
            raw = json.loads((await run(["hyprctl", "activewindow", "-j"])).decode() or "{}")
            if not raw:
                return None
            return Window(
                id=str(raw.get("address") or ""), title=str(raw.get("title") or ""),
                app=str(raw.get("class") or ""), pid=int(raw.get("pid") or 0), focused=True,
                workspace=str((raw.get("workspace") or {}).get("name") or ""),
            )
        wins = await self.windows()
        return next((w for w in wins if w.focused), None)

    async def clipboard_read(self) -> Clip:
        if session() == "wayland" and shutil.which("wl-paste"):
            try:
                types = (await run(["wl-paste", "--list-types"])).decode(errors="replace").split()
            except PcUnsupported:
                return Clip(text="", types=[])  # порожній буфер — не помилка
            text_type = next((t for t in types if t.startswith("text/plain")), None) or \
                next((t for t in types if t in ("UTF8_STRING", "STRING", "TEXT")), None)
            if text_type is None:
                return Clip(text="", types=types)
            text = (await run(["wl-paste", "--no-newline", "--type", text_type])).decode(errors="replace")
            return Clip(text=text, types=types)
        if shutil.which("xclip"):
            try:
                types = (await run(["xclip", "-selection", "clipboard", "-o", "-t", "TARGETS"])).decode().split()
            except PcUnsupported:
                types = []
            text = (await run(["xclip", "-selection", "clipboard", "-o"])).decode(errors="replace")
            return Clip(text=text, types=types)
        if shutil.which("xsel"):
            return Clip(text=(await run(["xsel", "--clipboard", "--output"])).decode(errors="replace"))
        raise PcUnsupported("читання буфера: потрібен wl-paste або xclip")

    async def open_url(self, url: str) -> None:
        if not shutil.which("xdg-open"):
            raise PcUnsupported("немає xdg-open")
        await _spawn(["xdg-open", url])

    async def launch_app(self, app: str) -> str:
        found = await asyncio.to_thread(find_desktop_entry, app)
        if found is None:
            raise PcUnsupported(f"не знаю встановленої програми «{app}»")
        app_id, path, name = found
        if shutil.which("gtk-launch"):
            await _spawn(["gtk-launch", app_id])
        else:
            await _spawn(desktop_exec_argv(path))
        return name

    async def type_text(self, text: str) -> None:
        if session() == "wayland" and shutil.which("wtype"):
            await run(["wtype", "--", text], timeout=max(_TIMEOUT_S, len(text) * 0.02))
            return
        from input.desktop_control import ControlBackendError, type_text

        try:
            await type_text(text)
        except ControlBackendError as exc:
            raise PcUnsupported("набір тексту: потрібен wtype, xdotool або ydotool") from exc

    async def hotkey(self, keys: list[str]) -> None:
        combo = "+".join(keys)
        mods, key = parse_keys(combo)
        if session() == "wayland" and shutil.which("wtype"):
            await run(wtype_argv(mods, key))
            return
        if shutil.which("xdotool"):
            xmods = {"ctrl": "ctrl", "shift": "shift", "alt": "alt", "logo": "super"}
            await run(["xdotool", "key", "+".join([xmods[m] for m in mods] + [key])])
            return
        raise PcUnsupported("сполучення клавіш: потрібен wtype або xdotool")

    async def click(self, x: int, y: int, button: str = "left") -> None:
        from input.desktop_control import ControlBackendError, click

        try:
            await click(int(x), int(y), button=button)
        except ControlBackendError as exc:
            raise PcUnsupported("клацання: потрібен ydotool або xdotool") from exc

    async def _a11y(self, request: dict[str, Any]) -> Any:
        return await a11y_call(request)

    async def ui_find(self, *, label: str = "", role: str = "", app: str = "", limit: int = 40) -> list[Element]:
        rows = await self._a11y({"op": "find", "label": label, "role": role, "app": app, "limit": limit})
        return [_element(r) for r in rows or []]

    async def ui_press(self, *, label: str, role: str = "", app: str = "") -> Optional[Element]:
        row = await self._a11y({"op": "press", "label": label, "role": role, "app": app})
        return None if not row else _element(row)

    async def ui_focused(self) -> Optional[Element]:
        try:
            win = await self.active_window()
        except PcUnsupported:
            win = None
        try:
            row = await self._a11y({"op": "focused", "pid": win.pid if win else 0})
        except PcUnsupported:
            return None
        return None if not row else _element(row)


def _element(row: dict[str, Any]) -> Element:
    bbox = row.get("bbox")
    return Element(
        role=str(row.get("role") or ""), name=str(row.get("name") or ""),
        app=str(row.get("app") or ""), pid=int(row.get("pid") or 0),
        bbox=tuple(bbox) if bbox else None, text=row.get("text"),
        states=list(row.get("states") or []),
    )


def _has_module(name: str) -> bool:
    import importlib.util

    return importlib.util.find_spec(name) is not None


def _a11y_python() -> Optional[str]:
    """Чим ганяти AT-SPI: '' — у цьому процесі, шлях — системним python."""
    try:
        from symbiote.pc import a11y_atspi

        if a11y_atspi.available():
            return ""
    except Exception:  # noqa: BLE001
        pass
    for candidate in ("/usr/bin/python3", shutil.which("python3")):
        if candidate and os.path.realpath(candidate) != os.path.realpath(sys.executable) and os.path.exists(candidate):
            return candidate
    return None


async def a11y_call(request: dict[str, Any]) -> Any:
    py = _a11y_python()
    if py is None:
        raise PcUnsupported("дерево доступності: немає AT-SPI (gi.repository.Atspi)")
    if py == "":
        from symbiote.pc import a11y_atspi

        return await asyncio.to_thread(a11y_atspi.handle, request)
    out = await run([py, str(_A11Y_SCRIPT), json.dumps(request, ensure_ascii=False)], timeout=12.0)
    reply = json.loads(out.decode() or "{}")
    if not reply.get("ok"):
        raise PcUnsupported(f"дерево доступності: {reply.get('error', 'не відповіло')}")
    return reply.get("result")


async def _spawn(argv: list[str]) -> None:
    try:
        await asyncio.create_subprocess_exec(
            *argv, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
            stdin=asyncio.subprocess.DEVNULL, start_new_session=True,
        )
    except FileNotFoundError as exc:
        raise PcUnsupported(f"немає програми {argv[0]}") from exc


__all__ = [
    "LinuxBackend", "parse_keys", "wtype_argv", "parse_hypr_clients", "parse_wmctrl",
    "find_desktop_entry", "desktop_exec_argv", "a11y_call",
]
