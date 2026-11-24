-- Quem produziu o indicador e quem revisou. Colunas nulas: o que já existe continua válido.
ALTER TABLE intel_entity ADD COLUMN evidence_source VARCHAR(40);
ALTER TABLE intel_entity ADD COLUMN reviewed_by VARCHAR(80);
ALTER TABLE intel_entity ADD COLUMN reviewed_at TIMESTAMP;
