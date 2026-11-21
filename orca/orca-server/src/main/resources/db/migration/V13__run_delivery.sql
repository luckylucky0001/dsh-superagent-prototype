-- a webhook delivery id (Idempotency-Key, X-GitHub-Delivery, X-Gitlab-Event-UUID): a redelivery starts no second run
alter table workflow_run add column delivery_key varchar(300);
create unique index ux_workflow_run_delivery on workflow_run (delivery_key);
