import { api, getSession, onSession, setSession } from "./api/client.js";
import { createRouter } from "./router.js";
import { button, changePasswordForm, empty, errorNotice, field, loginForm, pill, statusPill } from "./ui.js";
import { el, reducedMotion } from "./utils/dom.js";
import { brl, CHANNEL, dateTime, INCIDENT_STATUS, INCIDENT_TYPE, relative, setTitle, STATUS, STATUS_ICON, time } from "./utils/format.js";
import { icon } from "./utils/icons.js";

const HIDE_KEY = "aureon.ocultarValores";
const SEEN_KEY = "aureon.notificados";
const TABS = [
  { to: "/banco", label: "Início", icon: "home", match: (p) => p === "/banco" },
  { to: "/banco/compras", label: "Compras", icon: "receipt", match: (p) => p.startsWith("/banco/compras") },
  { to: "/banco/confira", label: "Confira", icon: "confira", match: (p) => p.startsWith("/banco/confira") },
  { to: "/banco/relatar", label: "Fui vítima", icon: "flag", match: (p) => p.startsWith("/banco/relatar") },
];
const LEVEL = { GREEN: "ok", YELLOW: "warn", RED: "stop", GRAY: "muted" };
const LEVEL_ICON = { GREEN: "check", YELLOW: "alert", RED: "stop", GRAY: "info" };
const EXAMPLES = [
  { label: "Falsa central", text: "Olá, aqui é da central de segurança do seu banco. Detectamos uma compra suspeita. Para cancelar, compre o notebook no link e entregue na Rua Pedra Lisa, 880. Não conte a ninguém." },
  { label: "Link de entrega", text: "Sua encomenda está retida. Pague a taxa de R$ 12,90 em bit.ly/entrega-retida hoje ou ela volta ao remetente." },
  { label: "Mensagem normal", text: "Oi! Seu pedido NEXA saiu para entrega e chega amanhã entre 9h e 18h." },
];
const FIRST_STEPS = [
  ["phoneCall", "Ligue para a central", "0800 000 0000, 24 horas. Use só o número do verso do cartão."],
  ["lock", "Não faça mais nada que pedirem", "Nenhuma central pede compra, Pix ou código para “cancelar” algo."],
  ["flag", "Registre o relato aqui", "O telefone, o link e o endereço que você informar bloqueiam o golpe para outros clientes."],
];
const DECISION = {
  APPROVED: ["Aprovamos na hora", "Nada nesta compra fugiu do seu padrão."],
  APPROVED_WITH_ALERT: ["Aprovamos, com um aviso", "A compra passou, mas alguns detalhes fogem do seu padrão. Se alguém pediu esta compra por telefone ou mensagem, fale com a central oficial."],
  CONTEXT_REQUIRED: ["Esperando a sua confirmação", "Antes de aprovar, queremos entender como a compra começou. Responda as perguntas na loja para seguir."],
  INTERVENTION: ["Pausamos para sua proteção", "As respostas indicam que alguém pode estar orientando esta compra. Ela só segue com a sua decisão."],
  BLOCKED: ["Interrompemos esta compra", "Os sinais apontam para um golpe em andamento. A compra não foi concluída."],
  CANCELLED: ["Compra cancelada", "A compra não foi concluída."],
  INCIDENT_RECORDED: ["Golpe relatado", "Você relatou esta compra. Um analista acompanha o caso."],
  ANALYZING: ["Em análise", "Estamos analisando esta compra agora."],
  RECEIVED: ["Recebida", "A compra chegou e vai ser analisada em seguida."],
};
const NOT_CHARGED = ["BLOCKED", "CANCELLED"];

let hidden = localStorage.getItem(HIDE_KEY) === "1";
let me = null;
let meError = null;
let alerts = [];
let unread = 0;
let sheet = null;
let toastText = "";
let router;
const stage = el("div", { class: "az-stage" });
const screen = el("div", { class: "az-screen" });
const overlays = el("div", { class: "az-overlays", "aria-hidden": "true" });
const toasts = el("div", { class: "az-toasts", role: "status", "aria-live": "polite" });
const pageHost = el("div");

/** Overlays vazios ficam fora da árvore de acessibilidade; com dialog ativo, voltam a ser expostos. */
function syncOverlayA11y() {
  if (overlays.childElementCount > 0) overlays.removeAttribute("aria-hidden");
  else overlays.setAttribute("aria-hidden", "true");
}

function session() {
  return getSession("customer");
}
function go(url, opts) {
  return router.go(url, opts);
}
function money(value, className = "") {
  return el(
    "span",
    { class: `az-money num ${className}`.trim() },
    hidden
      ? el("span", {}, el("span", { "aria-hidden": "true" }, "R$ ••••"), el("span", { class: "sr-only" }, "valor oculto"))
      : brl(value),
  );
}
function toast(text, tone) {
  toastText = text;
  const node = el("div", { class: `az-toast${tone ? ` tone-${tone}` : ""}` }, icon(tone === "stop" ? "alert" : "check", { size: 18 }), text);
  toasts.append(node);
  setTimeout(() => node.classList.add("leaving"), 3200);
  setTimeout(() => node.remove(), 3400);
}
function alertTone(kind) {
  return kind === "danger" ? "stop" : kind === "warning" ? "warn" : kind === "action" ? "info" : "muted";
}
function alertIcon(kind) {
  return kind === "danger" ? "stop" : kind === "warning" ? "alert" : kind === "action" ? "question" : "bell";
}

function appMark(size = 40) {
  return el(
    "svg",
    { width: String(size), height: String(size), viewBox: "0 0 40 40", "aria-hidden": "true", class: "az-appmark" },
    el("rect", { width: "40", height: "40", rx: "10", fill: "#0e4f4f" }),
    el("path", { d: "M11.5 29 20 10.5 28.5 29", fill: "none", stroke: "#e2b552", "stroke-width": "2.6", "stroke-linecap": "round", "stroke-linejoin": "round" }),
    el("path", { d: "M14.6 23.4c3.3-2.5 7.5-2.5 10.8 0", fill: "none", stroke: "#e2b552", "stroke-width": "2.2", "stroke-linecap": "round" }),
  );
}
function wordmark(className = "") {
  return el(
    "span",
    { class: `az-wordmark ${className}`.trim(), translate: "no" },
    el("svg", { width: "22", height: "22", viewBox: "0 0 24 24", "aria-hidden": "true" }, el("path", { d: "M5 20.5 12 4l7 16.5", fill: "none", stroke: "currentColor", "stroke-width": "2.1", "stroke-linecap": "round", "stroke-linejoin": "round" }), el("path", { d: "M7.6 15.6c2.6-2 6.2-2 8.8 0", fill: "none", stroke: "currentColor", "stroke-width": "1.8", "stroke-linecap": "round" })),
    "Aureon",
  );
}

async function refreshMe() {
  if (!session()) {
    me = null;
    return;
  }
  try {
    me = await api("/api/me", { area: "customer" });
    meError = null;
  } catch (e) {
    meError = e;
  }
}
async function refreshAlerts() {
  if (!session()) return;
  try {
    alerts = await api("/api/alerts", { area: "customer" });
    const count = await api("/api/alerts/unread-count", { area: "customer" });
    unread = count.unread ?? alerts.filter((a) => !a.readAt).length;
  } catch {
    /* a lista anterior permanece */
  }
}
async function markRead(a) {
  if (a.readAt) return;
  const r = await api(`/api/alerts/${a.id}/read`, { method: "PATCH", area: "customer" }).catch(() => null);
  if (!r) return;
  alerts = alerts.map((x) => (x.id === a.id ? r : x));
  unread = Math.max(0, unread - 1);
}
function openAlert(a) {
  sheet = a;
  markRead(a).then(() => paintShell());
  paintShell();
}

function topBar({ title, back, large, sub, right }) {
  const backEl = !back
    ? null
    : back.to
      ? el("a", { href: back.to, class: "az-back" }, icon("chevron", { size: 22, className: "flip" }), back.label)
      : el("button", { type: "button", class: "az-back", onClick: back.onClick }, icon("chevron", { size: 22, className: "flip" }), back.label);
  return el(
    "div",
    {},
    el("div", { class: "az-bar", "data-stuck": large ? "false" : "true" }, el("div", { class: "az-bar-side" }, backEl), large ? el("span", { class: "az-bar-title", "aria-hidden": "true" }, title) : el("h1", { class: "az-bar-title" }, title), el("div", { class: "az-bar-side end" }, right ?? null)),
    large ? el("div", { class: "az-large" }, el("h1", {}, title), sub ? el("p", { class: "az-large-sub" }, sub) : null) : null,
  );
}
function bell() {
  return el(
    "a",
    { href: "/banco/avisos", class: "az-icon-btn az-bell", "aria-label": unread ? `Avisos, ${unread} ${unread === 1 ? "não lido" : "não lidos"}` : "Avisos" },
    icon("bell", { size: 22 }),
    unread > 0 ? el("span", { class: "az-badge num", "aria-hidden": "true" }, unread > 9 ? "9+" : String(unread)) : null,
  );
}
function rowsSkeleton(n, label) {
  return el("div", { class: "az-group", "aria-busy": "true", "aria-label": label }, Array.from({ length: n }, () => el("div", { class: "az-row az-row-skel" }, el("span", { class: "skeleton az-skel-dot" }), el("span", { class: "az-row-main" }, el("span", { class: "skeleton", style: { width: "60%", height: "14px" } }), el("span", { class: "skeleton", style: { width: "40%", height: "12px" } })))));
}
function emptyState(iconName, title, text, action) {
  return el("div", { class: "az-empty" }, el("span", { class: "az-empty-ic", "aria-hidden": "true" }, icon(iconName, { size: 26 })), el("h2", {}, title), text ? el("p", {}, text) : null, action ?? null);
}

function paintChrome(path) {
  const s = session();
  const tone = s ? (path === "/banco" ? "brand" : "paper") : path.startsWith("/banco/confira") ? "paper" : "brand";
  screen.dataset.tone = tone;
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.content = tone === "brand" ? "#0e4f4f" : "#f6f4ee";
  const now = new Date();
  const clock = `${now.getHours()}:${String(now.getMinutes()).padStart(2, "0")}`;
  const status = screen.querySelector(".az-status") ?? el("div", { class: "az-status", "aria-hidden": "true" });
  status.replaceChildren(el("span", { class: "az-status-time num" }, clock));
  if (!status.parentElement) screen.prepend(status);
}

function paintShell() {
  const path = location.pathname.replace(/\/$/, "") || "/banco";
  paintChrome(path);
  const hideTabs = /^\/banco\/relatar\/(novo|[^/]+\/editar)/.test(path);
  const signed = !!session();
  const showTabs = signed && !hideTabs;
  const idx = TABS.findIndex((t) => t.match(path));
  const app = el(
    "div",
    { class: "az-app", "data-az-app": "", "data-tabs": showTabs ? "true" : "false" },
    el("main", { class: "az-scroll" }, el("div", { class: "az-page" }, pageHost)),
    showTabs
      ? el(
          "nav",
          { class: "az-tabs", "aria-label": "Seções do app" },
          el("span", { class: "az-tabs-ink", "aria-hidden": "true", "data-off": idx < 0 ? "true" : null, style: { transform: `translateX(${Math.max(0, idx) * 100}%)` } }),
          TABS.map((t) => el("a", { href: t.to, class: `az-tab${t.match(path) ? " active" : ""}`, "aria-current": t.match(path) ? "page" : null }, el("span", { class: "az-tab-ic" }, icon(t.icon, { size: 24 })), el("span", { class: "az-tab-label" }, t.label))),
        )
      : null,
  );
  const keep = screen.querySelector(".az-status");
  screen.replaceChildren(keep ?? el("div", { class: "az-status", "aria-hidden": "true" }), app, overlays, el("span", { class: "az-island", "aria-hidden": "true" }), el("span", { class: "az-homebar", "aria-hidden": "true" }));
  overlays.replaceChildren(sheet ? alertSheet() : null);
  syncOverlayA11y();
  if (!toasts.parentElement) screen.querySelector(".az-app")?.append(toasts);
  paintChrome(path);
}

function alertSheet() {
  const a = sheet;
  const tone = alertTone(a.kind);
  const phone = a.body.match(/0800[\d\s]{7,}\d/)?.[0].trim();
  return el(
    "div",
    { class: "az-sheet-layer", onKeydown: (e) => e.key === "Escape" && closeSheet() },
    el("div", { class: "az-backdrop", onClick: closeSheet }),
    el(
      "div",
      { class: `az-sheet tone-${tone}`, role: "dialog", "aria-modal": "true" },
      el("span", { class: "az-grabber", "aria-hidden": "true" }),
      el("div", { class: "az-sheet-head" }, el("h2", {}, a.title), el("button", { type: "button", class: "az-icon-btn az-sheet-x", "aria-label": "Fechar", onClick: closeSheet }, icon("x", { size: 20 }))),
      el(
        "div",
        { class: "az-sheet-body" },
        el("p", { class: "az-alert-body" }, a.body),
        el(
          "div",
          { class: "az-sheet-actions" },
          a.txId ? button({ variant: "primary", size: "lg", block: true, onClick: () => { closeSheet(); go(`/banco/compras/${a.txId}`); } }, "Ver a compra") : null,
          phone ? el("a", { class: "btn btn-lg btn-block", href: `tel:${phone.replace(/\s/g, "")}` }, icon("phoneCall", { size: 20 }), ` Ligar para ${phone}`) : null,
          button({ variant: "quiet", block: true, class: "az-text-danger", onClick: () => removeAlert(a) }, icon("trash", { size: 18 }), " Apagar aviso"),
        ),
      ),
    ),
  );
}
function closeSheet() {
  sheet = null;
  paintShell();
}
async function removeAlert(a) {
  closeSheet();
  try {
    await api(`/api/alerts/${a.id}`, { method: "DELETE", area: "customer" });
    alerts = alerts.filter((x) => x.id !== a.id);
    toast("Aviso removido");
    router.start();
  } catch {
    toast("Não deu para remover o aviso. Tente de novo.", "stop");
  }
}

function login() {
  setTitle("Entrar · Aureon");
  return el(
    "div",
    { class: "az-login" },
    el("div", { class: "az-login-hero" }, appMark(56), wordmark("az-login-word"), el("p", {}, "Seu banco, atento ao contexto de cada compra.")),
    el(
      "div",
      { class: "az-login-card" },
      loginForm({
        area: "customer",
        heading: el("h1", { class: "az-login-h" }, "Entre na sua conta"),
        suggestions: [
          { label: "Ana", email: "ana.martins@correio.example" },
          { label: "Mariana", email: "mariana.rocha@correio.example" },
          { label: "Pedro", email: "pedro.alves@correio.example" },
        ],
        onDone: () => go("/banco"),
      }),
      el(
        "a",
        { href: "/banco/confira", class: "az-row az-row-link az-login-check" },
        el("span", { class: "icon-dot tone-info", "aria-hidden": "true" }, icon("confira", { size: 20 })),
        el("span", { class: "az-row-main" }, el("span", { class: "az-row-title" }, "Recebeu uma mensagem estranha?"), el("span", { class: "az-row-sub" }, "Confira antes de pagar. Não precisa entrar.")),
        icon("chevron", { size: 20, className: "az-chev" }),
      ),
    ),
  );
}

function home() {
  setTitle("Aureon");
  const s = session();
  const box = el("div");
  const customer = me?.customer;
  const card = me?.cards?.[0];
  const first = customer?.firstName ?? s?.user.name.split(" ")[0] ?? "";
  const initials = customer?.initials ?? (s?.user.name ?? "").split(" ").map((p) => p[0]).slice(0, 2).join("");
  box.append(
    el(
      "header",
      { class: "az-home-head" },
      el(
        "div",
        { class: "az-home-top" },
        el("span", { class: "az-avatar", "aria-hidden": "true" }, initials),
        el(
          "div",
          { class: "az-home-actions" },
          bell(),
          el("button", { type: "button", class: "az-icon-btn on-brand", "aria-label": "Sair", onClick: () => { setSession("customer", null); go("/banco"); } }, icon("out", { size: 22 })),
        ),
      ),
      el("h1", { class: "az-hello" }, `Olá, ${first}`),
      el("p", { class: "az-hello-sub" }, customer ? `Cliente desde ${customer.since.slice(0, 4)}` : " "),
      el("a", { href: "/banco/senha", class: "az-link small" }, "Alterar senha"),
    ),
    el(
      "div",
      { class: "az-content az-home-body" },
      el("div", { class: "az-cardsec", id: "az-card" }, meError ? errorNotice(meError, () => refreshMe().then(() => router.start())) : card ? cardArt(card) : el("div", { class: "az-card az-card-skel", "aria-busy": "true" })),
      quick(),
      el("section", { id: "az-last" }, el("div", { class: "az-sec-head" }, el("h2", { id: "h-ultimas", class: "az-h2" }, "Últimas compras"), el("a", { href: "/banco/compras", class: "az-link" }, "Ver todas")), rowsSkeleton(4, "Carregando compras")),
    ),
  );
  api("/api/transactions", { area: "customer" }).then((txs) => {
    const host = box.querySelector("#az-last");
    if (!host) return;
    const pending = (txs ?? []).filter((t) => t.status === "CONTEXT_REQUIRED" || t.status === "INTERVENTION");
    const latest = alerts.filter((a) => !a.readAt).sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0];
    const list = el(
      "section",
      { "aria-labelledby": "h-ultimas" },
      pending.map((t) =>
        el(
          "div",
          { class: `notice tone-${STATUS[t.status]?.tone ?? "muted"}` },
          icon("info", { size: 20 }),
          el("div", {}, el("strong", {}, STATUS[t.status]?.customer ?? t.status), el("div", { class: "az-pending" }, el("span", {}, t.description, ", ", money(t.amount)), el("a", { href: `/loja/pedido/${t.id}`, class: "btn btn-sm" }, "Resolver agora"))),
        ),
      ),
      latest
        ? el("button", { type: "button", class: "az-alert-row", onClick: () => openAlert(latest) }, el("span", { class: `icon-dot tone-${alertTone(latest.kind)}` }, icon(alertIcon(latest.kind), { size: 18 })), el("span", { class: "az-row-main" }, el("span", { class: "az-row-title" }, latest.title), el("span", { class: "az-row-sub" }, el("span", { class: "az-new" }, "Aviso novo"), ` · ${relative(latest.createdAt)}`)), icon("chevron", { size: 20, className: "az-chev" }))
        : null,
      el("div", { class: "az-sec-head" }, el("h2", { id: "h-ultimas", class: "az-h2" }, "Últimas compras"), el("a", { href: "/banco/compras", class: "az-link" }, "Ver todas")),
      !(txs ?? []).length
        ? emptyState("receipt", "Nenhuma compra ainda", "Quando você usar o cartão, a compra aparece aqui em segundos.")
        : el("div", { class: "az-group" }, txs.slice(0, 4).map((t) => txRow(t, relative(t.createdAt)))),
    );
    host.replaceWith(list);
  }).catch((e) => {
    box.querySelector("#az-last")?.replaceChildren(errorNotice(e, () => router.start()));
  });
  return box;
}

function cardArt(card) {
  const used = Math.max(0, card.creditLimit - card.available);
  const share = card.creditLimit > 0 ? Math.min(1, used / card.creditLimit) : 0;
  const tier = card.product.replace(/^Aureon\s*/i, "") || card.product;
  return el(
    "div",
    {},
    el(
      "div",
      { class: "az-card-wrap" },
      el(
        "div",
        { class: "az-card", role: "img", "aria-label": `Cartão ${card.product}, ${card.kind.toLowerCase()}, final ${card.last4}, em nome de ${card.holder}` },
        el("div", { class: "az-card-row" }, wordmark("az-card-brand"), el("span", { class: "az-card-tier" }, tier)),
        el("div", { class: "az-card-row end" }, el("span", { class: "az-card-id" }, el("span", { class: "az-card-num num" }, `•••• ${card.last4}`), el("span", { class: "az-card-holder" }, card.holder)), el("span", { class: "az-card-meta" }, el("span", {}, card.kind), el("span", { class: "num" }, card.expiry))),
      ),
    ),
    el(
      "section",
      { class: "az-limit", "aria-labelledby": "h-limite" },
      el("div", { class: "az-limit-top" }, el("h2", { id: "h-limite", class: "az-limit-label" }, "Limite disponível"), el("button", { type: "button", class: "az-icon-btn", "aria-pressed": hidden ? "true" : "false", "aria-label": hidden ? "Mostrar valores" : "Ocultar valores", onClick: () => { hidden = !hidden; localStorage.setItem(HIDE_KEY, hidden ? "1" : "0"); router.start(); } }, icon(hidden ? "eyeOff" : "eye", { size: 22 }))),
      money(card.available, "az-limit-value"),
      el("div", { class: "az-meter", "aria-hidden": "true" }, el("span", { style: { transform: `scaleX(${share})` } })),
      el("p", { class: "az-limit-foot" }, el("span", {}, "Usado ", money(used)), el("span", {}, "Limite total ", money(card.creditLimit))),
    ),
  );
}
function quick() {
  const items = [
    ["/banco/confira", "Confira antes de pagar", "confira"],
    ["/banco/relatar", "Fui vítima", "flag"],
    ["/banco/compras", "Compras", "receipt"],
    ["/banco/avisos", "Avisos", "bell", unread],
  ];
  return el(
    "nav",
    { class: "az-quick", "aria-label": "Atalhos" },
    items.map(([to, label, ic, badge]) =>
      el(
        "a",
        { href: to, class: "az-quick-item", "aria-label": badge ? `${label}, ${badge} ${badge === 1 ? "não lido" : "não lidos"}` : null },
        el("span", { class: "az-quick-ic" }, icon(ic, { size: 24 }), badge ? el("span", { class: "az-badge num on-surface", "aria-hidden": "true" }, badge > 9 ? "9+" : String(badge)) : null),
        el("span", { class: "az-quick-label" }, label),
      ),
    ),
  );
}
function txRow(t, when) {
  const tone = STATUS[t.status]?.tone ?? "muted";
  return el(
    "a",
    { href: `/banco/compras/${t.id}`, class: `az-row az-row-link tone-${tone}` },
    el("span", { class: `icon-dot tone-${tone}`, "aria-hidden": "true" }, icon(STATUS_ICON[t.status] ?? "bag", { size: 18 })),
    el("span", { class: "az-row-main" }, el("span", { class: "az-row-title" }, t.description), el("span", { class: "az-row-sub" }, el("span", { class: t.status === "APPROVED" ? null : "az-tone-ink" }, STATUS[t.status]?.customer ?? t.status), ` · ${when ?? time(t.createdAt)}`)),
    money(t.amount, NOT_CHARGED.includes(t.status) ? "struck" : ""),
  );
}

function purchases() {
  setTitle("Compras · Aureon");
  const card = me?.cards?.[0];
  const box = el("div", {}, topBar({ title: "Compras", large: true, sub: card ? `${card.product}, final ${card.last4}` : "Cartão de crédito", right: bell() }), el("div", { class: "az-content" }, rowsSkeleton(4, "Carregando compras")));
  api("/api/transactions", { area: "customer" }).then((txs) => {
    const content = box.querySelector(".az-content");
    if (!txs?.length) {
      content.replaceChildren(emptyState("receipt", "Nenhuma compra ainda", "As compras feitas com o seu cartão aparecem aqui, com o que a análise de segurança decidiu."));
      return;
    }
    const groups = [];
    for (const t of [...txs].sort((a, b) => b.createdAt.localeCompare(a.createdAt))) {
      const d = new Date(t.createdAt);
      const key = `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`;
      const g = groups.at(-1);
      if (g && g.key === key) g.items.push(t);
      else groups.push({ key, label: dayLabel(d), items: [t] });
    }
    content.replaceChildren(
      ...groups.map((g) =>
        el("section", { class: "az-day", "aria-labelledby": `dia-${g.key}` }, el("h2", { class: "az-day-head", id: `dia-${g.key}` }, el("span", {}, g.label), el("span", { class: "az-day-count num" }, `${g.items.length} ${g.items.length === 1 ? "compra" : "compras"}`)), el("div", { class: "az-group" }, g.items.map((t) => txRow(t)))),
      ),
    );
  }).catch((e) => box.querySelector(".az-content")?.replaceChildren(errorNotice(e, () => router.start())));
  return box;
}
function dayLabel(d) {
  const today = new Date();
  const y = new Date();
  y.setDate(today.getDate() - 1);
  const same = (a, b) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
  if (same(d, today)) return "Hoje";
  if (same(d, y)) return "Ontem";
  const s = new Intl.DateTimeFormat("pt-BR", { weekday: "long", day: "numeric", month: "long" }).format(d);
  return s.charAt(0).toUpperCase() + s.slice(1);
}

function purchase(id) {
  setTitle("Compra · Aureon");
  const box = el("div", {}, topBar({ title: "Compra", back: { to: "/banco/compras", label: "Compras" } }), el("div", { class: "az-content" }, el("div", { class: "az-detail-skel", "aria-busy": "true", "aria-label": "Carregando compra" }, el("span", { class: "skeleton", style: { width: "70%", height: "22px" } }))));
  api(`/api/transactions/${id}`, { area: "customer" }).then((t) => {
    const tone = STATUS[t.status]?.tone ?? "muted";
    const d = DECISION[t.status];
    const card = me?.cards?.[0];
    box.querySelector(".az-content").replaceChildren(
      el("section", { class: "az-tx-hero", "aria-label": "Resumo da compra" }, el("span", { class: "az-tx-merchant" }, icon("store", { size: 18 }), t.merchant), el("h2", { class: "az-tx-desc" }, t.description), money(t.amount, `az-tx-amount${NOT_CHARGED.includes(t.status) ? " struck" : ""}`), el("span", { class: "az-tx-meta" }, statusPill(t.status, { customer: true }), el("span", { class: "small muted" }, dateTime(t.createdAt)))),
      d
        ? el(
            "section",
            { class: `az-decision tone-${tone}`, "aria-labelledby": "h-decisao" },
            el("div", { class: "az-decision-head" }, el("span", { class: `icon-dot tone-${tone}` }, icon(STATUS_ICON[t.status] === "check" ? "shield" : STATUS_ICON[t.status] ?? "shield", { size: 20 })), el("div", { class: "az-decision-text" }, el("h2", { id: "h-decisao" }, d[0]), el("p", {}, d[1]))),
            t.reasons?.length
              ? el("div", { class: "az-reasons-wrap" }, el("h3", { class: "az-h3" }, "O que pesou na análise"), el("ul", { class: "az-reasons" }, t.reasons.map((r) => el("li", {}, icon("info", { size: 18 }), el("span", {}, el("span", { class: "az-reason-label" }, r.label), el("span", { class: "az-reason-detail num" }, r.detail))))))
              : null,
            el("p", { class: "az-footnote" }, icon("shield", { size: 16 }), " Análise do ContextShield, feita no momento da compra."),
          )
        : null,
      t.status === "CONTEXT_REQUIRED" || t.status === "INTERVENTION" ? el("a", { href: `/loja/pedido/${t.id}`, class: "btn btn-primary btn-lg btn-block" }, "Resolver esta compra") : null,
      el(
        "section",
        { "aria-labelledby": "h-detalhes" },
        el("h2", { id: "h-detalhes", class: "az-h2 az-sec-title" }, "Detalhes"),
        el(
          "dl",
          { class: "az-kv" },
          el("div", {}, el("dt", {}, "Loja"), el("dd", {}, t.merchant)),
          t.items?.length ? el("div", {}, el("dt", {}, "Itens"), el("dd", {}, t.items.map((it) => el("span", { class: "az-kv-line" }, `${it.quantity}× ${it.name}`)))) : null,
          el("div", {}, el("dt", {}, "Entrega"), el("dd", {}, `${t.address.line1}${t.address.line2 ? `, ${t.address.line2}` : ""}${t.address.city ? ` · ${t.address.city}` : ""}`)),
          card ? el("div", {}, el("dt", {}, "Cartão"), el("dd", {}, `${card.product}, final ${card.last4}`)) : null,
          el("div", {}, el("dt", {}, "Data"), el("dd", {}, dateTime(t.createdAt))),
          el("div", {}, el("dt", {}, "Protocolo"), el("dd", { class: "mono", translate: "no" }, t.id)),
        ),
      ),
      t.status !== "INCIDENT_RECORDED" ? el("a", { href: `/banco/relatar/novo?tx=${t.id}`, class: "btn btn-lg btn-block" }, icon("flag", { size: 20 }), " Não fui eu ou fui enganado") : null,
    );
  }).catch((e) => box.querySelector(".az-content")?.replaceChildren(errorNotice(e, () => router.start())));
  return box;
}

function alertsPage() {
  setTitle("Avisos · Aureon");
  const sorted = [...alerts].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  return el(
    "div",
    {},
    topBar({
      title: "Avisos",
      large: true,
      back: { to: "/banco", label: "Início" },
      sub: unread ? `${unread} ${unread === 1 ? "aviso não lido" : "avisos não lidos"}` : "Tudo em dia",
      right: unread > 0 ? el("button", { type: "button", class: "az-bar-action", onClick: async () => { await Promise.all(alerts.filter((a) => !a.readAt).map(markRead)); router.start(); } }, "Ler todos") : null,
    }),
    el(
      "div",
      { class: "az-content" },
      sorted.length
        ? el("ul", { class: "az-group az-alerts", role: "list" }, sorted.map((a) => el("li", {}, el("button", { type: "button", class: `az-row az-row-link az-alert${a.readAt ? "" : " unread"}`, onClick: () => openAlert(a) }, el("span", { class: `icon-dot tone-${alertTone(a.kind)}` }, icon(alertIcon(a.kind), { size: 18 })), el("span", { class: "az-row-main" }, el("span", { class: "az-row-title" }, !a.readAt ? el("span", { class: "az-dot", "aria-label": "Não lido" }) : null, a.title), el("span", { class: "az-row-body" }, a.body), el("span", { class: "az-row-sub" }, relative(a.createdAt))), icon("chevron", { size: 20, className: "az-chev" })))))
        : emptyState("bell", "Nenhum aviso", "Quando uma compra precisar da sua atenção, o aviso chega aqui e no topo da tela."),
    ),
  );
}

function reports() {
  setTitle("Fui vítima · Aureon");
  const box = el(
    "div",
    {},
    topBar({ title: "Fui vítima", large: true, sub: "Aja rápido: cada minuto conta.", right: bell() }),
    el(
      "div",
      { class: "az-content" },
      el(
        "section",
        { class: "az-sos", "aria-labelledby": "h-sos" },
        el("h2", { id: "h-sos", class: "az-sos-h" }, "Caiu num golpe ou desconfia de um?"),
        el("ol", { class: "az-sos-steps" }, FIRST_STEPS.map((s, i) => el("li", { style: { "--i": String(i) } }, el("span", { class: "az-sos-ic", "aria-hidden": "true" }, icon(s[0], { size: 20 })), el("span", {}, el("strong", {}, s[1]), el("span", {}, s[2]))))),
        el("div", { class: "az-sos-actions" }, el("a", { href: "/banco/relatar/novo", class: "btn btn-primary btn-lg btn-block" }, icon("flag", { size: 20 }), " Registrar um relato"), el("a", { href: "tel:08000000000", class: "btn btn-lg btn-block az-btn-ghost" }, icon("phoneCall", { size: 20 }), " Ligar para a central")),
      ),
      el("section", { id: "az-reports", "aria-labelledby": "h-relatos" }, el("h2", { id: "h-relatos", class: "az-h2" }, "Seus relatos"), rowsSkeleton(2, "Carregando relatos")),
    ),
  );
  api("/api/incidents", { area: "customer" }).then((list) => paintReports(box, list)).catch((e) => box.querySelector("#az-reports")?.append(errorNotice(e, () => router.start())));
  return box;
}
function paintReports(box, list) {
  const host = box.querySelector("#az-reports");
  if (!host) return;
  host.replaceChildren(
    el("div", { class: "az-sec-head" }, el("h2", { id: "h-relatos", class: "az-h2" }, "Seus relatos"), list.length ? el("span", { class: "az-count num" }, String(list.length)) : null),
    list.length
      ? el(
          "ul",
          { class: "az-reports", role: "list" },
          list.map((i) => {
            const st = INCIDENT_STATUS[i.status] ?? { label: i.status, tone: "muted" };
            return el(
              "li",
              { class: "az-report" },
              el("div", { class: "az-report-top" }, el("span", { class: "mono az-report-id", translate: "no" }, i.id), pill(st.tone, st.label)),
              el("p", { class: "az-report-type" }, INCIDENT_TYPE[i.type] ?? i.type),
              el("p", { class: "az-row-sub" }, `${CHANNEL[i.channel] ?? i.channel} · ${relative(i.createdAt)}`),
              progress(i.status),
              i.status === "CLOSED" && i.closeNote ? el("p", { class: "az-report-note" }, i.closeNote) : null,
              i.status === "OPEN"
                ? el(
                    "div",
                    { class: "az-report-actions" },
                    el("a", { href: `/banco/relatar/${i.id}/editar`, class: "btn btn-sm" }, icon("edit", { size: 16 }), " Editar"),
                    button({ size: "sm", variant: "quiet", class: "az-text-danger", onClick: () => confirmDelete(i, list) }, icon("trash", { size: 16 }), " Apagar"),
                  )
                : null,
            );
          }),
        )
      : emptyState("flag", "Nenhum relato", "Se algo acontecer, registre aqui. Seu relato protege outros clientes."),
  );
}
function progress(status) {
  const steps = ["Enviado", "Em análise", "Encerrado"];
  const at = status === "CLOSED" ? 2 : status === "IN_REVIEW" ? 1 : 0;
  return el("ol", { class: "az-progress", "aria-label": `Andamento: ${steps[at]}` }, steps.map((s, i) => el("li", { "data-on": i <= at ? "true" : null, "data-now": i === at ? "true" : null }, el("span", { "aria-hidden": "true" }), s)));
}
function confirmDelete(incident, list) {
  sheet = null;
  const layer = el(
    "div",
    { class: "az-sheet-layer" },
    el("div", {
      class: "az-backdrop",
      onClick: () => {
        layer.remove();
        syncOverlayA11y();
      },
    }),
    el(
      "div",
      { class: "az-sheet tone-stop", role: "dialog", "aria-modal": "true" },
      el("div", { class: "az-sheet-head" }, el("h2", {}, "Apagar este relato?")),
      el(
        "div",
        { class: "az-sheet-body" },
        el("p", { class: "az-alert-body" }, "O relato ", el("span", { class: "mono" }, incident.id), " ainda não foi analisado. Depois de apagar, ele deixa de proteger outros clientes e não dá para desfazer."),
        el(
          "div",
          { class: "az-sheet-actions" },
          button(
            {
              variant: "danger",
              size: "lg",
              block: true,
              onClick: async () => {
                await api(`/api/incidents/${incident.id}`, { method: "DELETE", area: "customer" });
                toast("Relato apagado");
                layer.remove();
                syncOverlayA11y();
                paintReports(document.querySelector("#az-reports")?.closest(".az-page")?.firstElementChild ?? document.body, list.filter((x) => x.id !== incident.id));
                router.start();
              },
            },
            "Apagar relato",
          ),
          button({
            size: "lg",
            block: true,
            onClick: () => {
              layer.remove();
              syncOverlayA11y();
            },
          }, "Manter"),
        ),
      ),
    ),
  );
  overlays.append(layer);
  syncOverlayA11y();
}

function reportForm(id, search) {
  setTitle(id ? "Editar relato · Aureon" : "Novo relato · Aureon");
  const form = { type: "", channel: "", description: "", contactHandle: "", contactLink: "", destinationText: "", consent: !!id, txId: search.get("tx") ?? "" };
  let options = null;
  let txs = [];
  let error = null;
  let busy = false;
  const box = el("div");
  function draw() {
    const f = error?.fields ?? {};
    box.replaceChildren(
      topBar({ title: id ? "Editar relato" : "Novo relato", back: { to: "/banco/relatar", label: id ? "Voltar" : "Cancelar" } }),
      el(
        "form",
        {
          class: "az-content az-form",
          novalidate: true,
          onSubmit: async (e) => {
            e.preventDefault();
            if (busy) return;
            busy = true;
            error = null;
            draw();
            try {
              const body = { ...form, txId: form.txId || null };
              const saved = id ? await api(`/api/incidents/${id}`, { method: "PUT", body, area: "customer" }) : await api("/api/incidents", { method: "POST", body, area: "customer" });
              toast(id ? "Relato atualizado" : `Relato ${saved.id} registrado`);
              go("/banco/relatar", { replace: true });
            } catch (err) {
              error = err;
              busy = false;
              draw();
            }
          },
        },
        el("p", { class: "az-lede" }, "Conte o que aconteceu. Se for uma compra aprovada, ela fica marcada como golpe relatado e um analista acompanha o caso."),
        error && !Object.keys(f).length ? errorNotice(error) : null,
        el(
          "fieldset",
          { class: "az-fs" },
          el("legend", {}, el("span", { class: "az-fs-n num" }, "1"), " A compra"),
          field({
            label: "Qual compra?",
            hint: "Opcional. Deixe em branco se não houve compra.",
            control: (p) => el("select", { ...p, class: "select", value: form.txId, disabled: !!id, onChange: (e) => { form.txId = e.target.value; } }, el("option", { value: "" }, "Nenhuma compra"), txs.filter((t) => t.status !== "INCIDENT_RECORDED" || t.id === form.txId).map((t) => el("option", { value: t.id }, `${t.description} · ${brl(t.amount)}`))),
          }),
        ),
        el(
          "fieldset",
          { class: "az-fs" },
          el("legend", {}, el("span", { class: "az-fs-n num" }, "2"), " O golpe"),
          field({ label: "O que aconteceu?", error: f.type, control: (p) => el("select", { ...p, class: "select", value: form.type, onChange: (e) => { form.type = e.target.value; } }, el("option", { value: "" }, "Escolha"), (options?.incidentTypes ?? []).map((t) => el("option", { value: t }, INCIDENT_TYPE[t] ?? t))) }),
          field({ label: "Por onde falaram com você?", error: f.channel, control: (p) => el("select", { ...p, class: "select", value: form.channel, onChange: (e) => { form.channel = e.target.value; } }, el("option", { value: "" }, "Escolha"), (options?.incidentChannels ?? []).map((t) => el("option", { value: t }, CHANNEL[t] ?? t))) }),
          field({ label: "Conte com suas palavras", error: f.description, control: (p) => el("textarea", { ...p, class: "textarea", rows: "4", placeholder: "Ex.: Ligaram dizendo ser do banco e pediram para eu comprar um celular…", value: form.description, onInput: (e) => { form.description = e.target.value; } }) }),
        ),
        el(
          "fieldset",
          { class: "az-fs" },
          el("legend", {}, el("span", { class: "az-fs-n num" }, "3"), " Pistas que protegem outros"),
          el("p", { class: "az-fs-help" }, "Tudo opcional. Quanto mais pistas, mais rápido o golpe é barrado para outras pessoas."),
          field({ label: "Telefone ou perfil que falou com você", hint: "Opcional", error: f.contactHandle, control: (p) => el("input", { ...p, class: "input", inputmode: "tel", value: form.contactHandle, onInput: (e) => { form.contactHandle = e.target.value; } }) }),
          field({ label: "Link que mandaram", hint: "Opcional", error: f.contactLink, control: (p) => el("input", { ...p, class: "input", spellcheck: "false", inputmode: "url", value: form.contactLink, onInput: (e) => { form.contactLink = e.target.value; } }) }),
          field({ label: "Endereço de entrega que passaram", hint: "Opcional", error: f.destinationText, control: (p) => el("input", { ...p, class: "input", value: form.destinationText, onInput: (e) => { form.destinationText = e.target.value; } }) }),
        ),
        el("label", { class: "check az-consent" }, el("input", { type: "checkbox", checked: form.consent, onChange: (e) => { form.consent = e.target.checked; } }), "Autorizo o uso deste relato para proteger outros clientes."),
        f.consent ? el("span", { class: "field-error" }, f.consent) : null,
        el("div", { class: "az-submit" }, button({ type: "submit", variant: "primary", size: "lg", block: true, busy }, id ? "Salvar alterações" : "Enviar relato")),
      ),
    );
  }
  draw();
  Promise.all([api("/api/catalog/options"), api("/api/transactions", { area: "customer" }), id ? api("/api/incidents", { area: "customer" }) : Promise.resolve([])]).then(([opt, list, mine]) => {
    options = opt;
    txs = list ?? [];
    if (id) {
      const editing = (mine ?? []).find((i) => i.id === id);
      if (!editing) {
        box.replaceChildren(topBar({ title: "Editar relato", back: { to: "/banco/relatar", label: "Voltar" } }), el("div", { class: "az-content" }, emptyState("flag", "Relato não encontrado", "Ele pode ter sido apagado ou já estar em análise.", el("a", { href: "/banco/relatar", class: "btn btn-primary" }, "Ver seus relatos"))));
        return;
      }
      Object.assign(form, { type: editing.type, channel: editing.channel, description: editing.description, contactHandle: editing.contactHandle ?? "", contactLink: editing.contactLink ?? "", destinationText: editing.destinationText ?? "", consent: true, txId: editing.txId ?? "" });
    }
    draw();
  });
  return box;
}

function verdictLabel(result) {
  return ({ alto: "Alto risco", atencao: "Atenção", insuficiente: "Informação insuficiente", baixo: "Baixo risco observado" }[result.verdict] ?? ({ RED: "Alto risco", YELLOW: "Atenção", GRAY: "Informação insuficiente", GREEN: "Baixo risco observado" }[result.level] ?? "Resultado"));
}
function entityPill(en) {
  if (en.status === "CONFIRMED") return pill("stop", "confirmado");
  if (en.status === "INVESTIGATING") return pill("warn", "em investigação");
  if (en.status === "UNVERIFIED" && en.reported > 0) return pill("warn", "ainda não confirmado");
  if (en.reported > 0) return pill("stop", `em ${en.reported} ${en.reported === 1 ? "relato" : "relatos"}`);
  return pill("muted", "sem relatos");
}

function checkPage() {
  setTitle("Confira antes de pagar · Aureon");
  let text = "";
  let result = null;
  let error = null;
  let busy = false;
  const box = el("div");
  function draw() {
    const tone = result ? LEVEL[result.level] ?? "muted" : "muted";
    const signals = result ? (result.signals?.length ? result.signals : [...(result.types ?? []), ...(result.cues ?? [])]) : [];
    box.replaceChildren(
      topBar({ title: "Confira", large: true, sub: "Cole a mensagem, o link ou o endereço que mandaram. Comparamos com golpes já relatados.", back: session() ? null : { to: "/banco", label: "Entrar" }, right: session() ? bell() : null }),
      el(
        "div",
        { class: "az-content" },
        el(
          "form",
          {
            class: "az-check",
            onSubmit: async (e) => {
              e.preventDefault();
              if (busy || !text.trim()) return;
              busy = true;
              error = null;
              draw();
              try {
                result = await api("/api/check", { method: "POST", body: { text } });
              } catch (err) {
                error = err;
              } finally {
                busy = false;
                draw();
              }
            },
          },
          field({ label: "Mensagem recebida", error: error?.fields?.text, control: (p) => el("textarea", { ...p, class: "textarea", rows: "5", placeholder: "Ex.: Olá, aqui é da central de segurança. Para cancelar a compra, acesse…", value: text, onInput: (e) => { text = e.target.value; if (result) { result = null; draw(); } } }) }),
          el("div", { class: "az-examples", role: "group", "aria-label": "Exemplos para testar" }, el("span", {}, "Testar com:"), EXAMPLES.map((x) => el("button", { type: "button", class: "az-chip", onClick: () => { text = x.text; result = null; draw(); } }, x.label))),
          button({ type: "submit", variant: "primary", size: "lg", block: true, busy, disabled: !text.trim() }, icon("confira", { size: 20 }), " Conferir"),
        ),
        error && !error.fields?.text ? errorNotice(error) : null,
        result
          ? el(
              "section",
              { class: `az-verdict tone-${tone}`, "aria-labelledby": "h-veredito" },
              el("div", { class: "az-verdict-head" }, el("span", { class: "az-lamp", "aria-hidden": "true" }, icon(LEVEL_ICON[result.level] ?? "info", { size: 26 })), el("div", {}, el("p", { class: "az-verdict-k" }, verdictLabel(result)), el("h2", { id: "h-veredito" }, result.title), el("p", {}, result.summary), result.analyzedAt ? el("p", { class: "az-analyzed" }, "Análise feita em ", el("time", { datetime: result.analyzedAt }, dateTime(result.analyzedAt)), ". Isso não é uma garantia.") : null)),
              signals.length ? el("div", { class: "az-verdict-sec" }, el("h3", { class: "az-h3" }, "O que chamou atenção"), el("ul", { class: "az-signals" }, signals.map((c) => el("li", {}, icon("alert", { size: 18 }), el("span", {}, el("strong", {}, c.label), el("span", {}, c.explain)))))) : null,
              (result.unverified ?? []).length
                ? el("div", { class: "az-verdict-sec" }, el("h3", { class: "az-h3" }, "Não foi possível verificar"), el("ul", { class: "az-signals az-unverified" }, result.unverified.map((c) => el("li", {}, icon("info", { size: 18 }), el("span", {}, el("strong", {}, c.label), el("span", {}, c.explain))))))
                : null,
              result.entities.length
                ? el("div", { class: "az-verdict-sec" }, el("h3", { class: "az-h3" }, "Encontramos na mensagem"), el("ul", { class: "az-entities" }, result.entities.map((en) => el("li", {}, el("span", { class: "mono", translate: "no" }, en.value), entityPill(en)))))
                : null,
              result.guidance.length ? el("div", { class: "az-verdict-sec" }, el("h3", { class: "az-h3" }, "O que fazer agora"), el("ol", { class: "az-guide" }, result.guidance.map((g, i) => el("li", {}, el("span", { class: "num", "aria-hidden": "true" }, String(i + 1)), el("span", {}, g))))) : null,
              session() && (result.level === "RED" || result.level === "YELLOW") ? el("a", { href: "/banco/relatar/novo", class: "btn btn-lg btn-block" }, icon("flag", { size: 20 }), " Já caí nesse golpe") : null,
            )
          : null,
      ),
    );
  }
  draw();
  return box;
}

function passwordPage() {
  setTitle("Alterar senha — Aureon");
  return el(
    "div",
    {},
    topBar({ title: "Alterar senha", back: { to: "/banco", label: "Início" } }),
    el("div", { class: "az-content" }, changePasswordForm({ area: "customer", backLink: "/banco" })),
  );
}

async function render(loc) {
  const signed = !!session();
  if (signed && !me) await refreshMe();
  if (signed) refreshAlerts();
  let node;
  if (!signed && loc.name !== "check") node = login();
  else if (loc.name === "home") node = signed ? home() : login();
  else if (loc.name === "alerts") node = alertsPage();
  else if (loc.name === "purchases") node = purchases();
  else if (loc.name === "purchase") node = purchase(loc.params.id);
  else if (loc.name === "reports") node = reports();
  else if (loc.name === "report-new") node = reportForm(null, loc.search);
  else if (loc.name === "report-edit") node = reportForm(loc.params.id, loc.search);
  else if (loc.name === "check") node = checkPage();
  else if (loc.name === "password" && signed) node = passwordPage();
  else {
    setTitle("Aureon");
    node = el("div", {}, topBar({ title: "Aureon", back: { to: "/banco", label: "Início" } }), el("div", { class: "az-content" }, emptyState("search", "Esta tela não existe", "O endereço pode ter mudado.", el("a", { href: "/banco", class: "btn btn-primary" }, "Voltar ao início"))));
  }
  pageHost.replaceChildren(node);
  paintShell();
}

function mount() {
  const phone = el("div", { class: "az-phone" }, el("span", { class: "az-btn-side a", "aria-hidden": "true" }), el("span", { class: "az-btn-side b", "aria-hidden": "true" }), el("span", { class: "az-btn-side c", "aria-hidden": "true" }), el("span", { class: "az-btn-side d", "aria-hidden": "true" }), screen);
  stage.append(
    el("aside", { class: "az-caption", "aria-label": "Sobre esta tela" }, el("p", { class: "az-caption-lead" }, "App do banco Aureon, como a cliente vê no celular."), el("p", { class: "az-caption-text" }, "Avisos, compras e o verificador de mensagens usam a mesma análise do ContextShield que protege o checkout da loja."), el("a", { href: "/", class: "az-caption-link" }, "Ver as outras telas da demonstração")),
    el("div", { class: "az-slot" }, phone),
  );
  document.body.append(el("div", { class: "az" }, stage));
  const fit = () => {
    const s = Math.min(1, (window.innerHeight - 40) / 868, (window.innerWidth - 32) / 414);
    stage.style.setProperty("--s", String(Math.max(0.5, s)));
  };
  fit();
  window.addEventListener("resize", fit);
  router = createRouter({
    base: "/banco",
    routes: [
      { name: "home", pattern: /^$/ },
      { name: "alerts", pattern: /^avisos$/ },
      { name: "purchases", pattern: /^compras$/ },
      { name: "purchase", pattern: /^compras\/(?<id>[^/]+)$/ },
      { name: "reports", pattern: /^relatar$/ },
      { name: "report-new", pattern: /^relatar\/novo$/ },
      { name: "report-edit", pattern: /^relatar\/(?<id>[^/]+)\/editar$/ },
      { name: "check", pattern: /^confira$/ },
      { name: "password", pattern: /^senha$/ },
    ],
    render,
  });
  onSession(() => {
    me = null;
    router.start();
  });
  setInterval(() => {
    if (document.visibilityState === "visible" && session()) refreshAlerts().then(paintShell);
  }, 15000);
  router.start();
}
mount();
