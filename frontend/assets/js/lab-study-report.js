/** Interpretação de report_json dos estudos de pesos/regras (sem inventar métricas). */

function num(v) {
  if (v == null || v === "" || Number.isNaN(Number(v))) return null;
  return Number(v);
}

/**
 * improvedOnBothSets no backend significa: busca melhorou E confirmação não piorou
 * (custo e FN), não necessariamente que ambas as etapas melhoraram.
 */
export function improvementInterpretation(opt) {
  if (!opt || typeof opt !== "object") {
    return { headline: "Sem dados de otimização", detail: "", acceptCriteria: false, misleadingDualImprovement: false };
  }
  const baselineSearchCost = num(opt.baselineSearch?.cost);
  const bestSearchCost = num(opt.bestSearch?.cost);
  const baselineConfirmCost = num(opt.baselineConfirm?.cost);
  const bestConfirmCost = num(opt.bestConfirm?.cost);
  const searchImproved = baselineSearchCost != null && bestSearchCost != null && bestSearchCost < baselineSearchCost;
  const confirmNotWorse = baselineConfirmCost != null && bestConfirmCost != null && bestConfirmCost <= baselineConfirmCost;
  const confirmUnchanged = baselineConfirmCost != null && bestConfirmCost != null && bestConfirmCost === baselineConfirmCost;

  let headline;
  if (searchImproved && confirmUnchanged) headline = "Melhorou a busca e não piorou a confirmação";
  else if (searchImproved && confirmNotWorse) headline = "Melhorou a busca e manteve a confirmação dentro do patamar aceito";
  else if (Boolean(opt.improvedOnBothSets)) headline = "Passou nos critérios de aceite (busca + confirmação estável)";
  else headline = "Nenhuma melhoria confiável na busca com confirmação estável";

  const detail = opt.note || opt.improvementSummary || "";
  const misleadingDualImprovement = Boolean(opt.improvedOnBothSets) && searchImproved && confirmUnchanged;

  return {
    headline,
    detail,
    acceptCriteria: Boolean(opt.improvedOnBothSets),
    misleadingDualImprovement,
    searchImproved,
    confirmNotWorse,
    published: opt.published === true,
    autoPublish: false,
  };
}

export function weightStudyDataOrigin(report) {
  if (!report) return { primary: "desconhecida", reviewedCount: 0, synthetic: true, warnings: [] };
  const mix = report.dataMix || {};
  const reviewed = num(mix.reviewedFraudLegit) ?? 0;
  const warnings = [];
  if (report.reviewedInsufficient) warnings.push(report.reviewedMessage || "Poucos rótulos revisados para otimizar com dados reais.");
  if (reviewed === 0) warnings.push("Nenhum rótulo real revisado entrou neste estudo; métricas sintéticas não comprovam fraude bancária.");
  const primary = reviewed > 0 ? "misto (revisados + sintético)" : "sintético";
  return { primary, reviewedCount: reviewed, synthetic: reviewed === 0, warnings };
}

export function metricTriple(baseline, best) {
  const keys = ["cost", "falsePositives", "falseNegatives"];
  return keys.map((key) => ({
    key,
    label: key === "cost" ? "Custo" : key === "falsePositives" ? "Falsos positivos" : "Falsos negativos",
    before: num(baseline?.[key]),
    after: num(best?.[key]),
  }));
}

/** Dados para gráficos de barras (valores reais do relatório). */
export function comparisonChartSeries(opt) {
  if (!opt) return { search: [], confirm: [] };
  return {
    search: metricTriple(opt.baselineSearch, opt.bestSearch),
    confirm: metricTriple(opt.baselineConfirm, opt.bestConfirm),
  };
}

export function weightDiffRows(published, suggested) {
  if (!suggested || typeof suggested !== "object" || !Object.keys(suggested).length) return [];
  const base = published && typeof published === "object" ? published : {};
  return Object.entries(suggested).map(([key, to]) => ({
    key,
    from: num(base[key]) ?? base[key],
    to: num(to) ?? to,
  }));
}

/** Fixture alinhado ao estudo 10 (AWS): regressão de texto e números. */
export const STUDY_10_REGRESSION = {
  kind: "WEIGHTS",
  status: "DONE",
  report: {
    publishedRulesCount: 16,
    reviewedInsufficient: true,
    recommendation: "test",
    dataMix: { reviewedFraudLegit: 0, pendingReports: 0 },
    syntheticOptimization: {
      method: "busca aleatória estruturada",
      candidates: 48,
      requestedCandidates: 48,
      published: false,
      improvedOnBothSets: true,
      dynamicRulesApplied: true,
      cost: "Padrão",
      costProfileId: "standard",
      baselineSearch: { cost: 50, falsePositives: 10, falseNegatives: 8 },
      bestSearch: { cost: 49, falsePositives: 9, falseNegatives: 8 },
      baselineConfirm: { cost: 36, falsePositives: 6, falseNegatives: 6 },
      bestConfirm: { cost: 36, falsePositives: 6, falseNegatives: 6 },
      suggestedWeights: { THRESHOLD_MEDIUM: 12 },
      note: "A sugestão reduziu o custo na busca e não piorou falso negativo nem custo na confirmação. Ela não foi publicada.",
      reasons: [{ key: "THRESHOLD_MEDIUM", from: 10, to: 12, why: "Ajuste candidato." }],
    },
    syntheticBaseline: { origin: "sintético", warning: "Não mede fraude real." },
  },
};

export function summarizeWeightReport(report) {
  const opt = report?.syntheticOptimization;
  const interp = improvementInterpretation(opt);
  const origin = weightStudyDataOrigin(report);
  return {
    method: opt?.method || "—",
    candidates: num(opt?.candidates) ?? num(opt?.requestedCandidates) ?? 0,
    rulesCount: num(report?.publishedRulesCount) ?? 0,
    costProfile: report?.costProfile?.description || opt?.cost || opt?.costProfileId || "—",
    origin: origin.primary,
    reviewedCount: origin.reviewedCount,
    warnings: origin.warnings,
    recommendation: report?.recommendation || interp.detail || interp.headline,
    interp,
    charts: comparisonChartSeries(opt),
    weightDiffs: weightDiffRows(report?.publishedWeights, opt?.suggestedWeights),
    leakageControl: report?.leakageControl || "",
    syntheticWarning: report?.syntheticBaseline?.warning || "",
    note: opt?.note || "",
    published: opt?.published === true,
  };
}