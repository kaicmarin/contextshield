/**
 * Cliente HTTP. Cliente (loja e app) e equipe (painel) têm sessões separadas,
 * para a demonstração poder mostrar as duas telas no mesmo navegador.
 */

const KEYS = { customer: "cs.session.customer", ops: "cs.session.ops" };
const EVENT = "cs-session";

export class ApiError extends Error {
  constructor(status, message, fields = {}) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.fields = fields;
  }
}

function storage() {
  try {
    return localStorage;
  } catch {
    return null;
  }
}

export function getSession(area) {
  const box = storage();
  if (!box) return null;
  try {
    const raw = box.getItem(KEYS[area]);
    if (!raw) return null;
    const s = JSON.parse(raw);
    return new Date(s.expiresAt).getTime() > Date.now() ? s : null;
  } catch {
    return null;
  }
}

export function setSession(area, s) {
  const box = storage();
  try {
    if (!box) return;
    if (s) box.setItem(KEYS[area], JSON.stringify(s));
    else box.removeItem(KEYS[area]);
  } catch {
    /* armazenamento bloqueado */
  }
  window.dispatchEvent(new Event(EVENT));
}

export function onSession(cb) {
  window.addEventListener(EVENT, cb);
  window.addEventListener("storage", cb);
  return () => {
    window.removeEventListener(EVENT, cb);
    window.removeEventListener("storage", cb);
  };
}

export async function api(path, { method = "GET", body, area } = {}) {
  const headers = { Accept: "application/json" };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const session = area ? getSession(area) : null;
  if (session) headers.Authorization = `Bearer ${session.token}`;

  let res;
  try {
    res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new ApiError(0, "Sem conexão com o servidor. Verifique se a API está no ar.");
  }

  if (res.status === 204) return undefined;
  const data = await res.json().catch(() => null);
  if (!res.ok) {
    if (res.status === 401 && area && session) setSession(area, null);
    throw new ApiError(res.status, data?.error ?? `Erro ${res.status}`, data?.fields ?? {});
  }
  return data;
}

export async function login(area, email, password) {
  const s = await api("/api/auth/login", { method: "POST", body: { email, password } });
  if (area === "ops" && s.user.role === "CUSTOMER") {
    throw new ApiError(403, "Esta conta é de cliente. Use uma conta da equipe.");
  }
  if (area === "customer" && s.user.role !== "CUSTOMER") {
    throw new ApiError(403, "Esta conta é da equipe. Entre pelo painel de operações.");
  }
  setSession(area, s);
  return s;
}
