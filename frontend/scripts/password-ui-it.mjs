/**
 * IT: telas Alterar senha (NEXA, Aureon, Ops).
 *   API_URL=http://127.0.0.1:18080 BASE=http://127.0.0.1:5173 node scripts/password-ui-it.mjs
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { BASE, launch } from "./browser.mjs";

const API = process.env.API_URL ?? "http://127.0.0.1:18080";
const creds = JSON.parse(
  readFileSync(join(dirname(fileURLToPath(import.meta.url)), "../../services/core-api/src/test/resources/seed/test-credentials.json"), "utf8"),
).passwords;

async function login(email, password) {
  const r = await fetch(`${API}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  assert.equal(r.status, 200, `login ${email}`);
  return r.json();
}

async function adminReset(email, password) {
  const admin = await login("admin@contextshield.example", creds["admin@contextshield.example"]);
  const users = await fetch(`${API}/api/admin/users`, { headers: { Authorization: `Bearer ${admin.token}` } }).then((x) => x.json());
  const u = users.find((x) => x.email.toLowerCase() === email.toLowerCase());
  assert.ok(u, `user ${email}`);
  const r = await fetch(`${API}/api/admin/users/${u.id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${admin.token}` },
    body: JSON.stringify({ password }),
  });
  assert.equal(r.status, 200);
}

async function uiLogin(page, areaPath, quickName, email, password) {
  await page.goto(BASE + areaPath, { waitUntil: "networkidle" });
  if (quickName) await page.getByRole("button", { name: quickName, exact: true }).click();
  if (email) await page.getByLabel("E-mail").fill(email);
  await page.getByLabel("Senha").fill(password);
  await page.getByRole("button", { name: "Entrar", exact: true }).click();
  await page.getByRole("button", { name: "Entrar", exact: true }).waitFor({ state: "detached", timeout: 45000 });
}

async function changePasswordForm(page, current, next) {
  await page.getByRole("heading", { name: /Alterar senha/i }).waitFor({ timeout: 10000 });
  const form = page.locator("form").filter({ has: page.getByRole("button", { name: /Salvar nova senha/i }) });
  await form.getByLabel("Senha atual").fill(current);
  await form.getByLabel("Nova senha", { exact: true }).fill(next);
  await form.getByLabel("Confirmar nova senha").fill(next);
  const [res] = await Promise.all([
    page.waitForResponse((r) => r.url().includes("/api/me/password") && r.status() === 200, { timeout: 20000 }),
    form.getByRole("button", { name: /Salvar nova senha/i }).click(),
  ]);
  assert.equal(res.status(), 200, `change password HTTP ${res.status()}`);
  const body = await res.json();
  assert.ok(body.token, "resposta deve trazer novo JWT");
}

const browser = await launch();
const page = await (await browser.newContext({ viewport: { width: 1280, height: 900 } })).newPage();
const step = (s) => console.log("✓", s);

const email = `pwd.ui.${Date.now()}@correio.example`;
let pwCurrent = "CsIt-Ui-Reg-Pw01";
const pwNext = "CsIt-Ui-Reg-Pw02";
const pwAfter = "CsIt-Ui-Reg-Pw03";

await fetch(`${API}/api/auth/register`, {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({
    name: "Pwd UI IT",
    email,
    password: pwCurrent,
    city: "São Paulo",
    addressLine: "Rua UI 1",
    district: "Centro",
    zip: "01000-000",
  }),
}).then((r) => assert.equal(r.status, 201));

await uiLogin(page, "/loja/entrar", null, email, pwCurrent);
await page.goto(BASE + "/loja/conta/senha", { waitUntil: "networkidle" });
await changePasswordForm(page, pwCurrent, pwNext);
pwCurrent = pwNext;
step("NEXA — alterar senha");

await page.goto(BASE + "/banco/senha", { waitUntil: "networkidle" });
if (await page.getByRole("heading", { name: /Entre na sua conta/i }).isVisible().catch(() => false)) {
  await uiLogin(page, "/banco", null, email, pwCurrent);
  await page.goto(BASE + "/banco/senha", { waitUntil: "networkidle" });
}
await changePasswordForm(page, pwCurrent, pwAfter);
pwCurrent = pwAfter;
step("Aureon — alterar senha");

const analystSeed = creds["analista@contextshield.example"];
const analystTemp = "CsIt-Analyst-UiPw1";
await uiLogin(page, "/ops", "Analista", "analista@contextshield.example", analystSeed);
await page.goto(BASE + "/ops/conta/senha", { waitUntil: "networkidle" });
await changePasswordForm(page, analystSeed, analystTemp);
step("Operations — alterar senha");

await adminReset("analista@contextshield.example", analystSeed);
step("Analista restaurado para senha de IT");

await browser.close();
console.log("password-ui-it: ok");