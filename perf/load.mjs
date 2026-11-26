/**
 * Teste de desempenho sem dependências (Node 18+).
 * Mede o tempo de resposta da API em carga normal e em pico, com um mix parecido com o uso real:
 * 60% vitrine, 25% checkout (que chama o motor de risco), 15% consulta de compras.
 *
 *   node perf/load.mjs                       # API em http://localhost:8080
 *   API=https://minha-api node perf/load.mjs  # outra URL
 *   DURATION=60 node perf/load.mjs            # segundos por fase (padrão 20)
 */
const API = process.env.API ?? "http://localhost:8080";
const DURATION = Number(process.env.DURATION ?? 20) * 1000;
const PHASES = [
  { name: "normal", users: 10 },
  { name: "pico", users: 50 },
];
// metas (requisito não funcional): p95 da API
const TARGET_P95 = { normal: 500, pico: 1500 };

import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const CUSTOMERS = ["ana.martins", "carlos.menezes", "mariana.rocha", "rafael.nunes", "beatriz.lima", "diego.faria", "helena.prado", "pedro.alves", "joana.ribeiro"];
const SEED_PW = JSON.parse(
  readFileSync(join(dirname(fileURLToPath(import.meta.url)), "../services/core-api/src/test/resources/seed/test-credentials.json"), "utf8"),
).passwords;

async function json(method, path, body, token) {
  const t0 = performance.now();
  const res = await fetch(API + path, {
    method,
    headers: { "Content-Type": "application/json", ...(token && { Authorization: `Bearer ${token}` }) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await res.json().catch(() => null);
  return { ms: performance.now() - t0, status: res.status, data };
}

const pct = (arr, p) => {
  if (!arr.length) return 0;
  const s = [...arr].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.ceil((p / 100) * s.length) - 1)];
};

async function prepare() {
  const sessions = [];
  for (const c of CUSTOMERS) {
    const email = `${c}@correio.example`;
    const r = await json("POST", "/api/auth/login", { email, password: SEED_PW[email] });
    if (r.status !== 200) throw new Error(`login ${c}: ${r.status}`);
    const me = await json("GET", "/api/me", null, r.data.token);
    const home = me.data.addresses.find((a) => a.kind === "home") ?? me.data.addresses[0];
    const device = me.data.devices.find((d) => d.trusted) ?? me.data.devices[0];
    sessions.push({ token: r.data.token, addressId: home.id, deviceId: device.id });
  }
  const products = (await json("GET", "/api/catalog/products")).data.filter((p) => p.price < 400).map((p) => p.id);
  return { sessions, products };
}

async function phase({ name, users }, ctx) {
  const lat = { all: [], vitrine: [], checkout: [], consulta: [] };
  let errors = 0, total = 0;
  const statuses = {};
  const end = Date.now() + DURATION;
  async function user(i) {
    const s = ctx.sessions[i % ctx.sessions.length];
    while (Date.now() < end) {
      const roll = Math.random();
      let kind, r;
      try {
        if (roll < 0.6) {
          kind = "vitrine";
          r = await json("GET", "/api/catalog/products");
        } else if (roll < 0.85) {
          kind = "checkout";
          const productId = ctx.products[Math.floor(Math.random() * ctx.products.length)];
          r = await json("POST", "/api/transactions", { items: [{ productId, quantity: 1 }], addressId: s.addressId, deviceId: s.deviceId, declaredOrigin: "self" }, s.token);
          if (r.data?.status) statuses[r.data.status] = (statuses[r.data.status] ?? 0) + 1;
        } else {
          kind = "consulta";
          r = await json("GET", "/api/transactions", null, s.token);
        }
        total++;
        if (r.status >= 400) errors++;
        lat[kind].push(r.ms);
        lat.all.push(r.ms);
      } catch {
        total++;
        errors++;
      }
      await new Promise((ok) => setTimeout(ok, 50 + Math.random() * 150)); // tempo de "pensar"
    }
  }
  const t0 = Date.now();
  await Promise.all(Array.from({ length: users }, (_, i) => user(i)));
  const secs = (Date.now() - t0) / 1000;
  const summary = (a) => ({ n: a.length, p50: +pct(a, 50).toFixed(1), p95: +pct(a, 95).toFixed(1), p99: +pct(a, 99).toFixed(1), max: +Math.max(0, ...a).toFixed(1) });
  return {
    phase: name,
    users,
    seconds: +secs.toFixed(1),
    requests: total,
    throughput: +(total / secs).toFixed(1),
    errorRate: +((errors / Math.max(1, total)) * 100).toFixed(2),
    latency: { geral: summary(lat.all), vitrine: summary(lat.vitrine), checkout: summary(lat.checkout), consulta: summary(lat.consulta) },
    checkoutDecisions: statuses,
    targetP95: TARGET_P95[name],
    pass: pct(lat.all, 95) <= TARGET_P95[name] && errors / Math.max(1, total) < 0.01,
  };
}

const ctx = await prepare();
// aquecimento da JVM (não entra no resultado)
await phase({ name: "aquecimento", users: 5 }, { ...ctx }).catch(() => null);
const results = [];
for (const p of PHASES) {
  const r = await phase(p, ctx);
  results.push(r);
  console.log(`${r.phase.padEnd(7)} ${String(r.users).padStart(3)} usuários  ${r.requests} req  ${r.throughput} req/s  p50 ${r.latency.geral.p50} ms  p95 ${r.latency.geral.p95} ms  p99 ${r.latency.geral.p99} ms  erros ${r.errorRate}%  ${r.pass ? "OK" : "ACIMA DA META"}`);
}
const out = { api: API, at: new Date().toISOString(), durationPerPhase: DURATION / 1000, results };
const { writeFile, mkdir } = await import("node:fs/promises");
await mkdir(new URL("./results/", import.meta.url), { recursive: true });
await writeFile(new URL("./results/latest.json", import.meta.url), JSON.stringify(out, null, 2));
console.log("resultado salvo em perf/results/latest.json");
