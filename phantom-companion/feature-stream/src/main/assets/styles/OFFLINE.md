# Мапа без мережі: що вже є і що лишилось

## Виміряно, не припущено

`MBTilesFileSource` **є** в `libmaplibre.so` для `arm64-v8a` — саме тій бібліотеці, що їде в
телефон (MapLibre 11.5.1). Разом з нею в бінарнику лежить її власне повідомлення про помилку, і
воно називає єдину умову:

    MBTilesFileSource only supports absolute path urls

Отже джерело задається як `mbtiles:///абсолютний/шлях/файл.mbtiles` — три косі.

`pmtiles` у цій версії **немає жодної згадки** (зʼявляється з 11.8.0). Тобто дорога через PMTiles
вимагала б підняття SDK; дорога через MBTiles не вимагає нічого.

Міряти так (не вір AAR — `.so` лежить у перетвореному кеші, і в самому AAR його ти не знайдеш):

    L=~/.gradle/caches/*/transforms/*/transformed/android-sdk-11.5.1/jni/arm64-v8a/libmaplibre.so
    strings -n 4 $L | grep -i mbtiles

Регістр має значення: рядок називається `MBTilesFileSource`, тож `grep` без `-i` дає чесний нуль
про хибне питання.

## Що вже зроблено

- Стиль наш власний, 59 шарів, їде в застосунку (`obsidian-*.json`).
- Підписи більше не залежать від мережі: `glyphs` вказує в пакунок (`Spec.GLYPHS_URL`), гліфи
  лежать у `assets/fonts/`. Доти без мережі мапа лишалась би **без жодної назви**.
- Спрайти не потрібні взагалі — `sprite` у стилі порожній.

## Що зроблено 05.09 (wave/offline-tiles-05sep)

Підміна є. `OfflineBasemapImporter` (`:core-geo`, `core/geo/basemap/OfflineBasemap.kt`) приймає файл
через SAF, перевіряє `metadata`/`tiles`, `format=pbf`, `bounds`/`minzoom`/`maxzoom`, копіює у приватний
каталог (`filesDir/offline-basemap/basemap.mbtiles`) і лишає його там назавжди — MapLibre читає файл
напряму під час малювання, тож копію не можна видаляти після імпорту, на відміну від дорожнього пакета.

`OfflineBasemapStore` (той самий модуль) памʼятає обране між запусками. Картка внесення — «ОФЛАЙН-ОСНОВА»
у «Точності», поруч із «ОФЛАЙН-ДОРОГИ» (`PrecisionScreen.kt`).

Сама підміна — `applyOfflineBasemap` (`feature-stream/.../ui/map/OfflineBasemapStyle.kt`): рядкова
заміна `sources.openmaptiles.url` на `mbtiles:///абсолютний/шлях`, застосовується в `MapCanvas.kt` лише
коли обраний стиль — наш власний Обсидіан (`asset://styles/obsidian-*.json`) і файл внесено; чужий
постачальник лишається на мережі. Сторож — `OfflineBasemapStyleTest`.

За межами `bounds` внесеного файла мапа не мовчить: `MapChromeParts.OfflineBasemapCoverageBanner`
показує слово, коли оператор виїхав за межі покриття (`MapCanvas` рахує це з `operatorLocation` і
`OfflineBasemapInfo.contains`).

**Не доведено на склі 05.09** — реальний `ukraine.mbtiles` (1,3 ГБ) лежить на `phantom-oracle`, а не в
цьому дереві, і тестовий апарат був за PIN-кодом ОС під час хвилі; шлях перевірено юніт-тестом і
читанням коду, не кадром малювання без мережі.

## Готові пакети (спечено `planetiler` на phantom-oracle, безкоштовно)

    ukraine.mbtiles       1,3 ГБ   z0-14   400 506 тайлів   до будинку
    ukraine-z12.mbtiles   288 МБ   z0-12    26 569 тайлів   до кварталу

Обидва в схемі OpenMapTiles — тій самій, під яку писано наш стиль.
