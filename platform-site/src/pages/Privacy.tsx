import { Mail } from 'lucide-react';
import { Section } from '../components/ui';
import { useLanguage } from '../i18n';
import { deleteAccountCopy } from '../i18n/deleteAccount';
import { legalCopy, type LegalClause, type LegalDoc } from '../i18n/legal';
import { CHANNELS, mailto } from '../lib/company';
import { LEGAL_REVISION } from '../lib/entity';
import { usePageMeta } from '../lib/meta';

export default function Privacy() {
  const { lang } = useLanguage();
  const c = legalCopy[lang];
  const d = deleteAccountCopy[lang];
  const docs = c.docs.filter((doc) => doc.id === 'privacy' || doc.id === 'app');
  usePageMeta({ title: `${d.privacyTitle} — Nightpost`, description: docs[0]?.lead ?? c.meta.description });

  return (
    <>
      <section className="px-4 pb-6 pt-12 sm:px-6 lg:px-14 md:pt-20">
        <div className="mx-auto max-w-shell">
          <p className="eyebrow">{c.eyebrow}</p>
          <h1 className="mt-3 max-w-4xl text-balance text-page-h1 text-ink">{docs[0]?.title ?? d.privacyTitle}</h1>
          <p className="tnum mt-4 text-footnote text-ink-3">
            {c.revisionLabel} {LEGAL_REVISION}
          </p>
        </div>
      </section>

      <Section className="!pt-6" contain={false}>
        <div className="min-w-0">
          {docs.map((doc, index) => (
            <Doc key={doc.id} doc={doc} showTitle={index > 0} />
          ))}

          <div className="mt-14 border-t border-line pt-8">
            <h2 className="text-title-2 text-ink">{c.contactHeading}</h2>
            <p className="mt-2 max-w-prose text-callout text-ink-2">{c.contactBody}</p>
            <a href={mailto('support', 'Nightpost privacy')} className="btn-secondary mt-5">
              <Mail className="h-4 w-4" strokeWidth={1.75} aria-hidden />
              {CHANNELS.support.email}
            </a>
          </div>
        </div>
      </Section>
    </>
  );
}

function Doc({ doc, showTitle = true }: { doc: LegalDoc; showTitle?: boolean }) {
  return (
    <section id={doc.id} className="mt-14 scroll-mt-24 border-t border-line pt-10 first:mt-0 first:border-t-0 first:pt-0">
      {showTitle && <h2 className="text-h2 text-ink">{doc.title}</h2>}
      <p className="mt-3 max-w-prose text-body text-ink-2">{doc.lead}</p>
      <div className="mt-8 grid gap-8">
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
      <h3 className="text-title-2 text-ink">{clause.heading}</h3>
      {clause.body.map((paragraph) => (
        <p key={paragraph} className="mt-3 max-w-prose text-body text-ink-2">
          {paragraph}
        </p>
      ))}
      {clause.bullets && (
        <ul className="mt-4 grid max-w-prose gap-2.5">
          {clause.bullets.map((bullet) => (
            <li key={bullet} className="flex gap-3 text-callout text-ink-2">
              <span className="mt-[0.6em] h-1.5 w-1.5 shrink-0 rounded-full bg-ink-3" aria-hidden />
              {bullet}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
