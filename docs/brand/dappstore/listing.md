# Nightpost у Solana dApp Store — лістинг

Чернетка тексту для порталу [publish.solanamobile.com](https://publish.solanamobile.com), англійською
(основна мова лістингу) з українським перекладом. Межі полів звірено 04.10.2026 з документацією й кодом
порталу (див. `docs/brand/BRIEF.md`, §7.1).

**Правило одне:** у лістингу лише те, що робить APK, який реально подаємо. Гроші — завжди з
позначкою «test network / тестова мережа (devnet)». Ніде не пишемо «secure», «unhackable»,
«AI-powered», «military-grade», не вигадуємо користувачів, цифри чи партнерів. Слова «Phantom» у
лістингу, на картинках і в кадрах бути не може (політика видавця, категорія відмови
«Branding & Impersonation»).

## Поля

| Поле | EN | UA | Межа |
|---|---|---|---|
| Назва (dApp name) | `Nightpost` (9) | `Nightpost` (9) — латиницею, не транслітеруємо | ≤ 25 |
| Підзаголовок (subtitle) | `Messages and maps, offline` (26) | `Розмови й мапа без інтернету` (28) | пишемо до 30 (документація), портал приймає 50 |
| Запасний підзаголовок | `Chat and map when the net dies` (30) | `Звʼязок і мапа без мережі` (25) | ≤ 30 |
| Опис | нижче, 1937 знаків | нижче, 1869 знаків | ≤ 10 000 |
| What's new | не потрібне для першого випуску | — | обовʼязкове з другого |
| Editor's Choice: заголовок | `Messages and maps that keep working offline` (43) | `Повідомлення й мапа, що працюють без мережі` (43) | ≤ 50 |
| Editor's Choice: мітка | `Offline` (7) | `Без мережі` (10) | ≤ 12 |
| Мови | `en-US`, `uk-UA` | | ≥ 1 |
| Категорія | призначає Solana Mobile після схвалення | | — |

## Картинки (усе в цій теці)

| Файл | Розмір | Примітка |
|---|---|---|
| `icon-512.png` | 512×512, PNG RGB без альфа-каналу | на повний квадрат; кути заокруглює крамниця |
| `banner-1200x600.png` | 1200×600, PNG RGB | знак втиснено в рельєф Києва; зона під іконкою (x 5–38 %, y 84–100 %) порожня |
| `editors-choice-1200x1200.png` | 1200×1200, PNG RGB | необовʼязкове поле |
| `frame/` → `previews/` | 1080×2400, PNG RGB, ≤ 3 МБ | 4–8 кадрів, усі одного розміру; див. «Кадри» |

Джерела SVG — `src/`; усе перебудовує `python docs/brand/src/build.py`.

## Опис — English

```text
Nightpost is a messenger and a map that keep working when the network doesn't.

It was built in Ukraine, where blackouts and mobile-internet cuts are part of an ordinary week. It is for anyone who wants their phone to stay useful on a bad night.

MESSAGES
• Messages, photos and voice notes, encrypted end to end. No phone number, no email, no account: people join by invitation or find you by @name.
• Phones on the same Wi-Fi or hotspot talk to each other directly, with no internet.
• When someone is away, the message waits for them on our server for up to 7 days, sealed. The server cannot read it.
• Voice and video calls between phones.

MAP
• OpenStreetMap vector map with turn-by-turn directions on foot and by car.
• Routes are worked out on your phone from a road pack you download once, so directions work with no network.
• Air-raid alerts and nearby shelters, with their status confirmed by people.

MONEY, ON THE TEST NETWORK
• A wallet that only you hold: 12 words, the key kept in the phone's secure storage, every signature behind your fingerprint or PIN. Mobile Wallet Adapter and Seed Vault are supported.
• Send SOL, USDC or EURC from a conversation. Addresses are exchanged inside the encrypted chat, so nobody has to copy one.
• Seal a file: only its SHA-256 fingerprint goes on-chain, in a memo. The file stays with you.
• On Seeker, Nightpost recognises the Seeker Genesis Token.
• The wallet starts on Solana devnet, the test network, with test money. The main network is used only if you switch to it yourself.

LIGHT
The app's light follows the sun where you are: it turns to night after dusk and back to day at dawn.

ASSISTANT
Speech recognition runs on the phone. A cloud model is used only with your own key.

PRIVACY
No ads. No analytics.

PLEASE NOTE
Nightpost is a tool, not a guarantee of safety. Blockchain transfers are public and final. Keep your 12 words on paper; nobody can restore them for you.
```

## Опис — українською

```text
Nightpost — месенджер і мапа, які працюють, коли мережа ні.

Його зробили в Україні, де відключення світла й мобільного інтернету — звичайний тиждень. Він для всіх, кому телефон має лишатися корисним у погану ніч.

РОЗМОВИ
• Повідомлення, фото й голосові, зашифровані від телефона до телефона. Без номера, без пошти, без акаунта: людей додають запрошенням або за @іменем.
• Телефони в одній Wi-Fi мережі чи на одній точці доступу говорять напряму, без інтернету.
• Якщо людини немає поруч, лист чекає на нашому сервері до 7 днів, запечатаний. Сервер не може його прочитати.
• Голосові й відеодзвінки між телефонами.

МАПА
• Векторна мапа OpenStreetMap із покроковою навігацією пішки й автом.
• Маршрут рахує сам телефон із пакета доріг, який ви завантажуєте один раз, тож навігація працює без мережі.
• Повітряні тривоги й укриття поруч, стан яких підтверджують люди.

ГРОШІ — У ТЕСТОВІЙ МЕРЕЖІ
• Гаманець, який тримаєте лише ви: 12 слів, ключ у захищеному сховищі телефона, кожен підпис — відбитком пальця або PIN-кодом. Підтримано Mobile Wallet Adapter і Seed Vault.
• SOL, USDC чи EURC — просто з розмови. Адреси передаються всередині зашифрованої розмови, тож нікому не треба їх копіювати.
• Засвідчення файла: у блокчейн іде лише його відбиток SHA-256, у мемо. Сам файл лишається у вас.
• На Seeker Nightpost упізнає Seeker Genesis Token.
• Гаманець починає з devnet — тестової мережі Solana з тестовими грошима. Основна мережа вмикається лише вашим власним перемикачем.

СВІТЛО
Світло застосунку йде за сонцем там, де ви: після сутінків стає ніч, на світанку — знову день.

ПОМІЧНИК
Мова розпізнається на телефоні. Хмарна модель — лише з вашим власним ключем.

ПРИВАТНІСТЬ
Без реклами. Без аналітики.

ВАЖЛИВО
Nightpost — інструмент, а не гарантія безпеки. Перекази в блокчейні публічні й остаточні. Тримайте 12 слів на папері: ніхто не зможе відновити їх за вас.
```

## Рядки «лише якщо» — додавати, тільки коли це є в поданому APK

Кожен рядок нижче правдивий у коді, але ще не доведений у **поданій** збірці. Вставляти в розділ
MONEY / ГРОШІ лише після перевірки на двох телефонах із тим самим APK.

| Умова | EN | UA |
|---|---|---|
| Готівка без інтернету: є прогін на двох телефонах і Bluetooth-передача є у флейворі, який подаємо (`seeker`, а не лише `field`) | `• Cash without internet, on the test network: fill a pocket once while online, then hand signed notes to a phone nearby. Whoever gets online first settles the note, and a copy of a note that was already spent is rejected.` | `• Готівка без інтернету, у тестовій мережі: поповніть кишеню один раз, поки є звʼязок, а тоді передавайте підписані купюри телефону поруч. Хто першим вийде в мережу, той купюру й зарахує, а копію вже витраченої купюри буде відхилено.` |
| Помічник складає платіж: функція є в поданому APK | `• Ask Nightpost to prepare a payment in plain words. You check it and sign.` | `• Попросіть Nightpost підготувати платіж звичайними словами. Ви перевіряєте й підписуєте.` |

Підстава: доказ у devnet (SOL 02.10, USDC і EURC 03.10, відхилені «близнюки») — `docs/solana/DEVNET_PROOF.md`
на `cloud/sol-wedge`; прогону на двох апаратах у репозиторії станом на 04.10 немає.

## Кадри (dApp Preview)

Портал хоче 4–8 кадрів одного розміру; беремо 1080×2400, портрет. Кожен кадр — **справжній знімок
поданого APK**, загорнутий у рамку `frame/frame.html`. Ніяких макетів і вигаданих екранів.

1. Знімки 1080×2400 з телефона: нічна тема, англійський інтерфейс, лише демо-контакти, чистий
   статус-бар (demo mode). Покласти в `frame/captures/` під іменами з `frames.en.tsv`.
2. `bash docs/brand/dappstore/frame/render.sh frames.en.tsv ../previews/en`
   (і `frames.uk.tsv ../previews/uk`, якщо робимо локалізовані кадри). Скрипт запускає один браузер під
   спільним замком `/tmp/phantom-web.lock`, перевіряє 1080×2400 і ≤ 3 МБ.
3. Приклад порожньої рамки: `frame/example-frame.png`.

| № | Знімок | EN | UA |
|---|---|---|---|
| 1 | список розмов | Messenger · Talk when the internet is down. | Розмови · Пишіть, навіть коли інтернету немає. |
| 2 | розмова | Messenger · Sealed until it arrives. | Розмови · Запечатано, доки не дійде. |
| 3 | маршрут | Map · Directions without a network. | Мапа · Маршрут без мережі. |
| 4 | укриття | Map · Alerts and shelters nearby. | Мапа · Тривоги й укриття поруч. |
| 5 | гаманець | Money · test network · Your keys stay on your phone. | Гроші · тестова мережа · Ключ лишається у вашому телефоні. |
| 6 | переказ у розмові | Money · test network · Pay a person, not an address. | Гроші · тестова мережа · Платіть людині, а не адресі. |
| 7 | засвідчення файла | Proof · Seal a file. | Доказ · Засвідчіть файл. |
| 8 | готівка (лише якщо) | Money · test network · Cash without internet. | Гроші · тестова мережа · Готівка без інтернету. |

Повні підписи (з другим рядком) — у `frame/frames.en.tsv` і `frame/frames.uk.tsv`.

**Перш ніж знімати:** у теперішньому інтерфейсі в списку розмов стоїть «PHANTOM» (помічник), а
`app_name` = "PHANTOM". Такий кадр подавати не можна. Спершу перейменування в застосунку.

## Що вирішує власник до подання

1. **Імʼя пакета.** Зараз `local.phantom.companion(.seeker)`. Воно публічне в посиланні
   `solanadappstore://details?id=<package>` і навічно привʼязане до лістингу: змінити його після першого
   подання — це новий лістинг. Вирішити до першого подання.
2. **Назва в застосунку.** `app_name`, «Спитати PHANTOM», `aria-label="PHANTOM"` → Nightpost.
3. **Адреси для полів порталу.** Сайт, Terms of Use, Privacy Policy, пошта підтримки й контактна пошта —
   усі https і обовʼязкові. Домен `phantom-os.dev` несе старе імʼя; потрібна адреса без нього.
4. **Пошук знака.** Перед витратами на знак — пошук EUIPO і USPTO у класах 9, 38 і 36.
5. **Порівняння на справжньому телефоні.** Іконка Nightpost поруч із Phantom, Solflare, Backpack і Jupiter
   на одному головному екрані (див. `BRAND.md`, «Перевірка»).

## Звідки кожне твердження

| Твердження | Джерело |
|---|---|
| E2E, без номера й акаунта, запрошення, @імʼя | `BRIEF.md` §2 (cloud/beta @ b629fd08) |
| Телефони в одній Wi-Fi напряму | там само |
| Лист чекає до 7 днів, сервер не читає | там само («сховок») |
| Дзвінки між телефонами | там само |
| Мапа OSM, навігація, маршрути на телефоні з пакетів доріг | там само |
| Тривоги й укриття з підтвердженням людей | там само |
| Гаманець: 12 слів, Keystore, біометрія/PIN, MWA, Seed Vault | `BRIEF.md` §2 (cloud/sol-wedge @ 3c90e4ce) |
| SOL, USDC, EURC з розмови; адреси всередині E2E | там само |
| Засвідчення файла: лише SHA-256 у Memo | там само |
| Seeker Genesis Token | там само |
| Devnet типово, основна мережа — лише перемикачем користувача | там само |
| Світло за сонцем (`LivingSun`) | `BRIEF.md` §2 |
| Розпізнавання мови на телефоні; хмара лише з власним ключем | там само |
| Без реклами й аналітики | у `phantom-companion/**/build.gradle.kts` на cloud/beta немає SDK реклами чи аналітики (перевірено 04.10) |
