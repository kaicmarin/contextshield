-- Estado do indicador de inteligência. Default compatível: tudo que já existia fica UNVERIFIED.
ALTER TABLE intel_entity ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED';
ALTER TABLE intel_entity ADD COLUMN confidence NUMERIC(4, 3);
ALTER TABLE intel_entity ADD COLUMN valid_until TIMESTAMP;
