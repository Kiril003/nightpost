import { lazy, Suspense, type ComponentType } from 'react';
import { createBrowserRouter, Navigate, RouterProvider } from 'react-router-dom';
import Layout from './components/Layout';
import Home from './pages/Home';
import NotFound from './pages/NotFound';
import { startFresh } from './lib/fresh';

/*
 * Головна їде в першому файлі; решта сторінок — окремими шматками, які
 * браузер бере лише при переході. На телефоні з поганим звʼязком перший
 * екран не чекає на тексти безпеки, кабінету й таблиці пакетів.
 */
type Loader = () => Promise<{ default: ComponentType }>;

/**
 * Хто прийшов прямо на /security чи /account, тому сторінка потрібна одразу:
 * без цього її шматок просився лише після того, як React розібрав головний
 * файл, і LCP на мобільному виростав до 2,8 с. Тому шматок ПОТОЧНОЇ адреси
 * просимо ще під час розбору цього модуля — паралельно з рештою старту.
 */
const LOADERS: Record<string, Loader> = {
  '/pricing': () => import('./pages/Pricing'),
  '/download': () => import('./pages/Download'),
  '/security': () => import('./pages/Security'),
  '/account': () => import('./pages/Account'),
  '/legal': () => import('./pages/Legal'),
  '/delete-account': () => import('./pages/DeleteAccount'),
  '/privacy': () => import('./pages/Privacy'),
  '/build/place': () => import('./pages/Build'),
  '/builder': () => import('./pages/BuildLanding'),
};

const here = typeof window === 'undefined' ? '' : window.location.pathname.replace(/\/+$/, '');
const early = LOADERS[here]?.();

function page(path: keyof typeof LOADERS) {
  const load = LOADERS[path];
  const Page = lazy(path === here && early ? () => early : load);
  return (
    <Suspense fallback={<div className="min-h-screen" aria-busy="true" />}>
      <Page />
    </Suspense>
  );
}

const PublishedPage = lazy(() => import('./pages/PublishedPage'));
const BuildStart = lazy(() => import('./pages/BuildStart'));
const BuildEditor = lazy(() => import('./pages/BuildEditor'));
const BuildAi = lazy(() => import('./pages/BuildAi'));
const bare = (el: JSX.Element) => <Suspense fallback={<div className="min-h-screen" aria-busy="true" />}>{el}</Suspense>;

const router = createBrowserRouter([
  { path: '/p/:handle', element: bare(<PublishedPage />) },
  { path: '/build', element: bare(<BuildEditor />) },
  { path: '/build/start', element: bare(<BuildStart />) },
  { path: '/build/ai', element: bare(<BuildAi />) },
  { path: '/build/edit/:id', element: bare(<BuildEditor />) },
  {
    element: <Layout />,
    children: [
      { path: '/', element: <Home /> },
      { path: '/how', element: <Navigate to="/" replace /> },
      { path: '/roadmap', element: <Navigate to="/" replace /> },
      { path: '/architecture', element: <Navigate to="/" replace /> },
      { path: '/pricing', element: page('/pricing') },
      { path: '/download', element: page('/download') },
      { path: '/packs', element: <Navigate to="/download" replace /> },
      { path: '/security', element: page('/security') },
      { path: '/company', element: <Navigate to="/" replace /> },
      // Закладки, старі листи й адреса в підписаній політиці оновлень мусять
      // жити далі — маршрут лишається назавжди, сторінки за ним більше немає.
      { path: '/license', element: <Navigate to="/account?door=key" replace /> },
      /*
       * Три сторінки старої картини продукту знято з сайту.
       *
       * `/companion` показувала намальовану на самому сайті симуляцію
       * застосунку під написом «ЖИВЕ ДЕМО»: вигадані дані, вигаданий журнал
       * «Вежі», дзвінки й SMS, яких застосунок не робить (він відкриває
       * системну дзвонилку — і це навмисно, останній рух лишається людині).
       * `/world` малювала пульт із чотирма агентами під підписом «це не
       * картинка». `/phantom-os` описувала залізо в теперішньому часі.
       *
       * Адреси лишаються назавжди й ведуть на головну: закладки, старі листи
       * й посилання ззовні не мусять упиратись у 404 — той самий закон, що
       * вже діє для `/license`.
       */
      { path: '/companion', element: <Navigate to="/" replace /> },
      { path: '/world', element: <Navigate to="/" replace /> },
      { path: '/phantom-os', element: <Navigate to="/" replace /> },
      { path: '/account', element: page('/account') },
      { path: '/delete-account', element: page('/delete-account') },
      { path: '/legal', element: page('/legal') },
      { path: '/privacy', element: page('/privacy') },
      { path: '/build/place', element: page('/build/place') },
      { path: '/builder', element: page('/builder') },
      { path: '*', element: <NotFound /> },
    ],
  },
]);

let seen = router.state.location.key;
startFresh((fn) =>
  router.subscribe((s) => {
    if (s.location.key === seen) return;
    seen = s.location.key;
    fn();
  }),
);

export default function App() {
  return <RouterProvider router={router} />;
}
