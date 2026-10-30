"""Покласти збірку в R2 і сказати, чим після цього має стати R2_BUILDS.

    python upload_to_r2.py android ../../phantom-companion/app/build/outputs/apk/field/release/app-field-arm64-v8a-release.apk

Тип файла ставиться ЯВНО. R2 сам не вгадує: до цього об'єкт лежав узагалі без
типу, і браузер, побачивши байти на «PK», зберігав архів — а APK і є архів,
тож людина отримувала «зіпку з купою файлів» замість застосунку. Посилання з
storage.py тепер тип теж перекриває, але лишати брехню в самому бакеті не варто.

Ключі R2 читаються з server/.env і нікуди більше не потрапляють.
"""
import os
import sys
from pathlib import Path

import boto3
from dotenv import load_dotenv

sys.path.insert(0, str(Path(__file__).resolve().parent))
import storage  # noqa: E402  — після sys.path

load_dotenv(Path(__file__).resolve().parent / ".env")


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 64
    platform, source = sys.argv[1], Path(sys.argv[2]).expanduser().resolve()
    if not source.is_file():
        print(f"немає файла: {source}")
        return 66

    key = sys.argv[3] if len(sys.argv) > 3 else f"builds/{source.name}"
    bucket = os.environ["R2_BUCKET"]
    content_type = storage.content_type_for(source.name)

    s3 = boto3.client(
        "s3",
        endpoint_url=f"https://{os.environ['R2_ACCOUNT_ID']}.r2.cloudflarestorage.com",
        aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
        aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"],
        region_name="auto",
    )

    size_mb = source.stat().st_size / 1_000_000
    print(f"{source.name} → {bucket}/{key}  ({size_mb:.0f} МБ, {content_type})")
    s3.upload_file(
        str(source),
        bucket,
        key,
        ExtraArgs={"ContentType": content_type, "ContentDisposition": f'attachment; filename="{source.name}"'},
    )

    builds = dict(
        pair.split(":", 1) for pair in os.environ.get("R2_BUILDS", "").split(",") if ":" in pair
    )
    builds[platform] = key
    line = ",".join(f"{name}:{value}" for name, value in sorted(builds.items()))
    print("\nГотово. Тепер сервер має вказувати на цей файл:")
    print(f'  fly secrets set --app phantom-license R2_BUILDS="{line}"')
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
