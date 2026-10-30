/**
 * Настільна «Я» (desk): екран акаунта (`MeScreen`) і аркуші, що з нього
 * відкриваються, — профіль, код, запрошення, памʼять, «Звук і приватність».
 *
 * Сюди веде рядок акаунта вгорі Налаштувань ПК (`DeskSettings`) і «Налаштування»
 * в меню «⋯» шапки розмови; телефон іде в рідний профіль застосунку.
 * Імʼя файла лишилось від часів, коли цей аркуш жив на телефоні; перейменування
 * на DeskMeSheet — окрема справа (його імпортують тести поза цим пакетом).
 *
 * У «Звук і приватність» лишилось лише те, що справді щось робить; решту
 * ВІДНЯТО, а не сховано (у телефонний бандл
 * настільна `SettingsModal` більше не їде — див. `modals/rootModals.ts`):
 *
 *  - «Акцентний відтінок» і «Розмір шрифту» — рухали лише старі змінні
 *    (`--msg-accent`, `--t-*`); екрани форми читають `--f-*` і сталі кеглі,
 *    тож на телефоні перемикачі не міняли жодного пікселя. Кегль на телефоні
 *    веде система (textZoom), кадри знято при 1.3.
 *  - «Нейромережа & API» — помічнику на телефоні відповідає застосунок, не
 *    сторінка; ключ, вписаний тут, лягав у сховище WebView і більше нікуди.
 *  - «Експортувати повний бекап» — обробник був `() => {}`.
 *  - ключ вузла й QR — живуть у «Людина» (нова розмова) і у власному профілі.
 *  - технічні подробиці дзвінка й пояснення про шифр — не рішення людини.
 *
 * Рядка версії тут немає свідомо: «веб-месенджер» з печаткою коміта вже
 * показує застосунок у «Про PHANTOM», і друге місце розійшлося б із першим.
 */
import React, { useEffect, useState } from 'react';
import { FormSheet } from './FormSheet';
import { MeScreen, type MePage } from './MeScreen';
import { SettingsRoot } from './SettingsRoot';
import { InviteCard } from './InviteCard';
import { HandleSheet } from './HandleSheet';
import { PhantomKnows } from './PhantomKnows';
import { PhantomFeed } from './PhantomFeed';
import { UserProfileModal } from './UserProfileModal';
import { useMessengerStore } from '../../stores/messengerStore';
import { OfflineSetup } from './OfflineSetup';
import { useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n';
import { canNavigate, navigateTo } from '../../services/nav';

export interface PhoneSettingsProps {
  isOpen: boolean;
  onClose: () => void;
  isSoundEnabled: boolean;
  onToggleSound: () => void;
  /** Лишено для сумісності: власний профіль тепер відкривається з шапки «Я». */
  onOpenProfile?: () => void;
  // Лишено для сумісності з настільною `SettingsModal` (одна точка монтування).
  onExportAllData: () => void;
  onClearHistory?: () => void;
}

// «Доказові розмови» (прибирання розмов за назвою «тест/proof/…») тут були
// групою аркуша. Засів is_demo більше нічого не сіє, а одну розмову людина
// прибирає з її власного меню (ChatActionsSheet) — друга, «масова» гумка за
// здогадом по назві не потрібна. Віднято (04.10).

const SETTINGS_PAGES = ['prefs', 'look', 'privacy'] as const;
const isSettingsPage = (p: MePage | null): p is (typeof SETTINGS_PAGES)[number] => !!p && (SETTINGS_PAGES as readonly string[]).includes(p);

const PAGE_TITLE: Record<Exclude<MePage, 'profile' | (typeof SETTINGS_PAGES)[number]>, MessageKey> = {
  code: 'phoneSettingsSheet.pageCode',
  invite: 'phoneSettingsSheet.pageInvite',
  knows: 'phoneSettingsSheet.pageKnows',
  handle: 'phoneSettingsSheet.pageHandle',
  offline: 'phoneSettingsSheet.pageOffline',
  feed: 'phoneSettingsSheet.pageFeed',
};

export const PhoneSettingsSheet: React.FC<PhoneSettingsProps> = ({ isOpen, onClose, isSoundEnabled, onToggleSound }) => {
  const { t } = useTranslation();
  const me = useMessengerStore((s) => s.currentUser);
  const updateCurrentUser = useMessengerStore((s) => s.updateCurrentUser);
  const [page, setPage] = useState<MePage | null>(null);

  useEffect(() => {
    if (!isOpen) setPage(null);
  }, [isOpen]);
  /** На телефоні «Я» одне — рідна вкладка; веб-екран тут не малюється. */
  const native = canNavigate();
  useEffect(() => {
    if (isOpen && native && navigateTo('profile')) onClose();
  }, [isOpen, native, onClose]);

  if (!isOpen || native) return null;
  const back = () => setPage(null);

  return (
    <>
      <MeScreen onClose={onClose} onOpen={setPage} handlesSheet />
      {page && page !== 'profile' && !isSettingsPage(page) && (
        <FormSheet
          open
          onClose={back}
          title={t(PAGE_TITLE[page])}
          tall={page !== 'invite' && page !== 'handle'}
          testId={page === 'handle' ? 'me-handle-sheet' : `me-${page}`}
        >
          {page === 'handle' && <HandleSheet onDone={back} />}
          {page === 'knows' && <PhantomKnows />}
          {page === 'offline' && <OfflineSetup />}
          {page === 'feed' && <PhantomFeed />}
          {(page === 'code' || page === 'invite') && (
            <div className="px-4 pb-4">
              <InviteCard qrFirst={page === 'code'} />
            </div>
          )}
        </FormSheet>
      )}
      {isSettingsPage(page) && (
        <SettingsRoot
          onClose={back}
          isSoundEnabled={isSoundEnabled}
          onToggleSound={onToggleSound}
          initial={page === 'prefs' ? 'root' : page}
        />
      )}
      {page === 'profile' && (
        <UserProfileModal isOpen onClose={back} currentUser={me} targetUser={null} onUpdateCurrentUser={updateCurrentUser} />
      )}
    </>
  );
};

export default PhoneSettingsSheet;
