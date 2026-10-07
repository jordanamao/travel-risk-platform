-- Travel preferences, risk tolerance and alert settings an employee edits on the Profile page.
alter table user_profiles add column home_city varchar(255);
alter table user_profiles add column preferred_mode varchar(32);
alter table user_profiles add column risk_tolerance varchar(32) not null default 'balanced';
alter table user_profiles add column alert_email boolean not null default true;
alter table user_profiles add column alert_slack boolean not null default true;
alter table user_profiles add column alert_min_level varchar(32) not null default 'any';

create table user_frequent_routes (
  username varchar(255) not null references user_profiles (username) on delete cascade,
  route_order integer not null,
  origin varchar(255) not null,
  destination varchar(255) not null,
  mode varchar(32) not null,
  primary key (username, route_order)
);
