import { Link } from 'react-router-dom';
import Accent from '../components/wow/Accent';
import { useLanguage } from '../i18n';
import { pricingCopy } from '../i18n/commerce';
import { pricingPageCopy, TIER_ORDER, type TierId } from '../i18n/pricingPage';
import { BUILD_ANCHOR } from '../lib/downloads';
import { usePageMeta } from '../lib/meta';
import { planById, type FeatureKey } from '../lib/plans';

const FREE_KEYS = new Set(planById('free').features.map((f) => f.key));
const PC_EXTRAS = planById('personal').features.filter((f) => f.live && !FREE_KEYS.has(f.key)).map((f) => f.key);

export default function Pricing() {
  const { lang } = useLanguage();
  const p = pricingPageCopy[lang];
  usePageMeta(p.meta);

  return (
    <div className="t">
      <section className="ph-head">
        <div className="wrap">
          <p className="kick">{p.eyebrow}</p>
          <h1>
            <Accent text={p.title} />
          </h1>
          <p className="lead">{p.lead}</p>
        </div>
      </section>

      <section id="core" className="sec">
        <div className="wrap">
          <article className="core">
            <div>
              <p className="pcard-k">{p.coreEyebrow}</p>
              <h2>{p.coreTitle}</h2>
              <ul className="tags">
                {p.coreItems.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </div>
            <div className="core-end">
              <p className="price tnum">{p.corePrice}</p>
              <Link to={BUILD_ANCHOR} className="btn btn-primary btn-lg">
                {p.coreCta}
              </Link>
            </div>
          </article>
        </div>
      </section>

      <section id="builder" className="sec">
        <div className="wrap">
          <div className="sec-h">
            <h2>
              <Accent text={p.builderTitle} words={3} />
            </h2>
            <ul className="tags">
              {p.builderFacts.map((fact) => (
                <li key={fact}>{fact}</li>
              ))}
            </ul>
          </div>
          <div className="tiers">
            {TIER_ORDER.map((id, i) => (
              <TierCard key={id} id={id} prev={i > 0 ? TIER_ORDER[i - 1] : null} />
            ))}
          </div>
          <div id="plans" className="pc">
            <div>
              <b>{p.pcTitle}</b>
              <p>{p.pcSub}</p>
              {PC_EXTRAS.length > 0 && (
                <ul className="tags">
                  {PC_EXTRAS.map((key) => (
                    <li key={key}>{pricingCopy[lang].features[key as FeatureKey]}</li>
                  ))}
                </ul>
              )}
            </div>
            <span className="st">{pricingCopy[lang].notOnSale}</span>
          </div>
          <p style={{ marginTop: 40 }}>
            <Link className="link-arrow link" to="/build">
              {p.builderCta}
            </Link>
          </p>
        </div>
      </section>
    </div>
  );
}

function TierCard({ id, prev }: { id: TierId; prev: TierId | null }) {
  const { lang } = useLanguage();
  const p = pricingPageCopy[lang];
  const t = p.tiers[id];
  return (
    <article className="tier">
      <h3>{t.name}</h3>
      <p className="seats">{t.seats}</p>
      <p className="price tnum">
        {t.price}
        {t.per && <small>{t.per}</small>}
      </p>
      <ul className="ticks">
        {prev && <li className="ev">{p.everythingIn(p.tiers[prev].name)}</li>}
        {t.items.map((item) => (
          <li key={item}>{item}</li>
        ))}
        {t.soon.map((item) => (
          <li key={item} className="soon">
            {item} <small>{p.inProgress}</small>
          </li>
        ))}
      </ul>
    </article>
  );
}
