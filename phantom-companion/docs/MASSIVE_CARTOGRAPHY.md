# MASSIVE CARTOGRAPHY — the impenetrable map base

**Mandate:** IRONCLAD KINEMATICS & MASSIVE CARTOGRAPHY, Vector 2 (2026-07-16).
**Goal:** a map that is vast, data-rich, and completely indifferent to network
blackouts — country-scale basemap, monotonic routing mesh, dense offline POI.

## 0. Verified facts this architecture stands on

Checked against the actual artifacts, not release notes:

- **MapLibre 11.5.1 has NO PMTiles support** — zero `pmtiles` symbols in
  `libmaplibre.so`. Do **not** architect around `pmtiles://` until the SDK is
  upgraded to a version that ships it.
- **MapLibre 11.5.1 SHIPS `MBTilesFileSource`** — the native lib resolves
  `mbtiles://` **absolute-path** URLs directly. A sideloaded country MBTiles
  file is renderable today, no loopback tile server, no SDK change.
- `RoadTruth`'s live tile cache is RAM-only (6 tiles, 30-min TTL) — by design,
  for matching. Persistence now lives *behind* it (see M-B), not inside it.
- `poi_gazetteer` already accumulates POIs as a fetch by-product (FTS4 —
  device has **no fts5**; prefix syntax differs, see memory).
- The phone must **never parse a raw `.osm.pbf`** — extracts are processed on
  the Radxa (the FORGE node) and sideloaded as finished artifacts.

## 1. M-A — MASSIVE basemap (country-scale vector tiles, offline)

**Carrier:** one `ukraine.mbtiles` (OpenMapTiles schema, z0–z14) + a style
JSON whose source URL is swapped to the absolute device path.

Build on the Radxa (once, ~hours; needs ~8 GB free):

```bash
# 1. Vector tiles for all of Ukraine (planetiler downloads the extract itself)
java -Xmx4g -jar planetiler.jar --download --area=ukraine \
     --output=ukraine.mbtiles
# 2. Take the Liberty style and point its source at the device file
curl -s https://tiles.openfreemap.org/styles/liberty > atlas-style.json
#    …edit: "sources".openmaptiles.url → "mbtiles:///sdcard/PhantomAtlas/ukraine.mbtiles"
#    (drop the "glyphs"/"sprite" swap for phase 1 — they still resolve online;
#     phase 2 bundles them under file:// for total blackout typography)
# 3. Sideload
adb push ukraine.mbtiles atlas-style.json /sdcard/PhantomAtlas/
```

**App integration phases:**
- **Phase 1 (zero code, works now):** `TileProviderId.CUSTOM` + custom style
  URL `file:///sdcard/PhantomAtlas/atlas-style.json`.
- **Phase 2:** first-class «МАСИВ» provider — auto-detect `PhantomAtlas/`,
  register in the stratum switcher, bundle glyphs/sprites locally, and keep
  hillshade/3D-extrusion install steps (they read the *style's* vector source,
  which is exactly why `installBuildingExtrusions` resolves the source
  dynamically — dense 3D comes free with the offline tiles, country-wide).
- **Sizing:** `ukraine.osm.pbf` ≈ 1.1 GB → z14 MBTiles ≈ 2–3 GB. Fine for the
  device; the artifact lives on shared storage, not in the APK.

## 2. M-B — MONOTONIC routing mesh *(landed with this commit)*

`RoadMeshStore` (core-sensor): SQLite, cell-sharded (0.02° grid, same density
as RoadTruth's tiles), locale-proof text geometry codec, `bulkImport` seam.

Wiring (`AppContainer.fetchRoadWays`): every successful Overpass fetch
**thickens the permanent mesh** as a by-product; every failed fetch — the
blackout case — is **answered from the mesh**. RoadTruth, the matcher, the
evacuation router and the shelter isochrone all sit downstream of this one
function and needed zero changes. Asphalt does not move: rows never expire.

**Next (post-QA):**
- Per-oblast road packs built on the Radxa (`osmium tags-filter w/highway` →
  way list → `bulkImport` over adb/import screen) — the mesh goes from
  "everywhere I've driven" to "everywhere, period".
- Raise `RoadTruth.MAX_TILES` once the store makes refetch free (RAM is then
  the only cost; the QA-baseline value stays untouched until the drive says
  the baseline holds).

## 3. M-C — POI density

Same doctrine as M-B, same pipeline node: `osmium tags-filter` the Ukraine
extract for the gazetteer's category set → SQLite staging file → bulk ingest
into `poi_gazetteer` (FTS4 + unicode61 — **not** fts5, device-verified).
The gazetteer's accumulate-forever contract already exists; this only feeds
it a country at once instead of a viewport at a time.

## 4. M-D — dense 3D

No new pipeline: the OpenMapTiles `building` layer inside `ukraine.mbtiles`
carries footprints + `render_height` country-wide, and the existing
extrusion/hillshade layers consume whatever vector source the active style
exposes. Offline 3D is a consequence of M-A, not a project.

## 5. What deliberately did NOT change

- `RoadTruth` (QA-baseline matcher) — untouched; persistence wraps its fetch.
- Overpass remains the freshness source when the network exists.
- No PMTiles code against an SDK that cannot render it.

## 6. Order of execution

M-B (done) → Re-Drive QA → M-A phase 1 (operator-side, zero code) →
M-C bulk ingest → M-A phase 2 («МАСИВ» provider + offline glyphs) →
MAX_TILES raise. Each step is independently shippable and independently
revertible.
