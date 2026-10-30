/**
 * Подвійний тап по «Я» повертає попередній профіль: стор памʼятає, з якого
 * профілю перейшли, а двері `__phantomProfiles.back()` віддають нативу назву.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { loadProfiles, startProfiles, useProfiles } from '../stores/profilesStore';

type Door = { __phantomProfiles?: { back?: () => string } };

let stop: (() => void) | undefined;

beforeEach(() => {
  localStorage.clear();
  useProfiles.setState(loadProfiles());
  stop = startProfiles();
});

afterEach(() => {
  stop?.();
  stop = undefined;
});

describe('назад до попереднього профілю', () => {
  it('перемикає туди-сюди між двома останніми', () => {
    const s = useProfiles.getState();
    const personal = s.activeId;
    const work = s.addFromTemplate('work');
    const family = s.addFromTemplate('family');
    s.setActive(work.id);
    s.setActive(family.id);
    expect(useProfiles.getState().switchBack()?.id).toBe(work.id);
    expect(useProfiles.getState().activeId).toBe(work.id);
    expect(useProfiles.getState().switchBack()?.id).toBe(family.id);
    expect(personal).not.toBe(family.id);
  });

  it('памʼятає попередній після перезапуску', () => {
    const s = useProfiles.getState();
    const personal = s.activeId;
    const work = s.addFromTemplate('work');
    s.setActive(work.id);
    useProfiles.setState(loadProfiles());
    expect(useProfiles.getState().switchBack()?.id).toBe(personal);
  });

  it('без попереднього або коли його прибрали — нічого', () => {
    const s = useProfiles.getState();
    expect(s.switchBack()).toBeNull();
    const work = s.addFromTemplate('work');
    const personal = s.activeId;
    s.setActive(work.id);
    useProfiles.getState().remove(personal);
    expect(useProfiles.getState().switchBack()).toBeNull();
    expect(useProfiles.getState().activeId).toBe(work.id);
  });

  it('двері для натива віддають назву нового активного або refused', () => {
    const door = (window as unknown as Door).__phantomProfiles;
    expect(door?.back?.()).toBe('refused');
    const s = useProfiles.getState();
    const work = s.addFromTemplate('work');
    s.setActive(work.id);
    expect(door?.back?.()).toBe(`opened:${useProfiles.getState().profiles[0].name}`);
  });
});
