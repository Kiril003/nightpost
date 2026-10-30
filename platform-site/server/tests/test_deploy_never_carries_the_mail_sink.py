"""Розробницька скринька не їде на прод — доведено прогоном скрипта, не оком.

`mailer.py:82`: коли `MAIL_DEV_SINK` має значення, лист пишеться У ФАЙЛ і
`_send` повертає успіх. На Fly той файл лежить в ефемерному контейнері, тож
бета-ключ або код входу зникає, а сервер відповідає `delivered: true` — збій,
який виглядає як успіх на всіх поверхнях одразу.

`deploy-server.sh` переносить у Fly КОЖЕН непорожній рядок `.env`, крім
перелічених. Отже межа мусить стояти саме там, і саме її тут перевіряють:
скрипт запускають із підробленим `fly`, який лише записує свої аргументи.
"""
from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "deploy-server.sh"

FAKE_FLY = """#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$FLY_LOG"
case "$1 $2" in
  "auth whoami") exit 0 ;;
  "status --app") exit 0 ;;
  "volumes list") echo phantom_license_data; exit 0 ;;
esac
exit 0
"""


def _run(env_body: str, tmp_path: Path) -> list[str]:
    """Проганяє справжній скрипт у власному дереві з підробленим fly."""
    root = tmp_path / "site"
    (root / "scripts").mkdir(parents=True)
    (root / "server").mkdir()
    shutil.copy(SCRIPT, root / "scripts" / SCRIPT.name)
    (root / "server" / ".env").write_text(env_body, encoding="utf-8")

    fly = tmp_path / "fly"
    fly.write_text(FAKE_FLY, encoding="utf-8")
    fly.chmod(0o755)
    log = tmp_path / "fly.log"

    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    curl = bin_dir / "curl"
    curl.write_text("#!/usr/bin/env bash\nexit 7\n", encoding="utf-8")
    curl.chmod(0o755)
    env = dict(os.environ, FLY_BIN=str(fly), FLY_LOG=str(log), PATH=f"{bin_dir}:{os.environ['PATH']}")
    proc = subprocess.run(
        ["bash", str(root / "scripts" / SCRIPT.name)],
        capture_output=True, text=True, env=env, timeout=60,
    )
    assert proc.returncode == 0, proc.stderr or proc.stdout
    return log.read_text(encoding="utf-8").splitlines() if log.exists() else []


def test_скринька_розробника_не_потрапляє_у_викладення(tmp_path):
    calls = _run(
        "SMTP_HOST=smtp.example.com\nMAIL_DEV_SINK=/tmp/letters.txt\nADMIN_TOKEN=t\n",
        tmp_path,
    )
    setting = " ".join(c for c in calls if c.startswith("secrets set"))
    assert "MAIL_DEV_SINK" not in setting, (
        "MAIL_DEV_SINK поїхав у Fly — на проді листи писались би у файл "
        f"ефемерного контейнера. Аргументи: {setting}"
    )
    # Межа не має ставати глушником: решта секретів їде як їхала.
    assert "SMTP_HOST=smtp.example.com" in setting
    assert "ADMIN_TOKEN=t" in setting


def test_скринька_знімається_і_з_попереднього_викладення(tmp_path):
    calls = _run("MAIL_DEV_SINK=/tmp/letters.txt\nADMIN_TOKEN=t\n", tmp_path)
    unset = [c for c in calls if c.startswith("secrets unset")]
    assert any("MAIL_DEV_SINK" in c for c in unset), (
        "Скриньку могло занести попереднім викладенням, до цієї межі — "
        f"її треба знімати явно. Виклики: {calls}"
    )
    assert any("--stage" in c for c in unset), (
        "Зняття без --stage перезапускає застосунок посеред скрипта, "
        "ще до викладення."
    )


def test_порожня_скринька_не_піднімає_тривоги(tmp_path):
    """Порожнє значення нічого не вмикало й до межі — не вигадуємо причину."""
    calls = _run("MAIL_DEV_SINK=\nADMIN_TOKEN=t\n", tmp_path)
    assert not [c for c in calls if c.startswith("secrets unset")], (
        f"Порожня змінна не мала б нічого знімати. Виклики: {calls}"
    )
