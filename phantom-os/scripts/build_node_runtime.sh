#!/usr/bin/env bash
# Вузол PHANTOM для пакета — справжній CPython поруч із кодом, без PyInstaller.
#
# Результат: src/frontend/src-tauri/binaries/phantom-node/
#   python/   — інтерпретатор (+ site-packages із залежностями вузла)
#   app/      — код вузла як є (.py + скомпільовані .pyc), вхід _phantom_entry.py
# Оболонка кладе теку в ресурси пакета (tauri.conf.json → bundle.resources) і
# запускає `python -X utf8 -E -s app/_phantom_entry.py` (src-tauri/src/main.rs).
#
# Чому не PyInstaller (scripts/build_sidecar.sh лишився для порівняння):
#   - Windows: python.exe і DLL з офіційного embeddable-пакета python.org
#     підписані Python Software Foundation — антивіруси й SmartScreen не
#     бачать у них «невідомий розпакувальник», як у завантажувачі PyInstaller;
#   - жодних хуків: модулі лежать звичайними файлами, тож бібліотеки, що
#     шукають себе на диску (chromadb, pkgutil, importlib.resources), просто
#     працюють — з PyInstaller кожна така потребувала ручного хука;
#   - оновити вузол — замінити теку app/, без перезбирання інтерпретатора.
#
# Звідки Python:
#   Linux / macOS — python-build-standalone (Astral; на ньому працює uv);
#   Windows       — embeddable-zip із python.org (підписаний PSF). У ньому
#                   нема pip, тож залежності ставить pip хоста тієї самої
#                   мінорної версії (--target), а шляхи — файл python311._pth.
#
# torch/sentence-transformers не везем: вектори пам'яті рахує ONNX Runtime
# (memory/onnx_embedding.py).
#
# Використання:
#   scripts/build_node_runtime.sh
#   PHANTOM_PY=3.11.9 PBS_TAG=20240814 scripts/build_node_runtime.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
command -v cygpath >/dev/null 2>&1 && ROOT="$(cygpath -m "$ROOT")"
BACKEND="$ROOT/src/backend"
DEST="$ROOT/src/frontend/src-tauri/binaries/phantom-node"
PY_VER="${PHANTOM_PY:-3.11.9}"
PBS_TAG="${PBS_TAG:-20240814}"
PY_MM="${PY_VER%.*}"             # 3.11
PY_TAG="${PY_MM/./}"             # 311
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64)   PLAT=linux;   TRIPLE=x86_64-unknown-linux-gnu ;;
  Linux-aarch64)  PLAT=linux;   TRIPLE=aarch64-unknown-linux-gnu ;;
  Darwin-arm64)   PLAT=mac;     TRIPLE=aarch64-apple-darwin ;;
  Darwin-x86_64)  PLAT=mac;     TRIPLE=x86_64-apple-darwin ;;
  MINGW*|MSYS*|CYGWIN*) PLAT=windows ;;
  *) echo "невідома платформа: $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

rm -rf "$DEST"; mkdir -p "$DEST"
grep -viE '^(torch|sentence-transformers|triton)\b' "$BACKEND/requirements.txt" > "$WORK/req.txt"

if [[ $PLAT == windows ]]; then
  echo "· Python $PY_VER embeddable (python.org, підпис PSF)"
  curl -fsSL -o "$WORK/py.zip" "https://www.python.org/ftp/python/$PY_VER/python-$PY_VER-embed-amd64.zip"
  mkdir -p "$DEST/python"
  unzip -q "$WORK/py.zip" -d "$DEST/python"
  PYEXE="$DEST/python/python.exe"
  SITE="$DEST/python/Lib/site-packages"
  mkdir -p "$SITE"
  # Embeddable ігнорує PYTHONPATH і site — шляхи задає лише ._pth.
  cat > "$DEST/python/python${PY_TAG}._pth" <<EOF
python${PY_TAG}.zip
.
Lib/site-packages
../app
import site
EOF
  HOST_PY="${HOST_PYTHON:-python}"
  "$HOST_PY" -c "import sys; assert sys.version_info[:2]==tuple(map(int,'$PY_MM'.split('.'))), sys.version" \
    || { echo "pip хоста мусить бути Python $PY_MM" >&2; exit 1; }
  echo "· залежності вузла → site-packages (pip хоста, --target)"
  "$HOST_PY" -m pip install --no-cache-dir --disable-pip-version-check -q \
    --target "$SITE" -r "$WORK/req.txt"
else
  echo "· Python $PY_VER ($TRIPLE, python-build-standalone $PBS_TAG)"
  curl -fsSL -o "$WORK/py.tgz" \
    "https://github.com/astral-sh/python-build-standalone/releases/download/$PBS_TAG/cpython-$PY_VER+$PBS_TAG-$TRIPLE-install_only_stripped.tar.gz"
  tar -xzf "$WORK/py.tgz" -C "$DEST"       # → $DEST/python
  PYEXE="$DEST/python/bin/python3"
  echo "· залежності вузла → site-packages"
  # -s: без користувацького site хоста — інакше pip вважає «вже стоїть»
  # те, що лежить у ~/.local, і не кладе його у вузол (так зник certifi).
  PYTHONNOUSERSITE=1 "$PYEXE" -s -m pip install --no-cache-dir --disable-pip-version-check -q \
    --root-user-action=ignore -r "$WORK/req.txt"
fi

echo "· код вузла → app/"
mkdir -p "$DEST/app"
# Лише код і дані, що їдуть з вузлом; не тести, не venv, не дані розробника.
tar -C "$BACKEND" \
  --exclude='./.venv' --exclude='./tests' --exclude='*/__pycache__' --exclude='./.pytest_cache' \
  --exclude='./.phantom-data' --exclude='./data' --exclude='*.db' --exclude='*.db-*' \
  --exclude='./chroma_data' --exclude='./.env' --exclude='./*.log' \
  -cf - . | tar -C "$DEST/app" -xf -
# .pyc наперед: перший старт не компілює сотні модулів (і не пише поруч із
# кодом — у встановленому пакеті тека лише для читання).
"$PYEXE" -X utf8 -m compileall -q -j 0 "$DEST/app" >/dev/null || true

# Сміття, що не потрібне вузлу в пакеті.
find "$DEST/python" -type d \( -name test -o -name tests -o -name idle_test \) -path '*/lib*' -prune -exec rm -rf {} + 2>/dev/null || true

echo "вузол: $DEST ($(du -sh "$DEST" | cut -f1); python $(du -sh "$DEST/python" | cut -f1), app $(du -sh "$DEST/app" | cut -f1))"
