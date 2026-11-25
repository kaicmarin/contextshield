/**
 * Validação IT: NEXA, Aureon e Operations com API em 18080 (via server.mjs + API_URL).
 *   API_URL=http://127.0.0.1:18080 BASE=http://127.0.0.1:5173 node scripts/ui-it.mjs
 */
import { BASE, launch, loginAs } from "./browser.mjs";

const browser = await launch();
const page = await (await browser.newContext({ viewport: { width: 1280, height: 900 } })).newPage();
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));
const step = (s) => console.log("✓", s);

await loginAs(page, "/loja", "Ana");
await page.goto(BASE + "/loja", { waitUntil: "networkidle" });
await page.locator(".nx-card").first().waitFor({ timeout: 30000 });
step("NEXA — catálogo carregou");

await page.goto(BASE + "/banco", { waitUntil: "networkidle" });
if (await page.getByRole("heading", { name: /Entre na sua conta/i }).isVisible().catch(() => false)) {
  await page.getByRole("button", { name: "Ana", exact: true }).click();
  await page.getByRole("button", { name: "Entrar", exact: true }).click();
  await page.getByRole("button", { name: "Entrar", exact: true }).waitFor({ state: "detached" });
}
await page.getByText(/Olá|Últimas compras/i).first().waitFor({ timeout: 15000 });
step("Aureon — área logada");

await loginAs(page, "/ops", "Analista");
await page.goto(BASE + "/ops/transacoes", { waitUntil: "networkidle" });
await page.locator("tbody tr").first().waitFor({ timeout: 15000 });
step("Operations — transações listadas");

await page.goto(BASE + "/ops/inteligencia", { waitUntil: "networkidle" });
await page.getByRole("heading", { name: /Indicadores confirmados/i }).waitFor({ timeout: 20000 });
step("Operations — inteligência / indicadores");

await page.goto(BASE + "/ops/modelos", { waitUntil: "networkidle" });
await page.getByRole("heading", { name: /Modelo|Modelos publicados/i }).first().waitFor({ timeout: 20000 });
step("Operations — ML / modelo");

await page.goto(BASE + "/ops/casos", { waitUntil: "networkidle" });
await page.locator(".o-links li a, .o-empty, h1").first().waitFor({ timeout: 20000 });
step("Operations — investigações");

await browser.close();
if (errors.length) {
  console.error(errors.join("\n"));
  process.exit(1);
}
console.log("ui-it: ok");