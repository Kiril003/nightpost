import { useEffect } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import Accent from '../components/wow/Accent';
import { useLanguage } from '../i18n';
import { legalCopy, type LegalClause, type LegalDoc } from '../i18n/legal';
import { LEGAL_REVISION, SELLING_ENTITY, entityIsConfigured } from '../lib/entity';
import { usePageMeta } from '../lib/meta';
import { CHANNELS, mailto } from '../lib/company';

export default function Legal() {
  const { lang } = useLanguage();
  const c = legalCopy[lang];
  usePageMeta(c.meta);
  const configured = entityIsConfigured();
  const { hash } = useLocation();
  const navigate = useNavigate();
  const docs = c.docs.filter((doc) => doc.id !== 'privacy' && doc.id !== 'app');
  const privacyNav = c.docs.find((doc) => doc.id === 'privacy')?.nav;

  useEffect(() => {
    if (hash === '#privacy' || hash === '#app') navigate(`/privacy${hash === '#app' ? '#app' : ''}`, { replace: true });
  }, [hash, navigate]);

  return (
    <div className="t">
      <section className="ph-head">
        <div className="wrap">
          <p className="kick">{c.eyebrow}</p>
          <h1>
            <Accent text={c.title} words={4} />
          </h1>
          <p className="lead">{c.lead}</p>
          <ul className="tags" style={{ marginTop: 28 }}>
            <li className="tnum">
              {c.revisionLabel} {LEGAL_REVISION}
            </li>
          </ul>
        </div>
      </section>

      <section className="wrap doc">
        {/* Зміст лишається на місці при гортанні — документ довгий. */}
        <nav className="toc" aria-label={c.eyebrow}>
          {docs.map((doc) => (
            <a key={doc.id} href={`#${doc.id}`}>
              {doc.nav}
            </a>
          ))}
          {privacyNav && <Link to="/privacy">{privacyNav}</Link>}
          <a href="#seller">{c.entityHeading}</a>
        </nav>

        <article className="prose">
          <div className="notes">
            <div>
              <b>{c.draftTitle}</b>
              <p>{c.draftBody}</p>
            </div>
            {!configured && (
              <div>
                <b>{c.notEntityTitle}</b>
                <p>{c.notEntityBody}</p>
              </div>
            )}
          </div>

          {docs.map((doc) => (
            <Doc key={doc.id} doc={doc} />
          ))}

          <div className="seller" id="seller">
            <h2>{c.entityHeading}</h2>
            <dl>
              {(
                [
                  ['legalName', SELLING_ENTITY.legalName],
                  ['registrationId', SELLING_ENTITY.registrationId],
                  ['address', SELLING_ENTITY.address],
                  ['jurisdiction', SELLING_ENTITY.jurisdiction],
                  ['vatNote', SELLING_ENTITY.vatNote],
                ] as const
              ).map(([field, value]) => (
                <div key={field}>
                  <dt>{c.entityFields[field]}</dt>
                  <dd>{value ?? '—'}</dd>
                </div>
              ))}
            </dl>
          </div>

          <div className="seller">
            <h2>{c.contactHeading}</h2>
            <p>{c.contactBody}</p>
            <p>
              <a className="link" href={mailto('support', 'Nightpost legal')}>
                {CHANNELS.support.email}
              </a>
            </p>
          </div>
        </article>
      </section>
    </div>
  );
}

function Doc({ doc }: { doc: LegalDoc }) {
  return (
    <section id={doc.id} className="ldoc">
      <h2>{doc.title}</h2>
      <p className="dlead">{doc.lead}</p>
      <div>
        {doc.clauses.map((clause) => (
          <Clause key={clause.heading} clause={clause} />
        ))}
      </div>
    </section>
  );
}

function Clause({ clause }: { clause: LegalClause }) {
  return (
    <div>
      <h3>{clause.heading}</h3>
      {clause.body.map((paragraph) => (
        <p key={paragraph}>{paragraph}</p>
      ))}
      {clause.bullets && (
        <ul className="bul">
          {clause.bullets.map((bullet) => (
            <li key={bullet}>{bullet}</li>
          ))}
        </ul>
      )}
    </div>
  );
}
