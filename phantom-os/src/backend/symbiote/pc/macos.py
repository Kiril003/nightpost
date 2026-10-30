"""macOS — заглушка. Чесно: тіло ПК на Mac поки бачить лише процеси й
відкриває посилання (`open`); решта кидає PcUnsupported з поясненням.

Що потрібно для повного тіла: знімок — `screencapture -x` (дозвіл Screen
Recording), вікна — Quartz `CGWindowListCopyWindowInfo`, клавіатура й миша —
`CGEventPost` (дозвіл Accessibility), доступність — AXUIElement, буфер —
`pbpaste` (+ `org.nspasteboard.ConcealedType` від менеджерів паролів).
"""
from __future__ import annotations

import asyncio

from symbiote.pc.backend import OsBackend, PcUnsupported


class MacBackend(OsBackend):
    name = "macos"

    def capabilities(self) -> dict[str, bool]:
        return {"processes": True, "open_url": True}

    async def open_url(self, url: str) -> None:
        try:
            proc = await asyncio.create_subprocess_exec(
                "open", url, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
            )
        except FileNotFoundError as exc:
            raise PcUnsupported("немає програми open") from exc
        await asyncio.wait_for(proc.wait(), timeout=6.0)


__all__ = ["MacBackend"]
