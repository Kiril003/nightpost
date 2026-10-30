import { Link } from 'react-router-dom';
import { useLanguage } from '../i18n';
import { shellCopy } from '../i18n/company';
import { wowCopy } from '../i18n/wow';
import { CHANNELS } from '../lib/company';
import Logo from './Logo';

export default function Footer() {
  const { lang } = useLanguage();
  const s = shellCopy[lang];
  const f = wowCopy[lang].foot;
  const n = wowCopy[lang].nav;
  const year = new Date().getFullYear();

  const links = [
    { to: '/builder', label: n.builder },
    { to: '/pricing', label: n.pricing },
    { to: '/download', label: n.download },
    { to: '/security', label: f.security },
    { to: '/privacy', label: f.privacy },
    { to: '/legal', label: f.terms },
    { to: '/account', label: s.nav.cabinet },
  ];

  return (
    <footer data-chrome="bottom" className="t">
      <div className="foot">
        <div className="wrap foot-in">
          <Logo />
          <nav aria-label={lang === 'uk' ? 'Підвал' : 'Footer'}>
            {links.map((link) => (
              <Link key={link.to} to={link.to} className="inline-flex min-h-[44px] items-center">
                {link.label}
              </Link>
            ))}
            <a href={`mailto:${CHANNELS.support.email}`} className="inline-flex min-h-[44px] items-center">
              {f.contact}
            </a>
          </nav>
          <p>© {year} Nightpost</p>
        </div>
      </div>
    </footer>
  );
}
