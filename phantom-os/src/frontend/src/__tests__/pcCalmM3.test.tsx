import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SystemState } from '@shared/types';
import { DeskShell } from '../components/shell/DeskShell';
import { DeskWaking } from '../components/shell/DeskWaking';
import { DeskMeet } from '../components/shell/DeskMeet';
import { DeskSettings } from '../components/shell/DeskSettings';
import { DeskHq, Markdown } from '../components/shell/DeskHq';
import MapLayout from '../layouts/MapLayout';
import { useSystemStore } from '../stores/systemStore';
import { useSettingsStore } from '../stores/settingsStore';
import { useMessengerStore } from '../stores/messengerStore';
import * as api from '../services/api';
import { messengerApi } from '../services/messengerApi';

const ROOT = process.cwd();
const read = (p: string): string => readFileSync(join(ROOT, p), 'utf8');

function assertCalmM3(container: HTMLElement) {
  expect(container.querySelectorAll('.shell-aura')).toHaveLength(0);
  expect(container.querySelectorAll('.shell-orb')).toHaveLength(0);
  expect(container.querySelectorAll('.shell-orb-ring')).toHaveLength(0);
  expect(container.querySelectorAll('.backdrop-blur-sm')).toHaveLength(0);
  expect(container.querySelectorAll('.font-serif')).toHaveLength(0);
}

beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({ ok: true, status: 200, json: async () => ({}) })),
  );
  useSystemStore.setState({
    authenticated: true,
    state: SystemState.SHADOW,
    context: null,
    wsConnected: true,
  });
  useSettingsStore.setState({ values: {}, loaded: true });
  useMessengerStore.setState({ chats: [], activeChatId: '', hydrated: true });
  vi.spyOn(api, 'request').mockImplementation(async (_m: string, path: string) => {
    if (path === '/agent/status') {
      return { foreground: { active: false, goal: null }, background: { active: false, goal: null } } as never;
    }
    if (path === '/tools/calendar') return { events: [] } as never;
    return {} as never;
  });
  vi.spyOn(api.chatApi, 'getSessions').mockResolvedValue({ sessions: [], total: 0 });
  vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('PC shell & map — calm Material 3 (no glow, glass, gradients, or serif)', () => {
  it('DeskShell mounts with solid bg-surface border-r border-line rail and no aura or backdrop-blur-sm', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { container } = render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/settings']}>
          <Routes>
            <Route element={<DeskShell />}>
              <Route path="/settings" element={<DeskSettings />} />
            </Route>
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    const dock = await screen.findByTestId('phantom-dock');
    assertCalmM3(container);
    expect(dock.className).toContain('bg-surface');
    expect(dock.className).toContain('rounded-full');
    expect(dock.className).not.toContain('backdrop-blur-sm');
  });

  it('DeskWaking and DeskMeet render PhantomMark w-14 h-14 on flat bg-canvas without aura, orb, or font-serif', () => {
    const waking = render(<DeskWaking waitedMs={1000} />);
    assertCalmM3(waking.container);
    const wakingMark = waking.container.querySelector('[data-phantom-mark]');
    expect(wakingMark).not.toBeNull();
    expect(wakingMark?.className).toContain('w-14');
    expect(wakingMark?.className).toContain('h-14');
    expect(waking.container.querySelector('.shell-root')?.className).toContain('bg-canvas');
    waking.unmount();

    const meet = render(<DeskMeet onDone={() => {}} />);
    assertCalmM3(meet.container);
    const meetMark = meet.container.querySelector('[data-phantom-mark]');
    expect(meetMark).not.toBeNull();
    expect(meetMark?.className).toContain('w-14');
    expect(meetMark?.className).toContain('h-14');
    expect(meet.container.querySelector('.shell-root')?.className).toContain('bg-canvas');
    const meetHeading = meet.container.querySelector('h1');
    expect(meetHeading?.className).toContain('font-sans');
    expect(meetHeading?.className).toContain('text-f-display');
    meet.unmount();
  });

  it('DeskSettings and DeskHq use font-sans token classes (text-f-display, text-f-title) with zero font-serif', async () => {
    const settings = render(
      <MemoryRouter>
        <DeskSettings />
      </MemoryRouter>,
    );
    assertCalmM3(settings.container);
    const settingsH1 = settings.container.querySelector('h1');
    expect(settingsH1?.className).toContain('font-sans');
    expect(settingsH1?.className).toContain('text-f-display');
    settings.unmount();

    vi.spyOn(api.polisApi, 'state').mockResolvedValue({
      missions: [],
      citizens: [],
      gates: [],
      governor: { wave_size: 0, max_wave: 4, running_nodes: 0, queued_nodes: 0, night_mode: false },
    });
    vi.spyOn(api.forgeApi, 'list').mockResolvedValue({ runs: [] });
    vi.spyOn(api.agentAskApi, 'waiting').mockResolvedValue({ questions: [] });

    const hq = render(
      <div>
        <DeskHq />
        <Markdown text={'# Заголовок звіту\nТекст звіту.'} />
      </div>,
    );
    await screen.findByText('Штаб Nightpost');
    assertCalmM3(hq.container);
    const hqH1 = hq.container.querySelector('h1');
    expect(hqH1?.className).toContain('font-sans');
    expect(hqH1?.className).toContain('text-f-title');
    const mdHeading = screen.getByText('Заголовок звіту');
    expect(mdHeading.className).toContain('font-sans');
    expect(mdHeading.className).toContain('text-f-title');
    hq.unmount();
  });

  it('MapLayout mounts with zero inline radial-gradient or blur(2px) and zero aura/orb/serif/backdrop-blur-sm', () => {
    const { container } = render(<MemoryRouter><MapLayout /></MemoryRouter>);
    assertCalmM3(container);
    expect(container.innerHTML).not.toContain('radial-gradient');
    expect(container.innerHTML).not.toContain('blur(2px)');
  });

  it('shell.css, map.css, and DeskRouter.tsx have no radial-gradient spheres, sepia filters, or data-desk-night wrappers', () => {
    const shellCss = read('src/components/shell/shell.css');
    expect(shellCss).not.toContain('.shell-aura');
    expect(shellCss).not.toContain('.shell-orb');
    expect(shellCss).not.toContain('radial-gradient');

    const mapCss = read('src/styles/map.css');
    expect(mapCss).not.toContain('radial-gradient');
    expect(mapCss).not.toContain('sepia(');
    expect(mapCss).toContain('var(--ph-canvas)');

    const deskRouter = read('src/app/DeskRouter.tsx');
    expect(deskRouter).not.toContain('data-desk-night');
  });
});
