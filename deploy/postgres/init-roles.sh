#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v core_pw="$CORE_DB_PASSWORD" \
  -v threat_pw="$THREAT_DB_PASSWORD" \
  -v investigation_pw="$INVESTIGATION_DB_PASSWORD" <<'SQL'
create schema if not exists threat;
create schema if not exists investigation;
do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'core_app') then
    create role core_app login password :'core_pw';
  end if;
  if not exists (select 1 from pg_roles where rolname = 'threat_app') then
    create role threat_app login password :'threat_pw';
  end if;
  if not exists (select 1 from pg_roles where rolname = 'investigation_app') then
    create role investigation_app login password :'investigation_pw';
  end if;
end
$$;
grant connect on database contextshield to core_app, threat_app, investigation_app;
grant usage, create on schema public to core_app;
grant usage, create on schema threat to threat_app;
grant usage, create on schema investigation to investigation_app;
alter schema threat owner to threat_app;
alter schema investigation owner to investigation_app;
revoke all on schema public from threat_app, investigation_app;
revoke all on schema threat from core_app, investigation_app;
revoke all on schema investigation from core_app, threat_app;
SQL
