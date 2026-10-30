import React, { Component, useEffect, useMemo } from 'react';
import { Calendar, Info, MapPin, Smartphone, Wallet, X } from 'lucide-react';
import { QueryClient, QueryClientProvider, useQueryClient } from '@tanstack/react-query';
import { useUIStore, type DeskPane } from '../../stores/uiStore';
import { useMessengerStore } from '../../stores/messengerStore';
import { useMapStore } from '../../stores/mapStore';
import { useIntentsStore } from '../../stores/intentsStore';
import { useSettingsStore } from '../../stores/settingsStore';
import { useTranslation } from '../../i18n/useTranslation';
import { useShellSignals } from '../shell/useShellSignals';
import { humanMinutes } from '../shell/presence';
import { OmniMap } from '../map/OmniMap';
import { IntentsList, latestIntents } from './MyIntents';
import { SolanaSheets } from './solana/SolanaSheets';
import { PointsCard } from './PointsCard';
import { DeviceSheet } from './DeviceSheet';
import { GroupProfileSheet } from './GroupProfileSheet';
import { PeerProfileSheet } from './PeerProfileSheet';
import { peerProfileEdits } from './peerProfileEdits';
import { WhereSheet } from './WhereCard';
import { wherePeerOf } from '../../services/peerWhere';
import { askSolana, usePayPeer } from '../../services/solanaActions';
import { myDevices } from '../../services/peopleIdentity';
import { messengerApi, phoneBridge } from '../../services/messengerApi';
import { displayTitle, isSavedNotes } from './chatListFormat';
import { soundFx } from '../../utils/messengerSound';
import type { Chat, ChatMember } from '../../types/messenger';

export interface DeskSidePaneProps {
  activeChat?: Chat;
  infoSubView?: 'profile' | 'where';
  onInfoSubViewChange?: (view: 'profile' | 'where') => void;
  onSelectMember?: (member: ChatMember) => void;
  onSearchInChat?: () => void;
}

type PaneTab = Exclude<DeskPane, null>;
const PANE_TABS: PaneTab[] = ['day', 'map', 'wallet', 'info'];

const fallbackQueryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false,
      refetchOnWindowFocus: false,
    },
  },
});

function EnsureQueryClient({ children }: { children: React.ReactNode }): JSX.Element {
  let hasClient = false;
  try {
    useQueryClient();
    hasClient = true;
  } catch {
    hasClient = false;
  }
  if (hasClient) return <>{children}</>;
  return <QueryClientProvider client={fallbackQueryClient}>{children}</QueryClientProvider>;
}

class MapErrorBoundary extends Component<
  { fallbackLabel: string; children: React.ReactNode },
  { failed: boolean }
> {
  state = { failed: false };
  static getDerivedStateFromError(): { failed: boolean } {
    return { failed: true };
  }
  render(): React.ReactNode {
    if (this.state.failed) {
      return (
        <div
          data-testid="desk-pane-map-failed"
          className="relative w-full h-full flex flex-col items-center justify-center p-6 text-center text-ink-3"
        >
          <MapPin className="w-6 h-6 mb-2 text-ink-3" strokeWidth={1.75} aria-hidden />
          <span className="text-subhead text-ink-2">{this.props.fallbackLabel}</span>
        </div>
      );
    }
    return this.props.children;
  }
}

const DayPaneContent: React.FC = () => {
  const { t } = useTranslation();
  const signals = useShellSignals();
  const chats = useMessengerStore((s) => s.chats);
  const intentRows = useIntentsStore((s) => s.rows);
  const devices = useMemo(() => myDevices(chats).filter((c) => !c.archived), [chats]);
  const intents = useMemo(() => latestIntents(intentRows), [intentRows]);

  useEffect(() => {
    void useIntentsStore.getState().refresh();
  }, []);

  const isEmpty =
    !(signals.agentBusy && signals.agentGoal) &&
    signals.waiting.length === 0 &&
    !signals.nextEvent &&
    devices.length === 0 &&
    !signals.noticed &&
    intents.length === 0;

  if (isEmpty) {
    return (
      <div
        data-testid="desk-pane-day-empty"
        className="flex-1 flex flex-col items-center justify-center p-6 text-center"
      >
        <span className="mb-3 w-12 h-12 rounded-control bg-raised text-ink-2 flex items-center justify-center" aria-hidden>
          <Calendar className="w-6 h-6" strokeWidth={1.75} />
        </span>
        <p className="text-subhead text-ink-2">{t('deskPane.emptyDay')}</p>
      </div>
    );
  }

  return (
    <div data-testid="desk-pane-day" className="flex-1 min-h-0 overflow-y-auto p-4 space-y-3">
      {signals.agentBusy && signals.agentGoal && (
        <section className="rounded-card bg-raised p-3.5">
          <p className="text-callout text-ink">{t('deskHome.doing', { goal: signals.agentGoal })}</p>
        </section>
      )}

      {signals.waiting.length > 0 && (
        <section className="rounded-card bg-raised p-3.5 space-y-2" data-testid="desk-pane-waiting">
          <h3 className="text-footnote font-semibold text-ink-3">{t('deskHome.waiting')}</h3>
          <ul className="space-y-1">
            {signals.waiting.map((chat) => (
              <li key={chat.id}>
                <button
                  type="button"
                  onClick={() => {
                    soundFx.playTap();
                    useMessengerStore.getState().setActiveChat(chat.id);
                  }}
                  className="w-full min-h-11 px-2.5 py-1.5 rounded-control flex items-center justify-between gap-2 text-left hover:bg-surface active:bg-surface focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line"
                >
                  <span className="truncate text-subhead font-medium text-ink">{displayTitle(chat.title)}</span>
                  {chat.unreadCount > 0 && (
                    <span className="shrink-0 h-5 min-w-5 px-1.5 rounded-full bg-accent text-on-accent text-caption font-semibold tnum flex items-center justify-center">
                      {chat.unreadCount}
                    </span>
                  )}
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      {signals.nextEvent && (
        <section className="rounded-card bg-raised p-3.5 space-y-1" data-testid="desk-pane-next-event">
          <h3 className="text-footnote font-semibold text-ink-3">{t('deskHome.next')}</h3>
          <p className="text-subhead font-medium text-ink">{signals.nextEvent.title}</p>
          <p className="text-caption text-ink-2">
            {signals.nextEvent.inMinutes === 0
              ? t('deskHome.now')
              : t('deskHome.inTime', { time: humanMinutes(signals.nextEvent.inMinutes) })}
            {signals.nextEvent.location ? ` · ${signals.nextEvent.location}` : ''}
          </p>
        </section>
      )}

      {devices.length > 0 && (
        <section className="rounded-card bg-raised p-3.5 space-y-2" data-testid="desk-pane-phone-card">
          <h3 className="text-footnote font-semibold text-ink-3">{t('phoneCard.phone')}</h3>
          <ul className="space-y-1">
            {devices.map((dev) => (
              <li key={dev.id} className="min-h-10 flex items-center gap-2.5">
                <Smartphone className="w-4 h-4 text-ink-2 shrink-0" strokeWidth={1.75} aria-hidden />
                <span className="min-w-0 flex-1 truncate text-subhead text-ink">{displayTitle(dev.title)}</span>
                <span className="text-caption text-ink-3">
                  {dev.isOnline ? t('phoneCard.online') : t('phoneCard.offline')}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}

      {signals.noticed && (
        <section className="rounded-card bg-raised p-3.5 space-y-1" data-testid="desk-pane-noticed">
          <h3 className="text-footnote font-semibold text-ink-3">{t('deskHome.noticed')}</h3>
          <p className="text-callout text-ink break-words">{signals.noticed.text}</p>
        </section>
      )}

      <IntentsList />
    </div>
  );
};

const MapPaneContent: React.FC<{ activeChat?: Chat }> = ({ activeChat }) => {
  const { t } = useTranslation();
  const activeLocationData = useMessengerStore((s) => s.activeLocationData);
  const zoomRaw = useSettingsStore((s) => s.values.ui_map_default_zoom);
  const initialZoom = typeof zoomRaw === 'number' && Number.isFinite(zoomRaw) ? zoomRaw : undefined;

  const coords = activeLocationData?.coords;

  const initialCenter = useMemo<[number, number] | undefined>(
    () => (coords ? [coords.lng, coords.lat] : undefined),
    [coords],
  );

  useEffect(() => {
    if (coords) {
      useMapStore.getState().setCenter([coords.lng, coords.lat]);
      useMapStore.getState().setZoom(16);
      return;
    }
    if (activeChat?.peerNodeId) {
      messengerApi.focusPeerOnMap(activeChat.peerNodeId).catch(() => {});
    }
  }, [coords, activeChat?.peerNodeId]);

  return (
    <div data-testid="desk-pane-map" className="flex-1 min-h-0 flex flex-col overflow-hidden">
      {activeLocationData && (activeLocationData.name || activeLocationData.address) && (
        <div className="shrink-0 px-4 py-2.5 border-b border-line bg-surface flex items-center justify-between gap-2">
          <div className="min-w-0 flex-1">
            {activeLocationData.name && (
              <p className="truncate text-subhead font-medium text-ink">{activeLocationData.name}</p>
            )}
            {activeLocationData.address && (
              <p className="truncate text-caption text-ink-2">{activeLocationData.address}</p>
            )}
          </div>
        </div>
      )}
      <div className="flex-1 min-h-0 relative">
        <MapErrorBoundary fallbackLabel={t('deskPane.map')}>
          <OmniMap initialCenter={initialCenter} initialZoom={initialZoom} />
        </MapErrorBoundary>
      </div>
    </div>
  );
};

const WalletPaneContent: React.FC<{ activeChat?: Chat }> = ({ activeChat }) => {
  const { t } = useTranslation();
  const peer = wherePeerOf(activeChat);
  const { pay } = usePayPeer(peer);

  if (!peer || !pay) {
    return (
      <>
        <div
          data-testid="desk-pane-wallet-empty"
          className="flex-1 flex flex-col items-center justify-center p-6 text-center"
        >
          <Wallet className="mb-2 w-7 h-7 text-ink-3" strokeWidth={1.75} aria-hidden />
          <p className="text-callout text-ink-3">{activeChat || !phoneBridge() ? t('deskPane.noPay') : t('deskPane.emptyWallet')}</p>
        </div>
        <PointsCard className="mx-4 mb-4" />
        <SolanaSheets />
      </>
    );
  }

  return (
    <div data-testid="desk-pane-wallet" className="flex-1 min-h-0 overflow-y-auto p-4 space-y-4">
      <div className="rounded-[16px] bg-raised p-4 space-y-3">
        <div className="flex items-center justify-between gap-2">
          <span className="truncate text-headline text-ink">{displayTitle(peer.name)}</span>
          <span data-testid="desk-pane-wallet-cluster" className="shrink-0 px-2 py-1 rounded-chip border border-line-strong text-footnote font-semibold text-ink-2">
            {pay.cluster === 'mainnet' ? t('deskPane.mainnet') : t('deskPane.devnet')}
          </span>
        </div>
        <button
          type="button"
          data-testid="desk-pane-wallet-pay"
          onClick={() => {
            soundFx.playTap();
            askSolana({ kind: 'pay', peer: { id: peer.id, name: displayTitle(peer.name) }, cluster: pay.cluster });
          }}
          className="w-full h-12 px-4 rounded-[16px] bg-accent text-on-accent text-callout font-semibold flex items-center justify-center gap-2 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line"
        >
          <Wallet className="w-4 h-4" strokeWidth={1.75} aria-hidden />
          <span>{t('payMoneySheet.send')}</span>
        </button>
      </div>
      <PointsCard />
      <SolanaSheets />
    </div>
  );
};

const InfoPaneContent: React.FC<{
  activeChat?: Chat;
  infoSubView?: 'profile' | 'where';
  onInfoSubViewChange?: (view: 'profile' | 'where') => void;
  onSelectMember?: (member: ChatMember) => void;
  onSearchInChat?: () => void;
  onClosePane: () => void;
}> = ({ activeChat, infoSubView = 'profile', onInfoSubViewChange, onSelectMember, onSearchInChat, onClosePane }) => {
  const { t } = useTranslation();

  if (!activeChat) {
    return (
      <div
        data-testid="desk-pane-info-empty"
        className="flex-1 flex flex-col items-center justify-center p-6 text-center"
      >
        <span className="mb-3 w-12 h-12 rounded-control bg-raised text-ink-2 flex items-center justify-center" aria-hidden>
          <Info className="w-6 h-6" strokeWidth={1.75} />
        </span>
        <p className="text-subhead text-ink-2">{t('deskPane.emptyInfo')}</p>
      </div>
    );
  }

  const wherePeer = wherePeerOf(activeChat);
  const withPeer =
    Boolean(activeChat.peerNodeId) &&
    activeChat.type !== 'group' &&
    activeChat.type !== 'channel' &&
    !isSavedNotes(activeChat);
  const device = withPeer && Boolean(activeChat.mine);
  const person = withPeer && !device;

  // Листи мають власний стан (перейменування, підтвердження, «Керування»): на іншу розмову він не переходить.
  return (
    <div key={activeChat.id} data-testid="desk-pane-info" className="flex-1 min-h-0 flex flex-col overflow-hidden">
      {infoSubView === 'where' && wherePeer ? (
        <WhereSheet inline peer={wherePeer} onClose={() => onInfoSubViewChange?.('profile')} />
      ) : device ? (
        <DeviceSheet inline title={displayTitle(activeChat.title)} peerNodeId={activeChat.peerNodeId} onClose={onClosePane} />
      ) : person ? (
        <PeerProfileSheet
          inline
          peerNodeId={activeChat.peerNodeId}
          name={displayTitle(activeChat.title)}
          avatar={activeChat.avatar}
          chatId={activeChat.id}
          onClose={onClosePane}
          onSearch={onSearchInChat}
          {...peerProfileEdits(activeChat.id)}
        />
      ) : (
        <GroupProfileSheet
          inline
          chat={activeChat}
          onClose={onClosePane}
          onSelectMember={(m) => onSelectMember?.(m)}
          onTogglePinChat={(chatId) => useMessengerStore.getState().togglePinChat(chatId)}
          onSearch={onSearchInChat}
        />
      )}
    </div>
  );
};

export const DeskSidePane: React.FC<DeskSidePaneProps> = ({
  activeChat,
  infoSubView = 'profile',
  onInfoSubViewChange,
  onSelectMember,
  onSearchInChat,
}) => {
  const { t } = useTranslation();
  const deskPane = useUIStore((s) => s.deskPane);
  const setDeskPane = useUIStore((s) => s.setDeskPane);

  if (!deskPane) return null;

  const handleClose = () => {
    setDeskPane(null);
    useMessengerStore.getState().closeLocationSheet();
  };

  return (
    <EnsureQueryClient>
      <aside
        data-testid="desk-side-pane"
        data-pane={deskPane}
        aria-label={t(`deskPane.${deskPane}`)}
        className="w-[380px] shrink-0 border-l border-line bg-surface flex flex-col h-full overflow-hidden"
      >
        <header className="h-14 shrink-0 px-3 border-b border-line flex items-center gap-1 bg-surface">
          <div role="tablist" aria-label={t('deskPane.tabsAria')} className="flex-1 min-w-0 grid grid-cols-4 gap-1">
            {PANE_TABS.map((tab) => {
              const active = deskPane === tab;
              return (
                <button
                  key={tab}
                  type="button"
                  role="tab"
                  data-testid={`desk-pane-tab-${tab}`}
                  aria-selected={active}
                  onClick={() => {
                    soundFx.playTap();
                    setDeskPane(tab);
                  }}
                  className={`h-11 px-1 rounded-control text-footnote font-semibold truncate transition-colors duration-quick focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line ${
                    active ? 'bg-raised text-ink' : 'text-ink-2 hover:text-ink hover:bg-raised/60'
                  }`}
                >
                  {t(`deskPane.${tab}`)}
                </button>
              );
            })}
          </div>
          <button
            type="button"
            data-testid="desk-pane-close"
            onClick={() => {
              soundFx.playTap();
              handleClose();
            }}
            aria-label={t('deskPane.close')}
            title={t('deskPane.close')}
            className="w-11 h-11 shrink-0 rounded-full flex items-center justify-center text-ink-2 hover:text-ink hover:bg-raised transition-colors duration-quick focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line"
          >
            <X className="w-4 h-4" strokeWidth={1.75} aria-hidden />
          </button>
        </header>

        {deskPane === 'day' && <DayPaneContent />}
        {deskPane === 'map' && <MapPaneContent activeChat={activeChat} />}
        {deskPane === 'wallet' && <WalletPaneContent activeChat={activeChat} />}
        {deskPane === 'info' && (
          <InfoPaneContent
            activeChat={activeChat}
            infoSubView={infoSubView}
            onInfoSubViewChange={onInfoSubViewChange}
            onSelectMember={onSelectMember}
            onSearchInChat={onSearchInChat}
            onClosePane={handleClose}
          />
        )}
      </aside>
    </EnsureQueryClient>
  );
};

export default DeskSidePane;
