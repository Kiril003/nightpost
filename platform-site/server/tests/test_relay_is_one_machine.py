"""Сховок і живий канал тримаються на ОДНІЙ машині — і викладення це береже.

Сховок — SQLite на томі машини, підписки живого каналу — мапа в пам'яті її
процесу. Друга машина Fly мала б свій том і свою мапу: лист, покладений через
одну, не існував би для забору й підписки через іншу. Жодна помилка при цьому
не з'явилась би — просто половина листів перестала б доходити.
"""
import re
import tomllib
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parents[1]
FLY = tomllib.loads((SERVER_DIR / "fly.toml").read_text(encoding="utf-8"))
DEPLOY = (SERVER_DIR.parent / "scripts" / "deploy-server.sh").read_text(encoding="utf-8")
DOCKERFILE = (SERVER_DIR / "Dockerfile").read_text(encoding="utf-8")


def test_машина_не_засинає_і_не_множиться():
    http = FLY["http_service"]
    assert http["auto_stop_machines"] in (False, "off"), "сон машини рве кожен живий канал"
    assert http["min_machines_running"] == 1
    assert "max_machines_running" not in http or http["max_machines_running"] == 1


def test_стеля_зєднань_не_типові_двадцять_пять():
    concurrency = FLY["http_service"].get("concurrency")
    assert concurrency, "без [http_service.concurrency] Fly тримає 25 з'єднань на машину"
    assert concurrency["hard_limit"] >= 500
    assert concurrency["soft_limit"] <= concurrency["hard_limit"]


def test_викладення_не_створює_другу_машину_й_зупиняється_на_двох():
    deploys = re.findall(r'"\$FLY" deploy[^\n]*', DEPLOY)
    assert deploys and all("--ha=false" in d for d in deploys), deploys
    guard_calls = [line for line in DEPLOY.splitlines() if line.strip() == "one_machine_or_die"]
    assert len(guard_calls) >= 2, "перевірка числа машин мусить стояти до і після викладення"
    assert "scale count 1" in DEPLOY


def test_перевірка_сховку_не_виводить_машину_з_обігу():
    checks = FLY.get("checks", {})
    assert any(c.get("path") == "/relay/v2/health" for c in checks.values())
    routed = [c["path"] for c in FLY["http_service"].get("checks", [])]
    assert "/relay/v2/health" not in routed


def test_кадр_живого_каналу_має_стелю():
    m = re.search(r"--ws-max-size (\d+)", DOCKERFILE)
    assert m, "типова стеля uvicorn — 16 МБ на кадр"
    biggest_json_drop = 4 * ((69660 + 2) // 3) + 200
    assert biggest_json_drop < int(m.group(1)) <= 1024 * 1024
