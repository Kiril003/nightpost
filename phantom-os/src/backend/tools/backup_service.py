"""A-1: Encrypted Backup & Restore Service.

Bundles the entire PHANTOM_DATA_DIR and .env into a zip archive, encrypts it
using the existing CRYPTO-1 Fernet key, and allows restoration.
"""
import io
import os
import shutil
import sys
import zipfile
from pathlib import Path
from typing import Optional

from cryptography.fernet import InvalidToken

from config import config
from paths import REPO_ROOT, data_root
from security.crypto import _fernet

# Track A-1: configurable paths for easier testing
ENV_FILE_PATH = REPO_ROOT / ".env"

#: Файли, яких у резервній копії бути не сміє. Стан конверта v5 (RELAY_FS.md
#: §11): пряма секретність тримається на тому, що приватний OPK стирається
#: після листа; копія, зроблена ДО листа, тримала б його й надалі — і разом
#: із записаною копією зі сховку відчиняла б «стертий» лист. Після відновлення
#: вузол починає v5 з чистого стану й роздає нові пачки.
_NEVER_BACKED_UP = {"messenger_relay_fs.bin", "messenger_relay_fs.bin.tmp"}


def create_backup() -> bytes:
    """Create an encrypted zip of the data directory and .env."""
    # `data_root()`, а не `paths._root_dir`: той став змінною (= REPO_ROOT),
    # і виклик `_root_dir()` кидав TypeError — кожне завантаження бекапу
    # закінчувалось 500, відновлення — так само.
    data_dir = data_root()
    env_file = ENV_FILE_PATH

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        # Add .env if it exists
        if env_file.exists():
            zf.write(env_file, arcname=".env")

        # Add data directory recursively
        if data_dir.exists():
            for root, dirs, files in os.walk(data_dir):
                for file in files:
                    file_path = Path(root) / file
                    # Skip socket files, pipes, etc. just in case
                    if file_path.is_file() and file not in _NEVER_BACKED_UP:
                        arcname = file_path.relative_to(data_dir.parent)
                        zf.write(file_path, arcname=str(arcname))

    zip_bytes = buf.getvalue()
    # Encrypt the entire zip bytes
    # Fernet handles bytes directly
    encrypted_bytes = _fernet().encrypt(zip_bytes)
    return encrypted_bytes


async def restore_backup(encrypted_bytes: bytes) -> None:
    """Decrypt and extract a backup, overwriting current data.
    
    This function drops DB connections, extracts the files, and forcefully
    restarts the backend process to pick up the new database and .env.
    """
    try:
        zip_bytes = _fernet().decrypt(encrypted_bytes)
    except InvalidToken as exc:
        raise ValueError("Invalid or corrupted backup file (decryption failed).") from exc

    # Ensure it's a valid zip
    buf = io.BytesIO(zip_bytes)
    try:
        with zipfile.ZipFile(buf, "r") as zf:
            if zf.testzip() is not None:
                raise ValueError("Corrupted zip archive inside backup.")
    except zipfile.BadZipFile as exc:
        raise ValueError("Not a valid zip archive.") from exc

    # 1. Dispose DB engine to release SQLite locks
    from db.database import engine
    await engine.dispose()

    # 2. Re-open zip to extract
    buf.seek(0)
    with zipfile.ZipFile(buf, "r") as zf:
        for member in zf.infolist():
            # Security: prevent path traversal in zip
            if ".." in member.filename or member.filename.startswith("/"):
                continue

            if member.filename == ".env":
                target_path = ENV_FILE_PATH
            else:
                # member.filename looks like ".phantom-data/chroma/..." or "data/..."
                # Extract relative to data_dir.parent so it overwrites correctly
                target_path = data_root().parent / member.filename

            target_path.parent.mkdir(parents=True, exist_ok=True)
            if not member.is_dir():
                with zf.open(member) as source, open(target_path, "wb") as target:
                    shutil.copyfileobj(source, target)

    # 3. Graceful restart (execv replaces the current process)
    # Use sys.executable and sys.argv to restart uvicorn/python
    os.execv(sys.executable, [sys.executable] + sys.argv)
