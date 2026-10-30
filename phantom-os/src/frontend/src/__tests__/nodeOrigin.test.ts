/**
 * Під Tauri сторінка живе на tauri://localhost (Windows — http://tauri.localhost),
 * і відносний `/api/v1` ішов туди, а не до вузла. Сторож: у оболонці адреси
 * вузла абсолютні на петлю, у браузері й телефоні — відносні, як були.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { DESK_NODE_ORIGIN, inDeskShell, nodeOrigin, nodeWsUrl } from '../services/nodeOrigin';

const at = (href: string) => {
  const u = new URL(href);
  vi.stubGlobal('location', { href: u.href, protocol: u.protocol, hostname: u.hostname, host: u.host, origin: u.origin });
};

afterEach(() => vi.unstubAllGlobals());

describe('адреса вузла', () => {
  it('Tauri на Linux: tauri://localhost → петля :8000', () => {
    at('tauri://localhost/index.html');
    expect(inDeskShell(window.location)).toBe(true);
    expect(nodeOrigin()).toBe(DESK_NODE_ORIGIN);
    expect(nodeWsUrl('/ws').toString()).toBe('ws://127.0.0.1:8000/ws');
  });

  it('Tauri на Windows: http://tauri.localhost → петля :8000', () => {
    at('http://tauri.localhost/index.html');
    expect(nodeOrigin()).toBe('http://127.0.0.1:8000');
  });

  it('браузер: той самий хост, відносно', () => {
    at('https://node.lan:8443/messenger');
    expect(inDeskShell(window.location)).toBe(false);
    expect(nodeOrigin()).toBe('');
    expect(nodeWsUrl('/ws').toString()).toBe('wss://node.lan:8443/ws');
  });
});
