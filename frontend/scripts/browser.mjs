import { chromium } from "playwright";

export const BASE = process.env.BASE ?? "http://localhost:5173";

/** Abre o Chromium do Playwright (ou o indicado em CHROMIUM_PATH). */
export function launch() {
  return chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
}

export async function loginAs(page, area, quick) {
  let path = area;
  if (area === "/loja") path = `${area}/entrar`;
  await page.goto(BASE + path);
  await page.getByRole("button", { name: quick, exact: true }).click();
  const enter = page.getByRole("button", { name: "Entrar", exact: true });
  await enter.click();
  // só segue quando a sessão foi gravada e o formulário saiu da tela
  await enter.waitFor({ state: "detached" });
  await page.waitForLoadState("networkidle");
}
