import React, { useEffect } from 'react';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { AnimatePresence } from 'framer-motion';
import { Providers } from './providers';
import { ViewportFrame } from './ViewportFrame';
import { ErrorBoundary } from '../components/core/ErrorBoundary';
import { useAuthStore } from '../stores/authStore';
import { ToastRail } from '../components/core/ToastRail';
import { CrashPrompt } from '../components/support/CrashPrompt';
import { useSystemStore } from '../stores/systemStore';
import { onDoorTicket } from '../services/doorTicket';
import { authApi } from '../services/api';
import { PHANTOM_MARK_PATH } from '../components/messenger/PhantomMark';

/* ─── Lazy views ─────────────────────────────────────────────────────────── */

const MessengerLayout = React.lazy(() => import('../layouts/MessengerLayout'));
// ПК: оболонка PHANTOM (components/shell) — вхід, знайомство, Головна й
// розділи — живе в `DeskRouter` і підвантажується лише поза телефоном.
// Розділи там — лише ті, живу функцію яких доведено запуском
// (docs/pc-census.md).
//
// У збірці телефона цих дверей немає, але статичні імпорти оболонки ПК і
// `React.lazy(() => import(...))` усе одно народжували код і чанки — мапа,
// діалог і налаштування ПК лежали в APK мертвою вагою (~450 КБ разом із
// maplibre-воркером), а оболонка тягнула свій shell.css. `__PHONE_BUILD__` —
// літерал, тож гілка з `import()` відпадає ще в rollup, і чанків не стає
// зовсім. Сторож — `phoneEntryGraph.test.ts` (граф модулів) і
// `phoneBundle.test.ts` (сам dist-phone).
const DeskNothing: React.FC<{ fallback?: React.ReactNode }> = () => null;
const DeskRouter = __PHONE_BUILD__ ? DeskNothing : React.lazy(() => import('./DeskRouter'));

export function StateSurface() {
  return <MessengerLayout />;
}

/**
 * Маршрути, які застосунок СПРАВДІ має. Не декорація: `path="*"` нижче ловить
 * геть усе й малює той самий месенджер, тож `navigate('/будь-що')` виглядає як
 * успішний перехід і мовчки нікуди не веде. Відрізнити справжній маршрут від
 * ловленого катч-олом можна лише за цим списком — тому він і винесений сюди,
 * а сторож `appsTilesReach.test.tsx` звіряє з ним кожні двері в інтерфейсі.
 */
export const DECLARED_ROUTES = __PHONE_BUILD__
  ? (['/', '/messenger', '/chat'] as const)
  : (['/', '/messenger', '/map', '/day', '/phantom', '/hq', '/settings', '/settings/all'] as const);

function MainRouter() {
  if (!__PHONE_BUILD__) {
    return (
      <React.Suspense fallback={<BootFrame />}>
        <DeskRouter fallback={<BootFrame />} />
      </React.Suspense>
    );
  }
  return (
    <React.Suspense fallback={<BootFrame />}>
      <AnimatePresence mode="wait">
        <Routes>
          {DECLARED_ROUTES.map((path) => (
            <Route key={path} path={path} element={<MessengerLayout />} />
          ))}
          <Route path="*" element={<MessengerLayout />} />
        </Routes>
      </AnimatePresence>
    </React.Suspense>
  );
}

/**
 * Кадр холодного старту — ТОЙ САМИЙ, що заставка `#boot` в index.html.
 *
 * Перший комміт React змітає `#boot` (createRoot чистить контейнер), а
 * MessengerLayout ще їде. Тут стояв PhantomLoader зі скла ПК, потім — копія
 * старої заставки (знак, «Відкриваю месенджер…»). Збірка 16: та сама розмітка
 * й ті самі класи, що в index.html — на телефоні ескіз домівки (шапка з
 * диском знака, капсула питання, рядок, картки), на ПК знак по центру. Стилі
 * живуть у `<style>` index.html і переживають монтування React, тож передача
 * кадру непомітна: змінюється лише власник розмітки. Ні тексту, ні анімації —
 * див. коментар у index.html.
 */
export function BootFrame() {
  return (
    <div id="boot" data-boot-frame aria-hidden="true">
      <svg className="glyph" viewBox="0 0 24 24">
        <path fill="currentColor" d={PHANTOM_MARK_PATH} />
      </svg>
      <div className="sk">
        <div className="sk-head">
          <span className="sk-disc">
            <svg viewBox="0 0 24 24">
              <path fill="currentColor" d={PHANTOM_MARK_PATH} />
            </svg>
          </span>
          <i className="sk-title" />
          <i className="sk-pill" />
        </div>
        <div className="sk-ask">
          <i className="sk-capsule" />
          <i className="sk-mic" />
        </div>
        <div className="sk-row">
          <i className="sk-line" />
          <i className="sk-line thin" />
        </div>
        <i className="sk-card first" />
        <i className="sk-card" />
      </div>
    </div>
  );
}

function AutoLoginManager() {
  const autoLogin = useAuthStore((s) => s.autoLogin);
  useEffect(() => {
    void autoLogin();
  }, [autoLogin]);
  // Настільна оболонка передає квиток дверей, щойно вузол відповів.
  useEffect(
    () =>
      onDoorTicket((ticket) => {
        void authApi
          .door(ticket)
          .then((res) => {
            useAuthStore.getState().setUser(res.user, res.token, res.expires_at);
            useSystemStore.getState().setAuthenticated(true);
          })
          .catch(() => undefined);
      }),
    [],
  );
  return null;
}

/* ─── App Root ────────────────────────────────────────────────────────────── */

export function App() {
  return (
    <Providers>
      <AutoLoginManager />
      <BrowserRouter>
        <ViewportFrame>
          <ErrorBoundary>
            <div
              className="w-full h-full min-h-screen overflow-hidden relative flex flex-col"
              style={{ background: 'var(--ph-canvas)' }}
            >
              <MainRouter />
              <ToastRail />
              <CrashPrompt />
            </div>
          </ErrorBoundary>
        </ViewportFrame>
      </BrowserRouter>
    </Providers>
  );
}
