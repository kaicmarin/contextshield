/**
 * Varredura automática de acessibilidade (axe-core, WCAG 2 A/AA + boas práticas) nas telas principais.
 *   BASE=http://localhost:5173 npm run test:a11y
 * Sai com código 1 se alguma tela tiver violação.
 */
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { BASE, launch, loginAs } from "./browser.mjs";

const axe = readFileSync(createRequire(import.meta.url).resolve("axe-core/axe.min.js"), "utf8");
const browser = await launch();
const page = await (await browser.newContext({ viewport: { width: 1280, height: 860 } })).newPage();
let failed = 0;

async function scan(path) {
  await page.goto(BASE + path);
  await page.waitForLoadState("networkidle");
  // espera as animações de entrada terminarem: o contraste é medido no estado final
  await page.waitForTimeout(1600);
  await page.addScriptTag({ content: axe });
  const r = await page.evaluate(() => window.axe.run(document, { runOnly: ["wcag2a", "wcag2aa", "best-practice"] }));
  failed += r.violations.length;
  console.log(`${r.violations.length ? "✗" : "✓"} ${path.padEnd(36)} ${r.passes.length} regras ok, ${r.violations.length} violações`);
  for (const v of r.violations) console.log(`    ${v.impact} ${v.id}: ${v.nodes[0]?.target}`);
}

for (const p of ["/", "/loja", "/loja/produto/prod-001", "/banco", "/banco/confira"]) await scan(p);
await loginAs(page, "/banco", "Ana");
for (const p of ["/banco", "/banco/compras", "/banco/relatar", "/banco/avisos", "/loja/carrinho"]) await scan(p);
await loginAs(page, "/ops", "Admin");
for (const p of ["/ops", "/ops/transacoes", "/ops/transacoes/TX-2026-000168", "/ops/relatos", "/ops/inteligencia", "/ops/motor", "/ops/usuarios", "/ops/auditoria", "/ops/demo"]) await scan(p);

await browser.close();
process.exit(failed ? 1 : 0);
