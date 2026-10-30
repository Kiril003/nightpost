/**
 * ЗНАЙТИ ЛЮДИНУ ЗА НІКОМ — у глобальному довіднику, а не в списку вузла.
 *
 * Це друга й окрема смуга пошуку, і плутати її з першою не можна: сусідній
 * рядок питає ВУЗОЛ, на якому ти сидиш, і знає лише його людей. Ця питає
 * сервер платформи й знає будь-кого, хто сам себе туди поклав. Тому підпис
 * над нею названий вголос — інакше два поля «знайти людину» на одному екрані
 * означали б дві різні відповіді на одне питання без жодного натяку, чому.
 *
 * Три речі, без яких цей екран робив би продукт гіршим, а не кращим:
 *
 *   1. ПІДПИС ЗВІРЕНО ДО ПОКАЗУ. Картка малюється тільки зі стану `found`, а
 *      `found` народжується лише після Ed25519 (`verifyEntry`). Показана
 *      непідтверджена особа — це вже підміна, тож стану «показали, потім
 *      позначимо» тут немає. Сервер, який підсунув чужий ключ, дістає не
 *      значок, а окремий гучний екран.
 *   2. ВІДБИТОК ВИДИМИЙ. 24 цифри великим моноширинним, щоб двоє звірили їх
 *      голосом. Це ті самі цифри, які показує телефон (`PeerInvite.
 *      fingerprint`), інакше звірка «прочитай мені свій відбиток» не звіряла б
 *      нічого.
 *   3. ПОРОЖНІЙ РЕЗУЛЬТАТ КАЖЕ ПРАВДУ. «Немає» — це «немає», а не «ось хтось
 *      схожий». Підказок, списків і схожих імен тут немає за побудовою: адреса
 *      запису — sha256, і скласти з хешів перелік нема з чого.
 *
 * І окремо — те, чого екран не стверджує. Дійсний підпис доводить, що запис
 * склав власник ключа, а НЕ що цей ключ належить тій людині, яку ти шукав.
 * Хто зайняв імʼя першим — той його й має. Тому знайдений приходить із тегом
 * «не звірено», і церемонія звірки ключів лишається тією самою.
 */
import { useTranslation } from '../../i18n/useTranslation';
import { dateTag } from '../../i18n/dateTag';
import { useCallback, useRef, useState } from 'react';
import { Globe, KeyRound, ShieldAlert, Search } from 'lucide-react';
import {
  directoryOrigin,
  lookup,
  normalise,
  spokenFingerprint,
  type LookupResult,
} from '../../services/peerDirectory';

type Phase = { state: 'idle' } | { state: 'searching' } | { state: 'done'; result: LookupResult };

interface Props {
  /**
   * Готовий рядок PH2 знайденого. Веде в ту саму, вже доведену дорогу
   * «запрошення → розмова», а не в другу власну: дві дороги до одного
   * означали б два місця, де ключ може загубитись.
   */
  onUseInvite: (compact: string) => void;
}

const CARD = 'p-3 rounded-control bg-raised ';

export function DirectorySearchPanel({ onUseInvite }: Props) {
  const { t } = useTranslation();
  const [handle, setHandle] = useState('');
  const [phase, setPhase] = useState<Phase>({ state: 'idle' });
  const abort = useRef<AbortController | null>(null);

  const run = useCallback(async () => {
    const name = normalise(handle);
    if (!name) {
      // Форму імені перевіряє клієнт: у мережу не тієї форми не йде нічого.
      setPhase({ state: 'done', result: { kind: 'absent' } });
      return;
    }
    abort.current?.abort();
    const controller = new AbortController();
    abort.current = controller;
    setPhase({ state: 'searching' });
    const result = await lookup(name, controller.signal);
    if (!controller.signal.aborted) setPhase({ state: 'done', result });
  }, [handle]);

  return (
    <div className="space-y-2.5" data-testid="directory-search">
      <div className="flex items-center gap-1.5">
        <Globe className="w-3.5 h-3.5 text-ink-2 shrink-0" />
        <span className="text-footnote font-semibold text-ink-3 ">
          {t('directorySearchPanel.title')}
        </span>
      </div>
      <p className="text-callout text-ink font-semibold leading-snug">
        {t('directorySearchPanel.lead')}
      </p>
      <p className="text-callout text-ink-3 leading-snug">
        {t('directorySearchPanel.explain', { origin: directoryOrigin().replace(/^https?:\/\//, '') })}
      </p>

      <div className="flex items-stretch gap-2">
        {/* Підпис поля — не «@імʼя»: сусіднє поле вже підписане так, і два
            однакових поля на одному екрані означали б два різні пошуки без
            жодного способу їх розрізнити. */}
        <input
          type="text"
          value={handle}
          onChange={(e) => {
            setHandle(e.target.value);
            if (phase.state !== 'idle') setPhase({ state: 'idle' });
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              void run();
            }
          }}
          placeholder={t('directorySearchPanel.placeholder')}
          aria-label={t('directorySearchPanel.inputAria')}
          className="flex-1 min-w-0 min-h-[48px] px-3.5 py-2.5 text-callout rounded-control bg-raised text-ink placeholder:text-ink-3 focus:outline-none font-semibold"
        />
        <button
          type="button"
          onClick={() => void run()}
          disabled={!handle.trim() || phase.state === 'searching'}
          className="shrink-0 min-h-[48px] px-4 rounded-control bg-accent hover:bg-accent text-on-accent text-footnote font-semibold disabled:bg-raised disabled:text-ink-3 transition-colors inline-flex items-center gap-1.5"
        >
          <Search className="w-3.5 h-3.5" />
          {/* НЕ просто «Знайти»: сусідня смуга вже має кнопку з таким написом,
              і дві однакові кнопки на одному екрані — це не дрібниця тесту, а
              два різні пошуки, між якими людині не дали способу відрізнити. */}
          {phase.state === 'searching' ? t('directorySearchPanel.checking') : t('directorySearchPanel.check')}
        </button>
      </div>

      {phase.state === 'done' && <Outcome result={phase.result} onUseInvite={onUseInvite} />}
    </div>
  );
}

function Outcome({ result, onUseInvite }: { result: LookupResult; onUseInvite: Props['onUseInvite'] }) {
  const { t } = useTranslation();
  switch (result.kind) {
    case 'found':
      return <FoundCard result={result} onUseInvite={onUseInvite} />;

    case 'absent':
      // Правда, і вся правда: людини в довіднику немає. Це НЕ означає, що її
      // немає — типово в довіднику немає нікого, поки він сам себе не поклав.
      return (
        <p className={`${CARD} text-callout text-ink-3 leading-relaxed`}>
          {t('directorySearchPanel.absentA')} <b>{t('directorySearchPanel.absentB')}</b>. {t('directorySearchPanel.absentC')}
        </p>
      );

    case 'closed':
      return (
        <p className={`${CARD} text-callout text-ink-3 leading-relaxed`}>
          {t('directorySearchPanel.closed')}
        </p>
      );

    case 'unreachable':
      return (
        <p className={`${CARD} text-callout text-ink-2 leading-relaxed`}>
          {t('directorySearchPanel.unreachable')}
        </p>
      );

    case 'unverifiable':
      return (
        <p className={`${CARD} text-callout text-ink-2 leading-relaxed`}>
          {t('directorySearchPanel.unverifiable')}
        </p>
      );

    case 'forged':
      // Гучно й окремо. Це не «не знайшли» — це сервер віддав те, чого не міг
      // скласти власник ключа.
      return (
        <div className="p-3 rounded-control bg-raised flex items-start gap-2">
          <ShieldAlert className="w-4 h-4 text-danger shrink-0 mt-0.5" />
          <span className="text-callout text-danger leading-relaxed">
            {t('directorySearchPanel.forgedA')} <b>{t('directorySearchPanel.forgedB')}</b>. {t('directorySearchPanel.forgedC')}
          </span>
        </div>
      );
  }
}

function FoundCard({
  result,
  onUseInvite,
}: {
  result: Extract<LookupResult, { kind: 'found' }>;
  onUseInvite: Props['onUseInvite'];
}) {
  const { t, locale } = useTranslation();
  const { record } = result;
  const until = new Date(record.expSeconds * 1000);
  const [verifyOpen, setVerifyOpen] = useState(false);
  return (
    <div className={`${CARD} space-y-2.5`} data-testid="directory-found">
      <div className="flex items-center justify-between gap-2">
        <div className="min-w-0">
          <span className="block font-semibold text-callout text-ink truncate">
            @{record.handle}
          </span>
          {/* Імені сервер не знає; код ключа іменем не буває. */}
          <span className="block text-callout text-ink-2 truncate">
            {t('directorySearchPanel.unverified')}
          </span>
        </div>
      </div>

      <button
        type="button"
        aria-expanded={verifyOpen}
        onClick={() => setVerifyOpen((v) => !v)}
        className="w-full min-h-[44px] flex items-center gap-2 text-callout text-ink-2"
      >
        <KeyRound className="w-4 h-4 text-ink-3 shrink-0" />
        {t('directorySearchPanel.verifyLive')}
      </button>

      {verifyOpen && (
      <div className="p-2.5 rounded-control bg-canvas space-y-1">
        <div className="flex items-center gap-1.5">
          <KeyRound className="w-3 h-3 text-ink-3 shrink-0" />
          <span className="text-footnote font-semibold text-ink-3 ">
            {t('directorySearchPanel.fingerprint')}
          </span>
        </div>
        <code
          data-testid="directory-fingerprint"
          className="block text-callout leading-relaxed text-ink break-all"
        >
          {spokenFingerprint(record.fingerprint)}
        </code>
        <p className="text-callout text-ink-3 leading-snug">
          {t('directorySearchPanel.readAloud')}
        </p>
      </div>
      )}

      <div className="flex items-center justify-between gap-2">
        <span className="text-callout text-ink-3">
          {t('directorySearchPanel.validUntil', { date: until.toLocaleDateString(dateTag(locale)) })}
        </span>
        <button
          type="button"
          onClick={() => onUseInvite(record.compact)}
          className="shrink-0 min-h-[44px] px-4 rounded-control bg-accent hover:bg-accent text-on-accent text-footnote font-semibold transition-colors"
        >
          {t('directorySearchPanel.start')}
        </button>
      </div>
    </div>
  );
}
