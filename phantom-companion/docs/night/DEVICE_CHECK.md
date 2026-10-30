# Device check — what is built and never seen

Everything below is gated green and committed. None of it has been on the phone.
Ordered so one pass through the app covers all of it.

Package: `local.phantom.companion.debug` (field flavour — the owner's real data).
Never `pm clear` it. Never `ui.sh reset` it.

    ./gradlew --offline :app:assembleFieldDebug
    ~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/field/debug/app-field-arm64-v8a-debug.apk

---

## 1. Chat — one screen, four things

**Artifact card height** (`ec9afa47`). Ask for anything that renders an artifact
card. Look for a void under the content. The card used to reserve a fixed
height; it now measures its own content.
→ *Wrong if:* empty space below the card's content.

**Markdown table** (`63a7630e`). Ask for anything that answers with a table
(«порівняй у вигляді таблиці»). Check a heading and the value under it start at
the same x.
→ *Wrong if:* columns are ragged, or a wrapped cell shifts its neighbours.

**Agent card after a kill** (`b6411206`). Start a long agent task, force-stop
the app mid-run, reopen. The card must read «обірвано», not pulse «виконується».
→ *Wrong if:* the dot still pulses, or the cancel button is still offered.

**Notice actions**. Confirm «повторити» appears under a failed turn and NOT
under an ordinary notice such as a bank SMS.

## 2. Map — the territories panel

**Open it** (`448096b0`). Tap the cloud icon on the right rail. A panel docks at
the bottom and every stored pack paints as a rectangle on the map.
- accent fill = usable with this basemap
- dim = still downloading
- grey = saved against a different basemap, will render blank here

**Then:**
- tap a row → camera flies to that pack, row highlights, rectangle brightens
- tap the bin twice → pack disappears from both the list and the map
- switch basemap (dark ↔ light) with the panel open → packs for the other style
  must go grey and the primary action must change to «зберегти для цієї карти»
- pan somewhere unsaved → action reads «зберегти цей вид»
- pan inside a saved pack → action disappears, line reads «цей вид уже збережено»

→ *Wrong if:* rectangles do not appear, colours do not follow the basemap, or
deleting leaves the rectangle on the map.

## 3. Place card — the overview

Tap any café or shop with opening hours. Open «огляд від PHANTOM» (`9e1ebb8d`).
The prose must agree with the pill above it — if the pill says «відчинено · до
18:00», the overview may not say it is closed. It should also mention what else
of the kind is nearby.
→ *Wrong if:* the two disagree, or the overview invents a closing time.

Tap bare ground away from any POI. The overview must not make confident claims
about a named business — it is now told the name came from the road mesh.

## 4. Voice — the thing that mattered most

**Start any route and walk/drive one turn** (`c79b746b`).

The maneuver must be spoken in **PHANTOM's voice**, not Android's. The pack is
now inside the APK; nothing needs pushing over adb any more.

→ *Wrong if:* it still sounds like the system voice. Then check:

    adb logcat -d | grep BakedNavVoice

Expect `baked nav voice: 1057 clips (shipped)`. If it says `(pushed)` an old
pack is still in the app's files dir and is winning — that is fine, but it means
the shipped path is untested; clear it with
`adb shell run-as local.phantom.companion.debug rm -rf files/models/navvoice`
and try again. If the line is absent entirely, the manifest did not load.

## 5. Day screen — the calendar

Open ДЕНЬ (`2283b8d9`). With permission granted and a real event today, it lists
it. With permission not yet granted, it offers to connect.
→ *Wrong if:* it claims «на сьогодні в календарі порожньо» before the query has
returned, or after a failure. There are now three states and this was the defect.

## 6. Call journal

Open РУКИ (`8470d401`). Three states: reading, empty, unreadable.
→ *Wrong if:* a database failure reads as «жодного дзвінка не відсіяно».

---

## Still in flight, not on this list

Pedestrian guidance (distance tiers, walking camera, off-route tolerance,
arrival radius) and the chat card honesty pass are being worked on in separate
branches and are not in the APK above. The 3D agent office is PHANTOM OS, not
this app.
