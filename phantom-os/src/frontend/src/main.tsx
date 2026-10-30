/**
 * ВАДА: `import './lib/maplibreWorker'` стояв ТУТ, у вході застосунку.
 *
 * Той модуль робить рівно одну річ — каже maplibre, де лежить його воркер.
 * Але щоб її зробити, він статично тягне `maplibre-gl`, а це 712 КБ рушія
 * мап. Вхід — це граф першого кадру, тож `dist-phone/index.html` виписував
 * на нього `<link rel="modulepreload">`, і телефон качав та РОЗБИРАВ увесь
 * рушій мап до того, як намалювати першу бульбашку. Месенджер мапи не
 * малює взагалі: у `components/messenger/**` немає жодного імпорту
 * maplibre.
 *
 * Прапорцем це не лікується: статичний імпорт або є в графі, або його там
 * немає (та сама вада, що описана в PHONE_SWAPS у vite.config.ts).
 *
 * Тому рядок знято, а `setWorkerUrl` переїхав до тих двох модулів, які
 * СПРАВДІ будують мапу (`TacticalMap`, `MapResponse`) — вони вже тягнуть
 * maplibre статично, тож їм це не коштує нічого, і вони лежать за лінивою
 * межею. Щоб наступна мапа не з'явилась без воркера мовчки, контракт
 * стереже тест `maplibreWorker.test.ts`: кожен файл із `new maplibregl.Map(`
 * мусить імпортувати цей модуль.
 */
// Першим: стори читають localStorage ще під час імпорту, відновлена копія мусить лягти раніше.
import './services/backupStashBoot';
import React from 'react';
import ReactDOM from 'react-dom/client';
// Один шрифт (дизайн-система PHANTOM v1, §3.1): Manrope 400/500/600/700 і
// JetBrains Mono 400 лише для коду — src/styles/phantom/fonts.css (власні
// файли, без CDN, з unicode-range). Onest, Space Grotesk і Playfair Display
// прибрано разом із серифною «короною».
import './styles/phantom/fonts.css';
import { App } from './app/App';
import { initReduceMotion } from './feel/motionKit';

initReduceMotion();
import { applyBootstrapLanguage } from './services/settingsBootstrap';
import { startNativeLanguageWatch } from './services/nativeLanguage';
import { loadRegion } from './services/regionApi';
import { applyThemeNow } from './services/theme';
import { ensureCapabilityProbed } from './stores/capabilityStore';
import { startProfiles } from './stores/profilesStore';
import { wipeLegacyProviderKey } from './services/legacyProviderKey';
import './styles/globals.css';

wipeLegacyProviderKey();

// Тема (Ніч/День за сонцем, services/theme.ts) — ДО першого кадру: без спалаху
// чужої палітри на холодному старті. Старі теми (sunrise-warm, amber-night…)
// прибрано разом із їхнім бутстрапом.
// На телефоні фазу доби (сонце в точці людини) знає лише рідний бік. Питаємо
// міст синхронно ТУТ, до першого кадру: інакше месенджер малювався нічним і
// перефарбовувався вже в useEffect — мерехтіння на кожному холодному старті.
applyThemeNow();
// Same for <html lang> — the cached locale has to be on the element before
// the first paint, or the UI renders in Ukrainian and swaps to English once
// `/settings` resolves.
applyBootstrapLanguage();
// На телефоні мову обирає рідний застосунок (перший екран, Профіль › Мова):
// месенджер бере її з моста до першого кадру й далі слухає поштовх
// `__phantomLanguage`. На ПК моста немає — лишається кеш вище.
startNativeLanguageWatch();
startProfiles();
void loadRegion();

// Kicks off the GPU capability probe (T0/T1/T2) as early as possible so a
// verdict is usually ready before TacticalMap first constructs its map.
// Bounded to ~1.5s worst case (see capabilityProbe.ts), never blocks
// render — fire-and-forget. TacticalMap also calls this (idempotent) so
// it still gets a verdict if this module ever loads without main.tsx
// (tests, alternate entry points).
void ensureCapabilityProbed();

import { useMessengerStore } from './stores/messengerStore';
import { globalP2PMesh } from './services/globalP2PMesh';

if (typeof window !== 'undefined') {
  (window as any).__phantom_messenger_store = useMessengerStore;
  (window as any).__phantom_p2p_mesh = globalP2PMesh;
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
