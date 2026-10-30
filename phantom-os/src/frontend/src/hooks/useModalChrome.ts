import { useEffect, useRef } from 'react';
import { messengerApi } from '../services/messengerApi';
import { usePhoneLayerStore } from '../stores/phoneLayerStore';

export const MODAL_CLOSE_SETTLE_MS = 120;

const NOT_MODAL = new Set(['list:search', 'list:select']);

export function useModalChrome(extraOpen: boolean): void {
  const layerOpen = usePhoneLayerStore((s) => Object.keys(s.layers).some((k) => !NOT_MODAL.has(k)));
  const open = layerOpen || extraOpen;
  const sent = useRef(false);

  useEffect(() => {
    const tell = (hidden: boolean) => {
      sent.current = hidden;
      messengerApi.chromeHidden(hidden).catch(() => undefined);
    };
    if (open) {
      if (!sent.current) tell(true);
      return undefined;
    }
    if (!sent.current) return undefined;
    const timer = window.setTimeout(() => tell(false), MODAL_CLOSE_SETTLE_MS);
    return () => window.clearTimeout(timer);
  }, [open]);
}
