/**
 * Servidor de desenvolvimento do frontend estático.
 * Entrega HTML, CSS e JavaScript e encaminha /api para a core-api,
 * no mesmo papel que o Vite fazia e que o nginx faz na implantação.
 *
 *   npm run dev          →  http://localhost:5173
 *   API_URL=http://localhost:8080 PORT=5173 node server.mjs
 */
import fs from "node:fs";
import http from "node:http";
import https from "node:https";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.join(root, "public");
const port = Number(process.env.PORT ?? 5173);
const api = process.env.API_URL ?? "http://localhost:8080";

const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".svg": "image/svg+xml",
  ".webp": "image/webp",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".png": "image/png",
  ".json": "application/json; charset=utf-8",
  ".woff2": "font/woff2",
  ".ico": "image/x-icon",
};

function inside(base, target) {
  const rel = path.relative(base, target);
  return rel === "" || (!rel.startsWith("..") && !path.isAbsolute(rel));
}

function resolveFile(urlPath) {
  let decoded = urlPath;
  try {
    decoded = decodeURIComponent(urlPath);
  } catch {
    return null;
  }
  const rel = decoded === "/" ? "/index.html" : decoded;
  const candidates = [path.join(root, rel), path.join(publicDir, rel)];
  if (rel.endsWith("/")) candidates.push(path.join(root, rel, "index.html"), path.join(publicDir, rel, "index.html"));
  for (const file of candidates) {
    if (!inside(root, file) && !inside(publicDir, file)) continue;
    if (fs.existsSync(file) && fs.statSync(file).isFile()) return file;
  }
  return null;
}

function fallback(urlPath) {
  if (urlPath === "/sistema" || urlPath.startsWith("/sistema/")) return path.join(root, "sistema.html");
  if (urlPath === "/loja" || urlPath.startsWith("/loja/")) return path.join(root, "loja", "index.html");
  if (urlPath === "/banco" || urlPath.startsWith("/banco/")) return path.join(root, "banco", "index.html");
  if (urlPath === "/ops" || urlPath.startsWith("/ops/")) return path.join(root, "ops", "index.html");
  return path.join(root, "index.html");
}

function sendFile(res, file) {
  const type = TYPES[path.extname(file).toLowerCase()] ?? "application/octet-stream";
  res.writeHead(200, { "Content-Type": type, "X-Content-Type-Options": "nosniff" });
  fs.createReadStream(file).pipe(res);
}

function proxy(req, res) {
  let target;
  try {
    target = new URL(req.url, api);
  } catch {
    res.writeHead(500);
    res.end();
    return;
  }
  const lib = target.protocol === "https:" ? https : http;
  const headers = { ...req.headers, host: target.host };
  delete headers.origin;
  const upstream = lib.request(target, { method: req.method, headers }, (up) => {
    res.writeHead(up.statusCode ?? 502, up.headers);
    up.pipe(res);
  });
  upstream.on("error", () => {
    if (res.headersSent) return;
    res.writeHead(502, { "Content-Type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({ status: 502, error: "Sem conexão com o servidor. Verifique se a API está no ar.", path: req.url }));
  });
  req.pipe(upstream);
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url ?? "/", "http://localhost");
  if (/^\/(api|swagger-ui|v3\/api-docs)(\/|$)/.test(url.pathname) || url.pathname === "/swagger-ui.html") {
    proxy(req, res);
    return;
  }
  if (req.method !== "GET" && req.method !== "HEAD") {
    res.writeHead(405);
    res.end();
    return;
  }
  const file = resolveFile(url.pathname) ?? fallback(url.pathname);
  if (!fs.existsSync(file)) {
    res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
    res.end("Não encontrado");
    return;
  }
  if (req.method === "HEAD") {
    res.writeHead(200, { "Content-Type": TYPES[path.extname(file).toLowerCase()] ?? "application/octet-stream" });
    res.end();
    return;
  }
  sendFile(res, file);
});

server.listen(port, () => {
  console.log(`ContextShield (frontend estático) em http://localhost:${port}`);
  console.log(`API encaminhada para ${api}`);
});
