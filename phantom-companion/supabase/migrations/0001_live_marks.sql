-- ATLAS-4 «Живий шар» v0 — спільні TTL-мітки між операторами.
--
-- Що тут НЕМАЄ і не з'явиться: облікових записів, позиції оператора, треків.
-- Рядок — це «хтось повідомив ось таку небезпеку ось тут, живе дві години».
-- `device_tag` — анонімний токен встановлення, не особа: він потрібен лише
-- щоб пристрій не тягнув назад власні мітки, і ніколи не читається клієнтом
-- як «хто».
--
-- Чесність (D8.1): синхронізована мітка ≠ підтверджений факт. Провенанс
-- лишається в даних, клієнт мусить показувати «спільнота».

create extension if not exists pgcrypto;

create table if not exists public.live_marks (
    id          uuid primary key default gen_random_uuid(),
    kind        text not null check (kind in ('патруль', 'блокпост', 'ДТП')),
    lat         double precision not null check (lat between -90 and 90),
    lon         double precision not null check (lon between -180 and 180),
    -- Геохеш шести знаків ≈ 1.2 км. Саме за ним іде запит регіону, тож у
    -- журналі сервера видно лише грубу клітинку, а не в'юпорт до метра.
    geohash6    text not null check (char_length(geohash6) = 6),
    created_at  timestamptz not null default now(),
    expires_at  timestamptz not null,
    device_tag  text
);

create index if not exists live_marks_region_idx
    on public.live_marks (geohash6, expires_at);

alter table public.live_marks enable row level security;

-- Права рівня таблиці. Без них RLS узагалі не грає: PostgREST відповість
-- «permission denied» ще до політик. Правки й стирання не даємо на рівні
-- привілеїв — це сильніше за просту відсутність політики.
grant select, insert on public.live_marks to anon;
revoke update, delete on public.live_marks from anon;

-- Вставка: будь-хто анонімний, але лише живу мітку і не довшу за дві години.
-- Стеля тут, а не в клієнті: клієнт можна переписати, політику — ні.
drop policy if exists live_marks_anon_insert on public.live_marks;
create policy live_marks_anon_insert
    on public.live_marks
    for insert
    to anon
    with check (
        expires_at > now()
        and expires_at <= now() + interval '2 hours'
    );

-- Читання: лише те, що ще живе. Прострочене не існує для клієнта навіть
-- до прибирання.
drop policy if exists live_marks_anon_select on public.live_marks;
create policy live_marks_anon_select
    on public.live_marks
    for select
    to anon
    using (expires_at > now());

-- Оновлення й видалення для anon не існує СВІДОМО: інакше один клієнт міг
-- би стерти чужі мітки. Мітка вмирає лише від часу.

-- Ідемпотентно: повторний прогін міграції не має падати на «вже в публікації».
do $$
begin
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'live_marks'
    ) then
        alter publication supabase_realtime add table public.live_marks;
    end if;
end
$$;
