/**
 * Senhas da massa de integração/teste (@correio.example / @contextshield.example) — não usar em produção.
 * Produção (AWS) usa credenciais definidas só no ambiente, fora do repositório.
 */
const DEMO_IT_PASSWORDS = {
  "ana.martins@correio.example": "CsIt-Cust001-Seed",
  "carlos.menezes@correio.example": "CsIt-Cust002-Seed",
  "mariana.rocha@correio.example": "CsIt-Cust003-Seed",
  "rafael.nunes@correio.example": "CsIt-Cust004-Seed",
  "beatriz.lima@correio.example": "CsIt-Cust005-Seed",
  "diego.faria@correio.example": "CsIt-Cust006-Seed",
  "helena.prado@correio.example": "CsIt-Cust007-Seed",
  "pedro.alves@correio.example": "CsIt-Cust008-Seed",
  "joana.ribeiro@correio.example": "CsIt-Cust009-Seed",
  "analista@contextshield.example": "CsIt-Analyst-Seed",
  "admin@contextshield.example": "CsIt-Admin-SeedX9",
};

export function demoPasswordFor(email) {
  return DEMO_IT_PASSWORDS[String(email ?? "").trim().toLowerCase()] ?? "";
}