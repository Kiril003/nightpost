import { Download } from 'lucide-react';
import { Link } from 'react-router-dom';
import Qr from '../components/wow/Qr';
import Rich from '../components/wow/Rich';
import { useLanguage } from '../i18n';
import { PROOF_TX, topCopy, type TopChapter } from '../i18n/top';
import { SITE_ORIGIN, usePageMeta } from '../lib/meta';

const DOWNLOAD_URL = `${SITE_ORIGIN}/download`;

/** Кадр телефона з Blender: денний і нічний, тема вирішує, який видно. */
function Still({ name, alt }: { name: string; alt: string }) {
  return (
    <>
      <img className="d" src={`/top/${name}-day.webp`} alt={alt} />
      <img className="n" src={`/top/${name}-night.webp`} alt={alt} />
    </>
  );
}

function Copy({ c, no, ask }: { c: TopChapter; no: number; ask: string }) {
  return (
    <div className="ch-copy">
      <p className="kick">
        <span className="tnum">{String(no).padStart(2, '0')}</span>
        {c.kick}
      </p>
      <h2>
        <Rich t={c.title} />
      </h2>
      <p className="body">{c.body}</p>
      <dl className="facts">
        {c.facts.map(([dt, dd]) => (
          <div key={dt}>
            <dt>{dt}</dt>
            <dd>{dd}</dd>
          </div>
        ))}
      </dl>
      <p className="ask">
        <span className="ask-k">{ask}</span>
        <q>{c.ask}</q>
        <span className="ask-r">{c.answer}</span>
      </p>
    </div>
  );
}

export default function HomeTop() {
  const { lang } = useLanguage();
  const h = topCopy[lang];
  usePageMeta(h.meta);
  const solo = h.chapters.slice(0, 4);
  const money = h.chapters[4];

  return (
    <div className="t">
      <section className="hx">
        <div className="wrap hx-in">
          <div className="hx-copy">
            <h1>
              <Rich t={h.hero.title} />
            </h1>
            <p className="lede">{h.hero.lede}</p>
            <div className="cta-row">
              <Link className="btn btn-primary btn-lg" to="/download">
                <Download strokeWidth={2} aria-hidden />
                {h.hero.cta}
              </Link>
              <div className="qr-mini">
                <div className="qrbox">
                  <Qr text={DOWNLOAD_URL} label={h.get.qrAlt} />
                </div>
                <span>
                  {h.hero.scan[0]}
                  <br />
                  {h.hero.scan[1]}
                </span>
              </div>
            </div>
            <p className="meta">{h.hero.facts}</p>
          </div>
          <div className="hx-ph">
            <Still name="hero" alt={h.hero.alt} />
          </div>
        </div>
      </section>

      {solo.map((c, i) => {
        const left = i % 2 === 0;
        const ph = (
          <div className="ch-ph">
            <Still name={c.id} alt={c.alt[0]} />
          </div>
        );
        return (
          <section key={c.id} className="ch" id={c.id}>
            <div className={`wrap ch-in ${left ? 'ph-left' : 'ph-right'}`}>
              {left && ph}
              <Copy c={c} no={i + 1} ask={h.ask} />
              {!left && ph}
            </div>
          </section>
        );
      })}

      <section className="ch ch-two" id={money.id}>
        <div className="wrap ch-in">
          <Copy c={money} no={5} ask={h.ask} />
          <div className="two-ph">
            <div className="p1">
              <Still name="place" alt={money.alt[0]} />
            </div>
            <div className="p2">
              <Still name="money" alt={money.alt[1]} />
            </div>
          </div>
        </div>
      </section>

      <section className="proof">
        <div className="wrap">
          <div className="proof-head">
            <h2>
              <Rich t={h.proof.title} />
            </h2>
            <p className="body">{h.proof.lead}</p>
          </div>
          <div className="proof-grid">
            <article className="pcard">
              <p className="pcard-k">{h.proof.app.k}</p>
              <h3>{h.proof.app.h}</h3>
              <p>{h.proof.app.p}</p>
              <Link className="link" to="/download">
                {h.proof.app.link}
              </Link>
            </article>
            <article className="pcard pcard-tx">
              <p className="pcard-k">{h.proof.tx.k}</p>
              <p className="amt tnum">
                {PROOF_TX.amount} <span>{h.proof.tx.unit}</span>
              </p>
              <dl className="tx tnum">
                {[PROOF_TX.instructions, PROOF_TX.fee, PROOF_TX.slot, h.proof.tx.time].map((v, i) => (
                  <div key={h.proof.tx.labels[i]}>
                    <dt>{h.proof.tx.labels[i]}</dt>
                    <dd>{v}</dd>
                  </div>
                ))}
              </dl>
              <a className="link" href={`https://explorer.solana.com/tx/${PROOF_TX.signature}?cluster=devnet`} target="_blank" rel="noreferrer">
                {h.proof.tx.explorer}
              </a>
              <p className="fine">{h.proof.tx.fine}</p>
            </article>
            <article className="pcard">
              <p className="pcard-k">{h.proof.builder.k}</p>
              <h3>{h.proof.builder.h}</h3>
              <p>{h.proof.builder.p}</p>
              <Link className="link" to="/builder">
                {h.proof.builder.link}
              </Link>
            </article>
          </div>
        </div>
      </section>

      <section className="get" id="get">
        <div className="wrap get-in">
          <div>
            <h2>
              <Rich t={h.get.title} />
            </h2>
            <Link className="btn btn-primary btn-lg" to="/download">
              <Download strokeWidth={2} aria-hidden />
              {h.get.cta}
            </Link>
            <p className="meta">{h.hero.facts}</p>
          </div>
          <div className="get-side">
            <div className="qr-big">
              <Qr text={DOWNLOAD_URL} label={h.get.qrAlt} />
            </div>
            <ol className="gsteps">
              {h.get.steps.map((s, i) => (
                <li key={s}>
                  <span className="tnum">{i + 1}</span>
                  {s}
                </li>
              ))}
            </ol>
          </div>
        </div>
      </section>
    </div>
  );
}
