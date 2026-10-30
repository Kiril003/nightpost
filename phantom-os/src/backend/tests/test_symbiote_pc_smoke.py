"""Справжній прогін тіла ПК на Linux — лише за явною згодою: `PHANTOM_PC_SMOKE=1`.

Відкриває текстовий редактор, набирає рядок справжньою клавіатурою (wtype /
xdotool / ydotool — та сама рука `pc.type`, зі сторожем) і читає його назад
через дерево доступності (AT-SPI, рука `pc.ui.find`). Друкує лише в редактор,
який сам відкрив, і лише коли саме він у фокусі.

    PHANTOM_PC_SMOKE=1 JWT_SECRET_KEY=test .venv/bin/python -m pytest -q tests/test_symbiote_pc_smoke.py
"""
from __future__ import annotations

import asyncio
import os
import shutil
import sys
import time
import uuid
from pathlib import Path

import pytest

pytestmark = [
    pytest.mark.real_pc_body,
    pytest.mark.skipif(os.environ.get("PHANTOM_PC_SMOKE") != "1", reason="справжній ПК — лише з PHANTOM_PC_SMOKE=1"),
    pytest.mark.skipif(not sys.platform.startswith("linux"), reason="Linux-прогін"),
    pytest.mark.skipif(not (os.environ.get("WAYLAND_DISPLAY") or os.environ.get("DISPLAY")), reason="немає графічної сесії"),
]

EDITOR = Path(__file__).parent / "fixtures" / "phantom_scratch_editor.py"
KNOWN = (  # (програма, ім'я застосунку в AT-SPI)
    ("gnome-text-editor", "gnome-text-editor"), ("gedit", "gedit"), ("mousepad", "mousepad"),
    ("xed", "xed"), ("pluma", "pluma"), ("featherpad", "featherpad"),
)


def _editor() -> tuple[list[str], str]:
    for binary, app in KNOWN:
        if shutil.which(binary):
            return [binary, "--new-window"] if binary in ("gedit", "gnome-text-editor") else [binary], app
    return ["/usr/bin/python3", str(EDITOR), f"PHANTOM smoke {uuid.uuid4().hex[:6]}"], "phantom-smoke-editor"


@pytest.mark.asyncio
async def test_open_editor_type_a_line_and_read_it_back():
    from symbiote import desktop_hands
    from symbiote.pc import backend as pc_backend
    from symbiote.pc.hands import execute
    from symbiote.pc.linux import LinuxBackend

    linux = LinuxBackend()
    caps = linux.capabilities()
    if not (caps["type"] and caps["accessibility"]):
        pytest.skip(f"немає набору тексту чи AT-SPI: {caps}")
    pc_backend.use_backend(linux)
    argv, app = _editor()
    proc = await asyncio.create_subprocess_exec(
        *argv, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
        env={**os.environ, "NO_AT_BRIDGE": "0", "GTK_A11Y": "atspi"},
    )
    try:
        # Друкувати лише в свій редактор: чекаємо, доки саме він у фокусі.
        deadline = time.monotonic() + 15
        focused = False
        while time.monotonic() < deadline:
            await asyncio.sleep(0.3)
            try:
                win = await linux.active_window()
            except Exception:  # noqa: BLE001
                win = None
            if win is not None and (win.pid == proc.pid or app in (win.app or "").lower()
                                    or "phantom smoke" in (win.title or "").lower()):
                focused = True
                break
        if not focused:
            pytest.skip("редактор не отримав фокусу — не друкую в чуже вікно")

        line = f"PHANTOM бачить і пише {uuid.uuid4().hex[:8]}"
        ok, detail, _ = await desktop_hands.execute("pc.type", {"text": line})
        assert ok, detail

        found_text = ""
        for _ in range(20):
            await asyncio.sleep(0.3)
            ok, detail, payload = await execute("pc.ui.find", {"app": app, "role": "text"})
            assert ok, detail
            texts = [e.get("text") or "" for e in payload["elements"]]
            found_text = next((t for t in texts if line in t), "")
            if found_text:
                break
        assert line in found_text, f"не прочитав назад: {payload['elements'][:5]}"
    finally:
        pc_backend.use_backend(None)
        if proc.returncode is None:
            proc.terminate()
            try:
                await asyncio.wait_for(proc.wait(), timeout=5)
            except asyncio.TimeoutError:
                proc.kill()
