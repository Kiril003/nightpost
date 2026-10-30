/**
 * Черга задач із межею одночасності — для вивантаження вкладень.
 *
 * Пачку з десяти фото композер віз ПОСЛІДОВНО: кожне чекало, доки попереднє
 * зашифрується, вивантажиться і буде записане вузлом. Усі десять разом теж
 * погано — десять шифротекстів у памʼяті слабкого телефона й десять XHR,
 * що ділять один канал. Тут — не більше `limit` одночасно, решта чекає в
 * порядку надходження.
 */

/** Задача, яку скасували ще до старту: вона не починалась і не почнеться. */
export class QueueCancelled extends Error {
  constructor() {
    super('cancelled before start');
    this.name = 'QueueCancelled';
  }
}

export interface TaskQueue {
  /**
   * Поставити задачу. `cancelled` питають у мить, коли черга дійшла до неї:
   * true — задача не стартує, а обіцянка відхиляється `QueueCancelled`.
   */
  run<T>(task: () => Promise<T>, opts?: { cancelled?: () => boolean }): Promise<T>;
  /** Скільки задач виконується зараз. */
  active(): number;
  /** Скільки чекає своєї черги. */
  waiting(): number;
}

export function createTaskQueue(limit: number): TaskQueue {
  const max = Math.max(1, Math.floor(limit));
  let running = 0;
  const queue: Array<() => void> = [];

  const next = () => {
    while (running < max && queue.length) queue.shift()!();
  };

  return {
    run<T>(task: () => Promise<T>, opts?: { cancelled?: () => boolean }) {
      return new Promise<T>((resolve, reject) => {
        queue.push(() => {
          if (opts?.cancelled?.()) {
            reject(new QueueCancelled());
            return;
          }
          running += 1;
          let p: Promise<T>;
          try {
            p = task();
          } catch (err) {
            p = Promise.reject(err);
          }
          p.then(resolve, reject).finally(() => {
            running -= 1;
            next();
          });
        });
        next();
      });
    },
    active: () => running,
    waiting: () => queue.length,
  };
}

/** Межа одночасних вивантажень вкладень на всю вкладку. */
export const UPLOAD_CONCURRENCY = 3;

/** Одна черга на вкладку: дві пачки з двох розмов ділять ті самі три місця. */
export const attachmentUploads: TaskQueue = createTaskQueue(UPLOAD_CONCURRENCY);
