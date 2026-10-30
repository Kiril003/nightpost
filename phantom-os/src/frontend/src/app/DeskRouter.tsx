import React from 'react';
import { Routes, Route } from 'react-router-dom';
import { useSystemStore } from '../stores/systemStore';
import { DeskShell } from '../components/shell/DeskShell';
import { DeskSettings } from '../components/shell/DeskSettings';
import { DeskEngine } from '../components/engine/DeskEngine';
import { DeskToday } from '../components/shell/DeskToday';
import { DeskWaking, useNodeWaking } from '../components/shell/DeskWaking';
import { DeskMeet, useBootstrap } from '../components/shell/DeskMeet';
import { inDeskShell } from '../services/nodeOrigin';
import LoginScreen from '../components/auth/LoginScreen';
import MessengerLayout from '../layouts/MessengerLayout';

/**
 * Маршрутизатор оболонки ПК: прокидання вузла, знайомство, вхід, Головна й
 * розділи. Окремий модуль, бо App.tsx підвантажує його ЛИШЕ поза телефоном
 * (`__PHONE_BUILD__ ? … : React.lazy(() => import('./DeskRouter'))`): статичні
 * імпорти оболонки в App.tsx тягнули її в граф телефона, хоч телефон її не
 * показує ніколи. Сторож — `phoneEntryGraph.test.ts`.
 *
 * Розділи — лише ті, живу функцію яких доведено запуском (docs/pc-census.md).
 */
const MapLayout = React.lazy(() => import('../layouts/MapLayout'));
const SettingsPanel = React.lazy(() => import('../components/settings/SettingsPanel'));

export default function DeskRouter({ fallback }: { fallback?: React.ReactNode }) {
  const authenticated = useSystemStore((s) => s.authenticated);
  const node = useNodeWaking();
  const [bootstrap, claimed] = useBootstrap(node.up);
  // Перший запуск: вузол ще нічий — PHANTOM знайомиться (ім'я і PIN), а вже
  // тоді Головна. Не з петлі чи вузол має власника — одразу далі.
  if (node.up && bootstrap === 'unclaimed') return <DeskMeet onDone={claimed} />;
  // Поки не знаємо — ще «прокидаюсь» (частки секунди), а не Головна, що
  // за мить змінилась би знайомством.
  if (node.up && bootstrap === 'unknown') return <DeskWaking waitedMs={node.waitedMs} missing={node.missing} />;
  // Вікно малює PHANTOM одразу, не чекаючи вузла: поки той прокидається (і
  // ще кілька секунд — дати оболонці передати квиток входу), видно знак, що
  // прокидається, а не порожнечу чи форму PIN.
  if (!authenticated) {
    if (!node.up || (inDeskShell() && node.upForMs < 5000)) return <DeskWaking waitedMs={node.waitedMs} missing={node.missing} />;
    return <LoginScreen />;
  }
  return (
    <React.Suspense fallback={fallback ?? null}>
      <Routes>
        <Route element={<DeskShell />}>
          <Route path="/" element={<MessengerLayout />} />
          <Route path="/messenger" element={<MessengerLayout />} />
          <Route path="/map" element={<MapLayout />} />
          <Route path="/day" element={<DeskToday />} />
          <Route path="/hq" element={<DeskEngine />} />
          <Route path="/settings" element={<DeskSettings />} />
          <Route path="/settings/all" element={<SettingsPanel />} />
          <Route path="*" element={<MessengerLayout />} />
        </Route>
      </Routes>
    </React.Suspense>
  );
}
