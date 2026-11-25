-- Executar manualmente no PostgreSQL de produção, uma vez, após deploy do IntelText canônico.
-- Não roda via Flyway em H2; preserve histórico de intel_entity e intel_mention.
-- Ordem: criar IDs novos, repontar menções, remover IDs legados (evita violação de FK).

BEGIN;

-- Telefones
WITH canonical AS (
    SELECT id AS old_id,
           'ENT-PHONE-' || REGEXP_REPLACE(display_value, '[^0-9]', '', 'g') AS new_id,
           kind, display_value, sub, first_seen, last_seen,
           REGEXP_REPLACE(display_value, '[^0-9]', '', 'g') AS digits
    FROM intel_entity
    WHERE kind = 'phone'
)
INSERT INTO intel_entity (id, kind, display_value, sub, first_seen, last_seen)
SELECT c.new_id, c.kind, c.display_value, c.sub, c.first_seen, c.last_seen
FROM canonical c
WHERE LENGTH(c.digits) >= 10
  AND c.old_id <> c.new_id
  AND NOT EXISTS (SELECT 1 FROM intel_entity x WHERE x.id = c.new_id);

UPDATE intel_mention im
SET entity_id = c.new_id
FROM intel_entity e
INNER JOIN (
    SELECT id AS old_id,
           'ENT-PHONE-' || REGEXP_REPLACE(display_value, '[^0-9]', '', 'g') AS new_id,
           REGEXP_REPLACE(display_value, '[^0-9]', '', 'g') AS digits
    FROM intel_entity
    WHERE kind = 'phone'
) c ON c.old_id = e.id
WHERE im.entity_id = e.id
  AND LENGTH(c.digits) >= 10
  AND c.old_id <> c.new_id;

DELETE FROM intel_entity e
USING (
    SELECT id AS old_id,
           'ENT-PHONE-' || REGEXP_REPLACE(display_value, '[^0-9]', '', 'g') AS new_id,
           REGEXP_REPLACE(display_value, '[^0-9]', '', 'g') AS digits
    FROM intel_entity
    WHERE kind = 'phone'
) c
WHERE e.id = c.old_id
  AND LENGTH(c.digits) >= 10
  AND c.old_id <> c.new_id
  AND EXISTS (SELECT 1 FROM intel_entity x WHERE x.id = c.new_id);

-- Domínios
WITH canonical AS (
    SELECT id AS old_id,
           UPPER('ENT-DOMAIN-' || REPLACE(REPLACE(LOWER(TRIM(display_value)), '.example', ''), '.', '-')) AS new_id,
           kind, display_value, sub, first_seen, last_seen
    FROM intel_entity
    WHERE kind = 'domain' AND display_value IS NOT NULL
)
INSERT INTO intel_entity (id, kind, display_value, sub, first_seen, last_seen)
SELECT c.new_id, c.kind, c.display_value, c.sub, c.first_seen, c.last_seen
FROM canonical c
WHERE LENGTH(c.new_id) <= 60
  AND c.old_id <> c.new_id
  AND NOT EXISTS (SELECT 1 FROM intel_entity x WHERE x.id = c.new_id);

UPDATE intel_mention im
SET entity_id = c.new_id
FROM intel_entity e
INNER JOIN (
    SELECT id AS old_id,
           UPPER('ENT-DOMAIN-' || REPLACE(REPLACE(LOWER(TRIM(display_value)), '.example', ''), '.', '-')) AS new_id
    FROM intel_entity
    WHERE kind = 'domain' AND display_value IS NOT NULL
) c ON c.old_id = e.id
WHERE im.entity_id = e.id
  AND LENGTH(c.new_id) <= 60
  AND c.old_id <> c.new_id;

DELETE FROM intel_entity e
USING (
    SELECT id AS old_id,
           UPPER('ENT-DOMAIN-' || REPLACE(REPLACE(LOWER(TRIM(display_value)), '.example', ''), '.', '-')) AS new_id
    FROM intel_entity
    WHERE kind = 'domain' AND display_value IS NOT NULL
) c
WHERE e.id = c.old_id
  AND LENGTH(c.new_id) <= 60
  AND c.old_id <> c.new_id
  AND EXISTS (SELECT 1 FROM intel_entity x WHERE x.id = c.new_id);

COMMIT;