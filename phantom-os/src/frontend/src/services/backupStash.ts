type StashBridge = {
  backupStashPut?: (key: string, raw: string) => void;
  backupStashTake?: () => string;
  backupStashReplace?: (json: string) => void;
};

function bridge(): StashBridge | undefined {
  if (typeof window === 'undefined') return undefined;
  return (window as unknown as { PhantomBridge?: StashBridge }).PhantomBridge;
}

export const BACKED_UP_KEYS: ReadonlySet<string> = new Set([
  'np.profiles.v1',
  'np.chatprofile.v1',
  'phantom.listFolders.v1',
  'phantom_archived_chats',
  'np.drafts.v1',
  'np.pins1to1.v1',
  'np.pollVotes.v1',
  'phantom_pinned_chats',
  'phantom_unread_marks',
  'phantom_hushed_chats',
  'phantom_hushed_until',
  'phantom_hush_rules.v1',
  'np.chatKeep.v1',
  'np.selfAdded.v1',
  'nightpost.moments.archive',
  'nightpost.moments.audienceChosen',
  'nightpost.moments.kept',
  'nightpost.moments.known',
  'np.momentseen.v1',
  'np.momentprofile.v1',
  'np.callPrefs.v1',
  'np.gifts.opened',
  'np.stickers.v1',
  'phantom_recent_reactions',
  'np.support.tickets.v1',
  'np.support.reports.v1',
  'np.bizinbox.v1',
  'np.circleRights.v1',
  'np.ringPresets.v1',
  'phantom_language',
  'ui_language',
  'phantom_theme',
  'phantom.themeMode',
  'phantom_invite_name',
  'phantom_invite_address',
  'phantom_user_profile',
  'phantom_pay_notes',
  'phantom_points_payment_claims',
  'phantom_meetup_reminders',
  'np.voiceText.v1',
  'nightpost.builder.pages.v1',
  'np.tempended.v1',
]);

export const BACKED_UP_PREFIXES: readonly string[] = ['np.spaces.v1.', 'np.work.v1.'];

export function isBackedUpKey(key: string): boolean {
  return BACKED_UP_KEYS.has(key) || BACKED_UP_PREFIXES.some((p) => key.startsWith(p));
}

export function stashForBackup(key: string, raw: string): void {
  if (!isBackedUpKey(key)) return;
  try {
    bridge()?.backupStashPut?.(key, raw);
  } catch {
    /* немає рідного боку — копія без вебу */
  }
}

export function adoptRestoredStash(storage: Storage): number {
  const take = bridge()?.backupStashTake;
  if (!take) return 0;
  let parsed: unknown;
  try {
    parsed = JSON.parse(take() || '{}');
  } catch {
    return 0;
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return 0;
  let n = 0;
  for (const [key, raw] of Object.entries(parsed as Record<string, unknown>)) {
    if (!isBackedUpKey(key) || typeof raw !== 'string') continue;
    try {
      storage.setItem(key, raw);
      n++;
    } catch {
      /* квота */
    }
  }
  return n;
}

export function stashAll(storage: Storage): void {
  const all: Record<string, string> = {};
  for (let i = 0; i < storage.length; i++) {
    const key = storage.key(i);
    if (!key || !isBackedUpKey(key)) continue;
    const raw = storage.getItem(key);
    if (raw !== null) all[key] = raw;
  }
  try {
    bridge()?.backupStashReplace?.(JSON.stringify(all));
  } catch {
    /* немає рідного боку */
  }
}

// Most stores write localStorage directly; without this hook their keys reach the copy only on the next app start.
// The prototype is patched because assigning storage.setItem on a real Storage stores an item named "setItem".
export function watchStash(storage: Storage, delayMs = 1500): () => void {
  let timer: ReturnType<typeof setTimeout> | null = null;
  const flush = () => {
    if (timer !== null) clearTimeout(timer);
    timer = null;
    stashAll(storage);
  };
  const touch = (self: unknown, key: string) => {
    if (self !== storage || !isBackedUpKey(String(key)) || timer !== null) return;
    timer = setTimeout(flush, delayMs);
  };
  // Assigning setItem on a real Storage would store an item named "setItem": patch its prototype instead.
  const own = typeof Object.getOwnPropertyDescriptor(storage, 'setItem')?.value === 'function';
  const proto = (own ? storage : Object.getPrototypeOf(storage)) as Storage;
  const set = proto.setItem;
  const remove = proto.removeItem;
  proto.setItem = function (this: Storage, key: string, value: string) {
    set.call(this, key, value);
    touch(this, key);
  };
  proto.removeItem = function (this: Storage, key: string) {
    remove.call(this, key);
    touch(this, key);
  };
  return flush;
}
