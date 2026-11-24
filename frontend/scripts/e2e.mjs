/**
 * Teste de ponta a ponta pela interface, com a história da apresentação:
 *  1. Ana cai no golpe da falsa central: o checkout pede contexto, as respostas pausam a compra, ela cancela.
 *  2. Ana registra "Fui vítima" com o endereço que o golpista passou.
 *  3. Mariana tenta comprar para o mesmo endereço: a compra é bloqueada pelo relato da Ana.
 * Requer o sistema no ar com dados recém-reiniciados (Painel → Demonstração → Reiniciar dados).
 *   BASE=http://localhost:5173 npm run test:e2e
 */
import assert from "node:assert/strict";
import { BASE, launch, loginAs } from "./browser.mjs";

const browser = await launch();
const page = await (await browser.newContext({ viewport: { width: 1280, height: 860 } })).newPage();
const step = (s) => console.log("✓", s);

// 1. checkout guiado
await page.goto(BASE + "/loja/carrinho");
await page.getByText("Cenários da apresentação").click();
await page.getByText("Possível indução").click();
await page.getByText("Enquanto isso, no celular").waitFor();
await page.getByRole("button", { name: /^Pagar/ }).click();
await page.waitForURL(/\/loja\/pedido\//);
await page.getByRole("heading", { name: "Só mais um passo" }).waitFor();
step("checkout com sinais de indução pede contexto");

// responde como alguém orientado: escolhe sempre a primeira opção
for (;;) {
  await page.locator(".choice").first().click();
  const next = page.getByRole("button", { name: /Próxima|Enviar respostas/ });
  const last = /Enviar/.test(await next.textContent());
  await next.click();
  if (last) break;
}
await page.getByRole("heading", { name: /Pausamos esta compra/ }).waitFor();
step("respostas orientadas pausam a compra");
await page.getByRole("button", { name: "Cancelar esta compra" }).click();
await page.getByRole("heading", { name: "Compra cancelada" }).waitFor();
step("cliente cancela e nada é cobrado");

// 2. relato
await page.goto(BASE + "/banco/relatar/novo");
await page.getByLabel("O que aconteceu?").selectOption("impersonation");
await page.getByLabel("Por onde falaram com você?").selectOption("whatsapp");
await page.getByLabel("Conte com suas palavras").fill("Uma falsa central pediu para eu comprar um notebook e entregar em um ponto de retirada.");
await page.getByLabel(/Endereço de entrega que passaram/).fill("Rua Pedra Lisa, 880 — loja 3");
await page.getByText("Autorizo o uso").click();
await page.getByRole("button", { name: "Enviar relato" }).click();
await page.getByText(/Relato CS-\d{4}-\d+ registrado/).waitFor();
step("relato registrado no app do banco");

// 3. outra cliente, mesmo destino
await page.goto(BASE + "/loja/carrinho");
await page.getByText("Cenários da apresentação").click();
await page.getByText("Nova compra relacionada").click();
await page.getByText("Enquanto isso, no celular").waitFor();
await page.getByRole("button", { name: /^Pagar/ }).click();
await page.waitForURL(/\/loja\/pedido\//);
const title = await page.getByRole("heading", { level: 1 }).textContent();
assert.equal(title, "Compra interrompida");
step("compra de outra cliente para o mesmo destino é bloqueada");

// painel: a decisão está explicada
await loginAs(page, "/ops", "Analista");
await page.goto(BASE + "/ops/transacoes?status=BLOCKED");
await page.locator("tbody tr").first().click();
await page.waitForURL(/\/ops\/transacoes\/TX-/);
await page.getByText("R1", { exact: true }).first().waitFor();
assert.ok(await page.getByText("Bloquear", { exact: true }).first().isVisible());
step("painel mostra a regra R1 na compra bloqueada");

await browser.close();
console.log("Fluxo completo OK");
