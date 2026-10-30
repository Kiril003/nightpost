#!/usr/bin/env bash
# Вузол PHANTOM як sidecar настільної оболонки — PyInstaller, режим onedir.
#
# Результат: src/frontend/src-tauri/binaries/phantom-backend/
#   phantom-backend(.exe)   — запускач (вхід _phantom_entry.py → uvicorn main:app)
#   _internal/              — інтерпретатор, бібліотеки, дані вузла
# Оболонка кладе цю теку в ресурси пакета (tauri.conf.json → bundle.resources)
# і сама запускає phantom-backend з неї (src-tauri/src/main.rs).
#
# Чому onedir, а не onefile: onefile розпаковує сотні мегабайтів у /tmp на
# КОЖНОМУ старті — це секунди до першого кадру. onedir стартує з місця.
#
# Дані, які вузол читає з диска поруч із кодом (не .py-модулі):
#   alembic.ini + db/alembic/**            — міграції бази (скрипти алембіка — файли)
#   geo/layer_registry/manifests/*         — реєстр шарів мапи
#   geo/glyphs/**                          — підписи мапи без мережі
#   agent/cognition/will/*.md              — цінності й самооповідь волі
#   voice/models/silero-vad/*              — детектор мовлення
#
# Рядкові імпорти, яких аналізатор не бачить (URL бази `sqlite+aiosqlite`,
# схеми passlib) — названо явно; перевірено запуском зібраного вузла.
#
# UTF-8 — з першої миті інтерпретатора (`-X utf8`): alembic.ini і дані вузла
# українською, а заморожений Python без локалі читає їх як ASCII (Windows —
# cp1252) і вузол не стартує. `PYTHONUTF8` у _phantom_entry ставиться запізно.
#
# Використання:
#   scripts/build_sidecar.sh            # хост-платформа
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Git Bash на Windows дає шляхи `/d/a/…`, а Python (PyInstaller) читає їх як
# `\d\a\…` і не знаходить файлів. `D:/a/…` розуміють обидва.
command -v cygpath >/dev/null 2>&1 && ROOT="$(cygpath -m "$ROOT")"
BACKEND="$ROOT/src/backend"
DEST="$ROOT/src/frontend/src-tauri/binaries"
PY="$BACKEND/.venv/bin/python"
[[ -x "$PY" ]] || PY="$BACKEND/.venv/Scripts/python.exe"
[[ -x "$PY" ]] || { echo "немає venv вузла: $BACKEND/.venv" >&2; exit 1; }

"$PY" -m PyInstaller --version >/dev/null 2>&1 || {
  echo "у venv вузла немає PyInstaller: uv pip install --python $PY pyinstaller" >&2
  exit 1
}

# Без torch за замовчуванням. torch із PyPI на Linux — CUDA-збірка: разом із
# nvidia-* і triton це 5,3 ГБ із 5,7 ГБ вузла, а на ПК без відеокарти вона
# навіть не імпортується. Єдине, для чого вузлу torch, — вектори пам'яті;
# без нього їх рахує ONNX Runtime (memory/onnx_embedding.py, рівність зі
# старим шляхом доведено tests/test_onnx_embedding_parity.py).
#   SIDECAR_WITH_TORCH=1   — везти torch і sentence-transformers як раніше.
EXCLUDE=(--exclude-module tests --exclude-module pytest)
if [[ "${SIDECAR_WITH_TORCH:-0}" != "1" ]]; then
  EXCLUDE+=(--exclude-module torch --exclude-module torchvision --exclude-module torchaudio
            --exclude-module triton --exclude-module sentence_transformers)
fi

SEP=":"; [[ "${OS:-}" == "Windows_NT" ]] && SEP=";"
# SIDECAR_WORK — стала робоча тека: повторна збірка не аналізує все заново.
if [[ -n "${SIDECAR_WORK:-}" ]]; then
  WORK="$SIDECAR_WORK"; mkdir -p "$WORK"; CLEAN=()
else
  WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT; CLEAN=(--clean)
fi

PKGS=(agent ai api core db discovery dispatch geo input licensing linux memory messenger node symbiote tool_registry tools vision voice workbench)
COLLECT=()
for p in "${PKGS[@]}"; do COLLECT+=(--collect-submodules "$p"); done

cd "$BACKEND"
"$PY" -m PyInstaller \
  --noconfirm "${CLEAN[@]}" --onedir \
  --python-option "X utf8" \
  --name phantom-backend \
  --distpath "$DEST" \
  --workpath "$WORK/build" \
  --specpath "$WORK" \
  --paths "$BACKEND" \
  --additional-hooks-dir "$ROOT/scripts/pyinstaller-hooks" \
  --hidden-import main \
  "${COLLECT[@]}" \
  --collect-submodules uvicorn \
  --hidden-import aiosqlite \
  --hidden-import sqlalchemy.dialects.sqlite.aiosqlite \
  --collect-submodules passlib \
  --hidden-import bcrypt \
  --add-data "$BACKEND/alembic.ini${SEP}." \
  --add-data "$BACKEND/db/alembic${SEP}db/alembic" \
  --add-data "$BACKEND/geo/layer_registry/manifests${SEP}geo/layer_registry/manifests" \
  --add-data "$BACKEND/geo/glyphs${SEP}geo/glyphs" \
  --add-data "$BACKEND/agent/cognition/will${SEP}agent/cognition/will" \
  --add-data "$BACKEND/symbiote/contract.json${SEP}symbiote" \
  --add-data "$BACKEND/voice/models/silero-vad${SEP}voice/models/silero-vad" \
  "${EXCLUDE[@]}" \
  "$BACKEND/_phantom_entry.py"

echo "sidecar: $DEST/phantom-backend ($(du -sh "$DEST/phantom-backend" | cut -f1))"
