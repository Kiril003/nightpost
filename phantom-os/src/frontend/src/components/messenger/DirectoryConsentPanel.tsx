/**
 * МОЄ ІМʼЯ ДЛЯ ПОШУКУ — згода бути в довіднику, і ціна, названа вголос.
 *
 * З коробки людини в довіднику НЕМАЄ. Це не налаштування десь углибині і не
 * галочка «покращити пошук»: поки людина сама себе туди не поклала, слова, за
 * яким її можна знайти на сервері, не існує. «Не опубліковано» тут — стан, а
 * не порожнє поле.
 *
 * Чому перелік того, що стане видно, стоїть НА ЕКРАНІ, а не в документації:
 * людина погоджується один раз і назавжди, і «безпечно й приватно» — це не
 * згода, а вмовляння. Тому сказано рівно те, що сервер побачить, включно з
 * незручним: відбиток імені йде без солі й без розтягування, тож коротке імʼя
 * підбирається словником, а з дампу бази видно, СКІЛЬКИ людей опублікувалось.
 * Ці два речення — дзеркало того, що вже каже апарат
 * (`strings.xml: dir_row_server_sees`); розійтись їм не можна, бо це одна й та
 * сама обіцянка на двох склах.
 *
 * Підписує ЗАВЖДИ апарат. Приватний ключ лежить там, браузер його не бачить і
 * бачити не мусить, тож ця панель не шифрує й не підписує нічого — вона
 * передає намір мостові (`WebMessengerDirectory.route`, ручка `me`). Там, де
 * моста немає (браузер на столі проти вузла), панель каже це просто й не
 * малює кнопки, яка нічого не зробить: мертвої кнопки тут не буде.
 *
 * «Зняти себе» справді знімає: локальний стан гасне ЛИШЕ після того, як
 * сервер підтвердив. Показувати «знято», поки запис живий, було б рівно тією
 * брехнею, від якої цей екран мав би боронити.
 */
import { useTranslation } from '../../i18n/useTranslation';
import { dateTag } from '../../i18n/dateTag';
import { useCallback, useEffect, useState } from 'react';
import { EyeOff, Globe, Loader2 } from 'lucide-react';
import type { MessageKey } from '../../i18n/messages/uk';
import { messengerApi } from '../../services/messengerApi';
import { directoryOrigin, MIN_PUBLISH_LENGTH, normaliseForPublish } from '../../services/peerDirectory';

type State =
  | { kind: 'loading' }
  /** Моста немає — згода живе там, де ключ. Не помилка, а інша машина. */
  | { kind: 'unavailable' }
  | { kind: 'off' }
  | { kind: 'on'; handle: string; until?: string };

/** Рівно те, що сервер побачить. Без жодного «тощо». */
const WHAT_THE_SERVER_SEES: MessageKey[] = [
  'directoryConsentPanel.sees1',
  'directoryConsentPanel.sees2',
  'directoryConsentPanel.sees3',
  'directoryConsentPanel.sees4',
];

/** І окремо — чого сервер НЕ побачить. Теж перелічено, бо це половина угоди. */
const WHAT_IT_NEVER_SEES: MessageKey[] = [
  'directoryConsentPanel.never1',
  'directoryConsentPanel.never2',
  'directoryConsentPanel.never3',
  'directoryConsentPanel.never4',
];

const CARD = 'p-3 rounded-card bg-raised border border-line';

export function DirectoryConsentPanel() {
  const { t, locale } = useTranslation();
  const [state, setState] = useState<State>({ kind: 'loading' });
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const read = useCallback(async () => {
    try {
      const me = await messengerApi.directoryMe();
      setState(
        me.published && me.handle
          ? { kind: 'on', handle: me.handle, until: me.until }
          : { kind: 'off' },
      );
    } catch {
      // Ручки немає — значить, і ключа тут немає. Це не поломка.
      setState({ kind: 'unavailable' });
    }
  }, []);

  useEffect(() => {
    void read();
  }, [read]);

  const publish = async () => {
    const handle = normaliseForPublish(draft);
    if (!handle) {
      setNotice(
        t('directoryConsentPanel.badName', { min: MIN_PUBLISH_LENGTH }),
      );
      return;
    }
    setBusy(true);
    setNotice(null);
    try {
      await messengerApi.directoryPublish(handle);
      setDraft('');
      // Стан перечитуємо з апарата, а не вигадуємо з відповіді: опублікованим
      // лічиться те, що підтвердив сервер.
      await read();
    } catch (err) {
      setNotice(err instanceof Error ? err.message : t('directoryConsentPanel.publishFailed'));
    } finally {
      setBusy(false);
    }
  };

  const withdraw = async () => {
    setBusy(true);
    setNotice(null);
    try {
      await messengerApi.directoryWithdraw();
      await read();
    } catch (err) {
      // Найважливіша відмова на цьому екрані: якщо зняття не вдалось, запис
      // ЖИВИЙ, і сказати «знято» тут означало б збрехати про те саме, про що
      // ця панель і мусить не брехати.
      setNotice(
        err instanceof Error
          ? t('directoryConsentPanel.withdrawFailedWith', { message: err.message })
          : t('directoryConsentPanel.withdrawFailed'),
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-2.5" data-testid="directory-consent">
      <div className="flex items-center gap-1.5">
        <Globe className="w-3.5 h-3.5 text-accent-text shrink-0" />
        <span className="text-caption font-extrabold text-ink-2 ">
          {t('directoryConsentPanel.title')}
        </span>
      </div>

      {state.kind === 'loading' && (
        <p className={`${CARD} text-footnote text-ink-2 flex items-center gap-2`}>
          <Loader2 className="w-3.5 h-3.5 animate-spin shrink-0" />
          {t('directoryConsentPanel.asking')}
        </p>
      )}

      {state.kind === 'unavailable' && (
        <p className={`${CARD} text-footnote text-ink-2 leading-relaxed`}>
          {t('directoryConsentPanel.unavailable')}
        </p>
      )}

      {state.kind === 'off' && (
        <div className={`${CARD} space-y-3`} data-testid="directory-consent-off">
          <p className="text-footnote text-ink font-bold leading-relaxed">
            {t('directoryConsentPanel.notListed')}
          </p>
          <p className="text-caption text-ink-2 leading-relaxed">
            {t('directoryConsentPanel.offBody', { origin: directoryOrigin().replace(/^https?:\/\//, '') })}
          </p>

          <div className="p-2.5 rounded-control bg-accent-soft border border-accent-line/30 space-y-1.5">
            <span className="block text-caption font-extrabold text-accent-text ">
              {t('directoryConsentPanel.serverSees')}
            </span>
            <ul className="space-y-1 pl-4 list-disc text-caption text-ink-2 leading-snug">
              {WHAT_THE_SERVER_SEES.map((line) => (
                <li key={line}>{t(line)}</li>
              ))}
            </ul>
          </div>

          <div className="p-2.5 rounded-control bg-surface border border-line space-y-1.5">
            <span className="block text-caption font-extrabold text-ink-2 ">
              {t('directoryConsentPanel.serverNeverSees')}
            </span>
            <ul className="space-y-1 pl-4 list-disc text-caption text-ink-2 leading-snug">
              {WHAT_IT_NEVER_SEES.map((line) => (
                <li key={line}>{t(line)}</li>
              ))}
            </ul>
          </div>

          <p className="text-caption text-ink-2 leading-snug">
            {t('directoryConsentPanel.whoTypes1')} <b>{t('directoryConsentPanel.whoTypesBold')}</b> {t('directoryConsentPanel.whoTypes2')}
          </p>

          <div className="flex items-stretch gap-2">
            <input
              type="text"
              value={draft}
              onChange={(e) => {
                setDraft(e.target.value);
                if (notice) setNotice(null);
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && draft.trim() && !busy) {
                  void publish();
                }
              }}
              placeholder={t('directoryConsentPanel.namePlaceholder')}
              aria-label={t('directoryConsentPanel.nameLabel')}
              className="flex-1 min-w-0 min-h-[48px] px-3.5 py-2.5 text-sm rounded-card border border-line bg-surface text-ink placeholder-[color:var(--ph-lit-c64748b)] focus:outline-none focus:border-accent-line font-semibold"
            />
            <button
              type="button"
              onClick={() => void publish()}
              disabled={!draft.trim() || busy}
              className="shrink-0 min-h-[48px] px-4 rounded-card bg-accent hover:bg-accent text-on-accent text-footnote font-extrabold disabled:bg-raised disabled:text-ink-3 transition-colors"
            >
              {busy ? t('directoryConsentPanel.publishing') : t('directoryConsentPanel.listMe')}
            </button>
          </div>
        </div>
      )}

      {state.kind === 'on' && (
        <div className={`${CARD} space-y-2.5`} data-testid="directory-consent-on">
          <div>
            <span className="block font-bold text-footnote text-ink">
              {t('directoryConsentPanel.listedAs')} <span className=" text-accent-text">@{state.handle}</span>
            </span>
            <span className="block text-caption text-ink-2 leading-snug mt-1">
              {state.until
                ? `${t('directoryConsentPanel.keptUntil', { date: new Date(state.until).toLocaleDateString(dateTag(locale)) })} `
                : ''}
              {t('directoryConsentPanel.onSummary')}
            </span>
          </div>
          <button
            type="button"
            onClick={() => void withdraw()}
            disabled={busy}
            className="w-full min-h-[48px] px-4 rounded-card bg-overlay hover:bg-overlay border border-danger text-danger text-footnote font-extrabold disabled:opacity-60 transition-colors inline-flex items-center justify-center gap-2"
          >
            <EyeOff className="w-3.5 h-3.5" />
            {busy ? t('directoryConsentPanel.removing') : t('directoryConsentPanel.removeMe')}
          </button>
        </div>
      )}

      {notice && (
        <p className="text-caption text-warning leading-relaxed" role="status">
          {notice}
        </p>
      )}
    </div>
  );
}
