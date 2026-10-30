"""Windows: рідні виклики через ctypes і PowerShell — без нових пакетів.

Знімок — Pillow `ImageGrab` (уже в залежностях). Вікна — EnumWindows.
Буфер — OpenClipboard/GetClipboardData, з іменами зареєстрованих форматів:
менеджери паролів (KeePass, 1Password, Bitwarden) кладуть поруч
`ExcludeClipboardContentFromMonitorProcessing` — сторож це бачить і не читає.
Клавіатура й миша — SendInput (кирилиця через KEYEVENTF_UNICODE). Доступність —
UI Automation через PowerShell (`System.Windows.Automation`), аргументи —
змінними середовища, не підстановкою в текст скрипта.

Модуль імпортується на будь-якій ОС (ctypes.wintypes — лише всередині
методів), щоб чисті частини (коди клавіш) перевірялись тестами на Linux.
"""
from __future__ import annotations

import asyncio
import io
import json
import os
from pathlib import Path
from typing import Any, Optional

from symbiote.pc.backend import Clip, Element, OsBackend, PcUnsupported, Window
from symbiote.pc.linux import parse_keys

VK = {
    "ctrl": 0x11, "shift": 0x10, "alt": 0x12, "logo": 0x5B,
    "Return": 0x0D, "Tab": 0x09, "Escape": 0x1B, "space": 0x20, "BackSpace": 0x08,
    "Delete": 0x2E, "Home": 0x24, "End": 0x23, "Prior": 0x21, "Next": 0x22,
    "Up": 0x26, "Down": 0x28, "Left": 0x25, "Right": 0x27, "Insert": 0x2D,
    **{f"F{i}": 0x6F + i for i in range(1, 13)},
    "-": 0xBD, "=": 0xBB, "[": 0xDB, "]": 0xDD, ";": 0xBA, "'": 0xDE,
    ",": 0xBC, ".": 0xBE, "/": 0xBF, "`": 0xC0, "\\": 0xDC,
}


def vk_sequence(keys: str) -> list[tuple[int, bool]]:
    """«ctrl+s» → [(VK_CONTROL, down), (S, down), (S, up), (VK_CONTROL, up)]."""
    mods, key = parse_keys(keys)
    if key in VK:
        code = VK[key]
    elif len(key) == 1 and key.isalnum() and key.isascii():
        code = ord(key.upper())
    else:
        raise ValueError(f"клавіша «{key}» не має коду Windows")
    seq = [(VK[m], True) for m in mods] + [(code, True), (code, False)]
    seq += [(VK[m], False) for m in reversed(mods)]
    return seq


_PS_UIA = r"""
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
$A = [System.Windows.Automation.AutomationElement]
$op = $env:PHANTOM_UIA_OP; $label = $env:PHANTOM_UIA_LABEL; $role = $env:PHANTOM_UIA_ROLE
$app = $env:PHANTOM_UIA_APP; $limit = [int]$env:PHANTOM_UIA_LIMIT
function Snap($e) {
  $c = $e.Current; $r = $c.BoundingRectangle; $text = $null
  if (-not $c.IsPassword) {
    $vp = $null
    if ($e.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$vp)) { $text = $vp.Current.Value }
  }
  $pname = ''
  try { $pname = (Get-Process -Id $c.ProcessId).ProcessName } catch {}
  [ordered]@{ role = $c.ControlType.ProgrammaticName.Replace('ControlType.', '').ToLower();
    name = $c.Name; app = $pname; pid = $c.ProcessId;
    bbox = if ($r.IsEmpty) { $null } else { @([int]$r.X, [int]$r.Y, [int]$r.Width, [int]$r.Height) };
    text = $text; states = @($(if ($c.IsPassword) { 'protected' }), $(if ($c.HasKeyboardFocus) { 'focused' })) | Where-Object { $_ } }
}
if ($op -eq 'focused') { $f = $A::FocusedElement; if ($f) { Snap $f | ConvertTo-Json -Compress -Depth 4 } else { 'null' }; exit }
$out = New-Object System.Collections.ArrayList
$tops = $A::RootElement.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition)
foreach ($w in $tops) {
  if ($app) { try { if ((Get-Process -Id $w.Current.ProcessId).ProcessName -notlike "*$app*") { continue } } catch { continue } }
  foreach ($e in $w.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)) {
    $c = $e.Current
    $r = $c.ControlType.ProgrammaticName.Replace('ControlType.', '').ToLower()
    if ($role -and $r -notlike "*$($role.ToLower())*") { continue }
    if ($label -and ($c.Name -notlike "*$label*")) { continue }
    if ($op -eq 'press') {
      $ip = $null
      if ($e.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$ip)) {
        $s = Snap $e; $s.text = $null; $ip.Invoke(); $s.pressed = $true
        $s | ConvertTo-Json -Compress -Depth 4; exit
      }
      continue
    }
    [void]$out.Add((Snap $e)); if ($out.Count -ge $limit) { break }
  }
  if ($out.Count -ge $limit) { break }
}
if ($op -eq 'press') { 'null' } else { ConvertTo-Json -InputObject @($out) -Compress -Depth 4 }
"""


class WindowsBackend(OsBackend):
    name = "windows"

    def capabilities(self) -> dict[str, bool]:
        return {
            "screenshot": True, "windows": True, "clipboard_read": True, "type": True,
            "hotkey": True, "click": True, "accessibility": True, "open_url": True,
            "launch_app": True, "processes": True,
        }

    async def screenshot(self) -> bytes:
        def grab() -> bytes:
            from PIL import ImageGrab

            img = ImageGrab.grab(all_screens=True)
            buf = io.BytesIO()
            img.save(buf, format="PNG")
            return buf.getvalue()

        return await asyncio.to_thread(grab)

    async def windows(self) -> list[Window]:
        return await asyncio.to_thread(_enum_windows)

    async def active_window(self) -> Optional[Window]:
        wins = await self.windows()
        return next((w for w in wins if w.focused), None)

    async def clipboard_read(self) -> Clip:
        return await asyncio.to_thread(_read_clipboard)

    async def open_url(self, url: str) -> None:
        await asyncio.to_thread(os.startfile, url)  # type: ignore[attr-defined]

    async def launch_app(self, app: str) -> str:
        link = await asyncio.to_thread(_find_start_menu, app)
        if link is None:
            raise PcUnsupported(f"не знаю встановленої програми «{app}»")
        await asyncio.to_thread(os.startfile, str(link))  # type: ignore[attr-defined]
        return link.stem

    async def type_text(self, text: str) -> None:
        from symbiote.desktop_hands import _win_type_sync

        await asyncio.to_thread(_win_type_sync, text)

    async def hotkey(self, keys: list[str]) -> None:
        await asyncio.to_thread(_send_vk, vk_sequence("+".join(keys)))

    async def click(self, x: int, y: int, button: str = "left") -> None:
        await asyncio.to_thread(_mouse_click, int(x), int(y), button)

    async def _uia(self, op: str, *, label: str = "", role: str = "", app: str = "", limit: int = 40) -> Any:
        env = {**os.environ, "PHANTOM_UIA_OP": op, "PHANTOM_UIA_LABEL": label,
               "PHANTOM_UIA_ROLE": role, "PHANTOM_UIA_APP": app, "PHANTOM_UIA_LIMIT": str(limit)}
        proc = await asyncio.create_subprocess_exec(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", _PS_UIA,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, env=env,
        )
        try:
            out, err = await asyncio.wait_for(proc.communicate(), timeout=15.0)
        except asyncio.TimeoutError:
            proc.kill()
            raise PcUnsupported("UI Automation не відповіла вчасно")
        if proc.returncode != 0:
            raise PcUnsupported(f"UI Automation: {(err or b'').decode(errors='replace')[:160]}")
        return json.loads((out or b"null").decode("utf-8", errors="replace") or "null")

    async def ui_find(self, *, label: str = "", role: str = "", app: str = "", limit: int = 40) -> list[Element]:
        rows = await self._uia("find", label=label, role=role, app=app, limit=limit)
        if isinstance(rows, dict):
            rows = [rows]
        return [_element(r) for r in rows or []]

    async def ui_press(self, *, label: str, role: str = "", app: str = "") -> Optional[Element]:
        row = await self._uia("press", label=label, role=role, app=app)
        return _element(row) if row else None

    async def ui_focused(self) -> Optional[Element]:
        try:
            row = await self._uia("focused")
        except PcUnsupported:
            return None
        return _element(row) if row else None


def _element(row: dict[str, Any]) -> Element:
    bbox = row.get("bbox")
    return Element(
        role=str(row.get("role") or ""), name=str(row.get("name") or ""),
        app=str(row.get("app") or ""), pid=int(row.get("pid") or 0),
        bbox=tuple(bbox) if bbox else None, text=row.get("text"),
        states=list(row.get("states") or []),
    )


def _enum_windows() -> list[Window]:
    import ctypes
    from ctypes import wintypes

    import psutil

    user32 = ctypes.WinDLL("user32", use_last_error=True)
    foreground = user32.GetForegroundWindow()
    out: list[Window] = []
    proto = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

    def visit(hwnd, _lparam):
        if not user32.IsWindowVisible(hwnd):
            return True
        length = user32.GetWindowTextLengthW(hwnd)
        if length <= 0:
            return True
        buf = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, buf, length + 1)
        pid = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        try:
            app = psutil.Process(pid.value).name()
        except Exception:  # noqa: BLE001
            app = ""
        out.append(Window(
            id=hex(int(hwnd or 0)), title=buf.value, app=app, pid=int(pid.value),
            focused=hwnd == foreground, visible=not user32.IsIconic(hwnd),
        ))
        return True

    user32.EnumWindows(proto(visit), 0)
    return out


def _read_clipboard() -> Clip:
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.WinDLL("user32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    user32.GetClipboardData.restype = wintypes.HANDLE
    kernel32.GlobalLock.argtypes = [wintypes.HGLOBAL]
    kernel32.GlobalLock.restype = wintypes.LPVOID
    kernel32.GlobalUnlock.argtypes = [wintypes.HGLOBAL]
    if not user32.OpenClipboard(None):
        raise PcUnsupported("буфер зайнятий іншою програмою")
    try:
        types: list[str] = []
        fmt = 0
        while True:
            fmt = user32.EnumClipboardFormats(fmt)
            if not fmt:
                break
            if fmt >= 0xC000:
                buf = ctypes.create_unicode_buffer(256)
                if user32.GetClipboardFormatNameW(fmt, buf, 256):
                    types.append(buf.value)
            else:
                types.append(f"CF_{fmt}")
        handle = user32.GetClipboardData(13)  # CF_UNICODETEXT
        if not handle:
            return Clip(text="", types=types)
        pointer = kernel32.GlobalLock(handle)
        try:
            text = ctypes.wstring_at(pointer) if pointer else ""
        finally:
            kernel32.GlobalUnlock(handle)
        return Clip(text=text, types=types)
    finally:
        user32.CloseClipboard()


def _start_menu_dirs() -> list[Path]:
    dirs = []
    for env in ("APPDATA", "PROGRAMDATA"):
        base = os.environ.get(env)
        if base:
            dirs.append(Path(base) / "Microsoft" / "Windows" / "Start Menu" / "Programs")
    return dirs


def _find_start_menu(app: str, dirs: Optional[list[Path]] = None) -> Optional[Path]:
    want = app.strip().lower()
    exact, partial = None, None
    for base in dirs if dirs is not None else _start_menu_dirs():
        if not base.is_dir():
            continue
        for link in base.rglob("*.lnk"):
            stem = link.stem.lower()
            if stem == want:
                exact = exact or link
            elif want and want in stem:
                partial = partial or link
    return exact or partial


def _inputs():
    import ctypes
    from ctypes import wintypes

    ulong_ptr = ctypes.c_size_t

    class KEYBDINPUT(ctypes.Structure):
        _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                    ("time", wintypes.DWORD), ("dwExtraInfo", ulong_ptr)]

    class MOUSEINPUT(ctypes.Structure):
        _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                    ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ulong_ptr)]

    class _U(ctypes.Union):
        _fields_ = [("ki", KEYBDINPUT), ("mi", MOUSEINPUT)]

    class INPUT(ctypes.Structure):
        _fields_ = [("type", wintypes.DWORD), ("u", _U)]

    user32 = ctypes.WinDLL("user32", use_last_error=True)
    user32.SendInput.argtypes = [wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int]
    return ctypes, user32, INPUT, KEYBDINPUT, MOUSEINPUT


def _send_vk(seq: list[tuple[int, bool]]) -> None:
    ctypes, user32, INPUT, KEYBDINPUT, _ = _inputs()
    events = []
    for code, down in seq:
        ev = INPUT(type=1)
        ev.u.ki = KEYBDINPUT(code, 0, 0 if down else 0x0002, 0, 0)
        events.append(ev)
    array = (INPUT * len(events))(*events)
    if user32.SendInput(len(events), array, ctypes.sizeof(INPUT)) != len(events):
        raise PcUnsupported("клавіатура не прийняла сполучення")


def _mouse_click(x: int, y: int, button: str) -> None:
    ctypes, user32, INPUT, _, MOUSEINPUT = _inputs()
    flags = {"left": (0x0002, 0x0004), "right": (0x0008, 0x0010), "middle": (0x0020, 0x0040)}
    down, up = flags.get(button, flags["left"])
    if not user32.SetCursorPos(int(x), int(y)):
        raise PcUnsupported("миша не перемістилась")
    events = []
    for flag in (down, up):
        ev = INPUT(type=0)
        ev.u.mi = MOUSEINPUT(0, 0, 0, flag, 0, 0)
        events.append(ev)
    array = (INPUT * len(events))(*events)
    if user32.SendInput(len(events), array, ctypes.sizeof(INPUT)) != len(events):
        raise PcUnsupported("миша не клацнула")


__all__ = ["WindowsBackend", "vk_sequence"]
