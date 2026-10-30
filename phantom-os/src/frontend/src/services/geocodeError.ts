import type { MessageKey } from '../i18n';

const REASON_KEYS: Record<string, MessageKey> = {
  geocoder_not_configured: 'map.geocode.notConfigured',
  geocoder_unavailable: 'map.geocode.unavailable',
};

export function geocodeReason(err: unknown): string | null {
  const reason = (err as { reason?: unknown } | null)?.reason;
  return typeof reason === 'string' && reason in REASON_KEYS ? reason : null;
}

export function geocodeErrorKey(err: unknown): MessageKey {
  const reason = geocodeReason(err);
  return reason ? REASON_KEYS[reason] : 'map.geocode.failed';
}
