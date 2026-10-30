import { Fragment, useEffect, useRef, type CSSProperties } from 'react';
import { Link } from 'react-router-dom';
import '../styles/d1.css';
import { useLanguage } from '../i18n';
import { homeD1Copy } from '../i18n/homeD1';
import { bindD1Motion } from '../lib/d1motion';
import { usePageMeta } from '../lib/meta';

const shot = (key: string) => `/d1/phone/${key}-night.webp`;
const stagger = (i: number): CSSProperties => ({ '--i': i } as CSSProperties);

export default function HomeD1() {
  const { lang } = useLanguage();
  const h = homeD1Copy[lang] ?? homeD1Copy.en;
  usePageMeta(h.meta);

  const rootRef = useRef<HTMLDivElement>(null);
  useEffect(() => bindD1Motion(rootRef.current), []);

  return (
    <div className="d1" ref={rootRef}>
      <section className="hero" data-scrub>
        <div className="wrap c">
          <h1 className="d1" data-r>
            {h.h1}
          </h1>
          <p className="sub" data-r style={stagger(1)}>
            {h.sub}
          </p>
          <div className="ctas" data-r style={stagger(2)}>
            <Link className="btn pri" to="/download">
              {h.cta}
            </Link>
            <a className="btn ghost" href="#jobs">
              {h.more}
            </a>
          </div>
          <p className="facts" data-r style={stagger(3)}>
            {h.facts.map((fact, i) => (
              <Fragment key={fact}>
                {i > 0 && <i aria-hidden="true" />}
                {fact}
              </Fragment>
            ))}
          </p>
        </div>
        <div className="trio">
          <img className="ph l" src={shot('talk-task')} alt="" />
          <img className="ph c" src={shot('money-order')} alt="" />
          <img className="ph r" src={shot('place-shifts')} alt="" />
        </div>
      </section>

      <section id="jobs" className="breadth wrap">
        <p className="eb" data-r>
          {h.breadthEyebrow}
        </p>
        <h2 className="h2" data-r>
          {h.breadthTitle}
        </h2>
        <div className="grid">
          {h.jobs.map((j, i) => (
            <article key={j.id} className="job" data-r style={stagger(i % 3)}>
              <p className="no">{String(i + 1).padStart(2, '0')}</p>
              <h3>{j.n}</h3>
              <p>{j.line}</p>
              <div className="shot">
                {j.shot ? (
                  <img className="ph" src={shot(j.shot)} alt="" />
                ) : (
                  <div className="words">
                    {j.words?.map((w) => (
                      <span key={w}>{w}</span>
                    ))}
                  </div>
                )}
              </div>
            </article>
          ))}
        </div>
      </section>

      <section className="scene" data-scrub="pin" style={{ '--n': h.scene.length } as CSSProperties}>
        <div className="wrap scene-h">
          <p className="eb">{h.sceneEyebrow}</p>
          <h2 className="h2">{h.sceneTitle}</h2>
        </div>
        <div className="pin">
          <div className="wrap two">
            <div className="steps">
              <p className="eb">{h.sceneEyebrow}</p>
              <h2 className="h2">{h.sceneTitle}</h2>
              <ol>
                {h.scene.map((s, i) => (
                  <li key={s.t} data-step className={i === 0 ? 'on' : ''}>
                    <span className="t">{s.t}</span>
                    <b>{s.h}</b>
                    <span className="p">{s.p}</span>
                  </li>
                ))}
              </ol>
            </div>
            <div className="stage">
              {h.scene.map((s, i) => (
                <img key={s.t} data-scr className={i === 0 ? 'ph on' : 'ph'} src={shot(s.shot)} alt="" />
              ))}
            </div>
          </div>
        </div>
        <div className="stack wrap">
          <p className="eb" data-r>
            {h.sceneEyebrow}
          </p>
          <h2 className="h2" data-r>
            {h.sceneTitle}
          </h2>
          {h.scene.map((s) => (
            <div key={s.t} className="srow" data-r>
              <div className="stx">
                <span className="t">{s.t}</span>
                <b>{s.h}</b>
                <span className="p">{s.p}</span>
              </div>
              <img className="ph" src={shot(s.shot)} alt="" />
            </div>
          ))}
        </div>
      </section>

      <section className="band wrap c">
        <h2 className="d2" data-r>
          {h.priceTitle}
        </h2>
        <p className="sub" data-r style={stagger(1)}>
          {h.priceLine}
        </p>
        <Link className="btn sec" data-r style={stagger(2)} to="/pricing">
          {h.priceCta}
        </Link>
      </section>

      <section className="end wrap c">
        <h2 className="d0" data-r>
          {h.endTitle}
        </h2>
        <Link className="btn pri" data-r style={stagger(1)} to="/download">
          {h.endCta}
        </Link>
      </section>
    </div>
  );
}
