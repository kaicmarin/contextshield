/**
 * Valida fallbacks SPA e estáticos via server.mjs (mesma lógica do dev; espelha nginx/Caddy).
 *   node scripts/validate-spa-routes.mjs
 */
import assert from "node:assert/strict";
import http from "node:http";
import { spawn } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const port = Number(process.env.VALIDATE_PORT ?? 19876);
const base = `http://127.0.0.1:${port}`;

const child = spawn(process.execPath, ["server.mjs"], {
  cwd: root,
  env: { ...process.env, PORT: String(port), API_URL: "http://127.0.0.1:1" },
  stdio: ["ignore", "pipe", "pipe"],
});

await new Promise((resolve, reject) => {
  const t = setTimeout(() => reject(new Error("server timeout")), 8000);
  child.stdout.on("data", (d) => {
    if (String(d).includes("localhost")) {
      clearTimeout(t);
      resolve();
    }
  });
  child.on("error", reject);
});

function get(urlPath) {
  return new Promise((resolve, reject) => {
    http
      .get(`${base}${urlPath}`, (res) => {
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () =>
          resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString("utf8"), type: res.headers["content-type"] }),
        );
      })
      .on("error", reject);
  });
}

const cases = [
  ["/mark.svg", (r) => r.status === 200 && (r.type?.includes("svg") || r.body.includes("<svg"))],
  ["/previews/loja.png", (r) => r.status === 200 && (r.type?.includes("image") || r.body.length > 100)],
  ["/products/notebook-graphite.jpg", (r) => r.status === 200 && (r.type?.includes("image") || r.body.length > 100)],
  ["/assets/js/store.js?v=2", (r) => r.status === 200 && r.body.includes("mount")],
  ["/loja/ajuda", (r) => r.status === 200 && r.body.includes("store.js") && !r.body.includes("home.js")],
  ["/loja/produto/prod-001", (r) => r.status === 200 && r.body.includes("store.js")],
  ["/banco/relatar", (r) => r.status === 200 && r.body.includes("bank.js")],
  ["/ops/transacoes", (r) => r.status === 200 && r.body.includes("ops.js")],
  ["/", (r) => r.status === 200 && r.body.includes("home.js")],
  ["/sistema", (r) => r.status === 200 && r.body.includes("system.js")],
];

let ok = 0;
for (const [p, pred] of cases) {
  const r = await get(p);
  assert.ok(pred(r), `${p} status=${r.status}`);
  console.log("✓", p);
  ok += 1;
}

child.kill("SIGTERM");
console.log(`validate-spa-routes: ${ok}/${cases.length} ok`);