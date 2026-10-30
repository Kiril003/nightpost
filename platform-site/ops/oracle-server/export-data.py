"""Consistent copy of /data (SQLite online backup + keys/policy files) as a tar.gz.

On Fly:  flyctl ssh sftp put export-data.py /tmp/export-data.py -a phantom-license
         flyctl ssh console -a phantom-license -C "python /tmp/export-data.py /tmp/nightpost-data.tgz"
On VM:   docker compose exec -T server python - < export-data.py > backups/data-$(date +%F).tgz
/relay (envelopes, truth) is deliberately never exported.
"""
import hashlib, io, os, sqlite3, sys, tarfile, tempfile

ROOT = os.environ.get("EXPORT_ROOT", "/data")
out = sys.argv[1] if len(sys.argv) > 1 else None
buf = io.BytesIO()
with tarfile.open(fileobj=buf, mode="w:gz") as tar, tempfile.TemporaryDirectory() as tmp:
    for dirpath, _, files in os.walk(ROOT):
        for name in files:
            if name.endswith(("-wal", "-shm")):
                continue
            path = os.path.join(dirpath, name)
            arc = os.path.relpath(path, ROOT)
            if name.endswith(".db"):
                copy = os.path.join(tmp, arc.replace(os.sep, "_"))
                src = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
                dst = sqlite3.connect(copy)
                src.backup(dst)
                dst.close(); src.close()
                tar.add(copy, arcname=arc)
            else:
                tar.add(path, arcname=arc)
data = buf.getvalue()
if out:
    with open(out, "wb") as f:
        f.write(data)
    print(hashlib.sha256(data).hexdigest(), out, file=sys.stderr)
else:
    sys.stdout.buffer.write(data)
