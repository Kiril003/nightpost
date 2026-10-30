"""Day-4 Wave-2 — Block V-1: Tauri 2.x scaffold contract pin
(ADR-DSH-001, `docs/architecture/desktop-shell.md`).

Closes audit U5-PKG-C1 (no desktop scaffold exists today).

The Tauri toolchain is NOT a CI dependency on this Day-4 build (no Rust
in the venv image; bundle artefacts live downstream of Day-5 signing).
Instead this test asserts the *files exist with the correct shape* —
the contract that downstream blocks (V-5 splash gate, W-3b settings UI
launcher, AB capstone tag) depend on.

Coverage:

1. Scaffold files exist at the canonical paths.
2. `Cargo.toml` lists Tauri 2.x + the shell plugin (sidecar host).
3. `tauri.conf.json` is valid JSON, sets `identifier`, declares
   `bundle.externalBin = ["binaries/phantom-backend"]`, and the splash
   window points at `splash.html` (NOT the dist bundle directly — the
   /readyz gate must run first).
4. `tauri.conf.json` window respects PHANTOM's 1024x600 hardware
   constraint as a min size (`min_width=1024`, `min_height=600`).
5. `src/main.rs` spawns the sidecar with `PHANTOM_PACKAGED=1` so
   `_refuse_lan_bind_in_packaged_mode` (`main.py:211`) activates.
6. CSP forbids cross-origin connect except 127.0.0.1:8000 (closes the
   "shell allows arbitrary fetch" sleeper concern).
7. The sidecar build helper exists and is executable.
"""
from __future__ import annotations

import json
import os
import re
import stat
from pathlib import Path

import pytest


# Resolve the repo root from this file: src/backend/tests/<this> →
# walk up three levels.
REPO_ROOT = Path(__file__).resolve().parents[3]
TAURI_DIR = REPO_ROOT / "src" / "frontend" / "src-tauri"
CARGO_TOML = TAURI_DIR / "Cargo.toml"
TAURI_CONF = TAURI_DIR / "tauri.conf.json"
MAIN_RS = TAURI_DIR / "src" / "main.rs"
# The splash moved out of src-tauri/ into the Vite public/ root in 5107435:
# from src-tauri/ it resolved `../dist/index.html`, which is a 404 in the
# packaged bundle, and its poll loop was inline against a `script-src 'self'`
# CSP. Served from public/ it lands beside index.html in dist/.
SPLASH_DIR = REPO_ROOT / "src" / "frontend" / "public"
SPLASH_HTML = SPLASH_DIR / "splash.html"
SPLASH_JS = SPLASH_DIR / "splash.js"
BUILD_RS = TAURI_DIR / "build.rs"
SIDECAR_BUILD = REPO_ROOT / "scripts" / "build_sidecar.sh"


# ───────────────────────────────────────────────────────────── files exist ──


class TestScaffoldFilesExist:
    def test_cargo_toml_present(self):
        assert CARGO_TOML.is_file(), (
            f"V-1 regression: missing {CARGO_TOML.relative_to(REPO_ROOT)} — "
            "the Tauri crate manifest disappeared. Day-5 signing assumes "
            "this path; do not rename without updating ADR-DSH-001."
        )

    def test_tauri_conf_present(self):
        assert TAURI_CONF.is_file(), (
            f"V-1 regression: missing {TAURI_CONF.relative_to(REPO_ROOT)}"
        )

    def test_main_rs_present(self):
        assert MAIN_RS.is_file(), (
            f"V-1 regression: missing {MAIN_RS.relative_to(REPO_ROOT)}"
        )

    def test_no_splash_gate_left(self):
        """26.09: вікно відкривається одразу на вебі — PHANTOM прокидається на
        очах (components/shell/DeskWaking), а не за чорним сплешем, що чекав
        бекенд. Сплеш, який лишився б, знову став би воротами."""
        assert not SPLASH_HTML.exists(), "splash.html повернувся — вікно знову чекатиме бекенд"
        assert not SPLASH_JS.exists()

    def test_build_rs_present(self):
        assert BUILD_RS.is_file()

    def test_sidecar_build_helper_present_and_executable(self):
        assert SIDECAR_BUILD.is_file(), (
            f"V-1 regression: missing {SIDECAR_BUILD.relative_to(REPO_ROOT)}"
        )
        mode = SIDECAR_BUILD.stat().st_mode
        assert mode & stat.S_IXUSR, (
            f"V-1: {SIDECAR_BUILD.name} not executable — `chmod +x` it back."
        )


# ─────────────────────────────────────────────────────────── Cargo manifest ──


class TestCargoManifest:
    def test_cargo_pins_tauri_v2_and_shell_plugin(self):
        body = CARGO_TOML.read_text(encoding="utf-8")
        # tauri-build under [build-dependencies], tauri + shell plugin under
        # [dependencies]. Lenient regex — just pin the major version.
        assert re.search(r'tauri\s*=\s*\{\s*version\s*=\s*"2\.', body), (
            "V-1: Cargo.toml must pin tauri = \"2.x\""
        )
        assert re.search(r'tauri-plugin-shell\s*=\s*"2\.', body), (
            "V-1: Cargo.toml must pin tauri-plugin-shell = \"2.x\" — "
            "the sidecar is spawned through ShellExt::sidecar()."
        )
        assert re.search(r'tauri-build\s*=\s*\{\s*version\s*=\s*"2\.', body), (
            "V-1: build-dependency tauri-build must be 2.x"
        )

    def test_cargo_release_profile_is_size_optimized(self):
        body = CARGO_TOML.read_text(encoding="utf-8")
        # Mobile-class device target — release profile must shave bytes.
        assert "lto = true" in body
        assert 'opt-level = "s"' in body
        assert "strip = true" in body


# ─────────────────────────────────────────────────────────── tauri.conf.json ──


class TestTauriConf:
    def _load(self) -> dict:
        return json.loads(TAURI_CONF.read_text(encoding="utf-8"))

    def test_identifier_and_product_name(self):
        conf = self._load()
        assert conf["identifier"] == "ai.phantom.os"
        # 26.09: у заголовку вікна й у меню ОС — просто «PHANTOM».
        assert conf["productName"] == "PHANTOM"

    def test_frontend_dist_points_at_react_bundle(self):
        conf = self._load()
        # `../dist` resolves from src-tauri/ to src/frontend/dist/, which is
        # exactly where `npm run build` writes its output.
        assert conf["build"]["frontendDist"] == "../dist"

    def test_window_respects_1024x600_hardware_floor(self):
        """PHANTOM ships on Radxa Q6A with a 7" 1024x600 panel. The shell
        window MUST NOT shrink below that — every UI screen is laid out
        exactly to those pixels (rule 3 of CLAUDE.md coding rules)."""
        conf = self._load()
        windows = conf["app"]["windows"]
        assert windows, "V-1: at least one window must be declared"
        main = windows[0]
        assert main["minWidth"] == 1024, (
            f"V-1: minWidth={main['minWidth']!r}, expected 1024 (Q6A panel width)"
        )
        assert main["minHeight"] == 600, (
            f"V-1: minHeight={main['minHeight']!r}, expected 600 (Q6A panel height)"
        )

    def test_window_draws_the_ui_immediately(self):
        """Вікно — одразу на вбудованому вебі; вузол веб чекає сам."""
        conf = self._load()
        url = conf["app"]["windows"][0]["url"]
        assert url == "index.html", f"window must open index.html, got {url!r}"
        waking = (REPO_ROOT / "src" / "frontend" / "src" / "components" / "shell" / "DeskWaking.tsx").read_text(encoding="utf-8")
        assert "/health" in waking

    def test_sidecar_is_an_onedir_resource(self):
        """26.09: вузол — PyInstaller onedir (запускач + `_internal/`).
        `externalBin` бере один файл, тож теку везуть ресурсами пакета, а
        оболонка запускає її сама (main.rs → sidecar_path)."""
        conf = self._load()
        assert "externalBin" not in conf["bundle"], "onedir не влазить в externalBin"
        assert conf["bundle"]["resources"] == {"binaries/phantom-node/": "phantom-node/"}

    def test_bundle_targets_cover_linux_and_windows(self):
        conf = self._load()
        targets = set(conf["bundle"]["targets"])
        # ARM64 Linux (Radxa Q6A primary) ships through .deb + .AppImage.
        # x86_64 Windows ships through .msi + .nsis.
        assert {"appimage", "deb"}.issubset(targets), (
            f"V-1: Linux bundle targets missing — got {targets!r}"
        )
        assert targets & {"msi", "nsis"}, (
            f"V-1: Windows bundle target missing — got {targets!r}"
        )

    def test_csp_locks_connect_to_loopback(self):
        """The shell HTML must NOT be able to fetch arbitrary origins.
        connect-src constrained to 127.0.0.1:8000 keeps a compromised
        WebView from exfiltrating to the public internet."""
        conf = self._load()
        csp = conf["app"]["security"]["csp"]
        # Дозволено рівно: сам застосунок, IPC оболонки (ipc: на Linux/macOS,
        # http://ipc.localhost на Windows — канал до Rust, не мережа), вузол на
        # петлі і три названі дороги, які вікно кличе саме: тайли й шрифти мапи,
        # рельєф, каталог людей месенджера. Без них (до 27.09) мапа у вікні
        # PHANTOM була чорна, а пошук людини — мовчки порожній.
        connect = next(d for d in csp.split(";") if d.strip().startswith("connect-src")).split()[1:]
        assert set(connect) == {
            "'self'", "ipc:", "http://ipc.localhost", "http://127.0.0.1:8000", "ws://127.0.0.1:8000",
            "https://tiles.openfreemap.org", "https://s3.amazonaws.com/elevation-tiles-prod/", "https://api.phantom-os.dev",
            "blob:", "data:",
        }, f"V-1: CSP connect-src — лише петля, IPC і три названі дороги — got {csp!r}"
        # No wildcards permitted in any directive.
        assert "*" not in csp, (
            f"V-1: CSP wildcard rejected — got {csp!r}"
        )

    def test_csp_lets_the_window_reach_what_it_calls(self):
        """Кожна зовнішня адреса, яку вікно кличе саме (мапа, рельєф, каталог
        людей), є в connect-src. Інакше в браузері працює, а у вікні — ні."""
        import re

        conf = self._load()
        connect = next(d for d in conf["app"]["security"]["csp"].split(";") if d.strip().startswith("connect-src"))
        front = TAURI_DIR.parent / "src"
        called = set()
        for rel in ("components/map", "services/peerDirectory.ts"):
            target = front / rel
            files = [target] if target.is_file() else [f for f in target.rglob("*.ts*") if "__tests__" not in f.parts]
            for f in files:
                called |= set(re.findall(r"https://[a-z0-9.-]+\.(?:org|com|dev)(?=[/'\"`])", f.read_text(encoding="utf-8")))
        assert called, "не знайшов жодної зовнішньої адреси — тест нічого не перевіряє"
        missing = sorted(h for h in called if h not in connect)
        assert not missing, f"вікно кличе, а CSP блокує: {missing}"

    def test_shell_plugin_does_not_open_arbitrary_paths(self):
        """tauri-plugin-shell is included to spawn the sidecar via
        ShellExt::sidecar(). The `open` capability (which would let the
        WebView shell out to xdg-open / start.exe) MUST stay disabled."""
        conf = self._load()
        shell_plugin = conf["plugins"]["shell"]
        assert shell_plugin["open"] is False, (
            "V-1: shell.open must be False — keep the WebView from launching "
            "external programs."
        )
        # `scope` was removed in 5107435: tauri-plugin-shell v2 panics at
        # startup on the key, and with `open: false` there is no command
        # surface for a scope to constrain. Absent is the safe state; an
        # empty list is a crash.
        assert "scope" not in shell_plugin, (
            f"V-1: shell.scope must be absent, not empty — got "
            f"{shell_plugin.get('scope')!r}; the key panics the shell on start."
        )


# ───────────────────────────────────────────────────────────────── main.rs ──


class TestMainRs:
    def _body(self) -> str:
        return MAIN_RS.read_text(encoding="utf-8")

    def test_main_rs_sets_phantom_packaged_env(self):
        """The refuse-LAN-bind guard (V-4, `main.py:211`) activates ONLY
        when PHANTOM_PACKAGED=1. If the shell forgets to set it, the
        backend would happily bind 0.0.0.0 inside the desktop installer."""
        body = self._body()
        assert '.env("PHANTOM_PACKAGED", "1")' in body, (
            "V-1 LEAK: main.rs must spawn the sidecar with "
            "PHANTOM_PACKAGED=1 — otherwise V-4 refuse-guard never fires."
        )

    def test_main_rs_pins_loopback_host(self):
        """config.py читає `HOST` (pydantic-settings, без префікса). Досі тут
        стояв `PHANTOM_HOST`, якого не читає ніхто: вузол у пакеті лишався на
        0.0.0.0 і refuse-guard V-4 відмовляв йому стартувати (перевірено 26.09)."""
        body = self._body()
        assert '.env("HOST", "127.0.0.1")' in body, (
            "main.rs must set HOST=127.0.0.1 — the field config.py actually reads."
        )
        assert '"PHANTOM_HOST"' not in body, "PHANTOM_HOST is read by nobody"
        from config import PhantomConfig

        assert "host" in PhantomConfig.model_fields

    def test_main_rs_spawns_the_onedir_node(self):
        body = self._body()
        assert '.join("phantom-backend").join(exe)' in body
        assert "std::process::Command::new(&exe)" in body
        # Гасіння чемне: SIGTERM, 8 с на lifespan (заміряно ~4,5), лише тоді kill;
        # оболонку вбито — ядро саме шле вузлу SIGTERM (PR_SET_PDEATHSIG).
        assert "libc::SIGTERM" in body and "Duration::from_secs(8)" in body and "PR_SET_PDEATHSIG" in body

    def test_main_rs_terminates_sidecar_on_exit(self):
        body = self._body()
        # On RunEvent::ExitRequested or RunEvent::Exit the shell must call
        # kill() on the stored CommandChild handle. Without this the sidecar
        # leaks across app restarts and ports stay bound.
        assert "RunEvent::ExitRequested" in body
        assert "RunEvent::Exit" in body
        assert ".kill()" in body, (
            "V-1 LEAK: main.rs must kill() the sidecar on exit — orphan "
            "uvicorn keeps :8000 bound across re-launches."
        )

    def test_main_rs_windows_subsystem_is_windows_in_release(self):
        """Cosmetic but required: without the cfg_attr, a Windows release
        build pops a console window alongside the WebView."""
        body = self._body()
        assert "windows_subsystem" in body
        assert "all(not(debug_assertions), target_os = \"windows\")" in body

    def test_ready_mode_single_instance_tray_autostart(self):
        """Напоготові: другий запуск показує наявне вікно (single-instance —
        першим плагіном), закрите вікно ховається, автозапуск із --hidden,
        трей лише коли бібліотека є (паніка в релізі = abort)."""
        body = self._body()
        ready = (TAURI_DIR / "src" / "ready.rs").read_text(encoding="utf-8")
        assert body.index("tauri_plugin_single_instance::init") < body.index("tauri_plugin_shell::init")
        assert "api.prevent_close()" in body and "ready::load(app_handle).ready" in body
        assert 'HIDDEN_ARG: &str = "--hidden"' in ready and "Some(vec![ready::HIDDEN_ARG])" in body
        assert "tray_supported()" in body and "dlopen" in ready
        conf = json.loads((TAURI_DIR / "tauri.conf.json").read_text(encoding="utf-8"))
        assert conf["app"]["windows"][0]["visible"] is False  # показує setup, якщо не --hidden
        assert "libayatana-appindicator3-1" in conf["bundle"]["linux"]["deb"]["depends"]

    def test_windows_node_has_no_console_and_dies_with_shell(self):
        """Windows: вузол — консольна програма (PyInstaller). Без
        CREATE_NO_WINDOW поруч із PHANTOM відкривалось би чорне вікно консолі;
        без Job Object із KILL_ON_JOB_CLOSE вбита оболонка лишала б вузол
        сиротою на :8000 (PR_SET_PDEATHSIG там нема)."""
        body = self._body()
        assert "creation_flags(CREATE_NO_WINDOW)" in body
        assert "0x0800_0000" in body
        assert "JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE" in body
        assert "winjob::die_with_shell(&child)" in body
        cargo = (TAURI_DIR / "Cargo.toml").read_text(encoding="utf-8")
        assert "Win32_System_JobObjects" in cargo


# ─────────────────────────────────────────────────────────── splash gate ──


class TestDoorHandoff:
    """Квиток входу оболонка передає вебу, щойно вузол відповів: лише Rust
    знає ключ дверей і не віддає його вебу."""

    def test_gate_polls_health_on_loopback_only(self):
        body = MAIN_RS.read_text(encoding="utf-8")
        assert '"http://127.0.0.1:8000"' in body
        assert '"/health"' in body, "gate on /health: in messenger-only mode /readyz is 503"
        assert "http://localhost" not in body

    def test_ticket_is_handed_to_the_web_not_the_key(self):
        body = MAIN_RS.read_text(encoding="utf-8")
        assert "__phantomDoorTicket" in body and "phantom-door" in body
        web = (REPO_ROOT / "src" / "frontend" / "src" / "services" / "doorTicket.ts").read_text(encoding="utf-8")
        assert "export function onDoorTicket" in web


# ─────────────────────────────────────────────────────── adr cross-check ──


class TestAdrCrossReference:
    def test_adr_desktop_shell_doc_present(self):
        """V-1 closes ADR-DSH-001. The ADR file is the contract of record;
        if it disappears the scaffold is unanchored."""
        adr = REPO_ROOT / "docs" / "architecture" / "desktop-shell.md"
        assert adr.is_file(), (
            "V-1: docs/architecture/desktop-shell.md missing — Phase-2 "
            "decisions evaporated."
        )
        body = adr.read_text(encoding="utf-8")
        assert "ADR-DSH-001" in body
        assert "_refuse_lan_bind_in_packaged_mode" in body
