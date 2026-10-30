import { useEffect, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useSystemStore } from '../../stores/systemStore';
import { useMessengerStore } from '../../stores/messengerStore';
import { isNodeChat } from '../messenger/chatListFormat';
import { presenceOf, type Presence } from './presence';
import { useShellSignals } from './useShellSignals';
import { useDeskPhase } from './useDeskPhase';
import { InDeskContext } from './inDesk';
import { DeskIsland } from './DeskIsland';
import { DeskFlanks } from './DeskFlanks';
import { useTabFade } from '../../feel/motionKit';
import './shell.css';
import { tNow } from '../chat/tNow';

export type ShellSignals = ReturnType<typeof useShellSignals> & { presence: Presence };

export function DeskShell() {
  useDeskPhase();
  const state = useSystemStore((s) => s.state);
  const context = useSystemStore((s) => s.context);
  const signals = useShellSignals();
  const presence = presenceOf({ state, context, agentBusy: signals.agentBusy, waiting: signals.waiting.length });
  const outlet: ShellSignals = { ...signals, presence };
  useCloseChatOffscreen();
  const { pathname } = useLocation();
  const seg = pathname.split('/')[1] ?? '';
  const tabKey = seg === '' ? 'messenger' : seg;
  const tabFade = useTabFade(tabKey);

  return (
    <div data-mood={presence.mood} className="shell-root relative flex flex-col w-screen h-screen overflow-hidden bg-canvas text-f-ink">
      <TopStrip />
      <main className="relative flex-1 min-w-0 min-h-0 flex flex-col pb-[88px]">
        <div className="relative flex-1 min-h-0">
          <InDeskContext.Provider value>
            <div key={tabKey} className={`h-full ${tabFade}`}>
              <Outlet context={outlet} />
            </div>
          </InDeskContext.Provider>
        </div>
      </main>
      <DeskIsland unread={signals.waiting.length} />
      <DeskFlanks signals={outlet} />
    </div>
  );
}

function TopStrip() {
  const navigate = useNavigate();
  const clock = useClock();
  const lost = useLostNode();
  return (
    <header data-testid="desk-top" className="h-10 shrink-0 flex items-center gap-3 px-4">
      <button
        type="button"
        data-testid="desk-brand"
        aria-label="Nightpost"
        onClick={() => {
          const st = useMessengerStore.getState();
          const nodeChat = st.chats.find((c) => isNodeChat(c) && !c.archived) ?? st.chats.find((c) => isNodeChat(c));
          if (nodeChat) st.setActiveChat(nodeChat.id);
          navigate('/');
        }}
        className="flex items-center gap-2 rounded-full px-2 py-1 hover:bg-raised/60"
      >
        <span className="shell-dot w-[9px] h-[9px]" aria-hidden />
        <span className="text-[17px] leading-6 font-semibold text-f-ink">Nightpost</span>
      </button>
      {lost && (
        <span data-testid="desk-lost" className="text-f-body text-f-warn">
          {tNow('pcBar.lost')}
        </span>
      )}
      <div className="flex-1" />
      <span className="text-f-label text-f-ink tabular-nums">{clock}</span>
    </header>
  );
}

function useCloseChatOffscreen(): void {
  const { pathname } = useLocation();
  useEffect(() => {
    const inMessenger = pathname === '/' || pathname.startsWith('/messenger');
    if (!inMessenger && useMessengerStore.getState().activeChatId) {
      useMessengerStore.setState({ activeChatId: '' });
    }
  }, [pathname]);
}

function useClock(): string {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 15_000);
    return () => clearInterval(t);
  }, []);
  return now.toLocaleTimeString('uk-UA', { hour: '2-digit', minute: '2-digit' });
}

function useLostNode(): boolean {
  const connected = useSystemStore((s) => s.wsConnected);
  const [lost, setLost] = useState(false);
  useEffect(() => {
    if (connected) {
      setLost(false);
      return;
    }
    const t = setTimeout(() => setLost(true), 6000);
    return () => clearTimeout(t);
  }, [connected]);
  return lost;
}
