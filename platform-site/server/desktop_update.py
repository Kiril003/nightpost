"""Автооновлення PHANTOM для ПК (Tauri updater) через сервер ліцензій.

Оболонка ПК (phantom-os/src/frontend/src-tauri) питає
`GET /api/desktop/update/{target}/{arch}/{current_version}` і отримує або 204
(«нічого новішого»), або маніфест динамічного сервера Tauri:

    {"version", "notes", "pub_date", "url", "signature"}

Довіра тримається НЕ на цьому сервері: `signature` — підпис minisign самого
файла ключем оновлень, публічна половина якого вшита в оболонку під час
збирання. Підмінений сервер чи бакет може хіба що не дати оновлення; чужий
файл оболонка не поставить. `url` — підписане посилання на бакет, живе години,
тому видається на кожен запит, а не зберігається в маніфесті.

Реліз публікує власник: `POST /admin/desktop/release` (X-Admin-Token) з
версією, нотатками і для кожної платформи — ім'ям файла в бакеті та вмістом
`.sig`, який `tauri build` кладе поруч з артефактом.
"""
from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from pathlib import Path

#: Платформи, які збирає наш конвеєр: ключ — `{target}-{arch}` так, як його
#: підставляє Tauri у `{{target}}`/`{{arch}}`.
PLATFORMS = ("linux-x86_64", "windows-x86_64")

_SEMVER = re.compile(r"^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$")


def parse_version(raw: str) -> tuple | None:
    m = _SEMVER.match(raw.strip())
    if not m:
        return None
    major, minor, patch, pre = m.groups()
    # Збірка без суфікса новіша за будь-який передреліз тієї ж версії.
    return (int(major), int(minor), int(patch), 0 if pre else 1, pre or "")


def problem(release: dict) -> str | None:
    """Чому реліз НЕ можна публікувати. Напівописаний реліз гірший за жодний:
    оболонка завантажила б файл, якому нема з чим звірити підпис."""
    if parse_version(str(release.get("version", ""))) is None:
        return "version must be semver, e.g. 0.21.0"
    platforms = release.get("platforms") or {}
    if not platforms:
        return "at least one platform is required"
    for key, entry in platforms.items():
        if key not in PLATFORMS:
            return f"unknown platform {key!r}; expected one of {PLATFORMS}"
        filename = str(entry.get("filename", ""))
        if not filename or "/" in filename or "\\" in filename or filename.startswith("."):
            return f"{key}: filename must be a bare object name"
        signature = str(entry.get("signature", "")).strip()
        # Вміст .sig від `tauri signer` — base64 тексту minisign; без нього
        # оболонка відмовиться ставити файл, тож і публікувати нема що.
        if len(signature) < 80:
            return f"{key}: signature is missing or too short (paste the .sig file content)"
    return None


class ReleaseStore:
    def __init__(self, path: Path):
        self.path = path

    def load(self) -> dict | None:
        if not self.path.is_file():
            return None
        try:
            return json.loads(self.path.read_text(encoding="utf-8"))
        except ValueError:
            return None

    def save(self, release: dict) -> dict:
        stored = {
            "version": str(release["version"]).strip(),
            "notes": str(release.get("notes") or "").strip(),
            "pub_date": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
            "platforms": {
                key: {"filename": str(v["filename"]), "signature": str(v["signature"]).strip()}
                for key, v in release["platforms"].items()
            },
        }
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(stored, ensure_ascii=False, sort_keys=True), encoding="utf-8")
        tmp.replace(self.path)
        return stored


def answer(release: dict | None, target: str, arch: str, current: str, presign) -> dict | None:
    """Маніфест для цієї платформи або None (→ 204).

    `presign(filename) -> url | None` — підписане посилання на бакет."""
    if not release:
        return None
    entry = (release.get("platforms") or {}).get(f"{target}-{arch}")
    if not entry:
        return None
    offered = parse_version(release.get("version", ""))
    installed = parse_version(current)
    # Невідома встановлена версія — не пропонуємо нічого: краще пропустити одне
    # оновлення, ніж поставити старіше поверх новішої збірки з невідомим номером.
    if offered is None or installed is None or offered <= installed:
        return None
    url = presign(entry["filename"])
    if not url:
        return None
    return {
        "version": release["version"],
        "notes": release.get("notes", ""),
        "pub_date": release.get("pub_date"),
        "url": url,
        "signature": entry["signature"],
    }
