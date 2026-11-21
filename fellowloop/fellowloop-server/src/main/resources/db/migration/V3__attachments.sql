create table attachment (
  id varchar(40) primary key,
  issue_id varchar(40) not null,
  name varchar(300) not null,
  content_type varchar(100) not null,
  size_bytes bigint not null,
  storage_path varchar(500) not null,
  created_by varchar(40),
  created_at bigint not null
);
create index attachment_issue on attachment(issue_id, created_at);
