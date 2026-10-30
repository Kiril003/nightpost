-- Спільний шар більше не приймає «блокпост»: позначка, яку бачать інші люди,
-- робила з шару краудсорс позицій блокпостів. Застосовувати разом із клієнтом,
-- у якого `LiveMarksConfig.ALLOWED_KINDS` = патруль, ДТП.

delete from public.live_marks where kind not in ('патруль', 'ДТП');

alter table public.live_marks drop constraint if exists live_marks_kind_check;
alter table public.live_marks
    add constraint live_marks_kind_check check (kind in ('патруль', 'ДТП'));
