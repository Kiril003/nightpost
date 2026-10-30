import { create } from 'zustand';
import type { CircleInfo } from '../services/circlesApi';

export const CIRCLE_RIGHTS_KEY = 'np.circleRights.v1';

export const RIGHT_CAPS = ['status', 'place', 'moments', 'phone'] as const;
export type RightCap = (typeof RIGHT_CAPS)[number];
export type RightValue = 'yes' | 'picked' | 'no';
export type Rights = Record<RightCap, RightValue>;
export type RightsOverrides = Record<string, Partial<Rights>>;

/** Права, що справді діють: решта ключів лишається в сховищі, але не показується. */
export const ENFORCED_CAPS: readonly RightCap[] = ['place', 'moments'];

const RANK: Record<RightValue, number> = { no: 0, picked: 1, yes: 2 };

export const rightOptions = (cap: RightCap): readonly RightValue[] =>
  cap === 'moments' ? ['yes', 'picked', 'no'] : ['yes', 'no'];

const isValue = (v: unknown): v is RightValue => v === 'yes' || v === 'picked' || v === 'no';

const readOverrides = (): RightsOverrides => {
  try {
    if (typeof localStorage === 'undefined') return {};
    const parsed: unknown = JSON.parse(localStorage.getItem(CIRCLE_RIGHTS_KEY) ?? '{}');
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
    const out: RightsOverrides = {};
    for (const [id, entry] of Object.entries(parsed)) {
      if (!entry || typeof entry !== 'object') continue;
      const row: Partial<Rights> = {};
      for (const cap of RIGHT_CAPS) {
        const v = (entry as Record<string, unknown>)[cap];
        if (isValue(v) && rightOptions(cap).includes(v)) row[cap] = v;
      }
      if (Object.keys(row).length > 0) out[id] = row;
    }
    return out;
  } catch {
    return {};
  }
};

const writeOverrides = (o: RightsOverrides): void => {
  try {
    if (typeof localStorage !== 'undefined') localStorage.setItem(CIRCLE_RIGHTS_KEY, JSON.stringify(o));
  } catch {
    /* без сховища вибір живе в памʼяті */
  }
};

const STATUS_DEFAULT = ['personal', 'close', 'friends'];
const PHONE_DEFAULT = ['personal', 'close'];

export const defaultRights = (c: Pick<CircleInfo, 'id' | 'locationDefault' | 'momentsDefault'>): Rights => ({
  status: STATUS_DEFAULT.includes(c.id) ? 'yes' : 'no',
  place: c.locationDefault ? 'yes' : 'no',
  moments: c.momentsDefault ? 'yes' : 'no',
  phone: PHONE_DEFAULT.includes(c.id) ? 'yes' : 'no',
});

export const rightsOf = (c: CircleInfo, overrides: RightsOverrides): Rights => ({
  ...defaultRights(c),
  ...overrides[c.id],
});

/** Людина в кількох колах бачить найширше з них. */
export const personRights = (ids: readonly string[], circles: readonly CircleInfo[], overrides: RightsOverrides): Rights => {
  const out: Rights = { status: 'no', place: 'no', moments: 'no', phone: 'no' };
  for (const c of circles) {
    if (!ids.includes(c.id)) continue;
    const r = rightsOf(c, overrides);
    for (const cap of RIGHT_CAPS) if (RANK[r[cap]] > RANK[out[cap]]) out[cap] = r[cap];
  }
  return out;
};

export const lostCaps = (from: Rights, to: Rights): RightCap[] =>
  ENFORCED_CAPS.filter((cap) => RANK[to[cap]] < RANK[from[cap]]);

/** Кола з урахуванням власних прав: так їх читає порівняння «що зникне при переході». */
export const withRights = (circles: readonly CircleInfo[], overrides: RightsOverrides): CircleInfo[] =>
  circles.map((c) => {
    const r = rightsOf(c, overrides);
    return { ...c, locationDefault: r.place === 'yes', momentsDefault: r.moments === 'yes' };
  });

interface CircleRightsState {
  overrides: RightsOverrides;
  set: (circleId: string, cap: RightCap, value: RightValue) => void;
}

export const useCircleRights = create<CircleRightsState>((set, get) => ({
  overrides: readOverrides(),
  set: (circleId, cap, value) => {
    if (!circleId || !rightOptions(cap).includes(value)) return;
    const next = { ...get().overrides, [circleId]: { ...get().overrides[circleId], [cap]: value } };
    writeOverrides(next);
    set({ overrides: next });
  },
}));
