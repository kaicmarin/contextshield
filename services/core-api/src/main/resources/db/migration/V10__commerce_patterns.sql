-- Padrões de comércio. Não publica e não altera a política fintech da loja.
-- Os números são os de PolicyPatterns.

UPDATE risk_policy
SET name = 'Marketplace',
    weights_json = '{"RECENT_SELLER":12,"SELLER_HIGH_CLAIMS":10,"ESTABLISHED_SELLER":-4,"SELLER_IN_INCIDENT":18,"NEW_DESTINATION_THIRD":14,"PICKUP_POINT":6,"DESTINATION_IN_INCIDENT":34,"AMOUNT_ABOVE_LOW":3,"AMOUNT_ABOVE_MID":6,"AMOUNT_ABOVE_HIGH":9,"ORIGIN_LINK":8,"ORIGIN_GUIDED":12}',
    note = 'Vários vendedores no mesmo site. Vendedor novo, reclamação e entrega fora do cadastro pesam mais. O valor pesa menos porque cada anúncio tem um ticket diferente. Não está publicado.'
WHERE profile = 'marketplace' AND status = 'DRAFT';

UPDATE risk_policy
SET name = 'Loja própria',
    weights_json = '{"RECENT_SELLER":3,"SELLER_HIGH_CLAIMS":2,"ESTABLISHED_SELLER":-1,"SELLER_IN_INCIDENT":5,"AMOUNT_ABOVE_LOW":8,"AMOUNT_ABOVE_MID":16,"AMOUNT_ABOVE_HIGH":24,"UNUSUAL_HOUR":8,"NEW_DEVICE":12,"NEW_DESTINATION_THIRD":14,"HABITUAL_DESTINATION":-5,"KNOWN_DEVICE":-6}',
    note = 'A loja vende o próprio estoque, então o vendedor pesa pouco. Pesam o valor longe do hábito, o horário, o aparelho novo e o endereço que não é da casa. Não está publicado.'
WHERE profile = 'ecommerce' AND status = 'DRAFT';

INSERT INTO risk_policy (name, version, status, profile, organization_id, weights_json, note, created_by, created_at)
SELECT 'Marketplace', 1, 'DRAFT', 'marketplace', organization.id,
       '{"RECENT_SELLER":12,"SELLER_HIGH_CLAIMS":10,"ESTABLISHED_SELLER":-4,"SELLER_IN_INCIDENT":18,"NEW_DESTINATION_THIRD":14,"PICKUP_POINT":6,"DESTINATION_IN_INCIDENT":34,"AMOUNT_ABOVE_LOW":3,"AMOUNT_ABOVE_MID":6,"AMOUNT_ABOVE_HIGH":9,"ORIGIN_LINK":8,"ORIGIN_GUIDED":12}',
       'Vários vendedores no mesmo site. Vendedor novo, reclamação e entrega fora do cadastro pesam mais. O valor pesa menos porque cada anúncio tem um ticket diferente. Não está publicado.',
       'sistema', CURRENT_TIMESTAMP
FROM organization
WHERE organization.code = 'nexa-demo'
  AND NOT EXISTS (
      SELECT 1 FROM risk_policy existing
      WHERE existing.organization_id = organization.id AND existing.profile = 'marketplace'
  );

INSERT INTO risk_policy (name, version, status, profile, organization_id, weights_json, note, created_by, created_at)
SELECT 'Loja própria', 1, 'DRAFT', 'ecommerce', organization.id,
       '{"RECENT_SELLER":3,"SELLER_HIGH_CLAIMS":2,"ESTABLISHED_SELLER":-1,"SELLER_IN_INCIDENT":5,"AMOUNT_ABOVE_LOW":8,"AMOUNT_ABOVE_MID":16,"AMOUNT_ABOVE_HIGH":24,"UNUSUAL_HOUR":8,"NEW_DEVICE":12,"NEW_DESTINATION_THIRD":14,"HABITUAL_DESTINATION":-5,"KNOWN_DEVICE":-6}',
       'A loja vende o próprio estoque, então o vendedor pesa pouco. Pesam o valor longe do hábito, o horário, o aparelho novo e o endereço que não é da casa. Não está publicado.',
       'sistema', CURRENT_TIMESTAMP
FROM organization
WHERE organization.code = 'nexa-demo'
  AND NOT EXISTS (
      SELECT 1 FROM risk_policy existing
      WHERE existing.organization_id = organization.id AND existing.profile = 'ecommerce'
  );

INSERT INTO risk_policy (name, version, status, profile, organization_id, weights_json, note, created_by, created_at)
SELECT 'Vale-presente', 1, 'DRAFT', 'giftcard', organization.id,
       '{"ORIGIN_GUIDED":14,"ORIGIN_LINK":10,"VER_ASKED":20,"VER_CONTACT":12,"SCRIPT_ECHO":20,"VER_COACHED":8,"AMOUNT_ABOVE_MID":16,"AMOUNT_ABOVE_HIGH":22,"PICKUP_POINT":6,"NEW_DESTINATION_THIRD":12,"RECENT_SELLER":3}',
       'O golpe pede que a pessoa compre um vale e entregue o código. Pesam a origem orientada, alguém pedindo a compra e o roteiro repetido. O vendedor da loja pesa pouco. Não está publicado.',
       'sistema', CURRENT_TIMESTAMP
FROM organization
WHERE organization.code = 'nexa-demo'
  AND NOT EXISTS (
      SELECT 1 FROM risk_policy existing
      WHERE existing.organization_id = organization.id AND existing.profile = 'giftcard'
  );

INSERT INTO risk_policy_event (policy_id, action, actor, detail, at)
SELECT id, 'PATTERN', 'sistema', name, CURRENT_TIMESTAMP
FROM risk_policy
WHERE profile IN ('marketplace', 'ecommerce', 'giftcard') AND status = 'DRAFT';
