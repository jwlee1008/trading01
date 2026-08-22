begin;

alter type public.universe_kind add value if not exists 'DEMO_TOP_50';

commit;
