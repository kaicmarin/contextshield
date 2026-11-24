-- Verificação contextual, estágio da previsão e escopo do relato.
-- Colunas novas, com valor padrão. Nenhuma tabela é apagada.

ALTER TABLE verification ALTER COLUMN answers DROP NOT NULL;
ALTER TABLE verification ALTER COLUMN answered_at DROP NOT NULL;
ALTER TABLE verification ADD COLUMN state VARCHAR(20) NOT NULL DEFAULT 'COMPLETED';
ALTER TABLE verification ADD COLUMN reason VARCHAR(400);
ALTER TABLE verification ADD COLUMN requested_at TIMESTAMP;
ALTER TABLE verification ADD COLUMN expires_at TIMESTAMP;

ALTER TABLE ml_shadow ADD COLUMN stage VARCHAR(20) NOT NULL DEFAULT 'initial';

ALTER TABLE ml_model ADD COLUMN feature_set VARCHAR(40) NOT NULL DEFAULT 'operation';

ALTER TABLE known_outcome ADD COLUMN review_state VARCHAR(20) NOT NULL DEFAULT 'REVIEWED';
ALTER TABLE known_outcome ADD COLUMN origin_kind VARCHAR(30) NOT NULL DEFAULT 'ANALYST';

ALTER TABLE incident ADD COLUMN organization_id BIGINT NOT NULL DEFAULT 1;
UPDATE incident SET organization_id = COALESCE((SELECT t.organization_id FROM transactions t WHERE t.id = incident.tx_id), 1);
CREATE INDEX ix_incident_org ON incident(organization_id);
