import BuildCard from '../components/BuildCard';
import Waitlist from '../components/Waitlist';
import Qr from '../components/wow/Qr';
import Rich from '../components/wow/Rich';
import { downloadCopy, type DownloadCopy } from '../i18n/download';
import { useLanguage } from '../i18n';
import { wowCopy } from '../i18n/wow';
import { CHANNELS } from '../lib/company';
import { buildFor, isDebugBuild, useDownloads, type Build, type Downloads } from '../lib/downloads';
import { SITE_ORIGIN, usePageMeta } from '../lib/meta';

const DESKTOP = ['windows', 'linux'] as const;

/** Те саме слово, що й у картці: стан приходить від сервера, а не з тексту. */
function apkStatus(downloads: Downloads, build: Build | null, c: DownloadCopy): { ready: boolean; text: string } {
  const s = c.ways.apk.state;
  if (downloads.state === 'asking') return { ready: false, text: s.asking };
  if (build) return isDebugBuild(build) ? { ready: false, text: s.test } : { ready: true, text: s.ready };
  if (downloads.state === 'gated') return { ready: false, text: s.gated };
  if (downloads.state === 'unreachable') return { ready: false, text: s.unreachable };
  return { ready: false, text: s.none };
}

export default function Download() {
  const { lang } = useLanguage();
  const legacy = downloadCopy[lang];
  const w = wowCopy[lang].download;
  usePageMeta(w.meta);
  const downloads = useDownloads();
  const android = buildFor(downloads, 'android');
  const apk = apkStatus(downloads, android, legacy);
  const desktopBuilds = DESKTOP.filter((p) => buildFor(downloads, p));
  const file = android?.filename ?? legacy.verify.fileHint;
  const [scan, ...rest] = w.steps;

  return (
    <div className="t">
      <section className="wrap dl-top">
        <div className="dl-intro">
          <p className="kick">{wowCopy[lang].nav.download}</p>
          <h1>
            <Rich t={w.titleRich} />
          </h1>
          <p className="lead">
            <span className="desk-only">{w.lead}</span>
            <span className="phone-only">{w.leadShort}</span>
          </p>
        </div>
        <ol className="nsteps">
          <li className="desk-only">
            <b>{scan[0]}</b>
            <p>{scan[1]}</p>
          </li>
          {rest.map(([title, text]) => (
            <li key={title}>
              <b>{title}</b>
              <p>{text}</p>
            </li>
          ))}
        </ol>
        <div className="dl-side">
          <div className="handoff">
            <div className="qrbox">
              <Qr text={`${SITE_ORIGIN}/download`} label={w.handoff.qrAlt} />
            </div>
            <div>
              <b>{w.handoff.title}</b>
              <p>{w.handoff.text}</p>
            </div>
          </div>
          <BuildCard id="android" />
        </div>
      </section>

      <section className="sec" aria-labelledby="ways-t">
        <div className="wrap">
          <div className="sec-h">
            <h2 id="ways-t">
              <Rich t={w.ways.titleRich} />
            </h2>
            <p>{w.ways.lead}</p>
          </div>
          <ul className="ways">
            {w.rows.map(([name, desc], i) => {
              const status = i === 0 ? apk : { ready: false, text: w.notYet };
              return (
                <li key={name}>
                  <b>{name}</b>
                  <p>{desc}</p>
                  <span className={status.ready ? 'st ok' : 'st'}>{status.text}</span>
                </li>
              );
            })}
          </ul>
          <p className="other">{w.others}</p>
          {desktopBuilds.length ? (
            <div className="desk-builds">
              {desktopBuilds.map((p) => (
                <BuildCard key={p} platform={p} id={p} />
              ))}
            </div>
          ) : (
            <Waitlist platform="desktop" />
          )}

          <div className="acc">
            {w.checks.map(([title, note, text], i) => (
              <details key={title} open={i === 0}>
                <summary>
                  {title} <span>{note}</span>
                </summary>
                <div className="acc-b">
                  <p>{text}</p>
                  {i === 0 && (
                    <>
                      <code>{legacy.verify.command(file)}</code>
                      <p>
                        {w.onWindows} <code className="in">{legacy.verify.windowsCommand(file)}</code>
                      </p>
                    </>
                  )}
                </div>
              </details>
            ))}
          </div>
        </div>
      </section>

      <section className="wrap help">
        <b>{w.help.title}</b>
        <span>
          {w.help.write}{' '}
          <a className="link" href={`mailto:${CHANNELS.support.email}`}>
            {CHANNELS.support.email}
          </a>{' '}
          {w.help.tail}
        </span>
      </section>
    </div>
  );
}
