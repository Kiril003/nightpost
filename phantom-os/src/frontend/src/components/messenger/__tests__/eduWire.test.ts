import { describe, expect, it } from 'vitest';
import { composeEdu, eduPreview, eduTag, parseEdu, type EduEvent } from '../../../services/edu/eduWire';
import { assignmentsOf, attendanceOf, classOf, eduItems, topicsOf } from '../../../services/edu/eduModel';
import { classEv, evMsg, msg, seed, STUDENTS, TEACHER, ME } from './eduKit';

describe('навчання: лист із мітою', () => {
  it('туди й назад: читабельні рядки лишаються, дані читаються', () => {
    const ev: EduEvent = { k: 'assign', id: 'a1', t: 'Лабораторна 4 · Маятник', due: 1_800_000_000_000, note: 'Таблиця й висновок', at: 5 };
    const text = composeEdu(['Завдання · Лабораторна 4 · Маятник', 'До пт 23:59', ev.note], ev);
    expect(text.split('\n')[0]).toBe('Завдання · Лабораторна 4 · Маятник');
    expect(parseEdu(text)?.ev).toEqual(ev);
  });

  it('чуже тіло не проходить: не той рід, не ті типи, задовгі списки, непарна мітка', () => {
    const bad = (o: unknown) => `x\n⟦edu1:${btoa(JSON.stringify(o)).replace(/=+$/, '')}⟧`;
    expect(parseEdu(bad({ k: 'grade', at: 1 }))).toBeNull();
    expect(parseEdu(bad({ k: 'assign', id: 1, t: 'x', at: 1 }))).toBeNull();
    expect(parseEdu(bad({ k: 'attend', id: 'a', lesson: '', p: Array(501).fill('s'), l: [], x: [], at: 1 }))).toBeNull();
    expect(parseEdu('x\n⟦edu1:!!!⟧')).toBeNull();
    expect(parseEdu('просто текст')).toBeNull();
  });

  it('список розмов не показує мітку, навіть обрізану на 90 знаках', () => {
    const text = composeEdu(['Завдання · Лабораторна 4'], { k: 'assign', id: 'a1', t: 'Лаб', due: null, note: '', at: 1 });
    expect(eduPreview(text)).toBe('Завдання · Лаб');
    expect(eduPreview(text.slice(0, 40))).not.toContain('⟦');
    expect(eduTag({ k: 'class', n: 'a', s: '', at: 1 })).toMatch(/^⟦edu1:/);
  });
});

describe('навчання: стан із журналу групи', () => {
  it('вчитель класу — автор першого листа «клас»; завдання й присутність чужих ігноруються', () => {
    const chat = seed('student', [
      evMsg(TEACHER, classEv()),
      evMsg('st1', { k: 'assign', id: 'fake', t: 'Підроблене', due: null, note: '', at: 2 }),
      evMsg(TEACHER, { k: 'assign', id: 'a1', t: 'Лаб 4', due: null, note: '', at: 3 }),
      evMsg('st1', { k: 'attend', id: 'fk', lesson: 'x', p: ['st1'], l: [], x: [], at: 4 }),
    ]);
    const items = eduItems(chat, ME);
    const cls = classOf(items, ME)!;
    expect(cls.teacherId).toBe(TEACHER);
    expect(cls.isTeacher).toBe(false);
    const students = STUDENTS.map((s) => ({ ...s, avatar: '' }));
    expect(assignmentsOf(items, cls, students, ME).map((a) => a.id)).toEqual(['a1']);
    expect(attendanceOf(items, cls, students)).toHaveLength(0);
  });

  it('здано вчасно, здано пізно, не здано; перша здача рахується', () => {
    const chat = seed('teacher', [
      evMsg(TEACHER, classEv(), ['k'], '', true),
      evMsg(TEACHER, { k: 'assign', id: 'a1', t: 'Лаб 4', due: 100, note: '', at: 3 }, ['a'], '', true),
      evMsg('st1', { k: 'done', a: 'a1', t: 'Лаб 4', at: 90 }),
      evMsg('st2', { k: 'done', a: 'a1', t: 'Лаб 4', at: 150 }),
      evMsg('st1', { k: 'done', a: 'a1', t: 'Лаб 4', at: 999 }),
    ]);
    const items = eduItems(chat, TEACHER);
    const cls = classOf(items, TEACHER)!;
    const students = STUDENTS.map((s) => ({ ...s, avatar: '' }));
    const a = assignmentsOf(items, cls, students, TEACHER)[0];
    expect([a.done, a.late, a.missing]).toEqual([1, 1, 1]);
    expect(a.rows.find((r) => r.id === 'st1')?.state).toBe('done');
  });

  it('форум: відповідь позначає вчитель чи автор теми, не сторонній', () => {
    const chat = seed('teacher', [
      evMsg(TEACHER, classEv(), ['k'], '', true),
      evMsg('st1', { k: 'topic', id: 't1', t: 'Маятник', at: 2 }, ['Тема'], 'Чому період не залежить від маси?'),
      evMsg('st2', { k: 'reply', id: 'r1', tp: 't1', at: 3 }, ['В'], 'Бо маса скорочується'),
      evMsg('st3', { k: 'answer', tp: 't1', r: 'r1', at: 4 }),
    ]);
    const items = eduItems(chat, TEACHER);
    const cls = classOf(items, TEACHER)!;
    expect(topicsOf(items, cls)[0].solved).toBe(false);
    const chat2 = seed('teacher', [...chat.messages, evMsg('st1', { k: 'answer', tp: 't1', r: 'r1', at: 5 })]);
    const t = topicsOf(eduItems(chat2, TEACHER), classOf(eduItems(chat2, TEACHER), TEACHER)!)[0];
    expect(t.solved).toBe(true);
    expect(t.replies[0].answer).toBe(true);
    void msg;
  });
});
