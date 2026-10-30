"""Пакет переїзду на Oracle (ops/oracle-server) не розходиться з кодом сервера і не губить секрети.

Контейнер на ВМ читає лише те, що лежить у server.env; змінна, якої там немає,
мовчки вмикає типове значення. Саме так кран тестових USDC відповідав би 503
після переїзду: ключа крана не було б ні в прикладі, ні в рунбуку.
"""
import os
import re
import subprocess
import sys
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))
OPS = SERVER_DIR.parent / "ops" / "oracle-server"
SCRIPTS = SERVER_DIR.parent / "scripts"
FIXED_BY_IMAGE = {
    "LICENSE_DB", "LICENSE_SIGNING_KEY", "LICENSE_POLICY", "RELAY_DB", "TRUTH_DB",
    "SITE_ORIGINS", "TRUSTED_COUNTRY_HEADER",
}
READS = re.compile(r"""(?:environ\.get|getenv|_int_env|environ\[)\(?\s*["']([A-Z][A-Z0-9_]+)["']""")


def _example_names() -> set[str]:
    return set(re.findall(r"^([A-Z][A-Z0-9_]*)=", (OPS / "server.env.example").read_text(encoding="utf-8"), re.M))


def _migrate() -> str:
    return (OPS / "MIGRATE.md").read_text(encoding="utf-8")


def test_кожна_змінна_середовища_сервера_є_в_прикладі_server_env():
    used: dict[str, str] = {}
    for path in sorted(SERVER_DIR.glob("*.py")):
        for name in READS.findall(path.read_text(encoding="utf-8")):
            used.setdefault(name, path.name)
    missing = {n: f for n, f in used.items() if n not in _example_names() and n not in FIXED_BY_IMAGE}
    assert not missing, f"сервер читає, а server.env.example не називає: {missing}"


def test_dump_env_пише_лише_те_що_compose_прочитає_буквально():
    env = {
        "PATH": os.environ.get("PATH", ""),
        "PLAIN": "abc",
        "DOLLAR": "pa$$word$HOME",
        "JSONISH": '{"k":"a\\nb"}',
        "HAS_QUOTE": "it's",
        "ENDS_SLASH": "abc\\",
        "MULTILINE": "a\nb",
        "EMPTY": "",
    }
    names = ["PLAIN", "DOLLAR", "JSONISH", "HAS_QUOTE", "ENDS_SLASH", "MULTILINE", "EMPTY", "UNSET"]
    done = subprocess.run(
        [sys.executable, str(OPS / "dump-env.py"), *names],
        env=env, capture_output=True, text=True, check=True,
    )
    assert done.stdout.splitlines() == [
        "PLAIN='abc'",
        "DOLLAR='pa$$word$HOME'",
        'JSONISH=\'{"k":"a\\nb"}\'',
    ]
    for name in ("HAS_QUOTE", "ENDS_SLASH", "MULTILINE"):
        assert name in done.stderr
    for value in ("it's", "abc\\"):
        assert value not in done.stderr


def test_ключ_крана_зі_скрипта_приймає_сервер_і_він_не_світиться_у_виводі(tmp_path):
    import sponsor
    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

    out = tmp_path / "keys" / "faucet.json"
    done = subprocess.run(
        [sys.executable, str(SCRIPTS / "faucet-key.py"), str(out)],
        capture_output=True, text=True, check=True,
    )
    text = out.read_text(encoding="utf-8")
    key = sponsor.load_key(text)
    assert key is not None
    address = sponsor.b58encode(key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw))
    assert address in done.stdout
    assert "," not in done.stdout and text not in done.stdout
    assert (out.stat().st_mode & 0o777) == 0o600
    assert (out.parent.stat().st_mode & 0o777) == 0o700
    again = subprocess.run(
        [sys.executable, str(SCRIPTS / "faucet-key.py"), str(out)], capture_output=True, text=True,
    )
    assert again.returncode == 0 and address in again.stdout and "kept" in again.stdout
    assert text not in again.stdout
    assert out.read_text(encoding="utf-8") == text


def test_рунбук_бере_адресу_бази_з_ipv4_пулера():
    text = _migrate()
    assert "Transaction pooler" in text and "6543" in text and "pooler.supabase.com" in text
    assert "ipv6" in text.lower()


def test_рунбук_не_кладе_секрети_у_командний_рядок():
    text = _migrate()
    assert not re.search(r"\bEDGE=\$\(", text), "EDGE живе лише на ВМ і в pipe до flyctl"
    assert "secrets/edge" in text and "secrets import" in text
    assert "EDGE_SECRET=\"" not in text


def test_рунбук_ховає_бекапи_і_вигрібає_експорт_з_fly():
    text = _migrate()
    assert re.search(r"chmod 700 [^'\n]*/backups", text)
    cron = [line for line in text.splitlines() if "crontab" in line and "export-data.py" in line]
    assert cron and all(re.search(r"\* \* \* umask 077; ", line) for line in cron), cron
    assert "rm -f /tmp/nightpost-data.tgz" in text
    assert "~/nightpost-backup" in text
    assert "scp nightpost-data.tgz" not in text and "get /tmp/nightpost-data.tgz ./" not in text


def test_рунбук_синхронізує_uid_контейнера_і_має_живий_ws_смок():
    text = _migrate()
    assert "NP_UID=$(id -u)" in text
    assert "ops/oracle-server/ws-smoke.py" in text
    smoke = (OPS / "ws-smoke.py").read_text(encoding="utf-8")
    assert "/relay/v2/live" in smoke and '"op"' in smoke


def test_викладення_перехідника_бачить_його_докерфайл_як_контекст():
    text = _migrate()
    deploy = [line for line in text.splitlines() if "flyctl deploy" in line and "fly-proxy" in line]
    assert deploy and all("cd ops/oracle-server/fly-proxy" in line for line in deploy), deploy
