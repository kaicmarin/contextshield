import { api } from "./api/client.js";
import { button, notice, pill } from "./ui.js";
import { el, replace } from "./utils/dom.js";
import { dateTime } from "./utils/format.js";
import { icon } from "./utils/icons.js";
import { miniBarChart, stackedBars } from "./lab-charts.js";

const LIVE_MS_DEFAULT = 10000;
const TONE = { PREDICTION: "info", SHADOW_FAILURE: "stop", EXPERIMENT_DONE: "ok", EXPERIMENT_FAILED: "stop", MODEL_APPROVED: "ok", STUDY_DONE: "ok", STUDY_FAILED: "stop" };

let activeMount = null;

export function disposeLabActivity() {
  if (activeMount?.destroy) {
    activeMount.destroy();
    activeMount = null;
  }
}

function qs(params) {
  const p = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== "") p.set(k, String(v));
  });
  const s = p.toString();
  return s ? `?${s}` : "";
}

function card(label, value, hint) {
  return el("article", { class: "ml-act-card" },
    el("span", { class: "ml-act-card-label" }, label),
    el("strong", { class: "ml-act-card-value" }, value == null || value === "" ? "—" : String(value)),
    hint ? el("span", { class: "ml-act-card-hint" }, hint) : null);
}

function eventTone(type = "") {
  const base = String(type).split("_")[0];
  return TONE[type] || TONE[base] || "muted";
}

function timelineItem(ev, onSelectModel, onOpenReport) {
  const aligned = ev.alignedWithEngine;
  const alignLabel = aligned == null ? null : aligned ? "Concorda com o motor" : "Diverge do motor";
  const details = [];
  if (ev.txId) details.push(el("span", { class: "mono" }, ev.txId));
  if (ev.prediction) details.push(el("span", {}, `Prev.: ${ev.prediction}`));
  if (ev.probability != null) details.push(el("span", {}, `Saída: ${Number(ev.probability).toFixed(3)}`));
  if (ev.engineDecision) details.push(el("span", {}, `Motor: ${ev.engineDecision}`));
  if (ev.latencyMs != null) details.push(el("span", {}, `${Number(ev.latencyMs).toFixed(1)} ms`));
  if (ev.experimentId) details.push(el("span", {}, `Exp. ${ev.experimentId}`));
  if (ev.studyId) details.push(el("span", {}, `Estudo ${ev.studyId}`));
  return el("li", { class: "ml-tl-item", "data-event-key": ev.key },
    el("div", { class: "ml-tl-dot" }),
    el("div", { class: "ml-tl-body" },
      el("div", { class: "ml-tl-head" },
        el("time", {}, dateTime(ev.at)),
        pill(eventTone(ev.type), ev.type || "EVENTO", { size: "sm" }),
        alignLabel ? pill(aligned ? "ok" : "warn", alignLabel, { size: "sm" }) : null,
      ),
      el("h3", { class: "ml-tl-title" }, ev.title || ev.type),
      details.length ? el("div", { class: "ml-tl-meta" }, ...details) : null,
      ev.error ? el("p", { class: "ml-tl-error" }, ev.error) : null,
      ev.probabilityNote ? el("p", { class: "muted small" }, ev.probabilityNote) : null,
      ev.modelId ? button({ size: "sm", onClick: () => onSelectModel(ev.modelId) }, `Modelo ${ev.modelId}`) : null,
      ev.reportHref ? button({ size: "sm", onClick: () => onOpenReport(ev) }, "Ver relatório") : null,
    ),
  );
}

export function mountLabActivity(mount, { isAdmin, onOpenStudy } = {}) {
  disposeLabActivity();
  let tab = "live";
  let filters = { page: 0, size: 25, days: 7, liveIntervalMs: LIVE_MS_DEFAULT, cursor: "", cursorStack: [] };
  let summary = null;
  let items = [];
  let seen = new Set();
  let lastRefresh = null;
  let liveTimer = null;
  let inflight = null;
  let loading = false;
  let error = null;
  let empty = false;
  let selectedModel = null;
  let modelDetail = null;
  let selectedReport = null;
  let scrollTop = 0;
  let destroyed = false;

  const listEl = () => mount.querySelector(".ml-tl-list");
  const persistScroll = () => { const ul = listEl(); if (ul) scrollTop = ul.scrollTop; };
  const restoreScroll = () => { const ul = listEl(); if (ul) scrollTop = ul.scrollTop; };

  const stopLive = () => { if (liveTimer) { clearInterval(liveTimer); liveTimer = null; } };
  const scheduleLive = () => {
    stopLive();
    if (destroyed || tab !== "live" || document.hidden) return;
    liveTimer = setInterval(() => refreshFeed(true), filters.liveIntervalMs);
  };

  const loadSummary = async () => {
    summary = await api(`/api/ops/ml/activity/summary?days=${filters.days}`, { area: "ops" });
  };

  const buildQuery = (liveOnly) => ({
    page: liveOnly ? 0 : filters.page,
    size: liveOnly ? 15 : filters.size,
    cursor: liveOnly ? "" : (filters.cursor || undefined),
    modelId: filters.modelId,
    algorithm: filters.algorithm,
    eventType: filters.eventType,
    prediction: filters.prediction,
    align: filters.align,
    stage: filters.stage,
    status: filters.status,
    q: filters.q,
    from: filters.from,
    to: filters.to,
    live: liveOnly,
  });

  const loadFeed = async (liveOnly = false) => {
    const res = await api(`/api/ops/ml/activity${qs(buildQuery(liveOnly))}`, { area: "ops" });
    const incoming = res.items ?? [];
    if (liveOnly) {
      const fresh = incoming.filter((ev) => !seen.has(ev.key));
      fresh.forEach((ev) => seen.add(ev.key));
      if (fresh.length) items = [...fresh, ...items].slice(0, 200);
    } else {
      items = incoming;
      seen = new Set(items.map((e) => e.key));
      if (res.nextCursor) filters.nextCursor = res.nextCursor;
    }
    empty = items.length === 0;
    return res;
  };

  const refreshFeed = async (liveTick = false) => {
    if (destroyed) return;
    if (inflight) return inflight;
    loading = !liveTick;
    error = null;
    const run = async () => {
      try {
        if (!liveTick) await loadSummary();
        persistScroll();
        await loadFeed(liveTick);
        lastRefresh = new Date();
        if (!liveTick) paintShell();
        else patchLiveHead();
        restoreScroll();
      } catch (e) {
        error = e.message || "Falha ao carregar atividades.";
        if (!liveTick) paintShell();
      } finally {
        loading = false;
        inflight = null;
      }
    };
    inflight = run();
    return inflight;
  };

  const patchLiveHead = () => {
    const stamp = mount.querySelector("[data-ml-refresh]");
    if (stamp) stamp.textContent = lastRefresh ? `Atualizado ${lastRefresh.toLocaleTimeString()}` : "";
    const ul = listEl();
    if (!ul || items.length === 0) return;
    const newest = items[0];
    if (ul.querySelector(`[data-event-key="${newest.key}"]`)) return;
    ul.prepend(timelineItem(newest, selectModel, openReport));
    while (ul.children.length > 200) ul.lastElementChild?.remove();
  };

  const selectModel = async (id) => {
    selectedModel = id;
    try {
      modelDetail = await api(`/api/ops/ml/models/${id}/activity`, { area: "ops" });
    } catch (e) {
      modelDetail = { error: e.message };
    }
    paintShell();
  };

  const openReport = async (ev) => {
    const path = String(ev.reportHref || "");
    const study = /^\/api\/ops\/ml\/studies\/(\d+)$/.exec(path);
    if (study && typeof onOpenStudy === "function") {
      await onOpenStudy(Number(study[1]));
      return;
    }
    if (!/^\/api\/ops\/ml\/(?:studies|experiments)\/\d+$/.test(path)) {
      selectedReport = { title: "Relatório", error: "Endereço de relatório inválido." };
      paintShell();
      return;
    }
    try {
      const result = await api(path, { area: "ops" });
      selectedReport = {
        title: ev.title || "Relatório do estudo",
        data: result.report ?? result,
      };
    } catch (error) {
      selectedReport = { title: "Relatório", error: error.message || "Não foi possível abrir o relatório." };
    }
    paintShell();
  };

  const resetPagination = () => {
    filters.page = 0;
    filters.cursor = "";
    filters.cursorStack = [];
    filters.nextCursor = "";
  };

  const paintShell = () => {
    if (destroyed) return;
    const cards = summary?.cards ?? {};
    const charts = summary?.charts ?? {};
    const headRow = el("div", { class: "ml-act-head" },
      el("div", {}, el("h2", {}, "Central de Atividades dos Modelos")),
      el("p", { class: "muted" }, summary?.alignNote || "Modo sombra — o motor decide a compra."),
      el("p", { class: "small muted" }, summary?.calibrationNote || ""),
      el("div", { class: "pol-actions" },
        button({ size: "sm", variant: tab === "live" ? "primary" : "default", onClick: () => { tab = "live"; scheduleLive(); refreshFeed(); } }, icon("play", { size: 14 }), " Ao vivo"),
        button({ size: "sm", variant: tab === "history" ? "primary" : "default", onClick: () => { tab = "history"; stopLive(); refreshFeed(); } }, icon("receipt", { size: 14 }), " Histórico completo"),
        el("span", { class: "small muted", "data-ml-refresh": "" }, lastRefresh ? `Atualizado ${lastRefresh.toLocaleTimeString()}` : loading ? "Atualizando…" : ""),
      ),
    );
    const dashboard = el("div", { class: "ml-act-grid" },
      card("Previsões", cards.predictions),
      card("Últimas 24h", cards.predictions24h),
      card("Modelos em sombra", cards.activeShadowModels),
      card("Concordâncias", cards.agreements, "Direção, não acerto"),
      card("Divergências", cards.disagreements),
      card("Falhas", cards.failures),
      card("Latência média (ms)", cards.meanLatencyMs),
      card("Experimentos recentes", cards.recentExperiments),
      card("Estudos de política", summary?.recentStudies ?? 0, "Core API"),
    );
    const chartRow = el("div", { class: "ml-act-charts" },
      el("section", {}, el("h3", {}, "Previsões por dia"), miniBarChart(charts.predictionsByDay || [], { key: "total", label: (r) => r.d })),
      el("section", {}, el("h3", {}, "Concordância x divergência"), stackedBars(charts.alignmentByDay || [], ["agree", "disagree"], ["#4fd4a8", "#ff7f98"])),
      el("section", {}, el("h3", {}, "Mix FRAUD / LEGIT"), miniBarChart(charts.predictionMix || [], { key: "total", label: (r) => r.label, color: "#c9a0ff" })),
      el("section", {}, el("h3", {}, "Latência por modelo"), miniBarChart(charts.latencyByModel || [], { key: "avgMs", label: (r) => (r.algorithm || `m${r.modelId}`).slice(0, 10), color: "#6ab0ff" })),
    );
    const filterBar = el("form", { class: "ml-act-filters row", onSubmit: (e) => {
      e.preventDefault();
      const fd = new FormData(e.currentTarget);
      filters.modelId = fd.get("modelId") || undefined;
      filters.algorithm = fd.get("algorithm") || undefined;
      filters.q = fd.get("q") || undefined;
      filters.eventType = fd.get("eventType") || undefined;
      filters.prediction = fd.get("prediction") || undefined;
      filters.align = fd.get("align") || undefined;
      filters.status = fd.get("status") || undefined;
      filters.stage = fd.get("stage") || undefined;
      filters.from = fd.get("from") || undefined;
      filters.to = fd.get("to") || undefined;
      resetPagination();
      refreshFeed();
    } },
      el("select", { class: "input", name: "modelId", value: filters.modelId ?? "" },
        el("option", { value: "" }, "Modelo"),
        ...(summary?.models ?? []).map((m) => el("option", { value: String(m.id) }, `${m.id} · ${m.algorithm}`))),
      el("input", { class: "input", name: "algorithm", placeholder: "Algoritmo", value: filters.algorithm ?? "" }),
      el("input", { class: "input", name: "q", placeholder: "TX ou experimento", defaultValue: filters.q ?? "" }),
      el("select", { class: "input", name: "eventType", value: filters.eventType ?? "" },
        el("option", { value: "" }, "Tipo"),
        ["PREDICTION", "SHADOW_FAILURE", "EXPERIMENT_DONE", "EXPERIMENT_QUEUED", "MODEL_APPROVED", "STUDY_DONE"].map((t) => el("option", { value: t }, t))),
      el("select", { class: "input", name: "prediction", value: filters.prediction ?? "" },
        el("option", { value: "" }, "Resultado"),
        el("option", { value: "FRAUD" }, "FRAUD"),
        el("option", { value: "LEGIT" }, "LEGIT")),
      el("select", { class: "input", name: "align", value: filters.align ?? "" },
        el("option", { value: "" }, "Alinhamento"),
        el("option", { value: "agree" }, "Concorda"),
        el("option", { value: "disagree" }, "Diverge")),
      el("select", { class: "input", name: "stage", value: filters.stage ?? "" },
        el("option", { value: "" }, "Etapa"),
        el("option", { value: "initial" }, "Checkout"),
        el("option", { value: "recalculated" }, "Após verificação")),
      el("select", { class: "input", name: "status", value: filters.status ?? "" },
        el("option", { value: "" }, "Execução"),
        el("option", { value: "OK" }, "OK"),
        el("option", { value: "FAILED" }, "Falhou")),
      el("input", { class: "input", name: "from", type: "datetime-local", "aria-label": "De", value: filters.from ?? "" }),
      el("input", { class: "input", name: "to", type: "datetime-local", "aria-label": "Até", value: filters.to ?? "" }),
      button({ type: "submit", size: "sm", variant: "primary" }, "Filtrar"),
      tab === "live" ? el("label", { class: "small" }, "Atualização (s)",
        el("input", { type: "number", class: "input", min: "5", max: "120", value: String(filters.liveIntervalMs / 1000), onChange: (e) => {
          filters.liveIntervalMs = Math.max(5000, Number(e.target.value) * 1000 || LIVE_MS_DEFAULT);
          scheduleLive();
        } })) : null,
    );
    const timeline = el("ul", { class: "ml-tl-list o-stack" },
      items.length ? items.map((ev) => timelineItem(ev, selectModel, openReport)) : el("li", { class: "muted" }, empty && !error ? "Nenhuma atividade no período." : "—"));
    const pager = tab === "history" ? el("div", { class: "pol-actions" },
      button({ size: "sm", disabled: filters.cursorStack.length === 0, onClick: () => {
        filters.cursor = filters.cursorStack.pop() || "";
        filters.page = Math.max(0, filters.page - 1);
        refreshFeed();
      } }, "Anterior"),
      el("span", { class: "small" }, `Página ${filters.page + 1}`),
      button({ size: "sm", disabled: !filters.nextCursor, onClick: () => {
        filters.cursorStack.push(filters.cursor || "");
        filters.cursor = filters.nextCursor || "";
        filters.page += 1;
        refreshFeed();
      } }, "Próxima"),
    ) : null;
    const catalogSrc = modelDetail?.catalogSource;
    const detail = selectedModel && modelDetail ? el("aside", { class: "ml-model-drawer panel" },
      el("div", { class: "ml-drawer-head" },
        el("h3", {}, `Modelo ${selectedModel}`),
        button({ size: "sm", onClick: () => { selectedModel = null; modelDetail = null; paintShell(); } }, "Fechar")),
      modelDetail.error ? notice("stop", "Não carregou", modelDetail.error)
        : el("div", { class: "o-stack small" },
          catalogSrc ? el("p", { class: "muted" }, `Catálogo: ${catalogSrc}`) : null,
          el("p", {}, `${modelDetail.model?.algorithm ?? "—"} · ${modelDetail.model?.status ?? "—"}`),
          el("p", { class: "muted" }, `Previsões: ${modelDetail.predictionTotal ?? "—"} · Concordância: ${modelDetail.agreementRate ?? "—"}%`),
          el("p", { class: "muted" }, modelDetail.alignNote || ""),
        ),
    ) : null;
    const reportPanel = selectedReport
      ? el("section", { class: "panel o-stack", "data-ml-report": "" },
          el("div", { class: "pol-actions" },
            el("h3", {}, selectedReport.title),
            button({ size: "sm", onClick: () => { selectedReport = null; paintShell(); } }, "Fechar")),
          selectedReport.error
            ? notice("stop", "Relatório", selectedReport.error)
            : el("pre", { class: "mono small", style: { whiteSpace: "pre-wrap", overflowWrap: "anywhere", maxHeight: "360px", overflow: "auto" } },
                JSON.stringify(selectedReport.data, null, 2)),
        )
      : null;
    replace(mount,
      el("section", { class: "panel ml-activity o-stack" }, headRow, dashboard, chartRow, filterBar,
        error ? notice("stop", "Conexão", error) : null,
        reportPanel, timeline, pager, detail),
    );
  };

  const onVisibility = () => {
    if (document.hidden) stopLive();
    else {
      scheduleLive();
      if (tab === "live") refreshFeed(true);
    }
  };
  document.addEventListener("visibilitychange", onVisibility);

  const destroy = () => {
    destroyed = true;
    stopLive();
    document.removeEventListener("visibilitychange", onVisibility);
  };

  refreshFeed().then(scheduleLive);
  const ctl = { destroy, refresh: refreshFeed };
  activeMount = ctl;
  return ctl;
}