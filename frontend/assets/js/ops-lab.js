import { api } from "./api/client.js";
import {
  comparisonChartSeries,
  improvementInterpretation,
  summarizeWeightReport,
  weightDiffRows,
} from "./lab-study-report.js";
import { button, notice, pill } from "./ui.js";
import { el, replace } from "./utils/dom.js";
import { mountLabActivity } from "./lab-activity.js";
import { dateTime, setTitle } from "./utils/format.js";
import { icon } from "./utils/icons.js";

function head(title, sub) {
  setTitle(title);
  return el("header", { class: "o-head" }, el("div", { class: "o-head-row" }, el("div", { class: "o-head-text" }, el("h1", {}, title), sub ? el("p", { class: "o-head-sub" }, sub) : null)));
}

function table(headers, rows) {
  return el("div", { class: "table-wrap" }, el("table", { class: "table o-table" }, el("thead", {}, el("tr", {}, headers.map((h) => el("th", {}, h)))), el("tbody", {}, rows.map((cells) => el("tr", {}, cells.map((c) => el("td", {}, c)))))));
}

function studiesTable(headers, rowSpecs) {
  return el("div", { class: "table-wrap" }, el("table", { class: "table o-table study-table" },
    el("thead", {}, el("tr", {}, headers.map((h) => el("th", {}, h)))),
    el("tbody", {}, rowSpecs.map(({ cells, selected, onSelect }) => el("tr", {
      class: `study-row${selected ? " is-selected" : ""}`,
      tabIndex: 0,
      role: "button",
      "aria-pressed": selected ? "true" : "false",
      onClick: onSelect,
      onKeydown: (e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onSelect();
        }
      },
    }, cells.map((c) => el("td", {}, c))))),
  ));
}

function studyMetricChart(title, series) {
  const vals = series.flatMap((m) => [m.before, m.after]).filter((v) => v != null);
  const max = Math.max(1, ...vals.map(Number));
  return el("section", { class: "study-chart-block" },
    el("h4", { class: "study-chart-title" }, title),
    el("div", { class: "study-chart", role: "img", "aria-label": title },
      series.map((m) => {
        if (m.before == null && m.after == null) return null;
        const before = m.before ?? 0;
        const after = m.after ?? 0;
        return el("div", { class: "study-chart-row" },
          el("span", { class: "study-chart-label" }, m.label),
          el("div", { class: "study-chart-bars" },
            el("div", { class: "study-bar is-before", style: { width: `${(before / max) * 100}%` } }, el("span", {}, `Antes ${before}`)),
            el("div", { class: "study-bar is-after", style: { width: `${(after / max) * 100}%` } }, el("span", {}, `Depois ${after}`)),
          ),
        );
      }),
    ),
  );
}

function studyDetailSection(study, onClose) {
  if (!study) return null;
  const headRow = el("div", { class: "study-detail-head" },
    el("h3", {}, `Estudo #${study.id} · ${study.kind === "WEIGHTS" ? "Pesos" : study.kind === "RULES" ? "Regras" : study.kind}`),
    button({ size: "sm", onClick: onClose }, "Fechar"),
  );
  if (study.status === "QUEUED" || study.status === "RUNNING") {
    return el("section", { class: "panel study-detail o-stack" }, headRow,
      notice("info", "Em execução", "O relatório aparece ao concluir. A lista atualiza a cada poucos segundos."),
      study.note ? el("p", { class: "muted" }, study.note) : null);
  }
  if (study.status === "FAILED") {
    return el("section", { class: "panel study-detail o-stack" }, headRow,
      notice("stop", "Estudo falhou", study.error || "Erro não detalhado."));
  }
  const report = study.report;
  if (!report || (typeof report === "object" && !Object.keys(report).length)) {
    return el("section", { class: "panel study-detail o-stack" }, headRow,
      notice("muted", "Sem relatório", "Este estudo terminou sem report_json ou o conteúdo está vazio."));
  }
  if (study.kind === "RULES") return ruleStudyDetail(study, headRow);
  return weightStudyDetail(study, headRow);
}

function weightStudyDetail(study, headRow) {
  const summary = summarizeWeightReport(study.report);
  const opt = study.report.syntheticOptimization || {};
  const interp = summary.interp;
  const charts = comparisonChartSeries(opt);
  const diffs = weightDiffRows(study.report.publishedWeights, opt.suggestedWeights);
  const tones = interp.acceptCriteria ? "ok" : "muted";
  return el("section", { class: "panel study-detail o-stack" }, headRow,
    notice(tones, interp.headline, interp.detail || summary.note || "Critério: melhorar busca sem piorar confirmação (improvedOnBothSets)."),
    interp.misleadingDualImprovement ? notice("warn", "Nome do campo", 'improvedOnBothSets não significa "melhoria nas duas etapas" — use o título acima.') : null,
    notice("info", "Publicação", summary.published
      ? "O relatório marcou published=true; mesmo assim nada entra no motor sem aprovação."
      : "Este estudo não altera automaticamente pesos, regras nem decisões (published=false)."),
    el("dl", { class: "study-kv" },
      el("div", {}, el("dt", {}, "Método"), el("dd", {}, summary.method)),
      el("div", {}, el("dt", {}, "Candidatos avaliados"), el("dd", { class: "num" }, String(summary.candidates))),
      el("div", {}, el("dt", {}, "Origem dos dados"), el("dd", {}, summary.origin + ` (${summary.reviewedCount} revisados)`)),
      el("div", {}, el("dt", {}, "Perfil de custo"), el("dd", {}, summary.costProfile)),
      el("div", {}, el("dt", {}, "Regras dinâmicas"), el("dd", { class: "num" }, String(summary.rulesCount))),
    ),
    summary.warnings.map((w) => notice("warn", "Dados reais", w)),
    summary.syntheticWarning ? el("p", { class: "muted small" }, summary.syntheticWarning) : null,
    el("p", {}, el("strong", {}, "Recomendação: "), summary.recommendation),
    summary.leakageControl ? el("p", { class: "small muted" }, summary.leakageControl) : null,
    studyMetricChart("Conjunto de busca (sintético)", charts.search),
    studyMetricChart("Conjunto de confirmação (sintético)", charts.confirm),
    diffs.length ? el("section", { class: "o-stack" },
      el("h4", {}, "Pesos sugeridos vs publicados"),
      table(["Chave", "Publicado", "Sugerido"], diffs.map((d) => [d.key, String(d.from ?? "—"), String(d.to ?? "—")])),
      (opt.reasons || []).map((r) => el("p", { class: "small" }, `${r.key}: ${r.why || ""}`)),
    ) : el("p", { class: "muted" }, "Nenhuma alteração de peso sugerida neste relatório."),
  );
}

function ruleStudyDetail(study, headRow) {
  const report = study.report;
  return el("section", { class: "panel study-detail o-stack" }, headRow,
    notice("info", "Publicação", "Sugestões ficam em rascunho até aprovação humana. O motor publicado não muda sozinho."),
    report.insufficientData ? notice("warn", "Dados insuficientes", report.message || "Poucos rótulos revisados.") : null,
    el("dl", { class: "study-kv" },
      el("div", {}, el("dt", {}, "Regras publicadas"), el("dd", { class: "num" }, String(report.publishedRules ?? "—"))),
      el("div", {}, el("dt", {}, "Sugestões geradas"), el("dd", { class: "num" }, String(report.suggestedRules ?? (report.rules?.length ?? 0)))),
    ),
    report.note ? el("p", {}, report.note) : null,
    report.baseline ? el("p", { class: "mono small" }, `Baseline: custo ${report.baseline.cost ?? "—"} · FP ${report.baseline.falsePositives ?? "—"} · FN ${report.baseline.falseNegatives ?? "—"}`) : null,
    Array.isArray(report.rules) && report.rules.length
      ? table(["Regra", "Hits", "Custo sem regra", "FP", "FN"], report.rules.slice(0, 12).map((r) => [
        `${r.displayKey || r.ruleKey} · ${r.name || ""}`,
        String(r.assessmentHits ?? "—"),
        String(r.withoutRuleCost ?? "—"),
        String(r.withoutRuleFalsePositives ?? "—"),
        String(r.withoutRuleFalseNegatives ?? "—"),
      ]))
      : null,
  );
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

const RULE_OUTCOMES = ["APPROVE", "APPROVE_WITH_ALERT", "REQUEST_CONTEXT", "INTERVENE", "BLOCK"];

export function rulesPage(isAdmin) {
  const box = el("div", { class: "o-stack" }, head("Regras do motor", "Crie e publique regras sem reiniciar."), el("p", { class: "muted" }, "Carregando…"));
  let items = [];
  let catalog = null;
  let selected = null;
  const load = async () => {
    try {
      const [list, cat] = await Promise.all([
        api("/api/ops/rules", { area: "ops" }),
        api("/api/ops/rules/catalog", { area: "ops" }),
      ]);
      items = list;
      catalog = cat;
      if (!selected && items.length) selected = items.find((r) => r.status === "DRAFT")?.id ?? items[0].id;
      if (selected) await open(selected);
      else paint();
    } catch (error) {
      box.replaceChildren(head("Regras do motor"), notice("stop", "Não foi possível carregar", error.message));
    }
  };
  const open = async (id) => {
    selected = id;
    const full = await api(`/api/ops/rules/${id}`, { area: "ops" });
    items = items.map((item) => (item.id === id ? { ...item, ...full } : item));
    paint();
  };
  const paint = () => {
    const current = items.find((r) => r.id === selected);
    box.replaceChildren(
      head("Regras do motor", "R1–R12 vêm da migração. R13+ são criadas aqui e entram na próxima compra."),
      notice("info", `${catalog?.publishedCount ?? "—"} regras publicadas`, "O Safety Monitor e o ML continuam após a decisão das regras."),
      el("section", { class: "panel o-stack" },
        el("div", { class: "pol-actions" },
          button({ variant: "primary", size: "sm", onClick: () => newRule() }, "Nova regra (ex. R13)"),
        ),
        el("div", { class: "pol-versions", role: "tablist" }, items.map((r) => el("button", {
          type: "button",
          class: `pol-version${r.id === selected ? " is-on" : ""}`,
          onClick: () => open(r.id),
        }, el("span", {}, `${r.ruleKey} v${r.version}`), pill(STATUS_TONE[r.status] || "muted", STATUS_LABEL[r.status] || r.status, { size: "sm" })))),
        ruleDetail(current, isAdmin, load),
      ),
    );
  };
  const newRule = async () => {
    try {
      const created = await api("/api/ops/rules", {
        method: "POST",
        area: "ops",
        body: {
          ruleKey: "R13",
          name: "Regra personalizada",
          summary: "Score médio+ com destino em incidente",
          priority: 15,
          kind: "SCORE_SIGNAL",
          params: { weightKey: "THRESHOLD_MEDIUM", signal: "DESTINATION_IN_INCIDENT", stage: "any", summary: "destino citado em incidente" },
          outcome: "BLOCK",
          note: "Criada no Operations",
        },
      });
      selected = created.id;
      await load();
    } catch (error) {
      box.replaceChildren(head("Regras do motor"), notice("stop", "Não criei a regra", error.message));
    }
  };
  load();
  return box;

  function ruleDetail(rule, admin, reload) {
    if (!rule) return el("p", {}, "Nenhuma regra selecionada.");
    const editable = rule.status === "DRAFT" || rule.status === "REJECTED";
    const p = rule.params || {};
    return el("div", { class: "o-stack" },
      el("h2", {}, `${rule.ruleKey} · ${rule.name}`),
      el("p", {}, rule.summary || rule.note || ""),
      el("p", { class: "mono small" }, `Tipo ${rule.kind} → ${rule.outcome} · prioridade ${rule.priority}`),
      editable ? el("label", {}, "Prioridade", el("input", { class: "input", type: "number", id: "rule-priority", value: String(rule.priority) })) : null,
      rule.kind === "SCORE_SIGNAL" && editable ? el("div", { class: "pol-group" },
        el("label", {}, "Sinal", el("input", { class: "input", id: "rule-signal", value: p.signal || "DESTINATION_IN_INCIDENT" })),
        el("label", {}, "Peso limiar", el("input", { class: "input", id: "rule-weight", value: p.weightKey || "THRESHOLD_MEDIUM" })),
      ) : null,
      editable ? el("label", {}, "Decisão", el("select", { class: "input", id: "rule-outcome" }, RULE_OUTCOMES.map((o) => el("option", { value: o, selected: o === rule.outcome ? true : null }, o)))) : null,
      el("div", { class: "pol-actions" },
        editable ? button({ variant: "primary", size: "sm", onClick: () => saveRule(rule, reload) }, "Salvar rascunho") : null,
        rule.status === "DRAFT" ? button({ size: "sm", onClick: async () => { await api(`/api/ops/rules/${rule.id}/submit`, { method: "POST", area: "ops" }); reload(); } }, "Enviar para aprovação") : null,
        admin && rule.status === "PENDING" ? button({ variant: "primary", size: "sm", onClick: async () => { await api(`/api/admin/rules/${rule.id}/approve`, { method: "POST", area: "ops" }); reload(); } }, "Aprovar") : null,
        admin && rule.status === "APPROVED" ? button({ variant: "primary", size: "sm", onClick: async () => { await api(`/api/admin/rules/${rule.id}/publish`, { method: "POST", area: "ops" }); reload(); } }, "Publicar") : null,
        rule.status === "PUBLISHED" ? button({ size: "sm", onClick: async () => { await api(`/api/ops/rules/${rule.id}/disable`, { method: "POST", area: "ops" }); reload(); } }, "Desativar") : null,
        rule.status === "DISABLED" ? button({ size: "sm", onClick: async () => { await api(`/api/ops/rules/${rule.id}/enable`, { method: "POST", area: "ops" }); reload(); } }, "Ativar") : null,
        admin && rule.status === "ARCHIVED" ? button({ size: "sm", onClick: async () => { await api(`/api/admin/rules/${rule.id}/rollback`, { method: "POST", area: "ops" }); reload(); } }, "Restaurar") : null,
      ),
      rule.events?.length ? table(["Quando", "Ação", "Quem", "Detalhe"], rule.events.map((ev) => [dateTime(ev.at), ev.action, ev.actor, ev.detail || ""])) : null,
    );
  }
}

async function saveRule(rule, reload) {
  const priority = Number(document.getElementById("rule-priority")?.value ?? rule.priority);
  const outcome = document.getElementById("rule-outcome")?.value ?? rule.outcome;
  const params = { ...(rule.params || {}) };
  if (rule.kind === "SCORE_SIGNAL") {
    params.signal = document.getElementById("rule-signal")?.value ?? params.signal;
    params.weightKey = document.getElementById("rule-weight")?.value ?? params.weightKey;
  }
  await api(`/api/ops/rules/${rule.id}`, {
    method: "PUT",
    area: "ops",
    body: { name: rule.name, summary: rule.summary, priority, kind: rule.kind, params, outcome, note: rule.note },
  });
  reload();
}

/** Evita texto literal "null" ao montar o painel (replaceChildren nativo não filtra). */
export function filterPaintChildren(parts) {
  return parts.filter((p) => p != null && p !== false);
}

function count(n) {
  return n == null || Number.isNaN(Number(n)) ? 0 : Number(n);
}

function shadowSameDirection(shadow = {}) {
  if (shadow.sameDirection != null) return count(shadow.sameDirection);
  const predictions = count(shadow.predictions);
  const disagreements = count(shadow.disagreementsWithEngine);
  return Math.max(0, predictions - disagreements);
}

function studyResultCell(s) {
  if (s.error) return s.error;
  if (s.status === "FAILED") return s.error || "Falhou";
  if (s.status === "RUNNING" || s.status === "QUEUED") return "Em andamento…";
  if (s.status !== "DONE") return "—";
  const report = s.report || {};
  const opt = report.syntheticOptimization;
  if (opt) {
    const interp = improvementInterpretation(opt);
    if (interp.headline) return interp.headline;
  }
  if (report.reviewedInsufficient) return report.reviewedMessage || "Concluído · poucos rótulos revisados";
  if (s.note) return `Concluído · ${s.note}`;
  return "Concluído (sombra; sem publicação automática)";
}

export function labPage(isAdmin) {
  const box = el("div", { class: "o-stack" });
  const restMount = el("div", { class: "o-stack" }, el("p", { class: "muted" }, "Carregando…"));
  const activityMount = el("div", { class: "ml-activity-mount" });
  let activityCtl = null;
  let studyPollTimer = null;
  let selectedStudyId = null;
  let selectedStudyDetail = null;
  replace(box, head("Laboratório de Machine Learning", "Modo sombra: o motor continua decidindo."), activityMount, restMount);
  const stopStudyPoll = () => {
    if (studyPollTimer) {
      clearInterval(studyPollTimer);
      studyPollTimer = null;
    }
  };
  const closeStudy = () => {
    selectedStudyId = null;
    selectedStudyDetail = null;
  };
  const openStudy = async (id) => {
    selectedStudyId = id;
    try {
      selectedStudyDetail = await api(`/api/ops/ml/studies/${id}`, { area: "ops" });
    } catch (error) {
      selectedStudyDetail = { id, status: "FAILED", error: error.message, kind: "WEIGHTS", report: null };
    }
    const data = await api("/api/ops/ml", { area: "ops" }).catch(() => null);
    if (data) paint(data);
  };
  const load = async () => {
    try {
      const data = await api("/api/ops/ml", { area: "ops" });
      if (selectedStudyId) {
        try {
          selectedStudyDetail = await api(`/api/ops/ml/studies/${selectedStudyId}`, { area: "ops" });
        } catch {
          /* mantém último detalhe ou fecha */
        }
      }
      paint(data);
      stopStudyPoll();
      const active = (data.studies ?? []).some((s) => s.status === "QUEUED" || s.status === "RUNNING");
      if (active) studyPollTimer = setInterval(load, 2000);
    } catch (error) {
      stopStudyPoll();
      replace(restMount, notice("stop", "Não foi possível carregar", error.message));
    }
  };
  const paint = (data) => {
    const models = data.models ?? [];
    const suggestions = data.suggestions ?? [];
    const shadow = data.shadow ?? {};
    const labels = data.labels ?? {};
    const ruleSuggestions = data.ruleSuggestions ?? [];
    const studies = data.studies ?? [];
    const ruleSuggestionSection = ruleSuggestions.length
      ? el("section", { class: "panel" }, el("h2", {}, "Sugestões de novas regras"),
        el("p", { class: "muted small" }, "Rascunhos sugeridos por estudos. Criar rascunho não publica."),
        el("div", { class: "o-stack" }, ruleSuggestions.map((s) => el("article", { class: "o-stack" },
          el("p", {}, el("strong", {}, `${s.ruleKey} · ${s.name}`), " — ", s.summary || s.note),
          el("p", { class: "mono small" }, `${s.kind} → ${s.outcome} · prioridade ${s.priority}`),
          isAdmin && s.status === "SUGGESTED" ? button({ size: "sm", onClick: async () => { await api(`/api/admin/ml/rule-suggestions/${s.id}/draft`, { method: "POST", area: "ops" }); load(); } }, "Criar rascunho dinâmico") : null))))
      : null;
    if (!activityCtl) {
      activityCtl = mountLabActivity(activityMount, { isAdmin });
    }
    replace(restMount,
      notice("warn", "Leia antes de citar número", data.limitation),
      el("section", { class: "panel o-stack" },
        el("h2", {}, "Rótulos e origem dos dados"),
        el("p", { class: "muted" }, labels.syntheticNote || "Experimentos usam operações sintéticas; contagem abaixo vem do banco."),
        el("p", {}, `Revisados (fraude/legítimo, dados reais): ${count(labels.reviewedFraudLegit)} · Relatos pendentes: ${count(labels.pendingReports)} · Confirmados (FRAUD/LEGIT): ${count(data.confirmed)}`),
        el("p", { class: "small" }, labels.trainingLabelPolicy || "")),
      el("section", { class: "panel o-stack" },
        el("h2", {}, "Estudos de política (pesos e regras)"),
        el("p", { class: "muted" }, "Não publica automaticamente. O motor dinâmico e o Safety Monitor permanecem."),
        el("div", { class: "pol-actions" },
          button({
            size: "sm", variant: "primary", onClick: async () => {
              try {
                await api("/api/ops/ml/studies/weights", { method: "POST", body: { seed: 42, costProfile: "standard" }, area: "ops" });
                await load();
              } catch (error) {
                replace(restMount, notice("stop", "Não enfileirei o estudo de pesos", error.message));
              }
            },
          }, "Estudar pesos"),
          button({
            size: "sm", onClick: async () => {
              try {
                await api("/api/ops/ml/studies/rules", { method: "POST", area: "ops" });
                await load();
              } catch (error) {
                replace(restMount, notice("stop", "Não enfileirei o estudo de regras", error.message));
              }
            },
          }, "Estudar regras"),
        ),
        studies.length ? el("div", { class: "o-stack" },
          el("p", { class: "muted small" }, "Clique em uma linha para ver o relatório completo."),
          studiesTable(["Id", "Tipo", "Status", "Custo", "Quem", "Quando", "Andamento"], studies.map((s) => ({
            selected: s.id === selectedStudyId,
            onSelect: () => openStudy(s.id),
            cells: [
              String(s.id),
              s.kind === "WEIGHTS" ? "Pesos" : s.kind === "RULES" ? "Regras" : (s.kind || "—"),
              pill(STATUS_TONE[s.status] || "muted", s.status || "—"),
              s.costProfile || "—",
              s.createdBy || "—",
              dateTime(s.createdAt),
              studyResultCell(s),
            ],
          }))),
          studyDetailSection(selectedStudyDetail, () => { closeStudy(); load(); }),
        ) : el("p", {}, "Nenhum estudo ainda.")),
      ruleSuggestionSection,
      el("section", { class: "panel" }, el("h2", {}, "Modelos treinados"),
        el("p", { class: "muted small" }, "Validação PASS = experimento sintético. RECORDED = pré-carregado. Aprovado = sombra apenas."),
        models.length ? table(
          ["Algoritmo", "Validação", "Status", "Uso", "Origem dados", "Teste", "F1", "Recall", "Precisão", "FP", "FN"],
          models.map((m) => {
            const metric = m.metrics || {};
            const usage = m.status === "APPROVED" ? "sombra" : m.validation === "PASS" ? "validado (sintético)" : "treinado";
            const tipo = m.featureSet === "context" ? "Contextual" : "Operacional";
            const dataOrigin = m.origin === "sintetico" ? "Sintético" : m.validation === "RECORDED" ? "Pré-carregado" : (m.origin || "—");
            const origemTag = m.validation === "RECORDED" ? "Pré-carregado" : "Treino";
            return [`${m.algorithm} · ${tipo} (${origemTag})`, pill(STATUS_TONE[m.validation] || "muted", m.validation || "—"), m.status, usage, dataOrigin, m.examples == null ? "0" : String(m.examples), num(metric.f1), num(metric.recall), num(metric.precision), metric.falsePositives == null ? "—" : String(metric.falsePositives), metric.falseNegatives == null ? "—" : String(metric.falseNegatives)];
          }),
        ) : el("p", {}, "Nenhum modelo treinado ainda.")),
      el("section", { class: "panel" }, el("h2", {}, "Modo sombra"),
        el("p", {}, `${count(shadow.predictions)} previsões sem erro, ${count(shadow.failures)} falhas, ${shadowSameDirection(shadow)} na mesma direção do motor.`),
        el("p", { class: "muted small" }, "Contagens operacionais. Não substituem auditoria de fraude real.")),
      el("section", { class: "panel" }, el("h2", {}, "Sugestões de peso"),
        el("p", { class: "muted small" }, "Métricas de busca sintética. Criar rascunho exige aprovação humana."),
        suggestions.length ? el("div", { class: "o-stack" }, suggestions.map((s) => {
          const m = s.metrics || {};
          const base = m.baselineConfirm || m.baselineSearch || {};
          const best = m.bestConfirm || m.bestSearch || {};
          return el("article", { class: "o-stack" },
            el("p", {}, s.note || s.status),
            el("p", { class: "small mono" }, `Confirm: FP ${base.falsePositives ?? "—"}→${best.falsePositives ?? "—"} · FN ${base.falseNegatives ?? "—"}→${best.falseNegatives ?? "—"} · custo ${base.cost ?? "—"}→${best.cost ?? "—"}`),
            el("p", { class: "muted small" }, m.dynamicRulesApplied ? "Busca com regras dinâmicas publicadas." : ""),
            isAdmin && s.status === "SUGGESTED" ? button({ onClick: async () => { try { await api(`/api/admin/ml/suggestions/${s.id}/draft`, { method: "POST", area: "ops" }); load(); } catch (error) { replace(restMount, notice("stop", "Não criei o rascunho", error.message)); } } }, "Criar rascunho de pesos") : null);
        })) : el("p", {}, "Nenhuma sugestão guardada. A busca não publica sozinha.")),
      el("section", { class: "panel" }, el("h2", {}, "Novo experimento"), el("p", { class: "muted" }, "Roda no servidor, numa fila de um por vez. Dados sintéticos — não validam fraude real."), el("form", { class: "row", onSubmit: async (e) => { e.preventDefault(); const form = new FormData(e.currentTarget); const out = el("p", { "data-job": "" }); e.currentTarget.after(out); const job = await api("/api/ops/ml/experiments", { method: "POST", body: { examples: Number(form.get("examples")), seed: Number(form.get("seed")) }, area: "ops" }); poll(job.id, out); } },
        el("input", { name: "examples", type: "number", min: "200", max: "4000", value: "400", "aria-label": "Quantidade de operações sintéticas" }),
        el("input", { name: "seed", type: "number", value: "42", "aria-label": "Semente" }),
        button({ type: "submit", variant: "primary" }, icon("play", { size: 15 }), " Pedir experimento"))),
      (data.experiments ?? []).length ? el("section", { class: "panel" }, el("h2", {}, "Experimentos (sintético)"), table(["Id", "Status", "Exemplos", "Semente", "Quem"], data.experiments.slice(0, 8).map((ex) => [String(ex.id), ex.status, ex.examples == null ? "0" : String(ex.examples), ex.seed == null ? "—" : String(ex.seed), ex.requestedBy || "—"]))) : null,
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
