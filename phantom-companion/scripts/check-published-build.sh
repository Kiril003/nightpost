#!/usr/bin/env bash
# Чи описує `publishedBuild` той світ, який справді є.
#
#   scripts/check-published-build.sh            # читає число й хост із дерева
#   scripts/check-published-build.sh 7 https://phantom-license.fly.dev
#
# Друкує рядок виду `<вердикт>: <речення>` і йде з кодом 0 (можна збирати) або
# 1 (не можна). Вердикти — машинний контракт, їх читає release.sh і тест:
#
#   ok          сервер оголошує рівно те саме число — звірено
#   ahead       у файлі число БІЛЬШЕ за оголошене (див. «асиметрія» нижче)
#   stale       сервер оголошує БІЛЬШЕ — файл відстав від світу. Відмова.
#   unknown     документа в руках немає — звірити нічим. Відмова.
#   unverified  документа немає, але людина свідомо дозволила. Проходить.
#
# ── навіщо це існує ────────────────────────────────────────────────────────
# `publishedBuild` описує МИНУЛЕ: останню збірку, що поїхала до людей. Заслін у
# app/build.gradle.kts звіряє наступний номер із цим числом — але саме число
# доти трималось на тому, чи згадає людина підняти його після викладення. Двічі
# не згадала (1 -> 6, 6 -> 7), і другий раз мало не коштував дубліката: сьомий
# номер уже стояв у людей на телефонах, а файл про це не знав.
#
# Дублікат у кабінеті не падає гучно, як у Play. Він проходить увесь шлях
# зеленим, а на телефоні, який УЖЕ на сьомій, `latest.build (7) <= installed (7)`
# дає «оновлень немає» — назавжди. Тобто ламається рівно для тих, хто оновлювався
# вчасно, і ніхто цього не бачить.
#
# ── асиметрія, через яку `ahead` не є відмовою ─────────────────────────────
# Число, БІЛЬШЕ за оголошене, буває чесним: збірка поїхала в Play і не поїхала в
# кабінет. Найгірше, що з нього виходить, — наступний реліз перескочить вільний
# номер, а телефон порівнює `>`, і пропуск у шкалі йому байдужий. Число, МЕНШЕ
# за оголошене, — це завжди відставання від світу, і коштує воно мовчазним
# дублікатом. Тому вгору — попередження, вниз — відмова.
#
# ── чому мовчазний прохід без мережі заборонений ───────────────────────────
# Реліз без мережі — рідкісний і свідомий; реліз із застарілим числом — часта
# випадковість. Тож ціна кожного випадку призначена так: щоб зібрати без
# мережі, треба ВПИСАТИ PHANTOM_PUBLISHED_UNVERIFIED=1, і в журналі релізу
# лишиться рядок `unverified`. Питання «чи звіряли те число?» після цього має
# відповідь. Мовчазний прохід відповіді не лишає взагалі — тому його немає.
#
# Клапан діє ЛИШЕ на «не дістався сервера». На `stale` він не діє свідомо:
# коли сервер відповів і не погодився, чесних причин збирати далі немає.
#
# Підпис політики (`sig`) тут НЕ звіряють: ключа в bash немає, а вимір відповідає
# на питання «чи відстав наш файл», а не «чи можна довіряти цьому серверу».
# Той, хто підмінить відповідь, змусить заслін відмовити або назве справжнє
# число — обидва наслідки нешкідливі. Якір довіри лишається в застосунку.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TIMEOUT="${PHANTOM_POLICY_TIMEOUT:-10}"

# Кожен вердикт несе, У ЯКОМУ ДЕРЕВІ І НА ЯКІЙ ГІЛЦІ прочитано число. Це не
# оздоба: 05.09 тривога «publishedBuild = 6» була хибною рівно тому, що
# `app/build.gradle.kts` є в двох десятках робочих дерев із різними гілками, і
# аудит прочитав rebuild/mobile-day1 замість integration/beta. Те саме імʼя
# файла, інша гілка. Вимір без назви гілки тут переказати не можна.
TREE="$(basename "$ROOT")"
BRANCH="$(git -C "$ROOT" branch --show-current 2>/dev/null)"
[[ -n "$BRANCH" ]] || BRANCH="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo '?')"
WHERE="$TREE [$BRANCH]"

DECLARED="${1:-}"
HOST="${2:-}"

if [[ -z "$DECLARED" ]]; then
  DECLARED="$(grep -E '^val publishedBuild = [0-9]+' "$ROOT/app/build.gradle.kts" 2>/dev/null | grep -oE '[0-9]+$')"
fi
[[ "$DECLARED" =~ ^[0-9]+$ ]] || {
  echo "unknown: не знайшов publishedBuild у app/build.gradle.kts — звіряти нічого" >&2
  exit 1
}

if [[ -z "$HOST" ]]; then
  HOST="$(grep -E '^[[:space:]]*phantom\.licenseHost[[:space:]]*=' "$ROOT/local.properties" 2>/dev/null |
    sed -n 1p | sed 's/^[^=]*=//; s/^[[:space:]]*//; s/[[:space:]]*$//')"
fi
HOST="${HOST%/}"
[[ "$HOST" == "https://phantom-license.fly.dev" ]] && HOST="https://api.phantom-os.dev"

# Питаємо ТОЙ САМИЙ хост, якому довірятиме зібраний APK (phantom.licenseHost із
# local.properties), а не зашитий тут рядок: інакше заслін звіряв би число з
# сервером, до якого продукт не ходить.
POLICY=""
if [[ -n "${PHANTOM_POLICY_FILE:-}" ]]; then
  # Шов для тестів: подати документ файлом замість мережі. Порожній або
  # відсутній файл — це навмисне «сервер не відповів».
  ORIGIN="файл ${PHANTOM_POLICY_FILE}"
  POLICY="$(cat "$PHANTOM_POLICY_FILE" 2>/dev/null || true)"
elif [[ -z "$HOST" ]]; then
  ORIGIN="(phantom.licenseHost порожній)"
else
  ORIGIN="$HOST/api/policy"
  POLICY="$(curl -fsS --max-time "$TIMEOUT" "$HOST/api/policy" 2>/dev/null || true)"
fi

# Розбір без вигадування: якщо парсера немає, або документ не про android, або
# `latest` порожній — SERVER лишається порожнім, і це чесне «не знаю», а не нуль.
SERVER=""
if [[ -n "$POLICY" ]]; then
  if command -v jq >/dev/null 2>&1; then
    SERVER="$(jq -r '
      if ((.latest.platform // "") | ascii_downcase) == "android"
      then (.latest.build // empty) else empty end' <<<"$POLICY" 2>/dev/null || true)"
  elif command -v python3 >/dev/null 2>&1; then
    SERVER="$(python3 -c '
import json,sys
try:
    latest = (json.load(sys.stdin) or {}).get("latest") or {}
except Exception:
    sys.exit(0)
if str(latest.get("platform", "")).lower() == "android" and isinstance(latest.get("build"), int):
    print(latest["build"])
' <<<"$POLICY" 2>/dev/null || true)"
  fi
fi
[[ "$SERVER" =~ ^[0-9]+$ ]] || SERVER=""

if [[ -z "$SERVER" ]]; then
  if [[ "${PHANTOM_PUBLISHED_UNVERIFIED:-}" == "1" ]]; then
    echo "unverified: НЕ ЗВІРЕНО з $ORIGIN — publishedBuild=$DECLARED у $WHERE узято на віру" \
      "(PHANTOM_PUBLISHED_UNVERIFIED=1). Якщо число відстало, оновлення мовчки не дійде" \
      "до тих, хто вже на ньому."
    exit 0
  fi
  echo "unknown: не отримав політику з $ORIGIN — звірити publishedBuild=$DECLARED у $WHERE нічим." \
    "Заслін не пропускає незвірене: саме так число відстало двічі." \
    "Якщо збираєте свідомо без мережі — PHANTOM_PUBLISHED_UNVERIFIED=1 scripts/release.sh" >&2
  exit 1
fi

if [[ "$SERVER" -gt "$DECLARED" ]]; then
  echo "stale: сервер оголошує збірку $SERVER, а $WHERE знає лише про $DECLARED." \
    "Наступний номер вийшов би $((DECLARED + 1)) — той, що вже стоїть у людей." \
    "Телефон на $SERVER порівняв би $SERVER <= $SERVER і не побачив оновлення НІКОЛИ." \
    "Підніміть publishedBuild до $SERVER." >&2
  exit 1
fi

if [[ "$SERVER" -lt "$DECLARED" ]]; then
  echo "ahead: у $WHERE publishedBuild=$DECLARED, сервер оголошує $SERVER." \
    "Так буває, коли збірка поїхала повз кабінет; наступний номер просто перескочить вільний." \
    "Якщо ж число підняли наперед — це намір, а не минуле, і його треба повернути."
  exit 0
fi

echo "ok: publishedBuild=$DECLARED у $WHERE збігається з оголошеним на $ORIGIN"
exit 0
