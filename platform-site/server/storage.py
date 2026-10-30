"""Роздача збірок із Cloudflare R2 підписаним посиланням.

Підпис — AWS SigV4 у рядку запиту, руками: R2 говорить протоколом S3, а тягти
boto3 у сервер заради сорока рядків не варто. Посилання живе хвилини й видається
лише тому, у кого є чинна ліцензія, тож саме воно, а не файл, є контролем
доступу — бакет лишається закритим.

Env:
  R2_ACCOUNT_ID         — з адреси s3-ендпоїнта Cloudflare
  R2_ACCESS_KEY_ID      — R2 API token
  R2_SECRET_ACCESS_KEY  — він же, секретна половина
  R2_BUCKET             — назва бакета
  R2_BUILDS             — "android:builds/phantom-1.0.apk,windows:builds/phantom-1.0.exe"
"""
from __future__ import annotations

import hashlib
import hmac
import os
import time
import urllib.request
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from urllib.parse import quote

REGION = "auto"
#: APK ~156 МБ: на поганій мережі це години. Ламалось на ВІДНОВЛЕННІ — запит із
#: `Range:` після обриву це новий запит до простроченої адреси, тож на швидкій
#: мережі баг не відтворюється ніколи.
LINK_TTL_SECONDS = 43_200
#: Стоять поруч навмисно: одного разу вони розійшлися мовчки.
LARGEST_BUILD_BYTES = 156 * 1024 * 1024
SLOWEST_LINK_BITS_PER_SEC = 40_000
SIZE_TTL_SECONDS = 600
_PROBE_CACHE: dict[str, tuple[float, int | None, str | None]] = {}

# Тип віддає САМЕ посилання, а не запис у бакеті: об'єкт міг лягти туди
# будь-чим (R2 не вгадує тип), і тоді браузер бачить лише байти, що
# починаються на PK — тобто zip. Людина завантажує «архів із купою файлів»
# замість застосунку, бо APK і є zip; телефон не пропонує встановити.
CONTENT_TYPES = {
    ".apk": "application/vnd.android.package-archive",
    ".aab": "application/octet-stream",
    ".exe": "application/vnd.microsoft.portable-executable",
    ".appimage": "application/x-iso9660-appimage",
    ".dmg": "application/x-apple-diskimage",
    ".zip": "application/zip",
    ".webp": "image/webp",
    ".jpg": "image/jpeg",
    ".png": "image/png",
    ".avif": "image/avif",
}


def content_type_for(filename: str) -> str:
    dot = filename.rfind(".")
    return CONTENT_TYPES.get(filename[dot:].lower() if dot > 0 else "", "application/octet-stream")


def _env(name: str) -> str:
    """Читаємо щоразу, а не при імпорті: сервер піднімають раніше, ніж власник
    вписує ключі R2, і перезапуск заради цього не мав би бути обов'язковим."""
    return os.environ.get(name, "").strip()


def _builds() -> dict[str, str]:
    out: dict[str, str] = {}
    for pair in _env("R2_BUILDS").split(","):
        if ":" in pair:
            platform, key = pair.split(":", 1)
            out[platform.strip()] = key.strip()
    return out


def configured() -> bool:
    return all(
        _env(name)
        for name in ("R2_ACCOUNT_ID", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_BUCKET")
    )


def platforms() -> list[str]:
    return sorted(_builds()) if configured() else []


def filename_for(platform: str) -> str | None:
    """Ім'я файла, який сервер ВІДДАСТЬ для цієї платформи зараз, або None."""
    key = _builds().get(platform)
    return key.rsplit("/", 1)[-1] if key else None


def _http_date(raw: str | None) -> str | None:
    """RFC 7231 → ISO-8601 в UTC. Нерозібране — це None, а не «зараз»: дата,
    підставлена з годинника сервера, на картці виглядає точнісінько як справжня."""
    if not raw:
        return None
    try:
        stamp = parsedate_to_datetime(raw)
        if stamp.tzinfo is None:
            # `-0000` і дата зовсім без зони розбираються в НАЇВНИЙ datetime, а
            # наївний у astimezone мовчки береться за місцевий час машини —
            # дата на картці поїхала б на години разом із зоною сервера.
            stamp = stamp.replace(tzinfo=timezone.utc)
        return stamp.astimezone(timezone.utc).isoformat()
    except (AttributeError, TypeError, ValueError):
        return None


def probe(object_key: str) -> tuple[int | None, str | None]:
    """Скільки збірка важить і КОЛИ вона лягла в бакет — з одного запиту.

    None у будь-якому з двох — не дізнались; тоді кабінет числа не покаже, бо
    збрехати про 200 МБ мобільного трафіку (чи про вік файла) гірше, ніж
    змовчати. Відповідь тримається в пам'яті: об'єкт міняється раз на випуск.

    Дата — НЕ «коли зібрали», а коли САМЕ ЦІ байти лягли у сховище, і назвати
    її треба так само. Взято з `Last-Modified` того самого об'єкта, на який
    веде посилання, тому вона не може розійтися з тим, що людина завантажить:
    інший файл — інша дата, а жодна подія на сервері її не рухає. Три джерела,
    які напрошувалися ближче, ми відкинули, і кожне за виміряною причиною:

    * `issued_at` підписаної політики. Його піднімає КОЖНЕ відкликання й
      КОЖНЕ повернення грошей (`_republish_policy` у main.py) — один
      повернений платіж пересунув би «дату збірки» вперед, не змінивши
      жодного байта у файлі.
    * Час виклику `/admin/policy/build`. Скрипт публікації сам радить
      повторити себе після 409 («повторне вкладення того самого файла
      безпечне»), а сервер ще й перевидає політику на старті під
      DOWNLOAD_POLICY — обидва рухи дали б тому самому файлу свіжу дату.
    * Мітки часу всередині APK. Їх там немає: AGP заради відтворюваних
      збірок нормалізує мітки ВСІХ записів zip на 1981-01-01 01:01 —
      виміряно на app-field-arm64-v8a-release.apk, 1723 записи, жодного
      винятку. `META-INF/version-control-info.textproto` несе комміт, а не
      час, і комміт розходиться з байтами двічі: брудне дерево і
      перезбирання старого комміта сьогоднішнім днем.
    """
    now = time.monotonic()
    hit = _PROBE_CACHE.get(object_key)
    if hit and now - hit[0] < SIZE_TTL_SECONDS:
        return hit[1], hit[2]

    size: int | None = None
    published: str | None = None
    url = presign(object_key, ttl=60)
    if url:
        try:
            request = urllib.request.Request(url, headers={"Range": "bytes=0-0"})
            with urllib.request.urlopen(request, timeout=6) as response:
                total = response.headers.get("Content-Range", "").rsplit("/", 1)
                size = int(total[-1]) if len(total) == 2 and total[-1].isdigit() else None
                published = _http_date(response.headers.get("Last-Modified"))
        except Exception:
            size = None
            published = None
    _PROBE_CACHE[object_key] = (now, size, published)
    return size, published


def builds() -> list[dict[str, object]]:
    """Те, що кабінет показує людині ДО натискання: що це за файл, скільки
    важить і відколи лежить."""
    out = []
    for platform, key in sorted(_builds().items()):
        name = key.rsplit("/", 1)[-1]
        size, published = probe(key)
        out.append(
            {
                "platform": platform,
                "filename": name,
                "size": size,
                "published_at": published,
            }
        )
    return out


def _sign(key: bytes, message: str) -> bytes:
    return hmac.new(key, message.encode(), hashlib.sha256).digest()


def _signing_key(datestamp: str) -> bytes:
    key = _sign(f"AWS4{_env('R2_SECRET_ACCESS_KEY')}".encode(), datestamp)
    key = _sign(key, REGION)
    key = _sign(key, "s3")
    return _sign(key, "aws4_request")


def _presign(
    method: str,
    object_key: str,
    ttl: int,
    extra: dict[str, str],
    signed: dict[str, str] | None = None,
) -> str | None:
    """None — R2 не налаштовано. Порожній рядок тут прикидався б посиланням.

    `signed` — заголовки, які клієнт ЗОБОВ'ЯЗАНИЙ надіслати рівно такими, бо
    вони входять у підпис. Порожній (типовий) випадок лишає підпис таким, яким
    він був завжди: `SignedHeaders=host`. Це потрібне для вкладень, де стеля
    має бути СПРАВЖНЬОЮ: підписаний `content-length` означає, що R2 відмовить
    завантаженню іншої ваги, а не що ми попросили й повірили.
    """
    if not configured():
        return None
    host = f"{_env('R2_ACCOUNT_ID')}.r2.cloudflarestorage.com"
    now = datetime.now(timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    datestamp = now.strftime("%Y%m%d")
    scope = f"{datestamp}/{REGION}/s3/aws4_request"

    canonical_uri = "/" + quote(f"{_env('R2_BUCKET')}/{object_key}", safe="/")
    params = {
        "X-Amz-Algorithm": "AWS4-HMAC-SHA256",
        "X-Amz-Credential": f"{_env('R2_ACCESS_KEY_ID')}/{scope}",
        "X-Amz-Date": amz_date,
        "X-Amz-Expires": str(ttl),
        **extra,
    }
    # host завжди перший: канонічні заголовки йдуть за абеткою, і `content-length`
    # стає ПЕРЕД `host`. Порядок тут не косметичний — розійдись він із тим, що
    # надішле клієнт, R2 відповість SignatureDoesNotMatch.
    headers = dict(signed or {})
    headers["host"] = host
    names = sorted(headers)
    params["X-Amz-SignedHeaders"] = ";".join(names)
    canonical_query = "&".join(
        f"{quote(k, safe='')}={quote(v, safe='')}" for k, v in sorted(params.items())
    )
    canonical_request = "\n".join(
        [
            method,
            canonical_uri,
            canonical_query,
            "".join(f"{n}:{headers[n]}\n" for n in names),
            ";".join(names),
            "UNSIGNED-PAYLOAD",
        ]
    )
    to_sign = "\n".join(
        [
            "AWS4-HMAC-SHA256",
            amz_date,
            scope,
            hashlib.sha256(canonical_request.encode()).hexdigest(),
        ]
    )
    signature = hmac.new(
        _signing_key(datestamp), to_sign.encode(), hashlib.sha256
    ).hexdigest()
    return f"https://{host}{canonical_uri}?{canonical_query}&X-Amz-Signature={signature}"


def presign(object_key: str, ttl: int = LINK_TTL_SECONDS) -> str | None:
    filename = object_key.rsplit("/", 1)[-1]
    # Ці два перекривають те, що лежить у метаданих об'єкта, і мусять бути
    # підписані разом з рештою — інакше R2 відповість SignatureDoesNotMatch.
    return _presign(
        "GET",
        object_key,
        ttl,
        {
            "response-content-disposition": f'attachment; filename="{filename}"',
            "response-content-type": content_type_for(filename),
        },
    )


def delete(object_key: str) -> bool:
    """Best effort: an erase must not stall on storage; a failed delete leaves an object no door points to."""
    url = _presign("DELETE", object_key, 60, {})
    if url is None:
        return False
    try:
        with urllib.request.urlopen(urllib.request.Request(url, method="DELETE"), timeout=5) as res:
            return 200 <= res.status < 300
    except Exception:
        return False


def put(object_key: str, data: bytes, content_type: str) -> bool:
    """The server writes the object itself, so the bucket needs no CORS and the browser never sees an R2 link."""
    url = _presign("PUT", object_key, 300, {}, {"content-length": str(len(data)), "content-type": content_type})
    if url is None:
        return False
    req = urllib.request.Request(url, data=data, method="PUT",
                                 headers={"Content-Type": content_type, "Content-Length": str(len(data))})
    try:
        with urllib.request.urlopen(req, timeout=30) as res:
            return 200 <= res.status < 300
    except Exception:
        return False


def presign_put(object_key: str, ttl: int = 3600) -> str | None:
    """Право покласти ОДИН об'єкт і не більше. Ключі R2 живуть лише на сервері,
    тож викласти збірку зі сторонньої машини можна тільки таким посиланням."""
    return _presign("PUT", object_key, ttl, {})


def link_for(platform: str, ttl: int = LINK_TTL_SECONDS) -> tuple[str, str] | None:
    """(посилання, ім'я файла) або None, якщо збірки для платформи ще немає."""
    key = _builds().get(platform)
    if not key:
        return None
    url = presign(key, ttl)
    return (url, key.rsplit("/", 1)[-1]) if url else None


def presign_put_sized(object_key: str, size: int, ttl: int, content_type: str | None = None) -> str | None:
    """Право покласти один об'єкт РІВНО такої ваги.

    Відмінність від [presign_put] не косметична. Звичайне підписане посилання
    на запис не обмежує ваги взагалі: стеля, оголошена сервером, лишалась би
    проханням, а R2 прийняв би й терабайт. Тут `content-length` входить У
    ПІДПИС, тож завантаження іншої ваги відмовляє саме сховище — тобто стеля
    стає властивістю посилання, а не обіцянкою в документації.

    Ціна: клієнт мусить знати вагу шифротексту НАПЕРЕД і надіслати рівно її.
    Для вкладення це не обмеження — файл уже зашифрований на пристрої, і його
    довжина відома до першого байта в дроті.
    """
    signed = {"content-length": str(size)}
    if content_type:
        signed["content-type"] = content_type
    return _presign("PUT", object_key, ttl, {}, signed)
