import { api, ApiError, getSession, login, onSession, setSession } from "./api/client.js";
import { createRouter } from "./router.js";
import { button, dialog, empty, errorNotice, field, loginForm, notice, pill, skeleton, tabs, toast, txPill } from "./ui.js";
import { el } from "./utils/dom.js";
import { BAND, BAND_TONE, brl, CHANNEL, dateTime, DECISION, ENTITY_KIND, INCIDENT_STATUS, INCIDENT_TYPE, ms, ORIGIN, relative, setTitle, SOURCE, STATUS, STATUS_ICON } from "./utils/format.js";
import { icon, mark } from "./utils/icons.js";
import { labPage, policiesPage } from "./ops-lab.js?v=3";

const ATTENTION = ["INTERVENTION", "CONTEXT_REQUIRED", "ANALYZING", "BLOCKED"];
const STATUS_ORDER = ["INTERVENTION", "BLOCKED", "CONTEXT_REQUIRED", "ANALYZING", "RECEIVED", "INCIDENT_RECORDED", "APPROVED_WITH_ALERT", "APPROVED", "CANCELLED"];
const STATUS_GROUPS = {
  ATENCAO: ATTENTION,
  AGUARDANDO: ["CONTEXT_REQUIRED", "INTERVENTION"],
};
const NEXT = {
  ANALYZING: ["APPROVED", "APPROVED_WITH_ALERT", "CONTEXT_REQUIRED", "INTERVENTION", "BLOCKED"],
  CONTEXT_REQUIRED: ["APPROVED", "APPROVED_WITH_ALERT", "INTERVENTION", "BLOCKED", "CANCELLED"],
  INTERVENTION: ["APPROVED_WITH_ALERT", "CANCELLED", "BLOCKED"],
  APPROVED: ["INCIDENT_RECORDED"],
  APPROVED_WITH_ALERT: ["INCIDENT_RECORDED"],
};
const GO = { v: "/ops", t: "/ops/transacoes", r: "/ops/relatos", i: "/ops/inteligencia", m: "/ops/motor", a: "/ops/auditoria", d: "/ops/demo" };
const SECTIONS = { "": "Visão geral", transacoes: "Transações", relatos: "Relatos", inteligencia: "Inteligência", motor: "Motor de risco", politicas: "Políticas de risco", laboratorio: "Laboratório", auditoria: "Auditoria", usuarios: "Usuários", demo: "Demonstração" };
const CERT = { ROBUST: ["Robusta", "ok"], FRAGILE: ["Frágil", "risk"], INCONCLUSIVE: ["Inconclusiva", "warn"], DEGRADED: ["Degradada", "warn"] };
const DECISION_TONE = { APPROVE: "ok", APPROVE_WITH_ALERT: "warn", REQUEST_CONTEXT: "info", INTERVENE: "risk", BLOCK: "stop" };
const DECISION_ICON = { APPROVE: "check", APPROVE_WITH_ALERT: "alert", REQUEST_CONTEXT: "question", INTERVENE: "pause", BLOCK: "stop" };
const DECISION_ORDER = ["APPROVE", "APPROVE_WITH_ALERT", "REQUEST_CONTEXT", "INTERVENE", "BLOCK"];
const ENTITY_ICON = { phone: "phoneCall", domain: "external", account: "card", destination: "pin", seller: "store", merchant: "bag" };
const CHANNEL_ICON = { whatsapp: "confira", sms: "confira", phone: "phoneCall", email: "inbox", social: "users", site: "external", none: "info" };
const ROLE = { CUSTOMER: "Cliente", ANALYST: "Analista", ADMIN: "Administração" };
const ROLE_TONE = { CUSTOMER: "muted", ANALYST: "info", ADMIN: "warn" };
const INCIDENT_ICON = { OPEN: "inbox", IN_REVIEW: "eye", CLOSED: "check" };
const lastSearch = { tx: "", incidents: "" };
const norm = (s) => s.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase();
const isMac = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);

let router;
let txs = null;
let attentionTotal = 0;
let txError = null;
let incidents = null;
let config = null;
let fresh = new Set();
let updatedAt = null;
let txStamp = "";
let paletteOpen = false;
let look = (() => { try { return localStorage.getItem("ops-look") === "full" ? "full" : "rail"; } catch { return "rail"; } })();
const shell = el("div", { class: "ops" });
const page = el("main", { id: "conteudo", class: "o-page", tabindex: "-1" });

function session() {
  return getSession("ops");
}
function go(url, opts) {
  return router.go(url, opts);
}
function inGroup(status, g) {
  if (!g) return true;
  return STATUS_GROUPS[g] ? STATUS_GROUPS[g].includes(status) : status === g;
}

function asAudit(data) {
  if (Array.isArray(data)) {
    const actions = [...new Set(data.map((a) => a.action).filter(Boolean))].sort((a, b) => a.localeCompare(b, "pt-BR"));
    return { items: data, total: data.length, pages: 1, actions, client: true };
  }
  const items = data?.items ?? [];
  const actions = data?.actions ?? [...new Set(items.map((a) => a.action).filter(Boolean))].sort((a, b) => a.localeCompare(b, "pt-BR"));
  return { items, total: Number(data?.total ?? items.length), pages: Math.max(1, Number(data?.pages) || 1), actions, client: false };
}
function asQueue(data) {
  if (Array.isArray(data)) {
    const counts = { ALL: data.length, ATENCAO: 0, AGUARDANDO: 0 };
    for (const tx of data) {
      counts[tx.status] = (counts[tx.status] ?? 0) + 1;
      if (ATTENTION.includes(tx.status)) counts.ATENCAO += 1;
      if (["CONTEXT_REQUIRED", "INTERVENTION"].includes(tx.status)) counts.AGUARDANDO += 1;
    }
    return { items: data, total: data.length, pages: 1, counts };
  }
  const items = data?.items ?? [];
  const counts = { ...(data?.counts ?? {}) };
  if (counts.ALL == null) counts.ALL = Number(data?.total ?? items.length);
  return { items, total: Number(data?.total ?? items.length), pages: Math.max(1, Number(data?.pages) || 1), counts };
}

async function poll(quiet) {
  let txChanged = true;
  try {
    const payload = asQueue(await api("/api/ops/transactions?size=25", { area: "ops" }));
    const next = payload.items;
    if (txs) {
      const before = new Map(txs.map((t) => [t.id, t.status]));
      fresh = new Set(next.filter((t) => before.get(t.id) !== t.status).map((t) => t.id));
      if (fresh.size) setTimeout(() => { fresh = new Set(); paintChrome(); }, 1400);
    }
    const stamp = JSON.stringify(next.map((t) => [t.id, t.status, t.score, t.updatedAt]));
    txChanged = stamp !== txStamp;
    txStamp = stamp;
    txs = next;
    attentionTotal = Number(payload.counts.ATENCAO ?? 0);
    txError = null;
    updatedAt = Date.now();
  } catch (e) {
    txError = e instanceof ApiError ? e : new ApiError(0, String(e));
  }
  const beforeIncidents = JSON.stringify(incidents);
  try { incidents = await api("/api/ops/incidents", { area: "ops" }); } catch { /* mantém a lista */ }
  try { config = await api("/api/ops/engine/config", { area: "ops" }); } catch { /* mantém a config */ }
  if (!quiet) return;
  paintChrome();
  if (!txChanged && JSON.stringify(incidents) === beforeIncidents) return;
  const path = location.pathname;
  if (path === "/ops" || (path.startsWith("/ops/transacoes") && !/\/TX-/.test(path)) || path === "/ops/relatos") router.start();
}

function paintChrome() {
  const s = session();
  if (!s) return;
  const path = location.pathname.replace(/\/$/, "") || "/ops";
  const attention = attentionTotal;
  const pending = (incidents ?? []).filter((i) => i.status !== "CLOSED").length;
  const degraded = config ? Object.values(config.providers).filter((p) => p === "down").length + Object.values(config.flags).filter((f) => !f).length : 0;
  shell.querySelectorAll("[data-count]").forEach((n) => {
    const key = n.dataset.count;
    const value = key === "tx" ? attention : key === "inc" ? pending : 0;
    n.hidden = !value;
    const num = n.querySelector("[data-n]");
    if (num) num.textContent = String(value);
  });
  const live = shell.querySelector(".o-live");
  if (live) {
    const state = txError ? "off" : document.hidden || !updatedAt ? "idle" : "on";
    live.dataset.state = state;
    live.querySelector(".o-live-text").textContent = state === "off" ? "Sem conexão" : state === "idle" ? "Pausado" : "Ao vivo";
    live.title = updatedAt ? `Última atualização ${relative(new Date(updatedAt).toISOString())}` : "";
  }
  const flag = shell.querySelector(".o-degraded");
  if (flag) flag.hidden = degraded === 0;
  shell.querySelector("[data-nav='usuarios']")?.toggleAttribute("hidden", s.user.role !== "ADMIN");
}

function opsShell(loc) {
  const s = session();
  const isAdmin = s.user.role === "ADMIN";
  const attention = attentionTotal;
  const pending = (incidents ?? []).filter((i) => i.status !== "CLOSED").length;
  const degraded = config ? Object.values(config.providers).filter((p) => p === "down").length + Object.values(config.flags).filter((f) => !f).length : 0;
  const parts = loc.path.replace(/^\/ops\/?/, "").split("/").filter(Boolean);
  const section = parts[0] ?? "";
  const back = section === "transacoes" ? lastSearch.tx : section === "relatos" ? lastSearch.incidents : "";
  const nav = (to, ic, label, extra) => {
    const on = to === "/ops" ? loc.path === "/ops" : loc.path === to || loc.path.startsWith(`${to}/`);
    return el(
      "a",
      { href: to, class: `o-nav-item${on ? " active" : ""}`, "aria-label": label, "aria-current": on ? "page" : null, "data-nav": extra?.key || null, hidden: extra?.admin && !isAdmin ? true : null },
      el("span", { class: "o-nav-ic" }, icon(ic, { size: 18 }), extra?.mark ? el("span", { class: `o-nav-mark${extra.mark}`, "aria-hidden": "true" }) : null),
      el("span", { class: "o-nav-label" }, label),
      extra?.countKey
        ? el("span", { class: `o-count num${extra.urgent ? " is-urgent" : ""}`, "data-count": extra.countKey, hidden: !extra.count }, el("span", { "data-n": "", "aria-hidden": "true" }, String(extra.count || 0)))
        : null,
      el("span", { class: "o-tip", "aria-hidden": "true" }, label, extra?.count ? el("span", { class: "o-tip-n num" }, String(extra.count)) : null),
    );
  };
  shell.dataset.look = look;
  shell.replaceChildren(
    el("a", { class: "skip-link", href: "#conteudo" }, "Pular para o conteúdo"),
    el(
      "aside",
      { class: "o-side", id: "o-side", "aria-label": "Navegação do painel" },
      el("div", { class: "o-brand" }, el("a", { href: "/ops", class: "o-brand-link", "aria-label": "ContextShield, visão geral" }, mark(24), el("span", { class: "o-brand-name" }, "ContextShield")), el("button", { type: "button", class: "o-icon-btn o-collapse", "aria-label": look === "rail" ? "Expandir menu" : "Recolher menu", "aria-expanded": String(look !== "rail"), onClick: (e) => { look = look === "rail" ? "full" : "rail"; try { localStorage.setItem("ops-look", look); } catch { /* sem armazenamento */ } shell.dataset.look = look; e.currentTarget.setAttribute("aria-label", look === "rail" ? "Expandir menu" : "Recolher menu"); e.currentTarget.setAttribute("aria-expanded", String(look !== "rail")); } }, icon("chevron", { size: 16 }))),
      el(
        "nav",
        { class: "o-nav", "aria-label": "Seções" },
        nav("/ops", "overview", "Visão geral"),
        nav("/ops/transacoes", "list", "Transações", { countKey: "tx", count: attention, urgent: true, mark: attention ? " is-urgent" : "" }),
        nav("/ops/relatos", "inbox", "Relatos", { countKey: "inc", count: pending, mark: pending ? "" : "" }),
        nav("/ops/inteligencia", "network", "Inteligência"),
        el("span", { class: "o-nav-group" }, "Sistema"),
        nav("/ops/motor", "gauge", "Motor de risco", { mark: degraded ? " is-warn" : "" }),
        nav("/ops/politicas", "list", "Políticas de risco"),
        nav("/ops/laboratorio", "play", "Laboratório"),
        nav("/ops/auditoria", "history", "Auditoria"),
        nav("/ops/usuarios", "users", "Usuários", { admin: true, key: "usuarios" }),
        nav("/ops/demo", "play", "Demonstração"),
      ),
      el(
        "div",
        { class: "o-user" },
        el("span", { class: "o-avatar", "aria-hidden": "true" }, s.user.name.split(/\s+/).slice(0, 2).map((p) => p[0]).join("").toUpperCase()),
        el("span", { class: "o-user-text" }, el("strong", {}, s.user.name), el("span", {}, isAdmin ? "Administração" : "Analista")),
        el("button", { type: "button", class: "o-icon-btn o-logout", "aria-label": "Sair", onClick: () => setSession("ops", null) }, icon("out", { size: 18 })),
      ),
    ),
    el(
      "div",
      { class: "o-main" },
      el(
        "header",
        { class: "o-top" },
        el(
          "nav",
          { class: "o-crumbs", "aria-label": "Você está em" },
          el(
            "ol",
            {},
            el("li", {}, parts.length > 1 ? el("a", { href: `/ops/${section}${back}` }, SECTIONS[section] ?? section) : el("span", { "aria-current": "page" }, SECTIONS[section] ?? "Visão geral")),
            parts[1] ? el("li", {}, icon("chevron", { size: 14 }), el("span", { "aria-current": "page", class: "mono" }, decodeURIComponent(parts[1]))) : null,
          ),
        ),
        el(
          "div",
          { class: "o-top-right" },
          el("a", { href: "/ops/motor", class: "o-degraded", hidden: degraded ? null : true }, icon("alert", { size: 15, stroke: 1.9 }), el("span", {}, "Modo degradado")),
          el("span", { class: "o-live", "data-state": txError ? "off" : "on" }, el("span", { class: "o-live-dot", "aria-hidden": "true" }), el("span", { class: "o-live-text" }, txError ? "Sem conexão" : "Ao vivo")),
          el("button", { type: "button", class: "o-search", onClick: () => openPalette(isAdmin), "aria-keyshortcuts": isMac ? "Meta+K" : "Control+K" }, icon("search", { size: 16 }), el("span", { class: "o-search-label" }, "Buscar ou ir para…"), el("span", { class: "o-search-keys", "aria-hidden": "true" }, el("kbd", { class: "kbd" }, isMac ? "⌘" : "Ctrl"), el("kbd", { class: "kbd" }, "K"))),
        ),
      ),
      page,
    ),
  );
  document.documentElement.dataset.ops = "";
}

function head(title, { sub, back, badge, actions, heading } = {}) {
  setTitle(title);
  return el(
    "header",
    { class: "o-head" },
    back ? el("a", { href: back.to, class: "o-back" }, icon("back", { size: 16 }), ` ${back.label}`) : null,
    el("div", { class: "o-head-row" }, el("div", { class: "o-head-text" }, el("div", { class: "o-head-title" }, el("h1", {}, heading ?? title), badge ?? null), sub ? el("p", { class: "o-head-sub" }, sub) : null), actions ? el("div", { class: "o-head-actions" }, actions) : null),
  );
}

function chips(items, value, onChange, label) {
  return el("div", { class: "chips", role: "group", "aria-label": label }, items.map((it) => el("button", { type: "button", class: "chip", "aria-pressed": value === it.value ? "true" : "false", onClick: () => onChange(it.value) }, it.icon ? icon(it.icon, { size: 15 }) : null, it.label, it.count != null ? el("span", { class: "chip-n num" }, String(it.count)) : null)));
}

function countStatus(map, names) {
  return names.reduce((s, n) => s + Number(map?.[n] ?? 0), 0);
}
function share(n, total) {
  return total ? `${Math.round((n / total) * 100)}%` : "0%";
}
function dayKey(value) {
  return String(value ?? "").slice(0, 10);
}
function withinDays(value, days) {
  if (!days) return true;
  const raw = dayKey(value);
  const t = new Date(raw.length === 10 ? `${raw}T12:00:00` : value).getTime();
  return Number.isFinite(t) && Date.now() - t <= days * 86_400_000;
}
function localDay(date) {
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${date.getFullYear()}-${month}-${day}`;
}
function axisDay(key) {
  const [, month, day] = dayKey(key).split("-");
  return `${day}/${month}`;
}
function fillDaily(rows, window) {
  const map = new Map();
  for (const row of rows ?? []) {
    const key = dayKey(row.d);
    if (key) map.set(key, { d: key, total: Number(row.total) || 0, protected: Number(row.protected) || 0 });
  }
  const keys = [...map.keys()].sort();
  const startKey = window?.from ? dayKey(window.from) : keys[0];
  const endKey = window?.to ? dayKey(window.to) : keys.at(-1);
  if (!startKey || !endKey) return [];
  const out = [];
  const cursor = new Date(`${startKey}T12:00:00`);
  const end = new Date(`${endKey}T12:00:00`);
  for (; cursor <= end; cursor.setDate(cursor.getDate() + 1)) {
    const key = localDay(cursor);
    out.push(map.get(key) ?? { d: key, total: 0, protected: 0 });
  }
  return out;
}
function periodLabel(period) {
  const days = Number(period) || 0;
  if (!days) return "Todo o histórico";
  const end = new Date();
  end.setHours(12, 0, 0, 0);
  const start = new Date(end);
  start.setDate(end.getDate() - (days - 1));
  return `${axisDay(localDay(start))} – ${axisDay(localDay(end))}`;
}
function hasNumbers(map) {
  return !!map && typeof map === "object" && !Array.isArray(map) && Object.values(map).some((n) => Number(n));
}
function inPeriod(value, period) {
  const days = Number(period) || 0;
  if (!days) return true;
  const key = dayKey(value);
  if (!key) return false;
  const end = new Date();
  end.setHours(12, 0, 0, 0);
  const start = new Date(end);
  start.setDate(end.getDate() - (days - 1));
  const t = new Date(`${key}T12:00:00`).getTime();
  return t >= start.getTime() && t <= end.getTime();
}

function overview() {
  const box = el("div", { class: "o-stack" }, head("Visão geral", { sub: "Compras, decisões e o que o motor reteve." }), el("div", { id: "ov", "aria-busy": "true" }, skeleton(80)));
  let period = 30;
  let band = "";
  const load = () => {
    const params = new URLSearchParams();
    if (period) params.set("days", String(period));
    if (band) params.set("band", band);
    const queueParams = new URLSearchParams(params);
    queueParams.set("size", "100");
    Promise.all([
      api(`/api/ops/overview?${params}`, { area: "ops" }),
      api(`/api/ops/transactions?${queueParams}`, { area: "ops" }).catch(() => null),
    ])
      .then(([overviewData, queueData]) => render(overviewData, queueData))
      .catch((e) => {
        const host = box.querySelector("#ov");
        const notice = pageError(e, () => load());
        if (host) host.replaceChildren(notice);
        else box.append(notice);
      });
  };
  const render = (o, queueData) => {
        const loaded = Array.isArray(queueData) ? queueData : queueData?.items ?? [];
        const list = loaded.filter((t) => inPeriod(t.createdAt, period) && (!band || t.band === band));
        const by = hasNumbers(o.transactionsByStatus) ? o.transactionsByStatus : list.reduce((map, t) => { map[t.status] = (map[t.status] ?? 0) + 1; return map; }, {});
        const total = Number(o.transactions || 0) || list.length;
        const pending = countStatus(by, ["CONTEXT_REQUIRED", "INTERVENTION", "ANALYZING", "RECEIVED"]);
        const decisions = hasNumbers(o.decisions) ? o.decisions : list.reduce((map, t) => { const key = t.decision || t.status; if (key) map[key] = (map[key] ?? 0) + 1; return map; }, {});
        const decisionTotal = Object.values(decisions).reduce((sum, n) => sum + Number(n), 0);
        const signaled = decisionTotal - Number(decisions.APPROVE ?? 0);
        const bounds = (() => {
          const n = Number(period) || 0;
          if (!n) return null;
          const end = new Date();
          end.setHours(12, 0, 0, 0);
          const start = new Date(end);
          start.setDate(end.getDate() - (n - 1));
          return { days: n, from: localDay(start), to: localDay(end) };
        })();
        const days = fillDaily(o.daily ?? [], bounds).filter((d) => inPeriod(d.d, period));
        const held = days.reduce((sum, d) => sum + Number(d.protected || 0), 0);
        const heldPrev = period && o.previousProtected != null ? Number(o.previousProtected) : null;
        const heldNote = heldPrev == null ? [period ? "no período" : "no histórico"] : held === heldPrev ? [el("span", { class: "fa-delta flat" }, "= igual"), "ao período anterior"] : [el("span", { class: `fa-delta ${held > heldPrev ? "up" : "down"}` }, `${held > heldPrev ? "▲" : "▼"} ${Math.abs(held - heldPrev)}`), "vs. período anterior"];
        const origins = hasNumbers(o.origins) ? o.origins : list.reduce((map, t) => { if (t.declaredOrigin) map[t.declaredOrigin] = (map[t.declaredOrigin] ?? 0) + 1; return map; }, {});
        const amounts = hasNumbers(o.decisionAmounts) ? o.decisionAmounts : list.reduce((map, t) => { const key = t.decision || t.status; if (!key) return map; map[key] = (map[key] ?? 0) + Number(t.amount || 0); return map; }, {});
        const recent = Array.isArray(o.recentHeld) && o.recentHeld.length ? o.recentHeld : list.filter((t) => ["BLOCKED", "CANCELLED", "INTERVENTION", "CONTEXT_REQUIRED"].includes(t.status)).slice(0, 5);
        const down = config ? Object.values(config.providers).filter((p) => p === "down").length + Object.values(config.flags).filter((f) => !f).length : 0;
        const range = el(
          "div",
          { class: "fa-range", role: "group", "aria-label": "Período" },
          [["7", "7D"], ["30", "30D"], ["0", "Tudo"]].map(([value, label]) =>
            el("button", { type: "button", class: "fa-range-btn", "aria-pressed": String(period) === value ? "true" : "false", onClick: () => { period = Number(value); load(); } }, label),
          ),
        );
        const scope = `${periodLabel(period)}${band ? ` · risco ${BAND[band]}` : ""}`;
        box.replaceChildren(
          head("Visão geral", {
            sub: `${scope}. Os cartões, a série e as listas usam esse intervalo. Datas civis de São Paulo, da meia-noite do primeiro dia até agora.`,
            actions: range,
          }),
          el(
            "div",
            { class: "fa" },
            el("div", { class: "fa-band" }, chips([{ value: "", label: "Todo risco" }, ...["LOW", "MEDIUM", "HIGH", "CRITICAL"].map((id) => ({ value: id, label: BAND[id] }))], band, (value) => { band = value; load(); }, "Nível de risco")),
            band ? el("p", { class: "o-note" }, "O nível de risco filtra as compras pela última avaliação. Os relatos entram só pela data, porque não têm banda.") : null,
            el(
              "div",
              { class: "fa-kpis" },
              faKpi("/ops/transacoes?status=BLOCKED", "stop", "shield", "Valor protegido", brl(Number(o.protectedAmount ?? 0)), "bloqueadas ou canceladas"),
              faKpi("/ops/transacoes?status=ATENCAO", "risk", "stop", "Retidas", String(held), heldNote),
              faKpi("/ops/transacoes?status=APPROVED_WITH_ALERT", "ok", "alert", "Com sinal", decisionTotal ? `${Math.round((signaled / decisionTotal) * 1000) / 10}%` : "0%", `${signaled} fora da aprovação direta`),
              faKpi("/ops/motor", "info", "gauge", "Resposta", ms(o.latency?.engineP50), "mediana do motor"),
              faKpi("/ops/transacoes?status=ATENCAO", "warn", "pause", "Em análise", String(pending), `${total} compras no período`),
            ),
            el(
              "div",
              { class: "fa-mid" },
              el(
                "section",
                { class: "panel", "aria-labelledby": "h-daily" },
                el(
                  "div",
                  { class: "p-head" },
                  el("div", {}, el("h2", { id: "h-daily" }, "Linha do tempo"), el("p", { class: "o-note" }, "Compras e retidas por dia")),
                  el("div", { class: "o-legend" }, el("span", {}, el("i", { class: "swatch total" }), "Compras"), el("span", {}, el("i", { class: "swatch held" }), "Retidas")),
                ),
                days.some((d) => d.total) ? lineChart(days, txs ?? []) : el("p", { class: "o-note" }, "Não há compras nesse período."),
              ),
              el(
                "section",
                { class: "panel" },
                el("div", { class: "p-head" }, el("h2", {}, "Origem da compra"), el("a", { href: "/ops/transacoes", class: "p-link" }, "Ver fila")),
                originBars(origins),
              ),
            ),
            el(
              "div",
              { class: "fa-bot" },
              el("section", { class: "panel" }, el("div", { class: "p-head" }, el("h2", {}, "Por decisão")), patternBars(amounts)),
              el("section", { class: "panel" }, el("div", { class: "p-head" }, el("h2", {}, "Motor"), down ? pill("warn", "Degradado") : pill("ok", "Operando")), healthGrid(o)),
              el("section", { class: "panel" }, el("div", { class: "p-head" }, el("h2", {}, "Retidas recentes"), el("a", { href: "/ops/transacoes?status=ATENCAO", class: "p-link" }, "Abrir fila")), recentHeld(recent)),
            ),
          ),
        );
  };
  load();
  return box;
}
function faKpi(to, tone, ic, label, value, note) {
  return el(
    "a",
    { href: to, class: `fa-kpi tone-${tone}` },
    el("span", { class: "fa-kpi-top" }, el("span", { class: "fa-kpi-label" }, label), el("span", { class: "fa-kpi-ic", "aria-hidden": "true" }, icon(ic, { size: 15 }))),
    el("strong", { class: "num" }, value),
    el("span", { class: "fa-kpi-note" }, note),
  );
}
function originBars(origins) {
  const rows = Object.entries(origins ?? {})
    .map(([id, n]) => ({ id, n: Number(n) }))
    .filter((row) => row.n)
    .sort((a, b) => b.n - a.n);
  const total = rows.reduce((sum, row) => sum + row.n, 0);
  if (!rows.length) return el("p", { class: "o-note" }, "Nenhuma origem declarada nesse intervalo.");
  return el(
    "ul",
    { class: "origin-list" },
    rows.map((row, i) => {
      const pct = Math.round((row.n / total) * 100);
      return el(
        "li",
        {},
        el(
          "a",
          { href: "/ops/transacoes", class: `origin-card ${i < 2 ? "is-hot" : "is-calm"}` },
          el("span", { class: "origin-fill", style: { width: `${Math.max(pct, 8)}%` }, "aria-hidden": "true" }),
          el("span", { class: "origin-name" }, ORIGIN[row.id] ?? row.id),
          el("span", { class: "origin-meta" }, `${row.n} ${row.n === 1 ? "compra" : "compras"}`),
          el("span", { class: "origin-pct num" }, `${pct}%`),
        ),
      );
    }),
  );
}
function patternBars(amounts) {
  const rows = Object.entries(amounts ?? {})
    .map(([key, amount]) => ({ key, amount: Number(amount) }))
    .sort((a, b) => b.amount - a.amount)
    .slice(0, 4);
  const max = Math.max(1, ...rows.map((row) => row.amount));
  const iconFor = { APPROVE: "check", APPROVE_WITH_ALERT: "alert", INTERVENE: "pause", BLOCK: "stop", REQUEST_CONTEXT: "question" };
  if (!rows.length) return el("p", { class: "o-note" }, "Nenhuma compra nesse intervalo.");
  return el(
    "ul",
    { class: "pat-list" },
    rows.map((row) => {
      const tone = DECISION_TONE[row.key] ?? STATUS[row.key]?.tone ?? "info";
      return el(
        "li",
        { class: `tone-${tone}` },
        el("span", { class: "pat-ic", "aria-hidden": "true" }, icon(iconFor[row.key] ?? "receipt", { size: 14 })),
        el("span", { class: "pat-name" }, DECISION[row.key] ?? STATUS[row.key]?.label ?? row.key),
        el("span", { class: "pat-track" }, el("span", { class: `tone-${tone}`, style: { width: `${Math.max(8, Math.round((row.amount / max) * 100))}%` } })),
        el("span", { class: "pat-amt num" }, brl(row.amount)),
      );
    }),
  );
}
function healthGrid(o) {
  const latency = o.latency ?? {};
  return el(
    "div",
    { class: "fa-health" },
    [
      [String(Math.round(Number(o.averageScore ?? 0))), "Score médio"],
      [String(o.safetyAdjusted ?? 0), "Ajustes do envelope"],
      [ms(latency.engineP50), "Resposta do motor"],
      [ms(latency.roundTripP50), "Ida e volta"],
    ].map(([value, label]) => el("div", {}, el("strong", { class: "num" }, value), el("span", {}, label))),
  );
}
function recentHeld(rows) {
  const list = (Array.isArray(rows) ? rows : []).filter((tx) => ["BLOCKED", "CANCELLED", "INTERVENTION", "CONTEXT_REQUIRED"].includes(tx.status)).slice(0, 5);
  if (!list.length) return el("p", { class: "o-note" }, "Nenhuma compra retida nesse período.");
  return el(
    "ul",
    { class: "fa-recent" },
    list.map((tx) =>
      el(
        "li",
        {},
        el(
          "a",
          { href: `/ops/transacoes/${tx.id}` },
          el("span", {}, el("i", { class: `dot tone-${STATUS[tx.status]?.tone ?? "stop"}`, "aria-hidden": "true" }), el("span", { class: "mono" }, tx.id)),
          el("strong", { class: "num" }, brl(tx.amount)),
        ),
      ),
    ),
  );
}
function riskBars(bands, total) {
  const shown = bands.filter((b) => b.n);
  if (!shown.length) return el("p", { class: "o-note" }, "A fila filtrada não tem banda de risco.");
  return el(
    "ul",
    { class: "origin-list" },
    shown.map((b) => {
      const pct = Math.round((b.n / Math.max(1, total)) * 100);
      return el(
        "li",
        {},
        el(
          "button",
          { type: "button", class: "origin-row", onClick: () => go("/ops/transacoes") },
          el("span", { class: "origin-name" }, BAND[b.id]),
          el("span", { class: "origin-meta" }, `${b.n} ${b.n === 1 ? "compra" : "compras"}`),
          el("span", { class: "origin-pct num" }, `${pct}%`),
          el("span", { class: "origin-track", "aria-hidden": "true" }, el("span", { class: `tone-${BAND_TONE[b.id]}`, style: { width: `${Math.max(pct, 4)}%` } })),
        ),
      );
    }),
  );
}
function motorTiles(l) {
  if (!l) return [];
  return [
    ["Motor p50", ms(l.engineP50)],
    ["Motor p95", ms(l.engineP95)],
    ["Ida e volta p50", ms(l.roundTripP50)],
    ["Ida e volta p95", ms(l.roundTripP95)],
  ].map(([label, value]) => el("div", {}, el("strong", { class: "num" }, value), el("span", {}, label)));
}
function lineChart(rows, queue = []) {
  const w = 720;
  const h = 300;
  const pad = { l: 32, r: 14, t: 16, b: 34 };
  const rawMax = Math.max(1, ...rows.map((d) => Number(d.total) || 0));
  const step = rawMax > 3 ? Math.ceil(rawMax / 3) : 0;
  const max = step ? step * 3 : rawMax;
  const span = w - pad.l - pad.r;
  const x = (i) => pad.l + (rows.length <= 1 ? span / 2 : (i / (rows.length - 1)) * span);
  const y = (v) => pad.t + (h - pad.t - pad.b) * (1 - v / max);
  const floor = y(0);
  const ceil = y(max);
  const clamp = (v) => Math.min(floor, Math.max(ceil, v));
  const curve = (key) => {
    const pts = rows.map((d, i) => [x(i), y(Number(d[key]) || 0)]);
    if (pts.length < 2) return `M${pts[0][0].toFixed(1)} ${pts[0][1].toFixed(1)}`;
    let d = `M${pts[0][0].toFixed(1)} ${pts[0][1].toFixed(1)}`;
    for (let i = 0; i < pts.length - 1; i += 1) {
      const p0 = pts[Math.max(0, i - 1)];
      const p1 = pts[i];
      const p2 = pts[i + 1];
      const p3 = pts[Math.min(pts.length - 1, i + 2)];
      const c1x = p1[0] + (p2[0] - p0[0]) / 6;
      const c1y = clamp(p1[1] + (p2[1] - p0[1]) / 6);
      const c2x = p2[0] - (p3[0] - p1[0]) / 6;
      const c2y = clamp(p2[1] - (p3[1] - p1[1]) / 6);
      d += ` C${c1x.toFixed(1)} ${c1y.toFixed(1)} ${c2x.toFixed(1)} ${c2y.toFixed(1)} ${p2[0].toFixed(1)} ${p2[1].toFixed(1)}`;
    }
    return d;
  };
  const area = (key) => `${curve(key)} L${x(rows.length - 1).toFixed(1)} ${floor.toFixed(1)} L${x(0).toFixed(1)} ${floor.toFixed(1)} Z`;
  const ticks = step ? [0, step, step * 2, step * 3] : max <= 2 ? [0, max] : [0, Math.ceil(max / 2), max];
  const labelAt = new Set();
  const labelStep = rows.length > 24 ? 4 : rows.length > 16 ? 3 : rows.length > 10 ? 2 : 1;
  for (let i = 0; i < rows.length; i += labelStep) labelAt.add(i);
  if (!labelAt.has(rows.length - 1)) {
    const previous = [...labelAt].at(-1);
    if (previous != null && rows.length - 1 - previous < labelStep) labelAt.delete(previous);
    labelAt.add(rows.length - 1);
  }
  const slot = rows.length <= 1 ? span : span / (rows.length - 1);
  const guide = el("line", { class: "guide", x1: "0", x2: "0", y1: pad.t, y2: y(0), visibility: "hidden" });
  const tip = el("div", { class: "chart-tip", hidden: true });
  const dotTotal = el("circle", { class: "dot-total", r: "4.5", cx: "0", cy: "0", visibility: "hidden", "pointer-events": "none" });
  const dotHeld = el("circle", { class: "dot-held", r: "4.5", cx: "0", cy: "0", visibility: "hidden", "pointer-events": "none" });
  let current = rows.length - 1;
  const show = (row, i) => {
    current = i;
    guide.setAttribute("x1", x(i).toFixed(1));
    guide.setAttribute("x2", x(i).toFixed(1));
    guide.setAttribute("visibility", "visible");
    dotTotal.setAttribute("cx", x(i).toFixed(1));
    dotTotal.setAttribute("cy", y(Number(row.total) || 0).toFixed(1));
    dotTotal.setAttribute("visibility", "visible");
    dotHeld.setAttribute("cx", x(i).toFixed(1));
    dotHeld.setAttribute("cy", y(Number(row.protected) || 0).toFixed(1));
    dotHeld.setAttribute("visibility", "visible");
    const mine = queue.filter((tx) => dayKey(tx.createdAt) === row.d);
    tip.hidden = false;
    tip.replaceChildren(
      ...[
        el("strong", {}, axisDay(row.d)),
        el("span", {}, `${row.total} ${row.total === 1 ? "compra" : "compras"} · ${row.protected} ${row.protected === 1 ? "retida" : "retidas"}`),
        ...mine.slice(0, 4).map((tx) => el("span", { class: "mono" }, `${tx.id} · ${brl(tx.amount)}`)),
        mine.length > 4 ? el("span", {}, `+${mine.length - 4} na fila`) : null,
      ].filter(Boolean),
    );
    tip.style.left = `${Math.min(72, Math.max(0, (x(i) / w) * 100 - 6))}%`;
  };
  const svg = el(
    "svg",
    { class: "o-chart", viewBox: `0 0 ${w} ${h}`, role: "img", "aria-label": `Compras e retidas em ${rows.length} dias. Passe o cursor sobre um dia para ver o detalhe.` },
    ticks.map((value) => el("line", { class: "grid", x1: pad.l, x2: w - pad.r, y1: y(value), y2: y(value) })),
    ticks.map((value) => el("text", { class: "tick", x: "2", y: y(value) + 4 }, String(value))),
    el("defs", {},
      el("linearGradient", { id: "fa-total", x1: "0", y1: "0", x2: "0", y2: "1" }, el("stop", { offset: "0%", "stop-color": "#f0b04a", "stop-opacity": "0.22" }), el("stop", { offset: "100%", "stop-color": "#f0b04a", "stop-opacity": "0" })),
      el("linearGradient", { id: "fa-held", x1: "0", y1: "0", x2: "0", y2: "1" }, el("stop", { offset: "0%", "stop-color": "#ff5c7a", "stop-opacity": "0.34" }), el("stop", { offset: "100%", "stop-color": "#ff5c7a", "stop-opacity": "0" })),
    ),
    el("path", { class: "area-total", d: area("total") }),
    el("path", { class: "area-held", d: area("protected") }),
    el("path", { class: "line-total", d: curve("total") }),
    el("path", { class: "line-held", d: curve("protected") }),
    rows.map((row, i) => (labelAt.has(i) ? el("text", { class: "tick day", x: x(i), y: h - 10, "text-anchor": i === 0 ? "start" : i === rows.length - 1 ? "end" : "middle" }, axisDay(row.d)) : null)),
    guide,
    dotTotal,
    dotHeld,
    rows.map((row, i) => {
      const hit = el("rect", { class: "chart-hit", x: Math.max(pad.l, x(i) - slot / 2), y: pad.t, width: Math.min(slot, w - pad.r - Math.max(pad.l, x(i) - slot / 2)), height: y(0) - pad.t });
      hit.addEventListener("pointerenter", () => show(row, i));
      return hit;
    }),
  );
  const wrap = el("div", { class: "chart-wrap", tabindex: "0" }, svg, tip);
  const hide = () => {
    tip.hidden = true;
    for (const node of [guide, dotTotal, dotHeld]) node.setAttribute("visibility", "hidden");
  };
  wrap.addEventListener("pointerleave", hide);
  wrap.addEventListener("blur", hide);
  wrap.addEventListener("keydown", (e) => {
    const step = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : e.key === "Home" ? -rows.length : e.key === "End" ? rows.length : 0;
    if (!step) {
      if (e.key === "Escape") hide();
      return;
    }
    e.preventDefault();
    const next = Math.min(rows.length - 1, Math.max(0, (tip.hidden ? rows.length - 1 - step : current) + step));
    show(rows[next], next);
  });
  return wrap;
}
function kpi(to, tone, ic, label, value, note) {
  return el("a", { href: to, class: `kpi tone-${tone}`, "data-zero": value === 0 ? "true" : null }, el("span", { class: "kpi-label" }, el("span", { class: "kpi-ic", "aria-hidden": "true" }, icon(ic, { size: 15, stroke: 1.9 })), label), el("span", { class: "kpi-value" }, el("span", { class: "num" }, String(value))), el("span", { class: "kpi-note" }, note));
}
function stat(label, value) {
  return el("div", {}, el("dt", {}, label), el("dd", {}, el("span", { class: "num" }, String(value))));
}
function attentionList(rows) {
  if (!rows.length) return el("div", { class: "att-empty" }, icon("shield", { size: 22 }), el("div", {}, el("strong", {}, "Nada esperando por alguém"), el("p", {}, "Nenhuma compra pausada, bloqueada ou à espera do cliente. A fila se atualiza sozinha.")));
  return el("ul", { class: "att-list" }, rows.slice(0, 6).map((t) => el("li", { class: fresh.has(t.id) ? "is-fresh" : null }, el("a", { href: `/ops/transacoes/${t.id}`, class: "att-row" }, el("span", { class: "att-main" }, el("span", { class: "mono att-id" }, t.id), el("span", { class: "att-who" }, `${t.customerName} · ${t.description}`)), el("span", { class: "num att-amount" }, brl(t.amount)), txPill(t.status, "sm"), el("span", { class: "att-when" }, relative(t.createdAt))))));
}
function decisionMix(decisions) {
  const keys = [...DECISION_ORDER.filter((k) => decisions[k]), ...Object.keys(decisions).filter((k) => !DECISION_ORDER.includes(k))];
  const total = keys.reduce((s, k) => s + decisions[k], 0) || 1;
  return el("div", { class: "mix" }, el("div", { class: "mix-bar" }, keys.map((k) => el("span", { class: `mix-seg tone-${DECISION_TONE[k] ?? "muted"}`, style: { flexGrow: String(decisions[k]) } }))), el("ul", { class: "mix-legend" }, keys.map((k) => el("li", {}, el("span", { class: `mix-key tone-${DECISION_TONE[k] ?? "muted"}`, "aria-hidden": "true" }), el("span", { class: "mix-name" }, DECISION[k] ?? k), el("span", { class: "num mix-n" }, String(decisions[k])), el("span", { class: "num mix-pct" }, `${Math.round((decisions[k] / total) * 100)}%`)))));
}
function latency(l) {
  const x = (v) => (Math.log10(Math.max(1, Math.min(2000, v))) / Math.log10(2000)) * 100;
  const row = (label, p50, p95) => el("div", { class: "lat-row" }, el("span", { class: "lat-label" }, label), el("span", { class: "lat-track", "aria-hidden": "true" }, el("span", { class: "lat-range", style: { left: `${x(p50)}%`, width: `${Math.max(0.5, x(p95) - x(p50))}%` } }), el("span", { class: "lat-dot p50", style: { left: `${x(p50)}%` } }), el("span", { class: "lat-dot p95", style: { left: `${x(p95)}%` } })), el("span", { class: "lat-vals num" }, el("span", {}, ms(p50)), el("span", { class: "lat-p95" }, ms(p95))));
  return el("div", { class: "lat" }, el("h3", { class: "mix-title" }, "Tempo de resposta"), row("Motor", l.engineP50, l.engineP95), row("Ida e volta", l.roundTripP50, l.roundTripP95), el("p", { class: "lat-note num" }, `${l.samples} avaliações recentes, escala logarítmica`));
}
function daily(dailyRows) {
  const max = Math.max(1, ...dailyRows.map((d) => d.total));
  return el(
    "div",
    {},
    el("div", { class: "p-head" }, el("h2", { id: "h-daily" }, "Compras por dia")),
    el("p", { class: "p-sub num" }, `${dailyRows.reduce((s, d) => s + d.total, 0)} compras · ${dailyRows.reduce((s, d) => s + d.protected, 0)} retidas pelo motor`),
    el(
      "div",
      { class: "chart", style: { display: "flex", alignItems: "flex-end", gap: "6px", height: "180px" } },
      dailyRows.slice(-14).map((d) => el("div", { title: `${d.d}: ${d.total}`, style: { flex: "1", height: `${(d.total / max) * 100}%`, background: "var(--ink)", borderRadius: "4px 4px 0 0", minHeight: d.total ? "4px" : "0" } })),
    ),
  );
}

let queueText = "";
function queue(search) {
  lastSearch.tx = location.search;
  const status = search.get("status") ?? "";
  const q = search.get("q") ?? "";
  const page = Math.max(0, Number(search.get("page") || 0) || 0);
  const sort = search.get("sort") || "createdAt";
  const dir = search.get("dir") === "asc" ? "asc" : "desc";
  const days = search.get("days") ?? "";
  const band = search.get("band") ?? "";
  if (!queueText) queueText = q;
  const box = el("div", { class: "o-stack" }, head("Transações", { sub: "A busca, a ordem e a página são calculadas no banco." }), skeleton(220));
  const set = (next) => {
    const params = new URLSearchParams(search);
    for (const [key, value] of Object.entries(next)) value ? params.set(key, value) : params.delete(key);
    if (!Object.prototype.hasOwnProperty.call(next, "page")) params.delete("page");
    go(`/ops/transacoes?${params}`, { replace: true });
  };
  const group = Boolean(STATUS_GROUPS[status]);
  const ask = (extra) => {
    const params = new URLSearchParams({ sort, dir, ...extra });
    if (q) params.set("q", q);
    if (days) params.set("days", days);
    if (band) params.set("band", band);
    return api(`/api/ops/transactions?${params}`, { area: "ops" }).then(asQueue);
  };
  const loadQueue = async () => {
    if (!group) return ask({ page: String(page), size: "25", ...(status ? { status } : {}) });
    try {
      const data = await ask({ page: String(page), size: "25", status });
      if (data.items.length || Number(data.counts?.[status] ?? 0) === 0) return data;
    } catch {
      /* a API em execução ainda não aceita o grupo e responde erro */
    }
    const data = await ask({ page: "0", size: "100" });
    const allowed = new Set(STATUS_GROUPS[status]);
    data.items = data.items.filter((t) => allowed.has(t.status));
    data.total = data.items.length;
    data.pages = 1;
    return data;
  };
  loadQueue()
    .then((data) => {
      const rows = data.items;
      const shownPage = group && data.pages <= 1 ? 0 : page;
      const counts = data.counts;
      const count = (group) => Number(group ? counts[group] ?? 0 : counts.ALL ?? data.total ?? 0);
      const chipItems = [
        ["", "Todas", null, count("")],
        ["ATENCAO", "Pedem atenção", "pause", count("ATENCAO")],
        ["AGUARDANDO", "Aguardando cliente", "question", count("AGUARDANDO")],
        ["BLOCKED", "Bloqueadas", "stop", count("BLOCKED")],
        ["APPROVED_WITH_ALERT", "Com aviso", "alert", count("APPROVED_WITH_ALERT")],
        ["INCIDENT_RECORDED", "Golpe relatado", "flag", count("INCIDENT_RECORDED")],
        ["APPROVED", "Aprovadas", "check", count("APPROVED")],
      ].map(([value, label, ic, n]) => ({ value, label, icon: ic, count: n }));
      if (status && !chipItems.some((c) => c.value === status)) chipItems.push({ value: status, label: STATUS[status]?.label ?? status, count: count(status) });
      const pages = Math.max(1, Number(data.pages) || 1);
      const total = Number(data.total ?? 0);
      const compras = `${total} ${total === 1 ? "compra" : "compras"}`;
      box.replaceChildren(
        head("Transações", { sub: `${periodLabel(days)}. ${compras} neste filtro, página ${shownPage + 1} de ${pages}.` }),
        el(
          "div",
          { class: "o-filters" },
          chips(chipItems, status, (v) => set({ status: v }), "Filtrar por estado"),
          chips(
            [{ value: "", label: "Todo o histórico" }, { value: "7", label: "7 dias" }, { value: "30", label: "30 dias" }],
            days,
            (v) => set({ days: v }),
            "Período",
          ),
          el(
            "form",
            { class: "o-search-field", role: "search", onSubmit: (e) => { e.preventDefault(); set({ q: queueText.trim() }); } },
            icon("search", { size: 16 }),
            el("label", { class: "sr-only", for: "q" }, "Buscar transações"),
            el("input", { id: "q", class: "input", placeholder: "Id, cliente, item ou regra", value: queueText, onInput: (e) => { queueText = e.target.value; if (!e.target.value) set({ q: "" }); }, onBlur: () => queueText.trim() !== q && set({ q: queueText.trim() }) }),
          ),
        ),
        rows.length === 0
          ? el("div", { class: "o-empty" }, icon("search", { size: 22 }), el("strong", {}, "Nenhuma transação com esses filtros"), button({ size: "sm", onClick: () => { queueText = ""; go("/ops/transacoes", { replace: true }); } }, "Limpar filtros"))
          : el(
              "section",
              { class: "panel panel-flush o-table-panel", "aria-label": "Fila de transações" },
              el(
                "div",
                { class: "table-wrap" },
                el(
                  "table",
                  { class: "table o-table" },
                  el(
                    "thead",
                    {},
                    el(
                      "tr",
                      {},
                      [["id", "Transação"], ["customerName", "Cliente"], ["description", "Item"], ["amount", "Valor", "r"], ["score", "Score"], ["ruleId", "Regra"], ["status", "Estado"], ["createdAt", "Quando", "r"]].map(([key, label, align]) => {
                        const on = sort === key;
                        const nextDir = on && dir === "desc" ? "asc" : "desc";
                        return el(
                          "th",
                          { class: align || null, "aria-sort": on ? (dir === "asc" ? "ascending" : "descending") : "none" },
                          el("button", { type: "button", class: "th-sort", "data-on": on ? "" : null, "data-dir": on ? dir : null, onClick: () => set({ sort: key, dir: nextDir }) }, label, icon("chevron", { size: 12, class: "th-sort-ic" })),
                        );
                      }),
                    ),
                  ),
                  el(
                    "tbody",
                    {},
                    rows.map((t) =>
                      el(
                        "tr",
                        { class: `link-row${fresh.has(t.id) ? " is-fresh" : ""}${ATTENTION.includes(t.status) ? " is-hot" : ""}`, tabindex: "0", onClick: () => go(`/ops/transacoes/${t.id}`), onKeydown: (e) => { if (e.key === "Enter") go(`/ops/transacoes/${t.id}`); }, "aria-label": `${t.id}, ${t.customerName}, ${STATUS[t.status]?.label}` },
                        el("td", {}, el("a", { href: `/ops/transacoes/${t.id}`, class: "mono o-id", tabindex: "-1" }, t.id)),
                        el("td", { class: "o-strong" }, t.customerName),
                        el("td", { class: "o-trunc" }, t.description),
                        el("td", { class: "r num" }, brl(t.amount)),
                        el("td", {}, t.score != null ? el("span", { class: `o-score tone-${BAND_TONE[t.band] ?? "muted"}` }, el("span", { class: "num" }, String(t.score))) : "—"),
                        el("td", {}, t.ruleId ? el("span", { class: "o-rule mono" }, t.ruleId) : "—"),
                        el("td", {}, txPill(t.status, "sm")),
                        el("td", { class: "r muted o-when" }, relative(t.createdAt)),
                      ),
                    ),
                  ),
                ),
              ),
            ),
        el(
          "div",
          { class: "o-pager" },
          button({ size: "sm", disabled: shownPage <= 0, onClick: () => set({ page: String(shownPage - 1) }) }, "Anterior"),
          el("span", { class: "num" }, `${compras} · página ${shownPage + 1} de ${pages}`),
          button({ size: "sm", disabled: shownPage + 1 >= pages, onClick: () => set({ page: String(shownPage + 1) }) }, "Próxima"),
        ),
      );
    })
    .catch((e) => box.replaceChildren(pageError(e, () => router.start())));
  return box;
}

function txDetail(id) {
  const back = { to: `/ops/transacoes${lastSearch.tx}`, label: "Transações" };
  const box = el("div", { class: "o-stack", "aria-busy": "true" }, head(id, { heading: el("span", { class: "mono" }, id), back }));
  api(`/api/ops/transactions/${id}`, { area: "ops" })
    .then((data) => paintTx(box, data, back))
    .catch((e) => box.replaceChildren(pageError(e, () => router.start())));
  return box;
}
function paintTx(box, data, back) {
  const s = data.summary;
  const assessments = data.assessments ?? [];
  let idx = Math.max(0, assessments.length - 1);
  let to = "";
  let reason = "";
  let busy = false;
  let error = null;
  const draw = () => {
    const a = assessments[idx];
    const next = NEXT[s.status] ?? [];
    box.replaceChildren(
      head(s.id, { heading: el("span", { class: "mono" }, s.id), back, badge: txPill(s.status), sub: el("span", {}, `${s.customerName} · ${s.description} · `, el("span", { class: "num" }, brl(s.amount)), ` · ${dateTime(s.createdAt)}`) }),
      assessments.length > 1 ? tabs({ value: String(idx), label: "Avaliações desta compra", onChange: (v) => { idx = Number(v); draw(); }, items: assessments.map((x, i) => ({ value: String(i), label: `${x.stage === "initial" ? "No checkout" : x.stage === "recalculated" ? "Após a verificação" : x.stage} · ${DECISION[x.decision] ?? x.decision}` })) }) : null,
      a ? verdict(a) : notice("muted", "Sem avaliação", "O motor ainda não respondeu para esta compra."),
      a ? signals(a) : null,
      data.shadow?.length ? el("section", { class: "panel" }, el("h2", {}, "Modelo em modo sombra"), el("p", { class: "o-note" }, "Esta previsão não aprova nem bloqueia. A decisão da compra continua sendo a do motor."), el("ul", { class: "o-links" }, data.shadow.map((s) => el("li", {}, el("span", {}, s.error ? `Falha registrada: ${s.error}` : `${s.algorithm || "modelo"} · ${s.prediction || "—"} · probabilidade ${s.probability ?? "—"} · motor ${s.engineDecision || "—"}`))))) : null,
      a?.certificate ? certificate(a) : null,
      el(
        "form",
        { class: "panel o-action", onSubmit: async (e) => { e.preventDefault(); const form = e.currentTarget; to = form.querySelector("input[name=to]:checked")?.value || to; reason = form.querySelector("textarea")?.value ?? reason; if (busy || !to || !reason.trim()) return; busy = true; error = null; draw(); try { const r = await api(`/api/ops/transactions/${s.id}/transition`, { method: "POST", body: { to, reason: reason.trim() }, area: "ops" }); toast(`Transação movida para ${STATUS[r.summary.status].label}`); poll(true); paintTx(box, r, back); } catch (err) { error = err; busy = false; draw(); } } },
        el("h2", {}, "Ação do analista"),
        next.length === 0
          ? el("p", {}, icon("lock", { size: 16 }), " Estado final. A compra não pode mais mudar.")
          : el(
              "div",
              { class: "o-stack-sm" },
              el("div", { class: "o-moves", role: "radiogroup", "aria-label": "Mover para" }, next.map((n) => el("label", { class: `o-move tone-${STATUS[n].tone}` }, el("input", { type: "radio", name: "to", value: n, checked: to === n, onChange: () => { to = n; const send = box.querySelector("[data-decision-send]"); if (send) send.disabled = !reason.trim(); } }), txPill(n, "sm")))),
              field({ label: "Justificativa", hint: "Fica na trilha de auditoria.", error: error?.fields?.reason, control: (p) => el("textarea", { ...p, class: "textarea", rows: "3", value: reason, onInput: (e) => { reason = e.target.value; const send = box.querySelector("[data-decision-send]"); if (send) send.disabled = !to || !reason.trim(); } }) }),
              error && !Object.keys(error.fields ?? {}).length ? errorNotice(error) : null,
              button({ type: "submit", variant: "primary", busy, disabled: !to || !reason.trim(), dataset: { decisionSend: "" } }, "Registrar decisão"),
            ),
      ),
      context(data),
      data.incidents?.length
        ? el("section", { class: "panel" }, el("h2", {}, "Relatos ligados"), el("ul", { class: "o-links" }, data.incidents.map((i) => el("li", {}, el("a", { href: `/ops/relatos/${i.id}` }, el("span", { class: "mono" }, i.id), " ", incidentPill(i.status))))))
        : null,
      data.statusLog?.length ? el("section", { class: "panel" }, el("h2", {}, "Histórico de estado"), el("p", { class: "o-note" }, "Automático é o motor ou o sistema. Qualquer outro nome é a pessoa que registrou a ação."), el("ol", { class: "timeline o-timeline" }, data.statusLog.map((c) => el("li", { class: `tone-${STATUS[c.toStatus]?.tone ?? "muted"}` }, el("div", {}, el("strong", {}, STATUS[c.toStatus]?.label ?? c.toStatus), " ", el("span", { class: "muted num" }, dateTime(c.at))), el("div", { class: "small muted" }, `${c.reason || "Sem justificativa registrada"} · ${c.actor === "Motor de risco" || c.actor === "Sistema" ? "Automático" : "Registrado por " + c.actor}`))))) : null,
    );
  };
  draw();
}
function verdict(a) {
  const cert = CERT[a.certificate?.status];
  const changed = a.safety && a.safety.proposed !== a.safety.finalDecision;
  return el(
    "section",
    { class: `panel o-verdict tone-${DECISION_TONE[a.decision] ?? "muted"}`, "aria-label": "Resultado do motor" },
    el("div", { class: `score-ring tone-${BAND_TONE[a.band] ?? "muted"}`, role: "img", "aria-label": `Score ${a.score}, risco ${BAND[a.band] ?? a.band}` }, el("span", { class: "sr-text" }, el("strong", { class: "num" }, String(a.score)), el("span", {}, BAND[a.band] ?? a.band))),
    el(
      "div",
      { class: "o-verdict-main" },
      el("div", { class: "o-verdict-decision" }, el("span", { class: "o-verdict-k" }, "Decisão automática do motor"), changed ? el("span", { class: "o-struck" }, DECISION[a.safety.proposed]) : null, el("span", { class: `o-decision tone-${DECISION_TONE[a.decision] ?? "muted"}` }, icon(DECISION_ICON[a.decision] ?? "info", { size: 18, stroke: 1.9 }), DECISION[a.decision] ?? a.decision), el("span", { class: "o-rule mono" }, a.ruleId)),
      a.ruleText ? el("p", { class: "o-verdict-rule" }, a.ruleText) : null,
      el("dl", { class: "o-facts" }, fact("Confiança", `${a.confidence}%`), fact("Certificado", cert ? cert[0] : "—"), fact("Fontes independentes", String(a.independence?.independentSources ?? "—")), fact("Motor", ms(a.durationMs)), fact("Ida e volta", ms(a.roundTripMs))),
    ),
  );
}
function fact(k, v) {
  return el("div", {}, el("dt", {}, k), el("dd", { class: "num" }, v));
}
function signals(a) {
  const sorted = [...a.signals].sort((x, y) => Math.abs(y.points) - Math.abs(x.points));
  return el("section", { class: "panel panel-flush" }, el("div", { class: "p-head p-pad" }, el("h2", {}, "Sinais ", el("span", { class: "p-meta num" }, String(a.signals.length)))), el("div", { class: "table-wrap" }, el("table", { class: "table o-table" }, el("thead", {}, el("tr", {}, ["Sinal", "Origem", "Fonte", "Pontos", "Confiança"].map((h) => el("th", {}, h)))), el("tbody", {}, sorted.map((sg) => el("tr", {}, el("td", {}, el("div", { class: "o-strong" }, sg.label), el("div", { class: "small muted" }, sg.detail)), el("td", {}, sg.source === "customer" || sg.source === "verification" ? "Declarado" : "Observado"), el("td", {}, SOURCE[sg.source] ?? sg.source), el("td", { class: "num" }, sg.points > 0 ? `+${sg.points}` : String(sg.points)), el("td", { class: "num" }, `${sg.confidence}%`)))))));
}
function certificate(a) {
  const c = a.certificate;
  const cert = CERT[c.status] ?? [c.status, "muted"];
  return el("section", { class: "panel" }, el("div", { class: "p-head" }, el("h2", {}, "Certificado de robustez"), pill(cert[1], cert[0])), el("p", { class: "small" }, c.summary), el("div", { class: "table-wrap" }, el("table", { class: "table o-table" }, el("thead", {}, el("tr", {}, ["Perturbação", "Score", "Decisão"].map((h) => el("th", {}, h)))), el("tbody", {}, (c.perturbations ?? []).map((p) => el("tr", { class: p.changed ? "is-changed" : null }, el("td", {}, p.label), el("td", { class: "num" }, String(p.score)), el("td", {}, DECISION[p.decision] ?? p.decision, p.crossesClass ? pill("risk", "muda de classe") : null)))))));
}
function context(data) {
  const s = data.summary;
  return el(
    "section",
    { class: "panel" },
    el("h2", {}, "Contexto da compra"),
    el(
      "dl",
      { class: "kv" },
      el("dt", {}, "Valor"), el("dd", { class: "num" }, brl(s.amount)),
      el("dt", {}, "Origem declarada"), el("dd", {}, ORIGIN[s.declaredOrigin ?? ""] ?? s.declaredOrigin ?? "—"),
      el("dt", {}, "Aparelho"), el("dd", {}, `${data.device?.name ?? "—"} · ${data.device?.detail ?? ""} `, data.device?.trusted ? pill("ok", "conhecido") : pill("warn", "novo")),
      el("dt", {}, "Entrega"), el("dd", {}, `${data.address?.line1 ?? ""}${data.address?.line2 ? `, ${data.address.line2}` : ""}`),
      el("dt", {}, "Cliente desde"), el("dd", {}, `${data.customer?.since ?? "—"} · ticket médio `, el("span", { class: "num" }, brl(data.customer?.avgTicket90d))),
    ),
    data.answers && Object.keys(data.answers).length ? el("div", {}, el("h3", {}, "Respostas da verificação"), el("dl", { class: "kv" }, Object.entries(data.answers).flatMap(([k, v]) => [el("dt", { class: "mono" }, k), el("dd", { class: "mono" }, v)]))) : null,
    data.coachScript ? el("div", {}, el("h3", {}, "Roteiro recebido pelo cliente"), el("p", { class: "quote small", style: { whiteSpace: "pre-line" } }, data.coachScript)) : null,
  );
}

const INC_GROUPS = { PENDENTES: ["OPEN", "IN_REVIEW"], OPEN: ["OPEN"], IN_REVIEW: ["IN_REVIEW"], CLOSED: ["CLOSED"] };
function incidentList(search) {
  lastSearch.incidents = location.search;
  const status = search.get("status") ?? "";
  const all = incidents ?? [];
  const rows = all.filter((i) => !status || (INC_GROUPS[status] ?? []).includes(i.status)).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  const count = (g) => all.filter((i) => !g || (INC_GROUPS[g] ?? []).includes(i.status)).length;
  return el(
    "div",
    { class: "o-stack" },
    head("Relatos", { sub: "O que os clientes contaram em “Fui vítima”. Cada relato alimenta a inteligência do motor." }),
    chips(
      [
        { value: "", label: "Todos", count: count("") },
        { value: "PENDENTES", label: "Sem conclusão", icon: "eye", count: count("PENDENTES") },
        { value: "OPEN", label: "Novos", icon: "inbox", count: count("OPEN") },
        { value: "IN_REVIEW", label: "Em análise", icon: "eye", count: count("IN_REVIEW") },
        { value: "CLOSED", label: "Encerrados", icon: "check", count: count("CLOSED") },
      ],
      status,
      (v) => go(v ? `/ops/relatos?status=${v}` : "/ops/relatos", { replace: true }),
      "Filtrar por estado",
    ),
    !incidents
      ? skeleton(200)
      : rows.length === 0
        ? el("div", { class: "o-empty" }, icon("inbox", { size: 22 }), el("strong", {}, "Nenhum relato aqui"), el("span", {}, "Quando um cliente registra “Fui vítima” no app Aureon, o relato chega nesta fila."))
        : el(
            "div",
            { class: "table-wrap" },
            el(
              "table",
              { class: "table o-table" },
              el("thead", {}, el("tr", {}, ["Relato", "Tipo", "Canal", "Compra", "Estado", "Recebido"].map((h) => el("th", {}, h)))),
              el("tbody", {}, rows.map((i) => el("tr", { class: `link-row${i.status === "OPEN" ? " is-hot" : ""}`, tabindex: "0", "aria-label": `${i.id}, ${INCIDENT_TYPE[i.type] ?? i.type}`, onClick: () => go(`/ops/relatos/${i.id}`), onKeydown: (e) => { if (e.key === "Enter") go(`/ops/relatos/${i.id}`); } }, el("td", {}, el("a", { href: `/ops/relatos/${i.id}`, class: "mono o-id" }, i.id)), el("td", {}, INCIDENT_TYPE[i.type] ?? i.type), el("td", {}, CHANNEL[i.channel] ?? i.channel), el("td", { class: "mono" }, i.txId ?? "—"), el("td", {}, incidentPill(i.status)), el("td", {}, relative(i.createdAt))))),
            ),
          ),
  );
}
function incidentPill(status) {
  const s = INCIDENT_STATUS[status] ?? { label: status, tone: "muted" };
  return pill(s.tone, s.label, { iconName: INCIDENT_ICON[status] ?? "info", size: "sm" });
}
function incidentTimeline(i, view) {
  const steps = [["Recebido", i.createdAt]];
  if (i.assignedAt) steps.push(["Assumido", i.assignedAt]);
  if (i.closedAt) steps.push(["Encerrado", i.closedAt]);
  const state = i.status === "OPEN" ? "Ainda não foi assumido." : i.status === "IN_REVIEW" ? "Está com um analista." : `Encerrado como ${i.closedAs === "scam" ? "golpe" : "não golpe"}.`;
  return el(
    "section",
    { class: "panel" },
    el("h2", {}, "Linha do tempo"),
    el("p", { class: "o-note" }, state),
    el("ol", { class: "o-tl" }, steps.map(([label, at]) => el("li", {}, el("i", { "aria-hidden": "true" }), el("span", {}, el("strong", {}, label), " ", el("span", { class: "num o-note" }, dateTime(at)))))),
    view.transaction
      ? el("p", {}, el("a", { href: `/ops/transacoes/${view.transaction.id}` }, `Compra relacionada ${view.transaction.id}`))
      : el("p", { class: "o-note" }, "Este relato não está ligado a uma compra."),
  );
}
function personLine(person) {
  if (!person) return "";
  return [person.name, person.email].filter(Boolean).join(" · ");
}
function replyPanel(view, id) {
  const i = view.incident;
  const replies = Array.isArray(view.replies) ? view.replies : [];
  const attendance = view.attendance ?? {};
  let text = "";
  let busy = false;
  let error = null;
  const box = el("section", { class: "panel", "aria-labelledby": "h-replies" });
  const party = (title, value, note) => el("div", {}, el("h3", {}, title), el("strong", {}, value || "—"), el("p", { class: "o-note" }, note));
  const draw = () => {
    const closed = i.status === "CLOSED";
    box.replaceChildren(
      el("h2", { id: "h-replies" }, "Respostas e responsáveis"),
      el(
        "div",
        { class: "o-parties" },
        party("Denunciante", attendance.complainantName || view.customer?.name || i.customerId, "Quem registrou o relato"),
        party("Responsável pelo atendimento", personLine(attendance.attendant) || "Ainda não assumido", "Quem assumiu o caso"),
        party("Responsável pela decisão", personLine(attendance.decision) || "Ainda sem decisão", i.closedAs === "scam" ? "Confirmou o golpe" : i.closedAs === "not-scam" ? "Concluiu que não era golpe" : "O encerramento fica separado das respostas"),
      ),
      view.transaction
        ? el("p", { class: "o-note" }, "As respostas ficam neste relato e na compra ", el("a", { href: `/ops/transacoes/${view.transaction.id}`, class: "mono" }, view.transaction.id), ".")
        : el("p", { class: "o-note" }, "As respostas ficam neste relato."),
      replies.length
        ? el(
            "ol",
            { class: "o-replies" },
            replies.map((r) =>
              el(
                "li",
                { class: "o-reply" },
                el("div", { class: "o-reply-top" }, el("strong", {}, [r.authorName, r.authorEmail].filter(Boolean).join(" · ") || "Responsável sem identificação"), incidentPill(r.serviceStatus), el("span", { class: "num o-note" }, dateTime(r.createdAt))),
                el("p", {}, r.body),
              ),
            ),
          )
        : el("p", { class: "o-note" }, "Nenhuma resposta registrada."),
      closed
        ? el("p", { class: "o-note" }, "Este relato está encerrado. A decisão fica no tratamento e não entra como resposta.")
        : el(
            "form",
            {
              class: "o-stack-sm",
              onSubmit: async (e) => {
                e.preventDefault();
                if (busy || !text.trim()) return;
                busy = true;
                error = null;
                draw();
                try {
                  await api(`/api/ops/incidents/${id}/replies`, { method: "POST", body: { body: text.trim() }, area: "ops" });
                  toast("Resposta registrada");
                  router.start();
                } catch (err) {
                  error = err;
                  busy = false;
                  draw();
                }
              },
            },
            field({
              label: "Resposta",
              hint: i.status === "OPEN" ? "A primeira resposta também assume o relato: ele passa de aberto para em análise." : "A resposta entra no histórico. Encerrar continua sendo uma decisão separada.",
              error: error?.fields?.body,
              control: (p) => el("textarea", { ...p, class: "textarea", rows: "4", value: text, onInput: (e) => { text = e.target.value; const send = box.querySelector("[data-reply-send]"); if (send) send.disabled = !text.trim(); } }),
            }),
            error && !error.fields?.body ? errorNotice(error) : null,
            button({ type: "submit", variant: "primary", busy, disabled: !text.trim(), dataset: { replySend: "" } }, "Registrar resposta"),
          ),
    );
  };
  draw();
  return box;
}
function incidentDetail(id) {
  const back = { to: `/ops/relatos${lastSearch.incidents}`, label: "Relatos" };
  const box = el("div", { class: "o-stack" }, head(id, { heading: el("span", { class: "mono" }, id), back }));
  api(`/api/ops/incidents/${id}`, { area: "ops" }).then((view) => {
    const i = view.incident;
    const replies = replyPanel(view, id);
    let as = "";
    let note = "";
    let busy = false;
    let error = null;
    const draw = () => {
      box.replaceChildren(
        head(i.id, { heading: el("span", { class: "mono" }, i.id), back, badge: incidentPill(i.status), sub: `${view.customer?.name ?? i.customerId} · ${INCIDENT_TYPE[i.type] ?? i.type} · ${dateTime(i.createdAt)}` }),
        el("section", { class: "panel" }, el("h2", {}, "O que o cliente contou"), el("blockquote", { class: "o-story" }, i.description), el("dl", { class: "kv" }, el("dt", {}, "Contato"), el("dd", { class: "mono" }, i.contactHandle ?? "—"), el("dt", {}, "Link"), el("dd", { class: "mono" }, i.contactLink ?? "—"), el("dt", {}, "Destino"), el("dd", {}, i.destinationText ?? "—"))),
        replies,
        incidentTimeline(i, view),
        el("section", { class: "panel" }, el("h2", {}, "Entidades extraídas"), (view.entities ?? []).length ? el("ul", { class: "o-entities" }, view.entities.map((e) => el("li", {}, icon(ENTITY_ICON[e.kind] ?? "info", { size: 17 }), el("span", { class: "mono" }, e.value), pill(e.association === "forte" ? "stop" : e.association === "moderada" ? "risk" : "muted", e.association)))) : el("p", {}, "O relato não trouxe telefone, link, endereço nem vendedor.")),
        el(
          "section",
          { class: "panel o-action" },
          el("h2", {}, "Tratamento"),
          i.status === "OPEN" ? button({ variant: "primary", busy, onClick: async () => { busy = true; draw(); await api(`/api/ops/incidents/${id}/assign`, { method: "POST", area: "ops" }); toast("Relato em análise com você"); router.start(); } }, "Assumir relato") : null,
          i.status === "IN_REVIEW"
            ? el(
                "div",
                { class: "o-stack-sm" },
                el("div", { class: "choices", role: "radiogroup", "aria-label": "Conclusão" }, [["scam", "Confirmado como golpe"], ["not-scam", "Não era golpe"]].map(([k, label]) => el("label", { class: "choice" }, el("input", { type: "radio", name: "as", checked: as === k, onChange: () => { as = k; draw(); } }), el("span", { class: "dot", "aria-hidden": "true" }), el("span", {}, label)))),
                field({ label: "Nota de encerramento", error: error?.fields?.note, control: (p) => el("textarea", { ...p, class: "textarea", rows: "3", value: note, onInput: (e) => { note = e.target.value; } }) }),
                error ? errorNotice(error) : null,
                button({ variant: "primary", busy, disabled: !as || !note.trim(), onClick: async () => { busy = true; error = null; draw(); try { await api(`/api/ops/incidents/${id}/close`, { method: "POST", body: { as, note }, area: "ops" }); toast("Relato encerrado"); router.start(); } catch (e) { error = e; busy = false; draw(); } } }, "Encerrar relato"),
              )
            : null,
          i.status === "CLOSED" ? el("dl", { class: "kv" }, el("dt", {}, "Conclusão"), el("dd", {}, i.closedAs === "scam" ? "Golpe confirmado" : "Não era golpe"), el("dt", {}, "Nota"), el("dd", {}, i.closeNote ?? ""), el("dt", {}, "Encerrado"), el("dd", {}, dateTime(i.closedAt))) : null,
        ),
        view.transaction ? el("a", { href: `/ops/transacoes/${view.transaction.id}`, class: "panel o-linked" }, el("span", { class: "mono" }, view.transaction.id), " ", view.transaction.description) : null,
      );
    };
    draw();
  }).catch((e) => box.replaceChildren(pageError(e, () => router.start())));
  return box;
}

/* ================================================================
 * Inteligência
 * Entidades citadas em relatos e compras, e como elas se ligam.
 * A tela monta a estrutura uma vez e repinta só o que mudou, então
 * digitar, filtrar ou aproximar o mapa não perde foco, rolagem nem posição.
 * ================================================================ */
const IX_SUB = "Telefones, links, contas e vendedores citados em relatos ou vistos em compras. Escolha uma entidade para ver o que se liga a ela.";
const IX_TONE = { forte: "stop", moderada: "risk" };
const IX_RANK = { forte: 0, moderada: 1 };
const IX_SORT = {
  reports: ["Mais relatos", (a, b) => b.reports - a.reports || b.attempts - a.attempts],
  customers: ["Mais clientes", (a, b) => b.customers - a.customers || b.reports - a.reports],
  attempts: ["Mais tentativas", (a, b) => b.attempts - a.attempts || b.reports - a.reports],
  recent: ["Mais recentes", (a, b) => (Date.parse(b.lastSeen) || 0) - (Date.parse(a.lastSeen) || 0)],
};
const IX_PAGE = 40;
const IX_GRAPH_CAP = 24;
const IX_W = 960;
const IX_H = 620;
const IX_REFRESH_MS = 30000;
let intelCache = null;

const ixClamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
const ixPlural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
const ixTone = (assoc) => IX_TONE[assoc] ?? "muted";
const ixKind = (kind) => ENTITY_KIND[kind] ?? kind;
const ixAssocOrder = (a, b) => (IX_RANK[a] ?? 9) - (IX_RANK[b] ?? 9) || String(a).localeCompare(String(b), "pt-BR");

async function loadIntel() {
  const raw = await api("/api/ops/intelligence", { area: "ops" });
  const list = (raw ?? []).map((e) => ({
    ...e,
    reports: Number(e.reports) || 0,
    customers: Number(e.customers) || 0,
    attempts: Number(e.attempts) || 0,
    incidentIds: e.incidentIds ?? [],
    txIds: e.txIds ?? [],
  }));
  intelCache = { list, at: Date.now(), stamp: JSON.stringify(list) };
  return intelCache;
}

function ixRows(list, st, skip) {
  const needle = norm(st.q.trim());
  const sort = IX_SORT[st.sort]?.[1] ?? IX_SORT.reports[1];
  return list
    .filter((e) => (skip === "kind" || !st.kind || e.kind === st.kind)
      && (skip === "assoc" || !st.assoc || e.association === st.assoc)
      && (!st.multi || e.customers > 1)
      && (!needle || norm(`${e.value} ${e.sub ?? ""} ${e.id} ${ixKind(e.kind)} ${e.association}`).includes(needle)))
    .sort(sort);
}

function ixInsight(e) {
  const parts = [];
  if (e.reports > 1 && e.customers > 1) parts.push(`${ixPlural(e.reports, "relato cita", "relatos citam")} esta entidade, vindos de ${e.customers} clientes diferentes: o padrão se repete.`);
  else if (e.reports > 0) parts.push(`${ixPlural(e.reports, "relato cita", "relatos citam")} esta entidade, de ${ixPlural(Math.max(e.customers, 1), "cliente", "clientes")}.`);
  else parts.push("Nenhum relato cita esta entidade. Ela só apareceu em compras.");
  if (e.attempts > 0) parts.push(`Reapareceu em ${ixPlural(e.attempts, "tentativa", "tentativas")} de compra.`);
  return parts.join(" ");
}

/* Distribui até `total` vagas entre grupos sem deixar nenhum grupo sem representante. */
function ixShare(total, sizes) {
  const out = sizes.map(() => 0);
  let left = total;
  let open = sizes.map((s, i) => i).filter((i) => sizes[i] > 0);
  while (left > 0 && open.length) {
    const each = Math.max(1, Math.floor(left / open.length));
    for (const i of [...open]) {
      const give = Math.min(each, sizes[i] - out[i], left);
      out[i] += give;
      left -= give;
      if (out[i] >= sizes[i]) open = open.filter((j) => j !== i);
      if (!left) break;
    }
  }
  return out;
}

/* Mapa em estrela. Os vizinhos saem da lista inteira, não da lista filtrada,
 * para que um filtro na barra lateral nunca esconda uma ligação real. */
function ixGraph(list, focus) {
  if (!focus) return { nodes: [], edges: [], links: [], shown: 0, focus: null, selected: null };
  const reported = (a) => Boolean(a) && a !== "não indicativa";
  const incIds = focus.incidentIds ?? [];
  const txIds = focus.txIds ?? [];
  const incSet = new Set(incIds);
  const txSet = new Set(txIds);
  const incidentsNodes = incIds.map((id) => ({ id: `i:${id}`, kind: "incident", label: id, href: `/ops/relatos/${id}`, hot: true, ref: id }));
  const entityNodes = list
    .filter((o) => o.id !== focus.id && ((o.incidentIds ?? []).some((id) => incSet.has(id)) || (o.txIds ?? []).some((id) => txSet.has(id))))
    .sort((a, b) => b.reports - a.reports || b.attempts - a.attempts)
    .map((o) => ({ id: o.id, kind: "entity", label: o.value, entityKind: o.kind, entity: o, hot: reported(o.association) || reported(focus.association) }));
  const txNodes = txIds.map((id) => ({ id: `t:${id}`, kind: "tx", label: id, href: `/ops/transacoes/${id}`, hot: false, ref: id }));
  const links = [...entityNodes, ...incidentsNodes, ...txNodes];
  const [ne, ni, nt] = ixShare(IX_GRAPH_CAP, [entityNodes.length, incidentsNodes.length, txNodes.length]);
  const spokes = [...entityNodes.slice(0, ne), ...incidentsNodes.slice(0, ni), ...txNodes.slice(0, nt)];
  const cx = IX_W / 2;
  const cy = IX_H / 2;
  const two = spokes.length > 10;
  spokes.forEach((node, i) => {
    const outer = two ? i % 2 === 1 : true;
    const radius = two ? (outer ? 262 : 172) : 232;
    const angle = -Math.PI / 2 + (i * 2 * Math.PI) / Math.max(spokes.length, 1);
    node.angle = angle;
    node.inner = two && !outer;
    node.x = cx + Math.cos(angle) * radius;
    node.y = cy + Math.sin(angle) * radius * 0.86;
    node.r = node.kind === "entity" ? Math.min(26, 17 + (node.entity.reports || 0) * 1.5) : 17;
  });
  const center = { id: focus.id, kind: "entity", role: "center", label: focus.value, x: cx, y: cy, r: 34, entityKind: focus.kind, entity: focus, hot: focus.association === "forte" };
  return {
    focus,
    selected: focus.id,
    nodes: [center, ...spokes],
    edges: spokes.map((node) => ({ from: focus.id, to: node.id, hot: node.hot })),
    links,
    shown: spokes.length,
  };
}

function ixKeepFocus(root, fn) {
  const active = document.activeElement;
  const key = root.contains(active) ? active?.dataset?.key : null;
  fn();
  if (key != null) root.querySelector(`[data-key="${CSS.escape(key)}"]`)?.focus({ preventScroll: true });
}

function ixDownload(name, header, rows) {
  const cell = (v) => `"${String(v ?? "").replaceAll('"', '""')}"`;
  const lines = [header.join(";"), ...rows.map((r) => r.map(cell).join(";"))];
  const blob = new Blob([`\uFEFF${lines.join("\n")}`], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  URL.revokeObjectURL(url);
}

function intelligence(search) {
  const st = {
    q: search.get("q") ?? "",
    kind: search.get("tipo") ?? "",
    assoc: search.get("assoc") ?? "",
    multi: search.get("multi") === "1",
    sort: IX_SORT[search.get("ordem")] ? search.get("ordem") : "reports",
    selected: search.get("e") ?? "",
    picked: Boolean(search.get("e")),
    rel: "",
    limit: IX_PAGE,
  };
  let list = intelCache?.list ?? [];
  let stamp = intelCache?.stamp ?? "";
  let loadedAt = intelCache?.at ?? null;
  let failed = null;
  let detailId = null;

  const sync = () => {
    const p = new URLSearchParams();
    if (st.q.trim()) p.set("q", st.q.trim());
    if (st.kind) p.set("tipo", st.kind);
    if (st.assoc) p.set("assoc", st.assoc);
    if (st.multi) p.set("multi", "1");
    if (st.sort !== "reports") p.set("ordem", st.sort);
    if (st.picked && st.selected) p.set("e", st.selected);
    const qs = p.toString();
    try { history.replaceState(history.state, "", `${location.pathname}${qs ? `?${qs}` : ""}`); } catch { /* sem URL compartilhável */ }
  };
  const filtered = () => Boolean(st.q.trim() || st.kind || st.assoc || st.multi);
  const clear = () => {
    st.q = "";
    st.kind = "";
    st.assoc = "";
    st.multi = false;
    input.value = "";
    st.limit = IX_PAGE;
    paintAll();
  };
  const change = (patch) => {
    Object.assign(st, patch, { limit: IX_PAGE });
    paintAll();
  };
  const pick = (id) => {
    st.selected = id;
    st.picked = true;
    paintMain();
    sync();
  };

  /* ---- peças estáticas ---- */
  const stampNote = el("span", { class: "o-note ix-stamp" });
  const refreshBtn = button({ size: "sm", onClick: () => refresh(false) }, icon("refresh", { size: 14 }), " Atualizar");
  const kpiBox = el("div", { class: "fa-kpis ix-kpis" });
  const input = el("input", {
    id: "ixq",
    class: "input",
    type: "search",
    autocomplete: "off",
    placeholder: "Número, link, conta ou vendedor",
    value: st.q,
    onInput: (e) => { st.q = e.target.value; st.limit = IX_PAGE; paintToolbar(); paintMain(); sync(); },
    onKeydown: (e) => { if (e.key === "Escape" && input.value) { e.preventDefault(); st.q = ""; input.value = ""; paintAll(); } },
  });
  const searchField = el("form", { class: "o-search-field ix-search", role: "search", onSubmit: (e) => e.preventDefault() }, icon("search", { size: 16 }), el("label", { class: "sr-only", for: "ixq" }, "Buscar entidades"), input);
  const kindBox = el("div", { class: "ix-group" });
  const assocBox = el("div", { class: "ix-group" });
  const sortSelect = el(
    "select",
    { id: "ixs", class: "select ix-sort", onChange: (e) => change({ sort: e.target.value }) },
    Object.entries(IX_SORT).map(([key, [label]]) => el("option", { value: key, selected: st.sort === key ? true : null }, label)),
  );
  const clearBtn = button({ size: "sm", variant: "quiet", onClick: clear }, "Limpar filtros");
  const exportBtn = button({ size: "sm", onClick: () => exportRows() }, "Exportar CSV");
  const count = el("span", { class: "o-foot num ix-count", "aria-live": "polite" });
  const toolbar = el(
    "section",
    { class: "ix-toolbar", "aria-label": "Filtros de entidades" },
    el("div", { class: "ix-toolbar-row" }, searchField, el("label", { class: "sr-only", for: "ixs" }, "Ordenar por"), sortSelect, count, clearBtn, exportBtn),
    el("div", { class: "ix-toolbar-row" }, kindBox, assocBox),
  );
  const main = el("div", { class: "ix-main" });
  const listHead = el("div", { class: "ix-list-head" });
  const listUl = el("ul", { class: "ix-list" });
  const listMore = el("div", { class: "ix-more" });
  const listPanel = el("aside", { class: "panel ix-side", "aria-label": "Entidades" }, listHead, listUl, listMore);
  const detail = el("div", { class: "ix-detail" });
  const grid = el("div", { class: "ix" }, listPanel, detail);
  const box = el("div", { class: "o-stack ix-page" }, head("Inteligência", { sub: IX_SUB, actions: el("div", { class: "ix-actions" }, stampNote, refreshBtn) }), kpiBox, toolbar, main);

  /* ---- pintura ---- */
  const chip = (key, label, on, onClick, ic, n) => el(
    "button",
    { type: "button", class: "chip", "data-key": key, "aria-pressed": on ? "true" : "false", onClick },
    ic ? icon(ic, { size: 15 }) : null,
    label,
    n != null ? el("span", { class: "chip-n num" }, String(n)) : null,
  );

  const paintStamp = () => {
    stampNote.textContent = loadedAt ? `Atualizado ${relative(new Date(loadedAt).toISOString())}` : "";
  };

  const paintKpis = () => {
    const strong = list.filter((e) => e.association === "forte").length;
    const multi = list.filter((e) => e.customers > 1).length;
    const attempts = list.reduce((s, e) => s + e.attempts, 0);
    const kinds = new Set(list.map((e) => e.kind)).size;
    const card = (tone, ic, label, value, note, on, act) => {
      const props = { class: `fa-kpi ix-kpi tone-${tone}${act ? " is-action" : ""}` };
      if (act) Object.assign(props, { type: "button", "aria-pressed": on ? "true" : "false", onClick: act });
      return el(
        act ? "button" : "div",
        props,
        el("span", { class: "fa-kpi-top" }, el("span", { class: "fa-kpi-label" }, label), el("span", { class: "fa-kpi-ic", "aria-hidden": "true" }, icon(ic, { size: 15 }))),
        el("strong", { class: "num" }, String(value)),
        el("span", { class: "fa-kpi-note" }, note),
      );
    };
    kpiBox.replaceChildren(
      card("info", "network", "Entidades", list.length, kinds ? `Em ${ixPlural(kinds, "tipo", "tipos")} diferentes` : "Nada mapeado ainda", !filtered(), filtered() ? clear : null),
      card("stop", "shield", "Associação forte", strong, "Entidades com o sinal mais forte", st.assoc === "forte", strong ? () => change({ assoc: st.assoc === "forte" ? "" : "forte" }) : null),
      card("risk", "users", "Em vários clientes", multi, "Citadas por mais de um cliente", st.multi, multi ? () => change({ multi: !st.multi }) : null),
      card("warn", "alert", "Tentativas ligadas", attempts, "Compras em que reapareceram", false, null),
    );
  };

  const paintToolbar = () => {
    const kindRows = ixRows(list, st, "kind");
    const assocRows = ixRows(list, st, "assoc");
    const kinds = [...new Set(list.map((e) => e.kind))].sort((a, b) => list.filter((e) => e.kind === b).length - list.filter((e) => e.kind === a).length);
    const assocs = [...new Set(list.map((e) => e.association))].sort(ixAssocOrder);
    ixKeepFocus(toolbar, () => {
      kindBox.setAttribute("role", "group");
      kindBox.setAttribute("aria-label", "Tipo de entidade");
      kindBox.replaceChildren(
        chip("k:", "Todos os tipos", !st.kind, () => change({ kind: "" }), null, kindRows.length),
        ...kinds.map((k) => chip(`k:${k}`, ixKind(k), st.kind === k, () => change({ kind: st.kind === k ? "" : k }), ENTITY_ICON[k] ?? "network", kindRows.filter((e) => e.kind === k).length)),
      );
      assocBox.setAttribute("role", "group");
      assocBox.setAttribute("aria-label", "Grau de associação");
      assocBox.replaceChildren(
        chip("a:", "Qualquer associação", !st.assoc, () => change({ assoc: "" }), null, assocRows.length),
        ...assocs.map((a) => chip(`a:${a}`, a, st.assoc === a, () => change({ assoc: st.assoc === a ? "" : a }), null, assocRows.filter((e) => e.association === a).length)),
        chip("multi", "Em vários clientes", st.multi, () => change({ multi: !st.multi }), "users"),
      );
    });
    const rows = ixRows(list, st);
    count.textContent = `${rows.length} de ${list.length}`;
    clearBtn.hidden = !filtered();
    exportBtn.disabled = rows.length === 0;
  };

  const paintList = (rows, focus) => {
    const shown = rows.slice(0, st.limit);
    const max = Math.max(1, ...rows.map((e) => e.reports));
    ixKeepFocus(listPanel, () => {
      listHead.replaceChildren(el("strong", {}, "Entidades"), el("span", { class: "o-note num" }, `${shown.length} de ${rows.length}`));
      listUl.replaceChildren(...shown.map((e) => ixItem(e, e.id === focus.id, max, pick)));
      listMore.replaceChildren();
      if (rows.length > shown.length) {
        listMore.append(button({ size: "sm", onClick: () => { st.limit += IX_PAGE; paintMain(); } }, `Mostrar mais ${Math.min(IX_PAGE, rows.length - shown.length)}`));
      }
    });
  };

  const paintDetail = (focus) => {
    const graph = ixGraph(list, focus);
    const linksBox = el("section", { class: "panel panel-flush ix-links", "aria-label": "Ligações" });
    const paintLinks = () => ixKeepFocus(linksBox, () => linksBox.replaceChildren(...ixLinks(graph, st, paintLinks, pick)));
    paintLinks();
    detail.replaceChildren(ixFocusCard(focus), ixBoard(graph, pick), linksBox);
  };

  const paintMain = () => {
    if (failed && !list.length) {
      main.replaceChildren(pageError(failed, () => refresh(false)));
      return;
    }
    if (!loadedAt && !list.length) {
      main.replaceChildren(skeleton(320));
      return;
    }
    if (!list.length) {
      detailId = null;
      main.replaceChildren(el("div", { class: "o-empty" }, icon("network", { size: 22 }), el("strong", {}, "Ainda não há entidades mapeadas"), el("span", {}, "Quando um relato citar telefone, link, conta ou vendedor, o mapa aparece aqui.")));
      return;
    }
    const rows = ixRows(list, st);
    if (!rows.length) {
      detailId = null;
      main.replaceChildren(el("div", { class: "o-empty" }, icon("search", { size: 22 }), el("strong", {}, "Nenhuma entidade com esses filtros"), el("span", {}, "Tente outro termo ou remova um dos filtros."), button({ size: "sm", onClick: clear }, "Limpar filtros")));
      return;
    }
    const at = rows.findIndex((e) => e.id === st.selected);
    const focus = rows[Math.max(at, 0)];
    st.selected = focus.id;
    if (at >= st.limit) st.limit = at + 1;
    if (!grid.isConnected) main.replaceChildren(grid);
    paintList(rows, focus);
    if (detailId !== focus.id) {
      detailId = focus.id;
      st.rel = "";
      paintDetail(focus);
      const it = listUl.querySelector('[aria-pressed="true"]');
      if (it) {
        const top = it.offsetTop;
        const bottom = top + it.offsetHeight;
        if (top < listUl.scrollTop) listUl.scrollTop = Math.max(0, top - 8);
        else if (bottom > listUl.scrollTop + listUl.clientHeight) listUl.scrollTop = bottom - listUl.clientHeight + 8;
      }
    }
  };

  const paintAll = () => {
    paintKpis();
    paintToolbar();
    paintMain();
    sync();
  };

  const exportRows = () => {
    const rows = ixRows(list, st);
    ixDownload(
      "inteligencia-contextshield.csv",
      ["tipo", "valor", "detalhe", "associacao", "relatos", "clientes", "tentativas", "primeiro_visto", "ultimo_visto", "relatos_ids", "compras_ids"],
      rows.map((e) => [ixKind(e.kind), e.value, e.sub, e.association, e.reports, e.customers, e.attempts, e.firstSeen, e.lastSeen, e.incidentIds.join(" "), e.txIds.join(" ")]),
    );
  };

  const refresh = async (quiet) => {
    refreshBtn.disabled = true;
    refreshBtn.setAttribute("aria-busy", "true");
    try {
      const next = await loadIntel();
      failed = null;
      loadedAt = next.at;
      if (next.stamp !== stamp || !list.length) {
        list = next.list;
        stamp = next.stamp;
        detailId = null;
        paintAll();
      }
      paintStamp();
    } catch (e) {
      if (!quiet || !list.length) {
        failed = e instanceof ApiError ? e : new ApiError(0, String(e));
        paintMain();
      }
    } finally {
      refreshBtn.disabled = false;
      refreshBtn.removeAttribute("aria-busy");
    }
  };

  paintStamp();
  paintAll();
  if (!intelCache || Date.now() - intelCache.at > IX_REFRESH_MS / 2) refresh(true);
  const timer = setInterval(() => {
    if (!box.isConnected) return clearInterval(timer);
    paintStamp();
    if (!document.hidden) refresh(true);
  }, IX_REFRESH_MS);
  return box;
}

function ixItem(e, on, max, pick) {
  const tone = ixTone(e.association);
  const bar = el("span", { class: "ix-bar", "aria-hidden": "true" }, el("i", {}));
  bar.firstChild.style.width = `${e.reports ? Math.max(6, Math.round((e.reports / max) * 100)) : 0}%`;
  return el(
    "li",
    {},
    el(
      "button",
      { type: "button", class: `ix-item tone-${tone}`, "data-key": e.id, "aria-pressed": on ? "true" : "false", onClick: () => pick(e.id) },
      el("span", { class: "ix-item-ic", "aria-hidden": "true" }, icon(ENTITY_ICON[e.kind] ?? "network", { size: 16 })),
      el(
        "span",
        { class: "ix-item-main" },
        el("strong", { class: "mono" }, e.value),
        el("span", { class: "ix-item-meta" }, `${ixKind(e.kind)} · ${ixPlural(e.reports, "relato", "relatos")} · ${ixPlural(e.customers, "cliente", "clientes")}`),
      ),
      pill(tone, e.association, { size: "sm" }),
      bar,
    ),
  );
}

function ixFocusCard(e) {
  const tone = ixTone(e.association);
  const when = (iso) => (iso ? el("span", { title: dateTime(iso) }, relative(iso)) : "—");
  return el(
    "section",
    { class: "panel ix-focus", "aria-label": "Entidade selecionada" },
    el(
      "div",
      { class: "ix-focus-top" },
      el("span", { class: `ix-focus-ic tone-${tone}`, "aria-hidden": "true" }, icon(ENTITY_ICON[e.kind] ?? "network", { size: 22 })),
      el(
        "div",
        { class: "ix-focus-id" },
        el("p", { class: "ix-eyebrow" }, ixKind(e.kind)),
        el("h2", { class: "mono" }, e.value),
        e.sub ? el("p", { class: "o-note" }, e.sub) : null,
      ),
      pill(tone, e.association),
    ),
    el("p", { class: "ix-insight" }, ixInsight(e)),
    el(
      "div",
      { class: "og-facts" },
      el("div", {}, el("strong", { class: "num" }, String(e.reports)), el("span", {}, "relatos")),
      el("div", {}, el("strong", { class: "num" }, String(e.customers)), el("span", {}, "clientes")),
      el("div", {}, el("strong", { class: "num" }, String(e.attempts)), el("span", {}, "tentativas")),
    ),
    el("dl", { class: "ix-dates" }, el("div", {}, el("dt", {}, "Primeiro registro"), el("dd", {}, when(e.firstSeen))), el("div", {}, el("dt", {}, "Último registro"), el("dd", {}, when(e.lastSeen)))),
  );
}

function ixLinks(graph, st, repaint, pick) {
  const focus = graph.focus;
  const all = graph.links;
  const incidentById = new Map((incidents ?? []).map((i) => [i.id, i]));
  const txById = new Map((txs ?? []).map((t) => [t.id, t]));
  const groups = [["", "Todas", all.length], ["entity", "Entidades", all.filter((n) => n.kind === "entity").length], ["incident", "Relatos", all.filter((n) => n.kind === "incident").length], ["tx", "Compras", all.filter((n) => n.kind === "tx").length]];
  const rows = all.filter((n) => !st.rel || n.kind === st.rel);
  const open = (node) => (node.kind === "entity" ? pick(node.id) : node.href ? go(node.href) : null);
  const body = rows.map((node) => {
    let relation;
    let detail = "—";
    let state = "—";
    let when;
    if (node.kind === "incident") {
      const i = incidentById.get(node.ref);
      relation = el("span", { class: "ix-rel tone-risk" }, icon("flag", { size: 13 }), "Relato");
      if (i) {
        detail = INCIDENT_TYPE[i.type] ?? i.type;
        state = incidentPill(i.status);
        when = i.createdAt;
      }
    } else if (node.kind === "tx") {
      const t = txById.get(node.ref);
      relation = el("span", { class: "ix-rel tone-ok" }, icon("receipt", { size: 13 }), "Compra");
      if (t) {
        detail = `${brl(t.amount)} · ${t.description}`;
        state = txPill(t.status, "sm");
        when = t.createdAt;
      }
    } else {
      const o = node.entity;
      relation = el("span", { class: "ix-rel tone-info" }, icon(ENTITY_ICON[node.entityKind] ?? "network", { size: 13 }), ixKind(node.entityKind));
      detail = `${ixPlural(o.reports, "relato", "relatos")} · ${ixPlural(o.customers, "cliente", "clientes")}`;
      state = pill(ixTone(o.association), o.association, { size: "sm" });
      when = o.lastSeen;
    }
    const label = node.kind === "entity" ? el("span", { class: "mono o-strong" }, node.label) : el("a", { href: node.href, class: "mono o-id" }, node.label);
    return el(
      "tr",
      { class: "link-row", tabindex: "0", "aria-label": `${node.kind === "incident" ? "Relato" : node.kind === "tx" ? "Compra" : "Entidade"} ${node.label}`, onClick: () => open(node), onKeydown: (e) => { if (e.key === "Enter" && e.target === e.currentTarget) open(node); } },
      el("td", {}, relation),
      el("td", {}, label),
      el("td", {}, detail),
      el("td", {}, state),
      el("td", { class: "r" }, when ? el("span", { title: dateTime(when) }, relative(when)) : "—"),
    );
  });
  return [
    el(
      "div",
      { class: "p-head ix-links-head" },
      el("h2", {}, "Ligações"),
      el("div", { class: "chips", role: "group", "aria-label": "Tipo de ligação" }, groups.filter(([key, , n]) => !key || n).map(([key, label, n]) => el("button", { type: "button", class: "chip", "data-key": `r:${key}`, "aria-pressed": st.rel === key ? "true" : "false", onClick: () => { st.rel = key; repaint(); } }, label, el("span", { class: "chip-n num" }, String(n))))),
    ),
    all.length
      ? el(
          "div",
          { class: "table-wrap" },
          el("table", { class: "table o-table" }, el("thead", {}, el("tr", {}, ["Relação", "Ligação", "Detalhe", "Estado", "Quando"].map((h, i) => el("th", { class: i === 4 ? "r" : null }, h)))), el("tbody", {}, body)),
        )
      : el("div", { class: "o-empty ix-links-empty" }, icon("network", { size: 20 }), el("strong", {}, "Nada ligado a esta entidade ainda"), el("span", {}, `${focus.value} não tem relato, compra ou outra entidade em comum.`)),
  ];
}

function ixBoard(graph, onPick) {
  const focus = graph.focus;
  const board = el("div", { class: "og ix-board", tabindex: "0", role: "group", "aria-label": `Mapa de ligações de ${focus.value}. Setas movem, mais e menos aproximam, zero centraliza.` });
  const tip = el("div", { class: "og-tip", hidden: true });
  const level = el("span", { class: "og-zoom num", "aria-live": "off" }, "100%");
  const pan = el("g", { "data-pan": "1" });
  const svg = el("svg", { viewBox: `0 0 ${IX_W} ${IX_H}`, preserveAspectRatio: "xMidYMid meet" }, pan);
  const pos = new Map(graph.nodes.map((n) => [n.id, n]));
  let view = { x: 0, y: 0, scale: 1 };
  let drag = null;

  pan.append(
    ...graph.edges
      .map((edge) => {
        const a = pos.get(edge.from);
        const b = pos.get(edge.to);
        return a && b ? el("line", { class: `og-edge${edge.hot ? " is-on" : ""}`, x1: a.x.toFixed(1), y1: a.y.toFixed(1), x2: b.x.toFixed(1), y2: b.y.toFixed(1), "data-from": edge.from, "data-to": edge.to }) : null;
      })
      .filter(Boolean),
    ...graph.nodes.map((n) => radialNode(n, n.id === graph.selected)),
  );

  const apply = () => {
    pan.setAttribute("transform", `translate(${view.x.toFixed(1)} ${view.y.toFixed(1)}) scale(${view.scale.toFixed(3)})`);
    level.textContent = `${Math.round(view.scale * 100)}%`;
  };
  const geo = () => {
    const r = svg.getBoundingClientRect();
    const k = Math.min(r.width / IX_W, r.height / IX_H) || 1;
    return { r, k, ox: (r.width - IX_W * k) / 2, oy: (r.height - IX_H * k) / 2 };
  };
  const zoomAt = (factor, cx = IX_W / 2, cy = IX_H / 2) => {
    const scale = ixClamp(view.scale * factor, 0.5, 2.6);
    const f = scale / view.scale;
    view = { scale, x: cx - (cx - view.x) * f, y: cy - (cy - view.y) * f };
    apply();
  };
  const move = (dx, dy) => {
    view = { ...view, x: ixClamp(view.x + dx, -IX_W, IX_W), y: ixClamp(view.y + dy, -IX_H, IX_H) };
    apply();
  };
  const reset = () => {
    view = { x: 0, y: 0, scale: 1 };
    apply();
  };
  const activate = (node) => {
    if (node.dataset.kind === "entity") onPick(node.dataset.id);
    else if (node.dataset.href) go(node.dataset.href);
  };
  const paintHot = (id) => {
    board.querySelectorAll(".is-hot, .is-dim").forEach((n) => n.classList.remove("is-hot", "is-dim"));
    if (!id) return;
    const linked = new Set([id]);
    board.querySelectorAll(".og-edge").forEach((edge) => {
      if (edge.dataset.from === id || edge.dataset.to === id) {
        edge.classList.add("is-hot");
        linked.add(edge.dataset.from);
        linked.add(edge.dataset.to);
      } else edge.classList.add("is-dim");
    });
    board.querySelectorAll(".og-node").forEach((n) => n.classList.add(linked.has(n.dataset.id) ? "is-hot" : "is-dim"));
  };
  const showTip = (node) => {
    const meta = node ? graph.nodes.find((item) => item.id === node.dataset.id) : null;
    if (!meta) {
      tip.hidden = true;
      return;
    }
    const kind = meta.kind === "incident" ? "Relato" : meta.kind === "tx" ? "Compra" : `${ixKind(meta.entityKind) ?? "Entidade"}${meta.entity ? ` · ${meta.entity.association}` : ""}`;
    tip.hidden = false;
    tip.replaceChildren(el("strong", {}, meta.label), el("span", {}, kind));
    const host = board.getBoundingClientRect();
    const rect = node.getBoundingClientRect();
    tip.style.left = `${Math.min(host.width - 228, Math.max(8, rect.left - host.left))}px`;
    tip.style.top = `${Math.max(8, rect.top - host.top - 52)}px`;
  };

  board.addEventListener("wheel", (e) => {
    if (!e.ctrlKey && !e.metaKey) return;
    e.preventDefault();
    const g = geo();
    zoomAt(e.deltaY < 0 ? 1.1 : 1 / 1.1, (e.clientX - g.r.left - g.ox) / g.k, (e.clientY - g.r.top - g.oy) / g.k);
  }, { passive: false });
  board.addEventListener("pointerdown", (e) => {
    if (e.button !== 0 || e.target.closest(".og-tools")) return;
    drag = { id: e.pointerId, x: e.clientX, y: e.clientY, ox: view.x, oy: view.y, moved: false, node: e.target.closest(".og-node") };
  });
  board.addEventListener("pointermove", (e) => {
    if (!drag) return;
    if (!drag.moved && Math.hypot(e.clientX - drag.x, e.clientY - drag.y) < 5) return;
    if (!drag.moved) {
      drag.moved = true;
      board.setPointerCapture?.(drag.id);
      board.classList.add("is-panning");
      showTip(null);
    }
    const k = geo().k;
    view = { ...view, x: ixClamp(drag.ox + (e.clientX - drag.x) / k, -IX_W, IX_W), y: ixClamp(drag.oy + (e.clientY - drag.y) / k, -IX_H, IX_H) };
    apply();
  });
  const end = (e) => {
    if (!drag) return;
    if (!drag.moved && drag.node && e.type === "pointerup") activate(drag.node);
    board.classList.remove("is-panning");
    drag = null;
  };
  board.addEventListener("pointerup", end);
  board.addEventListener("pointercancel", end);
  board.addEventListener("pointerover", (e) => {
    if (drag?.moved) return;
    const node = e.target.closest?.(".og-node");
    paintHot(node?.dataset.id);
    showTip(node);
  });
  board.addEventListener("pointerleave", () => {
    paintHot(null);
    showTip(null);
  });
  board.addEventListener("focusin", (e) => {
    const node = e.target.closest?.(".og-node");
    if (!node) return;
    paintHot(node.dataset.id);
    showTip(node);
  });
  board.addEventListener("focusout", (e) => {
    if (!e.relatedTarget || !board.contains(e.relatedTarget)) {
      paintHot(null);
      showTip(null);
    }
  });
  board.addEventListener("keydown", (e) => {
    const node = e.target.closest?.(".og-node");
    if (node && (e.key === "Enter" || e.key === " ")) {
      e.preventDefault();
      activate(node);
      return;
    }
    if (e.metaKey || e.ctrlKey || e.altKey) return;
    const step = 48;
    const keys = { "+": () => zoomAt(1.25), "=": () => zoomAt(1.25), "-": () => zoomAt(1 / 1.25), "0": reset, ArrowLeft: () => move(step, 0), ArrowRight: () => move(-step, 0), ArrowUp: () => move(0, step), ArrowDown: () => move(0, -step) };
    if (keys[e.key]) {
      e.preventDefault();
      keys[e.key]();
    }
  });

  const hidden = graph.links.length - graph.shown;
  board.append(
    svg,
    el(
      "div",
      { class: "og-tools", role: "toolbar", "aria-label": "Controles do mapa" },
      el("button", { type: "button", class: "og-btn", "aria-label": "Afastar", onClick: () => zoomAt(1 / 1.25) }, "−"),
      level,
      el("button", { type: "button", class: "og-btn", "aria-label": "Aproximar", onClick: () => zoomAt(1.25) }, "+"),
      el("button", { type: "button", class: "og-btn og-btn-text", onClick: reset }, "Centralizar"),
    ),
    el(
      "div",
      { class: "og-legend" },
      el("span", {}, el("i", { class: "lg-entity" }), "Entidade"),
      el("span", {}, el("i", { class: "lg-incident" }), "Relato"),
      el("span", {}, el("i", { class: "lg-tx" }), "Compra"),
      el("span", {}, el("i", { class: "lg-hot" }), "Citada em relato"),
    ),
    el("p", { class: "og-hint" }, hidden > 0 ? `Mostrando ${graph.shown} de ${graph.links.length} ligações. As demais estão na tabela abaixo.` : "Arraste para mover. Ctrl + roda para aproximar."),
    tip,
  );
  if (graph.shown === 0) board.append(el("p", { class: "og-solo" }, "Nada ligado a esta entidade ainda."));
  apply();
  return board;
}

function radialNode(n, on) {
  const glyph = n.kind === "incident" ? "flag" : n.kind === "tx" ? "receipt" : ENTITY_ICON[n.entityKind] ?? "network";
  const radius = n.r ?? (n.role === "center" ? 34 : 18);
  const kindLabel = n.kind === "incident" ? "Relato" : n.kind === "tx" ? "Compra" : ixKind(n.entityKind) ?? "Entidade";
  const group = el("g", {
    class: `og-node${n.role === "center" ? " is-center" : ""}${on ? " is-on" : ""} kind-${n.kind}`,
    transform: `translate(${n.x.toFixed(1)} ${n.y.toFixed(1)})`,
    tabindex: "0",
    role: "button",
    "aria-label": `${kindLabel} ${n.label}${n.role === "center" ? ", no centro" : ""}`,
    "data-id": n.id,
    "data-kind": n.kind,
    "data-href": n.href || null,
  });
  group.append(el("circle", { class: "og-dot", r: String(radius) }));
  if (n.kind !== "tx") group.append(el("circle", { class: `og-pip${n.hot ? " is-hot" : ""}`, cx: String(radius * 0.68), cy: String(-radius * 0.68), r: "5" }));
  const holder = document.createElementNS("http://www.w3.org/2000/svg", "foreignObject");
  holder.setAttribute("x", "-12");
  holder.setAttribute("y", "-12");
  holder.setAttribute("width", "24");
  holder.setAttribute("height", "24");
  holder.setAttribute("pointer-events", "none");
  const wrap = document.createElement("div");
  wrap.style.cssText = "width:24px;height:24px;display:flex;align-items:center;justify-content:center;color:#f7fbff";
  wrap.append(icon(glyph, { size: n.role === "center" ? 18 : 14 }));
  holder.append(wrap);
  group.append(holder);
  const max = n.role === "center" ? 36 : n.inner ? 14 : 18;
  const short = n.label.length > max ? `${n.label.slice(0, max - 1)}…` : n.label;
  let x = 0;
  let y = radius + 22;
  let anchor = "middle";
  if (n.angle != null) {
    const cos = Math.cos(n.angle);
    const sin = Math.sin(n.angle);
    x = cos * (radius + 12);
    y = sin * (radius + 20);
    anchor = cos > 0.45 ? "start" : cos < -0.45 ? "end" : "middle";
    if (sin > 0.2) y += 6;
  }
  group.append(el("text", { class: "og-label", x: x.toFixed(1), y: y.toFixed(1), "text-anchor": anchor }, short));
  return group;
}

function engine(search) {
  const tab = search.get("aba") || "config";
  const box = el("div", { class: "o-stack" }, head("Motor de risco"), skeleton(200));
  Promise.all([api("/api/engine/model"), api("/api/ops/engine/config", { area: "ops" })]).then(([model, cfg]) => {
    let current = cfg;
    let pending = null;
    const draw = () => {
      const down = Object.values(current.providers).filter((p) => p === "down").length + Object.values(current.flags).filter((f) => !f).length;
      box.replaceChildren(
        head("Motor de risco", { sub: `${model.engine} · score base ${model.baseScore} · confiança mínima ${model.minConfidence}%`, badge: down ? pill("warn", "Modo degradado") : pill("ok", "Operando completo") }),
        tabs({ value: tab, label: "Seções do motor", onChange: (v) => go(v === "config" ? "/ops/motor" : `/ops/motor?aba=${v}`, { replace: true }), items: [{ value: "config", label: "Configuração" }, { value: "regras", label: `Regras (${model.rules.length})` }, { value: "sinais", label: `Sinais (${model.signals.length})` }] }),
        tab === "regras"
          ? table(["Regra", "Nome", "Condição", "Decisão"], model.rules.map((r) => [el("span", { class: "o-rule mono" }, r.id), r.name, r.condition, DECISION[r.outcome] ?? r.outcome]))
          : tab === "sinais"
            ? table(["Sinal", "Fonte", "Peso", "Confiança", "Critério"], model.signals.map((sg) => [sg.label, SOURCE[sg.source] ?? sg.source, sg.weight, `${sg.confidence}%`, sg.rationale]))
            : el(
                "div",
                { class: "two-col" },
                toggleList("Comportamentos", "Cada chave muda a próxima avaliação. Útil para mostrar o efeito de cada parte do motor.", model.flags.map((f) => ({ id: f.id, label: f.label, text: f.description, on: current.flags[f.id] ?? false }))),
                toggleList("Fontes de dados", "Desligar uma fonte simula uma falha. O motor perde confiança e o envelope de segurança age.", model.sources.filter((s) => s.hasProvider).map((s) => ({ id: s.id, label: s.label, text: current.providers[s.id] === "down" ? "Indisponível: o motor segue sem esta fonte" : s.provenance, on: current.providers[s.id] !== "down" }))),
              ),
      );
    };
    function toggleList(title, help, items) {
      return el("section", { class: "panel" }, el("h2", {}, title), el("p", { class: "small muted" }, help), el("ul", { class: "o-toggles" }, items.map((f) => el("li", { "data-off": f.on ? null : "true" }, el("label", {}, el("span", { class: "o-toggle-text" }, el("strong", {}, f.label), el("span", {}, f.text)), el("input", { type: "checkbox", role: "switch", class: "switch", checked: f.on, disabled: pending === f.id, onChange: async (e) => {
                const next = e.target.checked;
                e.target.checked = !next;
                if (!window.confirm(`${next ? "Ligar" : "Desligar"} “${f.label}”? Vale na próxima avaliação e fica na auditoria.`)) return;
                pending = f.id;
                draw();
                try {
                  current = await api("/api/ops/engine/config", { method: "PUT", body: { key: f.id, enabled: next }, area: "ops" });
                  config = current;
                  toast(next ? "Ligado. Vale a partir da próxima avaliação." : "Desligado. Vale a partir da próxima avaliação.");
                } catch (err) {
                  toast(err.message || "Não foi possível salvar.", "stop");
                } finally {
                  pending = null;
                  draw();
                  paintChrome();
                }
              } }))))));
    }
    draw();
  }).catch((e) => box.replaceChildren(pageError(e, () => router.start())));
  return box;
}
function table(headers, rows) {
  return el("div", { class: "table-wrap" }, el("table", { class: "table o-table" }, el("thead", {}, el("tr", {}, headers.map((h) => el("th", {}, h)))), el("tbody", {}, rows.map((cells) => el("tr", {}, cells.map((c) => el("td", {}, c)))))));
}

function audit() {
  const box = el("div", { class: "o-stack" }, head("Auditoria", { sub: "Eventos mais recentes primeiro." }), skeleton(200));
  let q = "";
  let action = "";
  let page = 0;
  const size = 50;
  let timer = null;
  const load = () => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (action) params.set("action", action);
    if (q.trim()) params.set("q", q.trim());
    api(`/api/ops/audit?${params}`, { area: "ops" }).then((payload) => render(asAudit(payload))).catch((e) => box.replaceChildren(pageError(e, () => load())));
  };
  const render = (data) => {
    let rows = data.items;
    let total = data.total;
    let pages = Math.max(1, Number(data.pages) || 1);
    if (data.client) {
      const query = norm(q.trim());
      const all = rows.filter((a) => (!action || a.action === action) && (!query || norm(`${a.actor ?? ""} ${a.action ?? ""} ${a.target ?? ""} ${a.detail ?? ""}`).includes(query)));
      total = all.length;
      pages = Math.max(1, Math.ceil(total / size) || 1);
      if (page > pages - 1) page = pages - 1;
      rows = all.slice(page * size, page * size + size);
    }
    const focused = document.activeElement?.id;
    box.replaceChildren(
      head("Auditoria", { sub: "Página atual da trilha. A exportação baixa todos os eventos filtrados, até 5000, não só esta página." }),
      el(
        "div",
        { class: "o-filters" },
        el("div", { class: "o-search-field" }, icon("search", { size: 16 }), el("label", { class: "sr-only", for: "aq" }, "Filtrar eventos"), el("input", { id: "aq", class: "input", placeholder: "Pessoa, ação ou alvo", value: q, onInput: (e) => { q = e.target.value; page = 0; clearTimeout(timer); timer = setTimeout(load, 250); } })),
        el("label", { class: "sr-only", for: "aa" }, "Tipo de ação"),
        el("select", { id: "aa", class: "select", value: action, onChange: (e) => { action = e.target.value; page = 0; load(); } }, el("option", { value: "" }, "Todas as ações"), (data.actions ?? []).map((name) => el("option", { value: name }, name))),
        button({ size: "sm", onClick: () => exportAudit(q, action) }, "Exportar filtrados"),
        el("span", { class: "o-foot num" }, `${total ?? 0} eventos`),
      ),
      rows.length
        ? table(["Quando", "Quem", "Ação", "Alvo", "Detalhe"], rows.map((a) => [dateTime(a.at), a.actor, a.action, a.target?.startsWith("TX-") ? el("a", { href: `/ops/transacoes/${a.target}`, class: "mono" }, a.target) : a.target?.startsWith("CS-") ? el("a", { href: `/ops/relatos/${a.target}`, class: "mono" }, a.target) : a.target ?? "", a.detail ?? ""]))
        : el("div", { class: "o-empty" }, el("strong", {}, "Nenhum evento com esse filtro")),
      el(
        "div",
        { class: "o-pager" },
        button({ size: "sm", disabled: page <= 0, onClick: () => { page -= 1; load(); } }, "Anterior"),
        el("span", { class: "num" }, `Página ${page + 1} de ${pages}`),
        button({ size: "sm", disabled: page + 1 >= pages, onClick: () => { page += 1; load(); } }, "Próxima"),
      ),
    );
    const chosen = box.querySelector("#aa");
    if (chosen) chosen.value = action;
    if (focused) box.querySelector(`#${CSS.escape(focused)}`)?.focus();
  };
  load();
  return box;
}
async function exportAudit(q, action) {
  const params = new URLSearchParams();
  if (action) params.set("action", action);
  if (q.trim()) params.set("q", q.trim());
  const token = getSession("ops")?.token;
  const res = await fetch(`/api/ops/audit/export?${params}`, { headers: token ? { Authorization: `Bearer ${token}` } : {} });
  if (!res.ok) {
    toast("Não foi possível exportar.", "stop");
    return;
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "auditoria-contextshield.csv";
  a.click();
  URL.revokeObjectURL(url);
  if (res.headers.get("X-Export-Truncated") === "true") toast("A exportação parou nos 5000 eventos filtrados mais recentes.");
}

function users(search) {
  if (session()?.user.role !== "ADMIN") return el("div", { class: "o-stack" }, empty("Somente administração", { iconName: "lock", text: "Peça a alguém com papel de administração para gerenciar usuários." }));
  const box = el("div", { class: "o-stack" }, head("Usuários", { sub: "Quem acessa o sistema e com qual papel." }), skeleton(160));
  const openNew = search.get("novo") === "1";
  api("/api/admin/users", { area: "ops" }).then((list) => {
    const draw = (rows) => {
      const table = el(
        "table",
        { class: "table o-table" },
        el("thead", {}, el("tr", {}, ["Pessoa", "Papel", "Cliente", "Situação", ""].map((h) => el("th", {}, h)))),
        el(
          "tbody",
          {},
          rows.map((u) =>
            el(
              "tr",
              {},
              el("td", {}, el("strong", {}, u.name), el("div", { class: "mono small muted" }, u.email)),
              el("td", {}, pill(ROLE_TONE[u.role] ?? "muted", ROLE[u.role] ?? u.role, { plain: true })),
              el("td", { class: "mono" }, u.customerId ?? "—"),
              el("td", {}, u.active === false ? pill("muted", "Inativo") : pill("ok", "Ativo")),
              el(
                "td",
                {},
                button({ size: "sm", variant: "quiet", icon: true, "aria-label": `Editar ${u.email}`, onClick: () => userDialog(u, () => router.start()) }, icon("edit", { size: 16 })),
                button({ size: "sm", variant: "quiet", icon: true, disabled: u.email === session()?.user.email, "aria-label": `Excluir ${u.email}`, onClick: () => removeUser(u, () => router.start()) }, icon("trash", { size: 16 })),
              ),
            ),
          ),
        ),
      );
      box.replaceChildren(
        head("Usuários", { sub: "Quem acessa o sistema e com qual papel.", actions: button({ variant: "primary", onClick: () => userDialog(null, () => router.start()) }, icon("plus", { size: 18 }), " Novo usuário") }),
        el("div", { class: "table-wrap" }, table),
      );
    };
    draw(list);
    if (openNew) userDialog(null, () => router.start());
  }).catch((e) => box.replaceChildren(pageError(e, () => router.start())));
  return box;
}
function userDialog(user, onDone) {
  const f = { name: user?.name ?? "", email: user?.email ?? "", role: user?.role ?? "ANALYST", customerId: user?.customerId ?? "", password: "", active: user?.active !== false };
  let error = null;
  let busy = false;
  let customers = [];
  const body = el("div");
  const d = dialog({ title: user ? "Editar usuário" : "Novo usuário", onClose: () => d.remove(), body });
  const draw = () => {
    const e = error?.fields ?? {};
    body.replaceChildren(
      el(
        "form",
        { class: "stack", novalidate: true, onSubmit: async (ev) => { ev.preventDefault(); busy = true; error = null; draw(); try { if (user) { const payload = { name: f.name, role: f.role, active: f.active }; if (f.password) payload.password = f.password; await api(`/api/admin/users/${user.id}`, { method: "PUT", body: payload, area: "ops" }); toast("Usuário atualizado"); } else { await api("/api/admin/users", { method: "POST", body: { ...f, customerId: f.role === "CUSTOMER" ? f.customerId : null }, area: "ops" }); toast("Usuário criado"); } d.close(); onDone(); } catch (err) { error = err; busy = false; draw(); } } },
        error && !Object.keys(e).length ? errorNotice(error) : null,
        field({ label: "Nome", error: e.name, control: (p) => el("input", { ...p, class: "input", value: f.name, onInput: (e) => { f.name = e.target.value; } }) }),
        field({ label: "E-mail", error: e.email, control: (p) => el("input", { ...p, class: "input", type: "email", disabled: !!user, value: f.email, onInput: (e) => { f.email = e.target.value; } }) }),
        field({ label: "Papel", error: e.role, control: (p) => el("select", { ...p, class: "select", value: f.role, onChange: (e) => { f.role = e.target.value; draw(); } }, Object.entries(ROLE).map(([k, v]) => el("option", { value: k }, v))) }),
        f.role === "CUSTOMER" && !user ? field({ label: "Cliente", error: e.customerId, control: (p) => el("select", { ...p, class: "select", value: f.customerId, onChange: (e) => { f.customerId = e.target.value; } }, el("option", { value: "" }, "Escolha"), customers.map((c) => el("option", { value: c.id }, `${c.name} (${c.id})`))) }) : null,
        field({ label: user ? "Nova senha" : "Senha", hint: user ? "Deixe em branco para manter a atual." : "De 8 a 72 caracteres.", error: e.password, control: (p) => el("input", { ...p, class: "input", type: "password", autocomplete: "new-password", value: f.password, onInput: (e) => { f.password = e.target.value; } }) }),
        user ? el("label", { class: "check" }, el("input", { type: "checkbox", checked: f.active, onChange: (e) => { f.active = e.target.checked; } }), "Conta ativa") : null,
        button({ type: "submit", variant: "primary", busy }, "Salvar"),
      ),
    );
  };
  draw();
  if (!user) api("/api/ops/customers", { area: "ops" }).then((c) => { customers = c; draw(); }).catch(() => {});
}
function removeUser(u, onDone) {
  const body = el("div", {}, el("p", { class: "muted" }, `${u.email} perde o acesso imediatamente. A ação fica registrada na auditoria.`), el("div", { class: "row", style: { justifyContent: "flex-end" } }, button({ onClick: () => d.close() }, "Manter"), button({ variant: "danger", onClick: async () => { await api(`/api/admin/users/${u.id}`, { method: "DELETE", area: "ops" }); toast(`${u.email} removido`); d.close(); onDone(); } }, "Excluir")));
  const d = dialog({ title: "Excluir usuário?", onClose: () => d.remove(), body });
}

function demo(search) {
  const box = el("div", { class: "o-stack" }, head("Demonstração", { sub: "Roteiro da banca: cada cenário passa pelo mesmo caminho do checkout real." }), skeleton(180));
  Promise.all([api("/api/catalog/scenarios"), api("/api/ops/customers", { area: "ops" })]).then(([scenarios, customers]) => {
    const results = {};
    let running = null;
    let error = null;
    const alert = { customerId: customers[0]?.id ?? "CUST-001", title: "", body: "" };
    if (search.get("confirmar") === "reiniciar") confirmReset();
    const draw = () => {
      box.replaceChildren(
        head("Demonstração", { sub: "Roteiro da banca: cada cenário passa pelo mesmo caminho do checkout real.", actions: button({ variant: "danger", onClick: confirmReset }, icon("refresh", { size: 18 }), " Reiniciar dados") }),
        error ? errorNotice(error) : null,
        el("h2", { class: "o-sec-h" }, "Cenários"),
        el(
          "ul",
          { class: "o-scen-grid" },
          scenarios.map((s) => {
            const r = results[s.id];
            return el(
              "li",
              { class: "panel o-scen", "data-done": r ? "true" : null },
              el("div", { class: "o-scen-top" }, el("span", { class: "o-scen-code mono" }, s.code), el("h3", {}, s.title)),
              el("p", { class: "o-scen-sum" }, s.summary),
              el("p", { class: "o-scen-exp" }, s.expected),
              el("div", { class: "o-scen-foot" }, r ? el("a", { href: `/ops/transacoes/${r.summary.id}` }, txPill(r.summary.status, "sm"), " ", el("span", { class: "mono small" }, `${r.summary.id} · ${r.summary.score} · ${r.summary.ruleId}`)) : el("span", { class: "small muted" }, "Ainda não rodado"), button({ size: "sm", variant: r ? null : "primary", busy: running === s.id, onClick: () => run(s) }, icon("play", { size: 15 }), r ? " De novo" : " Rodar")),
            );
          }),
        ),
        el(
          "form",
          { id: "aviso", class: "panel", onSubmit: async (e) => { e.preventDefault(); await api("/api/ops/alerts", { method: "POST", body: alert, area: "ops" }); toast("Aviso enviado ao app do cliente"); alert.title = ""; alert.body = ""; draw(); } },
          el("h2", {}, "Enviar aviso ao cliente"),
          field({ label: "Cliente", control: (p) => el("select", { ...p, class: "select", value: alert.customerId, onChange: (e) => { alert.customerId = e.target.value; } }, customers.map((c) => el("option", { value: c.id }, c.name))) }),
          field({ label: "Título", control: (p) => el("input", { ...p, class: "input", value: alert.title, onInput: (e) => { alert.title = e.target.value; } }) }),
          field({ label: "Mensagem", control: (p) => el("textarea", { ...p, class: "textarea", rows: "3", value: alert.body, onInput: (e) => { alert.body = e.target.value; } }) }),
          button({ type: "submit", variant: "primary" }, icon("bell", { size: 16 }), " Enviar aviso"),
        ),
      );
    };
    async function run(s) {
      running = s.id;
      error = null;
      draw();
      try {
        const r = await api(`/api/ops/demo/scenarios/${s.id}/run`, { method: "POST", body: {}, area: "ops" });
        results[s.id] = r;
        poll(true);
      } catch (e) {
        error = e;
      } finally {
        running = null;
        draw();
      }
    }
    function confirmReset() {
      const body = el("div", {}, el("p", { class: "muted" }, "Apaga compras, relatos e avisos criados durante a apresentação e recria a história original. Usuários e catálogo ficam."), el("div", { class: "row", style: { justifyContent: "flex-end" } }, button({ onClick: () => d.close() }, "Cancelar"), button({ variant: "danger", onClick: async () => { const r = await api("/api/ops/demo/reset", { method: "POST", area: "ops" }); toast(`Demonstração reiniciada: ${r.transactions} transações da história`); d.close(); poll(false).then(() => router.start()); } }, "Reiniciar")));
      const d = dialog({ title: "Reiniciar os dados da demonstração?", onClose: () => d.remove(), body });
    }
    draw();
  }).catch((e) => box.replaceChildren(pageError(e, () => router.start())));
  return box;
}

function pageError(error, onRetry) {
  return el("div", { class: "o-error", role: "alert" }, el("h2", {}, "Não foi possível carregar"), el("p", {}, error.status === 404 ? "Não encontramos este registro. Ele pode ter sido apagado ao reiniciar a demonstração." : error.message), onRetry && error.status !== 404 ? button({ size: "sm", onClick: onRetry }, "Tentar de novo") : null);
}

function openPalette(isAdmin) {
  if (paletteOpen) return;
  paletteOpen = true;
  const pages = [
    ["/ops", "Visão geral"],
    ["/ops/transacoes", "Transações"],
    ["/ops/transacoes?status=BLOCKED", "Transações bloqueadas"],
    ["/ops/relatos", "Relatos"],
    ["/ops/inteligencia", "Inteligência"],
    ["/ops/motor", "Motor de risco"],
    ["/ops/politicas", "Políticas de risco"],
    ["/ops/laboratorio", "Laboratório"],
    ["/ops/auditoria", "Auditoria"],
    ...(isAdmin ? [["/ops/usuarios", "Usuários"]] : []),
    ["/ops/demo", "Demonstração"],
  ];
  let q = "";
  let active = 0;
  const body = el("div", { class: "cmdk-box" });
  const d = el("dialog", { class: "cmdk", "aria-label": "Buscar ou ir para", onClick: (e) => { if (e.target === d) close(); }, onClose: () => { paletteOpen = false; d.remove(); } });
  function items() {
    const query = norm(q.trim());
    const txsItems = (txs ?? []).filter((t) => !query || norm(`${t.id} ${t.customerName} ${t.description}`).includes(query)).slice(0, 6).map((t) => ({ label: t.id, sub: t.customerName, run: () => go(`/ops/transacoes/${t.id}`) }));
    const pageItems = pages.filter((p) => !query || norm(p[1]).includes(query)).map(([to, label]) => ({ label, run: () => go(to) }));
    return [...pageItems, ...txsItems];
  }
  function draw() {
    const list = items();
    active = Math.min(active, Math.max(0, list.length - 1));
    body.replaceChildren(
      el("div", { class: "cmdk-field" }, icon("search", { size: 18 }), el("input", { class: "cmdk-input", role: "combobox", "aria-label": "Buscar transação, relato, página ou ação", placeholder: "Buscar transação, relato, página ou ação…", value: q, onInput: (e) => { q = e.target.value; active = 0; draw(); }, onKeydown: (e) => { const listNow = items(); if (e.key === "ArrowDown") { e.preventDefault(); active = (active + 1) % Math.max(1, listNow.length); draw(); } else if (e.key === "ArrowUp") { e.preventDefault(); active = (active - 1 + listNow.length) % Math.max(1, listNow.length); draw(); } else if (e.key === "Enter") { e.preventDefault(); listNow[active]?.run(); close(); } } })),
      el("div", { class: "cmdk-list", role: "listbox" }, list.map((it, i) => el("div", { class: "cmdk-item", role: "option", "aria-selected": i === active ? "true" : "false", "data-active": i === active ? "true" : null, onClick: () => { it.run(); close(); } }, el("span", { class: "cmdk-label" }, it.label), it.sub ? el("span", { class: "cmdk-sub" }, it.sub) : null))),
    );
    d.replaceChildren(body);
    queueMicrotask(() => body.querySelector("input")?.focus());
  }
  function close() {
    paletteOpen = false;
    d.close();
  }
  document.body.append(d);
  d.showModal();
  draw();
}

function loginScreen() {
  setTitle("Entrar · Painel");
  document.documentElement.dataset.ops = "";
  shell.className = "ops ops-auth";
  shell.replaceChildren(
    el(
      "main",
      { class: "auth-card" },
      el("div", { class: "auth-brand" }, mark(30), el("span", {}, "ContextShield")),
      loginForm({
        area: "ops",
        heading: el("h1", { class: "auth-title" }, "Painel de operações"),
        intro: "Acesso da equipe de prevenção a fraudes.",
        suggestions: [
          { label: "Analista", email: "analista@contextshield.example" },
          { label: "Admin", email: "admin@contextshield.example" },
        ],
        onDone: () => go("/ops"),
      }),
    ),
    el("p", { class: "auth-foot" }, "Acesso restrito. Entradas, decisões e mudanças de configuração ficam registradas na auditoria."),
  );
}

async function render(loc) {
  if (!session()) {
    loginScreen();
    if (!shell.parentElement) document.body.append(shell);
    return;
  }
  shell.className = "ops";
  if (!txs) await poll(false);
  opsShell(loc);
  let node;
  if (loc.name === "home") node = overview();
  else if (loc.name === "txs") node = queue(loc.search);
  else if (loc.name === "tx") node = txDetail(loc.params.id);
  else if (loc.name === "incs") node = incidentList(loc.search);
  else if (loc.name === "inc") node = incidentDetail(loc.params.id);
  else if (loc.name === "intel") node = intelligence(loc.search);
  else if (loc.name === "engine") node = engine(loc.search);
  else if (loc.name === "policies") node = policiesPage(session().user.role === "ADMIN");
  else if (loc.name === "lab") node = labPage(session().user.role === "ADMIN");
  else if (loc.name === "audit") node = audit();
  else if (loc.name === "users") node = users(loc.search);
  else if (loc.name === "demo") node = demo(loc.search);
  else node = empty("Página não encontrada", { iconName: "search", action: el("a", { href: "/ops" }, "Voltar para a visão geral") });
  page.replaceChildren(el("div", { class: "o-route" }, node));
  if (!shell.parentElement) document.body.append(shell);
}

function mount() {
  router = createRouter({
    base: "/ops",
    routes: [
      { name: "home", pattern: /^$/ },
      { name: "txs", pattern: /^transacoes$/ },
      { name: "tx", pattern: /^transacoes\/(?<id>[^/]+)$/ },
      { name: "incs", pattern: /^relatos$/ },
      { name: "inc", pattern: /^relatos\/(?<id>[^/]+)$/ },
      { name: "intel", pattern: /^inteligencia$/ },
      { name: "engine", pattern: /^motor$/ },
      { name: "policies", pattern: /^politicas$/ },
      { name: "lab", pattern: /^laboratorio$/ },
      { name: "audit", pattern: /^auditoria$/ },
      { name: "users", pattern: /^usuarios$/ },
      { name: "demo", pattern: /^demo$/ },
    ],
    render,
  });
  onSession(() => {
    txs = null;
    intelCache = null;
    router.start();
  });
  document.addEventListener("keydown", (e) => {
    if (!session()) return;
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
      e.preventDefault();
      if (paletteOpen) document.querySelector("dialog.cmdk")?.close();
      else openPalette(session().user.role === "ADMIN");
      return;
    }
    const typing = e.target?.closest?.("input, textarea, select, [contenteditable='true']");
    if (e.metaKey || e.ctrlKey || e.altKey || typing || document.querySelector("dialog[open]")) return;
    if (e.key === "g") {
      window.__csG = Date.now();
      return;
    }
    if (window.__csG && Date.now() - window.__csG < 1000 && GO[e.key]) {
      e.preventDefault();
      go(GO[e.key]);
    }
    window.__csG = 0;
  });
  setInterval(() => {
    if (!document.hidden && session()) poll(true);
  }, 10000);
  router.start();
}
mount();
