-- Escopo por organização nos recursos que ainda eram globais.
-- risk_policy.organization_id já existe desde a V7 e continua ligado ao perfil da organização.
-- O catálogo da loja permanece compartilhado.

ALTER TABLE audit_entry ADD COLUMN organization_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE ml_model ADD COLUMN organization_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE ml_experiment ADD COLUMN organization_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE ml_shadow ADD COLUMN organization_id BIGINT NOT NULL DEFAULT 1;

UPDATE risk_policy SET organization_id = 1 WHERE organization_id IS NULL;
ALTER TABLE risk_policy ALTER COLUMN organization_id SET DEFAULT 1;
ALTER TABLE risk_policy ALTER COLUMN organization_id SET NOT NULL;

CREATE INDEX ix_audit_org ON audit_entry(organization_id);
CREATE INDEX ix_policy_org ON risk_policy(organization_id, profile, status);
CREATE INDEX ix_model_org ON ml_model(organization_id, feature_set, status);
CREATE INDEX ix_shadow_org ON ml_shadow(organization_id);
