-- work on a project's repository without an issue (e.g. scheduled checks)
alter table task add column project_id varchar(64);
