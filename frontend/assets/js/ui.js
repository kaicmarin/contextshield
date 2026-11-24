import { el, reducedMotion, uid } from "./utils/dom.js";
import { STATUS, STATUS_ICON } from "./utils/format.js";
import { icon } from "./utils/icons.js";

export function button(props = {}, ...children) {
  const { busy, variant, size, block, icon: asIcon, class: extra = "", type = "button", disabled, ...rest } = props;
  const className = ["btn", variant && `btn-${variant}`, size && `btn-${size}`, block && "btn-block", asIcon && "btn-icon", extra]
    .filter(Boolean)
    .join(" ");
  return el(
    "button",
    { ...rest, type, class: className, disabled: !!(busy || disabled), "aria-busy": busy ? "true" : null },
    busy ? el("span", { class: "spinner", "aria-hidden": "true" }) : null,
    el("span", { class: "btn-label" }, ...children),
  );
}

export function pill(tone = "muted", children, { plain = false, iconName, size } = {}) {
  const cls = `pill${plain ? " plain" : ""}${iconName ? " has-ic" : ""} tone-${tone}${size === "sm" ? " pill-sm" : ""}`;
  return el(
    "span",
    { class: cls },
    iconName ? icon(iconName, { size: size === "sm" ? 13 : 14, stroke: 1.9 }) : null,
    children,
  );
}

export function statusPill(status, { customer = false } = {}) {
  const s = STATUS[status] ?? { label: status, customer: status, tone: "muted" };
  return pill(s.tone, customer ? s.customer : s.label);
}

export function txPill(status, size = "md") {
  const s = STATUS[status] ?? { label: status, tone: "muted" };
  return pill(s.tone, s.label, { iconName: STATUS_ICON[status] ?? "info", size });
}

const NOTICE_ICON = { ok: "check", warn: "alert", info: "info", risk: "pause", stop: "stop", muted: "info" };

export function notice(tone = "muted", title, ...children) {
  return el(
    "div",
    { class: `notice tone-${tone}`, role: tone === "stop" ? "alert" : null },
    icon(NOTICE_ICON[tone] ?? "info", { size: 20 }),
    el(
      "div",
      { class: "stack grow", style: { "--gap": "4px" } },
      title ? el("strong", {}, title) : null,
      children.length ? el("div", {}, ...children) : null,
    ),
  );
}

export function errorNotice(error, onRetry) {
  if (!error) return null;
  return notice(
    "stop",
    "Não deu certo",
    el(
      "div",
      { class: "spread" },
      el("span", {}, error.message),
      onRetry
        ? button({ size: "sm", onClick: onRetry }, icon("refresh", { size: 16 }), " Tentar de novo")
        : null,
    ),
  );
}

export function empty(title, { iconName = "inbox", text, action } = {}) {
  return el(
    "div",
    { class: "empty" },
    icon(iconName, { size: 28 }),
    el("h2", {}, title),
    text ? el("p", {}, text) : null,
    action ?? null,
  );
}

export function skeleton(h = 14, w = "100%") {
  return el("div", { class: "skeleton", style: { height: `${h}px`, width: typeof w === "number" ? `${w}px` : w }, "aria-hidden": "true" });
}

export function loading(lines = 3) {
  return el(
    "div",
    { class: "stack", "aria-busy": "true", "aria-label": "Carregando" },
    Array.from({ length: lines }, (_, i) => skeleton(i === 0 ? 22 : 14, i === 0 ? "40%" : `${90 - i * 12}%`)),
  );
}

export function field({ label, hint, error, className = "field", hintClass = "hint", errorClass = "field-error", control }) {
  const id = uid("fld");
  const described = [hint && !error ? `${id}-h` : null, error ? `${id}-e` : null].filter(Boolean).join(" ") || null;
  const input = control({ id, "aria-invalid": error ? "true" : null, "aria-describedby": described });
  return el(
    "div",
    { class: className },
    el("label", { for: id }, label),
    input,
    hint && !error ? el("span", { class: hintClass, id: `${id}-h` }, hint) : null,
    error ? el("span", { class: errorClass, id: `${id}-e` }, error) : null,
  );
}

export function nxField({ label, hint, error, className = "", control }) {
  return field({
    label,
    hint,
    error,
    className: `nx-field ${className}`.trim(),
    hintClass: "nx-field-hint",
    errorClass: "nx-field-err",
    control,
  });
}

export function tabs({ value, onChange, items, label, onKeyDown }) {
  const wrap = el("div", { class: "tabs", role: "tablist", "aria-label": label, onKeydown: onKeyDown });
  for (const it of items) {
    wrap.append(
      el(
        "button",
        {
          type: "button",
          role: "tab",
          class: "tab",
          "aria-selected": value === it.value ? "true" : "false",
          onClick: () => onChange(it.value),
        },
        it.label,
      ),
    );
  }
  const ink = el("span", { class: "tabs-ink", "aria-hidden": "true" });
  wrap.append(ink);
  requestAnimationFrame(() => placeInk(wrap, ink));
  return wrap;
}

function placeInk(wrap, ink) {
  const sel = wrap.querySelector('[aria-selected="true"]');
  if (!sel) return;
  ink.style.transform = `translateX(${sel.offsetLeft}px) scaleX(${sel.offsetWidth})`;
}

export function countUp(value, format = (n) => Math.round(n).toLocaleString("pt-BR"), duration = 700) {
  const span = el("span", { class: "num" }, reducedMotion() ? format(value) : "0");
  if (reducedMotion()) return span;
  const start = performance.now();
  const tick = (t) => {
    const k = Math.min(1, (t - start) / duration);
    const e = 1 - (1 - k) ** 4;
    span.textContent = format(value * e);
    if (k < 1) requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
  return span;
}

let toastHost = null;
export function ensureToasts(className = "toasts") {
  if (toastHost?.isConnected) return toastHost;
  toastHost = el("div", { class: className, role: "status", "aria-live": "polite" });
  document.body.append(toastHost);
  return toastHost;
}

export function toast(text, tone) {
  const host = ensureToasts();
  const node = el(
    "div",
    { class: `toast${tone ? ` tone-${tone}` : ""}` },
    icon(tone === "stop" ? "alert" : "check", { size: 18 }),
    text,
  );
  while (host.children.length >= 3) host.firstElementChild?.remove();
  host.append(node);
  setTimeout(() => node.classList.add("leaving"), 3800);
  setTimeout(() => node.remove(), 4000);
}

export function dialog({ title, onClose, body }) {
  const titleId = uid("dlg");
  const d = el(
    "dialog",
    {
      class: "dialog",
      "aria-labelledby": titleId,
      onClick: (e) => {
        if (e.target === d) onClose();
      },
      onClose,
    },
    el(
      "div",
      { class: "dialog-body stack", style: { "--gap": "16px" } },
      el("h2", { id: titleId, style: { fontSize: "19px" } }, title),
      body,
    ),
  );
  document.body.append(d);
  d.showModal();
  return d;
}

export function loginForm({ area, heading, intro, suggestions, onDone, buttonClass }) {
  let email = suggestions[0]?.email ?? "";
  let password = "";
  let busy = false;
  let error = null;
  const root = el("div");

  async function submit(e) {
    e.preventDefault();
    if (busy) return;
    busy = true;
    error = null;
    draw();
    try {
      const { login } = await import("./api/client.js");
      await login(area, email.trim(), password);
      onDone?.();
    } catch (err) {
      error = err;
      busy = false;
      draw();
    }
  }

  function draw() {
    root.replaceChildren(
      el(
        "form",
        { class: "stack", style: { "--gap": "16px" }, onSubmit: submit, novalidate: true },
        el("div", { class: "stack", style: { "--gap": "6px" } }, heading, intro ? el("p", { class: "muted" }, intro) : null),
        errorNotice(error),
        field({
          label: "E-mail",
          control: (p) =>
            el("input", {
              ...p,
              class: "input",
              type: "email",
              name: "email",
              autocomplete: "username",
              spellcheck: "false",
              value: email,
              required: true,
              onInput: (e) => {
                email = e.target.value;
              },
            }),
        }),
        field({
          label: "Senha",
          hint: "Na demonstração, todas as contas usam “contexto”.",
          control: (p) =>
            el("input", {
              ...p,
              class: "input",
              type: "password",
              name: "password",
              autocomplete: "current-password",
              value: password,
              required: true,
              onInput: (e) => {
                password = e.target.value;
              },
            }),
        }),
        button({ type: "submit", variant: "primary", size: "lg", block: true, busy, class: buttonClass }, "Entrar"),
        suggestions.length > 1
          ? el(
              "div",
              { class: "stack", style: { "--gap": "6px" } },
              el("span", { class: "small muted" }, "Entrar rápido como"),
              el(
                "div",
                { class: "row", style: { "--gap": "6px" } },
                suggestions.map((s) =>
                  button(
                    {
                      size: "sm",
                      onClick: () => {
                        email = s.email;
                        password = "contexto";
                        draw();
                      },
                    },
                    s.label,
                  ),
                ),
              ),
            )
          : null,
      ),
    );
  }
  draw();
  return root;
}
