import { Link, useLocation } from 'react-router-dom';
import Accent from '../components/wow/Accent';
import { useLanguage } from '../i18n';
import type { Lang, PageMeta } from '../i18n/types';
import { usePageMeta } from '../lib/meta';

const COPY: Record<Lang, { meta: PageMeta; title: string; sub: string; home: string; make: string }> = {
  en: {
    meta: { title: 'Page not found — Nightpost', description: 'There is no page at this address.' },
    title: 'This page isn’t here.',
    sub: 'The link may be old, or the page isn’t published yet.',
    home: 'Go to Overview',
    make: 'Make a page',
  },
  uk: {
    meta: { title: 'Сторінку не знайдено — Nightpost', description: 'За цією адресою сторінки немає.' },
    title: 'Цієї сторінки немає.',
    sub: 'Посилання могло застаріти, або сторінку ще не опублікували.',
    home: 'На огляд',
    make: 'Створити сторінку',
  },
};

export default function NotFound() {
  const { lang } = useLanguage();
  const c = COPY[lang] ?? COPY.en;
  usePageMeta(c.meta);
  const { pathname } = useLocation();
  // Чиясь сторінка з Builder: головна дія — зробити свою. Інша адреса: на огляд.
  const page = pathname.startsWith('/p/');

  return (
    <div className="t">
      <section className="wrap gone">
        <div>
          <p className="kick" style={{ justifyContent: 'center' }}>
            phantom-os.dev{pathname}
          </p>
          <h1 style={{ marginTop: 20 }}>
            <Accent text={c.title} words={2} />
          </h1>
          <p>{c.sub}</p>
          <div className="row">
            {page ? (
              <>
                <Link className="btn btn-primary btn-lg" to="/build">
                  {c.make}
                </Link>
                <Link className="link-arrow" to="/">
                  {c.home}
                </Link>
              </>
            ) : (
              <>
                <Link className="btn btn-primary btn-lg" to="/">
                  {c.home}
                </Link>
                <Link className="link-arrow" to="/build">
                  {c.make}
                </Link>
              </>
            )}
          </div>
        </div>
      </section>
    </div>
  );
}
