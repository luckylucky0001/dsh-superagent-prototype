-- A native code review instead of a prompt (Codex review/start), e.g. {"type":"baseBranch","branch":"main"}.
alter table task add column review_json varchar(2000);
