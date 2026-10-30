// PHANTOM напоготові — клік відкриває Головну миттєво.
//
// Вузол прокидається ~5 с (Python, база, схема). Жоден трюк із пакуванням
// цього не прибирає — прибирає лише вузол, що вже не спить. Тож коли режим
// увімкнено:
//   - оболонка стартує разом із системою (`--hidden`: без вікна) і тримає
//     вузол у фоні, зі значком у треї;
//   - «закрити вікно» ховає його, а не гасить PHANTOM; «Вийти з PHANTOM» у
//     треї гасить усе;
//   - повторний запуск не піднімає другий PHANTOM (і другий вузол на тому
//     самому :8000) — він показує вікно того, що вже живе.
// Ціна — вузол постійно тримає пам'ять, тому це перемикач у Налаштуваннях.
//
// Налаштування живе у файлі оболонки (`desk.json` у теці конфігурації), а
// не у вузлі: оболонка мусить знати його ДО того, як вузол прокинеться.

use std::path::PathBuf;

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager, Runtime};
use tauri_plugin_autostart::ManagerExt;

pub const HIDDEN_ARG: &str = "--hidden";

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq)]
pub struct DeskPrefs {
    /// Напоготові: з системою, у треї, закрите вікно лише ховається.
    pub ready: bool,
}

impl Default for DeskPrefs {
    fn default() -> Self {
        // Власник просив, щоб PHANTOM відкривався миттєво; вимкнути — один
        // перемикач у Налаштуваннях.
        Self { ready: true }
    }
}

fn prefs_path<R: Runtime>(app: &AppHandle<R>) -> Option<PathBuf> {
    app.path().app_config_dir().ok().map(|d| d.join("desk.json"))
}

pub fn parse_prefs(raw: &str) -> DeskPrefs {
    serde_json::from_str(raw).unwrap_or_default()
}

pub fn load<R: Runtime>(app: &AppHandle<R>) -> DeskPrefs {
    // Запуск розробника (scripts/phantom-desk) — вікно й вузол живуть і
    // гаснуть разом, без трея й автозапуску.
    if std::env::var("PHANTOM_READY").as_deref() == Ok("0") {
        return DeskPrefs { ready: false };
    }
    prefs_path(app)
        .and_then(|p| std::fs::read_to_string(p).ok())
        .map(|raw| parse_prefs(&raw))
        .unwrap_or_default()
}

fn save<R: Runtime>(app: &AppHandle<R>, prefs: DeskPrefs) -> Result<(), String> {
    let path = prefs_path(app).ok_or("нема теки конфігурації")?;
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    }
    std::fs::write(&path, serde_json::to_string(&prefs).unwrap()).map_err(|e| e.to_string())
}

/// Автозапуск із системою — за станом режиму. Помилка не валить оболонку.
pub fn apply_autostart<R: Runtime>(app: &AppHandle<R>, ready: bool) {
    // На свіжому профілі Linux теки ~/.config/autostart ще нема, а
    // auto-launch її не створює: «No such file or directory» — і PHANTOM
    // мовчки не стартував би з системою.
    #[cfg(target_os = "linux")]
    if ready {
        if let Ok(dir) = app.path().config_dir() {
            let _ = std::fs::create_dir_all(dir.join("autostart"));
        }
    }
    let launcher = app.autolaunch();
    let res = if ready { launcher.enable() } else { launcher.disable() };
    if let Err(e) = res {
        log::warn!("автозапуск не змінено: {e}");
    }
}

pub fn started_hidden() -> bool {
    std::env::args().any(|a| a == HIDDEN_ARG)
}

pub fn show_main<R: Runtime>(app: &AppHandle<R>) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

#[tauri::command]
pub fn desk_ready_get(app: AppHandle) -> bool {
    load(&app).ready
}

#[tauri::command]
pub fn desk_ready_set(app: AppHandle, ready: bool) -> Result<bool, String> {
    save(&app, DeskPrefs { ready })?;
    apply_autostart(&app, ready);
    Ok(ready)
}

/// Чи є на Linux бібліотека значка в треї. Без неї tray-icon панікує, а в
/// релізі паніка — це `abort`: краще без трея, ніж без PHANTOM.
#[cfg(target_os = "linux")]
pub fn tray_supported() -> bool {
    ["libayatana-appindicator3.so.1", "libappindicator3.so.1"].iter().any(|lib| {
        let name = std::ffi::CString::new(*lib).unwrap();
        let h = unsafe { libc::dlopen(name.as_ptr(), libc::RTLD_LAZY) };
        if h.is_null() {
            false
        } else {
            unsafe { libc::dlclose(h) };
            true
        }
    })
}

#[cfg(not(target_os = "linux"))]
pub fn tray_supported() -> bool {
    true
}

/// Значок у треї: відкрити / вийти. Без трея режим однаково корисний
/// (автозапуск і ховання), але сховане вікно тоді відкривається лише
/// повторним запуском — single-instance покаже наявне.
pub fn build_tray(app: &tauri::App) -> tauri::Result<()> {
    use tauri::menu::{Menu, MenuItem};
    use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};

    let open = MenuItem::with_id(app, "open", "Відкрити PHANTOM", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Вийти з PHANTOM", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&open, &quit])?;
    let mut tray = TrayIconBuilder::with_id("phantom")
        .tooltip("PHANTOM")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, e| match e.id.as_ref() {
            "open" => show_main(app),
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, e| {
            if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = e {
                show_main(tray.app_handle());
            }
        });
    if let Some(icon) = app.default_window_icon() {
        tray = tray.icon(icon.clone());
    }
    tray.build(app)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prefs_default_ready_and_tolerate_garbage() {
        assert!(DeskPrefs::default().ready);
        assert!(parse_prefs("").ready);
        assert!(parse_prefs("{не json").ready);
        assert!(!parse_prefs(r#"{"ready":false}"#).ready);
        assert!(parse_prefs(r#"{"ready":true}"#).ready);
    }
}
