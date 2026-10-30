import { Link } from 'react-router-dom';
import Accent from '../components/wow/Accent';
import { useLanguage } from '../i18n';
import type { Lang } from '../i18n/types';
import { pricingPageCopy } from '../i18n/pricingPage';
import { usePageMeta } from '../lib/meta';
import { buildCopy } from '../i18n/build';

interface LandingCopy {
  eyebrow: string;
  h1: string;
  lead: string;
  facts: readonly string[];
  cta: string;
  signIn: string;
  steps: readonly { n: string; h: string; p: string }[];
  proofEyebrow: string;
  proofTitle: string;
  proofLine: string;
  proofOpen: string;
  canTitle: string;
  phAlt: string;
  shotAlt: string;
}

const COPY: Record<Lang, LandingCopy> = {
  en: {
    eyebrow: 'Builder',
    h1: 'A page for every person and place.',
    lead: 'Make it on your phone or computer. Publish in minutes. 0% taken on sales.',
    facts: [...pricingPageCopy.en.builderFacts.slice(0, 2), 'Test network today', pricingPageCopy.en.builderFacts[2]],
    cta: 'Open Builder',
    signIn: 'Your places',
    steps: [
      { n: '01', h: 'Pick a look', p: 'Start from a template for a person, a studio or a place.' },
      { n: '02', h: 'Add blocks', p: 'Shop, classes, events, reviews. Edit on the page itself.' },
      { n: '03', h: 'Publish', p: 'One tap. Turn the page off again whenever you like.' },
    ],
    proofEyebrow: 'Sample page',
    proofTitle: 'Alma Brandt · Ceramics',
    proofLine: 'A shop, workshops and pickup at the studio, from one page.',
    proofOpen: 'Open the sample',
    canTitle: 'What a page does today',
    phAlt: 'A studio page made in Builder, on a phone',
    shotAlt: 'The sample page in a browser',
  },
  uk: {
    eyebrow: 'Builder',
    h1: 'Сторінка для кожної людини й місця.',
    lead: 'Зробіть її на телефоні чи компʼютері. Опублікуйте за кілька хвилин. 0% з продажів.',
    facts: [...pricingPageCopy.uk.builderFacts.slice(0, 2), 'Поки що тестова мережа', pricingPageCopy.uk.builderFacts[2]],
    cta: 'Відкрити Builder',
    signIn: 'Ваші місця',
    steps: [
      { n: '01', h: 'Оберіть вигляд', p: 'Почніть із шаблону для людини, студії чи місця.' },
      { n: '02', h: 'Додайте блоки', p: 'Крамниця, заняття, події, відгуки. Правте просто на сторінці.' },
      { n: '03', h: 'Опублікуйте', p: 'Один дотик. Вимкнути сторінку можна будь-коли.' },
    ],
    proofEyebrow: 'Зразок сторінки',
    proofTitle: 'Alma Brandt · Ceramics',
    proofLine: 'Крамниця, майстер-класи й самовивіз зі студії на одній сторінці.',
    proofOpen: 'Відкрити зразок',
    canTitle: 'Що сторінка вміє сьогодні',
    phAlt: 'Сторінка студії, зроблена в Builder, на телефоні',
    shotAlt: 'Зразок сторінки в браузері',
  },
};

export default function BuildLanding() {
  const { lang } = useLanguage();
  const b = COPY[lang] ?? COPY.en;
  const tier = pricingPageCopy[lang].tiers.person;
  const inProgress = pricingPageCopy[lang].inProgress;
  usePageMeta(buildCopy[lang].meta);

  return (
    <div className="t" data-build-view="landing">
      <section className="wrap bl-top">
        <div>
          <p className="kick">{b.eyebrow}</p>
          <h1 style={{ marginTop: 20 }}>
            <Accent text={b.h1} words={2} />
          </h1>
          <p className="lead">{b.lead}</p>
          <div className="row">
            <Link className="btn btn-primary btn-lg" to="/build">
              {b.cta}
            </Link>
            <Link className="link-arrow" to="/account#places">
              {b.signIn} →
            </Link>
          </div>
          <ul className="tags">
            {b.facts.map((fact) => (
              <li key={fact}>{fact}</li>
            ))}
          </ul>
        </div>
        <div className="bl-ph">
          <img className="d" src="/top/place-day.webp" alt={b.phAlt} />
          <img className="n" src="/top/place-night.webp" alt={b.phAlt} />
        </div>
      </section>

      <section className="sec">
        <div className="wrap three">
          {b.steps.map((s) => (
            <div key={s.n}>
              <span className="num tnum">{s.n}</span>
              <h3>{s.h}</h3>
              <p>{s.p}</p>
            </div>
          ))}
        </div>
      </section>

      <section className="sec">
        <div className="wrap sample">
          <div>
            <p className="kick">{b.proofEyebrow}</p>
            <h2 style={{ marginTop: 16 }}>{b.proofTitle}</h2>
            <p className="body">{b.proofLine}</p>
            <ul className="ticks" aria-label={b.canTitle}>
              {tier.items.map((x) => (
                <li key={x}>{x}</li>
              ))}
              {tier.soon.map((x) => (
                <li key={x} className="soon">
                  {x} <small>{inProgress}</small>
                </li>
              ))}
            </ul>
            <p>
              <Link className="link-arrow link" to="/p/alma-brandt">
                {b.proofOpen}
              </Link>
            </p>
          </div>
          <Link className="shot" to="/p/alma-brandt" aria-label={b.proofOpen}>
            <img className="d" src="/top/sample-day.webp" alt={b.shotAlt} />
            <img className="n" src="/top/sample-night.webp" alt={b.shotAlt} />
          </Link>
        </div>
      </section>
    </div>
  );
}

