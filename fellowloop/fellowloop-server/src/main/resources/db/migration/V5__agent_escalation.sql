-- When the daemon's policy would deny a request, ask a person in the inbox instead.
alter table agent add column escalate_approvals boolean default false not null;
