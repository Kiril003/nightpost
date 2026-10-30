import { describe, expect, it } from 'vitest';
import { tNow } from '../components/chat/tNow';
import { sealCarrier } from '../services/carrier/seal';
import { messageFromNode, type NodeMessage } from '../services/messengerApi';

function makeRow(kind: string, bodyObj: Record<string, unknown>): NodeMessage {
  return {
    id: 'msg_1',
    conversation_id: 'chat_a',
    client_id: 'c_1',
    seq: 1,
    author_id: 'peer_a',
    author_name: 'Alice',
    kind,
    body: JSON.stringify(bodyObj),
    ciphertext: null,
    transport: 'server',
    sent_at: '2026-04-01T10:00:00Z',
    edited_at: null,
    deleted_at: null,
  };
}

describe('carrierParse — messageFromNode Carrier v2 handling', () => {
  it('parses a file row with a valid manifest and empty key_hex into media.carrier without fileData', async () => {
    const { manifest } = await sealCarrier(
      new File([new Uint8Array(128).fill(7)], 'a.bin', { type: 'application/octet-stream' }),
    );
    const row = makeRow('file', {
      name: 'a.bin',
      size: manifest.size,
      mime: 'application/octet-stream',
      sha256: '',
      blob_id: 'tr_1',
      key_hex: '',
      nonce_hex: '',
      carrier: manifest,
    });

    const msg = messageFromNode(row, 'u_me', 'peer_a');

    expect(msg.type).toBe('file');
    expect(msg.media?.carrier).toEqual(manifest);
    expect(msg.media?.blobId).toBe('tr_1');
    expect(msg.fileData).toBeUndefined();
  });

  it('turns a file row with carrier.v = 3 into broken without leaking secret_hex into fileData or media', async () => {
    const { manifest } = await sealCarrier(
      new File([new Uint8Array(128).fill(9)], 'a.bin', { type: 'application/octet-stream' }),
    );
    const row = makeRow('file', {
      name: 'a.bin',
      size: manifest.size,
      mime: 'application/octet-stream',
      sha256: '',
      blob_id: 'tr_1',
      key_hex: '',
      nonce_hex: '',
      carrier: { ...manifest, v: 3 },
    });

    const msg = messageFromNode(row, 'u_me', 'peer_a');

    expect(msg.type).toBe('broken');
    expect(msg.broken).toEqual({
      of: 'file',
      reason: tNow('mediaC6.carrierUnreadable'),
    });
    expect(msg.fileData).toBeUndefined();
    expect(msg.media).toBeUndefined();
    expect(JSON.stringify(msg)).not.toContain(manifest.secret_hex);
  });

  it('turns an image row with wrong carrier.n into broken without leaking secret_hex into imageData', async () => {
    const { manifest } = await sealCarrier(
      new File([new Uint8Array(128).fill(11)], 'pic.png', { type: 'image/png' }),
    );
    const row = makeRow('image', {
      name: 'pic.png',
      size: manifest.size,
      mime: 'image/png',
      sha256: '',
      blob_id: 'tr_2',
      key_hex: '',
      nonce_hex: '',
      carrier: { ...manifest, n: 0 },
    });

    const msg = messageFromNode(row, 'u_me', 'peer_a');

    expect(msg.type).toBe('broken');
    expect(msg.imageData).toBeUndefined();
    expect(msg.media).toBeUndefined();
    expect(JSON.stringify(msg)).not.toContain(manifest.secret_hex);
  });

  it('keeps v1 media and drops an invalid carrier when key_hex is present', async () => {
    const { manifest } = await sealCarrier(
      new File([new Uint8Array(128).fill(13)], 'v1.bin', { type: 'application/octet-stream' }),
    );
    const row = makeRow('file', {
      name: 'v1.bin',
      size: 128,
      mime: 'application/octet-stream',
      sha256: 'aa'.repeat(32),
      blob_id: 'blob_v1',
      key_hex: 'bb'.repeat(32),
      nonce_hex: 'cc'.repeat(12),
      carrier: { ...manifest, v: 3 },
    });

    const msg = messageFromNode(row, 'u_me', 'peer_a');

    expect(msg.type).toBe('file');
    expect(msg.media).toBeDefined();
    expect(msg.media?.blobId).toBe('blob_v1');
    expect(msg.media?.keyHex).toBe('bb'.repeat(32));
    expect(msg.media?.carrier).toBeUndefined();
    expect(JSON.stringify(msg)).not.toContain(manifest.secret_hex);
  });
});
