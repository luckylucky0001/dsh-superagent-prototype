-- the cron and time zone the next fire time was computed from; a changed schedule is recomputed
alter table schedule_state add column spec varchar(300);
