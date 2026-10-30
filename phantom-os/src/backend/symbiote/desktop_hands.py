"""Руки ПК: те, що телефон може попросити зробити тут.

Два світи, у яких живе PHANTOM на ПК: Windows (рідні виклики через ctypes і
PowerShell — жодних нових пакетів) і Linux, зокрема Arch/Hyprland на Wayland
(wl-copy, notify-send, xdg-open, hyprlock через hypridle). Відповіді —
людськими словами: вони їдуть на екран телефона.
"""
from __future__ import annotations

import asyncio
import logging
import os
import shutil
import subprocess
import sys
from typing import Any, Optional
from urllib.parse import urlparse

from input.desktop_control import ControlBackendError, type_text

logger = logging.getLogger(__name__)

_TIMEOUT_S = 5.0

#: Відкривати можна лише посилання. Шлях до файлу чи `file://` на Windows —
#: це запуск програми, а на Linux xdg-open виконає `.desktop`.
_OPENABLE = ("http", "https", "mailto")


def _platform() -> str:
    if sys.platform.startswith("win"):
        return "windows"
    if os.environ.get("WAYLAND_DISPLAY") or (
        (os.environ.get("XDG_SESSION_TYPE") or "").lower() == "wayland"
    ):
        return "wayland"
    return "x11"


def _hyprland() -> bool:
    return bool(os.environ.get("HYPRLAND_INSTANCE_SIGNATURE"))


async def _run(
    cmd: list[str],
    *,
    stdin: Optional[str] = None,
    env: Optional[dict[str, str]] = None,
) -> tuple[bool, str]:
    try:
        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdin=asyncio.subprocess.PIPE if stdin is not None else None,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
            env={**os.environ, **env} if env else None,
        )
    except (OSError, FileNotFoundError) as exc:
        logger.info("symbiote: %s не запустився: %s", cmd[0], exc)
        return False, "На комп'ютері немає потрібної програми"
    try:
        _, err = await asyncio.wait_for(
            proc.communicate(stdin.encode() if stdin is not None else None),
            timeout=_TIMEOUT_S,
        )
    except asyncio.TimeoutError:
        proc.kill()
        return False, "Комп'ютер не відповів вчасно"
    except (BrokenPipeError, ConnectionResetError):
        err = b""
    if proc.returncode != 0:
        logger.info("symbiote: %s → %s", cmd[0], (err or b"")[:160])
        return False, "Не вийшло"
    return True, ""


async def _run_detached(cmd: list[str]) -> tuple[bool, str]:
    """Для тих, хто лишається жити після роботи (wl-copy тримає виділення)."""
    try:
        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL,
        )
    except (OSError, FileNotFoundError):
        return False, "На комп'ютері немає потрібної програми"
    try:
        code = await asyncio.wait_for(proc.wait(), timeout=_TIMEOUT_S)
    except asyncio.TimeoutError:
        proc.kill()
        return False, "Комп'ютер не відповів вчасно"
    return code == 0, "" if code == 0 else "Не вийшло"


async def _spawn(cmd: list[str]) -> tuple[bool, str]:
    """Запустити й не чекати: hyprlock живе, доки людина не відімкне."""
    try:
        await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL,
            start_new_session=True,
        )
    except (OSError, FileNotFoundError):
        return False, "На комп'ютері немає потрібної програми"
    return True, ""


def _is_running(name: str) -> bool:
    try:
        return subprocess.run(
            ["pidof", name], capture_output=True, timeout=1.5,
        ).returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


# ── Windows: рідні виклики ──────────────────────────────────────────────────


async def _win_clipboard(text: str) -> None:
    await asyncio.to_thread(_win_clipboard_sync, text)


def _win_clipboard_sync(text: str) -> None:
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.WinDLL("user32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.GlobalAlloc.argtypes = [wintypes.UINT, ctypes.c_size_t]
    kernel32.GlobalAlloc.restype = wintypes.HGLOBAL
    kernel32.GlobalLock.argtypes = [wintypes.HGLOBAL]
    kernel32.GlobalLock.restype = wintypes.LPVOID
    kernel32.GlobalUnlock.argtypes = [wintypes.HGLOBAL]
    kernel32.GlobalFree.argtypes = [wintypes.HGLOBAL]
    user32.OpenClipboard.argtypes = [wintypes.HWND]
    user32.SetClipboardData.argtypes = [wintypes.UINT, wintypes.HANDLE]
    user32.SetClipboardData.restype = wintypes.HANDLE

    cf_unicodetext, gmem_moveable = 13, 0x0002
    data = text.encode("utf-16-le") + b"\x00\x00"
    for _ in range(10):
        if user32.OpenClipboard(None):
            break
        import time

        time.sleep(0.05)
    else:
        raise OSError("буфер зайнятий іншою програмою")
    try:
        user32.EmptyClipboard()
        handle = kernel32.GlobalAlloc(gmem_moveable, len(data))
        if not handle:
            raise OSError("немає пам'яті для буфера")
        pointer = kernel32.GlobalLock(handle)
        ctypes.memmove(pointer, data, len(data))
        kernel32.GlobalUnlock(handle)
        if not user32.SetClipboardData(cf_unicodetext, handle):
            kernel32.GlobalFree(handle)
            raise OSError("буфер не прийняв текст")
    finally:
        user32.CloseClipboard()


async def _win_type(text: str) -> None:
    await asyncio.to_thread(_win_type_sync, text)


def _win_type_sync(text: str) -> None:
    """SendInput з KEYEVENTF_UNICODE: кирилиця без розкладки й без SendKeys."""
    import ctypes
    from ctypes import wintypes

    ulong_ptr = ctypes.c_size_t

    class KEYBDINPUT(ctypes.Structure):
        _fields_ = [
            ("wVk", wintypes.WORD),
            ("wScan", wintypes.WORD),
            ("dwFlags", wintypes.DWORD),
            ("time", wintypes.DWORD),
            ("dwExtraInfo", ulong_ptr),
        ]

    class MOUSEINPUT(ctypes.Structure):
        _fields_ = [
            ("dx", wintypes.LONG),
            ("dy", wintypes.LONG),
            ("mouseData", wintypes.DWORD),
            ("dwFlags", wintypes.DWORD),
            ("time", wintypes.DWORD),
            ("dwExtraInfo", ulong_ptr),
        ]

    class _INPUTUNION(ctypes.Union):
        _fields_ = [("ki", KEYBDINPUT), ("mi", MOUSEINPUT)]

    class INPUT(ctypes.Structure):
        _fields_ = [("type", wintypes.DWORD), ("u", _INPUTUNION)]

    input_keyboard, keyeventf_unicode, keyeventf_keyup = 1, 0x0004, 0x0002
    user32 = ctypes.WinDLL("user32", use_last_error=True)
    user32.SendInput.argtypes = [wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int]

    units = text.encode("utf-16-le")
    events: list[INPUT] = []
    for i in range(0, len(units), 2):
        code = int.from_bytes(units[i:i + 2], "little")
        for flags in (keyeventf_unicode, keyeventf_unicode | keyeventf_keyup):
            event = INPUT(type=input_keyboard)
            event.u.ki = KEYBDINPUT(0, code, flags, 0, 0)
            events.append(event)
    array = (INPUT * len(events))(*events)
    sent = user32.SendInput(len(events), array, ctypes.sizeof(INPUT))
    if sent != len(events):
        raise OSError("клавіатура не прийняла текст")


async def _win_open(target: str) -> None:
    await asyncio.to_thread(os.startfile, target)  # type: ignore[attr-defined]


_WIN_TOAST = (
    "$ErrorActionPreference='Stop';"
    "[Windows.UI.Notifications.ToastNotificationManager,Windows.UI.Notifications,"
    "ContentType=WindowsRuntime]>$null;"
    "$t=[Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent("
    "[Windows.UI.Notifications.ToastTemplateType]::ToastText02);"
    "$n=$t.GetElementsByTagName('text');"
    "$n.Item(0).AppendChild($t.CreateTextNode($env:PHANTOM_TITLE))>$null;"
    "$n.Item(1).AppendChild($t.CreateTextNode($env:PHANTOM_BODY))>$null;"
    "$id='{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe';"
    "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($id)"
    ".Show([Windows.UI.Notifications.ToastNotification]::new($t))"
)


# ── Руки ────────────────────────────────────────────────────────────────────


async def _type(args: dict[str, Any]) -> tuple[bool, str, dict]:
    text = str(args.get("text") or "")
    if not text:
        return False, "Нема що набирати", {}
    # Сторож тіла ПК: у менеджер паролів чи поле пароля не набираємо ніколи.
    from symbiote.pc.backend import PcRefused
    from symbiote.pc.hands import _active_secret

    try:
        await _active_secret("текст не набираю")
    except PcRefused as exc:
        return False, f"Відмовлено: {exc}", {"refused": True}
    if _platform() == "windows":
        await _win_type(text)
        return True, f"Набрано {len(text)} символів", {}
    try:
        await type_text(text)
    except ControlBackendError as exc:
        logger.info("symbiote: набір не вдався: %s", exc)
        return False, "На комп'ютері немає чим набирати текст", {}
    return True, f"Набрано {len(text)} символів", {}


async def _clipboard(args: dict[str, Any]) -> tuple[bool, str, dict]:
    text = str(args.get("text") or "")
    if not text:
        return False, "Нема що класти в буфер", {}
    if _platform() == "windows":
        await _win_clipboard(text)
        return True, "У буфері", {}
    tool = "wl-copy" if _platform() == "wayland" and shutil.which("wl-copy") else None
    tool = tool or next((t for t in ("xclip", "xsel") if shutil.which(t)), None)
    if tool is None:
        return False, "На комп'ютері немає доступу до буфера обміну", {}
    if tool == "wl-copy":
        ok, why = await _run_detached(["wl-copy", "--", text])
    else:
        cmd = {
            "xclip": ["xclip", "-selection", "clipboard"],
            "xsel": ["xsel", "--clipboard", "--input"],
        }[tool]
        ok, why = await _run(cmd, stdin=text)
    return ok, "У буфері" if ok else why, {}


def _is_link(target: str) -> bool:
    parsed = urlparse(target)
    if parsed.scheme.lower() not in _OPENABLE:
        return False
    return parsed.scheme.lower() == "mailto" or bool(parsed.netloc)


async def _open(args: dict[str, Any]) -> tuple[bool, str, dict]:
    target = str(args.get("url") or args.get("target") or "").strip()
    if not target:
        return False, "Нема що відкривати", {}
    if not _is_link(target):
        return False, "Відкрити можна лише посилання", {}
    if _platform() == "windows":
        await _win_open(target)
        return True, "Відкрито", {}
    if not shutil.which("xdg-open"):
        return False, "На комп'ютері нема чим відкрити посилання", {}
    ok, why = await _run(["xdg-open", target])
    return ok, "Відкрито" if ok else why, {}


async def _lock(_args: dict[str, Any]) -> tuple[bool, str, dict]:
    if _platform() == "windows":
        ok, why = await _run(["rundll32.exe", "user32.dll,LockWorkStation"])
        return ok, "Замкнено" if ok else why, {}
    # На Hyprland `loginctl lock-session` лише просить hypridle замкнути; без
    # нього прохання мовчки губиться, тож тоді кличемо hyprlock самі.
    if _hyprland() and shutil.which("hyprlock") and not _is_running("hypridle"):
        ok, why = await _spawn(["hyprlock"])
        return ok, "Замкнено" if ok else why, {}
    if not shutil.which("loginctl"):
        return False, "На комп'ютері нема чим замкнути екран", {}
    ok, why = await _run(["loginctl", "lock-session"])
    return ok, "Замкнено" if ok else why, {}


async def _notify(args: dict[str, Any]) -> tuple[bool, str, dict]:
    title = str(args.get("title") or "PHANTOM")[:120]
    body = str(args.get("body") or args.get("text") or "")[:500]
    ok, why = await say_here(title, body)
    return ok, "Показано" if ok else why, {}


async def say_here(title: str, body: str) -> tuple[bool, str]:
    """Сповіщення людині за цим ПК — і від телефона, і від самого PHANTOM."""
    if _platform() == "windows":
        return await _run(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", _WIN_TOAST],
            env={"PHANTOM_TITLE": title, "PHANTOM_BODY": body},
        )
    if not shutil.which("notify-send"):
        return False, "На комп'ютері нема чим показати сповіщення"
    return await _run(["notify-send", "--app-name=PHANTOM", "--", title, body])


_HANDS = {
    "pc.type": _type,
    "pc.clipboard": _clipboard,
    "pc.open": _open,
    "pc.lock": _lock,
    "pc.notify": _notify,
}


def knows(verb: str) -> bool:
    from symbiote.pc import hands as pc_hands

    return verb in _HANDS or pc_hands.knows(verb)


async def execute(verb: str, args: dict[str, Any]) -> tuple[bool, str, dict]:
    hand = _HANDS.get(verb)
    if hand is None:
        # Тіло ПК (`symbiote/pc`): бачити й діяти — екран, вікна, файли, браузер…
        from symbiote.pc import hands as pc_hands

        return await pc_hands.execute(verb, args)
    try:
        return await hand(args or {})
    except Exception as exc:  # noqa: BLE001
        logger.warning("symbiote: рука %s зламалась: %r", verb, exc)
        return False, "Не вийшло", {}


__all__ = ["execute", "knows", "say_here"]
