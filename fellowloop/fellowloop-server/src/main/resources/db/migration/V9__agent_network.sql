-- ask for network access inside the workspace sandbox (dependency downloads); the daemon must allow it
alter table agent add column network_access boolean default false not null;
