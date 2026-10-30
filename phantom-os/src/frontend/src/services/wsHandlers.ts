/**
 * wsHandlers — central place to wire WebSocket channels into stores.
 *
 * The existing WS subscribers live inline inside `providers.tsx`
 * (sensor / state); new channels land here instead — `providers.tsx`
 * only needs to call `registerWsHandlers()` once after `wsClient.connect()`.
 *
 * Currently wires:
 *   - `chat` TTS sentences → `ttsPlayer`
 *   - `polis` → the global bell (toasts)
 *
 * The `familiar` channel had a handler here that fed `familiarStore`; no
 * surface on the phone or the PC drew the familiar, so both are gone
 * (the phone guard `familiar-ws-summon` in bundlePatterns.ts pins it).
 */
import { wsClient } from './websocket';
import { registerTtsPlayerWsHandler } from './ttsPlayer';
import { useUIStore } from '../stores/uiStore';
import { tNow } from '../components/chat/tNow';

/**
 * ПОЛІС — global bell. Gates, budget alarms and mission endings must be
 * audible from ANY screen. Plain words, no emoji: the toast rail is part of
 * the PHANTOM look, and pictograms in it belonged to the old glass.
 */
export function registerPolisBellHandler(): () => void {
  return wsClient.on('polis', (msg) => {
    const toast = useUIStore.getState().toast;
    if (msg.type === 'gate_opened') {
      const q =
        ((msg.data as Record<string, any>)?.gate?.question as string) ??
        tNow('polis.gateWaits');
      toast({ kind: 'warn', message: tNow('polis.gate', { q }) });
    } else if (msg.type === 'budget_alert') {
      const p = Math.round(
        (((msg.data as Record<string, any>)?.pressure as number) ?? 0) * 100,
      );
      toast({ kind: 'warn', message: tNow('polis.budget', { p }) });
    } else if (msg.type === 'mission_status') {
      const m = (msg.data as Record<string, any>)?.mission;
      if (m?.status === 'done') {
        toast({ kind: 'success', message: tNow('polis.done', { title: m.title }) });
      } else if (m?.status === 'failed') {
        toast({ kind: 'error', message: tNow('polis.failed', { title: m.title }) });
      }
    }
  });
}

/**
 * Convenience batch wiring — call once after `wsClient.connect()` and
 * keep the returned cleanup for unmount. Future WS-driven features
 * register here.
 */
export function registerWsHandlers(): () => void {
  const unsubs: Array<() => void> = [
    // B1 — incremental sentence TTS playback (chat/tts.sentence + tts.stop).
    registerTtsPlayerWsHandler(),
    // ПОЛІС — глобальний дзвін Ратуші.
    registerPolisBellHandler(),
  ];
  return () => {
    unsubs.forEach((u) => {
      try {
        u();
      } catch {
        /* ignore */
      }
    });
  };
}
