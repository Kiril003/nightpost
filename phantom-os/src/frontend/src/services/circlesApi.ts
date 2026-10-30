import { ApiError, request } from './api';

export interface CircleInfo {
  id: string;
  name: string | null;
  builtin: boolean;
  count: number;
  locationDefault: boolean;
  momentsDefault: boolean;
}

interface WireCircle {
  id?: unknown;
  name?: unknown;
  builtin?: unknown;
  count?: unknown;
  location_default?: unknown;
  moments_default?: unknown;
}

interface WireCircleList {
  circles?: unknown;
  max_custom?: unknown;
}

const mapCircle = (r: WireCircle): CircleInfo | null => {
  if (!r || typeof r.id !== 'string' || !r.id) return null;
  return {
    id: r.id,
    name: typeof r.name === 'string' ? r.name : null,
    builtin: Boolean(r.builtin ?? !r.id.startsWith('u:')),
    count: typeof r.count === 'number' ? r.count : 0,
    locationDefault: r.location_default === true,
    momentsDefault: r.moments_default === true,
  };
};

export async function listCirclesWithMeta(): Promise<{ circles: CircleInfo[]; maxCustom: number }> {
  try {
    const raw = await request<WireCircleList>('GET', '/messenger/circles');
    const rows = Array.isArray(raw?.circles) ? (raw.circles as WireCircle[]) : [];
    const circles = rows.map(mapCircle).filter((c): c is CircleInfo => c !== null);
    const maxCustom = typeof raw?.max_custom === 'number' ? raw.max_custom : 8;
    return { circles, maxCustom };
  } catch {
    return { circles: [], maxCustom: 8 };
  }
}

export async function listCircles(): Promise<CircleInfo[]> {
  const { circles } = await listCirclesWithMeta();
  return circles;
}

export async function createCircle(name: string): Promise<CircleInfo> {
  const raw = await request<WireCircle>('POST', '/messenger/circles', { name });
  const mapped = mapCircle(raw);
  if (!mapped) {
    throw new ApiError(500, 'invalid_response', 'Invalid circle response');
  }
  return mapped;
}

export async function deleteCircle(id: string): Promise<boolean> {
  try {
    await request('DELETE', `/messenger/circles/${encodeURIComponent(id)}`);
    return true;
  } catch {
    return false;
  }
}

export async function joinCircle(circleId: string, chatId: string): Promise<string[] | null> {
  try {
    const res = await request<{ circles?: unknown }>(
      'POST',
      `/messenger/circles/${encodeURIComponent(circleId)}/members`,
      { peer_id: chatId },
    );
    if (!Array.isArray(res?.circles)) return null;
    return res.circles.filter((c): c is string => typeof c === 'string');
  } catch {
    return null;
  }
}

export async function leaveCircle(circleId: string, chatId: string): Promise<string[] | null> {
  try {
    const res = await request<{ circles?: unknown }>(
      'DELETE',
      `/messenger/circles/${encodeURIComponent(circleId)}/members/${encodeURIComponent(chatId)}`,
    );
    if (!Array.isArray(res?.circles)) return null;
    return res.circles.filter((c): c is string => typeof c === 'string');
  } catch {
    return null;
  }
}

export { ApiError };
