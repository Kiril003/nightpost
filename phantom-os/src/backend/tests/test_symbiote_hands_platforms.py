"""Руки й пульс ПК на Windows і на Arch/Hyprland — з підміною платформи."""
from __future__ import annotations

import sys

import pytest

from symbiote import desktop_body, desktop_hands


@pytest.fixture
def ran(monkeypatch):
    calls: list[dict] = []

    async def fake_run(cmd, *, stdin=None, env=None):
        calls.append({"cmd": list(cmd), "stdin": stdin, "env": env or {}, "how": "run"})
        return True, ""

    async def fake_detached(cmd):
        calls.append({"cmd": list(cmd), "how": "detached"})
        return True, ""

    async def fake_spawn(cmd):
        calls.append({"cmd": list(cmd), "how": "spawn"})
        return True, ""

    monkeypatch.setattr(desktop_hands, "_run", fake_run)
    monkeypatch.setattr(desktop_hands, "_run_detached", fake_detached)
    monkeypatch.setattr(desktop_hands, "_spawn", fake_spawn)
    return calls


@pytest.fixture
def windows(monkeypatch):
    monkeypatch.setattr(sys, "platform", "win32")
    for var in ("WAYLAND_DISPLAY", "DISPLAY", "HYPRLAND_INSTANCE_SIGNATURE", "XDG_SESSION_TYPE"):
        monkeypatch.delenv(var, raising=False)
    monkeypatch.setattr(desktop_hands.shutil, "which", lambda name: f"C:/Windows/{name}.exe")
    win: dict[str, list] = {"clip": [], "type": [], "open": []}

    async def clip(text):
        win["clip"].append(text)

    async def typer(text):
        win["type"].append(text)

    async def opener(target):
        win["open"].append(target)

    monkeypatch.setattr(desktop_hands, "_win_clipboard", clip)
    monkeypatch.setattr(desktop_hands, "_win_type", typer)
    monkeypatch.setattr(desktop_hands, "_win_open", opener)
    return win


@pytest.fixture
def hyprland(monkeypatch):
    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setenv("WAYLAND_DISPLAY", "wayland-1")
    monkeypatch.setenv("HYPRLAND_INSTANCE_SIGNATURE", "abc")
    monkeypatch.delenv("DISPLAY", raising=False)
    tools = {"wl-copy", "notify-send", "xdg-open", "loginctl", "hyprlock", "hypridle", "wtype", "hyprctl"}
    monkeypatch.setattr(
        desktop_hands.shutil, "which", lambda name: f"/usr/bin/{name}" if name in tools else None,
    )
    typed: list[str] = []

    async def fake_type(text, **_kw):
        typed.append(text)
        return True

    monkeypatch.setattr(desktop_hands, "type_text", fake_type)
    return typed


# ── Windows ──────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_windows_clipboard_goes_through_native_clipboard(ran, windows):
    ok, detail, _ = await desktop_hands.execute("pc.clipboard", {"text": "привіт"})
    assert ok, detail
    assert windows["clip"] == ["привіт"]
    assert "немає" not in detail


@pytest.mark.asyncio
async def test_windows_types_unicode(ran, windows):
    ok, _, _ = await desktop_hands.execute("pc.type", {"text": "Слава"})
    assert ok
    assert windows["type"] == ["Слава"]


@pytest.mark.asyncio
async def test_windows_opens_links_only(ran, windows):
    ok, _, _ = await desktop_hands.execute("pc.open", {"url": "https://phantom-os.dev"})
    assert ok and windows["open"] == ["https://phantom-os.dev"]
    for bad in ("C:/Windows/System32/cmd.exe", "file:///C:/x.bat", "\\\\host\\share\\run.exe"):
        ok, detail, _ = await desktop_hands.execute("pc.open", {"url": bad})
        assert not ok, bad
        assert "посилання" in detail
    assert windows["open"] == ["https://phantom-os.dev"]


@pytest.mark.asyncio
async def test_windows_locks_screen(ran, windows):
    ok, _, _ = await desktop_hands.execute("pc.lock", {})
    assert ok
    assert ran[-1]["cmd"][0].lower().startswith("rundll32")
    assert "LockWorkStation" in ran[-1]["cmd"][-1]


@pytest.mark.asyncio
async def test_windows_notification_carries_title_and_body_outside_the_script(ran, windows):
    ok, _, _ = await desktop_hands.execute(
        "pc.notify", {"title": "Мама", "body": "\"; Remove-Item C:\\ -Recurse"},
    )
    assert ok
    call = ran[-1]
    assert call["cmd"][0].lower().startswith("powershell")
    assert call["env"]["PHANTOM_TITLE"] == "Мама"
    assert call["env"]["PHANTOM_BODY"].startswith("\"; Remove-Item")
    assert "Remove-Item" not in " ".join(call["cmd"])


# ── Arch / Hyprland ──────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_hyprland_clipboard_is_wl_copy(ran, hyprland):
    ok, _, _ = await desktop_hands.execute("pc.clipboard", {"text": "привіт"})
    assert ok
    assert ran[-1]["cmd"] == ["wl-copy", "--", "привіт"]


@pytest.mark.asyncio
async def test_hyprland_notification_reads_title_and_body(ran, hyprland):
    ok, _, _ = await desktop_hands.execute("pc.notify", {"title": "Мама", "body": "Я вдома"})
    assert ok
    assert ran[-1]["cmd"][0] == "notify-send"
    assert ran[-1]["cmd"][-2:] == ["Мама", "Я вдома"]


@pytest.mark.asyncio
async def test_old_text_field_still_notifies(ran, hyprland):
    await desktop_hands.execute("pc.notify", {"text": "Я вдома"})
    assert ran[-1]["cmd"][-1] == "Я вдома"


@pytest.mark.asyncio
async def test_hyprland_opens_links_with_xdg_open_and_refuses_files(ran, hyprland):
    ok, _, _ = await desktop_hands.execute("pc.open", {"url": "https://phantom-os.dev"})
    assert ok and ran[-1]["cmd"] == ["xdg-open", "https://phantom-os.dev"]
    ok, _, _ = await desktop_hands.execute("pc.open", {"url": "/home/kyrylo/evil.desktop"})
    assert not ok
    assert len(ran) == 1


@pytest.mark.asyncio
async def test_hyprland_lock_asks_hypridle_when_it_runs(ran, hyprland, monkeypatch):
    monkeypatch.setattr(desktop_hands, "_is_running", lambda name: name == "hypridle")
    ok, _, _ = await desktop_hands.execute("pc.lock", {})
    assert ok and ran[-1]["cmd"] == ["loginctl", "lock-session"]


@pytest.mark.asyncio
async def test_hyprland_lock_starts_hyprlock_without_hypridle(ran, hyprland, monkeypatch):
    monkeypatch.setattr(desktop_hands, "_is_running", lambda name: False)
    ok, _, _ = await desktop_hands.execute("pc.lock", {})
    assert ok
    assert ran[-1] == {"cmd": ["hyprlock"], "how": "spawn"}


@pytest.mark.asyncio
async def test_hyprland_types_through_wayland_input(ran, hyprland):
    ok, _, _ = await desktop_hands.execute("pc.type", {"text": "Слава"})
    assert ok and hyprland == ["Слава"]


@pytest.mark.asyncio
async def test_missing_tool_is_said_in_human_words(ran, monkeypatch):
    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setenv("WAYLAND_DISPLAY", "wayland-1")
    monkeypatch.setattr(desktop_hands.shutil, "which", lambda name: None)
    ok, detail, _ = await desktop_hands.execute("pc.clipboard", {"text": "x"})
    assert not ok
    assert "wl-copy" not in detail and "xclip" not in detail
    assert "буфер" in detail.lower()


# ── Пульс ────────────────────────────────────────────────────────────────────


def test_windows_pulse_is_not_empty(monkeypatch):
    monkeypatch.setattr(sys, "platform", "win32")
    monkeypatch.setattr(desktop_body, "_win_battery", lambda: (57, True))
    monkeypatch.setattr(desktop_body, "_win_network", lambda: "wifi")
    monkeypatch.setattr(desktop_body, "_win_foreground", lambda: "Документ — Word")
    pulse = desktop_body.pulse()
    assert pulse == {
        "battery_pct": 57,
        "charging": True,
        "network": "wifi",
        "foreground": "Документ — Word",
    }


def test_windows_network_kind_from_adapter_names(monkeypatch):
    class Stat:
        def __init__(self, up):
            self.isup = up

    fake = type("P", (), {})()
    fake.net_if_stats = lambda: {
        "Loopback Pseudo-Interface 1": Stat(True),
        "Wi-Fi": Stat(True),
        "Ethernet": Stat(False),
    }
    monkeypatch.setitem(sys.modules, "psutil", fake)
    assert desktop_body._win_network() == "wifi"
    fake.net_if_stats = lambda: {"Ethernet 2": Stat(True), "Wi-Fi": Stat(True)}
    assert desktop_body._win_network() == "ethernet"
    fake.net_if_stats = lambda: {"Wi-Fi": Stat(False)}
    assert desktop_body._win_network() == "offline"
