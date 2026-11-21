-- two people answering the same item at once: the second gets "already answered" instead of a second answer
alter table inbox_item add column version bigint default 0 not null;
