/**
 * Abre as cinco páginas do frontend estático e falha se houver erro de JavaScript
 * ou se o texto principal não aparecer. Não exige a API: a loja mostra o erro de catálogo,
 * o banco e o painel mostram o login.
 *   npm run dev          (outro terminal)
 *   npm run test:smoke
 */
import assert from "node:assert/strict";
import { BASE, launch } from "./browser.mjs";

const browser = await launch();
const page = await (await browser.newContext({ viewport: { width: 1280, height: 860 } })).newPage();
const errors = [];
page.on("pageerror", (err) => errors.push(err.message));

const cases = [
  ["/", "ContextShield", "Como o relato da Ana protege a Mariana"],
  ["/loja", "NEXA", "Catálogo"],
  ["/banco", "Aureon", "Entre na sua conta"],
  ["/ops", "Painel de operações", "Analista"],
  ["/sistema", "Sistema visual", "Navegação e ações"],
  ["/loja/ajuda", "Ajuda", "Como podemos ajudar?"],
  ["/banco/confira", "Confira", "Mensagem recebida"],
];

for (const [path, titlePart, text] of cases) {
  await page.goto(BASE + path, { waitUntil: "domcontentloaded", timeout: 20000 });
  await page.getByText(text, { exact: false }).first().waitFor({ timeout: 15000 });
  const title = await page.title();
  assert.ok(title.includes(titlePart) || title.includes("ContextShield"), `${path} título inesperado: ${title}`);
  console.log("✓", path);
}

await page.setViewportSize({ width: 390, height: 844 });
await page.goto(BASE + "/loja", { waitUntil: "domcontentloaded" });
const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
assert.equal(overflow, false, "a loja não deve ter rolagem horizontal no celular");
console.log("✓ loja sem overflow em 390px");

await browser.close();
if (errors.length) {
  console.error(errors.join("\n"));
  process.exit(1);
}
console.log("smoke: ok");
