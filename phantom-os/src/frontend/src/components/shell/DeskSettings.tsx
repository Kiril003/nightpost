/**
 * Налаштування ПК — PHANTOM у ролі свого характеру. Не 112 параметрів вузла
 * («рівень логування», «NPU precision»), а кілька питань про те, як він
 * поводиться з людиною: чи приходити самому, чи слухати, як говорити, що
 * знати про неї. Кожен рядок пише той самий параметр вузла, що й повна
 * панель (`settingsApi.set`), і показується лише коли вузол цей параметр має.
 *
 * Повна панель лишається за тихим посиланням «Усі параметри» — правило
 * проєкту: кожен параметр налаштовується з інтерфейсу.
 */
import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronRight, LogOut } from 'lucide-react';
import { settingsApi, authApi, ApiError, peopleApi, brainApi, type Role, type BrainReason } from '../../services/api';
import type { User } from '@shared/types';
import { displayNameOf } from './presence';
import { useAuthStore } from '../../stores/authStore';
import { inDeskShell } from '../../services/nodeOrigin';
import { useSettingsStore } from '../../stores/settingsStore';
import { useSystemStore } from '../../stores/systemStore';
import { useUIStore } from '../../stores/uiStore';
import { bootstrapSettings } from '../../services/settingsBootstrap';
import { tNow } from '../chat/tNow';
import { Avatar, AVATAR_SIZE } from '../messenger/Avatar';
import { useMyHandle } from '../messenger/HandleSheet';
import { useMessengerStore } from '../../stores/messengerStore';
type Val = string | number | boolean;

/** Записати параметр: одразу в інтерфейсі, потім на вузол; вузол відмовив — повернути. */
async function write(key: string, value: Val): Promise<boolean> {
  const store = useSettingsStore.getState();
  const before = store.values[key];
  store.applyRemote(key, value);
  try {
    await settingsApi.set(key, value);
    return true;
  } catch {
    store.applyRemote(key, before);
    return false;
  }
}

function useSetting<T extends Val>(key: string): [T | undefined, (v: T) => void, boolean] {
  const value = useSettingsStore((s) => s.values[key]) as T | undefined;
  const [failed, setFailed] = useState(false);
  const set = (v: T) => {
    setFailed(false);
    void write(key, v).then((ok) => setFailed(!ok));
  };
  return [value, set, failed];
}

export function DeskSettings() {
  const loaded = useSettingsStore((s) => s.loaded);
  const role = useAuthStore((s) => s.user?.role);
  const navigate = useNavigate();
  useEffect(() => {
    if (!loaded) void bootstrapSettings().catch(() => undefined);
  }, [loaded]);

  const [initiative, setInitiative, initiativeFailed] = useSetting<boolean>('ai_initiative_enabled');
  const [, setProactive] = useSetting<boolean>('agent_proactive_enabled');
  const [lang, setLang, langFailed] = useSetting<string>('ai_response_language');
  const [voiceMode, setVoiceMode, voiceFailed] = useSetting<string>('voice_mode');
  const [wake, setWake, wakeFailed] = useSetting<string>('voice_wake_phrase');
  const [speak, setSpeak, speakFailed] = useSetting<boolean>('voice_tts_enabled');
  const [pace, setPace, paceFailed] = useSetting<number>('voice_tts_speed');
  const [patience, setPatience, patienceFailed] = useSetting<number>('voice_silence_timeout_ms');
  const [where, setWhere, whereFailed] = useSetting<boolean>('agent_browser_geolocation_enabled');
  const [session, setSession, sessionFailed] = useSetting<number>('security_session_timeout_m');

  // Налаштування вузла спільні: гість їх не змінить (вузол відмовить), тож
  // і перемикачів, що вдають, ніби можна, йому не показуємо.
  const canChange = role !== 'GUEST';
  if (!loaded) {
    return <div className="absolute inset-0 flex items-center justify-center text-f-read text-f-ink-3">{tNow('deskSettings.asking')}</div>;
  }

  return (
    <div className="absolute inset-0 overflow-y-auto no-scrollbar">
      <div className="mx-auto w-full max-w-[640px] px-6 pt-6 pb-10 flex flex-col gap-4" data-testid="desk-settings">
        <h1 className="font-sans text-f-display text-f-ink">{tNow('deskSettings.title')}</h1>

        <Account />
        <Me />
        <Brain />
        <People />
        <Ready />

        {canChange ? (
          <>
        <Section title="Nightpost">
          {initiative !== undefined && (
            <Toggle
              label={tNow('deskSettings.comeFirst')}
              hint={tNow('deskSettings.comeFirstHint')}
              value={initiative}
              failed={initiativeFailed}
              onChange={(v) => {
                setInitiative(v);
                setProactive(v);
              }}
            />
          )}
          {lang !== undefined && (
            <Choice
              label={tNow('deskSettings.replyLang')}
              value={lang}
              options={[
                ['auto', tNow('deskSettings.asIWrite')],
                ['uk', tNow('deskSettings.ukrainian')],
                ['en', 'English'],
              ]}
              failed={langFailed}
              onChange={setLang}
            />
          )}
        </Section>

        <Section title={tNow('deskSettings.voice')}>
          {voiceMode !== undefined && (
            <Choice
              label={tNow('deskSettings.listen')}
              value={voiceMode}
              failed={voiceFailed}
              options={[
                ['off', tNow('deskSettings.no')],
                ['wake_word', tNow('deskSettings.onName')],
                ['continuous', tNow('deskSettings.always')],
              ]}
              onChange={setVoiceMode}
            />
          )}
          {voiceMode === 'wake_word' && wake !== undefined && (
            <TextRow label={tNow('deskSettings.wakeName')} value={wake} failed={wakeFailed} onCommit={setWake} />
          )}
          {speak !== undefined && <Toggle label={tNow('deskSettings.speakAloud')} value={speak} failed={speakFailed} onChange={setSpeak} />}
          {speak && pace !== undefined && (
            <Choice
              label={tNow('deskSettings.speechPace')}
              value={nearest(pace, [0.85, 1, 1.2])}
              options={[
                [0.85, tNow('deskSettings.slower')],
                [1, tNow('deskSettings.usual')],
                [1.2, tNow('deskSettings.faster')],
              ]}
              failed={paceFailed}
              onChange={setPace}
            />
          )}
          {voiceMode !== 'off' && patience !== undefined && (
            <Choice
              label={tNow('deskSettings.waitUntil')}
              value={nearest(patience, [800, 1200, 2000])}
              options={[
                [800, tNow('deskSettings.short')],
                [1200, tNow('deskSettings.usual')],
                [2000, tNow('deskSettings.long')],
              ]}
              failed={patienceFailed}
              onChange={setPatience}
            />
          )}
        </Section>

        <Section title={tNow('deskSettings.privacy')}>
          {where !== undefined && (
            <Toggle
              label={tNow('deskSettings.knowWhere')}
              hint={tNow('deskSettings.knowWhereHint')}
              value={where}
              failed={whereFailed}
              onChange={setWhere}
            />
          )}
          {session !== undefined && (
            <Choice
              label={tNow('deskSettings.signOutWhenAway')}
              value={nearest(session, [60, 480, 1440])}
              options={[
                [60, tNow('deskSettings.afterHour')],
                [480, tNow('deskSettings.after8h')],
                [1440, tNow('deskSettings.afterDay')],
              ]}
              failed={sessionFailed}
              onChange={setSession}
            />
          )}
        </Section>
          </>
        ) : (
          <p className="text-f-body text-f-ink-3">
            {tNow('deskSettings.ownerOrAdmin')}
          </p>
        )}

        {canChange && (
        <button
          type="button"
          onClick={() => navigate('/settings/all')}
          className="self-start min-h-11 -ml-2 px-2 rounded-f-m flex items-center gap-1 text-f-body text-f-ink-3 hover:text-f-ink hover:bg-f-raise"
        >
          {tNow('deskSettings.allParams')}
          <ChevronRight className="w-4 h-4" strokeWidth={1.75} aria-hidden />
        </button>
        )}
      </div>
    </div>
  );
}

/** Найближче зі значень — щоб довільне число з вузла лягло на один із варіантів. */
function nearest(v: number, options: number[]): number {
  return options.reduce((best, o) => (Math.abs(o - v) < Math.abs(best - v) ? o : best), options[0]);
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  const items = React.Children.toArray(children).filter(Boolean);
  if (!items.length) return null;
  return (
    <section className="rounded-f-sheet bg-f-surface px-4 py-2" aria-label={title}>
      <h2 className="pt-2 pb-1 text-f-label text-f-ink-2">{title}</h2>
      <div className="divide-y divide-f-line">{items}</div>
    </section>
  );
}

function Row({ label, hint, failed, children }: { label: string; hint?: string; failed?: boolean; children: React.ReactNode }) {
  return (
    <div className="min-h-14 py-3 flex items-center gap-4">
      <div className="flex-1 min-w-0">
        <p className="text-f-read text-f-ink">{label}</p>
        {hint && <p className="text-f-body text-f-ink-3">{hint}</p>}
        {failed && <p className="text-f-body text-f-danger">{tNow('deskSettings.notSaved')}</p>}
      </div>
      {children}
    </div>
  );
}

function Toggle({ label, hint, value, failed, onChange }: { label: string; hint?: string; value: boolean; failed?: boolean; onChange: (v: boolean) => void }) {
  return (
    <Row label={label} hint={hint} failed={failed}>
      {/* Ціль дотику 44 × 44 (правило проєкту), доріжка всередині — своїх розмірів. */}
      <button
        type="button"
        role="switch"
        aria-checked={value}
        aria-label={label}
        onClick={() => onChange(!value)}
        className="shrink-0 w-14 h-11 flex items-center justify-center rounded-full"
      >
        <span
          aria-hidden
          className={`relative block w-12 h-7 rounded-full transition-colors duration-f-tap ease-f-std ${value ? 'bg-f-accent' : 'bg-f-raise'}`}
        >
          <span className={`absolute top-1 w-5 h-5 rounded-full bg-f-surface transition-all duration-f-tap ease-f-std ${value ? 'left-6' : 'left-1'}`} />
        </span>
      </button>
    </Row>
  );
}

function Choice<T extends string | number>({
  label,
  value,
  options,
  failed,
  onChange,
}: {
  label: string;
  value: T;
  options: Array<[T, string]>;
  failed?: boolean;
  onChange: (v: T) => void;
}) {
  return (
    <Row label={label} failed={failed}>
      <div role="radiogroup" aria-label={label} className="shrink-0 flex p-1 rounded-full bg-f-raise">
        {options.map(([v, text]) => (
          <button
            key={String(v)}
            type="button"
            role="radio"
            aria-checked={v === value}
            onClick={() => onChange(v)}
            className={`h-9 px-3 rounded-full text-f-body transition-colors duration-f-tap ease-f-std ${
              v === value ? 'bg-f-surface text-f-ink' : 'text-f-ink-2 hover:text-f-ink'
            }`}
          >
            {text}
          </button>
        ))}
      </div>
    </Row>
  );
}

function TextRow({ label, value, failed, onCommit }: { label: string; value: string; failed?: boolean; onCommit: (v: string) => void }) {
  const [draft, setDraft] = useState(value);
  useEffect(() => setDraft(value), [value]);
  const commit = () => {
    const v = draft.trim();
    if (v && v !== value) onCommit(v);
    else setDraft(value);
  };
  return (
    <Row label={label} failed={failed}>
      <input
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => e.key === 'Enter' && (e.currentTarget as HTMLInputElement).blur()}
        aria-label={label}
        className="w-44 h-10 px-3 rounded-f-m bg-f-raise text-f-read text-f-ink focus:outline focus:outline-2 focus:outline-f-accent"
      />
    </Row>
  );
}

/**
 * «Я» — як PHANTOM до тебе звертається і твій PIN. До 26.09 ні імені, ні
 * PIN змінити було ніде: екран входу радив «заміниш у Налаштуваннях», а
 * таких налаштувань не існувало.
 */
/** Рядок акаунта: «Я» в острові веде сюди, а екран акаунта — звідси, навіть без жодної розмови. */
function Account() {
  const me = useMessengerStore((s) => s.currentUser);
  const navigate = useNavigate();
  const name = me.name || tNow('msgr.add.unnamed');
  const handle = useMyHandle();
  return (
    <button
      type="button"
      data-testid="desk-account"
      onClick={() => {
        useMessengerStore.getState().setSettingsModalOpen(true);
        navigate('/');
      }}
      className="min-h-[72px] px-4 rounded-f-sheet bg-f-surface flex items-center gap-4 text-left hover:bg-f-raise"
    >
      <Avatar src={me.avatar} name={name} tone={me.id} className={`${AVATAR_SIZE.header} shrink-0`} />
      <span className="flex-1 min-w-0">
        <span className="block truncate text-f-read text-f-ink">{name}</span>
        {handle.kind === 'set' && <span className="block truncate text-f-body text-f-ink-3">@{handle.handle}</span>}
      </span>
      <ChevronRight className="w-5 h-5 shrink-0 text-f-ink-3" strokeWidth={1.75} aria-hidden />
    </button>
  );
}

function Me() {
  const user = useAuthStore((s) => s.user);
  const updateUser = useAuthStore((s) => s.updateUser);
  const [nameFailed, setNameFailed] = useState(false);
  if (!user) return null;
  const prefs = (user.preferences ?? {}) as unknown as Record<string, unknown>;
  const name = typeof prefs.display_name === 'string' && prefs.display_name ? prefs.display_name : user.username;
  return (
    <Section title={tNow('deskShell.you')}>
      <TextRow
        label={tNow('deskSettings.addressMe')}
        value={name}
        failed={nameFailed}
        onCommit={(v) => {
          setNameFailed(false);
          authApi
            .patchMe({ display_name: v })
            .then((u) => {
              updateUser({ preferences: u.preferences });
              const trimmed = v.trim();
              if (trimmed) {
                useMessengerStore.getState().updateCurrentUser({ name: trimmed });
              }
            })
            .catch(() => setNameFailed(true));
        }}
      />
      <PinChange />
      <SignOut />
    </Section>
  );
}

function SignOut() {
  const navigate = useNavigate();
  return (
    <button
      type="button"
      data-testid="desk-sign-out"
      onClick={() => {
        useAuthStore.getState().signOut();
        useSystemStore.getState().setAuthenticated(false);
        useUIStore.getState().closeAll();
        navigate('/');
      }}
      className="w-full min-h-11 flex items-center gap-3 text-left text-f-body text-f-ink hover:bg-f-raise rounded-f-m"
    >
      <LogOut className="w-4 h-4 text-f-ink-2" strokeWidth={1.75} aria-hidden />
      {tNow('deskShell.signOut')}
    </button>
  );
}

function PinChange() {
  const [open, setOpen] = useState(false);
  const [cur, setCur] = useState('');
  const [next, setNext] = useState('');
  const [again, setAgain] = useState('');
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const digits = (v: string) => v.replace(/\D/g, '').slice(0, 12);

  const save = async () => {
    setMsg(null);
    if (next.length < 4) return setMsg({ ok: false, text: tNow('deskSettings.pinMin') });
    if (next !== again) return setMsg({ ok: false, text: tNow('deskSettings.pinMismatch') });
    setBusy(true);
    try {
      await authApi.changePin(cur, next);
      setMsg({ ok: true, text: tNow('deskSettings.pinChanged') });
      setCur('');
      setNext('');
      setAgain('');
      setOpen(false);
    } catch (e) {
      const code = e instanceof ApiError ? e.status : 0;
      setMsg({
        ok: false,
        text:
          code === 401 ? tNow('deskSettings.wrongPin') : code === 429 ? tNow('deskSettings.tooMany') : code === 400 ? tNow('deskSettings.badPin') : tNow('deskSettings.notSaved'),
      });
    } finally {
      setBusy(false);
    }
  };

  const field = (label: string, value: string, set: (v: string) => void) => (
    <input
      type="password"
      inputMode="numeric"
      autoComplete="off"
      aria-label={label}
      placeholder={label}
      value={value}
      onChange={(e) => set(digits(e.target.value))}
      className="w-full h-11 px-3 rounded-f-m bg-f-raise text-f-read text-f-ink focus:outline focus:outline-2 focus:outline-f-accent"
    />
  );

  return (
    <div className="py-3 flex flex-col gap-3">
      <div className="flex items-center gap-4 min-h-8">
        <div className="flex-1 min-w-0">
          <p className="text-f-read text-f-ink">PIN</p>
          <p className="text-f-body text-f-ink-3">{tNow('deskSettings.pinHelp')}</p>
          {msg && <p className={`text-f-body ${msg.ok ? 'text-f-ink-2' : 'text-f-danger'}`} role="status">{msg.text}</p>}
        </div>
        {!open && (
          <button type="button" onClick={() => { setOpen(true); setMsg(null); }} className="shrink-0 min-h-11 px-4 rounded-full bg-f-raise text-f-body text-f-ink">
            {tNow('deskSettings.changePin')}
          </button>
        )}
      </div>
      {open && (
        <form
          className="grid grid-cols-1 sm:grid-cols-3 gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            void save();
          }}
        >
          {field(tNow('deskSettings.currentPin'), cur, setCur)}
          {field(tNow('deskSettings.newPin'), next, setNext)}
          {field(tNow('deskSettings.againNew'), again, setAgain)}
          <div className="sm:col-span-3 flex gap-2 justify-end">
            <button type="button" onClick={() => setOpen(false)} className="min-h-11 px-4 rounded-full text-f-body text-f-ink-2 hover:bg-f-raise">
              {tNow('deskSettings.noNeed')}
            </button>
            <button type="submit" disabled={busy || !cur || !next || !again} className="min-h-11 px-5 rounded-full bg-f-accent text-f-on-accent text-f-body disabled:opacity-40">
              {tNow('deskSettings.save')}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}

/**
 * Напоготові — лише у вікні PHANTOM (Tauri): оболонка стартує з системою,
 * тримає вузол у фоні, і клік відкриває Головну миттєво. Налаштування живе
 * в оболонці (`desk.json`), не у вузлі: вона мусить знати його до того, як
 * вузол прокинеться.
 */
function Ready() {
  const [on, setOn] = useState<boolean | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (!inDeskShell()) return;
    void import('@tauri-apps/api/core')
      .then(({ invoke }) => invoke<boolean>('desk_ready_get'))
      .then(setOn)
      .catch(() => setOn(null));
  }, []);
  if (on === null) return null;
  return (
    <Section title={tNow('deskSettings.startup')}>
      <Toggle
        label={tNow('deskSettings.keepReady')}
        hint={tNow('deskSettings.standbyHint')}
        value={on}
        failed={failed}
        onChange={(v) => {
          setFailed(false);
          setOn(v);
          void import('@tauri-apps/api/core')
            .then(({ invoke }) => invoke<boolean>('desk_ready_set', { ready: v }))
            .then(setOn)
            .catch(() => {
              setOn(!v);
              setFailed(true);
            });
        }}
      />
    </Section>
  );
}

const ROLE_WORD: Record<Role, string> = {
  get ROOT() { return tNow('deskSettings.role.root'); },
  get OPERATOR() { return tNow('deskSettings.role.operator'); },
  get GUEST() { return tNow('deskSettings.role.guest'); },
};

/**
 * Люди на цьому PHANTOM — лише для власника. Адмін міняє налаштування
 * вузла, гість — ні (вузол перевіряє сам, див. routes_settings). До 26.09
 * керувати людьми з інтерфейсу було неможливо: ручки /users були, екрана — ні,
 * а «Учасники» ходили в простір, якого у власника нема.
 */
function People() {
  const me = useAuthStore((s) => s.user);
  const [people, setPeople] = useState<User[] | null>(null);
  const [error, setError] = useState('');
  const [adding, setAdding] = useState(false);
  const load = () =>
    peopleApi
      .list()
      .then((r) => setPeople(r.users))
      .catch(() => setPeople(null));
  useEffect(() => {
    if (me?.role === 'ROOT') void load();
  }, [me?.role]);
  if (me?.role !== 'ROOT' || !people) return null;

  const act = async (fn: () => Promise<unknown>) => {
    setError('');
    try {
      await fn();
      await load();
    } catch (e) {
      const code = e instanceof ApiError ? e.status : 0;
      setError(code === 409 ? tNow('deskSettings.dupLogin') : tNow('deskSettings.notSaved'));
    }
  };

  return (
    <Section title={tNow('deskSettings.people')}>
      {people.map((p) => {
        const self = p.id === me.id;
        return (
          <div key={p.id} className="min-h-14 py-3 flex items-center gap-4">
            <div className="flex-1 min-w-0">
              <p className="text-f-read text-f-ink truncate">{displayNameOf(p)}{self ? tNow('deskSettings.thisIsYou') : ''}</p>
            </div>
            {self ? (
              <span className="text-f-body text-f-ink-3">{ROLE_WORD[p.role as Role] ?? p.role}</span>
            ) : (
              <>
                <div role="radiogroup" aria-label={tNow('deskSettings.roleOf', { name: displayNameOf(p) })} className="shrink-0 flex p-1 rounded-full bg-f-raise">
                  {(['OPERATOR', 'GUEST'] as Role[]).map((r) => (
                    <button
                      key={r}
                      type="button"
                      role="radio"
                      aria-checked={p.role === r}
                      onClick={() => p.role !== r && void act(() => peopleApi.setRole(p.id, r))}
                      className={`h-9 px-3 rounded-full text-f-body ${p.role === r ? 'bg-f-surface text-f-ink' : 'text-f-ink-2 hover:text-f-ink'}`}
                    >
                      {ROLE_WORD[r]}
                    </button>
                  ))}
                </div>
                <RemoveButton name={displayNameOf(p)} onConfirm={() => void act(() => peopleApi.remove(p.id))} />
              </>
            )}
          </div>
        );
      })}
      {adding ? (
        <AddPerson
          onCancel={() => setAdding(false)}
          onAdd={(v) =>
            void act(async () => {
              await peopleApi.add(v);
              setAdding(false);
            })
          }
        />
      ) : (
        <div className="py-3">
          <button type="button" onClick={() => setAdding(true)} className="min-h-11 px-4 rounded-full bg-f-raise text-f-body text-f-ink">
            {tNow('deskSettings.addPerson')}
          </button>
        </div>
      )}
      {error && <p className="pb-3 text-f-body text-f-danger" role="status">{error}</p>}
    </Section>
  );
}

function RemoveButton({ name, onConfirm }: { name: string; onConfirm: () => void }) {
  const [sure, setSure] = useState(false);
  return sure ? (
    <div className="shrink-0 flex gap-1">
      <button type="button" onClick={onConfirm} className="min-h-11 px-3 rounded-full bg-f-raise text-f-danger text-f-body">
        {tNow('deskSettings.removeName', { name })}
      </button>
      <button type="button" onClick={() => setSure(false)} className="min-h-11 px-3 rounded-full text-f-body text-f-ink-2">
        {tNow('deskSettings.no')}
      </button>
    </div>
  ) : (
    <button type="button" onClick={() => setSure(true)} className="shrink-0 min-h-11 px-3 rounded-full text-f-body text-f-ink-3 hover:bg-f-raise">
      {tNow('deskSettings.remove')}
    </button>
  );
}

function AddPerson({ onAdd, onCancel }: { onAdd: (v: { username: string; display_name: string; role: Role; pin: string }) => void; onCancel: () => void }) {
  const [name, setName] = useState('');
  const [role, setRole] = useState<Role>('GUEST');
  const [pin, setPin] = useState('');
  const [msg, setMsg] = useState('');
  const digits = (v: string) => v.replace(/\D/g, '').slice(0, 12);
  // Ім'я для входу — з імені, українське — латинкою («Оксана» → oksana):
  // раніше кирилиця ставала «person-muivh0as».
  const login = (n: string) =>
    translit(n.trim().toLowerCase()).replace(/[^a-z0-9_.-]+/g, '-').replace(/^-+|-+$/g, '') || `person-${Date.now().toString(36)}`;
  return (
    <form
      className="py-3 grid grid-cols-1 sm:grid-cols-2 gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        if (!name.trim()) return setMsg(tNow('deskSettings.whatsName'));
        if (pin.length < 4) return setMsg(tNow('deskSettings.pinMin2'));
        onAdd({ username: login(name), display_name: name.trim(), role, pin });
      }}
    >
      <input
        aria-label={tNow('deskSettings.personName')}
        placeholder={tNow('deskSettings.name')}
        value={name}
        onChange={(e) => setName(e.target.value)}
        className="h-11 px-3 rounded-f-m bg-f-raise text-f-read text-f-ink focus:outline focus:outline-2 focus:outline-f-accent"
      />
      <input
        type="password"
        inputMode="numeric"
        autoComplete="off"
        aria-label={tNow('deskSettings.personPin')}
        placeholder={tNow('deskSettings.pinForThem')}
        value={pin}
        onChange={(e) => setPin(digits(e.target.value))}
        className="h-11 px-3 rounded-f-m bg-f-raise text-f-read text-f-ink focus:outline focus:outline-2 focus:outline-f-accent"
      />
      <div role="radiogroup" aria-label={tNow('deskSettings.newPersonRole')} className="flex p-1 rounded-full bg-f-raise w-fit">
        {(['OPERATOR', 'GUEST'] as Role[]).map((r) => (
          <button
            key={r}
            type="button"
            role="radio"
            aria-checked={role === r}
            onClick={() => setRole(r)}
            className={`h-9 px-3 rounded-full text-f-body ${role === r ? 'bg-f-surface text-f-ink' : 'text-f-ink-2'}`}
          >
            {ROLE_WORD[r]}
          </button>
        ))}
      </div>
      <p className="text-f-body text-f-ink-3 self-center">
        {role === 'OPERATOR' ? tNow('deskSettings.adminCan') : tNow('deskSettings.guestCan')}
      </p>
      {msg && <p className="sm:col-span-2 text-f-body text-f-danger">{msg}</p>}
      <div className="sm:col-span-2 flex gap-2 justify-end">
        <button type="button" onClick={onCancel} className="min-h-11 px-4 rounded-full text-f-body text-f-ink-2 hover:bg-f-raise">
          {tNow('deskSettings.noNeed')}
        </button>
        <button type="submit" className="min-h-11 px-5 rounded-full bg-f-accent text-f-on-accent text-f-body">
          {tNow('deskSettings.add')}
        </button>
      </div>
    </form>
  );
}

const UK_LAT: Record<string, string> = {
  а: 'a', б: 'b', в: 'v', г: 'h', ґ: 'g', д: 'd', е: 'e', є: 'ie', ж: 'zh', з: 'z', и: 'y', і: 'i', ї: 'i',
  й: 'i', к: 'k', л: 'l', м: 'm', н: 'n', о: 'o', п: 'p', р: 'r', с: 's', т: 't', у: 'u', ф: 'f', х: 'kh',
  ц: 'ts', ч: 'ch', ш: 'sh', щ: 'shch', ь: '', ю: 'iu', я: 'ia', "'": '', 'ʼ': '', '’': '',
};

/** Українське ім'я латинкою (спрощена офіційна транслітерація) — для імені входу. */
export function translit(s: string): string {
  return Array.from(s).map((c) => UK_LAT[c] ?? c).join('');
}

const BRAIN_WHY: Record<BrainReason, string> = {
  get no_key() { return tNow('deskSettings.brain.noKey'); },
  get invalid_key() { return tNow('deskSettings.brain.invalidKey'); },
  get quota() { return tNow('deskSettings.brain.quota'); },
  get offline() { return tNow('deskSettings.brain.offline'); },
  get unknown() { return tNow('deskSettings.brain.unknown'); },
};

/**
 * Розум PHANTOM — лише власник. Без моделі PHANTOM не відповідає; до 27.09
 * розмова при цьому казала «Готовий», а ключ можна було дати лише файлом
 * .env чи в «Усіх параметрах», про що людина не знала.
 */
function Brain() {
  const role = useAuthStore((s) => s.user?.role);
  const [state, setState] = useState<{ ready: boolean; has_key: boolean; reason?: BrainReason | null } | null>(null);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [line, setLine] = useState('');
  const load = () =>
    brainApi
      .state()
      .then(setState)
      .catch(() => setState(null));
  useEffect(() => {
    if (role === 'ROOT') void load();
  }, [role]);
  if (role !== 'ROOT' || !state) return null;

  const save = async () => {
    const key = draft.trim();
    if (!key) return;
    setBusy(true);
    setLine('');
    try {
      await brainApi.setGeminiKey(key);
      const r = await brainApi.check();
      setLine(r.ok ? tNow('deskSettings.ready') : BRAIN_WHY[r.reason ?? 'unknown']);
      if (r.ok) setDraft('');
    } catch {
      setLine(tNow('deskSettings.keyNotSaved'));
    } finally {
      setBusy(false);
      void load();
    }
  };

  return (
    <Section title={tNow('deskSettings.brain')}>
      <div className="py-3">
        <p className="text-f-read text-f-ink">
          {state.ready
            ? tNow('deskSettings.thinks')
            : state.has_key && state.reason
              ? tNow('deskSettings.cantAnswer', { why: `${BRAIN_WHY[state.reason].charAt(0).toLowerCase()}${BRAIN_WHY[state.reason].slice(1)}` })
              : tNow('deskSettings.needsKey')}
        </p>
        {!state.ready && (
          <p className="mt-1 text-f-body text-f-ink-3">
            {tNow('deskSettings.keyHelp')}
          </p>
        )}
        <form
          className="mt-3 flex gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            void save();
          }}
        >
          <input
            type="password"
            autoComplete="off"
            spellCheck={false}
            aria-label={tNow('deskSettings.geminiKey')}
            placeholder={state.has_key ? tNow('deskSettings.replaceKey') : tNow('deskSettings.pasteKey')}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            className="flex-1 min-w-0 h-11 px-3 rounded-f-m bg-f-raise text-f-read text-f-ink focus:outline focus:outline-2 focus:outline-f-accent"
          />
          <button
            type="submit"
            disabled={busy || !draft.trim()}
            className="min-h-11 px-5 rounded-full bg-f-accent text-f-on-accent text-f-body disabled:opacity-50"
          >
            {busy ? tNow('deskSettings.checking') : tNow('deskSettings.save')}
          </button>
        </form>
        {line && (
          <p className="mt-2 text-f-body text-f-ink-2" role="status">
            {line}
          </p>
        )}
      </div>
    </Section>
  );
}

