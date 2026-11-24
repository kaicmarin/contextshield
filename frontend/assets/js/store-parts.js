import { cutOf, discountOf, installmentText, rating, tintOf } from "./catalog.js";
import { el } from "./utils/dom.js";
import { brl } from "./utils/format.js";
import { icon } from "./utils/icons.js";

export function wordmark(className = "") {
  return el("span", { class: `nx-wordmark ${className}`.trim(), translate: "no" }, "NEXA", el("i", { "aria-hidden": "true" }, "."));
}

export function storeError(message, { onRetry, title = "A vitrine não carregou" } = {}) {
  return el(
    "div",
    { class: "nx-error", role: "alert" },
    icon("alert", { size: 22 }),
    el("div", {}, el("strong", {}, title), el("p", {}, message)),
    onRetry
      ? el("button", { type: "button", class: "nx-btn nx-btn-ink", onClick: onRetry }, icon("refresh", { size: 18 }), "Tentar de novo")
      : null,
  );
}

export function plate(src, alt, { ratio = "1 / 1", size, eager, className = "", cut = true, children } = {}) {
  const box = el("div", {
    class: `nx-plate ${className}`.trim(),
    "data-size": size || null,
    style: { aspectRatio: ratio, "--tint": tintOf(src) },
  });
  const img = el("img", {
    alt,
    width: "1024",
    height: "1024",
    loading: eager ? "eager" : "lazy",
    decoding: "async",
    draggable: "false",
  });
  let failed = false;
  const shown = () => (cut && !failed ? cutOf(src) : src);
  const markLoaded = () => {
    if (img.complete && img.naturalWidth > 0) img.dataset.loaded = "true";
  };
  img.addEventListener("load", () => {
    img.dataset.loaded = "true";
  });
  img.addEventListener("error", () => {
    if (!failed && img.currentSrc.endsWith(".webp")) {
      failed = true;
      box.removeAttribute("data-cut");
      img.src = src;
    }
  });
  img.src = shown();
  markLoaded();
  if (cut) box.dataset.cut = "true";
  box.append(img);
  if (children) box.append(...[children].flat());
  queueMicrotask(markLoaded);
  return { box, media: box };
}

export function priceRow(p, { old = true, installments = true } = {}) {
  const off = discountOf(p);
  return el(
    "div",
    { class: "nx-card-price" },
    old && p.oldPrice
      ? el("s", { class: "nx-price-old num" }, el("span", { class: "sr-only" }, "Preço anterior: "), brl(p.oldPrice))
      : null,
    el("span", { class: "nx-price-now num" }, p.oldPrice ? el("span", { class: "sr-only" }, "Preço atual: ") : null, brl(p.price)),
    installments && p.installments > 1 ? el("span", { class: "nx-price-inst num" }, installmentText(p.price, p.installments)) : null,
    off > 0 && !old ? el("span", { class: "nx-price-off num" }, `−${off}%`) : null,
  );
}

export function priceBlock(p, size = "md", showInstallments = true) {
  const off = discountOf(p);
  return el(
    "div",
    { class: `nx-price nx-price-${size}` },
    el(
      "div",
      { class: "nx-price-row" },
      el("span", { class: "nx-price-now num" }, brl(p.price)),
      p.oldPrice
        ? el("s", { class: "nx-price-old num" }, el("span", { class: "sr-only" }, "Preço anterior: "), brl(p.oldPrice))
        : null,
      off > 0 ? el("span", { class: "nx-price-off num" }, `−${off}%`) : null,
    ),
    showInstallments && p.installments > 1
      ? el("span", { class: "nx-price-inst num" }, `em até ${installmentText(p.price, p.installments)}`)
      : null,
  );
}

export function ratingEl(p) {
  return el(
    "span",
    { class: "nx-rating num", "aria-label": `Nota ${rating(p.rating)} de 5, ${p.reviewCount.toLocaleString("pt-BR")} avaliações` },
    icon("star", { size: 14, className: "nx-star" }),
    rating(p.rating),
    el("span", { class: "nx-rating-n" }, `(${p.reviewCount.toLocaleString("pt-BR")})`),
  );
}

export function productCard(p, { index = 0, size = "md", eager, onAdd } = {}) {
  const off = discountOf(p);
  const media = plate(p.image, "", { eager });
  const article = el(
    "article",
    { class: `nx-card nx-card-${size}`, style: { "--i": String(Math.min(index, 7)) } },
    el(
      "div",
      { class: "nx-card-media" },
      media.box,
      el(
        "span",
        { class: "nx-card-badges" },
        p.tag ? el("span", { class: "nx-badge-tag" }, p.tag) : null,
        off > 0 ? el("span", { class: "nx-badge-off num" }, `−${off}%`) : null,
      ),
    ),
    el(
      "div",
      { class: "nx-card-body" },
      el("h3", { class: "nx-card-name" }, el("a", { href: `/loja/produto/${p.id}`, class: "nx-card-link" }, p.name)),
      el("p", { class: "nx-card-line" }, p.line),
      el(
        "div",
        { class: "nx-card-foot" },
        priceRow(p),
        el(
          "button",
          {
            type: "button",
            class: "nx-quickadd",
            "aria-label": `Adicionar ${p.name} ao carrinho`,
            onClick: () => onAdd?.(p.id, media.box),
          },
          icon("bag", { size: 19 }),
          el("span", { class: "nx-quickadd-plus", "aria-hidden": "true" }, icon("plus", { size: 11, stroke: 2.4 })),
        ),
      ),
    ),
  );
  return article;
}

export function cardSkeleton(size = "md") {
  return el(
    "div",
    { class: `nx-card nx-card-${size} is-skeleton`, "aria-hidden": "true" },
    el("div", { class: "nx-plate nx-skel-plate" }),
    el(
      "div",
      { class: "nx-card-body" },
      el("span", { class: "nx-skel", style: { width: "72%", height: "16px" } }),
      el("span", { class: "nx-skel", style: { width: "48%", height: "13px" } }),
      el("span", { class: "nx-skel", style: { width: "40%", height: "18px", marginTop: "6px" } }),
    ),
  );
}

export function qtyControl(value, name, onChange, { min = 0, max = 10 } = {}) {
  const trash = value - 1 <= 0 && min === 0;
  return el(
    "div",
    { class: "nx-qty", role: "group", "aria-label": `Quantidade de ${name}` },
    el(
      "button",
      {
        type: "button",
        disabled: value <= min,
        "aria-label": trash ? `Remover ${name}` : "Diminuir",
        onClick: () => onChange(value - 1),
      },
      icon(trash ? "trash" : "minus", { size: 16 }),
    ),
    el("output", { class: "num", "aria-live": "polite" }, String(value)),
    el(
      "button",
      { type: "button", disabled: value >= max, "aria-label": "Aumentar", onClick: () => onChange(value + 1) },
      icon("plus", { size: 16 }),
    ),
  );
}
