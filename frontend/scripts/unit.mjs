/**
 * Testes do frontend sem navegador: formatação, catálogo, cartão e o cliente HTTP.
 *   node scripts/unit.mjs
 */
import assert from "node:assert/strict";
import { searchProducts, installmentText, sortProducts, discountOf } from "../assets/js/catalog.js";
import { ApiError, api, getSession, setSession } from "../assets/js/api/client.js";
import { cardErrors, maskCard, maskCep, maskExp } from "../assets/js/utils/card.js";
import { brl, STATUS } from "../assets/js/utils/format.js";
import { filterPaintChildren } from "../assets/js/ops-lab.js";

const store = new Map();
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: (k) => store.delete(k),
};
globalThis.window = { dispatchEvent() {} };

assert.equal(maskCard("4242424242424242"), "4242 4242 4242 4242");
assert.equal(maskCep("01310100"), "01310-100");
assert.equal(maskExp("8"), "08");
assert.equal(maskExp("0829"), "08/29");
const valid = cardErrors({ number: "4242 4242 4242 4242", name: "Ana Martins", exp: "12/30", cvv: "123" }, new Date("2026-10-26"));
assert.deepEqual(valid, {});
const invalid = cardErrors({ number: "1234", name: "A", exp: "13/20", cvv: "1" }, new Date("2026-10-26"));
assert.ok(invalid.number && invalid.name && invalid.exp && invalid.cvv);

assert.equal(STATUS.BLOCKED.customer, "Interrompida");
assert.equal(STATUS.CONTEXT_REQUIRED.label, "Pede contexto");
assert.match(brl(4799.9), /4\.799,90/);

const products = [
  { id: "prod-001", name: "Notebook Lumen", line: "16 GB", categorySlug: "informatica", sku: "NX-1", price: 100, rating: 4.8, reviewCount: 10, installments: 10 },
  { id: "prod-003", name: "Fone Areia", line: "ANC", categorySlug: "audio", sku: "FN-1", price: 50, rating: 4.2, reviewCount: 4, installments: 2 },
];
const found = searchProducts(products, [{ slug: "informatica", name: "Informática" }], "notebook");
assert.equal(found[0].id, "prod-001");
assert.equal(installmentText(100, 10), `10x de ${brl(10)} sem juros`);
assert.deepEqual(sortProducts(products, "menor-preco").map((p) => p.id), ["prod-003", "prod-001"]);
assert.equal(discountOf({ price: 90, oldPrice: 100 }), 10);

setSession("customer", { token: "abc", expiresAt: new Date(Date.now() + 60_000).toISOString(), user: { role: "CUSTOMER", name: "Ana", email: "a@b" } });
assert.equal(getSession("customer").token, "abc");
setSession("customer", null);
assert.equal(getSession("customer"), null);

globalThis.fetch = async () => ({ ok: false, status: 400, json: async () => ({ error: "Campo inválido", fields: { email: "obrigatório" } }) });
await assert.rejects(() => api("/api/auth/login", { method: "POST", body: { email: "", password: "" } }), (e) => {
  assert.ok(e instanceof ApiError);
  assert.equal(e.status, 400);
  assert.equal(e.fields.email, "obrigatório");
  return true;
});

globalThis.fetch = async () => { throw new TypeError("failed"); };
await assert.rejects(() => api("/api/me"), (e) => e instanceof ApiError && e.status === 0);

assert.deepEqual(filterPaintChildren(["ok", null, undefined, false, 0, "tail"]), ["ok", 0, "tail"]);

console.log("unit: ok");
