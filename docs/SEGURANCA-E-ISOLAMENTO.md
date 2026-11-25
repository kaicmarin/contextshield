# Segurança e isolamento

## Organização

A organização ativa vem do JWT (`app_user.organization_id`). Não existe fallback automático para a organização 1 em `OrganizationService.idOfEmail()`.

Recursos sem `organization_id` não aparecem na fila do analista. Métricas que ainda usam `coalesce(organization_id, 1)` no SQL tratam dados legados da migração V9; isso **não** significa isolamento multiempresa completo.

## Limitações conhecidas

- Modelos ML publicados na organização 1 (`approved_by = publicado`) podem ser compartilhados como linha de base entre tenants no ML Service.
- Indicadores `SHARED` na inteligência continuam visíveis no verificador público quando configurados.
- Auditoria de atores de sistema sem alvo TX/CS ainda pode cair na organização 1 com aviso em log (último recurso).

## Variáveis sensíveis

| Variável | Desenvolvimento | Produção |
|---|---|---|
| `JWT_SECRET` | 32+ chars; dev permitido se `JWT_ALLOW_DEV_SECRET=true` | Obrigatório; dev secret bloqueado |
| `SEED_ENABLED` | `true` só quando quiser recarregar demo | `false` |
| `DEMO_PASSWORD` | `contexto` apenas em dev | Não usar senha embutida |
| `SPRING_PROFILES_ACTIVE` | `dev` (opcional) | `prod` |