import { el } from "./utils/dom.js";

export function miniBarChart(rows, { key = "total", label = (r) => r.d, color = "#5b8def", height = 120 } = {}) {
  if (!rows?.length) return el("p", { class: "muted small" }, "Sem dados no período.");
  const w = 320;
  const pad = { l: 8, r: 8, b: 22, t: 8 };
  const max = Math.max(1, ...rows.map((r) => Number(r[key]) || 0));
  const slot = (w - pad.l - pad.r) / rows.length;
  const bars = rows.map((r, i) => {
    const v = Number(r[key]) || 0;
    const h = ((height - pad.t - pad.b) * v) / max;
    return el("rect", {
      fill: color,
      opacity: "0.85",
      x: pad.l + i * slot + 2,
      y: height - pad.b - h,
      width: Math.max(2, slot - 4),
      height: Math.max(0, h),
      rx: "2",
    }, el("title", {}, `${label(r)}: ${v}`));
  });
  const labels = rows.length <= 10
    ? rows.map((r, i) => el("text", {
      class: "tick day",
      x: pad.l + i * slot + slot / 2,
      y: height - 4,
      "text-anchor": "middle",
      "font-size": "9",
    }, String(label(r)).slice(5)))
    : [];
  return el("svg", { class: "ml-mini-chart", viewBox: `0 0 ${w} ${height}`, role: "img" }, ...bars, ...labels);
}

export function stackedBars(rows, keys, colors, height = 130) {
  if (!rows?.length) return el("p", { class: "muted small" }, "Sem dados no período.");
  const w = 320;
  const pad = { l: 8, r: 8, b: 22, t: 8 };
  const max = Math.max(1, ...rows.map((r) => keys.reduce((s, k) => s + (Number(r[k]) || 0), 0)));
  const slot = (w - pad.l - pad.r) / rows.length;
  const parts = [];
  rows.forEach((r, i) => {
    let yBase = height - pad.b;
    keys.forEach((k, ki) => {
      const v = Number(r[k]) || 0;
      const h = ((height - pad.t - pad.b) * v) / max;
      yBase -= h;
      parts.push(el("rect", {
        fill: colors[ki] || "#888",
        x: pad.l + i * slot + 2,
        y: yBase,
        width: Math.max(2, slot - 4),
        height: Math.max(0, h),
        rx: "1",
      }));
    });
  });
  return el("svg", { class: "ml-mini-chart", viewBox: `0 0 ${w} ${height}`, role: "img" }, ...parts);
}