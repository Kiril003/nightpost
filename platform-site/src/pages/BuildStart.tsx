import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ArrowLeft, Check, X } from 'lucide-react';
import '@fontsource-variable/source-serif-4/opsz.css';
import '@fontsource-variable/source-serif-4/opsz-italic.css';
import '@fontsource-variable/inter-tight';
import '@fontsource-variable/wix-madefor-display';
import '../styles/creator.css';
import CreatorPage from '../components/creator/CreatorPage';
import { useLanguage } from '../i18n';
import { LOOK, LOOKS, isLook, type Look } from '../lib/creator/looks';
import { sampleByHandle, imageUrl } from '../lib/creator/samples';
import { WHO, WHO_IMAGE, back, editorHref, next, pickWho, previewHref, sampleFor, startState, type Start, type Who } from '../lib/creator/start';
import { builderChrome, builderTheme } from '../lib/creator/chrome';

const COPY = {
  en: {
    whoTitle: 'Who are you publishing as?',
    whoSub: 'You can add more pages later',
    who: {
      me: ['Just me', 'Sell what you make, take bookings, share your work'],
      group: ['A group', 'Band, club, team or crew with shared shows and chat'],
      place: ['A place', 'Café, studio or shop with hours, menu and staff'],
    } as Record<Who, [string, string]>,
    lookTitle: 'Pick a look',
    lookSub: 'Same blocks and words, three ways to show them. Switch any time.',
    looks: { journal: ['Journal', 'Serif type, quiet rules'], studio: ['Studio', 'Big type, colour blocks'], shop: ['Shop', 'Tiles and a product grid'] } as Record<Look, [string, string]>,
    cont: 'Continue',
    fromText: 'Start from a description',
    start: 'Start editing',
    example: 'See an example',
    close: 'Close',
    back: 'Back',
    preview: 'Live preview',
  },
  uk: {
    whoTitle: 'Від чийого імені публікуєте?',
    whoSub: 'Інші сторінки можна додати пізніше',
    who: {
      me: ['Лише я', 'Продавайте зроблене, приймайте записи, показуйте роботу'],
      group: ['Гурт', 'Гурт, клуб, команда з виступами й спільним чатом'],
      place: ['Місце', 'Кавʼярня, студія чи крамниця з годинами, меню й людьми'],
    } as Record<Who, [string, string]>,
    lookTitle: 'Оберіть вигляд',
    lookSub: 'Ті самі блоки й слова, три способи показати. Змінити можна будь-коли.',
    looks: { journal: ['Журнал', 'Шрифт із засічками, тонкі лінії'], studio: ['Студія', 'Великий шрифт, кольорові блоки'], shop: ['Крамниця', 'Плитки й сітка товарів'] } as Record<Look, [string, string]>,
    cont: 'Далі',
    fromText: 'Почати з опису й прайсу',
    start: 'Почати редагувати',
    example: 'Подивитись приклад',
    close: 'Закрити',
    back: 'Назад',
    preview: 'Наживо',
  },
};

const KEY = 'np.creator.start';

function Preview({ who, look, theme }: { who: Who; look: Look; theme: 'day' | 'night' }) {
  const { lang } = useLanguage();
  const box = useRef<HTMLDivElement>(null);
  const [w, setW] = useState(0);
  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setW(el.clientWidth));
    ro.observe(el);
    setW(el.clientWidth);
    return () => ro.disconnect();
  }, []);
  const sample = sampleByHandle(sampleFor(who, lang))!;
  const inner = w >= 560 ? 1280 : 390;
  const scale = w ? w / inner : 0;
  return (
    <div ref={box} className="bs-preview" aria-label={COPY[lang].preview} data-look={look}>
      {scale > 0 && (
        <div style={{ width: inner, transform: `scale(${scale})`, transformOrigin: '0 0' }} aria-hidden="true" {...{ inert: '' }}>
          <CreatorPage page={sample.page} live={sample.live} look={look} theme={theme} lang={lang} sample frame="preview" />
        </div>
      )}
    </div>
  );
}

export default function BuildStart() {
  const { lang } = useLanguage();
  const t = COPY[lang];
  const [params] = useSearchParams();
  const theme = builderTheme(params.get('theme'));
  const [s, setS] = useState<Start>(() => {
    const init = startState();
    const look = params.get('look');
    return isLook(look) ? { ...init, look } : init;
  });
  useEffect(() => {
    try {
      localStorage.setItem(KEY, JSON.stringify({ who: s.who, look: s.look }));
    } catch {
      /* the choice simply is not remembered */
    }
  }, [s.who, s.look]);
  useEffect(() => {
    document.title = s.step === 1 ? t.whoTitle : t.lookTitle;
  }, [s.step, t]);

  const themeParam = params.get('theme') ?? undefined;
  return (
    <div className="cx bs" data-look="shop" data-theme={theme} data-step={s.step} style={builderChrome(theme) as CSSProperties}>
      <div className="bs-wrap">
        <header className="bs-top">
          {s.step === 1 ? (
            <Link to="/build" className="cx-btn ghost icon m bs-icon" aria-label={t.close}><X size={20} aria-hidden="true" /></Link>
          ) : (
            <button type="button" className="cx-btn ghost icon m bs-icon" aria-label={t.back} onClick={() => setS(back(s))}><ArrowLeft size={20} aria-hidden="true" /></button>
          )}
          <div className="bs-dashes" aria-hidden="true"><i className="on" /><i className={s.step === 2 ? 'on' : ''} /><i /></div>
          <span className="bs-icon-space" />
        </header>

        {s.step === 1 ? (
          <main className="bs-main">
            <h1 className="bs-h1">{t.whoTitle}</h1>
            <p className="cx-muted bs-sub">{t.whoSub}</p>
            <div className="bs-who" role="radiogroup" aria-label={t.whoTitle}>
              {WHO.map((w) => (
                <button type="button" role="radio" aria-checked={s.who === w} key={w} className={`bs-card ${s.who === w ? 'on' : ''}`} onClick={() => setS(pickWho(s, w))}>
                  <img src={imageUrl(WHO_IMAGE[w]) ?? ''} alt="" className="bs-thumb" />
                  <span className="bs-card-text">
                    <b className="cx-h3">{t.who[w][0]}</b>
                    <span className="cx-muted">{t.who[w][1]}</span>
                  </span>
                  <span className="bs-radio">{s.who === w && <Check size={18} aria-hidden="true" />}</span>
                </button>
              ))}
            </div>
            <div className="bs-bar">
              <button type="button" className="cx-btn primary l wide" onClick={() => setS(next(s))}>{t.cont}</button>
              <Link to={themeParam ? `/build/ai?theme=${themeParam}` : '/build/ai'} className="bs-link">{t.fromText}</Link>
            </div>
          </main>
        ) : (
          <main className="bs-main bs-two">
            <div className="bs-side">
              <h1 className="bs-h1">{t.lookTitle}</h1>
              <p className="cx-muted bs-sub">{t.lookSub}</p>
              <div className="bs-looks" role="radiogroup" aria-label={t.lookTitle}>
                {LOOKS.map((l) => (
                  <button type="button" role="radio" aria-checked={s.look === l} key={l} className={`bs-look ${s.look === l ? 'on' : ''}`} onClick={() => setS({ ...s, look: l })}>
                    <span className="bs-aa" style={{ fontFamily: LOOK[l].display, fontWeight: LOOK[l].displayWeight, letterSpacing: LOOK[l].tracking }}>Aa</span>
                    <span className="bs-card-text">
                      <b>{t.looks[l][0]}</b>
                      <span className="cx-muted cx-small">{t.looks[l][1]}</span>
                    </span>
                  </button>
                ))}
              </div>
              <div className="bs-bar">
                <Link to={editorHref(s, themeParam)} className="cx-btn primary l wide">{t.start}</Link>
                <Link to={previewHref(s, themeParam, lang)} className="bs-link">{t.example}</Link>
              </div>
            </div>
            <Preview who={s.who} look={s.look} theme={theme} />
          </main>
        )}
      </div>
    </div>
  );
}
