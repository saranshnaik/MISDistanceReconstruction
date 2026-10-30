-- Keep the "just add up the pings" figure next to the rebuilt one, so a dispute can show both.
alter table distance_computation add column naive_km numeric(12, 2) not null default 0;
