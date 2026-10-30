import { useEffect, useState } from 'react';
import { Menu, UserRound, X } from 'lucide-react';
import { Link, NavLink, useLocation } from 'react-router-dom';
import { useLanguage } from '../i18n';
import { shellCopy } from '../i18n/company';
import { LANGS } from '../i18n/types';
import { wowCopy } from '../i18n/wow';
import Logo from './Logo';
import ThemeToggle from './wow/ThemeToggle';

interface Dest {
  to: string;
  label: string;
}

/** Шапка 72px (60px на телефоні): чотири місця, мова, тема, «Отримати застосунок». */
export default function Nav() {
  const { lang, setLang } = useLanguage();
  const s = shellCopy[lang];
  const w = wowCopy[lang].nav;
  const [open, setOpen] = useState(false);
  const { pathname } = useLocation();

  useEffect(() => setOpen(false), [pathname]);

  const dests: Dest[] = [
    { to: '/', label: w.product },
    { to: '/build', label: w.builder },
    { to: '/pricing', label: w.pricing },
    { to: '/download', label: w.download },
  ];
  const langs = (
    <span className="lang" role="group" aria-label="Language / Мова">
      {LANGS.map(({ code, label }) => (
        <button key={code} type="button" aria-pressed={lang === code} onClick={() => setLang(code)}>
          {label}
        </button>
      ))}
    </span>
  );

  return (
    <header data-chrome="top" className="t">
      <div className="nav">
        <div className="wrap nav-in">
          <Link to="/" className="logo" aria-label={w.home}>
            <Logo />
          </Link>
          <nav className="nav-links" aria-label={lang === 'uk' ? 'Основне меню' : 'Main menu'}>
            {dests.map((d) => (
              <NavLink key={d.to} to={d.to} end>
                {d.label}
              </NavLink>
            ))}
          </nav>
          <div className="nav-end">
            {langs}
            <ThemeToggle />
            <Link to="/account" className="tt acct-link" aria-label={s.nav.cabinet} title={s.nav.cabinet}>
              <UserRound strokeWidth={1.75} aria-hidden />
            </Link>
            <Link className="btn btn-ghost btn-sm" to="/download">
              {w.getApp}
            </Link>
            <button
              type="button"
              className="menu"
              aria-expanded={open}
              aria-controls="mobile-menu"
              aria-label={lang === 'uk' ? (open ? 'Закрити меню' : 'Відкрити меню') : open ? 'Close menu' : 'Open menu'}
              onClick={() => setOpen((v) => !v)}
            >
              {open ? <X strokeWidth={1.75} aria-hidden /> : <Menu strokeWidth={1.75} aria-hidden />}
            </button>
          </div>
        </div>
      </div>

      {open && (
        <nav id="mobile-menu" aria-label={w.menu} className="np-menu mob lg:hidden">
          <ul className="wrap">
            {[...dests, { to: '/account', label: s.nav.cabinet }].map((d) => (
              <li key={d.to}>
                <NavLink to={d.to} end>
                  {d.label}
                </NavLink>
              </li>
            ))}
          </ul>
          <div className="wrap mob-end">
            {langs}
            <ThemeToggle />
          </div>
        </nav>
      )}
    </header>
  );
}
