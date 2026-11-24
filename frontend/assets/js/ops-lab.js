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

export function policiesPage(isAdmin) {
  const box = el("div", { class: "o-stack" }, head("Gerenciamento de políticas de risco", "Os pesos saem do código. As regras R1 a R11 continuam no motor."), el("p", { class: "muted" }, "Carregando…"));
  let policies = [];
  let catalog = [];
  let selected = null;
  const draw = () => paint();
  const load = async () => {
    try {
      const [list, cat] = await Promise.all([api("/api/ops/policies", { area: "ops" }), api("/api/ops/policies/catalog", { area: "ops" })]);
      policies = list;
      catalog = cat.signals;
      if (!selected && policies[0]) selected = policies[0].id;
      paint();
    } catch (error) {
      box.replaceChildren(head("Gerenciamento de políticas de risco"), notice("stop", "Não foi possível carregar", error.message));
    }
  };
  const paint = () => {
    const current = policies.find((p) => p.id === selected);
    box.replaceChildren(
      head("Gerenciamento de políticas de risco", "Os pesos saem do código. As regras R1 a R11 continuam no motor."),
      notice("info", "Perfil em uso na loja", "A compra usa a política publicada do perfil fintech. Marketplace e e-commerce são exemplos e não isolam empresas."),
      el("div", { class: "o-ml-grid" },
        el("section", { class: "panel" }, el("h2", {}, "Versões"), policies.length ? el("ul", { class: "o-links" }, policies.map((p) => el("li", {}, el("button", { type: "button", class: "link-row", onClick: async () => { selected = p.id; const full = await api(`/api/ops/policies/${p.id}`, { area: "ops" }); policies = policies.map((item) => item.id === p.id ? { ...item, ...full } : item); paint(); } }, el("span", {}, `${p.name} · v${p.version}`), pill(STATUS_TONE[p.status] || "muted", p.status))))) : el("p", {}, "Nenhuma política.")),
        el("section", { class: "panel" }, detail(current, catalog, isAdmin, load)),
      ),
    );
  };
  load();
  return box;
  function detail(policy, signals, admin, reload) {
    if (!policy) return el("p", {}, "Escolha uma versão.");
    const editable = policy.status === "DRAFT" || policy.status === "REJECTED";
    const weights = policy.weights || {};
    return el("div", { class: "o-stack" },
      el("h2", {}, policy.name),
      el("p", { class: "muted" }, `${policy.profile} · criada por ${policy.createdBy || "—"} · ${policy.note || ""}`),
      table(["Sinal", "Peso", "Faixa", "Por quê"], signals.map((s) => [
        s.label,
        editable ? el("input", { class: "o-weight", type: "number", min: s.min, max: s.max, value: String(weights[s.key] ?? s.value), "data-key": s.key }) : String(weights[s.key] ?? s.value),
        `${s.min} a ${s.max}${s.safetyFloor ? " · piso" : ""}`,
        s.detail,
      ])),
      el("div", { class: "row" },
        editable ? button({ variant: "primary", onClick: () => save(policy.id, reload) }, "Salvar rascunho") : null,
        policy.status === "DRAFT" ? button({ onClick: async () => { await api(`/api/ops/policies/${policy.id}/submit`, { method: "POST", area: "ops" }); reload(); } }, "Enviar para aprovação") : null,
        admin && policy.status === "PENDING" ? button({ variant: "primary", onClick: async () => { await api(`/api/admin/policies/${policy.id}/approve`, { method: "POST", area: "ops" }); reload(); } }, "Aprovar") : null,
        admin && policy.status === "PENDING" ? button({ onClick: async () => { await api(`/api/admin/policies/${policy.id}/reject`, { method: "POST", body: { reason: "Rejeitada no painel" }, area: "ops" }); reload(); } }, "Rejeitar") : null,
        admin && policy.status === "APPROVED" ? button({ variant: "primary", onClick: async () => { await api(`/api/admin/policies/${policy.id}/publish`, { method: "POST", area: "ops" }); reload(); } }, "Publicar") : null,
        admin && policy.status === "ARCHIVED" ? button({ onClick: async () => { await api(`/api/admin/policies/${policy.id}/rollback`, { method: "POST", area: "ops" }); reload(); } }, "Restaurar") : null,
      ),
      el("form", { class: "row", onSubmit: async (e) => { e.preventDefault(); const txId = new FormData(e.currentTarget).get("tx"); const result = await api(`/api/ops/policies/${policy.id}/simulate`, { method: "POST", body: { txId }, area: "ops" }); e.currentTarget.querySelector("[data-sim]").replaceChildren(sim(result)); } },
        el("input", { name: "tx", placeholder: "TX-2026-000184", "aria-label": "Transação para simular" }),
        button({ type: "submit" }, "Simular"),
        el("div", { "data-sim": "" })),
      policy.events ? el("section", {}, el("h3", {}, "Histórico"), table(["Quando", "Ação", "Quem", "Detalhe"], policy.events.map((ev) => [dateTime(ev.at), ev.action, ev.actor, ev.detail || ""]))) : null,
    );
  }
}

function sim(result) {
  return notice("info", "Simulação, sem aplicar na compra", `Original ${result.originalScore ?? "—"} / ${result.originalDecision ?? "—"} · novo ${result.simulatedScore} / ${result.simulatedDecision}`);
}

async function save(id, reload) {
  const weights = {};
  document.querySelectorAll(".o-weight").forEach((input) => { weights[input.dataset.key] = Number(input.value); });
  try {
    await api(`/api/ops/policies/${id}`, { method: "PUT", body: { weights }, area: "ops" });
    reload();
  } catch (error) {
    const slot = document.querySelector("[data-sim]");
    if (slot) slot.replaceChildren(notice("stop", "Não salvei", error.message));
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
          return [m.algorithm, pill(STATUS_TONE[m.validation] || "muted", m.validation || "—"), m.status, usage, String(m.examples), num(metric.f1), num(metric.recall), num(metric.precision), String(metric.falsePositives ?? "—"), String(metric.falseNegatives ?? "—")];
        }),
      ) : el("p", {}, "Nenhum modelo treinado ainda.")),
      el("section", { class: "panel" }, el("h2", {}, "Modo sombra"), el("p", {}, `${data.shadow.predictions} previsões registradas, ${data.shadow.failures} falhas, ${data.shadow.sameDirection} na mesma direção do motor.`)),
      el("section", { class: "panel" }, el("h2", {}, "Sugestões de peso"), data.suggestions.length ? el("div", { class: "o-stack" }, data.suggestions.map((s) => el("article", {}, el("p", {}, s.note || s.status), isAdmin && s.status === "SUGGESTED" ? button({ onClick: async () => { try { await api(`/api/admin/ml/suggestions/${s.id}/draft`, { method: "POST", area: "ops" }); load(); } catch (error) { box.replaceChildren(head("Laboratório de Machine Learning"), notice("stop", "Não criei o rascunho", error.message)); } } }, "Criar rascunho") : null))) : el("p", {}, "Nenhuma sugestão guardada. A busca não publica sozinha.")),
      el("section", { class: "panel" }, el("h2", {}, "Novo experimento"), el("p", { class: "muted" }, "Roda no servidor, numa fila de um por vez. O navegador não executa comando."), el("form", { class: "row", onSubmit: async (e) => { e.preventDefault(); const form = new FormData(e.currentTarget); const out = el("p", { "data-job": "" }); e.currentTarget.after(out); const job = await api("/api/ops/ml/experiments", { method: "POST", body: { examples: Number(form.get("examples")), seed: Number(form.get("seed")) }, area: "ops" }); poll(job.id, out); } },
        el("input", { name: "examples", type: "number", min: "200", max: "4000", value: "400", "aria-label": "Quantidade de operações sintéticas" }),
        el("input", { name: "seed", type: "number", value: "42", "aria-label": "Semente" }),
        button({ type: "submit", variant: "primary" }, icon("play", { size: 15 }), " Pedir experimento"))),
      data.experiments?.length ? el("section", { class: "panel" }, el("h2", {}, "Experimentos"), table(["Id", "Status", "Exemplos", "Semente", "Quem"], data.experiments.slice(0, 8).map((ex) => [String(ex.id), ex.status, String(ex.examples), String(ex.seed), ex.requestedBy]))) : null,
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
