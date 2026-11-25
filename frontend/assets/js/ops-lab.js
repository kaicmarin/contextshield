import { api } from "./api/client.js";
import { button, notice, pill } from "./ui.js";
import { el } from "./utils/dom.js";
import { dateTime, setTitle } from "./utils/format.js";
import { icon } from "./utils/icons.js";

function head(title, sub) {
  setTitle(title);
  return el("header", { class: "o-head" }, el("div", { class: "o-head-row" }, el("div", { class: "o-head-text" }, el("h1", {}, title), sub ? el("p", { class: "o-head-sub" }, sub) : null)));
}

function table(headers, rows) {
  return el("div", { class: "table-wrap" }, el("table", { class: "table o-table" }, el("thead", {}, el("tr", {}, headers.map((h) => el("th", {}, h)))), el("tbody", {}, rows.map((cells) => el("tr", {}, cells.map((c) => el("td", {}, c)))))));
}

const STATUS_TONE = { PUBLISHED: "ok", APPROVED: "info", PENDING: "warn", DRAFT: "muted", REJECTED: "stop", ARCHIVED: "muted", TRAINED: "info", PASS: "ok", FAIL: "stop", SUGGESTED: "warn", DONE: "ok", RUNNING: "info", FAILED: "stop", QUEUED: "muted" };
const STATUS_LABEL = { PUBLISHED: "Publicada", APPROVED: "Aprovada", PENDING: "Em aprovação", DRAFT: "Rascunho", REJECTED: "Rejeitada", ARCHIVED: "Arquivada" };
const PROFILE_LABEL = { fintech: "Conta e cartão", marketplace: "Marketplace", ecommerce: "Loja própria", giftcard: "Vale-presente" };
const WEIGHT_GROUPS = [
  ["Faixas", ["BASE_SCORE", "THRESHOLD_MEDIUM", "THRESHOLD_HIGH", "THRESHOLD_BLOCK"]],
  ["Valor", ["AMOUNT_WITHIN_HABIT", "AMOUNT_ABOVE_LOW", "AMOUNT_ABOVE_MID", "AMOUNT_ABOVE_HIGH"]],
  ["Cliente", ["HISTORY_SPARSE", "LONG_TENURE", "UNUSUAL_HOUR"]],
  ["Aparelho", ["KNOWN_DEVICE", "SESSION_CONSISTENT", "LOCATION_CONSISTENT", "NEW_DEVICE", "SESSION_ANOMALY"]],
  ["Entrega", ["HABITUAL_DESTINATION", "NEW_DESTINATION_OWN", "NEW_DESTINATION_THIRD", "PICKUP_POINT"]],
  ["Vendedor", ["ESTABLISHED_SELLER", "RECENT_SELLER", "SELLER_HIGH_CLAIMS", "SELLER_IN_INCIDENT"]],
  ["Origem", ["ORIGIN_SELF", "ORIGIN_AD", "ORIGIN_LINK", "ORIGIN_GUIDED"]],
  ["Verificação", ["VER_ASKED", "VER_CONTACT", "VER_ADDRESS_GIVEN", "VER_CLEAR", "VER_COACHED", "SCRIPT_ECHO", "ANCHOR_FROM_SCRIPT", "EVIDENCE_DIVERGENCE"]],
  ["Inteligência", ["DESTINATION_IN_INCIDENT", "CONFIRMED_INDICATOR"]],
];

export function policiesPage(isAdmin) {
  const box = el("div", { class: "o-stack" }, head("Políticas de risco", "Mude o peso de cada sinal. A compra continua na versão publicada."), el("p", { class: "muted" }, "Carregando…"));
  let policies = [];
  let catalog = [];
  let patterns = [];
  let selected = null;
  const load = async () => {
    try {
      const [list, cat, kinds] = await Promise.all([
        api("/api/ops/policies", { area: "ops" }),
        api("/api/ops/policies/catalog", { area: "ops" }),
        api("/api/ops/policies/patterns", { area: "ops" }),
      ]);
      policies = list;
      catalog = cat.signals;
      patterns = kinds;
      if (!selected) {
        const draft = policies.find((p) => p.profile === "fintech" && (p.status === "DRAFT" || p.status === "REJECTED"));
        const published = policies.find((p) => p.profile === "fintech" && p.status === "PUBLISHED");
        selected = (draft || published || policies[0])?.id ?? null;
      }
      if (selected) await open(selected);
      else paint();
    } catch (error) {
      box.replaceChildren(head("Políticas de risco"), notice("stop", "Não foi possível carregar", error.message));
    }
  };
  const open = async (id) => {
    selected = id;
    const full = await api(`/api/ops/policies/${id}`, { area: "ops" });
    policies = policies.map((item) => item.id === id ? { ...item, ...full } : item);
    paint();
  };
  const paint = () => {
    const current = policies.find((p) => p.id === selected);
    const published = policies.find((p) => p.profile === "fintech" && p.status === "PUBLISHED");
    box.replaceChildren(
      head("Políticas de risco", "Cada sinal tem um peso. Salvar grava um rascunho. A compra só muda quando um administrador publica."),
      notice("info", published ? `A loja usa ${published.name}` : "Nenhuma política publicada", "Conta e cartão decide a compra. Os outros padrões ficam em rascunho até um administrador publicar."),
      el("section", { class: "panel" },
        el("h2", {}, "Padrões por tipo de comércio"),
        el("div", { class: "pol-cards" }, patterns.map((pattern) => {
          const highlights = [...(pattern.changes || [])].sort((a, b) => Math.abs(b.to - b.from) - Math.abs(a.to - a.from)).slice(0, 4);
          return el("article", { class: "pol-card" },
            el("h3", {}, pattern.name),
            el("p", {}, pattern.summary),
            el("div", { class: "pol-chips" }, highlights.map((change) => el("span", { class: "pol-chip" }, el("span", {}, change.label), el("b", {}, `${change.from} → ${change.to}`)))),
            el("span", { class: "small muted" }, `${(pattern.changes || []).length} pesos diferentes`),
            button({ size: "sm", onClick: () => usePattern(pattern) }, "Abrir"),
          );
        })),
      ),
      el("section", { class: "panel o-stack" },
        el("div", { class: "pol-versions", role: "tablist", "aria-label": "Versões" }, policies.map((p) => el("button", {
          type: "button",
          class: `pol-version${p.id === selected ? " is-on" : ""}`,
          role: "tab",
          "aria-selected": p.id === selected ? "true" : "false",
          onClick: () => open(p.id),
        }, el("span", {}, p.name), pill(STATUS_TONE[p.status] || "muted", STATUS_LABEL[p.status] || p.status, { size: "sm" })))),
        detail(current, catalog, isAdmin, load),
      ),
    );
  };
  const usePattern = async (pattern) => {
    const draft = policies.find((item) => item.profile === pattern.profile && (item.status === "DRAFT" || item.status === "REJECTED"));
    if (draft) {
      await open(draft.id);
      return;
    }
    try {
      const created = await api("/api/ops/policies", { method: "POST", body: { name: pattern.name, profile: pattern.profile, weights: pattern.weights, note: pattern.summary }, area: "ops" });
      selected = created.id;
      await load();
    } catch (error) {
      box.replaceChildren(head("Políticas de risco"), notice("stop", "Não abri o padrão", error.message));
    }
  };
  load();
  return box;
  function detail(policy, signals, admin, reload) {
    if (!policy) return el("p", {}, "Escolha uma versão.");
    const editable = policy.status === "DRAFT" || policy.status === "REJECTED";
    const weights = policy.weights || {};
    const byKey = new Map(signals.map((signal) => [signal.key, signal]));
    return el("div", { class: "o-stack" },
      el("div", { class: "pol-head" },
        el("div", {},
          el("h2", {}, policy.name),
          el("p", {}, `${PROFILE_LABEL[policy.profile] || policy.profile}. ${policy.note || (editable ? "Os campos alteram este rascunho." : "Salvar cria uma nova versão.")}`),
        ),
        el("div", { class: "pol-actions" },
          button({ variant: "primary", size: "sm", onClick: async () => { const saved = await save(policy); if (saved?.id) selected = saved.id; if (saved) reload(); } }, editable ? "Salvar rascunho" : "Salvar como nova versão"),
          policy.status === "DRAFT" ? button({ size: "sm", onClick: async () => { await api(`/api/ops/policies/${policy.id}/submit`, { method: "POST", area: "ops" }); reload(); } }, "Enviar para aprovação") : null,
          admin && policy.status === "PENDING" ? button({ variant: "primary", size: "sm", onClick: async () => { await api(`/api/admin/policies/${policy.id}/approve`, { method: "POST", area: "ops" }); reload(); } }, "Aprovar") : null,
          admin && policy.status === "PENDING" ? button({ size: "sm", onClick: async () => { await api(`/api/admin/policies/${policy.id}/reject`, { method: "POST", body: { reason: "Rejeitada no painel" }, area: "ops" }); reload(); } }, "Rejeitar") : null,
          admin && policy.status === "APPROVED" ? button({ variant: "primary", size: "sm", onClick: async () => { await api(`/api/admin/policies/${policy.id}/publish`, { method: "POST", area: "ops" }); reload(); } }, "Publicar") : null,
          admin && policy.status === "ARCHIVED" ? button({ size: "sm", onClick: async () => { await api(`/api/admin/policies/${policy.id}/rollback`, { method: "POST", area: "ops" }); reload(); } }, "Restaurar") : null,
        ),
      ),
      el("div", { class: "pol-groups" }, WEIGHT_GROUPS.map(([title, keys]) => {
        const rows = keys.map((key) => byKey.get(key)).filter(Boolean);
        if (!rows.length) return null;
        return el("section", { class: "pol-group" },
          el("h3", {}, title),
          ...rows.map((signal) => {
            const shown = weights[signal.key] ?? signal.value;
            const baseline = signal.fallback ?? signal.value;
            const shifted = Number(shown) !== Number(baseline);
            return el("label", { class: `pol-row${shifted ? " is-shifted" : ""}`, title: signal.detail },
              el("span", { class: "pol-name" }, signal.label),
              el("span", { class: "pol-base" }, shifted ? `padrão ${baseline}` : `${signal.min} a ${signal.max}`),
              el("input", { class: "o-weight", type: "number", min: signal.min, max: signal.max, value: String(shown), "data-key": signal.key, "aria-label": `Peso de ${signal.label}` }),
            );
          }),
        );
      })),
      el("form", { class: "pol-sim", onSubmit: async (e) => { e.preventDefault(); const txId = new FormData(e.currentTarget).get("tx"); const result = await api(`/api/ops/policies/${policy.id}/simulate`, { method: "POST", body: { txId }, area: "ops" }); e.currentTarget.querySelector("[data-sim]").replaceChildren(sim(result)); } },
        el("input", { class: "input", name: "tx", placeholder: "Simular numa compra, ex. TX-2026-000184", "aria-label": "Transação para simular" }),
        button({ type: "submit", size: "sm" }, "Simular"),
        el("div", { "data-sim": "" })),
      policy.events?.length ? el("details", { class: "pol-history" }, el("summary", {}, "Histórico"), table(["Quando", "Ação", "Quem", "Detalhe"], policy.events.map((ev) => [dateTime(ev.at), ev.action, ev.actor, ev.detail || ""]))) : null,
    );
  }
}

function sim(result) {
  return notice("info", "Simulação, sem aplicar na compra", `Original ${result.originalScore ?? "—"} / ${result.originalDecision ?? "—"} · novo ${result.simulatedScore} / ${result.simulatedDecision}`);
}

function collectWeights() {
  const weights = {};
  document.querySelectorAll(".o-weight").forEach((input) => { weights[input.dataset.key] = Number(input.value); });
  return weights;
}

async function save(policy) {
  const weights = collectWeights();
  const editable = policy.status === "DRAFT" || policy.status === "REJECTED";
  try {
    if (editable) {
      return await api(`/api/ops/policies/${policy.id}`, { method: "PUT", body: { weights, note: policy.note || "Pesos alterados no painel." }, area: "ops" });
    }
    return await api("/api/ops/policies", { method: "POST", body: { name: "Ajuste de pesos", profile: "fintech", weights, note: "Rascunho. A compra continua na versão publicada." }, area: "ops" });
  } catch (error) {
    const slot = document.querySelector("[data-sim]");
    if (slot) slot.replaceChildren(notice("stop", "Não salvei", error.message));
    return null;
  }
}

export function labPage(isAdmin) {
  const box = el("div", { class: "o-stack" }, head("Laboratório de Machine Learning", "Modo sombra: o motor continua decidindo."), el("p", { class: "muted" }, "Carregando…"));
  const load = async () => {
    try {
      paint(await api("/api/ops/ml", { area: "ops" }));
    } catch (error) {
      box.replaceChildren(head("Laboratório de Machine Learning"), notice("stop", "Não foi possível carregar", error.message));
    }
  };
  const paint = (data) => {
    box.replaceChildren(
      head("Laboratório de Machine Learning", "Modo sombra: o motor continua decidindo. Uma falha do modelo não libera a compra."),
      notice("warn", "Leia antes de citar número", data.limitation),
      el("section", { class: "panel" }, el("h2", {}, "Modelos treinados"), data.models.length ? table(
        ["Algoritmo", "Validação", "Status", "Uso", "Teste", "F1", "Recall", "Precisão", "FP", "FN"],
        data.models.map((m) => {
          const metric = m.metrics || {};
          const usage = m.status === "APPROVED" ? "sombra" : m.validation === "PASS" ? "validado" : "treinado";
          const tipo = m.featureSet === "context" ? "Contextual" : "Operacional";
          const origem = m.validation === "RECORDED" ? "Pré-carregado" : "Treinado";
          return [`${m.algorithm} · ${tipo} (${origem})`, pill(STATUS_TONE[m.validation] || "muted", m.validation || "—"), m.status, usage, m.examples == null ? "—" : String(m.examples), num(metric.f1), num(metric.recall), num(metric.precision), String(metric.falsePositives ?? "—"), String(metric.falseNegatives ?? "—")];
        }),
      ) : el("p", {}, "Nenhum modelo treinado ainda.")),
      el("section", { class: "panel" }, el("h2", {}, "Modo sombra"), el("p", {}, `${data.shadow.predictions ?? 0} previsões sem erro, ${data.shadow.failures ?? 0} falhas, ${data.shadow.sameDirection ?? (data.shadow.disagreementsWithEngine == null ? "—" : Number(data.shadow.predictions || 0) - Number(data.shadow.disagreementsWithEngine))} na mesma direção do motor.`)),
      el("section", { class: "panel" }, el("h2", {}, "Sugestões de peso"), data.suggestions.length ? el("div", { class: "o-stack" }, data.suggestions.map((s) => el("article", {}, el("p", {}, s.note || s.status), isAdmin && s.status === "SUGGESTED" ? button({ onClick: async () => { try { await api(`/api/admin/ml/suggestions/${s.id}/draft`, { method: "POST", area: "ops" }); load(); } catch (error) { box.replaceChildren(head("Laboratório de Machine Learning"), notice("stop", "Não criei o rascunho", error.message)); } } }, "Criar rascunho") : null))) : el("p", {}, "Nenhuma sugestão guardada. A busca não publica sozinha.")),
      el("section", { class: "panel" }, el("h2", {}, "Novo experimento"), el("p", { class: "muted" }, "Roda no servidor, numa fila de um por vez. O navegador não executa comando."), el("form", { class: "row", onSubmit: async (e) => { e.preventDefault(); const form = new FormData(e.currentTarget); const out = el("p", { "data-job": "" }); e.currentTarget.after(out); const job = await api("/api/ops/ml/experiments", { method: "POST", body: { examples: Number(form.get("examples")), seed: Number(form.get("seed")) }, area: "ops" }); poll(job.id, out); } },
        el("input", { name: "examples", type: "number", min: "200", max: "4000", value: "400", "aria-label": "Quantidade de operações sintéticas" }),
        el("input", { name: "seed", type: "number", value: "42", "aria-label": "Semente" }),
        button({ type: "submit", variant: "primary" }, icon("play", { size: 15 }), " Pedir experimento"))),
      data.experiments?.length ? el("section", { class: "panel" }, el("h2", {}, "Experimentos"), table(["Id", "Status", "Exemplos", "Semente", "Quem"], data.experiments.slice(0, 8).map((ex) => [String(ex.id), ex.status, ex.examples == null ? "—" : String(ex.examples), ex.seed == null ? "—" : String(ex.seed), ex.requestedBy || "—"]))) : null,
      isAdmin ? el("section", { class: "panel" }, el("h2", {}, "Aprovar um modelo validado"), el("form", { class: "row", onSubmit: async (e) => { e.preventDefault(); const id = new FormData(e.currentTarget).get("id"); await api(`/api/admin/ml/models/${id}/approve`, { method: "POST", area: "ops" }); load(); } }, el("input", { name: "id", type: "number", required: true, "aria-label": "Id do modelo" }), button({ type: "submit" }, "Aprovar para o modo sombra"))) : notice("muted", "Aprovação", "Só administração publica um modelo. Treinar não publica."),
    );
  };
  load();
  return box;
}

async function poll(id, node) {
  const tick = async () => {
    const job = await api(`/api/ops/ml/experiments/${id}`, { area: "ops" });
    node.textContent = `Experimento ${id}: ${job.status}${job.error ? " — " + job.error : ""}`;
    if (job.status === "QUEUED" || job.status === "RUNNING") setTimeout(tick, 2000);
  };
  tick();
}

function num(value) {
  return value == null || value === "" ? "—" : String(value);
}
