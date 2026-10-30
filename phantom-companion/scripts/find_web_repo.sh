#!/usr/bin/env bash
# Де лежить репозиторій веб-месенджера — одна відповідь для всіх скриптів.
#
# Раніше і синхронізація, і заслін релізу питали лише СУСІДА
# (`<companion>/../phantom-os-messenger`). У канонічному розкладі він там і
# лежить, але жодне робоче дерево бригади там не стоїть: воно живе на рівень
# вище, поруч із PHANTOM_OS_BLUEPRINT, а не всередині. Тож у кожному дереві
# бригади відповідь була «веба немає» — і заслін мовчки йшов у гілку «не
# перевірено», тобто саме в ту тишу, проти якої його й ставили.
#
# Шукаємо вгору по батьках, а не по списку захардкоджених шляхів: розклад
# дерев міняється, а «десь вище є тека phantom-os-messenger» — ні.
# PHANTOM_WEB_REPO перекриває пошук, коли дерев кілька й треба конкретне.
#
# МОНО-РЕПОЗИТОРІЙ (phantom-mono): веб лежить у `<companion>/../phantom-os`, і
# `.git` там немає — він один на корені. Тому «дерево веба» тут означає «тека
# з src/frontend усередині робочого дерева git», а не «тека з власним .git».
# Цей випадок перевіряється ПЕРШИМ: у моно сусід завжди поруч.
_is_web_tree() {
  [[ -d "$1/src/frontend" ]] && git -C "$1" rev-parse --is-inside-work-tree >/dev/null 2>&1
}

find_web_repo() {
  local start="${1:?потрібен шлях companion}"
  if [[ -n "${PHANTOM_WEB_REPO:-}" ]]; then
    _is_web_tree "${PHANTOM_WEB_REPO}" \
      && { echo "${PHANTOM_WEB_REPO}"; return 0; }
    echo "PHANTOM_WEB_REPO=${PHANTOM_WEB_REPO} — не дерево веба в git" >&2
    return 1
  fi
  # Лише якщо сусід — у ТОМУ САМОМУ репозиторії, що й companion. У старому
  # розкладі поруч теж лежить `phantom-os`, але це окремий репозиторій ПК-дерева
  # на іншій гілці — брати його за веб-месенджер не можна.
  local mono; mono="$(cd "$start/.." && pwd)/phantom-os"
  if _is_web_tree "$mono" && [[ "$(git -C "$mono" rev-parse --show-toplevel)" == \
       "$(git -C "$start" rev-parse --show-toplevel 2>/dev/null)" ]]; then
    echo "$mono"; return 0
  fi
  local dir; dir="$(cd "$start" && pwd)"
  local depth=0
  while [[ "$dir" != "/" && $depth -lt 5 ]]; do
    # На кожному рівні дивимось і сам рівень, і його теки на один крок углиб:
    # робочі дерева бригад стоять ПОРУЧ із PHANTOM_OS_BLUEPRINT, а веб лежить
    # ВСЕРЕДИНІ нього. Без цього кроку пошук угору проминав його на кожному
    # рівні й чесно казав «немає» там, де воно було.
    local cand
    for cand in "${dir}/phantom-os-messenger" "${dir}"/*/phantom-os-messenger; do
      if [[ -d "${cand}/src/frontend" ]] && [[ -d "${cand}/.git" || -f "${cand}/.git" ]]; then
        echo "$cand"; return 0
      fi
    done
    dir="$(dirname "$dir")"; depth=$((depth + 1))
  done
  return 1
}

# Шляхи бандла як pathspec від кореня репозиторію (`:/` — магія git «від
# вершини»): фронтенд і спільні типи (`@shared` → ../shared). Працює однаково
# і в старому окремому репозиторії (префікс ""), і в моно ("phantom-os/").
web_bundle_pathspec() {
  local p; p="$(git -C "${1:?потрібне дерево веба}" rev-parse --show-prefix)"
  echo ":/${p}src/frontend" ":/${p}src/shared"
}

# Коміт-печатка бандла: ОСТАННІЙ коміт на <ref>, що міняв файли бандла.
#
# Чому не просто `rev-parse <ref>`: у моно-репозиторії кожен коміт у
# phantom-companion (зокрема сам коміт синхронізації бандла) зсуває HEAD, і
# заслін у release.sh кричав би «бандл відстає» про байтово той самий веб.
# Останній коміт, що торкався шляхів бандла, однаковий для однакового веба —
# і в моно, і в старому окремому репозиторії.
web_source_sha() {
  local repo="${1:?потрібне дерево веба}" ref="${2:-HEAD}"
  # shellcheck disable=SC2046  # два pathspec без пробілів усередині
  git -C "$repo" log -1 --format=%H "$ref" -- $(web_bundle_pathspec "$repo")
}

# Хеш вмісту бандла, той самий, що рахує Gradle-задача VerifyWebBundleSeal:
# sha256 від рядків «<sha256 файла>  <шлях>\n», шляхи відносні й відсортовані
# побайтово, BUNDLE_SOURCE на корені не входить. Міняєш тут — міняй і там.
web_bundle_hash() {
  local dir="${1:?потрібна тека бандла}"
  ( cd "$dir" && find . -type f ! -path ./BUNDLE_SOURCE -printf '%P\0' \
      | LC_ALL=C sort -z | xargs -0 -r sha256sum | sha256sum | cut -d' ' -f1 )
}
