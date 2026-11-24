/** Pequenos auxiliares de DOM. Texto de usuário entra sempre por textContent. */

const SVG = new Set(["svg", "path", "circle", "rect", "g", "line", "text", "defs", "linearGradient", "stop"]);

/** setProperty exige o nome CSS (aspect-ratio), não o camelCase do objeto style. */
function cssProp(name) {
  return name.startsWith("--") ? name : name.replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`);
}

export function el(tag, props, ...kids) {
  const node = SVG.has(tag) ? document.createElementNS("http://www.w3.org/2000/svg", tag) : document.createElement(tag);
  // O valor de um select só gruda depois que as options existem. Antes disso o navegador volta para a primeira.
  let selectValue;
  if (tag === "select" && props && Object.prototype.hasOwnProperty.call(props, "value")) {
    selectValue = props.value;
    const rest = { ...props };
    delete rest.value;
    attrs(node, rest);
  } else {
    attrs(node, props);
  }
  append(node, kids);
  if (selectValue != null && selectValue !== false) node.value = String(selectValue);
  return node;
}

export function attrs(node, props) {
  if (!props) return node;
  for (const [k, v] of Object.entries(props)) {
    if (v == null || v === false) continue;
    if (k === "class") node.setAttribute("class", v);
    else if (k === "style") {
      if (typeof v === "string") node.setAttribute("style", v);
      else for (const [sk, sv] of Object.entries(v)) if (sv != null) node.style.setProperty(cssProp(sk), String(sv));
    } else if (k === "dataset") {
      for (const [dk, dv] of Object.entries(v)) if (dv != null) node.dataset[dk] = String(dv);
    } else if (k.startsWith("on") && typeof v === "function") node.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === "text") node.textContent = String(v);
    else if (k === "value") node.value = v;
    else if (k === "checked") node.checked = !!v;
    else if (k === "disabled") {
      node.disabled = !!v;
      if (!v) node.removeAttribute("disabled");
    } else if (v === true) node.setAttribute(k, "");
    else node.setAttribute(k, String(v));
  }
  return node;
}

export function append(parent, kids) {
  for (const c of kids.flat(Infinity)) {
    if (c == null || c === false || c === true) continue;
    parent.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return parent;
}

export function replace(parent, ...kids) {
  parent.replaceChildren();
  return append(parent, kids);
}

let n = 0;
export function uid(prefix = "f") {
  n += 1;
  return `${prefix}-${n}`;
}

/** Troca o conteúdo e devolve o foco para o campo que estava ativo, quando ele continua existindo. */
export function paint(parent, node) {
  const active = document.activeElement;
  const id = active?.id;
  const sel =
    active instanceof HTMLInputElement || active instanceof HTMLTextAreaElement
      ? [active.selectionStart, active.selectionEnd]
      : null;
  parent.replaceChildren(node);
  if (!id) return node;
  const next = parent.querySelector(`#${CSS.escape(id)}`);
  if (next instanceof HTMLElement) {
    next.focus({ preventScroll: true });
    if (sel && "setSelectionRange" in next) {
      try {
        next.setSelectionRange(sel[0], sel[1]);
      } catch {
        /* alguns tipos de input não aceitam seleção */
      }
    }
  }
  return node;
}

export function on(target, type, fn, opts) {
  target.addEventListener(type, fn, opts);
  return () => target.removeEventListener(type, fn, opts);
}

export function reducedMotion() {
  return typeof matchMedia !== "undefined" && matchMedia("(prefers-reduced-motion: reduce)").matches;
}

export function cssVar(name, value) {
  return { [name]: value };
}
