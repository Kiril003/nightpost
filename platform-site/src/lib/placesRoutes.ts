export type OsmType = 'node' | 'way' | 'relation';

export interface RouteSpec {
  method: 'GET' | 'POST' | 'PUT' | 'DELETE';
  path: string;
  body?: Record<string, unknown>;
}

const VENUE_ID_RE = /^[0-9a-f]{16}$/;
const EVENT_ID_RE = /^[0-9a-f]{16}\.[A-Za-z0-9_-]{1,36}$/;
const OSM_TYPES: readonly OsmType[] = ['node', 'way', 'relation'];

export function validVenueId(id: unknown): id is string {
  return typeof id === 'string' && VENUE_ID_RE.test(id);
}

export function validEventId(id: unknown): id is string {
  return typeof id === 'string' && EVENT_ID_RE.test(id);
}

function assertVenueId(id: string): void {
  if (!validVenueId(id)) throw new Error('invalid venue_id');
}

function assertEventId(id: string): void {
  if (!validEventId(id)) throw new Error('invalid event_id');
}

export const routes = {
  search(params: { lat: number; lon: number; q?: string }): RouteSpec {
    if (!Number.isFinite(params.lat) || !Number.isFinite(params.lon)) {
      throw new Error('invalid coordinates');
    }
    const lat = params.lat.toFixed(5);
    const lon = params.lon.toFixed(5);
    const q = encodeURIComponent((params.q ?? '').trim());
    return {
      method: 'GET',
      path: `/claims/osm/search?lat=${lat}&lon=${lon}&q=${q}`,
    };
  },

  claim(params: { venue_id: string; osm_type: OsmType; osm_id: number; evidence?: string }): RouteSpec {
    assertVenueId(params.venue_id);
    if (!OSM_TYPES.includes(params.osm_type)) throw new Error('invalid osm_type');
    if (!Number.isInteger(params.osm_id) || params.osm_id <= 0) throw new Error('invalid osm_id');
    const body: Record<string, unknown> = {
      venue_id: params.venue_id,
      osm_type: params.osm_type,
      osm_id: params.osm_id,
    };
    const ev = params.evidence?.trim();
    if (ev) body.evidence = ev;
    return {
      method: 'POST',
      path: '/claims',
      body,
    };
  },

  mine(): RouteSpec {
    return {
      method: 'GET',
      path: '/claims/mine',
    };
  },

  withdraw(id: string): RouteSpec {
    const trimmed = id.trim();
    if (!trimmed) throw new Error('invalid claim id');
    return {
      method: 'DELETE',
      path: `/claims/${encodeURIComponent(trimmed)}`,
    };
  },

  facts(venueId: string): RouteSpec {
    assertVenueId(venueId);
    return {
      method: 'GET',
      path: `/venues/${venueId}/facts`,
    };
  },

  eventState(eventId: string, state: { capacity: number | null; cancelled: boolean }): RouteSpec {
    assertEventId(eventId);
    if (
      state.capacity !== null &&
      (!Number.isInteger(state.capacity) || state.capacity < 1 || state.capacity > 10000)
    ) {
      throw new Error('invalid capacity');
    }
    return {
      method: 'PUT',
      path: `/events/${eventId}/state`,
      body: {
        capacity: state.capacity,
        cancelled: Boolean(state.cancelled),
      },
    };
  },

  stats(venueId: string): RouteSpec {
    assertVenueId(venueId);
    return {
      method: 'GET',
      path: `/venues/${venueId}/stats`,
    };
  },
};
