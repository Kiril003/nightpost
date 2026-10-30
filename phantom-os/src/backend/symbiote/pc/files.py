"""Файли тіла ПК: лише в теках, які схвалив власник, і з версіями.

* Корені (`roots`) додає й прибирає лише людина за ПК (маршрут
  `/symbiote/pc/roots`). Без жодного кореня файлова сфера чесно каже
  «немає схвалених тек», а не шукає по всьому диску.
* Шлях розв'язується до справжнього (символьні посилання розкриті) і мусить
  лежати всередині кореня — посилання «назовні» не вибирається.
* Сторож (`guard.secret_path`) закриває ключі, сховища паролів, .env і дані
  самого PHANTOM навіть усередині схваленої теки.
* Перед перезаписом, переміщенням поверх і видаленням — копія у сховищі
  версій (`<data>/symbiote/pc_backups`), з журналом `index.jsonl`.
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import tempfile
import threading
import time
from pathlib import Path
from typing import Any, Optional

from symbiote.pc.backend import PcRefused
from symbiote.pc.guard import secret_path

READ_CAP = 256 * 1024
SEARCH_RESULTS = 200
SEARCH_VISIT = 50_000
CONTENT_CAP = 1024 * 1024
DELETE_DIR_CAP = 200 * 1024 * 1024


def _symbiote_dir() -> Path:
    from paths import data_root

    return data_root() / "symbiote"


class Roots:
    """Схвалені теки. Файл у даних PHANTOM — туди сам розум писати не може."""

    def __init__(self, path: Optional[Path] = None) -> None:
        self._path = path
        self._lock = threading.Lock()

    def _file(self) -> Path:
        return self._path or (_symbiote_dir() / "pc_roots.json")

    def use_path(self, path: Path) -> None:
        self._path = path

    def list(self) -> list[Path]:
        try:
            data = json.loads(self._file().read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return []
        return [Path(p) for p in data.get("roots", []) if isinstance(p, str)]

    def _save(self, roots: list[Path]) -> None:
        path = self._file()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"roots": [str(r) for r in roots]}, ensure_ascii=False), encoding="utf-8")

    def add(self, raw: str) -> Path:
        root = Path(raw).expanduser().resolve()
        if not root.is_dir():
            raise PcRefused(f"теки «{raw}» немає")
        if root == Path(root.anchor):
            raise PcRefused("увесь диск схвалити не можна — обери теку")
        why = secret_path(root)
        if why:
            raise PcRefused(why)
        from symbiote.pc.guard import _data_root

        data = _data_root()
        if data is not None and (data == root or root in data.parents):
            raise PcRefused("тека містить дані самого PHANTOM — обери вужчу")
        with self._lock:
            roots = [r for r in self.list() if r != root]
            roots.append(root)
            self._save(roots)
        return root

    def remove(self, raw: str) -> bool:
        root = Path(raw).expanduser().resolve()
        with self._lock:
            roots = self.list()
            kept = [r for r in roots if r != root]
            self._save(kept)
        return len(kept) != len(roots)


roots = Roots()


def resolve(raw: str, *, must_exist: bool = True) -> Path:
    """Шлях користувача → справжній шлях усередині схваленого кореня."""
    allowed = roots.list()
    if not allowed:
        raise PcRefused("власник ще не схвалив жодної теки (POST /symbiote/pc/roots)")
    if not raw:
        raise PcRefused("не вказано шлях")
    candidate = Path(raw).expanduser()
    if not candidate.is_absolute():
        candidate = allowed[0] / candidate
    real = candidate.resolve(strict=False)
    inside = next((r for r in allowed if real == r or r in real.parents), None)
    if inside is None:
        raise PcRefused("це поза схваленими теками")
    why = secret_path(real)
    if why:
        raise PcRefused(why)
    if must_exist and not real.exists():
        raise PcRefused("такого файла немає")
    return real


# ── Версії ──────────────────────────────────────────────────────────────────


class Backups:
    def __init__(self, base: Optional[Path] = None) -> None:
        self._base = base
        self._lock = threading.Lock()

    def base(self) -> Path:
        return self._base or (_symbiote_dir() / "pc_backups")

    def use_path(self, base: Path) -> None:
        self._base = base

    def _slot(self, path: Path) -> Path:
        return self.base() / hashlib.sha256(str(path).encode()).hexdigest()[:20]

    def keep(self, path: Path, reason: str) -> Optional[dict[str, Any]]:
        """Копія теперішнього стану `path` (файл чи тека). Нічого нема — None."""
        if not path.exists():
            return None
        slot = self._slot(path)
        slot.mkdir(parents=True, exist_ok=True)
        stamp = time.strftime("%Y%m%dT%H%M%S") + f"-{time.time_ns() % 1_000_000:06d}"
        dest = slot / f"{stamp}__{path.name}"
        if path.is_dir():
            shutil.copytree(path, dest, symlinks=True)
            digest = ""
        else:
            shutil.copy2(path, dest, follow_symlinks=False)
            digest = _sha256(dest)
        row = {"path": str(path), "version": dest.name, "at": time.time(), "reason": reason, "sha256": digest}
        with self._lock:
            with (self.base() / "index.jsonl").open("a", encoding="utf-8") as fh:
                fh.write(json.dumps(row, ensure_ascii=False) + "\n")
        return row

    def versions(self, path: Path) -> list[str]:
        slot = self._slot(path)
        if not slot.is_dir():
            return []
        return sorted(p.name for p in slot.iterdir())

    def restore(self, path: Path, version: str) -> Path:
        src = self._slot(path) / version
        if not src.exists() or "/" in version or version.startswith("."):
            raise PcRefused("такої версії немає")
        self.keep(path, "before_restore")
        if src.is_dir():
            if path.exists():
                shutil.rmtree(path)
            shutil.copytree(src, path, symlinks=True)
        else:
            shutil.copy2(src, path)
        return path


backups = Backups()


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


# ── Дієслова ────────────────────────────────────────────────────────────────


def _entry(p: Path) -> dict[str, Any]:
    try:
        st = p.lstat()
    except OSError:
        return {"name": p.name, "kind": "unknown"}
    kind = "link" if p.is_symlink() else ("dir" if p.is_dir() else "file")
    return {"name": p.name, "kind": kind, "size": st.st_size, "mtime": int(st.st_mtime)}


def list_dir(raw: str = "") -> dict[str, Any]:
    if not raw:
        return {"roots": [str(r) for r in roots.list()], "entries": [
            {"name": str(r), "kind": "dir"} for r in roots.list()
        ]}
    path = resolve(raw)
    if not path.is_dir():
        raise PcRefused("це не тека")
    entries, hidden = [], 0
    for child in sorted(path.iterdir(), key=lambda c: c.name.lower())[:1000]:
        if secret_path(child.resolve(strict=False)) or secret_path(child):
            hidden += 1
            continue
        entries.append(_entry(child))
    return {"path": str(path), "entries": entries, "hidden_secret": hidden}


def _is_text(path: Path) -> bool:
    try:
        with path.open("rb") as fh:
            head = fh.read(4096)
    except OSError:
        return False
    if b"\x00" in head:
        return False
    try:
        head.decode("utf-8")
        return True
    except UnicodeDecodeError as exc:
        return exc.start > len(head) - 4  # обрізаний багатобайтовий символ у кінці


def search(query: str, raw_path: str = "", content: bool = False) -> dict[str, Any]:
    q = query.strip().lower()
    if not q:
        raise PcRefused("нема що шукати")
    bases = [resolve(raw_path)] if raw_path else roots.list()
    if not bases:
        raise PcRefused("власник ще не схвалив жодної теки (POST /symbiote/pc/roots)")
    found: list[dict[str, Any]] = []
    visited = 0
    for base in bases:
        for dirpath, dirnames, filenames in os.walk(base, followlinks=False):
            here = Path(dirpath)
            dirnames[:] = [d for d in dirnames if not secret_path(here / d)]
            for name in filenames:
                visited += 1
                if visited > SEARCH_VISIT or len(found) >= SEARCH_RESULTS:
                    return {"query": query, "results": found, "truncated": True}
                p = here / name
                if secret_path(p):
                    continue
                hit = q in name.lower()
                line = None
                if not hit and content and p.is_file() and not p.is_symlink():
                    try:
                        if p.stat().st_size <= CONTENT_CAP and _is_text(p):
                            for i, text in enumerate(p.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
                                if q in text.lower():
                                    hit, line = True, {"line": i, "text": text.strip()[:200]}
                                    break
                    except OSError:
                        continue
                if hit:
                    found.append({"path": str(p), **({"match": line} if line else {})})
    return {"query": query, "results": found, "truncated": False}


def read(raw: str) -> dict[str, Any]:
    path = resolve(raw)
    if path.is_dir():
        raise PcRefused("це тека — список дає «Що в теці»")
    if not _is_text(path):
        raise PcRefused("це не текстовий файл")
    size = path.stat().st_size
    with path.open("rb") as fh:
        blob = fh.read(READ_CAP)
    return {"path": str(path), "size": size, "truncated": size > READ_CAP,
            "text": blob.decode("utf-8", errors="replace")}


def write(raw: str, text: str, mode: str = "create") -> dict[str, Any]:
    mode = (mode or "create").lower()
    if mode not in ("create", "overwrite", "append"):
        raise PcRefused("режим запису: create, overwrite або append")
    path = resolve(raw, must_exist=False)
    if path.is_dir():
        raise PcRefused("це тека")
    existed = path.exists()
    if existed and mode == "create":
        raise PcRefused("файл уже є — щоб переписати, режим overwrite (стара версія збережеться)")
    kept = backups.keep(path, f"before_{mode}") if existed else None
    path.parent.mkdir(parents=True, exist_ok=True)
    resolve(str(path.parent))  # тека-батько теж мусить лишитися всередині кореня
    if mode == "append" and existed:
        with path.open("a", encoding="utf-8") as fh:
            fh.write(text)
    else:
        fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=".phantom-", suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                fh.write(text)
            os.replace(tmp, path)
        except BaseException:
            Path(tmp).unlink(missing_ok=True)
            raise
    return {"path": str(path), "bytes": len(text.encode("utf-8")), "mode": mode,
            "backup": kept["version"] if kept else None, "versions": len(backups.versions(path))}


def move(src_raw: str, dst_raw: str) -> dict[str, Any]:
    src = resolve(src_raw)
    dst = resolve(dst_raw, must_exist=False)
    if dst.exists() and dst.is_dir():
        dst = dst / src.name
        dst = resolve(str(dst), must_exist=False)
    if dst.exists():
        raise PcRefused("там уже є файл з такою назвою — спершу перейменуй")
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(src), str(dst))
    return {"from": str(src), "to": str(dst)}


def _tree_size(path: Path) -> int:
    total = 0
    for dirpath, _dirs, files in os.walk(path, followlinks=False):
        for f in files:
            try:
                total += (Path(dirpath) / f).lstat().st_size
            except OSError:
                pass
    return total


def delete(raw: str) -> dict[str, Any]:
    path = resolve(raw)
    if path in roots.list():
        raise PcRefused("схвалену теку цілком не видаляю — прибери її зі схвалених")
    if path.is_dir() and _tree_size(path) > DELETE_DIR_CAP:
        raise PcRefused("тека завелика, щоб зберегти копію перед видаленням")
    kept = backups.keep(path, "before_delete")
    if path.is_dir() and not path.is_symlink():
        shutil.rmtree(path)
    else:
        path.unlink()
    return {"path": str(path), "backup": kept["version"] if kept else None,
            "note": "копія лишилась у сховищі версій PHANTOM"}


__all__ = ["Roots", "roots", "Backups", "backups", "resolve", "list_dir", "search", "read", "write", "move", "delete"]
