-- whether the runtime's owner lets agents use the network inside the workspace sandbox
alter table runtime add column allow_network boolean default false not null;
