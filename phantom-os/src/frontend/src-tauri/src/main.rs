// PHANTOM для ПК — настільна оболонка.
//
// Вікно показує вбудований веб (`../dist`) з `tauri://localhost`; до вузла
// веб ходить абсолютною адресою петлі (`services/nodeOrigin.ts`), яку пускає
// CSP вікна й CORS вузла.
//
// Порядок:
//   1. Якщо в пакеті є вузол (PyInstaller onedir, scripts/build_sidecar.sh)
//      — піднімаємо його на петлі (`HOST=127.0.0.1` — саме це поле читає
//      config.py; без нього вузол у пакеті відмовляється стартувати, бо
//      бачить 0.0.0.0). Повний режим: моделі гріються фоном, `/health` за
//      ~4–5 с. Немає або не стартував — ідемо до вузла, що вже працює
//      (`PHANTOM_NODE_URL`, типово 127.0.0.1:8000).
//   2. Вікно відкривається одразу на вбудованому вебі: PHANTOM прокидається
//      на очах (DeskWaking), а не за сплешем. Веб сам стежить за `/health`.
//   3. Окремий потік чекає `/health` і, якщо відомий ключ дверей
//      (`PHANTOM_DOOR_KEY_FILE` або той, що створить наш sidecar), просить
//      одноразовий квиток і передає його вебу (`security/door.py`,
//      services/doorTicket.ts). Ключа немає — веб чесно попросить PIN.
//   4. На виході гасимо sidecar разом з оболонкою.

#![cfg_attr(
    all(not(debug_assertions), target_os = "windows"),
    windows_subsystem = "windows"
)]

use std::io::{Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use tauri::{AppHandle, Manager, RunEvent, State};

struct SidecarHandle(Mutex<Option<std::process::Child>>);

/// Куди йти: `PHANTOM_NODE_URL` або петля :8000 — там, де вузол слухає HTTP.
fn node_base() -> String {
    std::env::var("PHANTOM_NODE_URL")
        .ok()
        .map(|s| s.trim().trim_end_matches('/').to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "http://127.0.0.1:8000".to_string())
}

/// `http://host:port` → `host:port`. Інших схем оболонка не обслуговує:
/// вузол для робочого столу — відкритий HTTP на петлі (docs/PC_STATE.md).
fn host_port(base: &str) -> Option<String> {
    let rest = base.strip_prefix("http://")?;
    let hp = rest.split('/').next()?;
    Some(if hp.contains(':') { hp.to_string() } else { format!("{hp}:80") })
}

/// Мінімальний HTTP/1.1 без залежностей: один запит, `Connection: close`.
fn http(base: &str, method: &str, path: &str, headers: &[(&str, &str)]) -> Option<(u16, String)> {
    let hp = host_port(base)?;
    let addr = hp.to_socket_addrs().ok()?.next()?;
    let mut s = TcpStream::connect_timeout(&addr, Duration::from_millis(800)).ok()?;
    s.set_read_timeout(Some(Duration::from_secs(5))).ok()?;
    let mut req = format!("{method} {path} HTTP/1.1\r\nHost: {hp}\r\nConnection: close\r\nContent-Length: 0\r\n");
    for (k, v) in headers {
        req.push_str(&format!("{k}: {v}\r\n"));
    }
    req.push_str("\r\n");
    s.write_all(req.as_bytes()).ok()?;
    let mut raw = String::new();
    s.read_to_string(&mut raw).ok()?;
    let status = raw.split_whitespace().nth(1)?.parse().ok()?;
    let body = raw.split_once("\r\n\r\n").map(|(_, b)| b.to_string()).unwrap_or_default();
    Some((status, body))
}

/// Квиток дверей. `None` — ключа немає або вузол дверей не має; тоді PIN.
fn door_ticket(base: &str, key_file: &Option<PathBuf>) -> Option<String> {
    let key = std::fs::read_to_string(key_file.as_ref()?).ok()?;
    let key = key.trim();
    if key.is_empty() {
        return None;
    }
    let (status, body) = http(base, "POST", "/api/v1/auth/door/issue", &[("X-Phantom-Door-Key", key)])?;
    if status != 200 {
        log::warn!("вузол не видав квитка дверей: HTTP {status}");
        return None;
    }
    let v: serde_json::Value = serde_json::from_str(body.trim()).ok()?;
    v.get("ticket")?.as_str().map(str::to_string)
}

/// Кладе квиток у веб: `window.__phantomDoorTicket` + подія `phantom-door`
/// (services/doorTicket.ts → onDoorTicket). JSON-рядок — щоб квиток не
/// розірвав JS, хоч який він.
fn hand_ticket(app: &AppHandle, ticket: &str) {
    if let Some(w) = app.get_webview_window("main") {
        let js = format!(
            "window.__phantomDoorTicket={};window.dispatchEvent(new Event('phantom-door'));",
            serde_json::Value::String(ticket.to_string())
        );
        let _ = w.eval(&js);
    }
}

/// Вікно вже малює PHANTOM, що прокидається (DeskWaking); тут — лише вхід:
/// щойно вузол відповів на `/health`, беремо квиток дверей і передаємо вебу.
/// Передаємо кілька разів: перший `eval` може випередити завантаження веба,
/// а той самий квиток веб удруге не бере.
fn enter_when_ready(app: AppHandle, key_file: Option<PathBuf>, spawned: bool) {
    std::thread::spawn(move || {
        let base = node_base();
        let t0 = Instant::now();
        let mut told_missing = false;
        while !matches!(http(&base, "GET", "/health", &[]), Some((200, _))) {
            if !told_missing && (sidecar_gone(&app, spawned) && t0.elapsed() > Duration::from_secs(5)) {
                log::error!("вузла немає і ніщо не відповідає — веб покаже, що пакет неповний");
                tell_missing(&app);
                told_missing = true;
            }
            if t0.elapsed() > Duration::from_secs(600) {
                log::error!("вузол не відповів за 10 хв — вхід квитком скасовано");
                return;
            }
            std::thread::sleep(Duration::from_millis(250));
        }
        log::info!("вузол відповів за {:.1} с", t0.elapsed().as_secs_f32());
        let Some(ticket) = door_ticket(&base, &key_file) else {
            log::info!("ключа дверей немає — веб попросить PIN");
            return;
        };
        for _ in 0..8 {
            hand_ticket(&app, &ticket);
            std::thread::sleep(Duration::from_millis(600));
        }
    });
}

/// Вузла не піднято, або він уже помер.
fn sidecar_gone(app: &AppHandle, spawned: bool) -> bool {
    if !spawned {
        return std::env::var_os("PHANTOM_NODE_URL").is_none();
    }
    let state: State<SidecarHandle> = app.state();
    let mut guard = state.0.lock().expect("sidecar handle poisoned");
    matches!(guard.as_mut().map(|c| c.try_wait()), Some(Ok(Some(_))))
}

fn tell_missing(app: &AppHandle) {
    let app = app.clone();
    std::thread::spawn(move || {
        for _ in 0..8 {
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.eval("window.__nightpostNodeMissing=true;window.dispatchEvent(new Event('nightpost-node-missing'));");
            }
            std::thread::sleep(Duration::from_millis(600));
        }
    });
}

/// Як запустити вузол: програма, аргументи, робоча тека.
struct NodeLaunch {
    exe: PathBuf,
    args: Vec<std::ffi::OsString>,
    cwd: PathBuf,
}

/// Де лежить вузол, у порядку:
///   1. явний `PHANTOM_SIDECAR` (перевірка збірки без пакета) — програма;
///   2. `phantom-node/` у ресурсах пакета — справжній CPython + код вузла
///      (scripts/build_node_runtime.sh): `python -X utf8 -E -s app/_phantom_entry.py`;
///   3. `phantom-backend/` — старий вузол PyInstaller (scripts/build_sidecar.sh).
fn node_launch(app: &AppHandle) -> Option<NodeLaunch> {
    if let Some(p) = std::env::var_os("PHANTOM_SIDECAR").map(PathBuf::from) {
        let cwd = p.parent()?.to_path_buf();
        return p.is_file().then_some(NodeLaunch { exe: p, args: vec![], cwd });
    }
    let res = app.path().resource_dir().ok()?;
    let node = res.join("phantom-node");
    let python = if cfg!(windows) {
        node.join("python").join("python.exe")
    } else {
        node.join("python").join("bin").join("python3")
    };
    let entry = node.join("app").join("_phantom_entry.py");
    if python.is_file() && entry.is_file() {
        // -X utf8: дані вузла українською, а Python без UTF-8-локалі читає їх як
        // ASCII/cp1252; -E -s: чужі PYTHON* і користувацький site не лізуть у вузол.
        let args = ["-X", "utf8", "-E", "-s"].iter().map(Into::into).chain([entry.clone().into_os_string()]).collect();
        return Some(NodeLaunch { exe: python, args, cwd: node.join("app") });
    }
    let exe = if cfg!(windows) { "phantom-backend.exe" } else { "phantom-backend" };
    let legacy = res.join("phantom-backend").join(exe);
    legacy.is_file().then(|| NodeLaunch { cwd: legacy.parent().unwrap().to_path_buf(), exe: legacy, args: vec![] })
}

fn spawn_backend_sidecar(app: &AppHandle) -> Option<PathBuf> {
    let Some(launch) = node_launch(app) else {
        log::info!("вузла в пакеті немає — іду до того, що вже працює");
        return None;
    };
    let exe = launch.exe.clone();
    let data = app.path().app_data_dir().ok()?;
    let key_file = data.join("run").join("door.key");
    let _ = std::fs::create_dir_all(key_file.parent()?);
    let _ = std::fs::remove_file(&key_file);
    let mut cmd = std::process::Command::new(&exe);
    cmd.args(&launch.args);
    // Заморожений Python без UTF-8-локалі читає alembic.ini і дані вузла як
    // ASCII — і вузол не стартує (перевірено 26.09 на зібраному sidecar).
    // Немає локалі в системі — даємо C.UTF-8; є — не чіпаємо.
    #[cfg(unix)]
    if !std::env::var("LC_ALL").or_else(|_| std::env::var("LANG")).map(|v| v.to_uppercase().contains("UTF-8") || v.to_uppercase().contains("UTF8")).unwrap_or(false) {
        cmd.env("LC_ALL", "C.UTF-8");
    }
    // Оболонку можуть не закрити, а вбити — тоді RunEvent::Exit не настане.
    // Ядро Linux саме пошле вузлу SIGTERM, щойно батька не стало.
    #[cfg(target_os = "linux")]
    unsafe {
        use std::os::unix::process::CommandExt;
        cmd.pre_exec(|| {
            libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGTERM);
            Ok(())
        });
    }
    // Windows: вузол — консольна програма; без CREATE_NO_WINDOW людина
    // побачила б чорне вікно консолі поруч із PHANTOM.
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    let spawned = cmd
        .current_dir(&launch.cwd)
        .env("PHANTOM_PACKAGED", "1")
        // config.py читає HOST/PORT; PHANTOM_HOST не читає ніхто.
        .env("HOST", "127.0.0.1")
        .env("PORT", "8000")
        .env("PHANTOM_DATA_DIR", &data)
        .env("PHANTOM_DOOR_KEY_FILE", &key_file)
        .spawn();
    match spawned {
        Ok(child) => {
            log::info!("вузол піднято з {} (pid {})", exe.display(), child.id());
            #[cfg(windows)]
            winjob::die_with_shell(&child);
            let state: State<SidecarHandle> = app.state();
            *state.0.lock().expect("sidecar handle poisoned") = Some(child);
            Some(key_file)
        }
        Err(e) => {
            log::warn!("вузол не стартував ({e}) — іду до того, що вже працює");
            None
        }
    }
}

mod ready;
mod update;

fn main() {
    env_logger::init();

    tauri::Builder::default()
        // Першим: другий запуск не піднімає другий PHANTOM і другий вузол на
        // тому самому :8000 — він показує вікно того, що вже живе.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| ready::show_main(app)))
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            Some(vec![ready::HIDDEN_ARG]),
        ))
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .manage(SidecarHandle(Mutex::new(None)))
        .invoke_handler(tauri::generate_handler![ready::desk_ready_get, ready::desk_ready_set])
        .setup(|app| {
            let prefs = ready::load(app.handle());
            ready::apply_autostart(app.handle(), prefs.ready);
            if ready::tray_supported() {
                if let Err(e) = ready::build_tray(app) {
                    log::warn!("значок у треї не створено: {e}");
                }
            } else {
                log::info!("бібліотеки трея нема — PHANTOM без значка в треї");
            }
            // Автозапуск із системою — без вікна: PHANTOM прокидається
            // тихо, а клік по значку чи ярлику відкриває вже живу Головну.
            if !ready::started_hidden() {
                ready::show_main(app.handle());
            }
            let handle = app.handle().clone();
            // Ключ дверей: явний від власника — першим; інакше той, що
            // створить наш sidecar.
            let explicit = std::env::var_os("PHANTOM_DOOR_KEY_FILE").map(PathBuf::from);
            let spawned = if std::env::var_os("PHANTOM_NODE_URL").is_some() {
                None
            } else {
                spawn_backend_sidecar(&handle)
            };
            let has_child = spawned.is_some();
            enter_when_ready(handle.clone(), explicit.or(spawned), has_child);
            update::check_later(handle);
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("оболонка PHANTOM не зібралась")
        .run(|app_handle, event| match event {
            // Напоготові: «закрити» лише ховає вікно — вузол не спить, і
            // наступний клік відкриває Головну миттєво.
            RunEvent::WindowEvent { label, event: tauri::WindowEvent::CloseRequested { api, .. }, .. }
                if label == "main" && ready::load(app_handle).ready =>
            {
                api.prevent_close();
                if let Some(w) = app_handle.get_webview_window("main") {
                    let _ = w.hide();
                }
            }
            RunEvent::ExitRequested { .. } | RunEvent::Exit => {
                terminate_backend_sidecar(app_handle.state::<SidecarHandle>());
            }
            _ => {}
        });
}

/// Гасимо вузол разом з оболонкою: спершу чемно (SIGTERM — вузол
/// завершує lifespan: закриває базу, зупиняє прогрів; заміряно ~4,5 с),
/// за 8 с — силою.
fn terminate_backend_sidecar(state: State<SidecarHandle>) {
    let Some(mut child) = state.0.lock().expect("sidecar handle poisoned").take() else {
        return;
    };
    #[cfg(unix)]
    unsafe {
        libc::kill(child.id() as libc::pid_t, libc::SIGTERM);
    }
    // На Windows чемного сигналу консольній програмі без консолі нема —
    // чекати 8 с було б лише затримкою закриття. SQLite вузла (WAL)
    // переживає зупинку силою.
    #[cfg(windows)]
    {
        let _ = child.kill();
        let _ = child.wait();
        log::info!("вузол зупинено");
        return;
    }
    #[allow(unreachable_code)]
    let t0 = Instant::now();
    while t0.elapsed() < Duration::from_secs(8) {
        if let Ok(Some(_)) = child.try_wait() {
            log::info!("вузол зупинено");
            return;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    let _ = child.kill();
    let _ = child.wait();
    log::warn!("вузол не зупинився за 8 с — зупинено силою");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn host_port_reads_loopback_and_default_port() {
        assert_eq!(host_port("http://127.0.0.1:8000").as_deref(), Some("127.0.0.1:8000"));
        assert_eq!(host_port("http://node.lan/x").as_deref(), Some("node.lan:80"));
        assert_eq!(host_port("https://node.example"), None);
    }

    /// Голосові, відеокружки й розшифровані вкладення месенджер грає з
    /// `blob:`. Без `media-src` CSP падав на `default-src 'self'`, і на ПК
    /// звук і відео в розмовах мовчки не грали (сторож мапи — deskCsp.test.ts).
    #[test]
    fn window_csp_plays_messenger_media_and_has_no_wildcards() {
        let conf: serde_json::Value = serde_json::from_str(include_str!("../tauri.conf.json")).unwrap();
        let csp = conf["app"]["security"]["csp"].as_str().unwrap();
        let directive = |name: &str| -> Vec<String> {
            csp.split(';')
                .map(str::trim)
                .find(|p| p.split_whitespace().next() == Some(name))
                .map(|p| p.split_whitespace().skip(1).map(String::from).collect())
                .unwrap_or_default()
        };
        let media = directive("media-src");
        assert!(media.iter().any(|s| s == "blob:"), "media-src без blob: — голосові на ПК мовчать");
        assert!(directive("connect-src").iter().any(|s| s == "blob:"), "fetch(blob:) запису голосу відбито");
        for part in csp.split(';') {
            for src in part.split_whitespace().skip(1) {
                assert!(!src.contains('*'), "зірочка в CSP: {part}");
                assert!(!matches!(src, "http:" | "https:" | "ws:" | "wss:"), "гола схема в CSP: {part}");
            }
        }
    }

    #[test]
    fn updater_plugin_is_configured_against_downgrades() {
        let conf: serde_json::Value = serde_json::from_str(include_str!("../tauri.conf.json")).unwrap();
        let up = &conf["plugins"]["updater"];
        // Без цього розділу плагін не ініціалізується зовсім (pubkey обов'язковий).
        assert!(up["pubkey"].is_string());
        // Маніфест не підписаний: без вимоги підписаної версії підмінений
        // сервер міг би «оновити» до старішої, але чесно підписаної збірки.
        assert_eq!(up["requireSignedVersion"], serde_json::Value::Bool(true));
    }

    #[test]
    fn node_base_trims_trailing_slash() {
        std::env::set_var("PHANTOM_NODE_URL", "http://127.0.0.1:8123/");
        assert_eq!(node_base(), "http://127.0.0.1:8123");
        std::env::remove_var("PHANTOM_NODE_URL");
        assert_eq!(node_base(), "http://127.0.0.1:8000");
    }
}

/// Windows не має `PR_SET_PDEATHSIG`: якщо оболонку вбили (диспетчер задач,
/// збій), вузол лишився б жити сиротою й тримав би порт :8000 — наступний
/// запуск PHANTOM ішов би до старого процесу. Job Object із
/// `KILL_ON_JOB_CLOSE`: дескриптор завдання тримає лише оболонка, тож коли
/// її не стане, система закриє дескриптор і вб'є вузол.
#[cfg(windows)]
mod winjob {
    use std::os::windows::io::AsRawHandle;
    use windows_sys::Win32::System::JobObjects::{
        AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
        SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
        JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    };

    pub fn die_with_shell(child: &std::process::Child) {
        unsafe {
            let job = CreateJobObjectW(std::ptr::null(), std::ptr::null());
            if job.is_null() {
                log::warn!("Job Object не створено — вузол не прив'язано до оболонки");
                return;
            }
            let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
            info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            let ok = SetInformationJobObject(
                job,
                JobObjectExtendedLimitInformation,
                &info as *const _ as *const core::ffi::c_void,
                std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
            );
            if ok == 0 || AssignProcessToJobObject(job, child.as_raw_handle() as _) == 0 {
                log::warn!("вузол не прив'язано до оболонки (Job Object)");
            }
            // Дескриптор навмисно не закриваємо: він живе, доки живе оболонка.
        }
    }
}
