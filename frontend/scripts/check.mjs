/** Confere a sintaxe de todos os módulos do frontend estático. */
import { readdirSync, statSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { join } from "node:path";

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const file = join(dir, name);
    if (statSync(file).isDirectory()) walk(file, out);
    else if (file.endsWith(".js")) out.push(file);
  }
  return out;
}

const files = [...walk("assets/js"), "server.mjs", "scripts/unit.mjs", "scripts/smoke.mjs", "scripts/e2e.mjs", "scripts/a11y.mjs", "scripts/browser.mjs"];
let failed = 0;
for (const file of files) {
  const result = spawnSync(process.execPath, ["--check", file], { stdio: "inherit" });
  if (result.status) failed += 1;
}
if (failed) {
  console.error(`${failed} arquivo(s) com erro de sintaxe`);
  process.exit(1);
}
console.log(`check: ${files.length} arquivos ok`);
