import { afterEach, describe, expect, it, vi } from 'vitest';
import { observeNear, NEAR_MARGIN } from '../useNearViewport';

type Rec = { cb: IntersectionObserverCallback; opts?: IntersectionObserverInit; observed: Element[]; disconnected: boolean };
const created: Rec[] = [];

class FakeIO {
  rec: Rec;
  constructor(cb: IntersectionObserverCallback, opts?: IntersectionObserverInit) {
    this.rec = { cb, opts, observed: [], disconnected: false };
    created.push(this.rec);
  }
  observe(el: Element) { this.rec.observed.push(el); }
  unobserve(el: Element) { this.rec.observed = this.rec.observed.filter((e) => e !== el); }
  disconnect() { this.rec.disconnected = true; }
}

describe('observeNear', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    created.length = 0;
    document.body.innerHTML = '';
  });

  it('один спостерігач на прокручуваний контейнер, корінь — він, запас — екран', () => {
    vi.stubGlobal('IntersectionObserver', FakeIO);
    const feed = document.createElement('div');
    feed.style.overflowY = 'auto';
    const a = document.createElement('div');
    const b = document.createElement('div');
    feed.append(a, b);
    document.body.append(feed);
    const hitA = vi.fn();
    const hitB = vi.fn();
    observeNear(a, hitA);
    observeNear(b, hitB);
    expect(created).toHaveLength(1);
    expect(created[0].opts?.root).toBe(feed);
    expect(created[0].opts?.rootMargin).toBe(NEAR_MARGIN);

    created[0].cb([{ target: a, isIntersecting: false } as never], {} as never);
    expect(hitA).not.toHaveBeenCalled();
    created[0].cb([{ target: a, isIntersecting: true } as never], {} as never);
    created[0].cb([{ target: a, isIntersecting: true } as never], {} as never);
    expect(hitA).toHaveBeenCalledTimes(1);
    expect(hitB).not.toHaveBeenCalled();
    expect(created[0].observed).toEqual([b]);
  });

  it('остання відписка гасить спостерігача', () => {
    vi.stubGlobal('IntersectionObserver', FakeIO);
    const el = document.createElement('div');
    document.body.append(el);
    const off = observeNear(el, () => undefined);
    off();
    expect(created[0].disconnected).toBe(true);
  });

  it('без IntersectionObserver — «близько» одразу (jsdom, старий WebView)', () => {
    const hit = vi.fn();
    observeNear(document.createElement('div'), hit);
    expect(hit).toHaveBeenCalledTimes(1);
  });
});
