import { ICON_NAMES, ICONS } from "../icons-data.js";

const NS = "http://www.w3.org/2000/svg";

function shape(spec) {
  const node = document.createElementNS(NS, spec.t);
  for (const [k, v] of Object.entries(spec)) {
    if (k === "t") continue;
    node.setAttribute(k === "strokeWidth" ? "stroke-width" : k, String(v));
  }
  return node;
}

/** Ícone da família do ContextShield. `name` desconhecido cai no ícone de informação. */
export function icon(name, { size = 20, label, className, stroke = 1.6 } = {}) {
  const g = ICONS[name] ?? ICONS.info;
  const svg = document.createElementNS(NS, "svg");
  svg.setAttribute("width", String(size));
  svg.setAttribute("height", String(size));
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("fill", "none");
  svg.setAttribute("stroke", "currentColor");
  svg.setAttribute("stroke-width", String(stroke));
  svg.setAttribute("stroke-linecap", "round");
  svg.setAttribute("stroke-linejoin", "round");
  svg.setAttribute("focusable", "false");
  svg.setAttribute("class", ["ic", `ic-${name}`, className].filter(Boolean).join(" "));
  if (label) {
    svg.setAttribute("role", "img");
    svg.setAttribute("aria-label", label);
  } else svg.setAttribute("aria-hidden", "true");
  for (const p of g.base ?? []) svg.append(shape(p));
  if (g.ac?.length) {
    const group = document.createElementNS(NS, "g");
    group.setAttribute("class", "ac");
    for (const p of g.ac) group.append(shape(p));
    svg.append(group);
  }
  return svg;
}

/** Marca do ContextShield: escudo em camadas e o ponto que é a pessoa. */
export function mark(size = 28, className) {
  const svg = document.createElementNS(NS, "svg");
  svg.setAttribute("width", String(size));
  svg.setAttribute("height", String(size));
  svg.setAttribute("viewBox", "0 0 32 32");
  svg.setAttribute("fill", "none");
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("class", ["mark", className].filter(Boolean).join(" "));
  const outer = document.createElementNS(NS, "path");
  outer.setAttribute("d", "M16 2.8 5.2 7.1v8.1c0 6.6 4.5 11.9 10.8 14 6.3-2.1 10.8-7.4 10.8-14V7.1L16 2.8Z");
  outer.setAttribute("class", "mark-o");
  const inner = document.createElementNS(NS, "path");
  inner.setAttribute("d", "M16 8.2 9.6 10.8v4.7c0 3.9 2.6 7 6.4 8.3 3.8-1.3 6.4-4.4 6.4-8.3v-4.7L16 8.2Z");
  inner.setAttribute("class", "mark-i");
  const dot = document.createElementNS(NS, "circle");
  dot.setAttribute("cx", "16");
  dot.setAttribute("cy", "16");
  dot.setAttribute("r", "2.6");
  dot.setAttribute("class", "mark-d");
  svg.append(outer, inner, dot);
  return svg;
}

export { ICON_NAMES };
