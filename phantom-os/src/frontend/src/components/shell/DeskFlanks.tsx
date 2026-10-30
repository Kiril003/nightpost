import { useSyncExternalStore } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Smartphone, Plus } from 'lucide-react';
import { callEngine } from '../../services/callEngine';
import { symbioteApi } from '../../services/api';
import { useSystemStore } from '../../stores/systemStore';
import { useUIStore } from '../../stores/uiStore';
import { useMessengerStore } from '../../stores/messengerStore';
import { humanMinutes } from './presence';
import { tNow } from '../chat/tNow';
import type { ShellSignals } from './DeskShell';

export type Now = { kind: 'call' | 'run' | 'event'; title: string; detail: string };

export function nowOf(i: {
  call: { state: string; peer: { displayName: string } | null };
  agentBusy: boolean;
  agentGoal: string | null;
  nextEvent: { title: string; inMinutes: number } | null;
}): Now | null {
  if (i.call.state === 'active') {
    return {
      kind: 'call',
      title: i.call.peer?.displayName ?? '',
      detail: tNow('pcBar.call'),
    };
  }
  if (i.agentBusy && i.agentGoal) {
    return {
      kind: 'run',
      title: i.agentGoal,
      detail: tNow('pcBar.running'),
    };
  }
  if (i.nextEvent && i.nextEvent.inMinutes < 60) {
    return {
      kind: 'event',
      title: i.nextEvent.title,
      detail: tNow('deskHome.inTime', { time: humanMinutes(i.nextEvent.inMinutes) }),
    };
  }
  return null;
}

export function DeskFlanks({ signals }: { signals: ShellSignals }) {
  const navigate = useNavigate();
  const authenticated = useSystemStore((s) => s.authenticated);
  const call = useSyncExternalStore(callEngine.subscribe, callEngine.getSnapshot);

  const symbiote = useQuery({
    queryKey: ['shell', 'symbiote'],
    queryFn: () => symbioteApi.state(),
    enabled: authenticated,
    refetchInterval: 15_000,
    retry: 0,
  });

  const phones = (symbiote.data?.bodies ?? []).filter((b) => b.kind === 'phone');
  const online = phones.some((b) => b.online);
  const now = nowOf({
    call,
    agentBusy: signals.agentBusy,
    agentGoal: signals.agentGoal,
    nextEvent: signals.nextEvent,
  });

  if (!now && phones.length === 0) return null;

  return (
    <>
      {now && (
        <button
          type="button"
          data-testid="desk-now"
          data-kind={now.kind}
          onClick={() => {
            if (now.kind === 'run') {
              navigate('/hq');
            } else if (now.kind === 'event') {
              useUIStore.getState().setDeskPane('day');
              navigate('/');
            }
          }}
          className="absolute left-4 bottom-[22px] z-20 h-10 pl-3 pr-3.5 rounded-full bg-surface text-ink text-[14px] font-medium flex items-center gap-2 max-w-[min(320px,calc(50vw_-_300px))]"
        >
          <span className="w-2 h-2 rounded-full bg-accent shrink-0" aria-hidden />
          <span className="truncate">{now.title}</span>
          <span className="text-ink-2 font-normal shrink-0">· {now.detail}</span>
        </button>
      )}
      {phones.length > 0 && (
        <div data-testid="desk-flank-right" className="absolute right-4 bottom-[22px] z-20 flex items-center gap-2">
          <span
            data-testid="desk-phone"
            data-online={online ? 'true' : 'false'}
            className="h-10 px-3 hidden min-[1280px]:flex items-center gap-2 text-[14px] font-medium text-ink-2"
          >
            <Smartphone className="w-4 h-4" strokeWidth={1.75} aria-hidden />
            <span>{tNow('phoneCard.phone')}</span>
            <span className={`w-2 h-2 rounded-full ${online ? 'bg-success' : 'bg-ink-3'}`} aria-hidden />
            <span>{online ? tNow('pcBar.connected') : tNow('pcBar.notConnected')}</span>
          </span>
          <button
            type="button"
            data-testid="desk-new"
            onClick={() => {
              navigate('/');
              useMessengerStore.getState().setCreateChatModalOpen(true);
            }}
            className="h-10 px-4 rounded-full bg-surface text-ink text-[14px] font-medium flex items-center gap-1.5"
          >
            <Plus className="w-4 h-4" strokeWidth={1.75} aria-hidden />
            <span>{tNow('pcBar.new')}</span>
          </button>
        </div>
      )}
    </>
  );
}
