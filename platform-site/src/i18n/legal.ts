/**
 * Правові умови — п’ять документів на одній сторінці.
 *
 * Кожне твердження тут описує те, що справді робить код: ліцензія
 * перевіряється підписом на пристрої (licensing/verifier.py), вікно оновлень
 * керує ЗБІРКАМИ, а не правом запускати куплену (licensing/entitlements.py),
 * повернення коштів відкликає ключ (server/main.py). Нічого, чого немає в
 * коді, тут стверджувати не можна.
 */
import type { Lang, PageMeta } from './types';
import { FREE_UNTIL, PRICES, euro } from '../lib/pricing.ts';

export interface LegalClause {
  heading: string;
  /** Абзаци. Списки виносимо окремо, щоб не верстати їх рядком. */
  body: string[];
  bullets?: string[];
}

export interface LegalDoc {
  id: 'eula' | 'sale' | 'privacy' | 'app' | 'refund';
  nav: string;
  title: string;
  lead: string;
  clauses: LegalClause[];
}

export interface LegalCopy {
  meta: PageMeta;
  eyebrow: string;
  title: string;
  lead: string;
  revisionLabel: string;
  draftTitle: string;
  draftBody: string;
  notEntityTitle: string;
  notEntityBody: string;
  entityHeading: string;
  entityFields: { legalName: string; registrationId: string; address: string; jurisdiction: string; vatNote: string };
  contactHeading: string;
  contactBody: string;
  docs: LegalDoc[];
}

const uk: LegalCopy = {
  meta: {
    title: 'Правові умови — Nightpost',
    description:
      'Ліцензійна угода, умови продажу, приватність і повернення коштів. Чернетка до реєстрації продавця.',
  },
  eyebrow: 'Правові умови',
  title: 'Що саме ви купуєте і на яких умовах',
  lead:
    'Умови ліцензії, продажу й повернення коштів; приватність — окремою сторінкою. Написані так, щоб їх можна було прочитати без юриста — і так, щоб кожен пункт відповідав тому, що робить код.',
  revisionLabel: 'Редакція від',
  draftTitle: 'Це робоча чернетка, а не чинний договір',
  draftBody:
    'Текст підготувала команда продукту. До будь-якого продажу його має вичитати юрист — особливо в частині споживчих прав і податків у країні покупця.',
  notEntityTitle: 'Продаж ще не відкрито',
  notEntityBody:
    'Суб’єкта господарювання, від імені якого продаватиметься Nightpost, ще не зареєстровано, тож реквізити продавця нижче порожні, а умови не набрали чинності. Ми не вигадуємо назву й адресу заради того, щоб сторінка виглядала повною.',
  entityHeading: 'Продавець',
  entityFields: {
    legalName: 'Назва',
    registrationId: 'Код',
    address: 'Адреса',
    jurisdiction: 'Юрисдикція',
    vatNote: 'Податки',
  },
  contactHeading: 'Як з нами зв’язатися',
  contactBody:
    'Видалити обліковий запис і дані на сервері можна через «Видалити все» в застосунку або на https://phantom-os.dev/delete-account; пошта нижче лишається запасним шляхом і для будь-яких питань щодо цих умов чи доступу до своїх даних. Відповідаємо людиною, а не формою.',
  docs: [
    {
      id: 'eula',
      nav: 'Ліцензійна угода',
      title: 'Ліцензійна угода',
      lead: 'Що дозволяє ключ і чого він не обіцяє.',
      clauses: [
        {
          heading: 'Що ви отримуєте',
          body: [
            'Безстрокову, невиключну ліцензію на використання Nightpost без права передачі третім особам. «Безстрокову» тут означає буквально: збірка, куплена сьогодні, працює на вашому пристрої скільки завгодно довго, незалежно від того, чи існуємо ми і чи відповідає наш сервер.',
            'Ліцензія прив’язується до пристрою під час активації. Кількість одночасних активацій визначає рівень ключа; звільнити місце й перенести ліцензію на інший пристрій можна будь-коли на сторінці «Ліцензія».',
          ],
        },
        {
          heading: 'Вікно оновлень',
          body: [
            'У ціну входить визначений строк оновлень. Він керує тим, які ЗБІРКИ покриває ліцензія, а не тим, чи маєте ви право запускати вже куплену.',
            'Коли строк минув, усе, що ви маєте, працює далі. Якщо ви встановите збірку, випущену після кінця вікна, вона запуститься на вільному рівні, доки оновлення не буде поновлено.',
          ],
        },
        {
          heading: 'Вільний рівень',
          body: [
            'Вільний рівень не потребує ліцензії, не має строку і не вимикається — ні коли ключа немає, ні коли він недійсний, ні коли сервер недосяжний.',
            'Ці можливості відкриті завжди, на будь-якому рівні й у будь-якому стані ліцензії: розмова з ядром, мапа з офлайн-пакетами, навігація, тривоги й попередження, сховище, голос.',
          ],
        },
        {
          heading: 'Чого робити не можна',
          body: [
            'Передавати, перепродавати чи здавати ліцензію в оренду; обходити або підробляти перевірку ліцензії; вивчати зворотним проєктуванням ті частини, для яких це прямо не дозволено чинним законом; вилучати позначення авторства.',
            'Компоненти з відкритим кодом у складі продукту лишаються на своїх ліцензіях; ця угода їх не заміняє й не звужує.',
          ],
        },
        {
          heading: 'Nightpost — інструмент, а не гарантія безпеки',
          body: [
            'Це найважливіший пункт документа, і ми не ховаємо його в кінець.',
            'Nightpost працює там, де інфраструктура ненадійна, і саме тому не може бути єдиним джерелом навігації, попереджень чи рішень у небезпеці. Супутниковий сигнал підробляють, стрічки тривог запізнюються й помиляються, офлайн-мапа старіє з дня пакування, а модель на пристрої може відповісти невірно.',
            'Не покладайся на Nightpost як на єдине джерело. Тримай запасний спосіб зорієнтуватись і запасний спосіб дізнатись про загрозу. Продукт постачається «як є», без гарантій придатності для конкретної мети.',
          ],
        },
        {
          heading: 'Межа відповідальності',
          body: [
            'У межах, дозволених законом, наша сукупна відповідальність за будь-якими вимогами, пов’язаними з продуктом, обмежується сумою, яку ви за нього заплатили.',
            'Це обмеження не стосується випадків, коли закон його не допускає, і не звужує споживчих прав, які надані вам імперативними нормами вашої країни.',
          ],
        },
      ],
    },
    {
      id: 'sale',
      nav: 'Умови продажу',
      title: 'Умови продажу',
      lead: 'Що є предметом покупки і як вона відбувається.',
      clauses: [
        {
          heading: 'Предмет',
          body: [
            `Застосунок безкоштовний. Плани Білдера: Studio ${euro(PRICES.studio)} і Place ${euro(PRICES.place)} на місяць, 0% з продажів. Усе безкоштовно до ${FREE_UNTIL.uk}, до 2027 року нічого не стягується.`,
            'Окремо може пропонуватись поновлення пакетів даних — мап, місць, рельєфу і стрічок тривог. Це справді повторювана робота з нашого боку, тому за неї є повторювана оплата. Уже завантажений пакет лишається вашим і працює без мережі після закінчення поновлення.',
          ],
        },
        {
          heading: 'Ціни й оплата',
          body: [
            'Ціни вказані в євро на місяць. Остаточну суму з податками вашої країни показує платіжна сторінка до підтвердження. Як працюватиме оплата, ми повідомимо до першого списання.',
            'Оплату планується проводити через зовнішнього продавця-агента (merchant of record). На момент цієї редакції платіжного провайдера ще не підключено, і покупки не приймаються.',
            'Ми не бачимо і не зберігаємо реквізитів вашої картки — вони не проходять через наші сервери взагалі.',
          ],
        },
        {
          heading: 'Доставка',
          body: [
            'Ліцензійний ключ надходить на адресу пошти, вказану під час оплати. Ключ і є перепусткою: акаунта створювати не треба, пароля ми не заводимо.',
            'Якщо ключ загубився — напишіть нам із тієї самої адреси, з якої платили.',
          ],
        },
        {
          heading: 'Купівля в застосунку з Google Play',
          body: [
            'У версії застосунку з Google Play ліцензію купують лише через Google Play: оплату, податки й квитанцію обробляє Google, і діють умови Google Play. Пошти покупця Google нам не передає, тож ключ не надходить листом — він активується на телефоні одразу після оплати.',
            'Після перевстановлення чи на новому телефоні з тим самим акаунтом Google ключ повертає кнопка «Відновити покупку» на сторінці «Ліцензія». Кількість пристроїв обмежена рівнем ліцензії так само, як і для ключа з сайту.',
          ],
        },
      ],
    },
    {
      id: 'privacy',
      nav: 'Приватність',
      title: 'Приватність',
      lead: 'Що ми зберігаємо, куди йдуть дані і чого ми не робимо.',
      clauses: [
        {
          heading: 'Що зберігає наш сервер',
          body: ['Лише те, чого потребує функція:'],
          bullets: [
            'Обліковий запис, якщо ви входите на сайт: пошта, ключ телефона, яким ви підтвердили вхід, імʼя, якщо ви його вказали, мова, час створення й останнього входу. Кожна сесія зберігає IP-адресу, з якої її відкрили.',
            'Листування з нами з кабінету: ваші повідомлення й наші відповіді.',
            'Пошта в списку очікування, якщо ви її там залишили.',
            'Для ліцензії: SHA-256 від ключа (не сам ключ), пошта, вказана при купівлі, активовані пристрої з назвами, які ви їм дали, і журнал активацій та відкликань з IP-адресою кожного запиту.',
            'Для покупки в Google Play: SHA-256 від токена покупки, товар, рівень і дату. Пошти в такому записі немає.',
          ],
        },
        {
          heading: 'Помічник',
          body: [
            'Відповіді помічника дає постачальник моделі, сьогодні це Google Gemini. Звичайно запит іде з телефона через наш сервер до постачальника, і відповідь повертається тим самим шляхом. Винятки (власний ключ, голосова розмова) описано нижче, у розділі про застосунок. Google отримує текст розмови, вкладення й контекст, потрібний помічнику (наприклад, місце чи події календаря на сьогодні), і діє політика Google.',
            'Наш сервер не зберігає тексту запитів і відповідей. Для денного ліміту він тримає ключ пристрою, рівень, привʼязку до ліцензії, вердикт перевірки, лічильник за день і час останнього запиту, а ще модель і код відповіді.',
          ],
        },
        {
          heading: 'Чого ми не робимо',
          body: [
            'Ми не читаємо листів між людьми: через наш сервер вони проходять лише конвертами, запечатаними на телефоні.',
            'Жодної реклами, рекламних ідентифікаторів, аналітики чи продажу даних.',
          ],
        },
        {
          heading: 'Ваші права',
          body: [
            'Ви можете дізнатися, що саме ми про вас зберігаємо, виправити це або видалити дані через «Видалити все» в застосунку, на https://phantom-os.dev/delete-account чи листом з адреси, якою платили.',
            'Видалення записів ліцензії деактивує ключ: без запису на сервері активувати пристрій наново вже не вийде. Сам Nightpost на вже активованих пристроях від цього не зникає — після закінчення пільгового строку він переходить на вільний рівень.',
          ],
        },
      ],
    },
    {
      id: 'app',
      nav: 'Застосунок для телефона',
      title: 'Застосунок для телефона: що він передає',
      lead: 'Окремо від ліцензії: що застосунок Nightpost для Android надсилає з телефона, кому й навіщо.',
      clauses: [
        {
          heading: 'Без реєстрації в застосунку',
          body: [
            'Застосунок не просить пошти, номера телефону чи імені. Особою в ньому є ключ, створений на самому пристрої. Якщо ви входите на сайт, підтвердивши код на телефоні, сервер заводить обліковий запис, прив’язаний до цього ключа; видалити його можна на https://phantom-os.dev/delete-account. Людей додають запрошенням, а не з ваших контактів: системну книгу контактів застосунок не читає.',
          ],
        },
        {
          heading: 'На наш сервер',
          body: [
            'Кожен запит до нашого сервера названо в таблиці на сторінці безпеки (/security): що він несе й чого не несе ніколи. Коротко:',
          ],
          bullets: [
            'Ліцензія: ключ, відбиток пристрою (SHA-256 від ідентифікатора Android, не сам ідентифікатор), марка й модель телефона — при активації. Покупка в Google Play: товар і токен покупки.',
            'Листи між людьми: лише запечатаний на телефоні конверт і мітка скриньки. Прочитати лист сервер не може; конверт стирається після забору або за сім днів.',
            'Дзвінки: адреси для з’єднання. Звук і зображення зашифровані між телефонами; якщо пряме з’єднання неможливе, вони йдуть через наш ретранслятор зашифрованими — він бачить, хто з ким і скільки говорив, але не зміст.',
            'Довідник імен: хеш імені, яке ви самі опублікували, і ваше запрошення. Прибрати запис можна будь-коли в застосунку.',
            'Краудправда мапи: клітинка мапи (~460 м), факт і ваша відповідь, підписані ключем пристрою, — лише коли ви самі підтверджуєте факт. Зв’язку «ключ ↔ свідчення» сервер тримає до двох півжиттів факту (від 2 годин до 14 днів), далі лишаються тільки підсумки.',
          ],
        },
        {
          heading: 'До сторонніх сервісів',
          body: [
            'Частину даних застосунок бере з відкритих сервісів, і ці запити йдуть до них напряму, не через нас. Вони бачать вашу IP-адресу і те, що в запиті:',
          ],
          bullets: [
            'Мапа: тайли з OpenFreeMap і рельєф — видима область мапи.',
            'Пошук адреси: текст пошуку — до Photon або Nominatim.',
            'Об’єкти й дороги довкола: координати — до серверів Overpass. Досьє місця — координати тапнутої точки до Вікіпедії й Вікіданих.',
            'Погода: координати з точністю близько 11 м — до Open-Meteo, коли асистент шукає погоду.',
            'Транспорт: точки маршруту — до Transitous; розклади й живі позиції — до відкритих фідів міст.',
            'Публічні сповіщення: стрічки з публічних джерел, без вашого місця.',
            'Пошук асистента в мережі: запит, який склав асистент, — до DuckDuckGo і на відкриті сторінки результатів.',
            'Помічник: якщо ви не додали власний ключ Google Gemini, парний комп’ютер не відповідає і помічник не обмежений телефоном, запит іде до Google Gemini через наш сервер. Сервер не зберігає тексту запиту й відповіді. Для денного ліміту він тримає ключ пристрою, рівень, привʼязку до ліцензії, вердикт перевірки, лічильник за день і час останнього запиту, а ще модель і код відповіді. Голосова розмова: сервер лише видає короткий токен, звук іде з телефона до Google напряму. З власним ключем запит іде до Google напряму. В обох випадках текст розмови, вкладення і контекст (місце, події календаря на сьогодні) отримує Google, і діє політика Google.',
            'Розпізнавання тексту й штрихкодів (ML Kit від Google) працює на пристрої, але може надсилати Google анонімну технічну статистику.',
          ],
        },
        {
          heading: 'Що лишається на телефоні',
          body: [
            'Розмови, збережені файли, пам’ять асистента, ваш слід на мапі й події календаря зберігаються лише на телефоні, у зашифрованих базах. Резервне копіювання в хмару вимкнено. Розпізнавання мови для асистента відбувається на пристрої.',
            'Дозволи застосунок просить тоді, коли вони потрібні функції: місце — для мапи й навігації, мікрофон — для голосу й дзвінків, камеру — для QR-коду, фото й відеодзвінків, календар — для екрана «День», сповіщення — для тривог і листів. Фонового доступу до місця версія з Google Play не має.',
          ],
        },
        {
          heading: 'Чого немає',
          body: [
            'Реклами, рекламних ідентифікаторів, аналітики, збирача падінь і продажу даних. Ми не будуємо профілю людини й нікому не передаємо даних для реклами.',
          ],
        },
        {
          heading: 'Видалення',
          body: [
            'На телефоні: «Видалити все» в налаштуваннях, «Звільнити пристрій» на сторінці «Ліцензія», відкликання імені з довідника, видалення розмов і пам’яті в застосунку. Повністю — очищенням даних застосунку в налаштуваннях Android або видаленням застосунку.',
            'На сервері: через «Видалити все» в застосунку, на https://phantom-os.dev/delete-account або листом на пошту нижче як запасний шлях. Конверти листів зникають самі після забору або за сім днів.',
          ],
        },
      ],
    },
    {
      id: 'refund',
      nav: 'Повернення коштів',
      title: 'Повернення коштів',
      lead: 'Чотирнадцять днів і жодних питань «а чому».',
      clauses: [
        {
          heading: 'Строк',
          body: [
            'Повернути кошти можна протягом 14 днів від дня покупки. Пояснювати причину не треба.',
          ],
        },
        {
          heading: 'Що станеться технічно',
          body: [
            'Ключ буде відкликано на сервері ліцензій. Активовані пристрої після цього не зможуть підтвердити ліцензію і, коли вичерпається пільговий строк, перейдуть на вільний рівень.',
            'Перехід на вільний рівень — це не блокування. Розмова з ядром, мапа з уже завантаженими пакетами, навігація, тривоги, сховище і голос лишаються робочими. Ми не забираємо в людини те, що рятує, через питання оплати.',
          ],
        },
        {
          heading: 'Пакети даних',
          body: [
            'Уже завантажені пакети лишаються на пристрої й далі працюють. Повернення стосується оплати за майбутні поновлення, а не за те, що вже у вас.',
          ],
        },
      ],
    },
  ],
};

const en: LegalCopy = {
  meta: {
    title: 'Legal — Nightpost',
    description:
      'Licence agreement, terms of sale, privacy and refunds. A draft, pending registration of the selling entity.',
  },
  eyebrow: 'Legal',
  title: 'What you are buying, and on what terms',
  lead:
    'Licence, sale and refund terms; privacy has its own page. Written to be read without a lawyer — and written so that every clause matches what the code actually does.',
  revisionLabel: 'Revision of',
  draftTitle: 'This is a working draft, not a binding contract',
  draftBody:
    'The text was prepared by the product team. Before any sale it must be reviewed by a lawyer, especially on consumer rights and tax in the buyer’s country.',
  notEntityTitle: 'Sales are not open yet',
  notEntityBody:
    'The legal entity that will sell Nightpost is not yet registered, so the seller details below are empty and these terms are not in force. We are not inventing a name and an address to make the page look complete.',
  entityHeading: 'Seller',
  entityFields: {
    legalName: 'Name',
    registrationId: 'Registration',
    address: 'Address',
    jurisdiction: 'Jurisdiction',
    vatNote: 'Tax',
  },
  contactHeading: 'How to reach us',
  contactBody:
    'Delete your account and server data via Delete everything in the app or at https://phantom-os.dev/delete-account; the address below stays as the fallback and for anything else about these terms or access to your data. A person answers, not a form.',
  docs: [
    {
      id: 'eula',
      nav: 'Licence agreement',
      title: 'Licence agreement',
      lead: 'What the key allows, and what it does not promise.',
      clauses: [
        {
          heading: 'What you get',
          body: [
            'A perpetual, non-exclusive licence to use Nightpost, not transferable to third parties. "Perpetual" is meant literally: a build bought today runs on your device for as long as you like, whether or not we still exist and whether or not our server answers.',
            'The licence binds to a device on activation. How many simultaneous activations you get depends on the tier; a seat can be released and moved to another device at any time from the Licence page.',
          ],
        },
        {
          heading: 'The updates window',
          body: [
            'A defined period of updates is included in the price. It governs which BUILDS the licence covers, not whether you may run the one you already bought.',
            'When the window ends, everything you have keeps working. Install a build released after the window and it will start on the free tier until updates are renewed.',
          ],
        },
        {
          heading: 'The free tier',
          body: [
            'The free tier needs no licence, never expires and is never switched off — not when there is no key, not when the key is invalid, not when the server is unreachable.',
            'These are open always, at every tier and in every licence state: talking to the core, the map with its offline packs, navigation, alerts and warnings, the vault, and voice.',
          ],
        },
        {
          heading: 'What you may not do',
          body: [
            'Transfer, resell or rent the licence; circumvent or forge licence verification; reverse-engineer the parts where applicable law does not expressly permit it; remove authorship notices.',
            'Open-source components inside the product keep their own licences; this agreement neither replaces nor narrows them.',
          ],
        },
        {
          heading: 'Nightpost is a tool, not a guarantee of safety',
          body: [
            'This is the most important clause in the document, and we are not hiding it at the bottom.',
            'Nightpost is built for places where infrastructure fails, and that is exactly why it cannot be your only source of navigation, warnings or decisions in danger. Satellite signals are spoofed, alert feeds are late and wrong, an offline map ages from the day it was packed, and a model running on your device can answer incorrectly.',
            'Do not rely on Nightpost alone. Keep another way to find your bearings and another way to learn about a threat. The product is provided "as is", without warranty of fitness for a particular purpose.',
          ],
        },
        {
          heading: 'Limitation of liability',
          body: [
            'To the extent the law allows, our aggregate liability for any claim relating to the product is limited to the amount you paid for it.',
            'This limit does not apply where the law forbids it, and does not narrow consumer rights granted to you by mandatory rules in your country.',
          ],
        },
      ],
    },
    {
      id: 'sale',
      nav: 'Terms of sale',
      title: 'Terms of sale',
      lead: 'What is being sold, and how the purchase works.',
      clauses: [
        {
          heading: 'What is sold',
          body: [
            `The app is free. Builder plans are Studio ${euro(PRICES.studio)} and Place ${euro(PRICES.place)} a month, with 0% on sales. Everything is free until ${FREE_UNTIL.en}; nothing is charged before 2027.`,
            'Renewal of data packs — maps, places, terrain and alert feeds — may be offered separately. That is genuinely recurring work on our side, so it carries a recurring price. A pack you already downloaded stays yours and works offline after renewal lapses.',
          ],
        },
        {
          heading: 'Prices and payment',
          body: [
            'Prices are in euros per month. The final amount including your country’s taxes is shown on the payment page before you confirm. How billing will work will be announced before any charge.',
            'Payment is planned to run through an external merchant of record. As of this revision no payment provider is connected and purchases are not being accepted.',
            'We never see or store your card details — they do not pass through our servers at all.',
          ],
        },
        {
          heading: 'Delivery',
          body: [
            'The licence key is sent to the email address used at purchase. The key is the pass: no account to create, no password for us to hold.',
            'If the key is lost, write to us from the same address you paid with.',
          ],
        },
        {
          heading: 'Buying in the app from Google Play',
          body: [
            'In the version of the app from Google Play, the licence is bought only through Google Play: Google handles the payment, taxes and receipt, and Google Play’s terms apply. Google does not pass us the buyer’s email, so the key is not emailed — it activates on the phone right after payment.',
            'After a reinstall, or on a new phone with the same Google account, the Restore purchase button on the Licence page brings the key back. The number of devices is limited by the licence tier, the same as for a key bought on the site.',
          ],
        },
      ],
    },
    {
      id: 'privacy',
      nav: 'Privacy',
      title: 'Privacy',
      lead: 'What we store, where data goes, and what we do not do.',
      clauses: [
        {
          heading: 'What our server stores',
          body: ['Only what a feature needs:'],
          bullets: [
            'An account, if you sign in on the site: your email address, the key of the phone you confirmed sign-in with, a name if you set one, your language, and when the account was created and last used. Each session keeps the IP address it was opened from.',
            'Messages you send us from the account, and our replies.',
            'Your email on the waiting list, if you left it there.',
            'For a licence: the SHA-256 of the key (not the key itself), the email given at purchase, the devices you activated with the names you gave them, and a log of activations and revocations with the IP address of each request.',
            'For a Google Play purchase: a SHA-256 of the purchase token, the product, the tier and the date. There is no email in such a record.',
          ],
        },
        {
          heading: 'The assistant',
          body: [
            'Assistant answers come from a model provider, today Google Gemini. By default your request goes from the phone through our server to the provider, and the answer comes back the same way. The exceptions (your own key, a voice call) are described below, in the phone app section. Google receives the conversation text, attachments and the context the assistant needs (such as your location or today’s calendar events), under Google’s policy.',
            'Our server keeps no request or answer text. For the daily limit it keeps the device key, plan, licence link, an integrity verdict, the daily count and last-use time, plus the model and status code.',
          ],
        },
        {
          heading: 'What we do not do',
          body: [
            'We do not read messages between people: they cross our server only as envelopes sealed on the phone.',
            'No ads, no advertising identifiers, no analytics and no data sales.',
          ],
        },
        {
          heading: 'Your rights',
          body: [
            'You can find out what we hold about you, correct it, or delete it via Delete everything in the app, at https://phantom-os.dev/delete-account, or by writing from the address you paid with.',
            'Deleting your licence records deactivates the key: with no server record, a device can no longer be activated again. Nightpost does not vanish from already-activated devices — once the grace window ends they move to the free tier.',
          ],
        },
      ],
    },
    {
      id: 'app',
      nav: 'Phone app',
      title: 'The phone app: what it sends',
      lead: 'Separate from the licence: what the Nightpost app for Android sends from the phone, to whom, and why.',
      clauses: [
        {
          heading: 'No sign-up in the app',
          body: [
            'The app does not ask for an email, a phone number or a name. Your identity in it is a key generated on the device itself. If you sign in to the website by confirming a code on the phone, the server creates an account linked to that key; you can delete it at https://phantom-os.dev/delete-account. People are added by invitation, not from your contacts: the app does not read the system address book.',
          ],
        },
        {
          heading: 'To our server',
          body: [
            'Every request to our server is named in the table on the security page (/security), with what it carries and what it never carries. In short:',
          ],
          bullets: [
            'Licence: the key, a device fingerprint (SHA-256 of the Android identifier, not the identifier itself), and the phone’s make and model — on activation. A Google Play purchase: the product and the purchase token.',
            'Messages between people: only an envelope sealed on the phone and a mailbox tag. The server cannot read the letter; the envelope is deleted once collected, or after seven days.',
            'Calls: addresses to connect. Sound and video are encrypted between the phones; when a direct connection is impossible they pass through our relay encrypted — it sees who spoke to whom and for how long, not the content.',
            'Name directory: a hash of a name you chose to publish, and your invite. You can withdraw the entry in the app at any time.',
            'Map truth: a map cell (~460 m), the fact and your answer, signed by the device key — only when you yourself confirm a fact. The server keeps the key-to-report link for up to two half-lives of the fact (2 hours to 14 days), then keeps only totals.',
          ],
        },
        {
          heading: 'To other services',
          body: [
            'Some data comes from open services, and those requests go to them directly, not through us. They see your IP address and what is in the request:',
          ],
          bullets: [
            'Map: tiles from OpenFreeMap and terrain — the visible map area.',
            'Address search: the search text — to Photon or Nominatim.',
            'Objects and roads around you: coordinates — to Overpass servers. A place dossier — the tapped point’s coordinates to Wikipedia and Wikidata.',
            'Weather: coordinates to about 11 m — to Open-Meteo, when the assistant looks up the weather.',
            'Transit: route points — to Transitous; timetables and live positions — to cities’ open feeds.',
            'Public alerts: feeds from public sources, without your location.',
            'Assistant web search: the query the assistant wrote — to DuckDuckGo and the result pages it opens.',
            'The assistant: if you have not added your own Google Gemini key, no paired computer answers and the assistant is not limited to the phone, the request goes to Google Gemini through our server. The server keeps no request or answer text. For the daily limit it keeps the device key, plan, licence link, an integrity verdict, daily count and last-use time, plus the model and status code. For a voice call it only issues a short token; audio goes from the phone straight to Google. With your own key the request goes to Google directly. Either way Google receives the conversation text, attachments and context (location, today’s calendar events), under Google’s policy.',
            'Text and barcode recognition (Google ML Kit) runs on the device but may send Google anonymous technical statistics.',
          ],
        },
        {
          heading: 'What stays on the phone',
          body: [
            'Conversations, saved files, the assistant’s memory, your trail on the map and calendar events are stored only on the phone, in encrypted databases. Cloud backup is off. Speech recognition for the assistant happens on the device.',
            'The app asks for a permission when a feature needs it: location for the map and navigation, microphone for voice and calls, camera for QR codes, photos and video calls, calendar for the Day screen, notifications for alerts and messages. The Google Play version has no background location access.',
          ],
        },
        {
          heading: 'What does not exist',
          body: [
            'Ads, advertising identifiers, analytics, a crash reporter, or data sales. We build no profile of you and pass no data to anyone for advertising.',
          ],
        },
        {
          heading: 'Deletion',
          body: [
            'On the phone: Delete everything in settings, Free this device on the Licence page, withdrawing your name from the directory, deleting conversations and memory in the app. Completely — by clearing the app’s data in Android settings, or uninstalling.',
            'On the server: via Delete everything in the app, at https://phantom-os.dev/delete-account, or by writing to the address below as a fallback. Message envelopes disappear on their own once collected, or after seven days.',
          ],
        },
      ],
    },
    {
      id: 'refund',
      nav: 'Refunds',
      title: 'Refunds',
      lead: 'Fourteen days, and no questions about why.',
      clauses: [
        {
          heading: 'The window',
          body: ['You can request a refund within 14 days of purchase. No reason required.'],
        },
        {
          heading: 'What happens technically',
          body: [
            'The key is revoked on the licence server. Activated devices can then no longer confirm the licence and, once the grace window runs out, move to the free tier.',
            'Moving to the free tier is not a lockout. Talking to the core, the map with the packs you already have, navigation, alerts, the vault and voice all keep working. We do not take away what saves someone over a question of payment.',
          ],
        },
        {
          heading: 'Data packs',
          body: [
            'Packs already downloaded stay on the device and keep working. A refund concerns payment for future renewals, not for what you already hold.',
          ],
        },
      ],
    },
  ],
};

export const legalCopy: Record<Lang, LegalCopy> = { en, uk };
