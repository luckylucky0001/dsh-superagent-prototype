create table skill (
  id varchar(40) primary key,
  name varchar(100) not null unique,
  description text,
  content text not null,
  created_at bigint not null,
  updated_at bigint not null
);

alter table agent add column skills_json text;
