import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BACKED_UP_KEYS, BACKED_UP_PREFIXES, adoptRestoredStash, stashAll, stashForBackup, watchStash } from '../backupStash';

const src = (p: string) => readFileSync(resolve(__dirname, '..', '..', p), 'utf8');

describe('backup stash', () => {
  const put = vi.fn();
  const replace = vi.fn();
  let pending = '';
  beforeEach(() => {
    localStorage.clear();
    put.mockReset();
    replace.mockReset();
    pending = '';
    (window as unknown as { PhantomBridge: unknown }).PhantomBridge = {
      backupStashPut: put,
      backupStashReplace: replace,
      backupStashTake: () => {
        const out = pending;
        pending = '';
        return out;
      },
    };
  });
  afterEach(() => {
    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  });

  it('hands only profiles, spaces, work, folders and archive to the phone', () => {
    stashForBackup('np.profiles.v1', '{"v":1}');
    stashForBackup('np.chatprofile.v1', '{"v":1}');
    stashForBackup('np.spaces.v1.p1', '{}');
    stashForBackup('np.work.v1.p1', '{}');
    stashForBackup('phantom.listFolders.v1', '[]');
    stashForBackup('phantom_archived_chats', '[]');
    stashForBackup('np.profiles.v1.unread', '{}');
    stashForBackup('phantom_ai_key', 'sk-x');
    expect(put.mock.calls.map((c) => c[0])).toEqual([
      'np.profiles.v1',
      'np.chatprofile.v1',
      'np.spaces.v1.p1',
      'np.work.v1.p1',
      'phantom.listFolders.v1',
      'phantom_archived_chats',
    ]);
  });

  it('unsent drafts travel with the encrypted copy, their metadata does not', () => {
    stashForBackup('np.drafts.v1', '{"c1":"до завтра"}');
    stashForBackup('np.drafts.v1.meta', '{}');
    expect(put.mock.calls.map((c) => c[0])).toEqual(['np.drafts.v1']);
  });

  it('a restored stash lands in storage before the stores read it, once', () => {
    localStorage.setItem('np.profiles.v1', '{"fresh":true}');
    pending = JSON.stringify({ 'np.profiles.v1': '{"restored":true}', 'np.spaces.v1.p1': '{"v":1}', evil: 'x' });
    expect(adoptRestoredStash(localStorage)).toBe(2);
    expect(localStorage.getItem('np.profiles.v1')).toBe('{"restored":true}');
    expect(localStorage.getItem('evil')).toBeNull();
    expect(adoptRestoredStash(localStorage)).toBe(0);
  });

  it('boot sweep replaces the whole set, so a deleted space leaves the copy', () => {
    localStorage.setItem('phantom_archived_chats', '["c1"]');
    localStorage.setItem('other', '1');
    stashAll(localStorage);
    expect(replace).toHaveBeenCalledTimes(1);
    expect(JSON.parse(replace.mock.calls[0][0])).toEqual({ phantom_archived_chats: '["c1"]' });
  });

  it('the boot module is the first import of the app and every store write stashes', () => {
    const firstImport = src('main.tsx').match(/^import .*$/m)?.[0];
    expect(firstImport).toBe("import './services/backupStashBoot';");
    for (const file of ['stores/profilesStore.ts', 'stores/folderStore.ts', 'services/chatArchive.ts', 'services/spaces/spaceVault.ts']) {
      expect(src(file), file).toMatch(/stashForBackup\(/);
    }
  });

  describe('direct storage writes', () => {
    const owners = [localStorage, Object.getPrototypeOf(sessionStorage) as Storage].map((o) => ({ o, setItem: o.setItem, removeItem: o.removeItem }));
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => {
      for (const { o, setItem, removeItem } of owners) Object.assign(o, { setItem, removeItem });
      vi.useRealTimers();
    });

    it('a real browser storage keeps its items clean while watched', () => {
      sessionStorage.clear();
      watchStash(sessionStorage, 1000);
      sessionStorage.setItem('np.drafts.v1', '{}');
      expect(sessionStorage.length).toBe(1);
      expect(sessionStorage.getItem('setItem')).toBeNull();
      vi.advanceTimersByTime(1000);
      expect(JSON.parse(replace.mock.calls[0][0])).toEqual({ 'np.drafts.v1': '{}' });
      sessionStorage.clear();
    });

    it('a store that writes storage itself still reaches the copy, once per burst', () => {
      watchStash(localStorage, 1000);
      localStorage.setItem('np.drafts.v1', '{"c1":"до завтра"}');
      localStorage.setItem('np.spaces.v1.p1', '{}');
      expect(replace).not.toHaveBeenCalled();
      vi.advanceTimersByTime(1000);
      expect(replace).toHaveBeenCalledTimes(1);
      expect(Object.keys(JSON.parse(replace.mock.calls[0][0])).sort()).toEqual(['np.drafts.v1', 'np.spaces.v1.p1']);
      localStorage.removeItem('np.spaces.v1.p1');
      vi.advanceTimersByTime(1000);
      expect(JSON.parse(replace.mock.calls[1][0])).toEqual({ 'np.drafts.v1': '{"c1":"до завтра"}' });
    });

    it('caches and keys do not wake the copy', () => {
      watchStash(localStorage, 1000);
      localStorage.setItem('phantom_chats_backup', '[]');
      localStorage.setItem('phantom_ai_key', 'sk-x');
      vi.advanceTimersByTime(5000);
      expect(replace).not.toHaveBeenCalled();
    });
  });

  it('the list matches the census the phone checks', () => {
    const census = readFileSync(resolve(__dirname, '../../../../../../phantom-companion/app/src/test/resources/backup-census.tsv'), 'utf8')
      .split('\n')
      .filter((l) => l && !l.startsWith('#'))
      .map((l) => l.split('\t'));
    const keep = (kind: string) => census.filter((r) => r[0] === kind && r[2].startsWith('keep')).map((r) => r[1]).sort();
    expect([...BACKED_UP_KEYS].sort()).toEqual(keep('web'));
    expect([...BACKED_UP_PREFIXES].sort()).toEqual(keep('webprefix'));
    for (const r of census.filter((r) => r[0] === 'web' && r[2].startsWith('skip'))) expect(BACKED_UP_KEYS.has(r[1]), r[1]).toBe(false);
  });
});
