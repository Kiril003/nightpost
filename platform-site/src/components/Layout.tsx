import { useEffect, type ReactNode } from 'react';
import { Outlet, ScrollRestoration, useLocation } from 'react-router-dom';
import Footer from './Footer';
import Nav from './Nav';
import { anchorId } from '../lib/anchor';

export default function Layout({ children }: { children?: ReactNode }) {
  const { hash, pathname } = useLocation();

  // ScrollRestoration відновлює позицію, але якір у чужому маршруті не ловить:
  // посилання «Контакти» з підвалу веде на /company#contact, і без цього
  // сторінка відкривалась згори.
  // Сторінки вантажаться лінивими шматками: якоря ще нема в DOM, коли змінилась
  // адреса, тож чекаємо його до двох секунд.
  useEffect(() => {
    const id = anchorId(hash);
    if (!id) return;
    let frame = 0;
    const started = performance.now();
    const seek = () => {
      const target = document.getElementById(id);
      if (target) target.scrollIntoView({ behavior: 'smooth' });
      else if (performance.now() - started < 2000) frame = requestAnimationFrame(seek);
    };
    frame = requestAnimationFrame(seek);
    return () => cancelAnimationFrame(frame);
  }, [hash, pathname]);

  return (
    <div className="w flex min-h-screen flex-col">
      <Nav />
      <main className="flex-1">
        {children ?? <Outlet />}
      </main>
      <Footer />
      <ScrollRestoration />
    </div>
  );
}
