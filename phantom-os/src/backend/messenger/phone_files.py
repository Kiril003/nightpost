"""Байти вкладень між ПК-вузлом і телефоном — шматками PH-FCH тією самою дорогою, що й слова.

Телефон возить фото, файл, голосове й кружок купкою звичайних листів: опис
(JSON веба з ключем) і N шматків по 24 КіБ шифротексту (`PeerFile.kt`). ПК досі
опис показував «відкривається на телефоні», а шматки лишав слідом — байтів у
нього не було ніколи. Тут — обидва напрямки:

  * ВХІД: шматки складаються на диску поруч зі сховищем блобів; цілий файл
    стає блобом вузла (той самий, що віддає `/messenger/files/{id}`) лише коли
    зійшовся відбиток з опису. Шматок, що обігнав опис, чекає; опис, що обігнав
    шматки, теж — хто прийде другим, той і закриває.
  * ВИХІД: байти блоба, вже вивантаженого вебом, ріжуться на тіла листів.

Вузол байтів не розшифровує й не вміє: ключ їде в описі, а опис — наскрізним
конвертом пари. Тут лише перевезення.
"""
from __future__ import annotations

import json
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from messenger import blobs
from node import peer_frames as pf

__all__ = ["Assembled", "PartStore", "bodies_for"]


@dataclass(frozen=True)
class Assembled:
    blob_id: str
    size: int
    sha256: str


class PartStore:
    """Шматки на диску: `<blobs>/.parts/<blob_id>/<i>` і `meta.json` з описом."""

    def __init__(self, root: Optional[Path] = None) -> None:
        self._root = root

    def _dir(self, blob_id: str) -> Path:
        blobs.blob_path(blob_id)  # та сама перевірка імені, що й для блоба
        base = self._root or (blobs.blob_dir() / ".parts")
        return base / blob_id

    def _meta(self, blob_id: str) -> dict:
        path = self._dir(blob_id) / "meta.json"
        try:
            return json.loads(path.read_text())
        except (FileNotFoundError, ValueError):
            return {}

    def _write_meta(self, blob_id: str, meta: dict) -> None:
        d = self._dir(blob_id)
        d.mkdir(parents=True, exist_ok=True)
        (d / "meta.json").write_text(json.dumps(meta))

    def expect(self, att: pf.Attachment) -> Optional[Assembled]:
        """Опис приїхав: запам'ятати відбиток. Якщо шматки вже всі тут — закрити."""
        if blobs.read_bytes(att.blob_id) is not None:
            return None
        meta = self._meta(att.blob_id)
        meta.update({"sha256": att.sha256.lower(), "size": att.size})
        self._write_meta(att.blob_id, meta)
        return self._close(att.blob_id)

    def take(self, chunk: pf.Chunk) -> Optional[Assembled]:
        """Шматок приїхав. Повертає блоб, щойно файл цілий і відбиток зійшовся."""
        if blobs.read_bytes(chunk.blob_id) is not None:
            return None  # уже складено — повтор шматка нічого не міняє
        meta = self._meta(chunk.blob_id)
        if meta.get("total") not in (None, chunk.total):
            return None  # інша кількість шматків під тим самим id — не наш файл
        meta["total"] = chunk.total
        self._write_meta(chunk.blob_id, meta)
        (self._dir(chunk.blob_id) / str(chunk.index)).write_bytes(chunk.data)
        return self._close(chunk.blob_id)

    def missing(self, blob_id: str) -> Optional[list[int]]:
        meta = self._meta(blob_id)
        total = meta.get("total")
        if not total:
            return None
        d = self._dir(blob_id)
        return [i for i in range(total) if not (d / str(i)).exists()]

    def _close(self, blob_id: str) -> Optional[Assembled]:
        meta = self._meta(blob_id)
        total, sha = meta.get("total"), meta.get("sha256")
        if not total or not sha:
            return None  # бракує або шматків-лічильника, або опису з відбитком
        d = self._dir(blob_id)
        parts = [d / str(i) for i in range(total)]
        if not all(p.exists() for p in parts):
            return None
        data = b"".join(p.read_bytes() for p in parts)
        if blobs.sha256_hex(data) != sha or (meta.get("size") and len(data) != meta["size"]):
            # Байти не ті, що обіцяв опис: не блоб, а сміття. Стерти, щоб
            # наступна копія від телефона складалась начисто.
            shutil.rmtree(d, ignore_errors=True)
            return None
        blobs.store_bytes(blob_id, data)
        shutil.rmtree(d, ignore_errors=True)
        return Assembled(blob_id, len(data), sha)


def bodies_for(att: pf.Attachment) -> list[str]:
    """Тіла листів-шматків для блоба, що вже лежить на вузлі. Порожньо — байтів немає."""
    data = blobs.read_bytes(att.blob_id)
    if not data:
        return []
    return pf.chunks_of(att.blob_id, data)
