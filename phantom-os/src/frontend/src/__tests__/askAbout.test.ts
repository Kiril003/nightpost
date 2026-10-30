/**
 * askAbout: одна дорога до розуму з контекстом поверхні. Помилки чесні —
 * без розуму, без звʼязку, ліміт; відповіді ніколи не вигадуємо.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { askAbout, systemFor } from '../services/askAbout';
import { ApiError } from '../services/api';
import { phantomSelfApi } from '../services/phantomSelfApi';
import type { AskContext } from '../services/askContext';

const CTX: AskContext = {
  surface: 'group',
  label: 'Двір · 5 непрочитаних',
  facts: 'Chat «Двір»\nПетро: Збори о шостій біля підʼїзду',
  suggestions: ['Підсумуй непрочитане'],
};

afterEach(() => vi.restoreAllMocks());

describe('systemFor', () => {
  it('несе факти поверхні й правило мови', () => {
    const s = systemFor(CTX);
    expect(s).toContain('Context:');
    expect(s).toContain('Збори о шостій біля підʼїзду');
    expect(s).toMatch(/language of the question/i);
    expect(s.length).toBeLessThanOrEqual(2000);
  });

  it('українською звертається до користувача на «ви», ніколи «ти»/«тобі»', () => {
    for (const surface of ['day', 'wallet', 'person', 'chat', 'group'] as const) {
      const s = systemFor({ ...CTX, surface });
      expect(s).toContain('as «ви»');
      expect(s).toMatch(/never «ти»/);
      expect(s).not.toMatch(/informally as «ти»/);
    }
    expect(systemFor({ ...CTX, surface: 'compose' })).not.toContain('«ви»');
  });

  it('чернетка: лише переписаний текст', () => {
    const s = systemFor({ surface: 'compose', label: 'Чернетка', facts: 'Draft: привіт', suggestions: [], draft: 'привіт' });
    expect(s).toMatch(/only the rewritten message text, nothing else/i);
  });

  it('стеля 2000 навіть із довгими фактами', () => {
    expect(systemFor({ ...CTX, facts: 'x'.repeat(5000) }).length).toBeLessThanOrEqual(2000);
  });
});

describe('askAbout', () => {
  it('відповідь розуму — як є, з контекстом у system', async () => {
    const spy = vi.spyOn(phantomSelfApi, 'ask').mockResolvedValue({ answer: '  Збори в четвер о 18:00.  ' });
    const out = await askAbout(CTX, 'Підсумуй непрочитане');
    expect(out).toEqual({ kind: 'answer', text: 'Збори в четвер о 18:00.' });
    expect(spy).toHaveBeenCalledWith('Підсумуй непрочитане', expect.stringContaining('Збори о шостій'));
  });

  it('без розуму — no_mind з людським поясненням від телефона', async () => {
    vi.spyOn(phantomSelfApi, 'ask').mockRejectedValue(new ApiError(503, 'no_mind', 'Додайте ключ у налаштуваннях'));
    expect(await askAbout(CTX, 'q')).toEqual({ kind: 'none', reason: 'no_mind', detail: 'Додайте ключ у налаштуваннях' });
  });

  it('вузол без цієї дороги (ПК) — no_mind без вигаданого пояснення', async () => {
    vi.spyOn(phantomSelfApi, 'ask').mockRejectedValue(new ApiError(404, 'not_found', 'Not Found'));
    expect(await askAbout(CTX, 'q')).toEqual({ kind: 'none', reason: 'no_mind' });
  });

  it('ліміт — limit', async () => {
    vi.spyOn(phantomSelfApi, 'ask').mockRejectedValue(new ApiError(429, 'rate_limited', 'slow down'));
    expect(await askAbout(CTX, 'q')).toMatchObject({ kind: 'none', reason: 'limit' });
  });

  it('мережа чи телефон мовчить — offline', async () => {
    vi.spyOn(phantomSelfApi, 'ask').mockRejectedValueOnce(new TypeError('Failed to fetch'));
    expect(await askAbout(CTX, 'q')).toMatchObject({ kind: 'none', reason: 'offline' });
    vi.spyOn(phantomSelfApi, 'ask').mockRejectedValueOnce(new ApiError(504, 'timeout', 'Телефон не відповів'));
    expect(await askAbout(CTX, 'q')).toMatchObject({ kind: 'none', reason: 'offline' });
  });

  it('порожня відповідь чи інша помилка — failed, не вигадка', async () => {
    vi.spyOn(phantomSelfApi, 'ask').mockResolvedValueOnce({ answer: '   ' });
    expect(await askAbout(CTX, 'q')).toEqual({ kind: 'none', reason: 'failed' });
    vi.spyOn(phantomSelfApi, 'ask').mockRejectedValueOnce(new ApiError(500, 'failed', 'Unknown error'));
    expect(await askAbout(CTX, 'q')).toEqual({ kind: 'none', reason: 'failed' });
  });

  it('порожнє питання — нікуди не йде', async () => {
    const spy = vi.spyOn(phantomSelfApi, 'ask');
    expect(await askAbout(CTX, '   ')).toEqual({ kind: 'none', reason: 'failed' });
    expect(spy).not.toHaveBeenCalled();
  });
});
