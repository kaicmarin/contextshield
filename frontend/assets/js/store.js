import { api, getSession, onSession, setSession } from "./api/client.js";
import { addLine, cartState, loadProducts, onCart, reloadProducts, setQty } from "./cart.js";
import { arrivalDate, CURATED, cutOf, FEATURE_ID, fold, HERO_ID, installmentText, productNo, rating, searchProducts, SORTS, sortProducts, specsOf, tintOf } from "./catalog.js";
import { createRouter } from "./router.js";
import { createCheckout, orderView } from "./store-flow.js";
import { cardSkeleton, plate, priceBlock, productCard, qtyControl, ratingEl, storeError, wordmark } from "./store-parts.js";
import { loginForm, statusPill } from "./ui.js";
import { el, reducedMotion } from "./utils/dom.js";
import { brl, dateTime, setTitle } from "./utils/format.js";
import { icon, mark } from "./utils/icons.js";

const ORDER = ["informatica", "celulares", "audio", "games", "foto", "casa", "eletrodomesticos", "escritorio", "ferramentas", "acessorios"];
const SIGNS = [
  ["Alguém pediu a compra por telefone ou mensagem", "A NEXA e o seu banco nunca pedem que você compre algo para cancelar, validar ou estornar outra operação."],
  ["Há pressa e ameaça", "“Faça agora ou sua conta será bloqueada.” Urgência é a ferramenta principal de quem aplica o golpe."],
  ["O endereço de entrega não é seu", "Se passaram um endereço para você digitar, o produto vai para quem está aplicando o golpe."],
  ["Pediram para você não contar a ninguém", "Nenhuma central de verdade pede segredo. Desligue e ligue você mesmo para o número no verso do cartão."],
];
const HELP_NAV = [
  ["seguranca", "Compra segura", "shield"],
  ["entrega", "Entrega", "truck"],
  ["trocas", "Trocas e devoluções", "return"],
  ["pagamento", "Pagamento", "card"],
];

let cats = null;
let router;
let checkout;
let menu = false;
let said = "";
let inFlight = 0;
let heroPlayed = false;
let searchState = { q: "", open: false, active: -1 };
const root = el("div", { class: "nx" });
const header = el("header", { class: "nx-top" });
const main = el("main", { id: "conteudo", class: "nx-main" });
const footer = el("footer", { class: "nx-foot" });
const live = el("p", { class: "sr-only", "aria-live": "polite" });
const drawer = el("dialog", { class: "nx-drawer", "aria-labelledby": "nx-drawer-title" });
let cartBtn;

function session() {
  return getSession("customer");
}

function go(url, opts) {
  return router.go(url, opts);
}

function openCart() {
  if (!drawer.open) drawer.showModal();
}
function closeCart() {
  drawer.close();
}

function addToCart(productId, from, qty = 1) {
  const p = cartState().byId.get(productId);
  addLine(productId, qty);
  said = `${p?.name ?? "Produto"} adicionado ao carrinho.`;
  live.textContent = said;
  if (reducedMotion() || !from || !cartBtn || !p) return;
  const a = from.getBoundingClientRect();
  const b = cartBtn.getBoundingClientRect();
  const visible = b.bottom > 0 && b.top < window.innerHeight && a.width > 0;
  if (!visible) return;
  const ghost = document.createElement("div");
  ghost.className = "nx-ghost";
  ghost.setAttribute("aria-hidden", "true");
  Object.assign(ghost.style, {
    left: `${a.left}px`,
    top: `${a.top}px`,
    width: `${a.width}px`,
    height: `${a.height}px`,
    backgroundImage: `url("${cutOf(p.image)}")`,
  });
  root.append(ghost);
  const dx = b.left + b.width / 2 - (a.left + a.width / 2);
  const dy = b.top + b.height / 2 - (a.top + a.height / 2);
  const s = Math.max(0.06, 30 / Math.max(a.width, a.height));
  inFlight += qty;
  paintBadge();
  const done = () => {
    ghost.remove();
    inFlight = Math.max(0, inFlight - qty);
    paintBadge(true);
  };
  const anim = ghost.animate(
    [
      { transform: "translate(0, 0) scale(1)", opacity: 1 },
      { transform: `translate(${dx}px, ${dy}px) scale(${s})`, opacity: 0.85, offset: 0.9 },
      { transform: `translate(${dx}px, ${dy}px) scale(${s * 0.8})`, opacity: 0 },
    ],
    { duration: 450, easing: "cubic-bezier(0.65, 0, 0.35, 1)" },
  );
  anim.onfinish = done;
  anim.oncancel = done;
}

function paintBadge(tick = false) {
  if (!cartBtn) return;
  const count = cartState().count;
  const shown = Math.max(0, count - inFlight);
  cartBtn.setAttribute("aria-label", `Carrinho, ${count} ${count === 1 ? "item" : "itens"}`);
  const badge = cartBtn.querySelector(".nx-badge");
  const num = cartBtn.querySelector(".nx-badge-n");
  if (!badge || !num) return;
  badge.classList.toggle("is-zero", !shown);
  num.textContent = String(shown);
  num.className = tick ? "nx-badge-n tick" : "nx-badge-n";
}

function paintHeader(path) {
  const s = session();
  const first = s?.user.name.split(" ")[0];
  header.className = `nx-top${window.scrollY > 4 ? " is-scrolled" : ""}`;
  const menuBtn = el(
    "button",
    {
      type: "button",
      class: "nx-catbtn",
      "aria-expanded": menu ? "true" : "false",
      "aria-controls": "nx-catalog",
      onClick: () => {
        menu = !menu;
        paintHeader(path);
      },
    },
    icon(menu ? "x" : "grid", { size: 18 }),
    el("span", {}, "Catálogo"),
  );
  cartBtn = el(
    "button",
    { type: "button", class: "nx-cartbtn", "aria-haspopup": "dialog", onClick: openCart },
    icon("bag", { size: 22 }),
    el("span", { class: "nx-cartbtn-label" }, "Carrinho"),
    el("span", { class: `nx-badge${cartState().count ? "" : " is-zero"}`, "aria-hidden": "true" }, el("span", { class: "nx-badge-n" }, "0")),
  );
  header.replaceChildren(
    el(
      "div",
      { class: "nx-top-inner nx-wrap" },
      el("a", { href: "/loja", class: "nx-logo", "aria-label": "NEXA, página inicial da loja" }, wordmark()),
      menuBtn,
      el("div", { class: "nx-top-search" }, searchBox()),
      el(
        "nav",
        { class: "nx-top-links", "aria-label": "Sua conta" },
        el("a", { href: "/loja/ajuda", class: "nx-toplink nx-hide-md", "aria-current": path === "/loja/ajuda" ? "page" : null }, "Ajuda"),
        el("a", { href: "/loja/pedidos", class: "nx-toplink nx-hide-md", "aria-current": path === "/loja/pedidos" ? "page" : null }, "Pedidos"),
        s
          ? el(
              "a",
              { href: "/loja/pedidos", class: "nx-account", "aria-label": `Conta de ${s.user.name}, seus pedidos` },
              el("span", { class: "nx-avatar", "aria-hidden": "true" }, first?.[0] ?? ""),
              el("span", { class: "nx-hide-sm" }, first ?? ""),
            )
          : el("a", { href: "/loja/entrar", class: "nx-account" }, icon("user", { size: 20 }), el("span", { class: "nx-hide-sm" }, "Entrar")),
        cartBtn,
      ),
    ),
    el(
      "div",
      { id: "nx-catalog", class: "nx-catalog", hidden: menu ? null : true },
      el(
        "div",
        { class: "nx-wrap nx-catalog-inner" },
        el(
          "ul",
          { class: "nx-catalog-list" },
          (cats ?? []).map((c, i) =>
            el(
              "li",
              { style: { "--i": String(i) } },
              el(
                "a",
                { href: `/loja?cat=${c.slug}`, class: "nx-catalog-item" },
                el("span", { class: "nx-catalog-ic", "aria-hidden": "true" }, icon(c.slug, { size: 22 })),
                el("span", { class: "nx-catalog-txt" }, el("strong", {}, c.name), el("span", {}, c.blurb)),
              ),
            ),
          ),
        ),
        el(
          "div",
          { class: "nx-catalog-side" },
          el("a", { href: "/loja#vitrine", class: "nx-btn nx-btn-ink nx-btn-block" }, "Ver todos os produtos ", icon("arrow", { size: 16 })),
          el(
            "a",
            { href: "/loja/ajuda#seguranca", class: "nx-catalog-safe" },
            icon("shield", { size: 22 }),
            el("span", {}, el("strong", {}, "Compra protegida"), el("span", {}, "Como reconhecer um pedido falso")),
          ),
          el(
            "a",
            { href: "/loja/pedidos", class: "nx-catalog-safe nx-show-md" },
            icon("receipt", { size: 22 }),
            el("span", {}, el("strong", {}, "Seus pedidos"), el("span", {}, "Acompanhe pagamento e entrega")),
          ),
        ),
      ),
    ),
  );
  paintBadge();
  if (menu) menuBtn.focus();
}

function paintFooter() {
  footer.replaceChildren(
    el(
      "div",
      { class: "nx-wrap" },
      el(
        "div",
        { class: "nx-foot-grid" },
        el(
          "div",
          { class: "nx-foot-brand" },
          el("p", {}, "Tecnologia para o dia a dia, com entrega para todo o Brasil e pagamento analisado pelo seu banco."),
          el("p", { class: "nx-foot-shield" }, mark(20), " Pagamentos com cartão protegidos pelo ContextShield do banco Aureon."),
        ),
        navBlock("f-loja", "Loja", (cats ?? []).slice(0, 6).map((c) => el("li", {}, el("a", { href: `/loja?cat=${c.slug}` }, c.name)))),
        navBlock("f-ajuda", "Ajuda", [
          ["#seguranca", "Compra segura"],
          ["#entrega", "Entrega"],
          ["#trocas", "Trocas e devoluções"],
          ["#pagamento", "Pagamento"],
        ].map(([h, t]) => el("li", {}, el("a", { href: `/loja/ajuda${h}` }, t)))),
        navBlock("f-conta", "Conta", [
          ["/loja/entrar", "Entrar"],
          ["/loja/pedidos", "Seus pedidos"],
          ["/banco", "App Aureon"],
          ["/", "Sobre o ContextShield"],
        ].map(([h, t]) => el("li", {}, el("a", { href: h }, t)))),
      ),
      el("p", { class: "nx-foot-word", "aria-hidden": "true" }, el("span", {}, "NEXA."), el("span", {}, "STORE")),
      el("p", { class: "nx-foot-legal" }, "NEXA e Aureon são marcas fictícias, criadas para a demonstração acadêmica do ContextShield. Nenhum pagamento real é feito."),
    ),
  );
}

function navBlock(id, title, items) {
  return el("nav", { "aria-labelledby": id }, el("h2", { id }, title), el("ul", {}, items));
}

function searchBox() {
  const cart = cartState();
  const id = "nx-q";
  const listId = "nx-q-list";
  const all = searchProducts(cart.products, cats, searchState.q);
  const options = all.length ? [...all.slice(0, 6).map((p) => ({ kind: "product", p })), { kind: "all", total: all.length }] : [];
  const show = searchState.open && searchState.q.trim().length > 0;
  const form = el("form", {
    class: "nx-search",
    role: "search",
    "aria-label": "Buscar produtos",
    onSubmit: (e) => {
      e.preventDefault();
      const picked = searchState.active >= 0 ? options[searchState.active] : null;
      if (picked) return goOption(picked, all.length);
      if (searchState.q.trim()) goOption({ kind: "all", total: all.length }, all.length);
    },
  });
  const input = el("input", {
    id,
    class: "nx-search-input",
    type: "search",
    role: "combobox",
    "aria-expanded": show ? "true" : "false",
    "aria-controls": listId,
    "aria-autocomplete": "list",
    "aria-activedescendant": show && searchState.active >= 0 ? `nx-opt-${searchState.active}` : null,
    autocomplete: "off",
    spellcheck: "false",
    enterkeyhint: "search",
    placeholder: "Buscar notebook, fone, cadeira…",
    value: searchState.q,
    onInput: (e) => {
      searchState.q = e.target.value;
      searchState.open = true;
      searchState.active = -1;
      paintSearchList(form, optionsFor(e.target.value), true);
    },
    onFocus: () => {
      searchState.open = true;
      paintSearchList(form, optionsFor(searchState.q), true);
    },
    onBlur: () => {
      searchState.open = false;
      setTimeout(() => paintSearchList(form, [], false), 120);
    },
    onKeydown: (e) => onSearchKey(e, form),
  });
  form.append(
    el("label", { for: id, class: "sr-only" }, "Buscar na NEXA"),
    icon("search", { size: 18, className: "nx-search-ic" }),
    input,
    searchState.q ? null : el("kbd", { class: "nx-search-kbd", "aria-hidden": "true" }, "/"),
    el("div", { id: listId, role: "listbox", "aria-label": "Sugestões de produtos", class: "nx-search-pop", hidden: show ? null : true }),
  );
  paintSearchList(form, options, show);
  return form;
}

function optionsFor(q) {
  const all = searchProducts(cartState().products, cats, q);
  return all.length ? [...all.slice(0, 6).map((p) => ({ kind: "product", p })), { kind: "all", total: all.length }] : [];
}

function paintSearchList(form, options, show) {
  const list = form.querySelector(".nx-search-pop");
  const input = form.querySelector("input");
  if (!list || !input) return;
  input.setAttribute("aria-expanded", show ? "true" : "false");
  input.setAttribute("aria-activedescendant", show && searchState.active >= 0 ? `nx-opt-${searchState.active}` : "");
  if (!show) {
    list.hidden = true;
    return;
  }
  list.hidden = false;
  if (!options.length) {
    list.replaceChildren(el("p", { class: "nx-search-empty", role: "presentation" }, `Nada encontrado para “${searchState.q.trim()}”. Tente “notebook”, “fone” ou “cadeira”.`));
    return;
  }
  list.replaceChildren(
    ...options.map((o, i) => {
      const props = {
        id: `nx-opt-${i}`,
        role: "option",
        "aria-selected": i === searchState.active ? "true" : "false",
        class: o.kind === "all" ? "nx-search-opt nx-search-all" : "nx-search-opt",
        onMousedown: (e) => e.preventDefault(),
        onMousemove: () => {
          if (searchState.active !== i) {
            searchState.active = i;
            paintSearchList(form, options, true);
          }
        },
        onClick: () => goOption(o),
      };
      if (o.kind === "product") {
        return el(
          "div",
          props,
          el("img", { src: cutOf(o.p.image), alt: "", width: "44", height: "44", style: { background: tintOf(o.p.image) } }),
          el("span", { class: "nx-search-txt" }, el("span", { class: "nx-search-name" }, o.p.name), el("span", { class: "nx-search-line" }, o.p.line)),
          el("span", { class: "nx-search-price num" }, brl(o.p.price)),
        );
      }
      return el("div", props, el("span", {}, o.total === 1 ? `Ver o resultado para “${searchState.q.trim()}”` : `Ver os ${o.total} resultados para “${searchState.q.trim()}”`), icon("arrow", { size: 16 }));
    }),
  );
}

function onSearchKey(e, form) {
  const options = optionsFor(searchState.q);
  const show = searchState.open && searchState.q.trim();
  if (e.key === "ArrowDown" || e.key === "ArrowUp") {
    e.preventDefault();
    searchState.open = true;
    if (!options.length) return;
    searchState.active = e.key === "ArrowDown" ? (searchState.active + 1) % options.length : searchState.active <= 0 ? options.length - 1 : searchState.active - 1;
    paintSearchList(form, options, true);
  } else if (e.key === "Escape") {
    if (show) {
      e.preventDefault();
      searchState.open = false;
      searchState.active = -1;
      paintSearchList(form, options, false);
    } else if (searchState.q) {
      e.preventDefault();
      searchState.q = "";
      paintHeader(location.pathname);
    }
  }
}

function goOption(o) {
  searchState.open = false;
  searchState.active = -1;
  if (o.kind === "product") {
    searchState.q = "";
    go(`/loja/produto/${o.p.id}`);
  } else go(`/loja?busca=${encodeURIComponent(searchState.q.trim())}#vitrine`);
}

function paintDrawer() {
  const cart = cartState();
  const n = cart.count;
  const items = cart.lines.map((l) => ({ l, p: cart.byId.get(l.productId) }));
  drawer.replaceChildren(
    el(
      "div",
      { class: "nx-drawer-inner" },
      el(
        "header",
        { class: "nx-drawer-head" },
        el("h2", { id: "nx-drawer-title" }, "Carrinho ", el("span", { class: "nx-mono-meta num" }, n ? `${n} ${n === 1 ? "item" : "itens"}` : "")),
        el("button", { type: "button", class: "nx-iconbtn", "aria-label": "Fechar carrinho", onClick: closeCart }, icon("x", { size: 20 })),
      ),
      cart.lines.length === 0
        ? el(
            "div",
            { class: "nx-drawer-empty" },
            el("span", { class: "nx-empty-mark", "aria-hidden": "true" }, icon("bag", { size: 28 })),
            el("h3", {}, "Seu carrinho está vazio"),
            el("p", {}, "Os produtos que você adicionar aparecem aqui, com o total e as parcelas."),
            el("button", { type: "button", class: "nx-btn nx-btn-ink", onClick: () => { closeCart(); go("/loja#vitrine"); } }, "Ver a vitrine"),
          )
        : el(
            "div",
            {},
            el(
              "ul",
              { class: "nx-drawer-lines", "aria-label": "Itens no carrinho" },
              items.map(({ l, p }) =>
                p
                  ? el(
                      "li",
                      { class: "nx-cline" },
                      el("a", { href: `/loja/produto/${p.id}`, class: "nx-cline-ph", tabindex: "-1", "aria-hidden": "true" }, plate(p.image, "", { size: "sm" }).box),
                      el(
                        "div",
                        { class: "nx-cline-body" },
                        el("a", { href: `/loja/produto/${p.id}`, class: "nx-cline-name" }, p.name),
                        el("span", { class: "nx-cline-line" }, p.line),
                        el(
                          "div",
                          { class: "nx-cline-foot" },
                          qtyControl(l.quantity, p.name, (q) => setQty(p.id, q)),
                          el("span", { class: "nx-cline-price num" }, brl(p.price * l.quantity)),
                        ),
                      ),
                    )
                  : el("li", { class: "nx-cline is-loading", "aria-hidden": "true" }, el("span", { class: "nx-skel", style: { width: "88px", height: "88px" } })),
              ),
            ),
            el(
              "footer",
              { class: "nx-drawer-foot" },
              el(
                "dl",
                { class: "nx-sum" },
                el("div", {}, el("dt", {}, "Subtotal"), el("dd", { class: "num" }, brl(cart.total))),
                el("div", {}, el("dt", {}, "Frete"), el("dd", {}, "Grátis")),
                el("div", { class: "nx-sum-total" }, el("dt", {}, "Total"), el("dd", { class: "num" }, brl(cart.total))),
              ),
              cart.maxInstallments > 1 ? el("p", { class: "nx-sum-note num" }, `em até ${installmentText(cart.total, cart.maxInstallments)}`) : null,
              el("a", { href: "/loja/carrinho", class: "nx-btn nx-btn-accent nx-btn-lg nx-btn-block" }, "Finalizar compra", icon("arrow", { size: 18 })),
              el("button", { type: "button", class: "nx-btn nx-btn-ghost nx-btn-block", onClick: closeCart }, "Continuar comprando"),
            ),
          ),
    ),
  );
}

function covers() {
  if (!cats) return [];
  const products = cartState().products;
  return ORDER.map((slug) => cats.find((c) => c.slug === slug))
    .filter(Boolean)
    .map((c) => {
      const inCat = products.filter((p) => p.categorySlug === c.slug && !/^prod-02[4-8]$/.test(p.id));
      const cover = [...inCat].sort((a, b) => b.reviewCount - a.reviewCount)[0];
      return { c, cover, count: products.filter((p) => p.categorySlug === c.slug).length };
    });
}

let renderGen = 0;
async function render(loc) {
  const gen = ++renderGen;
  menu = false;
  const path = loc.path;
  paintHeader(path);
  paintFooter();
  paintDrawer();
  closeCart();
  const page = await pageFor(loc);
  if (gen !== renderGen) return;
  main.replaceChildren(page);
}

async function pageFor(loc) {
  if (loc.name === "home") return shelf(loc.search);
  if (loc.name === "product") return productPage(loc.params.id);
  if (loc.name === "cart") return checkoutPage(loc.search);
  if (loc.name === "order") return orderPage(loc.params.id, loc.state);
  if (loc.name === "login") return loginPage(loc.search);
  if (loc.name === "orders") return ordersPage();
  if (loc.name === "help") return helpPage(loc.hash);
  setTitle("Página não encontrada · NEXA");
  return el(
    "div",
    { class: "nx-wrap nx-pad" },
    el(
      "div",
      { class: "nx-empty nx-empty-lg" },
      el("span", { class: "nx-empty-mark", "aria-hidden": "true" }, icon("search", { size: 28 })),
      el("h1", { class: "nx-h2" }, "Esta página não existe"),
      el("p", {}, "O endereço pode ter mudado. A vitrine e a busca continuam no mesmo lugar."),
      el("a", { href: "/loja", class: "nx-btn nx-btn-ink nx-btn-lg" }, "Voltar à vitrine"),
    ),
  );
}

function shelf(search) {
  const cart = cartState();
  const cat = search.get("cat");
  const busca = search.get("busca")?.trim() ?? "";
  const sort = search.get("ordem");
  const category = cats?.find((c) => c.slug === cat);
  const base = busca ? searchProducts(cart.products, cats, busca) : cart.products.filter((p) => !cat || p.categorySlug === cat);
  const list = sortProducts(base, sort);
  const isHome = !cat && !busca;
  setTitle(busca ? `Busca: ${busca} · NEXA` : category ? `${category.name} · NEXA` : "NEXA · tecnologia para o dia a dia");
  searchState.q = busca;
  return el(
    "div",
    { class: "nx-shelf" },
    isHome ? [hero(), stories(), curated(), promos(), bento()] : listHead(busca, category, list.length, cart.status),
    el(
      "section",
      { id: "vitrine", class: "nx-section nx-wrap", "aria-labelledby": "h-grid" },
      el(
        "div",
        { class: "nx-section-head" },
        el("h2", { id: "h-grid", class: isHome ? "nx-h2" : "sr-only" }, isHome ? "Todos os produtos" : "Produtos"),
        isHome && cart.status === "ready" ? el("span", { class: "nx-mono-meta num" }, `${cart.products.length} produtos`) : null,
        el(
          "label",
          { class: "nx-sort" },
          el("span", {}, "Ordenar"),
          el(
            "select",
            {
              class: "nx-select",
              value: sort ?? "relevancia",
              onChange: (e) => {
                const next = new URLSearchParams(search);
                if (e.target.value === "relevancia") next.delete("ordem");
                else next.set("ordem", e.target.value);
                go(`/loja?${next}#vitrine`, { replace: true });
              },
            },
            SORTS.map((s) => el("option", { value: s.id }, s.label)),
          ),
        ),
      ),
      cart.status === "error"
        ? storeError(cart.error?.message ?? "Não foi possível falar com a loja.", { onRetry: reloadProducts })
        : cart.status === "loading"
          ? el("div", { class: "nx-grid", "aria-busy": "true", "aria-label": "Carregando produtos" }, Array.from({ length: 8 }, () => cardSkeleton()))
          : list.length === 0
            ? el(
                "div",
                { class: "nx-empty" },
                icon(busca ? "search" : "inbox", { size: 28 }),
                el("h3", {}, busca ? `Nada encontrado para “${busca}”` : "Nada nesta categoria por enquanto"),
                el("p", {}, busca ? "Confira a grafia ou tente um termo mais curto, como “fone” ou “notebook”." : "Veja as outras categorias no menu acima."),
                el("a", { href: "/loja", class: "nx-btn nx-btn-ink" }, "Ver todos os produtos"),
              )
            : el("div", { class: "nx-grid nx-stagger" }, list.map((p, i) => productCard(p, { index: i, eager: i < 4 && !isHome, onAdd: addToCart }))),
    ),
    isHome ? safetyBand() : null,
  );
}

function listHead(busca, category, n, status) {
  return el(
    "header",
    { class: "nx-listhead nx-wrap" },
    category ? el("span", { class: "nx-listhead-ic", "aria-hidden": "true" }, icon(category.slug, { size: 30, stroke: 1.4 })) : null,
    el("h1", { class: "nx-h1" }, busca ? `Resultados para “${busca}”` : category?.name ?? "Categoria"),
    el(
      "p",
      { class: "nx-listhead-sub" },
      busca ? "Busca por nome, configuração e categoria." : category?.blurb ?? "",
      status === "ready" ? el("span", { class: "nx-mono-meta num" }, ` · ${n} ${n === 1 ? "produto" : "produtos"}`) : null,
    ),
  );
}

function hero() {
  const cart = cartState();
  const p = cart.byId.get(HERO_ID);
  const still = heroPlayed;
  heroPlayed = true;
  const specs = p ? specsOf(p).slice(0, 3) : [];
  const stage = el(
    "div",
    { class: "nx-hero-stage" },
    el("span", { class: "nx-hero-glow", "aria-hidden": "true" }),
    p ? el("img", { class: "nx-hero-img", src: cutOf(p.image), alt: `${p.name}, aberto, visto de frente`, width: "768", height: "768" }) : el("span", { class: "nx-hero-img is-empty", "aria-hidden": "true" }),
  );
  return el(
    "section",
    { class: `nx-hero${still ? " is-still" : ""}`, "aria-labelledby": "hero-title" },
    el(
      "div",
      { class: "nx-hero-inner" },
      el("ul", { class: "nx-hero-meta", "aria-label": "Como a NEXA vende" }, ["Tecnologia para o dia a dia", "Entrega a partir de 1 dia útil", "Até 12x sem juros", "Troca em 30 dias"].map((t) => el("li", {}, t))),
      el("h1", { class: "nx-hero-word", "aria-label": "NEXA Store" }, el("span", { class: "nx-hero-w1", "aria-hidden": "true" }, "NEXA", el("i", {}, ".")), el("span", { class: "nx-hero-w2", "aria-hidden": "true" }, "STORE")),
      stage,
      el(
        "div",
        { class: "nx-hero-copy" },
        p
          ? el(
              "div",
              {},
              el("h2", { id: "hero-title", class: "nx-hero-name" }, p.name.replace(/^Notebook\s+/i, "")),
              el("p", { class: "nx-hero-lede" }, "Alumínio, tela de 14 polegadas e bateria para um dia inteiro de trabalho."),
              el("p", { class: "nx-hero-price num" }, el("strong", {}, brl(p.price)), p.oldPrice ? el("s", {}, el("span", { class: "sr-only" }, "Preço anterior: "), brl(p.oldPrice)) : null, el("span", {}, `ou ${installmentText(p.price, p.installments)}`)),
              el(
                "div",
                { class: "nx-hero-ctas" },
                el("button", { type: "button", class: "nx-btn nx-btn-light", onClick: () => addToCart(p.id, stage) }, icon("bag", { size: 18 }), " Adicionar ao carrinho"),
                el("a", { href: `/loja/produto/${p.id}`, class: "nx-btn nx-btn-glass" }, "Ver detalhes"),
              ),
            )
          : el("h2", { id: "hero-title", class: "nx-hero-name" }, cart.status === "error" ? "Vitrine indisponível" : el("span", { class: "nx-skel nx-skel-dark", style: { width: "260px", height: "40px" } })),
      ),
      specs.length
        ? el("dl", { class: "nx-hero-specs" }, specs.map(([k, v]) => el("div", {}, el("dt", {}, k), el("dd", {}, v))))
        : null,
    ),
  );
}

function stories() {
  const list = covers();
  return el(
    "nav",
    { class: "nx-stories nx-wrap", "aria-label": "Categorias" },
    el(
      "ul",
      {},
      list.length
        ? list.map(({ c, cover }, i) =>
            el(
              "li",
              { style: { "--i": String(i) } },
              el(
                "a",
                { href: `/loja?cat=${c.slug}`, class: "nx-story" },
                el("span", { class: "nx-story-ring", "aria-hidden": "true" }, el("span", { class: "nx-story-disc", style: cover ? { "--tint": tintOf(cover.image) } : null }, cover ? el("img", { src: cutOf(cover.image), alt: "", width: "72", height: "72", loading: "lazy" }) : null)),
                el("span", { class: "nx-story-name" }, c.name.split(" ")[0]),
              ),
            ),
          )
        : Array.from({ length: 8 }, () => el("li", { "aria-hidden": "true" }, el("span", { class: "nx-skel", style: { width: "76px", height: "76px", borderRadius: "999px" } }))),
    ),
  );
}

function curated() {
  const cart = cartState();
  const items = CURATED.map((id) => cart.byId.get(id)).filter(Boolean);
  const scroller = el(
    "ol",
    { class: "nx-carousel", "aria-label": "Escolhas da semana" },
    cart.status === "ready" ? items.map((p, i) => el("li", {}, productCard(p, { index: i, size: "lg", onAdd: addToCart }))) : Array.from({ length: 4 }, () => el("li", {}, cardSkeleton("lg"))),
  );
  const prev = el("button", { type: "button", class: "nx-iconbtn nx-iconbtn-line", "aria-label": "Ver anteriores", onClick: () => page(-1) }, icon("back", { size: 18 }));
  const next = el("button", { type: "button", class: "nx-iconbtn nx-iconbtn-line", "aria-label": "Ver próximos", onClick: () => page(1) }, icon("arrow", { size: 18 }));
  const update = () => {
    prev.disabled = scroller.scrollLeft < 8;
    next.disabled = scroller.scrollLeft + scroller.clientWidth >= scroller.scrollWidth - 8;
  };
  scroller.addEventListener("scroll", update, { passive: true });
  addEventListener("resize", update);
  requestAnimationFrame(() => requestAnimationFrame(update));
  function page(dir) {
    const card = scroller.querySelector("li");
    const step = card ? card.getBoundingClientRect().width + 16 : scroller.clientWidth * 0.8;
    scroller.scrollBy({ left: dir * step * (window.innerWidth > 900 ? 2 : 1), behavior: reducedMotion() ? "auto" : "smooth" });
  }
  return el(
    "section",
    { class: "nx-section", "aria-labelledby": "h-curated" },
    el("div", { class: "nx-section-head nx-wrap" }, el("h2", { id: "h-curated", class: "nx-h2" }, "Escolhas da semana"), el("div", { class: "nx-pager" }, prev, next)),
    el("div", { class: "nx-wrap" }, scroller),
  );
}

function promos() {
  const cart = cartState();
  const audio = cart.byId.get(FEATURE_ID);
  const game = cart.byId.get("prod-005");
  if (!audio || !game) return null;
  const a = el("div", { class: "nx-promo-media" }, el("img", { src: cutOf(audio.image), alt: `${audio.name} na cor areia`, width: "768", height: "768", loading: "lazy" }));
  const b = el("div", { class: "nx-promo-media" }, el("img", { src: cutOf(game.image), alt: `${game.name} branco, em pé`, width: "768", height: "768", loading: "lazy" }));
  return el(
    "section",
    { class: "nx-promos nx-wrap", "aria-label": "Ofertas em destaque" },
    promo("h-promo-a", "nx-promo-teal", "Silêncio quando você quiser.", `${audio.name}, com cancelamento ativo de ruído. ${brl(audio.price)} ou ${installmentText(audio.price, audio.installments)}.`, audio, a),
    promo("h-promo-b", "nx-promo-violet", "Console Vertex. Últimas unidades.", `SSD de 1 TB, jogos em 4K e um controle sem fio na caixa. ${brl(game.price)} ou ${installmentText(game.price, game.installments)}.`, game, b),
  );
}

function promo(hid, cls, title, text, product, media) {
  return el(
    "article",
    { class: `nx-promo ${cls}`, "aria-labelledby": hid },
    el(
      "div",
      { class: "nx-promo-copy" },
      el("h2", { id: hid, class: "nx-promo-title" }, title),
      el("p", {}, text),
      el(
        "div",
        { class: "nx-promo-ctas" },
        el("button", { type: "button", class: "nx-btn nx-btn-light", onClick: () => addToCart(product.id, media) }, "Adicionar"),
        el("a", { href: `/loja/produto/${product.id}`, class: "nx-promo-link" }, "Ver detalhes ", icon("arrow", { size: 16 })),
      ),
    ),
    media,
  );
}

function bento() {
  const list = covers();
  if (!list.length) return null;
  return el(
    "section",
    { class: "nx-section nx-wrap", "aria-labelledby": "h-cats" },
    el("div", { class: "nx-section-head" }, el("h2", { id: "h-cats", class: "nx-h2" }, "Explore por categoria")),
    el(
      "ul",
      { class: "nx-bento" },
      list.map(({ c, cover, count }, i) =>
        el(
          "li",
          { class: `nx-bento-${i}` },
          el(
            "a",
            { href: `/loja?cat=${c.slug}`, class: "nx-tile", style: cover ? { "--tint": tintOf(cover.image) } : null },
            el("span", { class: "nx-tile-head" }, el("span", { class: "nx-tile-name" }, c.name), el("span", { class: "nx-tile-n num" }, `${count} ${count === 1 ? "produto" : "produtos"}`)),
            el("span", { class: "nx-tile-go", "aria-hidden": "true" }, icon("arrow", { size: 16 })),
            cover ? el("img", { class: "nx-tile-img", src: cutOf(cover.image), alt: "", width: "768", height: "768", loading: "lazy" }) : null,
          ),
        ),
      ),
    ),
  );
}

function safetyBand() {
  return el(
    "section",
    { class: "nx-safety", "aria-labelledby": "h-safety" },
    el(
      "div",
      { class: "nx-wrap nx-safety-inner" },
      el("h2", { id: "h-safety", class: "nx-display-2" }, "A NEXA nunca pede compras por telefone ou mensagem."),
      el(
        "div",
        { class: "nx-safety-copy" },
        el("p", {}, "Se alguém disse que você precisa comprar algo para “cancelar”, “validar” ou “estornar” uma operação, é golpe. Pare a conversa e fale com o seu banco pelo número que está no verso do cartão."),
        el("p", { class: "nx-safety-small" }, "Toda compra com cartão na NEXA passa pela análise do ContextShield do seu banco, que pode pedir uma confirmação antes de concluir."),
        el("a", { href: "/loja/ajuda#seguranca", class: "nx-link-arrow" }, "Como reconhecer um pedido falso", icon("arrow", { size: 16 })),
      ),
    ),
  );
}

const productCache = new Map();
function productPage(id) {
  const wrap = el("div");
  let qty = 1;
  let added = false;
  const cached = productCache.get(id);
  const draw = (data, error, loading) => {
    if (error) {
      setTitle("Produto · NEXA");
      wrap.replaceChildren(
        el(
          "div",
          { class: "nx-wrap nx-pad" },
          storeError(error.message, { title: error.status === 404 ? "Produto não encontrado" : "O produto não carregou", onRetry: error.status === 404 ? null : () => load() }),
          el("a", { href: "/loja", class: "nx-link-arrow" }, "Voltar à vitrine ", icon("arrow", { size: 16 })),
        ),
      );
      return;
    }
    if (loading && !data) {
      setTitle("Produto · NEXA");
      wrap.replaceChildren(el("div", { class: "nx-pdp-page", "aria-busy": "true", "aria-label": "Carregando produto" }, el("div", { class: "nx-pdp nx-wrap" }, el("div", { class: "nx-plate nx-skel-plate", style: { aspectRatio: "1 / 1" } }))));
      return;
    }
    if (!data) return;
    const p = data.product;
    const s = data.seller;
    const official = s.tier === "oficial";
    setTitle(`${p.name} · NEXA`);
    const related = cartState().products.filter((x) => x.categorySlug === p.categorySlug && x.id !== p.id).slice(0, 4);
    const media = plate(p.image, `${p.name}, ${p.line}`, { eager: true, className: "nx-pdp-plate", children: [
      el("span", { class: "nx-plate-no" }, productNo(p)),
      p.tag ? el("span", { class: "nx-plate-tag" }, p.tag) : null,
      el("span", { class: "nx-plate-foot" }, el("span", {}, p.sku), el("span", {}, data.category.name)),
    ].filter(Boolean) });
    wrap.replaceChildren(
      el(
        "div",
        { class: "nx-pdp-page" },
        el("nav", { class: "nx-crumbs nx-wrap", "aria-label": "Você está em" }, el("ol", {}, el("li", {}, el("a", { href: "/loja" }, "Loja")), el("li", {}, el("a", { href: `/loja?cat=${data.category.slug}` }, data.category.name)), el("li", { "aria-current": "page" }, p.name))),
        el(
          "article",
          { class: "nx-pdp nx-wrap", "aria-labelledby": "pdp-title" },
          el("div", { class: "nx-pdp-media" }, media.box),
          el(
            "div",
            { class: "nx-pdp-info" },
            el("div", { class: "nx-pdp-titleblock" }, el("h1", { id: "pdp-title", class: "nx-h1" }, p.name), el("p", { class: "nx-pdp-line" }, p.line), ratingEl(p)),
            el(
              "div",
              { class: "nx-pdp-buy" },
              priceBlock(p, "xl", false),
              p.installments > 1 ? el("p", { class: "nx-pdp-inst num" }, "em até ", el("strong", {}, installmentText(p.price, p.installments)), " no cartão") : null,
              el("div", { class: "nx-pdp-ship" }, icon("truck", { size: 22 }), el("div", {}, el("strong", {}, `Chega até ${arrivalDate(p.deliveryDays)}`), el("span", {}, `Entrega em até ${p.deliveryDays} ${p.deliveryDays === 1 ? "dia útil" : "dias úteis"} · frete grátis`))),
              el(
                "div",
                { class: "nx-pdp-actions" },
                qtyControl(qty, p.name, (n) => { qty = Math.max(1, n); draw(data, null, false); }, { min: 1 }),
                el(
                  "button",
                  { type: "button", class: `nx-btn nx-btn-accent nx-btn-lg nx-grow nx-morph${added ? " is-done" : ""}`, onClick: () => { addToCart(p.id, media.box, qty); added = true; draw(data, null, false); setTimeout(() => { added = false; draw(data, null, false); }, 1800); } },
                  el("span", { class: "nx-morph-a" }, "Adicionar ao carrinho"),
                  el("span", { class: "nx-morph-b", "aria-hidden": "true" }, icon("check", { size: 18 }), " No carrinho"),
                ),
              ),
              el("button", { type: "button", class: "nx-btn nx-btn-line nx-btn-lg nx-btn-block", onClick: () => { addLine(p.id, qty); go("/loja/carrinho"); } }, "Comprar agora"),
              el("p", { class: `nx-pdp-stock num${p.stock <= 10 ? " is-low" : ""}` }, p.stock <= 10 ? `Restam ${p.stock} unidades` : `${p.stock} unidades em estoque`),
            ),
            el(
              "section",
              { class: `nx-seller${official ? " is-official" : ""}`, "aria-label": "Vendedor" },
              el("span", { class: "nx-seller-badge", "aria-hidden": "true" }, icon("store", { size: 20 })),
              el(
                "div",
                {},
                el("p", {}, "Vendido e entregue por ", el("strong", {}, s.name), official ? el("span", { class: "nx-chip" }, "Loja oficial") : null),
                el("p", { class: "nx-seller-meta num" }, typeof s.rating === "number" ? `Nota ${rating(s.rating)} · ` : "", typeof s.sales === "number" ? `${s.sales.toLocaleString("pt-BR")} vendas · ` : "", s.since ? `na NEXA desde ${new Date(`${s.since}T12:00:00`).getFullYear()}` : ""),
              ),
            ),
            el("p", { class: "nx-pdp-desc" }, p.description),
          ),
        ),
        el(
          "section",
          { class: "nx-section nx-wrap nx-specs-sec", "aria-labelledby": "h-specs" },
          el("div", { class: "nx-section-head" }, el("h2", { id: "h-specs", class: "nx-h2" }, "Ficha técnica")),
          el(
            "table",
            { class: "nx-specs" },
            el(
              "tbody",
              {},
              specsOf(p).map(([k, v]) => el("tr", {}, el("th", { scope: "row" }, k), el("td", {}, v))),
              el("tr", {}, el("th", { scope: "row" }, "Código"), el("td", { class: "nx-mono" }, p.sku)),
              el("tr", {}, el("th", { scope: "row" }, "Parcelamento"), el("td", { class: "num" }, p.installments > 1 ? `Até ${p.installments}x sem juros` : "À vista")),
              el("tr", {}, el("th", { scope: "row" }, "Vendedor"), el("td", {}, s.name)),
              el("tr", {}, el("th", { scope: "row" }, "Preço"), el("td", { class: "num" }, brl(p.price))),
            ),
          ),
        ),
        related.length
          ? el(
              "section",
              { class: "nx-section nx-wrap", "aria-labelledby": "h-related" },
              el("div", { class: "nx-section-head" }, el("h2", { id: "h-related", class: "nx-h2" }, `Mais em ${data.category.name}`), el("a", { href: `/loja?cat=${data.category.slug}`, class: "nx-link-arrow" }, "Ver tudo ", icon("arrow", { size: 16 }))),
              el("div", { class: "nx-grid" }, related.map((r, i) => productCard(r, { index: i, onAdd: addToCart }))),
            )
          : null,
      ),
    );
  };
  async function load() {
    draw(cached, null, !cached);
    try {
      const data = await api(`/api/catalog/products/${id}`);
      productCache.set(id, data);
      draw(data, null, false);
      window.scrollTo({ top: 0 });
    } catch (e) {
      draw(null, e, false);
    }
  }
  load();
  return wrap;
}

async function checkoutPage(search) {
  if (!checkout) checkout = createCheckout(session, go);
  setTitle(checkout.title(search));
  const node = el("div", { "aria-busy": "true" });
  const gen = renderGen;
  checkout.prepare().then(() => {
    if (gen !== renderGen) return;
    if (location.pathname.replace(/\/$/, "") !== "/loja/carrinho") return;
    main.replaceChildren(checkout.view(search, openCart));
  });
  return checkout.view(search, openCart);
}

let orderId = "";
function orderPage(id, state) {
  if (orderId !== id) {
    orderId = id;
  }
  const view = orderView({ id, session, state });
  return view.root;
}

function loginPage(search) {
  setTitle("Entrar · NEXA");
  const next = search.get("next") || "/loja/carrinho";
  return el(
    "div",
    { class: "nx-auth nx-wrap" },
    el(
      "div",
      { class: "nx-auth-card" },
      loginForm({
        area: "customer",
        heading: el("h1", { class: "nx-h2" }, "Entrar na ", wordmark("nx-wordmark-inline")),
        intro: "Use a mesma conta do app Aureon. O cartão da conta já aparece no pagamento.",
        suggestions: [
          { label: "Ana", email: "ana.martins@correio.example" },
          { label: "Mariana", email: "mariana.rocha@correio.example" },
          { label: "Beatriz", email: "beatriz.lima@correio.example" },
          { label: "Carlos", email: "carlos.menezes@correio.example" },
        ],
        onDone: () => go(next.startsWith("/") ? next : "/loja/carrinho"),
      }),
    ),
    el(
      "aside",
      { class: "nx-auth-side" },
      el("h2", { class: "nx-h3" }, "Por que pedimos para entrar"),
      el(
        "ul",
        {},
        el("li", {}, icon("card", { size: 20 }), "O cartão salvo na sua conta aparece no pagamento, sem digitar de novo."),
        el("li", {}, icon("shield", { size: 20 }), "O seu banco reconhece que é você quem está comprando."),
        el("li", {}, icon("receipt", { size: 20 }), "Os pedidos ficam guardados em “Seus pedidos”."),
      ),
    ),
  );
}

function ordersPage() {
  setTitle("Seus pedidos · NEXA");
  const wrap = el("div");
  const s = session();
  if (!s) {
    wrap.append(
      el(
        "div",
        { class: "nx-wrap nx-pad" },
        el(
          "div",
          { class: "nx-co-login" },
          icon("user", { size: 24 }),
          el("div", {}, el("strong", {}, "Entre para ver os seus pedidos"), el("p", {}, "Os pedidos ficam guardados na sua conta.")),
          el("a", { href: "/loja/entrar?next=/loja/pedidos", class: "nx-btn nx-btn-ink" }, "Entrar"),
        ),
      ),
    );
    return wrap;
  }
  wrap.append(el("div", { class: "nx-wrap nx-orders", "aria-busy": "true" }, el("h1", { class: "nx-h1" }, "Seus pedidos")));
  api("/api/transactions", { area: "customer" })
    .then((data) => {
      wrap.replaceChildren(
        el(
          "div",
          { class: "nx-wrap nx-orders" },
          el(
            "header",
            { class: "nx-orders-head" },
            el("div", {}, el("h1", { class: "nx-h1" }, "Seus pedidos"), el("p", { class: "nx-listhead-sub" }, `${s.user.name} · ${s.user.email}`)),
            el("button", { type: "button", class: "nx-btn nx-btn-line", onClick: () => { setSession("customer", null); go("/loja"); } }, icon("out", { size: 18 }), " Sair da conta"),
          ),
          !data?.length
            ? el("div", { class: "nx-empty" }, icon("receipt", { size: 28 }), el("h2", { class: "nx-h3" }, "Nenhum pedido ainda"), el("p", {}, "Quando você comprar, o pedido aparece aqui com o estado do pagamento."), el("a", { href: "/loja", class: "nx-btn nx-btn-ink" }, "Ver a vitrine"))
            : el(
                "ul",
                { class: "nx-orderlist" },
                data.map((t) =>
                  el(
                    "li",
                    {},
                    el(
                      "a",
                      { href: `/loja/pedido/${t.id}`, class: "nx-orderrow" },
                      el("span", { class: "nx-orderrow-main" }, el("strong", {}, t.description), el("span", { class: "nx-mono-meta num" }, `${t.id} · ${dateTime(t.createdAt)}`)),
                      statusPill(t.status, { customer: true }),
                      el("span", { class: "nx-orderrow-amt num" }, brl(t.amount)),
                      icon("chevron", { size: 18 }),
                    ),
                  ),
                ),
              ),
        ),
      );
    })
    .catch((e) => wrap.replaceChildren(el("div", { class: "nx-wrap nx-pad" }, storeError(e.message, { title: "Os pedidos não carregaram", onRetry: () => main.replaceChildren(ordersPage()) }))));
  return wrap;
}

function helpPage(hash) {
  setTitle("Ajuda · NEXA");
  queueMicrotask(() => {
    const id = hash?.slice(1);
    const target = id ? document.getElementById(id) : null;
    if (target) target.scrollIntoView({ behavior: reducedMotion() ? "auto" : "smooth", block: "start" });
  });
  return el(
    "div",
    { class: "nx-help nx-wrap" },
    el("header", { class: "nx-help-head" }, el("h1", { class: "nx-h1" }, "Como podemos ajudar?"), el("p", { class: "nx-listhead-sub" }, "Entrega, trocas, pagamento e, antes de tudo, como comprar com segurança.")),
    el(
      "div",
      { class: "nx-help-body" },
      el("nav", { class: "nx-help-nav", "aria-label": "Assuntos da ajuda" }, el("ul", {}, HELP_NAV.map(([id, label, ic]) => el("li", {}, el("a", { href: `/loja/ajuda#${id}`, "aria-current": hash === `#${id}` ? "location" : null }, icon(ic, { size: 18 }), label))))),
      el(
        "div",
        { class: "nx-help-main" },
        el(
          "section",
          { id: "seguranca", class: "nx-help-sec nx-help-safe", "aria-labelledby": "h-seg" },
          el("h2", { id: "h-seg", class: "nx-h2" }, "Como reconhecer um pedido falso"),
          el("p", {}, "O golpe mais comum hoje não invade a sua conta: ele convence você a comprar. Se qualquer um destes sinais aparecer, pare a conversa."),
          el("ol", { class: "nx-signs" }, SIGNS.map((s, i) => el("li", {}, el("span", { class: "nx-next-n num", "aria-hidden": "true" }, String(i + 1)), el("span", {}, el("strong", {}, s[0]), el("span", {}, s[1]))))),
          el("p", { class: "nx-help-call" }, icon("phoneCall", { size: 20 }), el("span", {}, "Central do banco Aureon, 24 horas: ", el("strong", { class: "num" }, "0800 000 0000"), ". Se você já fez a compra, registre em ", el("a", { href: "/banco/relatar" }, "“Fui vítima”"), " no app.")),
        ),
        el("section", { id: "entrega", class: "nx-help-sec", "aria-labelledby": "h-ent" }, el("h2", { id: "h-ent", class: "nx-h3" }, "Entrega"), el("p", {}, "O prazo aparece na página de cada produto e conta só dias úteis. O frete é grátis em toda a loja. Você pode receber em casa, no trabalho ou num ponto de retirada.")),
        el("section", { id: "trocas", class: "nx-help-sec", "aria-labelledby": "h-tro" }, el("h2", { id: "h-tro", class: "nx-h3" }, "Trocas e devoluções"), el("p", {}, "Você tem 30 dias para trocar ou devolver, sem custo. Abra o pedido em “Seus pedidos” e escolha o motivo.")),
        el("section", { id: "pagamento", class: "nx-help-sec", "aria-labelledby": "h-pag" }, el("h2", { id: "h-pag", class: "nx-h3" }, "Pagamento"), el("p", {}, "Aceitamos cartão de crédito em até 12x sem juros. Toda compra passa pela análise do ContextShield do seu banco, que pode pedir uma confirmação rápida antes de concluir. Isso não muda o preço.")),
      ),
    ),
  );
}

function mount() {
  root.append(el("a", { class: "skip-link", href: "#conteudo" }, "Pular para o conteúdo"), header, main, footer, drawer, live);
  document.body.append(root);
  drawer.addEventListener("click", (e) => {
    if (e.target === drawer) closeCart();
  });
  drawer.addEventListener("close", () => {});
  window.addEventListener("scroll", () => header.classList.toggle("is-scrolled", window.scrollY > 4), { passive: true });
  window.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && menu) {
      menu = false;
      paintHeader(location.pathname);
    }
    if (e.key !== "/" || e.metaKey || e.ctrlKey || e.altKey) return;
    const t = e.target;
    if (t.closest?.("input, textarea, select, [contenteditable='true'], dialog[open]")) return;
    const input = document.querySelector(".nx-search-input");
    if (!input || input.offsetParent === null) return;
    e.preventDefault();
    input.focus();
    input.select();
  });
  window.addEventListener("pointerdown", (e) => {
    if (!menu) return;
    if (e.target.closest?.("#nx-catalog, .nx-catbtn")) return;
    menu = false;
    paintHeader(location.pathname);
  });
  router = createRouter({
    base: "/loja",
    routes: [
      { name: "home", pattern: /^$/ },
      { name: "product", pattern: /^produto\/(?<id>[^/]+)$/ },
      { name: "cart", pattern: /^carrinho$/ },
      { name: "order", pattern: /^pedido\/(?<id>[^/]+)$/ },
      { name: "login", pattern: /^entrar$/ },
      { name: "orders", pattern: /^pedidos$/ },
      { name: "help", pattern: /^ajuda$/ },
    ],
    render,
  });
  let catalogStamp = "";
  onCart(() => {
    paintBadge(true);
    paintDrawer();
    const path = location.pathname.replace(/\/$/, "");
    const cart = cartState();
    const stamp = `${cart.status}:${cart.products.length}`;
    const catalogChanged = stamp !== catalogStamp;
    catalogStamp = stamp;
    if (path === "/loja/carrinho" || ((path === "/loja" || path.startsWith("/loja/produto")) && catalogChanged)) router.start();
  });
  onSession(() => router.start());
  api("/api/catalog/categories").then((c) => {
    cats = c;
    paintHeader(location.pathname);
    paintFooter();
    if (location.pathname.replace(/\/$/, "") === "/loja") router.start();
  }).catch(() => {});
  loadProducts();
  router.start();
}

mount();
