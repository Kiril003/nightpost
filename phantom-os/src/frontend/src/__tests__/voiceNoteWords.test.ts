import { describe, it, expect, vi, beforeEach } from 'vitest';
import * as phonePort from '../services/phonePort';
import { voiceApi } from '../services/voiceApi';

const clip = (bytes: number[], type: string) => {
  const blob = new Blob([new Uint8Array(bytes)], { type });
  Object.defineProperty(blob, 'arrayBuffer', { value: async () => new Uint8Array(bytes).buffer });
  return blob;
};

describe('voiceApi.transcribe on phone', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(phonePort, 'onPhone').mockReturnValue(true);
  });

  it('the phone shell asks the phone for words', async () => {
    const spy = vi.spyOn(phonePort, 'phoneRequest').mockResolvedValue({
      ok: true,
      status: 200,
      data: { text: 'hello' },
    });
    const blob = clip([10, 20, 30], 'audio/webm;codecs=opus');
    const res = await voiceApi.transcribe(blob);
    expect(spy).toHaveBeenCalledTimes(1);
    const [method, path, body] = spy.mock.calls[0];
    expect(method).toBe('POST');
    expect(path).toBe('/messenger/voice/transcribe');
    const parsed = JSON.parse(body!);
    expect(parsed.mime).toBe('audio/webm;codecs=opus');
    expect(parsed.data_b64).toBe(btoa(String.fromCharCode(10, 20, 30)));
    expect(res.text).toBe('hello');
    expect(res.engine).toBe('phone');
  });

  it('no engine resolves to empty words without throwing', async () => {
    vi.spyOn(phonePort, 'phoneRequest').mockResolvedValue({
      ok: false,
      status: 503,
      code: 'no_engine',
      detail: '',
    });
    const blob = clip([1, 2, 3], 'audio/webm');
    const res = await voiceApi.transcribe(blob);
    expect(res.text).toBe('');
    expect(res.engine).toBe('phone');
  });

  it('an undecodable clip throws instead of inventing words', async () => {
    vi.spyOn(phonePort, 'phoneRequest').mockResolvedValue({
      ok: false,
      status: 422,
      code: 'undecodable',
      detail: '',
    });
    const blob = clip([1, 2, 3], 'audio/webm');
    await expect(voiceApi.transcribe(blob)).rejects.toThrow('undecodable');
  });
});
