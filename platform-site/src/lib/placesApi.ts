import { BUILDER_SERVER } from './builderServer';
import { readToken, writeToken } from './account-api';
import { VenueRefusal, accountAlive } from './venuesApi';
import type { Token } from './venueBlocks';
import { routes, type OsmType, type RouteSpec } from './placesRoutes';

export { VenueRefusal };
export type { OsmType };

export type ClaimStatus = 'pending' | 'approved' | 'rejected' | 'withdrawn';

export interface Claim {
  id: string;
  venue_id: string;
  osm_type: OsmType;
  osm_id: number;
  osm_name: string;
  status: ClaimStatus;
  created_ms: number;
  decided_ms: number | null;
  reason: string | null;
}

export interface OsmPlace {
  osm_type: OsmType;
  osm_id: number;
  name: string;
  kind: string;
  lat: number;
  lon: number;
  claimed: boolean;
}

export interface PlaceEvent {
  event_id: string;
  venue_id: string;
  title: string;
  starts: string;
  ends?: string;
  note?: string;
  price?: string;
  token?: Token;
  going: number;
  capacity: number | null;
  cancelled: boolean;
}

export interface VenueFacts {
  venue_id: string;
  verified: boolean;
  osm: { type: OsmType; id: number } | null;
  events: PlaceEvent[];
}

export interface VisitDay {
  day: string;
  count: number;
}

export interface StatsEventRow {
  event_id: string;
  title: string;
  starts: string;
  going: number;
  capacity: number | null;
  cancelled: boolean;
}

export interface VenueStats {
  venue_id: string;
  days: number;
  visits: VisitDay[];
  visitors: number;
  repeat: number;
  rating: { mean: number; count: number; sum: number } | null;
  events: StatsEventRow[];
  claim: Claim | null;
}

async function readJson(res: Response): Promise<Record<string, unknown> | null> {
  try {
    const body: unknown = await res.json();
    return body && typeof body === 'object' ? (body as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

const str = (v: unknown): string | null => (typeof v === 'string' ? v : null);

async function call<T>(spec: RouteSpec): Promise<T> {
  const token = readToken();
  let res: Response;
  try {
    res = await fetch(`${BUILDER_SERVER}${spec.path}`, {
      method: spec.method,
      headers: {
        ...(spec.body ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: spec.body ? JSON.stringify(spec.body) : undefined,
    });
  } catch {
    throw new VenueRefusal(0, null, null, null, null);
  }
  if (res.ok) return (await res.json()) as T;
  const body = await readJson(res);
  if (res.status === 401 && token) {
    if (!(await accountAlive(token))) writeToken(null);
    else throw new VenueRefusal(0, null, null, null, null);
  }
  throw new VenueRefusal(
    res.status,
    str(body?.refused) ?? str(body?.reason),
    str(body?.at),
    str(body?.message_uk),
    str(body?.message_en),
  );
}

export async function searchOsmPlaces(params: { lat: number; lon: number; q?: string }): Promise<OsmPlace[]> {
  return call<{ places: OsmPlace[] }>(routes.search(params)).then((r) => r.places);
}

export async function submitClaim(params: {
  venue_id: string;
  osm_type: OsmType;
  osm_id: number;
  evidence?: string;
}): Promise<Claim> {
  return call<{ claim: Claim }>(routes.claim(params)).then((r) => r.claim);
}

export async function myClaims(): Promise<Claim[]> {
  return call<{ claims: Claim[] }>(routes.mine()).then((r) => r.claims);
}

export async function withdrawClaim(id: string): Promise<Claim> {
  return call<{ claim: Claim }>(routes.withdraw(id)).then((r) => r.claim);
}

export async function getVenueFacts(venueId: string): Promise<VenueFacts> {
  return call<VenueFacts>(routes.facts(venueId));
}

export async function setEventState(
  eventId: string,
  state: { capacity: number | null; cancelled: boolean },
): Promise<{ event_id: string; capacity: number | null; cancelled: boolean }> {
  return call<{ event_id: string; capacity: number | null; cancelled: boolean }>(routes.eventState(eventId, state));
}

export async function getVenueStats(venueId: string): Promise<VenueStats> {
  return call<VenueStats>(routes.stats(venueId));
}
