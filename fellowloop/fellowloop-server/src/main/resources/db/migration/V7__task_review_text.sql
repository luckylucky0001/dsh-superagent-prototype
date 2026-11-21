-- review targets can carry long instructions; store them like the other JSON columns
alter table task alter column review_json set data type text;
