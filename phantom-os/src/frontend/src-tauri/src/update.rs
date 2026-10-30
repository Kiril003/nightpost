//! Автооновлення PHANTOM для ПК через сервер ліцензій.
//!
//! Оболонка питає `GET <сервер>/api/desktop/update/{target}/{arch}/{версія}`
//! (platform-site/server/desktop_update.py). Довіра — не до сервера, а до
//! підпису: завантажений файл перевіряється minisign-ключем, вшитим у збірку
//! (`PHANTOM_UPDATER_PUBKEY` під час `cargo build`/`tauri build`). Без ключа
//! збірка оновлень не перевіряє зовсім — краще жодного оновлення, ніж
//! неперевірене.
//!
//! Людину питають один раз за запуск, рідною мовою, без слів «sidecar»,
//! «бінарник» чи «підпис»; відмова — це «пізніше», а не помилка.

use std::time::Duration;

use tauri::{AppHandle, Url};
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};
use tauri_plugin_updater::UpdaterExt;

/// Публічний ключ оновлень (вміст `*.key.pub` від `tauri signer generate`).
/// Публічний — ним лише перевіряють; приватний живе в секретах збірки.
const PUBKEY: Option<&str> = option_env!("PHANTOM_UPDATER_PUBKEY");

/// Той самий сервер ліцензій, що й у сайту (`VITE_LICENSE_SERVER`).
const LICENSE_HOST: Option<&str> = option_env!("PHANTOM_LICENSE_HOST");
const DEFAULT_HOST: &str = "https://api.phantom-os.dev";

/// Не одразу на старті: спершу хай прокинеться вузол і відкриється вікно.
const FIRST_CHECK_AFTER: Duration = Duration::from_secs(45);

pub fn pubkey() -> Option<&'static str> {
    PUBKEY.map(str::trim).filter(|k| !k.is_empty())
}

/// `{{target}}`, `{{arch}}`, `{{current_version}}` підставляє сам Tauri.
pub fn endpoint(host: &str) -> Option<Url> {
    let host = host.trim().trim_end_matches('/');
    // Лише HTTPS: маніфест не підписаний, і відкритий канал дозволив би
    // хоча б придушити оновлення. Файл і так перевіряється підписом.
    if !host.starts_with("https://") {
        return None;
    }
    Url::parse(&format!(
        "{host}/api/desktop/update/{{{{target}}}}/{{{{arch}}}}/{{{{current_version}}}}"
    ))
    .ok()
}

pub fn check_later(app: AppHandle) {
    let Some(key) = pubkey() else {
        log::info!("ключа оновлень у цій збірці немає — автооновлення вимкнено");
        return;
    };
    let Some(url) = endpoint(LICENSE_HOST.unwrap_or(DEFAULT_HOST)) else {
        log::warn!("адреса сервера оновлень не HTTPS — автооновлення вимкнено");
        return;
    };
    std::thread::spawn(move || {
        std::thread::sleep(FIRST_CHECK_AFTER);
        tauri::async_runtime::block_on(async move {
            if let Err(e) = check_once(&app, key, url).await {
                log::warn!("перевірка оновлення не вдалась: {e}");
            }
        });
    });
}

async fn check_once(app: &AppHandle, key: &str, url: Url) -> Result<(), Box<dyn std::error::Error>> {
    let updater = app
        .updater_builder()
        .pubkey(key)
        .endpoints(vec![url])?
        .timeout(Duration::from_secs(20))
        .build()?;
    let Some(update) = updater.check().await? else {
        log::info!("встановлено найновішу версію");
        return Ok(());
    };
    let mut text = format!("Вийшла нова версія PHANTOM — {}.", update.version);
    if let Some(notes) = update.body.as_deref().map(str::trim).filter(|n| !n.is_empty()) {
        text.push_str("\n\n");
        text.push_str(notes);
    }
    text.push_str("\n\nОновити зараз? PHANTOM перезапуститься, розмови й налаштування збережуться.");
    let yes = app
        .dialog()
        .message(text)
        .title("Оновлення PHANTOM")
        .kind(MessageDialogKind::Info)
        .buttons(MessageDialogButtons::OkCancelCustom("Оновити".into(), "Пізніше".into()))
        .blocking_show();
    if !yes {
        log::info!("оновлення {} відкладено", update.version);
        return Ok(());
    }
    // Підпис перевіряється всередині: файл, що не зійшовся з ключем, не ставиться.
    update.download_and_install(|_, _| {}, || {}).await?;
    app.restart();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn endpoint_keeps_tauri_placeholders_and_requires_https() {
        let url = endpoint("https://api.phantom-os.dev/").unwrap();
        assert_eq!(
            url.as_str(),
            "https://api.phantom-os.dev/api/desktop/update/%7B%7Btarget%7D%7D/%7B%7Barch%7D%7D/%7B%7Bcurrent_version%7D%7D"
        );
        assert!(endpoint("http://api.phantom-os.dev").is_none());
        assert!(endpoint("").is_none());
    }
}
