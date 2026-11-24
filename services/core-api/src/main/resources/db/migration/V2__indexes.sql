-- Índices para as consultas mais frequentes (achado do teste de carga: a lista de compras do cliente
-- ficava lenta no pico porque cada linha buscava a última avaliação sem índice).
CREATE INDEX idx_tx_customer_created ON transactions (customer_id, created_at DESC);
CREATE INDEX idx_tx_status ON transactions (status);
CREATE INDEX idx_tx_address ON transactions (address_id);
CREATE INDEX idx_item_tx ON transaction_item (tx_id);
CREATE INDEX idx_assessment_tx ON assessment (tx_id, id);
CREATE INDEX idx_status_change_tx ON status_change (tx_id, id);
CREATE INDEX idx_incident_customer ON incident (customer_id);
CREATE INDEX idx_incident_tx ON incident (tx_id);
CREATE INDEX idx_mention_entity ON intel_mention (entity_id);
CREATE INDEX idx_alert_customer ON alert (customer_id, created_at DESC);
