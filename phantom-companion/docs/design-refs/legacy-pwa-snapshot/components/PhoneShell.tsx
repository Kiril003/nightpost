import { type ReactNode, useState } from 'react';
import { GlobalStatusBar } from './GlobalStatusBar';
import { UtilityDrawer } from './UtilityDrawer';
import { useTabSwipe } from '@/hooks/useTabSwipe';
import type { PhantomTheme } from '@/types/theme';

// PhoneShell v2 — every screen sits inside this. Status bar at top,
// scrollable content in the middle, BottomNav (passed by the screen)
// at the bottom. Utility drawer hosted here so any screen's hamburger
// tap opens it.
//
// `framed` wraps the whole shell in a phone-like chrome — only used
// in the showcase / web preview, never on a real device.
//
// `showStatusBar={false}` is for the onboarding + pair flow which
// are pre-pair surfaces and don't have a meaningful "status" yet.

interface PhoneShellProps {
  theme: PhantomTheme;
  children: ReactNode;
  framed?: boolean;
  showStatusBar?: boolean;
}

export function PhoneShell({
  theme,
  children,
  framed = false,
  showStatusBar = true,
}: PhoneShellProps) {
  if (framed) {
    return (
      <div
        style={{
          width: 360,
          height: 740,
          borderRadius: 44,
          background: theme.isDark ? '#000' : '#1a1410',
          padding: 4,
          boxShadow: theme.isDark
            ? '0 30px 80px rgba(0,0,0,0.7), 0 0 0 1px rgba(244,175,37,0.15), inset 0 0 0 2px #2a1f10'
            : '0 30px 80px rgba(120,80,10,0.25), 0 0 0 1px rgba(0,0,0,0.1), inset 0 0 0 2px #0a0604',
          position: 'relative',
        }}
      >
        <Inner theme={theme} showStatusBar={showStatusBar} framed>
          {children}
        </Inner>
      </div>
    );
  }
  return (
    <div
      style={{
        width: '100%',
        height: '100%',
        position: 'relative',
        display: 'flex',
        flexDirection: 'column',
        background: theme.bgGradient,
      }}
    >
      <Inner theme={theme} showStatusBar={showStatusBar}>
        {children}
      </Inner>
    </div>
  );
}

function Inner({
  theme,
  children,
  showStatusBar,
  framed = false,
}: {
  theme: PhantomTheme;
  children: ReactNode;
  showStatusBar: boolean;
  framed?: boolean;
}) {
  const [drawerOpen, setDrawerOpen] = useState(false);
  // Swipe-between-primary-tabs gesture (operator ask 2026-05-04). Only
  // active when the GlobalStatusBar shell is rendered — pre-pair flows
  // (onboarding, pair) don't have tabs to swipe between.
  useTabSwipe(showStatusBar);

  return (
    <div
      style={{
        width: '100%',
        height: '100%',
        borderRadius: framed ? 40 : 0,
        overflow: 'hidden',
        background: framed ? theme.bgGradient : 'transparent',
        position: 'relative',
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      {showStatusBar && (
        <GlobalStatusBar theme={theme} onOpenDrawer={() => setDrawerOpen(true)} />
      )}
      <div
        className="ph-scroll"
        style={{
          flex: 1,
          overflow: 'auto',
          position: 'relative',
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        {children}
      </div>
      <UtilityDrawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        theme={theme}
      />
    </div>
  );
}
