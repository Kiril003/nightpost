"""Підпис посилання на збірку.

Перевірити повністю можна лише проти справжнього R2, тож тут ловимо те, що
ламається мовчки: втрачений параметр, підпис, що не залежить від ключа, і
«успіх» при ненастроєному сховищі.
"""
import sys
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))


@pytest.fixture()
def r2(monkeypatch):
    monkeypatch.setenv("R2_ACCOUNT_ID", "acc123")
    monkeypatch.setenv("R2_ACCESS_KEY_ID", "AKIAEXAMPLE")
    monkeypatch.setenv("R2_SECRET_ACCESS_KEY", "secret-half")
    monkeypatch.setenv("R2_BUCKET", "phantom-builds")
    monkeypatch.setenv("R2_BUILDS", "android:builds/phantom-1.0.apk,linux:builds/phantom-1.0.AppImage")
    sys.modules.pop("storage", None)
    import storage

    return storage


def test_ненастроєне_сховище_не_вдає_посилання(monkeypatch):
    for name in ("R2_ACCOUNT_ID", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_BUCKET"):
        monkeypatch.delenv(name, raising=False)
    sys.modules.pop("storage", None)
    import storage

    assert storage.configured() is False
    assert storage.presign("builds/x.apk") is None
    assert storage.link_for("android") is None
    assert storage.platforms() == []


def test_посилання_має_всі_обов_язкові_поля(r2):
    url = r2.presign("builds/phantom-1.0.apk", ttl=120)
    parsed = urlparse(url)
    query = parse_qs(parsed.query)

    assert parsed.netloc == "acc123.r2.cloudflarestorage.com"
    assert parsed.path == "/phantom-builds/builds/phantom-1.0.apk"
    assert query["X-Amz-Algorithm"] == ["AWS4-HMAC-SHA256"]
    assert query["X-Amz-Expires"] == ["120"]
    assert query["X-Amz-SignedHeaders"] == ["host"]
    assert query["X-Amz-Credential"][0].startswith("AKIAEXAMPLE/")
    assert query["X-Amz-Credential"][0].endswith("/auto/s3/aws4_request")
    assert len(query["X-Amz-Signature"][0]) == 64


def test_підпис_залежить_від_секрета_і_від_шляху(r2, monkeypatch):
    first = parse_qs(urlparse(r2.presign("builds/a.apk")).query)["X-Amz-Signature"][0]
    other_path = parse_qs(urlparse(r2.presign("builds/b.apk")).query)["X-Amz-Signature"][0]
    assert first != other_path

    monkeypatch.setenv("R2_SECRET_ACCESS_KEY", "інша-половина")
    other_secret = parse_qs(urlparse(r2.presign("builds/a.apk")).query)["X-Amz-Signature"][0]
    assert first != other_secret


def test_посилання_віддає_apk_а_не_архів(r2):
    """Без цих двох параметрів R2 віддає байти без типу, браузер бачить PK і
    зберігає архів — телефон такого не встановлює."""
    query = parse_qs(urlparse(r2.presign("builds/phantom-1.0.apk")).query)

    assert query["response-content-type"] == ["application/vnd.android.package-archive"]
    assert query["response-content-disposition"] == ['attachment; filename="phantom-1.0.apk"']
    # Підписані нарівні з рештою: непідписаний параметр R2 відкидає цілим запитом.
    assert "response-content-type" in urlparse(r2.presign("builds/a.apk")).query


def test_посилання_на_викладення_це_put_і_без_response_параметрів(r2):
    """PUT підписується тим самим кодом, що й GET, — тож звіряємо, що метод
    справді інший, а не що ми підписали GET і назвали його викладенням."""
    put = r2.presign_put("builds/new.apk", ttl=900)
    query = parse_qs(urlparse(put).query)

    assert query["X-Amz-Expires"] == ["900"]
    assert "response-content-type" not in query
    # Підпис GET і PUT на той самий ключ не може збігтись: метод у canonical request.
    get = parse_qs(urlparse(r2.presign("builds/new.apk", ttl=900)).query)
    assert query["X-Amz-Signature"] != get["X-Amz-Signature"]


def test_тип_береться_з_розширення(r2):
    assert r2.content_type_for("phantom.apk") == "application/vnd.android.package-archive"
    assert r2.content_type_for("phantom.exe") == "application/vnd.microsoft.portable-executable"
    assert r2.content_type_for("phantom.AppImage") == "application/x-iso9660-appimage"
    assert r2.content_type_for("noextension") == "application/octet-stream"


def test_платформа_без_збірки_це_none_а_не_виняток(r2):
    assert r2.link_for("windows") is None
    url, filename = r2.link_for("android")
    assert filename == "phantom-1.0.apk"
    assert url.startswith("https://")
    assert r2.platforms() == ["android", "linux"]


def test_шлях_ключа_підпису_мусить_бути_абсолютним():
    """Ключ підпису на ВІДНОСНОМУ шляху живе всередині контейнера й помирає з
    кожним викладенням — разом з усіма виданими сертифікатами. Саме так було
    втрачено ключ 3180ce6c 13.08.2026: змінна вказувала на `keys/signing.pem`,
    тобто на /app, а том лишався незайманим.
    """
    from pathlib import Path

    docker = (SERVER_DIR / "Dockerfile").read_text(encoding="utf-8")
    line = next(l for l in docker.splitlines() if "LICENSE_SIGNING_KEY" in l)
    path = line.split("LICENSE_SIGNING_KEY=", 1)[1].strip().rstrip("\\").strip()
    assert Path(path).is_absolute(), f"шлях {path!r} відносний — ключ не переживе викладення"
    assert path.startswith("/data/"), f"шлях {path!r} не на томі"


def test_посилання_переживає_завантаження_на_поганій_мережі():
    """TTL і вага збірки — одна пара чисел, і саме вона розійшлась мовчки:
    п'ять хвилин на 156 МБ. Чистого з'єднання це не ламало (R2 звіряє підпис у
    мить запиту, тож почате GET доходить), тому й не помічалось; ламало
    ВІДНОВЛЕННЯ після обриву — а погана мережа це і є обрив. Тест тримає обидва
    числа разом, щоб наступне зростання APK не проїхало знову.
    """
    import storage

    needed = storage.LARGEST_BUILD_BYTES * 8 / storage.SLOWEST_LINK_BITS_PER_SEC
    assert storage.LINK_TTL_SECONDS >= needed, (
        f"{storage.LINK_TTL_SECONDS} с не вистачить: "
        f"{storage.LARGEST_BUILD_BYTES / 1e6:.0f} МБ на "
        f"{storage.SLOWEST_LINK_BITS_PER_SEC / 1000:.0f} кбіт/с — це {needed / 3600:.1f} год"
    )


def test_вага_збірки_в_коді_не_відстала_від_справжньої():
    """Число вище — не пам'ятка, а вимір. Якщо релізний APK лежить поруч, воно
    мусить збігатися з ним: інакше запобіжник охороняв би вчорашню вагу."""
    import storage

    apk = (
        SERVER_DIR.parents[1]
        / "phantom-companion/app/build/outputs/apk/field/release"
        / "app-field-arm64-v8a-release.apk"
    )
    if not apk.exists():
        pytest.skip("релізного APK немає — міряти нічого")
    actual = apk.stat().st_size
    assert actual <= storage.LARGEST_BUILD_BYTES, (
        f"збірка виросла до {actual / 1e6:.0f} МБ, а код рахує "
        f"{storage.LARGEST_BUILD_BYTES / 1e6:.0f} МБ — TTL перерахувати"
    )
