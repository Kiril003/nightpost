// Standalone utilities — calculator, unit converter, password
// generator, QR generator. No phantom-os dependency, all pure-local.

import { useEffect, useMemo, useState } from 'react';
import QRCode from 'qrcode';
import { GlassCard } from '@/components/GlassCard';
import { BottomNav } from '@/components/BottomNav';
import { PhoneShell } from '@/components/PhoneShell';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useToastStore } from '@/state/toastStore';
import { evaluateExpression } from '@/services/calculatorEngine';
import {
  UNITS,
  unitsByCategory,
  convert,
  type Unit,
  type UnitCategory,
} from '@/services/unitConverter';
import {
  randomPassword,
  randomPassphrase,
  randomPin,
  entropyBits,
  passphraseEntropyBits,
  type PasswordOptions,
} from '@/services/passwordGen';
import type { PhantomTheme } from '@/types/theme';

type Tab = 'calc' | 'units' | 'pwgen' | 'qrgen';

interface UtilitiesScreenProps {
  themeOverride?: PhantomTheme;
  framed?: boolean;
}

export function UtilitiesScreen({ themeOverride, framed = false }: UtilitiesScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;
  const [tab, setTab] = useState<Tab>('calc');
  return (
    <PhoneShell theme={theme} framed={framed}>
      <div style={{ padding: '12px 16px 8px' }}>
        <span style={{ fontFamily: 'var(--mono)', fontSize: 11, letterSpacing: '0.22em', color: theme.ink3, fontWeight: 700, textTransform: 'uppercase' }}>⚙ утиліти</span>
      </div>
      <div style={{ padding: '0 12px 8px', display: 'flex', gap: 4 }}>
        {(
          [
            { k: 'calc', l: 'калькулятор' },
            { k: 'units', l: 'одиниці' },
            { k: 'pwgen', l: 'паролі' },
            { k: 'qrgen', l: 'qr' },
          ] as { k: Tab; l: string }[]
        ).map((t) => (
          <button
            key={t.k}
            onClick={() => setTab(t.k)}
            style={{ flex: 1, minHeight: 32, padding: '0 8px', borderRadius: 8, background: tab === t.k ? theme.primary : 'transparent', color: tab === t.k ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink2, border: `1px solid ${tab === t.k ? theme.primary : theme.glassBorder}`, fontFamily: 'var(--mono)', fontSize: 10, fontWeight: 700, letterSpacing: '0.14em', textTransform: 'uppercase' }}
          >
            {t.l}
          </button>
        ))}
      </div>
      <div style={{ flex: 1, padding: '0 12px 14px', overflowY: 'auto', minHeight: 0 }}>
        {tab === 'calc' && <CalculatorBlock theme={theme} />}
        {tab === 'units' && <UnitsBlock theme={theme} />}
        {tab === 'pwgen' && <PasswordBlock theme={theme} />}
        {tab === 'qrgen' && <QrBlock theme={theme} />}
      </div>
      <BottomNav theme={theme} accent={theme.primary} />
    </PhoneShell>
  );
}

function CalculatorBlock({ theme }: { theme: PhantomTheme }) {
  const [expr, setExpr] = useState('');
  const [history, setHistory] = useState<{ expr: string; result: string }[]>([]);
  const result = useMemo(() => {
    if (!expr.trim()) return '';
    try {
      const r = evaluateExpression(expr);
      return Number.isInteger(r) ? String(r) : r.toString();
    } catch (err) {
      return `⚠ ${(err as Error).message}`;
    }
  }, [expr]);
  const onCommit = () => {
    if (!expr.trim() || result.startsWith('⚠')) return;
    setHistory((h) => [{ expr, result }, ...h].slice(0, 20));
    setExpr(result);
  };
  return (
    <GlassCard level="card" theme={theme} style={{ padding: 14, display: 'flex', flexDirection: 'column', gap: 10 }}>
      <input value={expr} onChange={(e) => setExpr(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') onCommit(); }} placeholder="2 + 2 * sqrt(16)" style={{ minHeight: 48, padding: '0 14px', borderRadius: 12, background: theme.surfaceBase, border: `1px solid ${theme.glassBorder}`, color: theme.ink, fontFamily: 'var(--mono)', fontSize: 18 }} />
      <div style={{ minHeight: 36, padding: '8px 14px', borderRadius: 12, background: theme.glassPanel, border: `1px solid ${theme.glassBorder}`, color: result.startsWith('⚠') ? theme.coral : theme.ink, fontFamily: 'var(--mono)', fontSize: 16, textAlign: 'right' }}>{result || '—'}</div>
      <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
        {['7', '8', '9', '/', '4', '5', '6', '*', '1', '2', '3', '-', '0', '.', '(', ')', '^', 'sqrt(', 'pi', '+'].map((c) => (
          <button key={c} onClick={() => setExpr((s) => s + c)} style={{ flex: '1 0 18%', minHeight: 38, borderRadius: 8, background: theme.glassPanel, border: `1px solid ${theme.glassBorder}`, color: theme.ink, fontFamily: 'var(--mono)', fontSize: 12 }}>{c}</button>
        ))}
        <button onClick={() => setExpr('')} style={{ flex: '1 0 38%', minHeight: 38, borderRadius: 8, background: 'transparent', color: theme.coral, border: `1px solid ${theme.coral}55`, fontFamily: 'var(--mono)', fontSize: 11, fontWeight: 700, letterSpacing: '0.16em', textTransform: 'uppercase' }}>стерти</button>
        <button onClick={onCommit} style={{ flex: '1 0 38%', minHeight: 38, borderRadius: 8, background: theme.primary, color: theme.isDark ? '#1a1208' : '#fff', border: `1px solid ${theme.primary}`, fontFamily: 'var(--mono)', fontSize: 11, fontWeight: 700, letterSpacing: '0.16em', textTransform: 'uppercase' }}>=</button>
      </div>
      {history.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          {history.slice(0, 5).map((h, i) => (
            <button key={i} onClick={() => setExpr(h.expr)} style={{ background: 'transparent', border: 'none', color: theme.ink3, fontFamily: 'var(--mono)', fontSize: 10, textAlign: 'right', cursor: 'pointer' }}>
              {h.expr} = <span style={{ color: theme.ink2 }}>{h.result}</span>
            </button>
          ))}
        </div>
      )}
    </GlassCard>
  );
}

function UnitsBlock({ theme }: { theme: PhantomTheme }) {
  const [category, setCategory] = useState<UnitCategory>('length');
  const [from, setFrom] = useState<Unit>(unitsByCategory('length')[0]!);
  const [to, setTo] = useState<Unit>(unitsByCategory('length')[3] ?? unitsByCategory('length')[0]!);
  const [val, setVal] = useState('1');
  const result = useMemo(() => {
    const n = Number.parseFloat(val);
    if (!Number.isFinite(n)) return '';
    try {
      const r = convert(n, from, to);
      return Math.abs(r) < 1e-4 || Math.abs(r) > 1e6 ? r.toExponential(4) : r.toFixed(Number.isInteger(r) ? 0 : 4);
    } catch (err) {
      return `⚠ ${(err as Error).message}`;
    }
  }, [val, from, to]);
  const cats: UnitCategory[] = ['length', 'mass', 'volume', 'temperature', 'time', 'data'];
  return (
    <GlassCard level="card" theme={theme} style={{ padding: 14, display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
        {cats.map((c) => (
          <button key={c} onClick={() => { setCategory(c); const list = unitsByCategory(c); setFrom(list[0]!); setTo(list[1] ?? list[0]!); }} style={{ flex: '1 0 30%', minHeight: 32, borderRadius: 8, background: category === c ? theme.primary : theme.glassPanel, color: category === c ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink2, border: `1px solid ${category === c ? theme.primary : theme.glassBorder}`, fontFamily: 'var(--mono)', fontSize: 10, fontWeight: 700, letterSpacing: '0.12em', textTransform: 'uppercase' }}>{c}</button>
        ))}
      </div>
      <input type="number" value={val} onChange={(e) => setVal(e.target.value)} style={{ minHeight: 44, padding: '0 14px', borderRadius: 12, background: theme.surfaceBase, border: `1px solid ${theme.glassBorder}`, color: theme.ink, fontFamily: 'var(--mono)', fontSize: 18 }} />
      <div style={{ display: 'flex', gap: 6 }}>
        <select value={from.id} onChange={(e) => setFrom(UNITS.find((u) => u.id === e.target.value) ?? from)} style={{ flex: 1, minHeight: 38, padding: '0 10px', borderRadius: 10, background: theme.glassPanel, border: `1px solid ${theme.glassBorder}`, color: theme.ink, fontFamily: 'var(--mono)', fontSize: 12 }}>
          {unitsByCategory(category).map((u) => (<option key={u.id} value={u.id}>{u.label} ({u.id})</option>))}
        </select>
        <span style={{ alignSelf: 'center', color: theme.ink2 }}>→</span>
        <select value={to.id} onChange={(e) => setTo(UNITS.find((u) => u.id === e.target.value) ?? to)} style={{ flex: 1, minHeight: 38, padding: '0 10px', borderRadius: 10, background: theme.glassPanel, border: `1px solid ${theme.glassBorder}`, color: theme.ink, fontFamily: 'var(--mono)', fontSize: 12 }}>
          {unitsByCategory(category).map((u) => (<option key={u.id} value={u.id}>{u.label} ({u.id})</option>))}
        </select>
      </div>
      <div style={{ minHeight: 44, padding: '10px 14px', borderRadius: 12, background: theme.glassPanel, border: `1px solid ${theme.glassBorder}`, color: theme.ink, fontFamily: 'var(--mono)', fontSize: 18, textAlign: 'right' }}>
        {result || '—'} <span style={{ color: theme.ink3, fontSize: 12, marginLeft: 8 }}>{to.id}</span>
      </div>
    </GlassCard>
  );
}

function PasswordBlock({ theme }: { theme: PhantomTheme }) {
  const [mode, setMode] = useState<'password' | 'passphrase' | 'pin'>('password');
  const [length, setLength] = useState(20);
  const [opts, setOpts] = useState<PasswordOptions>({ length: 20, lower: true, upper: true, digits: true, symbols: false, avoidAmbiguous: false });
  const [output, setOutput] = useState('');
  const regen = () => {
    if (mode === 'password') setOutput(randomPassword({ ...opts, length }));
    else if (mode === 'passphrase') setOutput(randomPassphrase(Math.max(3, Math.min(length, 12))));
    else setOutput(randomPin(Math.max(3, Math.min(length, 12))));
  };
  useEffect(() => regen(), []); // eslint-disable-line react-hooks/exhaustive-deps
  const bits = mode === 'password' ? entropyBits({ ...opts, length }) : mode === 'passphrase' ? passphraseEntropyBits(length) : Math.log2(10) * length;
  const strength = bits < 40 ? 'слабкий' : bits < 60 ? 'нормально' : bits < 80 ? 'добре' : 'сильно';
  const strengthColor = bits < 40 ? theme.coral : bits < 60 ? '#F59E0B' : bits < 80 ? theme.primary : '#16A34A';
  return (
    <GlassCard level="card" theme={theme} style={{ padding: 14, display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div style={{ display: 'flex', gap: 4 }}>
        {(['password', 'passphrase', 'pin'] as const).map((m) => (
          <button key={m} onClick={() => { setMode(m); setLength(m === 'pin' ? 6 : m === 'passphrase' ? 5 : 20); }} style={{ flex: 1, minHeight: 32, borderRadius: 8, background: mode === m ? theme.primary : theme.glassPanel, color: mode === m ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink2, border: `1px solid ${mode === m ? theme.primary : theme.glassBorder}`, fontFamily: 'var(--mono)', fontSize: 10, fontWeight: 700, letterSpacing: '0.14em', textTransform: 'uppercase' }}>{m}</button>
        ))}
      </div>
      <div style={{ padding: '12px 14px', borderRadius: 12, background: theme.glassPanel, border: `1px solid ${theme.glassBorder}`, color: theme.ink, fontFamily: 'var(--mono)', fontSize: 16, wordBreak: 'break-all', letterSpacing: 0.5, minHeight: 60 }}>{output || '—'}</div>
      <div style={{ fontFamily: 'var(--mono)', fontSize: 10, color: strengthColor, letterSpacing: '0.18em', textTransform: 'uppercase', fontWeight: 700 }}>~{Math.round(bits)} bits · {strength}</div>
      <label style={{ fontFamily: 'var(--mono)', fontSize: 10, color: theme.ink3, letterSpacing: '0.16em' }}>Length: {length}</label>
      <input type="range" min={mode === 'pin' ? 3 : mode === 'passphrase' ? 3 : 6} max={mode === 'passphrase' ? 12 : mode === 'pin' ? 12 : 64} value={length} onChange={(e) => setLength(Number.parseInt(e.target.value, 10))} />
      {mode === 'password' && (
        <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
          {(['lower', 'upper', 'digits', 'symbols', 'avoidAmbiguous'] as const).map((k) => (
            <label key={k} style={{ flex: '1 0 30%', display: 'flex', alignItems: 'center', gap: 6, fontFamily: 'var(--mono)', fontSize: 10, color: theme.ink2, padding: '6px 8px', borderRadius: 6, background: theme.glassPanel, border: `1px solid ${theme.glassBorder}` }}>
              <input type="checkbox" checked={(opts as unknown as Record<string, boolean | number | undefined>)[k] === true} onChange={(e) => setOpts((o) => ({ ...o, [k]: e.target.checked }))} />
              {k}
            </label>
          ))}
        </div>
      )}
      <div style={{ display: 'flex', gap: 6 }}>
        <button onClick={regen} style={{ flex: 1, minHeight: 38, borderRadius: 10, background: theme.primary, color: theme.isDark ? '#1a1208' : '#fff', border: `1px solid ${theme.primary}`, fontFamily: 'var(--mono)', fontSize: 11, fontWeight: 700, letterSpacing: '0.18em', textTransform: 'uppercase' }}>↻ ще раз</button>
        <button onClick={async () => { try { await navigator.clipboard?.writeText(output); useToastStore.getState().push('success', 'Скопійовано'); } catch { useToastStore.getState().push('warn', 'Clipboard недоступний'); } }} style={{ flex: 1, minHeight: 38, borderRadius: 10, background: 'transparent', border: `1px solid ${theme.glassBorder}`, color: theme.ink2, fontFamily: 'var(--mono)', fontSize: 11, fontWeight: 700, letterSpacing: '0.18em', textTransform: 'uppercase' }}>⎘ копіювати</button>
      </div>
    </GlassCard>
  );
}

function QrBlock({ theme }: { theme: PhantomTheme }) {
  const [text, setText] = useState('');
  const [dataUrl, setDataUrl] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => {
    if (!text.trim()) { setDataUrl(null); setErr(null); return; }
    let cancelled = false;
    void QRCode.toDataURL(text, { width: 320, margin: 1, color: { dark: '#000000', light: '#FFFFFF' } })
      .then((u) => !cancelled && setDataUrl(u))
      .catch((e: Error) => !cancelled && setErr(e.message));
    return () => { cancelled = true; };
  }, [text]);
  return (
    <GlassCard level="card" theme={theme} style={{ padding: 14, display: 'flex', flexDirection: 'column', gap: 10 }}>
      <textarea value={text} onChange={(e) => setText(e.target.value)} rows={3} placeholder="Будь-який текст: URL, контакт, Wi-Fi…" style={{ padding: 12, borderRadius: 12, background: theme.glassPanel, border: `1px solid ${theme.glassBorder}`, color: theme.ink, fontFamily: 'var(--sans)', fontSize: 13, resize: 'vertical' }} />
      {err && <div style={{ color: theme.coral, fontSize: 11 }}>{err}</div>}
      {dataUrl && (
        <div style={{ display: 'flex', justifyContent: 'center', padding: 10 }}>
          <img src={dataUrl} alt="QR code" style={{ width: 240, height: 240, borderRadius: 12, background: '#fff' }} />
        </div>
      )}
      <div style={{ fontSize: 11, color: theme.ink3, lineHeight: 1.4 }}>Згенеровано локально через `qrcode` lib.</div>
    </GlassCard>
  );
}
