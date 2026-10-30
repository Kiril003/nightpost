import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { buildCopy } from '../../i18n/build.ts';
import {
  compactHours,
  dateTile,
  eventCount,
  findEvent,
  goingTotal,
  readiness,
  setDay,
   timeRange,
  weekStrip,
} from './builderModel.ts';

test('week strip has seven days and first row wins', () => {
  const strip = weekStrip([
    { day: 'fri', open: '08:00', close: '01:00' },
    { day: 'fri', open: '12:00', close: '16:00' },
    { day: 'mon', open: '08:00', close: '22:00' },
  ]);
  assert.equal(strip.length, 7);
  assert.deepEqual(strip.map((r) => r.day), ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun']);
  assert.deepEqual(strip[0], { day: 'mon', open: '08:00', close: '22:00' });
  assert.deepEqual(strip[4], { day: 'fri', open: '08:00', close: '01:00' });
  assert.deepEqual(strip[6], { day: 'sun', open: null, close: null });
});

test('setDay replaces one day and closing removes it', () => {
  const initial = [
    { day: 'fri', open: '08:00', close: '22:00' },
    { day: 'fri', open: '23:00', close: '02:00' },
    { day: 'mon', open: '09:00', close: '18:00' },
  ];
  const updated = setDay(initial, 'fri', { open: '10:00', close: '01:00' });
  assert.deepEqual(updated, [
    { day: 'mon', open: '09:00', close: '18:00' },
    { day: 'fri', open: '10:00', close: '01:00' },
  ]);
  const closed = setDay(updated, 'fri', null);
  assert.deepEqual(closed, [{ day: 'mon', open: '09:00', close: '18:00' }]);
});

test('compact hours', () => {
  assert.equal(compactHours('08:00', '22:00'), '08–22');
  assert.equal(compactHours('08:30', '01:00'), '08:30–01');
  assert.equal(compactHours('10:00', '01:45'), '10–01:45');
});

test('readiness counts five items and named dishes only', () => {
  const empty = readiness({
    title: 'Harbour Kitchen',
    about: '',
    address: '12 Harbour Street',
    point: '',
    hours: [],
    events: [],
    token: 'USDC',
    menus: [
      {
        key: 'm1',
        title: 'Kitchen',
        items: [
          { key: 'd1', id: '', name: '  ', price: '4.50', note: '' },
          { key: 'd2', id: '', name: 'Beet soup', price: '4.50', note: '' },
          { key: 'd3', id: '', name: 'Pork dumplings', price: '6.00', note: '' },
        ],
      },
    ],
    wallet: '',
  });
  assert.equal(empty.length, 5);
  assert.deepEqual(empty, [
    { key: 'basics', done: false },
    { key: 'place', done: false },
    { key: 'hours', done: false },
    { key: 'menu', done: true, count: 2 },
    { key: 'wallet', done: false },
  ]);

  const full = readiness({
    title: 'Harbour Kitchen',
    about: 'Soup and coffee',
    address: '12 Harbour Street',
    point: '49.83, 18.28',
    hours: [{ day: 'mon', open: '08:00', close: '22:00' }],
    events: [],
    token: 'USDC',
    menus: [{ key: 'm1', title: '', items: [{ key: 'd1', id: '', name: '   ', price: '', note: '' }] }],
    wallet: '11111111111111111111111111111111',
  });
  assert.deepEqual(full, [
    { key: 'basics', done: true },
    { key: 'place', done: true },
    { key: 'hours', done: true },
    { key: 'menu', done: false, count: 0 },
    { key: 'wallet', done: true },
  ]);
});

test('date tile reads the local string in en and uk', () => {
  assert.deepEqual(dateTile('2026-10-23T19:00', 'en'), { mon: 'OCT', day: '23' });
  assert.deepEqual(dateTile('2026-10-23T19:00', 'uk'), { mon: 'ЖОВ', day: '23' });
  assert.deepEqual(dateTile('2026-11-06T18:30', 'en'), { mon: 'NOV', day: '06' });
  assert.equal(timeRange('2026-10-23T19:00', '2026-10-23T22:00'), '19:00–22:00');
  assert.equal(timeRange('2026-10-23T19:00'), '19:00');
});

test('going total skips cancelled', () => {
  assert.equal(
    goingTotal([
      { going: 26, cancelled: false },
      { going: 14, cancelled: true },
      { going: 5, cancelled: false },
    ]),
    31,
  );
});

test('findEvent matches venue-qualified ids', () => {
  const page = {
    v: 1,
    blocks: [
      { type: 'title', text: 'Harbour Kitchen' },
      { type: 'event', id: 'dumpling-night', title: 'Dumpling night', starts: '2026-10-23T19:00' },
      { type: 'event', id: 'soup-club', title: 'Soup club', starts: '2026-11-06T18:30' },
    ],
  };
  assert.equal(eventCount(page), 2);
  assert.equal(eventCount(null), 0);
  const found = findEvent(page, 'v_123.dumpling-night', 'v_123');
  assert.equal(found?.title, 'Dumpling night');
  const byShort = findEvent(page, 'soup-club');
  assert.equal(byShort?.title, 'Soup club');
  assert.equal(findEvent(page, 'v_999.dumpling-night', 'v_123'), null);
});

test('B1–B3: neutral placeholders, compact hours pills, and single ready card', () => {
  const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');
  const buildI18n = read('../../i18n/build.ts');
  const venueEditor = read('./VenueEditor.tsx');
  const buildPage = read('../../pages/Build.tsx');

  for (const banned of ['Stodoln', 'Ostrava', '49.8347', 'Borscht', 'Борщ']) {
    assert.equal(buildI18n.includes(banned), false, `build.ts must not contain ${banned}`);
  }
  assert.equal(buildCopy.uk.editor.addressPlaceholder, 'Вулиця і номер');
  assert.equal(buildCopy.en.editor.addressPlaceholder, 'Street and number');
  assert.equal(buildCopy.uk.editor.pointPlaceholder, 'Широта, довгота');
  assert.equal(buildCopy.en.editor.pointPlaceholder, 'Latitude, longitude');
  assert.equal(buildCopy.uk.editor.dishPlaceholder, 'Страва');
  assert.equal(buildCopy.en.editor.dishPlaceholder, 'Dish');

  for (const key of ['pointBad', 'pointMissing']) {
    assert.match(buildCopy.uk.editor[key], /Формат: широта, довгота — два числа через кому/);
    assert.match(buildCopy.en.editor[key], /Format: latitude, longitude — two numbers with a comma/);
  }
  for (const key of ['bad_point', 'missing_point']) {
    assert.match(buildCopy.uk.problems[key], /Формат: широта, довгота — два числа через кому/);
    assert.match(buildCopy.en.problems[key], /Format: latitude, longitude — two numbers with a comma/);
  }

  assert.equal(venueEditor.includes('49.8347'), false, 'VenueEditor.tsx has no hard-coded coordinate placeholder');
  assert.match(venueEditor, /placeholder=\{e\.pointPlaceholder\}/);

  const stripStart = venueEditor.indexOf('strip.map((slot)');
  const stripEnd = venueEditor.indexOf('</button>', stripStart);
  assert.ok(stripStart > 0 && stripEnd > stripStart, 'Hours strip button exists');
  const pillBtn = venueEditor.slice(venueEditor.indexOf('<button', stripStart), stripEnd + '</button>'.length);
  assert.equal(pillBtn.includes('a.editor.closed'), false, 'Hours pill renders no a.editor.closed inside the day button');
  assert.match(pillBtn, /\bflex-1\b/);
  assert.match(pillBtn, /\bmin-w-0\b/);
  assert.match(pillBtn, /\bmin-h-\[44px\]/);
  assert.match(pillBtn, /aria-label=/);

  const readyMatches = buildPage.match(/a\.editor\.readyTitle/g) ?? [];
  assert.equal(readyMatches.length, 1, 'Build.tsx renders the ready card once');
  const readyIdx = buildPage.indexOf('a.editor.readyTitle');
  const readyCardOpen = buildPage.slice(Math.max(0, readyIdx - 400), readyIdx);
  assert.match(readyCardOpen, /\border-1\b/);
});

test('published page: the ready card says Finish your page, and hides when everything is done', () => {
  const buildPage = readFileSync(new URL('../../pages/Build.tsx', import.meta.url), 'utf8');
  assert.match(buildPage, /current\.published \? a\.editor\.finishTitle : a\.editor\.readyTitle/);
  assert.match(buildPage, /!\(current\.published && doneCount === items\.length\) && \(/);
});
