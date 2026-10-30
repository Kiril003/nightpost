/**
 * «Додати свій пристрій» — один скан, і все.
 *
 * На ПК — великий QR (`/pair/init`), на телефоні — камера; QR несе все, що
 * треба телефону, і міст спарюється тією самою дорогою, що й рідний екран
 * (`PcPairing`). Після спарювання ПК стає «моїм пристроєм», а не людиною.
 * Цифри з екрана — лише запасний шлях, коли камери немає.
 */
import { useTranslation } from '../../i18n/useTranslation';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Globe, Plus } from 'lucide-react';
import { messengerApi, phoneBridge, type OwnPairState } from '../../services/messengerApi';
import { pairApi, type PairInitResponse } from '../../services/api';
import { useMessengerStore } from '../../stores/messengerStore';
import { soundFx } from '../../utils/messengerSound';
import type { MessageKey } from '../../i18n/messages/uk';
import { FormSheet, PrimaryButton } from './FormSheet';
import { QrScanner } from './QrScanner';

const POLL_MS = 1000;
/** Скільки чекати на спарювання, перш ніж сказати, що не вийшло. */
const GIVE_UP_MS = 60_000;

const FAILED: Record<NonNullable<OwnPairState['reason']>, MessageKey> = {
  bad_input: 'addOwnDevice.failBadInput',
  expired: 'addOwnDevice.failExpired',
  not_found: 'addOwnDevice.failNotFound',
  unreachable: 'addOwnDevice.failUnreachable',
  not_proven: 'addOwnDevice.failNotProven',
};

type WebSignInBridge = NonNullable<ReturnType<typeof phoneBridge>> & { openWebSignIn?: () => boolean };

export function hasWebSignInBridge(): boolean {
  return phoneBridge() !== null;
}

export function openWebSignIn(): boolean {
  const bridge = phoneBridge() as WebSignInBridge | null;
  if (!bridge) return false;
  if (typeof bridge.openWebSignIn === 'function') return Boolean(bridge.openWebSignIn());
  bridge.call('POST', '/messenger/devices/web-signin', '{}');
  return true;
}

export const SignInOnWebRow: React.FC<{ onClick?: () => void }> = ({ onClick }) => {
  const { t } = useTranslation();
  const label = t('addOwnDevice.webSignIn');
  return (
    <button
      type="button"
      data-web-signin
      data-testid="web-signin-row"
      onClick={() => {
        soundFx.playTap();
        openWebSignIn();
        onClick?.();
      }}
      className="w-full min-h-14 px-4 flex items-center gap-3.5 text-left bg-transparent active:bg-raised transition-colors duration-instant ease-standard"
    >
      <span className="w-[52px] h-[52px] shrink-0 rounded-full bg-raised text-ink-2 flex items-center justify-center">
        <Globe className="w-[22px] h-[22px]" strokeWidth={1.75} aria-hidden />
      </span>
      <span className="min-w-0 flex-1 truncate text-headline text-ink">{label}</span>
    </button>
  );
};

export const AddOwnDeviceRow: React.FC<{ onClick: () => void }> = ({ onClick }) => {
  const { t } = useTranslation();
  return (
  <button
    type="button"
    data-add-own-device
    onClick={() => {
      soundFx.playTap();
      onClick();
    }}
    className="w-full min-h-14 px-4 flex items-center gap-3.5 text-left bg-transparent active:bg-raised transition-colors duration-instant ease-standard"
  >
    <span className="w-[52px] h-[52px] shrink-0 rounded-full bg-raised text-ink-2 flex items-center justify-center">
      <Plus className="w-[22px] h-[22px]" strokeWidth={1.75} aria-hidden />
    </span>
    <span className="min-w-0 flex-1 truncate text-headline text-ink">{t('addOwnDevice.addDevice')}</span>
  </button>
  );
};

const Done: React.FC<{ onClose: () => void; phone: boolean }> = ({ onClose, phone }) => {
  const { t } = useTranslation();
  return (
  <div className="px-4 pb-4" data-own-pair-done>
    <p className="pt-2 text-body text-ink">
      {phone ? t('addOwnDevice.doneComputer') : t('addOwnDevice.donePhone')}
    </p>
    <PrimaryButton className="mt-5" onClick={onClose}>
      {t('addOwnDevice.done')}
    </PrimaryButton>
  </div>
  );
};

/** Телефон: камера на QR з екрана ПК. */
const PhonePairing: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const { t } = useTranslation();
  const [stage, setStage] = useState<'scan' | 'digits' | 'working' | 'done' | 'failed'>('scan');
  const [noCamera, setNoCamera] = useState(false);
  const [digits, setDigits] = useState('');
  const [note, setNote] = useState<string | null>(null);
  const alive = useRef(true);
  useEffect(
    () => () => {
      alive.current = false;
    },
    [],
  );

  const submit = useCallback(async (text: string) => {
    setStage('working');
    setNote(null);
    try {
      let st = await messengerApi.pairOwnDevice(text);
      const until = Date.now() + GIVE_UP_MS;
      while (alive.current && st.state === 'working' && Date.now() < until) {
        await new Promise((r) => setTimeout(r, POLL_MS));
        st = await messengerApi.ownPairState();
      }
      if (!alive.current) return;
      if (st.state === 'done') {
        soundFx.playChime();
        await useMessengerStore.getState().refreshConversations();
        if (alive.current) setStage('done');
        return;
      }
      setNote(st.reason ? t(FAILED[st.reason]) : t('addOwnDevice.tryFailed'));
      setStage('failed');
    } catch (err) {
      if (!alive.current) return;
      const code = (err as { code?: unknown } | null)?.code;
      setNote(code === 'bad_input' ? t(FAILED.bad_input) : t('addOwnDevice.tryFailed'));
      setStage('failed');
    }
  }, [t]);

  if (stage === 'done') return <Done onClose={onClose} phone />;
  if (stage === 'working') {
    return (
      <p className="px-4 pt-2 pb-6 text-body text-ink-2" role="status">
        {t('addOwnDevice.connecting')}
      </p>
    );
  }
  if (stage === 'failed') {
    return (
      <div className="px-4 pb-4">
        <p className="pt-2 text-body text-ink" role="status">
          {note}
        </p>
        <PrimaryButton className="mt-5" onClick={() => setStage(noCamera ? 'digits' : 'scan')}>
          {t('addOwnDevice.again')}
        </PrimaryButton>
      </div>
    );
  }
  if (stage === 'digits') {
    const clean = digits.replace(/\D/g, '');
    return (
      <div className="px-4 pb-4">
        <input
          inputMode="numeric"
          autoComplete="off"
          value={digits}
          onChange={(e) => setDigits(e.target.value)}
          aria-label={t('addOwnDevice.digitsLabel')}
          placeholder={t('addOwnDevice.digitsLabel')}
          className="w-full h-12 px-4 rounded-full bg-raised text-center text-headline text-ink placeholder:text-ink-3 placeholder:tracking-normal placeholder:font-normal focus:outline focus:outline-2 focus:outline-accent-line"
        />
        <div data-pinned-action className="sticky bottom-0 bg-canvas pt-4">
          <PrimaryButton disabled={clean.length < 4} onClick={() => void submit(clean)}>
            {t('addOwnDevice.add')}
          </PrimaryButton>
        </div>
      </div>
    );
  }
  if (noCamera) {
    return (
      <div className="px-4 pb-4">
        <p className="pt-2 text-body text-ink">{t('addOwnDevice.noCamera')}</p>
        <PrimaryButton className="mt-5" onClick={() => setStage('digits')}>
          {t('addOwnDevice.enterDigits')}
        </PrimaryButton>
      </div>
    );
  }
  return (
    <div className="px-4 pb-4 space-y-3">
      <p className="text-callout text-ink-2">{t('addOwnDevice.pointCamera')}</p>
      <QrScanner onFound={(text) => void submit(text)} onCancel={onClose} onUnavailable={() => setNoCamera(true)} />
    </div>
  );
};

/** ПК: великий QR, що сам оновлюється, поки його не відсканували. */
const DeskPairing: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const { t } = useTranslation();
  const [session, setSession] = useState<PairInitResponse | null>(null);
  const [failed, setFailed] = useState(false);
  const [done, setDone] = useState(false);
  const [showDigits, setShowDigits] = useState(false);
  const [round, setRound] = useState(0);

  useEffect(() => {
    let alive = true;
    setFailed(false);
    pairApi
      .init()
      .then((s) => alive && setSession(s))
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, [round]);

  // QR живе хвилину: за кілька секунд до кінця — новий, без жодного лічильника.
  useEffect(() => {
    if (!session || done) return undefined;
    const ttl = Math.max(10, session.expires_in_seconds - 3) * 1000;
    const t = setTimeout(() => setRound((n) => n + 1), ttl);
    return () => clearTimeout(t);
  }, [session, done]);

  useEffect(() => {
    if (!session || done) return undefined;
    let alive = true;
    const t = setInterval(() => {
      pairApi
        .status(session.pair_id)
        .then(async (st) => {
          if (!alive || !st.device_id) return;
          setDone(true);
          soundFx.playChime();
          await useMessengerStore.getState().refreshConversations();
        })
        .catch(() => {});
    }, POLL_MS * 2);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, [session, done]);

  if (done) return <Done onClose={onClose} phone={false} />;
  if (failed) {
    return (
      <div className="px-4 pb-4">
        <p className="pt-2 text-body text-ink" role="status">
          {t('addOwnDevice.qrFailed')}
        </p>
        <PrimaryButton className="mt-5" onClick={() => setRound((n) => n + 1)}>
          {t('addOwnDevice.again')}
        </PrimaryButton>
      </div>
    );
  }
  const pin = session?.pin?.replace(/\D/g, '') ?? '';
  return (
    <div className="px-4 pb-4 flex flex-col items-center text-center">
      {/* QR — завжди на білому: камера читає контраст, а не тему. */}
      <div
        className={`w-full max-w-[320px] aspect-square rounded-control p-3 flex items-center justify-center ${
          session ? '' : 'bg-raised'
        }`}
        style={session ? { background: 'var(--ph-lit-white)' } : undefined}
      >
        {session ? (
          <img src={session.qr_svg_data_url} alt={t('addOwnDevice.qrAlt')} data-own-pair-qr className="w-full h-full" />
        ) : (
          <span className="text-callout text-ink-3" role="status">
            {t('addOwnDevice.qrPreparing')}
          </span>
        )}
      </div>
      <p className="mt-4 text-body text-ink max-w-[340px]">
        {t('addOwnDevice.onPhoneHint')}
      </p>
      {pin &&
        (showDigits ? (
          <p className="mt-3 text-callout text-ink-2" data-own-pair-digits>
            {t('addOwnDevice.typeOnPhone')} <span className=" text-headline text-ink ">{pin}</span>
          </p>
        ) : (
          <button type="button" onClick={() => setShowDigits(true)} className="mt-2 min-h-11 px-3 text-callout text-ink-3">
            {t('addOwnDevice.cameraNotWorking')}
          </button>
        ))}
    </div>
  );
};

export const AddOwnDeviceSheet: React.FC<{ open: boolean; onClose: () => void }> = ({ open, onClose }) => {
  const { t } = useTranslation();
  if (!open) return null;
  const onPhone = phoneBridge() !== null;
  return (
    <FormSheet open onClose={onClose} title={t('addOwnDevice.addDevice')} testId="add-own-device">
      {onPhone ? <PhonePairing onClose={onClose} /> : <DeskPairing onClose={onClose} />}
    </FormSheet>
  );
};

/** Готовий гачок для «Я» / «Мої пристрої»: рядок і його аркуш разом. */
export const AddOwnDevice: React.FC = () => {
  const [open, setOpen] = useState(false);
  const onPhone = hasWebSignInBridge();
  return (
    <>
      <AddOwnDeviceRow onClick={() => setOpen(true)} />
      {onPhone && <SignInOnWebRow />}
      <AddOwnDeviceSheet open={open} onClose={() => setOpen(false)} />
    </>
  );
};

export default AddOwnDevice;
