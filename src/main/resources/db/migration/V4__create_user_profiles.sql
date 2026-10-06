create table user_profiles (
  username varchar(255) primary key,
  email varchar(255),
  display_name varchar(255),
  updated_at timestamp with time zone not null
);
