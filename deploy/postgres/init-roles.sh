#!/bin/sh
set -eu
# Primeira inicialização do volume. Para um banco que já existe, rode
# deploy/postgres/ensure-roles.sql com as mesmas variáveis psql.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v core_pw="$CORE_DB_PASSWORD" \
  -v threat_pw="$THREAT_DB_PASSWORD" \
  -v investigation_pw="$INVESTIGATION_DB_PASSWORD" \
  -v ml_pw="$ML_DB_PASSWORD" <<'SQL'
create schema if not exists threat;
create schema if not exists investigation;
create schema if not exists ml;

select format($f$
do $body$
begin
  if not exists (select 1 from pg_roles where rolname = 'core_app') then
    create role core_app login password %L;
  else
    alter role core_app with login password %L;
  end if;
end
$body$;
$f$, :'core_pw', :'core_pw')
\gexec

select format($f$
do $body$
begin
  if not exists (select 1 from pg_roles where rolname = 'threat_app') then
    create role threat_app login password %L;
  else
    alter role threat_app with login password %L;
  end if;
end
$body$;
$f$, :'threat_pw', :'threat_pw')
\gexec

select format($f$
do $body$
begin
  if not exists (select 1 from pg_roles where rolname = 'investigation_app') then
    create role investigation_app login password %L;
  else
    alter role investigation_app with login password %L;
  end if;
end
$body$;
$f$, :'investigation_pw', :'investigation_pw')
\gexec

select format($f$
do $body$
begin
  if not exists (select 1 from pg_roles where rolname = 'ml_app') then
    create role ml_app login password %L;
  else
    alter role ml_app with login password %L;
  end if;
end
$body$;
$f$, :'ml_pw', :'ml_pw')
\gexec

do $$
begin
  execute format('grant connect on database %I to core_app, threat_app, investigation_app, ml_app', current_database());
end
$$;
grant usage, create on schema public to core_app;
grant usage, create on schema threat to threat_app;
grant usage, create on schema investigation to investigation_app;
grant usage, create on schema ml to ml_app;
alter schema threat owner to threat_app;
alter schema investigation owner to investigation_app;
alter schema ml owner to ml_app;
revoke all on schema public from threat_app, investigation_app, ml_app;
revoke all on schema threat from core_app, investigation_app, ml_app;
revoke all on schema investigation from core_app, threat_app, ml_app;
revoke all on schema ml from core_app, threat_app, investigation_app;
SQL
