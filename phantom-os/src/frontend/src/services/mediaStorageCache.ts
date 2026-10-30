/**
 * IndexedDB persistent storage cache for decrypted media blobs in PHANTOM messenger.
 * Prevents re-fetching and re-decrypting media (images, voice notes, files)
 * across chat switches, app relaunches, or page refreshes.
 */

const DB_NAME = 'phantom_media_vault_v1';
const STORE_NAME = 'decrypted_blobs';
const DB_VERSION = 1;

let dbPromise: Promise<IDBDatabase | null> | null = null;

function getDB(): Promise<IDBDatabase | null> {
  if (typeof indexedDB === 'undefined') {
    return Promise.resolve(null);
  }
  if (!dbPromise) {
    dbPromise = new Promise((resolve) => {
      try {
        const req = indexedDB.open(DB_NAME, DB_VERSION);
        req.onupgradeneeded = () => {
          const db = req.result;
          if (!db.objectStoreNames.contains(STORE_NAME)) {
            db.createObjectStore(STORE_NAME);
          }
        };
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => {
          console.warn('[mediaStorageCache] Failed to open IndexedDB:', req.error);
          resolve(null);
        };
      } catch (err) {
        console.warn('[mediaStorageCache] IndexedDB not available:', err);
        resolve(null);
      }
    });
  }
  return dbPromise;
}

export async function getCachedMediaBlob(blobId: string): Promise<Blob | null> {
  try {
    const db = await getDB();
    if (!db) return null;

    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readonly');
      const store = tx.objectStore(STORE_NAME);
      const req = store.get(blobId);

      req.onsuccess = () => {
        const result = req.result;
        if (result instanceof Blob) {
          resolve(result);
        } else {
          resolve(null);
        }
      };
      req.onerror = () => resolve(null);
    });
  } catch {
    return null;
  }
}

export async function putCachedMediaBlob(blobId: string, blob: Blob): Promise<void> {
  try {
    const db = await getDB();
    if (!db) return;

    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readwrite');
      const store = tx.objectStore(STORE_NAME);
      const req = store.put(blob, blobId);

      req.onsuccess = () => resolve();
      req.onerror = () => resolve(); // Ignore quota errors safely
    });
  } catch {
    // Quota or private mode fallback: fail open, in-memory cache will keep running
  }
}

export async function deleteCachedMediaBlob(blobId: string): Promise<void> {
  try {
    const db = await getDB();
    if (!db) return;

    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readwrite');
      const store = tx.objectStore(STORE_NAME);
      const req = store.delete(blobId);
      req.onsuccess = () => resolve();
      req.onerror = () => resolve();
    });
  } catch {
    // Ignore
  }
}

export async function clearMediaCache(): Promise<void> {
  try {
    const db = await getDB();
    if (!db) return;

    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readwrite');
      const store = tx.objectStore(STORE_NAME);
      const req = store.clear();
      req.onsuccess = () => resolve();
      req.onerror = () => resolve();
    });
  } catch {
    // Ignore
  }
}

/** Скільки місця займає кеш розшифрованих медіа; null — сховища немає. */
export async function mediaCacheSize(): Promise<number | null> {
  try {
    const db = await getDB();
    if (!db) return null;
    return await new Promise((resolve) => {
      let total = 0;
      const req = db.transaction(STORE_NAME, 'readonly').objectStore(STORE_NAME).openCursor();
      req.onsuccess = () => {
        const cur = req.result;
        if (!cur) return resolve(total);
        if (cur.value instanceof Blob) total += cur.value.size;
        cur.continue();
      };
      req.onerror = () => resolve(null);
    });
  } catch {
    return null;
  }
}
