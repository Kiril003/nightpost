import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { scrubTime, turnFrame } from './v9-motion.ts';
import * as M from '../i18n/home-v7.ts';

const css = readFileSync(new URL('../styles/v7.css', import.meta.url), 'utf8');
const page = readFileSync(new URL('./HomeV7.tsx', import.meta.url), 'utf8');
const narrow = css.slice(css.indexOf('@media (max-width: 899px)'));
const rule = (src, sel) => {
  const i = src.indexOf(`${sel} {`);
  return i < 0 ? '' : src.slice(i, src.indexOf('}', i));
};
const pub = (p) => new URL(`../../public${p}`, import.meta.url);

test('scrub maps equal scroll to each segment', () => {
  const b = [0, 4, 10];
  assert.equal(scrubTime(0, b, 10), 0);
  assert.equal(scrubTime(0.25, b, 10), 2);
  assert.equal(scrubTime(0.75, b, 10), 7);
});

test('scrub never asks for the last instant, so seeked cannot loop at the end', () => {
  assert.ok(scrubTime(1, [0, 8], 7.97) <= 7.97 - 0.04);
  assert.ok(scrubTime(1, [0, 8], 8) < 8);
  assert.equal(scrubTime(1, [0, 8], NaN), 8 - 0.04);
});

test('scrub primes muted on iOS and lands on the target after priming', () => {
  const hook = page.slice(page.indexOf('function useScrub'), page.indexOf('function People'));
  assert.match(hook, /defaultMuted = true/);
  assert.match(hook, /v\.pause\(\);\s*\n?\s*seek\(\)/);
  assert.match(hook, /scrubTime\(/);
});

test('turn plays every frame once and holds the last', () => {
  assert.equal(turnFrame(0, 1400, 36), 1);
  assert.equal(turnFrame(700, 1400, 36), 19);
  assert.equal(turnFrame(1400, 1400, 36), 36);
  assert.equal(turnFrame(9999, 1400, 36), 36);
});

test('hero turn and flat phone are wired from the real page', () => {
  assert.equal(M.TURN.frames, JSON.parse(readFileSync(pub(`${M.TURN.dir}/meta.json`), 'utf8')).frames);
  for (let i = 1; i <= M.TURN.frames; i++) assert.ok(existsSync(pub(`${M.TURN.dir}/${String(i).padStart(4, '0')}.webp`)), i);
  assert.ok(existsSync(pub(M.FLAT)));
  assert.match(page, /<HeroTurn\b/);
  assert.match(page, /src=\{FLAT\}/);
});

test('night look keeps text readable on amber and keeps the receipt on a dark card', () => {
  assert.match(rule(css, '.v7-btn'), /color: var\(--on-amber\) !important/);
  assert.match(rule(css, '.v7-nav[data-night] .v7-btn--sm'), /color: var\(--on-amber\)/);
  assert.match(rule(css, '.v7-btn--ghost'), /color: var\(--ink\) !important/);
  assert.doesNotMatch(rule(css, '.v7-btn--sm:hover'), /#2a241e/);
  assert.match(rule(css, '.v9-send svg'), /stroke: var\(--on-amber\)/);
  assert.match(rule(css, '.v7-receipt'), /--paper: rgb\(var\(--ph-raised\)\)/);
  assert.match(rule(css, ".v7[data-look='night']"), /--on-amber: rgb\(var\(--ph-on-accent\)\)/);
});

test('mobile hides flow and people copy visually only, never from screen readers', () => {
  assert.doesNotMatch(narrow.slice(0, narrow.indexOf('@media (prefers-reduced-motion')), /\.v9-stages \.v7-h3,\s*\.v9-stages \.v7-body \{\s*display: none/);
  assert.doesNotMatch(rule(narrow, '.v9-people-line'), /display: none/);
});

test('assistant clip loads while the request types', () => {
  assert.match(page, /<Device clip="b11"[^>]*eager=\{seen\}/);
});

test('flow caption on mobile remounts per stage and eases in over 200 ms', () => {
  assert.match(page, /<p key=\{seg\} className="v9-stage-now"/);
  const bodies = [...narrow.matchAll(/\n\s*\.v9-stage-now \{([^}]*)\}/g)].map((m) => m[1]);
  assert.ok(bodies.some((b) => /animation: v9-stage-in 200ms var\(--ease\);/.test(b)), 'caption animates in 200 ms');
  const keyframes = css.slice(css.indexOf('@keyframes v9-stage-in'));
  const frame = keyframes.slice(0, keyframes.indexOf('}\n}') + 3);
  assert.match(frame, /opacity: 0;/);
  assert.match(frame, /transform: translateY\(6px\);/);
  assert.doesNotMatch(frame, /!important|infinite/);
});

test('reduced motion still flattens every v7 animation, so the caption entrance cannot move', () => {
  const block = css.slice(css.indexOf('@media (prefers-reduced-motion: reduce) {\n  .v7 *'));
  assert.match(block.slice(0, block.indexOf('}\n}') + 3), /animation-duration: 0\.01ms !important/);
  assert.match(page, /<div className="v7" data-still=/);
});

const ffmpeg = (() => {
  try {
    execFileSync('ffmpeg', ['-version'], { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
})();
const gray = (file, t) =>
  execFileSync('ffmpeg', ['-loglevel', 'error', ...(t === undefined ? [] : ['-ss', String(t)]), '-i', file, '-frames:v', '1', '-vf', 'scale=90:182,format=gray', '-f', 'rawvideo', '-'], {
    maxBuffer: 1 << 20,
  });
const apart = (a, b) => {
  let s = 0;
  for (let i = 0; i < a.length; i++) s += Math.abs(a[i] - b[i]);
  return s / a.length;
};

test('scrubbed phones rest on one settled screen, and the still poster is that screen', { skip: !ffmpeg && 'no ffmpeg' }, () => {
  for (const [name, clip, start] of [
    ['people', M.PEOPLE, M.PEOPLE.marks[0]],
    ['flow', M.FLOW, M.FLOW.cuts[0]],
  ]) {
    const video = fileURLToPath(pub(clip.src));
    const rest = gray(video, start);
    assert.ok(apart(rest, gray(video, start + 0.1)) < 3, `${name}: resting frame is mid-transition`);
    assert.ok(apart(gray(fileURLToPath(pub(clip.poster))), rest) < 2, `${name}: poster differs from the resting frame`);
  }
});

test('on a phone, the still flow stacks its three stages so headings keep their width', () => {
  assert.match(rule(narrow, '.v9-still .v9-stages'), /grid-template-columns:\s*1fr;/);
});

test('demo clip and poster carry none of the "circles" scenes', { skip: !ffmpeg && 'no ffmpeg' }, () => {
  const crop = 'crop=iw*0.37:ih*0.22:0:ih*0.28,scale=96:32,format=gray';
  const grab = (file, vf) =>
    execFileSync('ffmpeg', ['-loglevel', 'error', '-i', file, '-vf', vf, '-f', 'rawvideo', '-'], { maxBuffer: 1 << 24 });
  const n = 96 * 32;
  const frames = Buffer.concat([grab(fileURLToPath(pub(M.DEMO.src)), `fps=2,${crop}`), grab(fileURLToPath(pub(M.DEMO.poster)), crop)]);
  for (const beat of ['b01', 'b02', 'b04']) {
    const ref = readFileSync(new URL(`./fixtures/demo-circles-${beat}.gray`, import.meta.url));
    for (let i = 0; i * n < frames.length; i++) assert.ok(apart(frames.subarray(i * n, (i + 1) * n), ref) > 4, `${beat} at sample ${i}`);
  }
});
