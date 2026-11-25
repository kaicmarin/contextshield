/**
 * Produção (somente leitura): páginas, catálogo, Confira, login opcional.
 * BASE=https://host node scripts/prod-readonly.mjs
 * Não cria compras, reset de demo nem treino ML.
 */
import assert from "node:assert/strict";
import { BASE, launch } from "./browser.mjs";

const base = process.env.BASE ?? "https://3-22-188-147.sslip.io";
process.env.BASE = base;

const browser = await launch();
const page = await (await browser.newContext({ viewport: { width: 1280, height: 860 } })).newPage();
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));

for (const [path, text] of [
  ["/", "ContextShield"],
  ["/loja", "Catálogo"],
  ["/loja/ajuda", "Como podemos ajudar?"],
  ["/banco", "Entre na sua conta"],
  ["/ops", "Analista"],
  ["/sistema", "Sistema visual"],
]) {
  await page.goto(BASE + path, { waitUntil: "domcontentloaded", timeout: 20000 });
  await page.getByText(text, { exact: false }).first().waitFor({ timeout: 15000 });
  console.log("✓", path);
}

const cat = await page.evaluate(async () => {
  const r = await fetch("/api/catalog/products");
  return { status: r.status, n: (await r.json()).length };
});
assert.ok(cat.status === 200 && cat.n > 0, `catalog products: ${cat.status} n=${cat.n}`);
console.log("✓", "/api/catalog/products", cat.n, "itens");

const chk = await page.evaluate(async () => {
  const r = await fetch("/api/check", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ text: "Ligue para 0800 000 0000" }),
  });
  return { status: r.status, body: await r.json() };
});
assert.equal(chk.status, 200);
assert.ok(chk.body.level);
console.log("✓", "/api/check", chk.body.level);

await browser.close();
if (errors.length) {
  console.error("page errors:", errors);
  process.exit(1);
}
console.log("prod-readonly: ok");