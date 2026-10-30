import type { Claim } from '../placesApi';
import type { Venue } from '../venuesApi';

export type ClaimStage = 'start' | 'waiting' | 'verified' | 'declined';
export type StepState = 'done' | 'current' | 'upcoming' | 'declined';

export type ClaimCopyKey =
  | 'osm_not_found'
  | 'osm_unnamed'
  | 'osm_not_a_place'
  | 'too_far'
  | 'needs_address'
  | 'venue_has_claim'
  | 'already_claimed'
  | 'osm_unavailable'
  | 'not_pending'
  | 'generic';

const KNOWN_REFUSALS = new Set<ClaimCopyKey>([
  'osm_not_found',
  'osm_unnamed',
  'osm_not_a_place',
  'too_far',
  'needs_address',
  'venue_has_claim',
  'already_claimed',
  'osm_unavailable',
  'not_pending',
]);

export function claimStage(claims: readonly Claim[]): ClaimStage {
  const active = claims.filter((c) => c.status !== 'withdrawn');
  if (active.some((c) => c.status === 'approved')) return 'verified';
  if (active.some((c) => c.status === 'pending')) return 'waiting';
  const rejected = active.filter((c) => c.status === 'rejected').sort((a, b) => b.created_ms - a.created_ms);
  if (rejected.length > 0) return 'declined';
  return 'start';
}

export function stepStates(stage: ClaimStage): [StepState, StepState, StepState] {
  switch (stage) {
    case 'start':
      return ['current', 'upcoming', 'upcoming'];
    case 'waiting':
      return ['done', 'current', 'upcoming'];
    case 'verified':
      return ['done', 'done', 'done'];
    case 'declined':
      return ['done', 'done', 'declined'];
  }
}

export function searchOrigin(venue: Venue | null | undefined): { lat: number; lon: number } | null {
  if (!venue) return null;
  const addrBlock = venue.page?.blocks.find((b) => b.type === 'address');
  if (addrBlock && addrBlock.type === 'address' && Number.isFinite(addrBlock.lat) && Number.isFinite(addrBlock.lon)) {
    return { lat: addrBlock.lat, lon: addrBlock.lon };
  }
  if (venue.location && Number.isFinite(venue.location.lat) && Number.isFinite(venue.location.lon)) {
    return { lat: venue.location.lat, lon: venue.location.lon };
  }
  return null;
}

export function refusalKey(err: unknown): ClaimCopyKey {
  if (err && typeof err === 'object' && 'refused' in err) {
    const r = (err as { refused?: unknown }).refused;
    if (typeof r === 'string' && KNOWN_REFUSALS.has(r as ClaimCopyKey)) {
      return r as ClaimCopyKey;
    }
  }
  return 'generic';
}
