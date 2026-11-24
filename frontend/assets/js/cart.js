import { api, ApiError } from "./api/client.js";

const KEY = "cs.cart";
export const MAX_QTY = 10;

function load() {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) ?? "[]");
    return Array.isArray(raw) ? raw.filter((l) => l && typeof l.productId === "string" && l.quantity > 0) : [];
  } catch {
    return [];
  }
}

const listeners = new Set();
let lines = load();
let products = [];
let status = "loading";
let error = null;
let attempt = 0;

function emit() {
  for (const l of listeners) l();
}

function persist() {
  try {
    localStorage.setItem(KEY, JSON.stringify(lines));
  } catch {
    /* sem armazenamento: o carrinho vive só na memória */
  }
}

export function onCart(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export function cartState() {
  const byId = new Map(products.map((p) => [p.id, p]));
  const known = lines.map((l) => ({ l, p: byId.get(l.productId) })).filter((x) => x.p);
  const total = known.reduce((s, x) => s + x.p.price * x.l.quantity, 0);
  return {
    lines,
    products,
    byId,
    status,
    error,
    count: lines.reduce((s, l) => s + l.quantity, 0),
    total,
    maxInstallments: known.length ? Math.min(...known.map((x) => x.p.installments)) : 1,
    deliveryDays: known.length ? Math.max(...known.map((x) => x.p.deliveryDays)) : 0,
  };
}

export function addLine(productId, qty = 1) {
  const found = lines.find((l) => l.productId === productId);
  lines = found
    ? lines.map((l) => (l.productId === productId ? { ...l, quantity: Math.min(MAX_QTY, l.quantity + qty) } : l))
    : [...lines, { productId, quantity: Math.min(MAX_QTY, qty) }];
  persist();
  emit();
}

export function setQty(productId, qty) {
  lines = qty <= 0 ? lines.filter((l) => l.productId !== productId) : lines.map((l) => (l.productId === productId ? { ...l, quantity: Math.min(MAX_QTY, qty) } : l));
  persist();
  emit();
}

export function replaceLines(next) {
  lines = next.map((l) => ({ productId: l.productId, quantity: l.quantity }));
  persist();
  emit();
}

export function clearCart() {
  lines = [];
  persist();
  emit();
}

export async function loadProducts() {
  const id = ++attempt;
  status = "loading";
  error = null;
  emit();
  try {
    const p = await api("/api/catalog/products");
    if (id !== attempt) return;
    products = p;
    status = "ready";
    error = null;
  } catch (e) {
    if (id !== attempt) return;
    error = e instanceof ApiError ? e : new ApiError(0, String(e));
    status = "error";
  }
  emit();
}

export function reloadProducts() {
  return loadProducts();
}
