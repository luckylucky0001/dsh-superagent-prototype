-- who did what: logins, tokens, members, agent permissions, runtimes, approvals, workflow and project changes
create table audit_event (
  id varchar(40) primary key,
  at bigint not null,
  actor_id varchar(40),
  actor_name varchar(200),
  action varchar(80) not null,
  target_type varchar(40),
  target_id varchar(80),
  detail_json text,
  ip varchar(64)
);
create index audit_event_at on audit_event(at desc);
create index audit_event_target on audit_event(target_type, target_id);
