import { adoptRestoredStash, stashAll, watchStash } from './backupStash';

if (typeof localStorage !== 'undefined') {
  try {
    adoptRestoredStash(localStorage);
    stashAll(localStorage);
    const flush = watchStash(localStorage);
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'hidden') flush();
    });
  } catch {
    /* сховище недоступне */
  }
}
