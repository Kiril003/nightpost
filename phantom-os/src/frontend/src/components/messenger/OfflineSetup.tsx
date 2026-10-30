/**
 * Профіль › PHANTOM › «Без інтернету» (розд. 07): як телефон дістає мій ПК,
 * коли інтернету немає. Єдине місце, де застосунок просить дозволи SMS — і
 * лише після того, як людина прочитала, що це і хто що побачить.
 */
import React, { useCallback, useEffect, useState } from 'react';
import { MessageSquareText, Waypoints } from 'lucide-react';
import { intentsApi, type IntentSetup, type IntentSetupPatch } from '../../services/intentsApi';
import { useIntentsStore } from '../../stores/intentsStore';
import { IntentTrail } from './IntentTrail';
import { SectionTitle } from './MeSections';
import { MeRow } from './MeRow';
import { IntentsList } from './MyIntents';
import { useTranslation } from '../../i18n/useTranslation';

const ICO = { className: 'w-5 h-5 shrink-0', strokeWidth: 1.75 } as const;

const Switch: React.FC<{ on: boolean; onToggle: () => void; label: string }> = ({ on, onToggle, label }) => (
  <button type="button" role="switch" aria-checked={on} aria-label={label} onClick={onToggle}
    className="shrink-0 w-[52px] h-11 min-h-0 min-w-0 flex items-center">
    <span aria-hidden="true" className={`relative w-full h-8 rounded-full transition-colors duration-instant ease-standard ${on ? 'bg-accent' : 'bg-overlay'}`}>
      <span className={`absolute top-1 left-1 w-6 h-6 rounded-full bg-on-accent transition-transform duration-instant ease-standard ${on ? 'translate-x-5' : ''}`} />
    </span>
  </button>
);

const Row: React.FC<{ title: string; meta?: string; children?: React.ReactNode }> = ({ title, meta, children }) => (
  <div className="min-h-[60px] py-2 pl-4 pr-2 flex items-center gap-3">
    <span className="flex-1 min-w-0">
      <span className="block text-body text-ink">{title}</span>
      {meta && <span className="block text-caption text-ink-3">{meta}</span>}
    </span>
    {children}
  </div>
);

const Divider = () => <div aria-hidden="true" className="ml-4 h-px bg-line" />;

const Note: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <p className="mx-4 mt-2 px-1 text-callout text-ink-3">{children}</p>
);

/** Рядок у «Я», що відкриває екран. Немає мосту — немає рядка. */
export const OfflineEntry: React.FC<{ onOpen: () => void }> = ({ onOpen }) => {
  const { t } = useTranslation();
  const [setup, setSetup] = useState<IntentSetup | null>(null);
  useEffect(() => {
    let alive = true;
    intentsApi.setup().then((s) => alive && setSetup(s)).catch(() => alive && setSetup(null));
    return () => {
      alive = false;
    };
  }, []);
  if (!setup) return null;
  const meta = !setup.sms_build
    ? t('offlineSetup.metaPcOnly')
    : setup.permitted && setup.gateway_number
      ? t('offlineSetup.metaPcAndSms')
      : t('offlineSetup.metaSmsOff');
  return <MeRow icon={Waypoints} title={t('offlineSetup.entryTitle')} meta={meta} onClick={onOpen} testId="me-offline" />;
};

export const OfflineSetup: React.FC = () => {
  const { t } = useTranslation();
  const [setup, setSetup] = useState<IntentSetup | null>(null);
  const [number, setNumber] = useState('');
  const [allowed, setAllowed] = useState('');
  const [testId, setTestId] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const rows = useIntentsStore((s) => s.rows);

  const load = useCallback(async () => {
    try {
      const s = await intentsApi.setup();
      setSetup(s);
      setNumber(s.gateway_number);
      setAllowed(s.allowed.join('\n'));
    } catch {
      setSetup(null);
    }
  }, []);

  // Подія мосту (журнал змінився або людина відповіла на системне вікно) — перечитати.
  useEffect(() => {
    void load();
  }, [load, rows]);

  if (!setup) return null;

  const save = async (patch: IntentSetupPatch) => {
    setNote(null);
    try {
      const s = await intentsApi.saveSetup(patch);
      setSetup(s);
      setNumber(s.gateway_number);
      setAllowed(s.allowed.join('\n'));
    } catch {
      setNote(t('offlineSetup.saveFailed'));
    }
  };

  const test = async () => {
    setNote(null);
    try {
      const { id } = await intentsApi.test();
      setTestId(id);
      void useIntentsStore.getState().refresh();
    } catch {
      setNote(setup.pc_paired ? t('offlineSetup.requestFailed') : t('offlineSetup.pairFirst'));
    }
  };

  return (
    <div data-testid="offline-setup" className="pb-6">
      <Note>
        {t('offlineSetup.intro')}
      </Note>

      {!setup.sms_build ? (
        <p data-testid="offline-store" className="mx-4 mt-4 px-4 py-3 rounded-card bg-raised text-callout text-ink-2">
          {t('offlineSetup.smsStoreOnly')}
        </p>
      ) : !setup.permitted ? (
        <section data-testid="offline-permit">
          <SectionTitle>{t('offlineSetup.smsHeading')}</SectionTitle>
          <Note>
            {t('offlineSetup.smsPermissions')}
          </Note>
          <button type="button" onClick={() => void intentsApi.permit().catch(() => setNote(t('offlineSetup.smsUnsupported')))}
            className="mx-4 mt-3 w-[calc(100%-2rem)] h-12 rounded-control bg-accent text-on-accent text-headline">
            {t('offlineSetup.smsAllow')}
          </button>
        </section>
      ) : (
        <>
          <SectionTitle>{t('offlineSetup.smsToGateway')}</SectionTitle>
          <div className="mx-4 rounded-card bg-raised overflow-hidden">
            <div className="py-2 px-4 flex items-center gap-2">
              <MessageSquareText {...ICO} aria-hidden="true" />
              <input aria-label={t('offlineSetup.gatewayLabel')} inputMode="tel" value={number} onChange={(e) => setNumber(e.target.value)}
                placeholder="+380…" className="flex-1 min-w-0 h-11 bg-transparent text-body text-ink placeholder:text-ink-3 focus:outline-none" />
              <button type="button" onClick={() => void save({ gateway_number: number })} disabled={number === setup.gateway_number}
                className="h-11 min-h-0 px-4 rounded-full bg-overlay text-callout font-semibold text-ink disabled:opacity-50">
                {t('offlineSetup.save')}
              </button>
            </div>
            <Divider />
            <Row title={t('offlineSetup.smsForQuestionsTitle')} meta={t('offlineSetup.smsForQuestionsSub')}>
              <Switch on={setup.personal} label={t('offlineSetup.smsForQuestionsLabel')} onToggle={() => void save({ personal: !setup.personal })} />
            </Row>
            <Divider />
            <Row title={t('offlineSetup.quietTitle')} meta={t('offlineSetup.quietSub')}>
              <Switch on={setup.quiet} label={t('offlineSetup.quietLabel')} onToggle={() => void save({ quiet: !setup.quiet })} />
            </Row>
          </div>

          <SectionTitle>{t('offlineSetup.gatewayPhone')}</SectionTitle>
          <div className="mx-4 rounded-card bg-raised overflow-hidden">
            <Row title={t('offlineSetup.gatewayTitle')} meta={t('offlineSetup.gatewaySub')}>
              <Switch on={setup.home_gateway} label={t('offlineSetup.gatewayLabel2')} onToggle={() => void save({ home_gateway: !setup.home_gateway })} />
            </Row>
            {setup.home_gateway && (
              <>
                <Divider />
                <div className="py-2 px-4">
                  <span className="block text-caption text-ink-3">{t('offlineSetup.phoneNumbers')}</span>
                  <textarea aria-label={t('offlineSetup.phoneNumbersLabel')} value={allowed} onChange={(e) => setAllowed(e.target.value)} rows={3}
                    className="mt-1 w-full bg-transparent text-body text-ink placeholder:text-ink-3 focus:outline-none resize-none" placeholder="+380…" />
                  <button type="button" onClick={() => void save({ allowed: allowed.split(/[\n,]/).map((x) => x.trim()).filter(Boolean) })}
                    className="mt-1 h-11 min-h-0 px-4 rounded-full bg-overlay text-callout font-semibold text-ink">
                    {t('offlineSetup.saveNumbers')}
                  </button>
                </div>
                {setup.ledger && (
                  <>
                    <Divider />
                    <Row title={t('offlineSetup.today')}
                      meta={t('offlineSetup.ledger', { received: setup.ledger.received, sms_sent: setup.ledger.sms_sent, cap: setup.ledger.cap, refused: setup.ledger.refused })} />
                  </>
                )}
              </>
            )}
          </div>
        </>
      )}

      <SectionTitle>{t('offlineSetup.intentsHeading')}</SectionTitle>
      <IntentsList />

      <SectionTitle>{t('offlineSetup.testHeading')}</SectionTitle>
      <Note>{t('offlineSetup.testIntro')}</Note>
      <button type="button" onClick={() => void test()} data-testid="offline-test"
        className="mx-4 mt-3 w-[calc(100%-2rem)] h-12 rounded-control bg-overlay text-headline text-ink">
        {t('offlineSetup.testButton')}
      </button>
      {testId && (
        <div className="mx-4 mt-2 px-1">
          <IntentTrail intentId={testId} />
        </div>
      )}
      {note && <p className="mx-4 mt-2 px-1 text-callout text-danger">{note}</p>}
    </div>
  );
};
