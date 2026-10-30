import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../services/api';
import { createCircle, deleteCircle, joinCircle, leaveCircle, listCircles, listCirclesWithMeta } from '../services/circlesApi';

const serve = (status: number, body: unknown) =>
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response(JSON.stringify(body), { status })),
  );

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('circlesApi', () => {
  it('maps wire rows and drops malformed rows', async () => {
    serve(200, {
      circles: [
        { id: 'close', name: null, builtin: true, count: 3, location_default: true, moments_default: true },
        { id: 'u:run', name: 'Run club', builtin: false, count: 1, location_default: false, moments_default: true },
        { id: 42 },
        null,
        { name: 'missing id' },
      ],
      max_custom: 8,
    });

    const rows = await listCircles();
    expect(rows).toEqual([
      { id: 'close', name: null, builtin: true, count: 3, locationDefault: true, momentsDefault: true },
      { id: 'u:run', name: 'Run club', builtin: false, count: 1, locationDefault: false, momentsDefault: true },
    ]);

    const meta = await listCirclesWithMeta();
    expect(meta.maxCustom).toBe(8);
  });

  it('createCircle surfaces 409 as ApiError', async () => {
    serve(409, { code: 'too_many_circles', detail: 'max custom circles reached' });

    await expect(createCircle('Ninth')).rejects.toMatchObject({
      status: 409,
      code: 'too_many_circles',
    });
    await expect(createCircle('Ninth')).rejects.toBeInstanceOf(ApiError);
  });

  it('deleteCircle, joinCircle and leaveCircle map responses', async () => {
    serve(200, {});
    await expect(deleteCircle('u:run')).resolves.toBe(true);

    serve(200, { circles: ['close', 'u:run'] });
    await expect(joinCircle('u:run', 'chat_1')).resolves.toEqual(['close', 'u:run']);

    serve(200, { circles: ['close'] });
    await expect(leaveCircle('u:run', 'chat_1')).resolves.toEqual(['close']);
  });
});
