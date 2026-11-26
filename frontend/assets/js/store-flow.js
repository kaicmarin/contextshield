import { api, login } from "./api/client.js";
import { demoPasswordFor } from "./demo-seed-passwords.js";
import { addLine, cartState, clearCart, replaceLines } from "./cart.js";
import { arrivalDate } from "./catalog.js";
import { plate, qtyControl, storeError } from "./store-parts.js";
import { nxField } from "./ui.js";
import { cardErrors, emptyCard, maskCard, maskCep, maskCvv, maskExp } from "./utils/card.js";
import { el, reducedMotion } from "./utils/dom.js";
import { brl, dateTime, setTitle, STATUS } from "./utils/format.js";
import { icon, mark } from "./utils/icons.js";
import { statusPill } from "./ui.js";

export const DEMO_EMAIL = {
  "CUST-001": "ana.martins@correio.example",
  "CUST-002": "carlos.menezes@correio.example",
  "CUST-003": "mariana.rocha@correio.example",
  "CUST-005": "beatriz.lima@correio.example",
};

export const DEMO_OWNER = {
  "CUST-001": { name: "Ana", of: "da Ana" },
  "CUST-002": { name: "Carlos", of: "do Carlos" },
  "CUST-003": { name: "Mariana", of: "da Mariana" },
  "CUST-005": { name: "Beatriz", of: "da Beatriz" },
};

const STEPS = [
  { id: "entrega", label: "Entrega" },
  { id: "pagamento", label: "Pagamento" },
  { id: "revisao", label: "Revisão" },
];
const ACTION = { entrega: "Continuar para pagamento", pagamento: "Revisar pedido", revisao: "Pagar" };
const ADDR_ICON = { home: "home", work: "store", pickup: "pin" };
const REPLIES = { induction: { 0: "Não fiz compra nenhuma. O que eu faço?", 2: "Tá bom, estou abrindo a NEXA." } };
const HEAD = {
  APPROVED: { title: "Pedido confirmado", text: "Tudo certo com o pagamento. Você recebe as atualizações da entrega por e-mail." },
  APPROVED_WITH_ALERT: {
    title: "Pedido confirmado, com um aviso",
    text: "Aprovamos a compra, mas alguns detalhes fogem do seu padrão. Se alguém pediu esta compra por telefone ou mensagem, fale com a central oficial antes de qualquer outro passo: 0800 000 0000.",
  },
  CONTEXT_REQUIRED: { title: "Só mais um passo", text: "Antes de concluir, o seu banco quer confirmar alguns detalhes. Leva menos de um minuto." },
  INTERVENTION: {
    title: "Pausamos esta compra para sua proteção",
    text: "O que vimos aqui se parece com golpes em que alguém guia a pessoa a comprar. Ninguém do banco ou da loja pede compras para “cancelar” ou “validar” nada.",
  },
  BLOCKED: {
    title: "Compra interrompida",
    text: "O banco interrompeu este pagamento. Nenhum valor foi cobrado.",
  },
  CANCELLED: { title: "Compra cancelada", text: "Nenhum valor foi cobrado. Se alguém orientou você a fazer esta compra, conte para o seu banco." },
  INCIDENT_RECORDED: { title: "Golpe relatado", text: "Recebemos o seu relato. A equipe do banco está acompanhando." },
  ANALYZING: {
    title: "Ainda estamos analisando",
    text: "O serviço de análise não respondeu a tempo. Por segurança, a compra não foi aprovada sem análise. Tente de novo em instantes.",
  },
};
const GLYPH = {
  APPROVED: "m7.4 12.4 3.2 3.2 6-6.6",
  APPROVED_WITH_ALERT: "M12 7.4v5.4M12 16.3v.01",
  CONTEXT_REQUIRED: "M9.9 9.7a2.2 2.2 0 1 1 3 2c-.5.3-.9.7-.9 1.3v.4M12 16.3v.01",
  INTERVENTION: "M10 8.4v7.2M14 8.4v7.2",
  BLOCKED: "m4.6 4.6 14.8 14.8",
  CANCELLED: "m8.6 8.6 6.8 6.8M15.4 8.6l-6.8 6.8",
  INCIDENT_RECORDED: "M9.4 16.8V7.4M9.4 7.8h6l-1.4 2.3 1.4 2.3h-6",
  ANALYZING: "M12 7.8V12l2.8 1.8",
  RECEIVED: "M12 7.8V12l2.8 1.8",
};

const blankAddr = () => ({ line1: "", line2: "", district: "", city: "São Paulo", zip: "", ownership: "mine" });

function planText(total, n) {
  const count = Number(n) || 1;
  return count > 1 ? `${count}x de ${brl(total / count)} sem juros` : `1x de ${brl(total)} (à vista)`;
}

export function createCheckout(session, go) {
  const model = {
    addressId: "",
    newAddr: blankAddr(),
    origin: "",
    deviceId: "",
    coachScript: "",
    scenario: null,
    card: emptyCard(),
    cardFocus: null,
    installments: 1,
    touched: { entrega: false, pagamento: false, revisao: false },
    dir: "fwd",
    me: null,
    meError: null,
    meLoading: false,
    options: null,
    pickups: [],
    scenarios: null,
    scenariosError: null,
    submitError: null,
    submitBusy: false,
    loadBusy: false,
    loadError: null,
    firstStep: true,
  };
  let token = session?.token ?? null;

  async function loadMe() {
    const s = session();
    if (!s) {
      model.me = null;
      model.meLoading = false;
      return;
    }
    if (model.me && token === s.token) return;
    token = s.token;
    model.meLoading = true;
    model.meError = null;
    try {
      model.me = await api("/api/me", { area: "customer" });
      if (!model.addressId) model.addressId = model.me.addresses.find((a) => a.kind === "home")?.id ?? model.me.addresses[0]?.id ?? "";
    } catch (e) {
      model.meError = e;
      model.me = null;
    } finally {
      model.meLoading = false;
    }
  }

  async function loadRefs() {
    try {
      const [options, pickups, scenarios] = await Promise.all([
        api("/api/catalog/options"),
        api("/api/catalog/pickup-points"),
        api("/api/catalog/scenarios"),
      ]);
      model.options = options;
      model.pickups = pickups;
      model.scenarios = scenarios;
      model.scenariosError = null;
    } catch (e) {
      model.scenariosError = e;
    }
  }

  function stepOf(search) {
    return STEPS.find((s) => s.id === search.get("etapa"))?.id ?? "entrega";
  }

  function addresses() {
    return [...(model.me?.addresses ?? []), ...model.pickups];
  }

  function deliveryOk() {
    return !!session() && !!model.addressId && (model.addressId !== "new" || (!!model.newAddr.line1.trim() && !!model.newAddr.city.trim()));
  }
  function paymentOk() {
    const saved = model.me?.cards[0];
    return model.card.mode === "saved" ? !!saved : Object.keys(cardErrors(model.card)).length === 0;
  }
  function reachable(s) {
    return s === "entrega" || (s === "pagamento" && deliveryOk()) || (s === "revisao" && deliveryOk() && paymentOk());
  }

  function goto(search, s, replace) {
    const idx = STEPS.findIndex((x) => x.id === stepOf(search));
    model.dir = STEPS.findIndex((x) => x.id === s) >= idx ? "fwd" : "back";
    const next = new URLSearchParams(search);
    next.set("etapa", s);
    go(`/loja/carrinho?${next}`, { replace });
  }

  async function pay() {
    const cart = cartState();
    const body = {
      items: cart.lines,
      declaredOrigin: model.origin,
      deviceId: model.deviceId || undefined,
      coachScript: model.coachScript || undefined,
      scenario: model.scenario?.id,
    };
    if (model.addressId === "new") {
      const a = model.newAddr;
      body.newAddress = { ...a, line2: a.line2 || undefined, district: a.district || undefined, zip: a.zip || undefined };
    } else body.addressId = model.addressId;
    model.submitBusy = true;
    model.submitError = null;
    try {
      const tx = await api("/api/transactions", { method: "POST", body, area: "customer" });
      const scenarioId = model.scenario?.id ?? null;
      clearCart();
      Object.assign(model, { scenario: null, coachScript: "", origin: "", deviceId: "" });
      go(`/loja/pedido/${tx.id}`, { state: { scenario: scenarioId } });
    } catch (e) {
      model.submitError = e;
      model.submitBusy = false;
    }
  }

  async function loadScenario(s, search) {
    model.loadBusy = true;
    model.loadError = null;
    try {
      model.addressId = s.addressId;
      model.deviceId = s.deviceId;
      model.origin = s.declaredOrigin;
      model.coachScript = (s.outside?.messages ?? []).map((m) => m.text).join("\n");
      model.card = emptyCard();
      model.installments = 1;
      model.touched = { entrega: false, pagamento: false, revisao: false };
      model.scenario = s;
      const current = session();
      if (current?.user.customerId !== s.customerId) {
        const email = DEMO_EMAIL[s.customerId];
        const pw = demoPasswordFor(email);
        if (!pw) throw new Error("Senha de demonstração não configurada para " + email);
        await login("customer", email, pw);
      }
      replaceLines(s.cart.map((l) => ({ ...l })));
      model.loadBusy = false;
      await loadMe();
      goto(search, "revisao", true);
    } catch (e) {
      model.loadError = e;
      model.loadBusy = false;
    }
  }

  function view(search, openCart) {
    const cart = cartState();
    const step = stepOf(search);
    const stepIdx = STEPS.findIndex((s) => s.id === step);
    const cap = cart.maxInstallments;
    if (model.installments > cap) model.installments = cap;
    if (cart.lines.length === 0) {
      return el(
        "div",
        { class: "nx-wrap nx-co-empty" },
        el(
          "div",
          { class: "nx-empty nx-empty-lg" },
          el("span", { class: "nx-empty-mark", "aria-hidden": "true" }, icon("bag", { size: 30 })),
          el("h1", { class: "nx-h2" }, "Seu carrinho está vazio"),
          el("p", {}, "Escolha um produto na vitrine ou carregue um cenário da apresentação."),
          el("a", { href: "/loja", class: "nx-btn nx-btn-ink nx-btn-lg" }, "Ver a vitrine"),
        ),
        demoPanel(search),
      );
    }

    const total = cart.total;
    const errStep = model.touched[step];
    const label = step === "revisao" ? `${ACTION.revisao} ${brl(total)}` : ACTION[step];
    const busy = model.submitBusy || model.loadBusy;
    const s = session();
    const savedCard = model.me?.cards[0];
    const address = model.addressId === "new" ? { id: "new", label: "Outro endereço", kind: "other", ...model.newAddr } : addresses().find((a) => a.id === model.addressId);
    const owner = model.scenario ? DEMO_OWNER[model.scenario.customerId] ?? { name: "a cliente", of: "da cliente" } : null;

    function onSubmit(e) {
      e.preventDefault();
      if (step === "entrega") {
        model.touched.entrega = true;
        if (deliveryOk()) goto(search, "pagamento");
        else viewRefresh();
      } else if (step === "pagamento") {
        model.touched.pagamento = true;
        if (paymentOk()) goto(search, "revisao");
        else viewRefresh();
      } else {
        model.touched.revisao = true;
        if (!deliveryOk()) return goto(search, "entrega");
        if (!paymentOk()) return goto(search, "pagamento");
        if (model.origin) pay().then(viewRefresh);
        else viewRefresh();
      }
    }

    const form = el(
      "form",
      { class: `nx-co nx-wrap${model.scenario?.outside ? " has-phone" : ""}`, onSubmit, novalidate: true },
      el(
        "div",
        { class: "nx-co-main" },
        el(
          "div",
          { class: "nx-co-top", id: "nx-co-top" },
          el("h1", { class: "nx-h1 nx-co-title" }, "Finalizar compra"),
          stepper(step, search),
        ),
        el(
          "div",
          { class: "nx-co-step", "data-dir": model.dir },
          el(
            "h2",
            { tabindex: "-1", class: "nx-h3 nx-co-steptitle", id: "nx-step-title" },
            step === "entrega" ? "Onde entregar?" : step === "pagamento" ? "Como você quer pagar?" : "Revise e confirme",
          ),
          !s
            ? loginGate("/loja/carrinho")
            : model.meError
              ? storeError(model.meError.message, { title: "Seus dados não carregaram", onRetry: () => loadMe().then(viewRefresh) })
              : model.meLoading || !model.me
                ? el(
                    "div",
                    { class: "nx-opts", "aria-busy": "true", "aria-label": "Carregando seus dados" },
                    [0, 1, 2].map(() => el("span", { class: "nx-skel", style: { height: "76px" } })),
                  )
                : step === "entrega"
                  ? deliveryStep(search, errStep)
                  : step === "pagamento"
                    ? payStep(savedCard, total, cart, errStep)
                    : reviewStep(address, cart, savedCard, total, search, errStep),
        ),
      ),
      el(
        "aside",
        { class: "nx-co-side", "aria-label": "Resumo do pedido" },
        summaryCard(cart, total, label, busy, s, openCart),
        demoPanel(search),
      ),
      model.scenario?.outside && owner
        ? el("div", { class: "nx-co-phone" }, phoneChat(model.scenario.id, model.scenario.outside, owner.name, owner.of))
        : null,
    );
    if (!model.firstStep) queueMicrotask(() => document.getElementById("nx-step-title")?.focus({ preventScroll: true }));
    model.firstStep = false;
    return form;

    function viewRefresh() {
      go(location.pathname + location.search, { replace: true });
    }
  }

  function stepper(step, search) {
    const idx = STEPS.findIndex((s) => s.id === step);
    return el(
      "nav",
      { class: "nx-stepper", "aria-label": "Etapas da compra" },
      el(
        "ol",
        { style: { "--p": String(idx / (STEPS.length - 1)) } },
        STEPS.map((s, i) => {
          const state = i < idx ? "done" : i === idx ? "current" : "todo";
          return el(
            "li",
            { "data-state": state },
            el(
              "button",
              {
                type: "button",
                disabled: i !== idx && !reachable(s.id),
                "aria-current": i === idx ? "step" : null,
                onClick: () => goto(search, s.id),
              },
              el(
                "span",
                { class: "nx-step-n num", "aria-hidden": "true" },
                state === "done" ? icon("check", { size: 14, stroke: 2 }) : String(i + 1).padStart(2, "0"),
              ),
              el("span", { class: "nx-step-l" }, s.label, state === "done" ? el("span", { class: "sr-only" }, " (concluída)") : null),
            ),
          );
        }),
      ),
      el(
        "span",
        { class: "nx-stepper-track", "aria-hidden": "true" },
        el("span", { style: { transform: `scaleX(${idx / (STEPS.length - 1)})` } }),
      ),
    );
  }

  function deliveryStep(search, showErrors) {
    const days = cartState().deliveryDays || 1;
    const arrival = arrivalDate(days);
    const list = addresses();
    return el(
      "div",
      { class: "nx-stepbody" },
      el(
        "div",
        { class: "nx-opts", role: "radiogroup", "aria-label": "Endereço de entrega" },
        list.map((a) => addrOption(a, arrival)),
        el(
          "label",
          { class: "nx-opt" },
          el("input", { type: "radio", name: "address", value: "new", checked: model.addressId === "new", onChange: () => { model.addressId = "new"; go(location.pathname + location.search, { replace: true }); } }),
          el("span", { class: "nx-opt-dot", "aria-hidden": "true" }),
          el("span", { class: "nx-opt-body" }, el("strong", {}, "Outro endereço"), el("span", {}, "Entregar num endereço que não está na sua conta")),
          el("span", { class: "nx-opt-side" }, icon("plus", { size: 20 })),
        ),
      ),
      showErrors && !model.addressId ? el("p", { class: "nx-field-err" }, "Escolha onde entregar.") : null,
      model.addressId === "new" ? newAddressForm(showErrors) : null,
    );
    function addrOption(a, arrivalText) {
      return el(
        "label",
        { class: "nx-opt" },
        el("input", {
          type: "radio",
          name: "address",
          value: a.id,
          checked: model.addressId === a.id,
          onChange: () => {
            model.addressId = a.id;
            go(location.pathname + location.search, { replace: true });
          },
        }),
        el("span", { class: "nx-opt-dot", "aria-hidden": "true" }),
        el(
          "span",
          { class: "nx-opt-body" },
          el("strong", {}, a.label),
          el("span", {}, `${a.line1}${a.line2 ? `, ${a.line2}` : ""}${a.district ? ` · ${a.district}` : ""} · ${a.city}`),
          a.zip ? el("span", { class: "nx-opt-meta num" }, `CEP ${a.zip}`) : null,
        ),
        el("span", { class: "nx-opt-side" }, icon(ADDR_ICON[a.kind] ?? "pin", { size: 20 }), el("span", { class: "num" }, `Chega até ${arrivalText}`)),
      );
    }
  }

  function newAddressForm(showErrors) {
    const a = model.newAddr;
    const redraw = () => go(location.pathname + location.search, { replace: true });
    const set = (k, v) => {
      model.newAddr = { ...model.newAddr, [k]: v };
    };
    return el(
      "div",
      { class: "nx-addr-form" },
      nxField({
        label: "Rua e número",
        className: "nx-span-2",
        error: showErrors && !a.line1.trim() ? "Informe a rua e o número." : null,
        control: (p) => el("input", { ...p, class: "nx-input", autocomplete: "address-line1", value: a.line1, onInput: (e) => set("line1", e.target.value) }),
      }),
      nxField({
        label: "Complemento",
        hint: "Opcional",
        control: (p) => el("input", { ...p, class: "nx-input", autocomplete: "address-line2", value: a.line2, onInput: (e) => set("line2", e.target.value) }),
      }),
      nxField({
        label: "CEP",
        hint: "Opcional",
        control: (p) =>
          el("input", {
            ...p,
            class: "nx-input num",
            inputmode: "numeric",
            autocomplete: "postal-code",
            placeholder: "00000-000",
            value: a.zip,
            onInput: (e) => {
              set("zip", maskCep(e.target.value));
              e.target.value = model.newAddr.zip;
            },
          }),
      }),
      nxField({
        label: "Bairro",
        control: (p) => el("input", { ...p, class: "nx-input", value: a.district, onInput: (e) => set("district", e.target.value) }),
      }),
      nxField({
        label: "Cidade",
        error: showErrors && !a.city.trim() ? "Informe a cidade." : null,
        control: (p) => el("input", { ...p, class: "nx-input", autocomplete: "address-level2", value: a.city, onInput: (e) => set("city", e.target.value) }),
      }),
      el(
        "label",
        { class: "nx-check nx-span-2" },
        el("input", {
          type: "checkbox",
          checked: a.ownership === "other",
          onChange: (e) => {
            set("ownership", e.target.checked ? "other" : "mine");
            redraw();
          },
        }),
        el("span", { class: "nx-check-box", "aria-hidden": "true" }, icon("check", { size: 14, stroke: 2.2 })),
        "Este endereço é de outra pessoa",
      ),
    );
  }

  function payStep(savedCard, total, cart, errStep) {
    const errs = errStep ? cardErrors(model.card) : {};
    const redraw = () => go(location.pathname + location.search, { replace: true });
    return el(
      "div",
      { class: "nx-pay" },
      el(
        "div",
        { class: "nx-opts", role: "radiogroup", "aria-label": "Cartão" },
        savedCard
          ? el(
              "label",
              { class: "nx-opt" },
              el("input", { type: "radio", name: "pay", checked: model.card.mode === "saved", onChange: () => { model.card = { ...model.card, mode: "saved" }; redraw(); } }),
              el("span", { class: "nx-opt-dot", "aria-hidden": "true" }),
              el(
                "span",
                { class: "nx-opt-body" },
                el("strong", {}, savedCard.product, " final ", el("span", { class: "num" }, savedCard.last4)),
                el("span", {}, savedCard.kind, " · vence em ", el("span", { class: "num" }, savedCard.expiry), " · cartão salvo na sua conta"),
              ),
              icon("card", { size: 22 }),
            )
          : null,
        el(
          "label",
          { class: "nx-opt" },
          el("input", { type: "radio", name: "pay", checked: model.card.mode === "new", onChange: () => { model.card = { ...model.card, mode: "new" }; redraw(); } }),
          el("span", { class: "nx-opt-dot", "aria-hidden": "true" }),
          el("span", { class: "nx-opt-body" }, el("strong", {}, "Novo cartão de crédito"), el("span", {}, "Digite os dados do cartão")),
          icon("plus", { size: 22 }),
        ),
      ),
      el(
        "div",
        { class: `nx-pay-card${model.card.mode === "new" ? " is-new" : ""}` },
        model.card.mode === "saved" && savedCard
          ? cardPreview({ saved: true, issuer: savedCard.product, number: `•••• •••• •••• ${savedCard.last4}`, name: savedCard.holder, exp: savedCard.expiry })
          : cardPreview({ issuer: "Cartão de crédito", number: model.card.number, name: model.card.name, exp: model.card.exp, cvv: model.card.cvv, focus: model.cardFocus }),
        model.card.mode === "new"
          ? el(
              "div",
              { class: "nx-pay-fields" },
              cardFields(errs, redraw),
              el("p", { class: "nx-note" }, icon("lock", { size: 16 }), "Na demonstração, a compra é paga com o cartão Aureon da conta. O que você digita aqui não sai do navegador."),
            )
          : null,
      ),
      nxField({
        label: "Parcelas",
        control: (p) =>
          el(
            "select",
            {
              ...p,
              class: "nx-select nx-select-lg num",
              value: String(model.installments),
              onChange: (e) => {
                model.installments = Number(e.target.value);
                redraw();
              },
            },
            Array.from({ length: cart.maxInstallments }, (_, i) => i + 1).map((n) =>
              el("option", { value: String(n), selected: n === model.installments }, planText(total, n)),
            ),
          ),
      }),
    );
  }

  function cardFields(errs, redraw) {
    const set = (patch) => {
      model.card = { ...model.card, ...patch };
    };
    const focus = (part) => {
      model.cardFocus = part;
      const fx = document.querySelector(".nx-cardfx");
      if (!fx) return;
      fx.classList.toggle("is-flipped", part === "cvv");
      if (part) fx.dataset.focus = part;
      else fx.removeAttribute("data-focus");
    };
    return el(
      "div",
      { class: "nx-cardfields" },
      nxField({
        label: "Número do cartão",
        error: errs.number,
        className: "nx-span-2",
        control: (p) =>
          el("input", {
            ...p,
            class: "nx-input num",
            inputmode: "numeric",
            autocomplete: "cc-number",
            name: "cc-number",
            placeholder: "0000 0000 0000 0000",
            value: model.card.number,
            onInput: (e) => {
              set({ number: maskCard(e.target.value) });
              e.target.value = model.card.number;
              const num = document.querySelector('[data-part="number"]');
              if (num) num.textContent = padNumber(model.card.number);
            },
            onFocus: () => focus("number"),
            onBlur: () => focus(null),
          }),
      }),
      nxField({
        label: "Nome impresso no cartão",
        error: errs.name,
        className: "nx-span-2",
        control: (p) =>
          el("input", {
            ...p,
            class: "nx-input",
            autocomplete: "cc-name",
            name: "cc-name",
            autocapitalize: "characters",
            spellcheck: "false",
            placeholder: "Como aparece no cartão",
            value: model.card.name,
            onInput: (e) => {
              set({ name: e.target.value.slice(0, 26) });
              const node = document.querySelector('[data-part="name"] .nx-cardfx-name');
              if (node) node.textContent = model.card.name.trim() ? model.card.name.toUpperCase() : "NOME NO CARTÃO";
            },
            onFocus: () => focus("name"),
            onBlur: () => focus(null),
          }),
      }),
      nxField({
        label: "Validade",
        error: errs.exp,
        control: (p) =>
          el("input", {
            ...p,
            class: "nx-input num",
            inputmode: "numeric",
            autocomplete: "cc-exp",
            name: "cc-exp",
            placeholder: "MM/AA",
            value: model.card.exp,
            onInput: (e) => {
              set({ exp: maskExp(e.target.value) });
              e.target.value = model.card.exp;
              const node = document.querySelector('[data-part="exp"] .num');
              if (node) node.textContent = model.card.exp || "MM/AA";
            },
            onFocus: () => focus("exp"),
            onBlur: () => focus(null),
          }),
      }),
      nxField({
        label: "Código de segurança",
        error: errs.cvv,
        hint: "3 ou 4 dígitos, no verso",
        control: (p) =>
          el("input", {
            ...p,
            class: "nx-input num",
            inputmode: "numeric",
            autocomplete: "cc-csc",
            name: "cc-csc",
            placeholder: "000",
            value: model.card.cvv,
            onInput: (e) => {
              set({ cvv: maskCvv(e.target.value) });
              e.target.value = model.card.cvv;
              const node = document.querySelector('[data-part="cvv"]');
              if (node) node.textContent = model.card.cvv ? "•".repeat(model.card.cvv.length) : "CVV";
            },
            onFocus: () => focus("cvv"),
            onBlur: () => focus(null),
          }),
      }),
    );
  }

  function reviewStep(address, cart, savedCard, total, search, showErrors) {
    const cardLabel =
      model.card.mode === "saved" && savedCard
        ? `${savedCard.product} final ${savedCard.last4}`
        : `Cartão final ${model.card.number.replace(/\D/g, "").slice(-4) || "—"}`;
    const redraw = () => go(location.pathname + location.search, { replace: true });
    return el(
      "div",
      { class: "nx-stepbody" },
      el(
        "dl",
        { class: "nx-review" },
        el(
          "div",
          {},
          el("dt", {}, icon("truck", { size: 20 }), "Entrega"),
          el(
            "dd",
            {},
            address
              ? el(
                  "span",
                  {},
                  el("strong", {}, address.label),
                  el("span", {}, `${address.line1 || ""}${address.line2 ? `, ${address.line2}` : ""} · ${address.city || ""}`),
                  el("span", { class: "num" }, `Chega até ${arrivalDate(cart.deliveryDays || 1)} · frete grátis`),
                )
              : el("span", {}, "Escolha um endereço"),
          ),
          el("button", { type: "button", class: "nx-textbtn", onClick: () => goto(search, "entrega") }, "Alterar ", el("span", { class: "sr-only" }, "entrega")),
        ),
        el(
          "div",
          {},
          el("dt", {}, icon("card", { size: 20 }), "Pagamento"),
          el(
            "dd",
            {},
            el("strong", {}, cardLabel),
            el("span", { class: "num" }, planText(total, model.installments)),
          ),
          el("button", { type: "button", class: "nx-textbtn", onClick: () => goto(search, "pagamento") }, "Alterar ", el("span", { class: "sr-only" }, "pagamento")),
        ),
      ),
      el(
        "fieldset",
        { class: "nx-fieldset", "aria-describedby": "origin-hint" },
        el("legend", { class: "nx-h4" }, "Como você chegou a esta compra?"),
        el("p", { id: "origin-hint", class: "nx-fieldset-hint" }, "Uma resposta rápida ajuda o seu banco a reconhecer quando alguém está guiando a compra. Não muda o preço."),
        el(
          "div",
          { class: "nx-opts nx-opts-2", role: "radiogroup", "aria-label": "Como você chegou a esta compra", "aria-invalid": showErrors && !model.origin ? "true" : null },
          (model.options?.declaredOrigins ?? []).map((o) =>
            el(
              "label",
              { class: "nx-opt nx-opt-compact" },
              el("input", { type: "radio", name: "origin", value: o.id, checked: model.origin === o.id, onChange: () => { model.origin = o.id; redraw(); } }),
              el("span", { class: "nx-opt-dot", "aria-hidden": "true" }),
              el("span", { class: "nx-opt-body" }, el("strong", {}, o.label), el("span", {}, o.hint)),
            ),
          ),
        ),
        showErrors && !model.origin ? el("p", { class: "nx-field-err" }, "Escolha uma opção para continuar.") : null,
      ),
    );
  }

  function summaryCard(cart, total, label, busy, s, openCart) {
    return el(
      "div",
      { class: "nx-sumcard" },
      el(
        "div",
        { class: "nx-sumcard-head" },
        el("h2", { class: "nx-h3" }, "Resumo"),
        el("button", { type: "button", class: "nx-textbtn", onClick: openCart }, "Editar carrinho"),
      ),
      el(
        "ul",
        { class: "nx-sumlines" },
        cart.lines.map((l) => {
          const p = cart.byId.get(l.productId);
          if (!p) return el("li", { "aria-hidden": "true" }, el("span", { class: "nx-skel", style: { width: "56px", height: "56px" } }), el("span", { class: "nx-skel", style: { width: "60%", height: "14px" } }));
          return el(
            "li",
            {},
            plate(p.image, "", { size: "sm" }).box,
            el("span", { class: "nx-sumlines-txt" }, el("span", {}, p.name), el("span", { class: "num" }, `${l.quantity} × ${brl(p.price)}`)),
            el("span", { class: "num" }, brl(p.price * l.quantity)),
          );
        }),
      ),
      el(
        "dl",
        { class: "nx-sum" },
        el("div", {}, el("dt", {}, "Subtotal"), el("dd", { class: "num" }, brl(total))),
        el("div", {}, el("dt", {}, "Frete"), el("dd", {}, "Grátis")),
        el("div", { class: "nx-sum-total" }, el("dt", {}, "Total"), el("dd", { class: "num" }, brl(total))),
      ),
      el(
        "p",
        { class: "nx-sum-note num" },
        planText(total, model.installments),
      ),
      model.submitError ? storeError(model.submitError.message, { title: "O pagamento não foi enviado" }) : null,
      el(
        "div",
        { class: "nx-co-action" },
        el("span", { class: "nx-co-action-total" }, el("span", {}, "Total"), el("strong", { class: "num" }, brl(total))),
        el(
          "button",
          { type: "submit", class: "nx-btn nx-btn-accent nx-btn-lg nx-btn-block", disabled: busy || !s, "aria-busy": model.submitBusy ? "true" : null },
          model.submitBusy ? el("span", { class: "nx-spinner", "aria-hidden": "true" }) : null,
          el("span", { class: "nx-btn-label" }, label),
        ),
      ),
      el("p", { class: "nx-co-shield" }, icon("shield", { size: 18 }), "Pagamento analisado pelo ContextShield do seu banco. Ele pode pedir uma confirmação antes de concluir."),
    );
  }

  function demoPanel(search) {
    return el(
      "details",
      { class: "nx-demo" },
      el("summary", {}, icon("play", { size: 16 }), el("span", {}, "Cenários da apresentação"), icon("chevronDown", { size: 16, className: "nx-demo-chev" })),
      el(
        "div",
        { class: "nx-demo-body" },
        el("p", {}, "Carrega carrinho, cliente, aparelho e endereço de um cenário e pula para a revisão. Quando há golpista na conversa, as mensagens aparecem num celular ao lado."),
        model.loadError || model.scenariosError ? el("p", { class: "nx-field-err" }, (model.loadError || model.scenariosError).message) : null,
        el(
          "div",
          { class: "nx-demo-list" },
          (model.scenarios ?? []).map((s) =>
            el(
              "button",
              {
                type: "button",
                class: "nx-demo-item",
                "aria-pressed": model.scenario?.id === s.id ? "true" : "false",
                disabled: model.loadBusy,
                onClick: () => loadScenario(s, search).then(() => {}),
              },
              el("span", { class: "nx-demo-code num" }, s.code),
              el("span", { class: "nx-demo-txt" }, el("strong", {}, s.title), el("span", {}, s.summary)),
            ),
          ),
        ),
        model.me
          ? nxField({
              label: "Aparelho simulado",
              hint: "Na vida real, o aparelho vem da sessão do navegador.",
              control: (p) =>
                el(
                  "select",
                  {
                    ...p,
                    class: "nx-select",
                    value: model.deviceId,
                    onChange: (e) => {
                      model.deviceId = e.target.value;
                    },
                  },
                  el("option", { value: "" }, "Aparelho de sempre"),
                  model.me.devices.map((d) => el("option", { value: d.id }, `${d.name} · ${d.detail ?? ""} ${d.trusted ? "" : "(novo)"}`)),
                ),
            })
          : null,
      ),
    );
  }

  return {
    model,
    prepare: async () => {
      await Promise.all([loadMe(), model.scenarios ? null : loadRefs()]);
    },
    view,
    title(search) {
      const cart = cartState();
      const step = stepOf(search);
      const label = STEPS.find((s) => s.id === step)?.label ?? "Carrinho";
      return cart.lines.length ? `${label} · Finalizar compra · NEXA` : "Carrinho · NEXA";
    },
  };
}

function loginGate(next) {
  return el(
    "div",
    { class: "nx-co-login" },
    icon("user", { size: 24 }),
    el("div", {}, el("strong", {}, "Entre para continuar"), el("p", {}, "Precisamos saber quem está comprando para proteger o seu cartão.")),
    el("a", { href: `/loja/entrar?next=${encodeURIComponent(next)}`, class: "nx-btn nx-btn-ink" }, "Entrar"),
  );
}

function padNumber(n) {
  const d = n.replace(/\D/g, "");
  const len = Math.max(16, d.length);
  return (d + "•".repeat(len - d.length)).replace(/(.{4})(?=.)/g, "$1 ");
}

export function cardPreview({ issuer, number, name, exp, cvv = "", focus = null, saved }) {
  const shown = saved ? number : padNumber(number);
  return el(
    "div",
    { class: `nx-cardfx${focus === "cvv" ? " is-flipped" : ""}`, "data-focus": focus || null, "aria-hidden": "true" },
    el(
      "div",
      { class: "nx-cardfx-inner" },
      el(
        "div",
        { class: "nx-cardfx-face nx-cardfx-front" },
        el("div", { class: "nx-cardfx-top" }, el("span", { class: "nx-cardfx-issuer" }, issuer), el("span", { class: "nx-cardfx-kind" }, "Crédito")),
        el(
          "div",
          { class: "nx-cardfx-chiprow" },
          el("span", { class: "nx-cardfx-chip" }),
          el("svg", { width: "22", height: "22", viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", "stroke-width": "1.6", "stroke-linecap": "round" }, el("path", { d: "M8.5 7.5a6.5 6.5 0 0 1 0 9M12 5a10 10 0 0 1 0 14M5 10a2.8 2.8 0 0 1 0 4" })),
        ),
        el("span", { class: "nx-cardfx-num num", "data-part": "number" }, shown),
        el(
          "div",
          { class: "nx-cardfx-row" },
          el("span", { "data-part": "name" }, el("small", {}, "Nome"), el("span", { class: "nx-cardfx-name" }, name.trim() ? name.toUpperCase() : "NOME NO CARTÃO")),
          el("span", { "data-part": "exp" }, el("small", {}, "Validade"), el("span", { class: "num" }, exp || "MM/AA")),
        ),
      ),
      el(
        "div",
        { class: "nx-cardfx-face nx-cardfx-back" },
        el("span", { class: "nx-cardfx-stripe" }),
        el("div", { class: "nx-cardfx-sign" }, el("span"), el("span", { class: "nx-cardfx-cvv num", "data-part": "cvv" }, cvv ? "•".repeat(cvv.length) : "CVV")),
        el("span", { class: "nx-cardfx-backnote" }, "O código de segurança fica aqui, no verso."),
      ),
    ),
  );
}

const minutes = (at) => {
  const [h, m] = at.split(":").map(Number);
  return h * 60 + m;
};

export function phoneChat(scenarioId, outside, who, of, { autoplay = true } = {}) {
  const isCall = /liga|telef/i.test(outside.channel);
  const replies = isCall ? {} : REPLIES[scenarioId] ?? {};
  const items = outside.messages.flatMap((m, i) => {
    const out = [{ from: "them", text: m.text, at: m.at }];
    if (replies[i]) out.push({ from: "me", text: replies[i], at: m.at });
    return out;
  });
  const root = el("figure", { class: "nx-phonewrap", "aria-label": `Conversa no celular ${of}` });
  let shown = autoplay && !reducedMotion() ? 0 : items.length;
  let typing = false;
  let timers = [];

  function paint() {
    const visible = items.slice(0, shown);
    const done = shown >= items.length;
    const clock = outside.messages.at(-1)?.at ?? "";
    const first = outside.messages[0]?.at;
    const callMin = first ? Math.max(1, minutes(clock) - minutes(first)) : 0;
    const initials = outside.sender.replace(/[“”"]/g, "").split(/\s+/).filter((w) => w.length > 2).slice(0, 2).map((w) => w[0]).join("").toUpperCase();
    root.replaceChildren(
      el(
        "div",
        { class: `nx-phone${isCall ? " is-call" : ""}` },
        el(
          "div",
          { class: "nx-phone-screen" },
          el(
            "div",
            { class: "nx-phone-status", "aria-hidden": "true" },
            el("span", { class: "num" }, clock),
            el("span", { class: "nx-phone-island" }),
            el("span", { class: "nx-phone-sys" }, "▮"),
          ),
          isCall
            ? el(
                "div",
                { class: "nx-call-head" },
                el("span", { class: "nx-chat-avatar is-lg", "aria-hidden": "true" }, initials || icon("user", { size: 26 })),
                el("strong", {}, outside.sender),
                el("span", { class: "nx-call-time num" }, `Em ligação · ${String(callMin).padStart(2, "0")}:00`),
              )
            : el(
                "div",
                { class: "nx-chat-head" },
                icon("back", { size: 18 }),
                el("span", { class: "nx-chat-avatar", "aria-hidden": "true" }, initials || icon("user", { size: 18 })),
                el("span", { class: "nx-chat-who" }, el("strong", {}, outside.sender), el("span", {}, typing ? "digitando…" : "online")),
                icon("phoneCall", { size: 18 }),
              ),
          el(
            "ol",
            { class: isCall ? "nx-call-lines" : "nx-chat-lines", "aria-label": isCall ? "O que a pessoa disse na ligação" : "Mensagens" },
            !isCall ? el("li", { class: "nx-chat-day", "aria-hidden": "true" }, "Hoje") : null,
            visible.map((m, i) =>
              el(
                "li",
                { class: `nx-bubble ${m.from === "me" ? "is-me" : "is-them"}` },
                el("span", { class: "sr-only" }, `${m.from === "me" ? `${who} respondeu` : `Mensagem de ${outside.sender}`}, às ${m.at}: `),
                el("span", { class: "nx-bubble-text" }, m.text),
                el("span", { class: "nx-bubble-meta", "aria-hidden": "true" }, el("span", { class: "num" }, m.at)),
              ),
            ),
            typing ? el("li", { class: "nx-bubble is-them nx-typing", "aria-label": isCall ? "Falando" : "Digitando" }, el("i"), el("i"), el("i")) : null,
          ),
        ),
      ),
      el(
        "figcaption",
        { class: "nx-phone-cap" },
        el("span", {}, `Enquanto isso, no celular ${of} · ${outside.channel}`),
        autoplay && !reducedMotion()
          ? el(
              "button",
              {
                type: "button",
                class: "nx-textbtn",
                disabled: !done,
                onClick: () => play(),
              },
              icon("refresh", { size: 15 }),
              "Rever conversa",
            )
          : null,
      ),
    );
  }

  function play() {
    timers.forEach(clearTimeout);
    timers = [];
    if (!autoplay || reducedMotion()) {
      shown = items.length;
      typing = false;
      paint();
      return;
    }
    shown = 0;
    typing = false;
    paint();
    let t = 350;
    items.forEach((it, i) => {
      const prev = items[i - 1];
      const gap = prev ? Math.max(0, minutes(it.at) - minutes(prev.at)) : 1;
      const wait = it.from === "me" ? 700 : Math.min(1600, Math.max(650, gap * 450));
      if (it.from === "them") timers.push(setTimeout(() => { typing = true; paint(); }, t));
      t += wait;
      timers.push(setTimeout(() => { typing = false; shown = i + 1; paint(); }, t));
    });
  }
  play();
  return root;
}

export function orderView({ id, session, state, reloadToken }) {
  const root = el("div");
  let tx = null;
  let error = null;
  let loading = true;
  let scenario = null;
  let retryBusy = false;
  let retryError = null;
  let qStep = 0;
  let answers = {};
  let sendBusy = false;
  let sendError = null;
  let sure = false;
  let actBusy = false;
  let actError = null;
  let lastStatus = null;

  async function load() {
    loading = true;
    paint();
    try {
      tx = await api(`/api/transactions/${id}`, { area: "customer" });
      error = null;
      if (state?.scenario) {
        const list = await api("/api/catalog/scenarios");
        scenario = list.find((s) => s.id === state.scenario) ?? null;
      }
    } catch (e) {
      error = e;
    } finally {
      loading = false;
      paint();
    }
  }

  function paint() {
    const s = session();
    if (!s) {
      root.replaceChildren(
        el(
          "div",
          { class: "nx-wrap nx-pad" },
          el(
            "div",
            { class: "nx-co-login" },
            icon("user", { size: 24 }),
            el("div", {}, el("strong", {}, "Entre para ver este pedido"), el("p", {}, "O pedido fica na conta de quem comprou.")),
            el("a", { href: `/loja/entrar?next=${encodeURIComponent(`/loja/pedido/${id}`)}`, class: "nx-btn nx-btn-ink" }, "Entrar"),
          ),
        ),
      );
      return;
    }
    if (loading && !tx) {
      root.replaceChildren(
        el(
          "div",
          { class: "nx-order nx-wrap", "aria-busy": "true", "aria-label": "Carregando pedido" },
          el(
            "div",
            { class: "nx-order-main" },
            el("span", { class: "nx-skel", style: { width: "64px", height: "64px", borderRadius: "999px" } }),
            el("span", { class: "nx-skel", style: { width: "70%", height: "44px" } }),
            el("span", { class: "nx-skel", style: { width: "90%", height: "18px" } }),
            el("span", { class: "nx-skel", style: { width: "100%", height: "220px", marginTop: "16px" } }),
          ),
        ),
      );
      return;
    }
    if (error) {
      root.replaceChildren(el("div", { class: "nx-wrap nx-pad" }, storeError(error.message, { title: "O pedido não carregou", onRetry: load })));
      return;
    }
    if (!tx) return;
    setTitle(`${STATUS[tx.status]?.customer ?? "Pedido"} · NEXA`);
    if (lastStatus && lastStatus !== tx.status) {
      window.scrollTo({ top: 0 });
      queueMicrotask(() => document.getElementById("nx-result-title")?.focus({ preventScroll: true }));
    }
    lastStatus = tx.status;
    const head = HEAD[tx.status] ?? { title: tx.status, text: "" };
    const tone = STATUS[tx.status]?.tone ?? "muted";
    const owner = scenario ? DEMO_OWNER[scenario.customerId] : null;
    const showReasons = tx.reasons.length > 0 && ["BLOCKED", "CANCELLED", "INTERVENTION", "APPROVED_WITH_ALERT"].includes(tx.status);
    root.replaceChildren(
      el(
        "div",
        { class: `nx-order nx-wrap${scenario?.outside ? " has-phone" : ""}` },
        el(
          "div",
          { class: "nx-order-main" },
          el(
            "header",
            { class: `nx-result tone-${tone}` },
            statusMark(tx.status),
            el("h1", { id: "nx-result-title", tabindex: "-1", class: "nx-h1 nx-result-title" }, head.title),
            el("p", { class: "nx-result-text" }, head.text),
            el("p", { class: "nx-mono-meta num" }, `Pedido ${tx.id} · ${dateTime(tx.createdAt)}`),
          ),
          tx.status === "CONTEXT_REQUIRED" && tx.verification ? shield("Confirme alguns detalhes", "info", "Estas perguntas são do seu banco, não da NEXA. Responda com o que aconteceu de verdade, mesmo que alguém tenha pedido outra resposta.", verification()) : null,
          tx.status === "INTERVENTION" ? shield("O que você quer fazer?", "risk", "Esta pausa é do seu banco, não da NEXA. Nenhum valor foi cobrado enquanto você decide.", intervention()) : null,
          tx.status === "ANALYZING"
            ? el(
                "div",
                { class: "nx-retry" },
                retryError ? storeError(retryError.message, { title: "Ainda sem resposta" }) : null,
                el(
                  "button",
                  {
                    type: "button",
                    class: "nx-btn nx-btn-ink nx-btn-lg",
                    disabled: retryBusy,
                    "aria-busy": retryBusy ? "true" : null,
                    onClick: async () => {
                      retryBusy = true;
                      retryError = null;
                      paint();
                      try {
                        tx = await api(`/api/transactions/${id}/retry`, { method: "POST", area: "customer" });
                        qStep = 0;
                        answers = {};
                      } catch (e) {
                        retryError = e;
                      } finally {
                        retryBusy = false;
                        paint();
                      }
                    },
                  },
                  retryBusy ? el("span", { class: "nx-spinner", "aria-hidden": "true" }) : null,
                  el("span", { class: "nx-btn-label" }, icon("refresh", { size: 18 }), " Analisar de novo"),
                ),
              )
            : null,
          nextSteps(tx),
          showReasons ? reasons(tx) : null,
          receipt(tx),
          el(
            "div",
            { class: "nx-order-actions" },
            el("a", { href: "/loja", class: "nx-btn nx-btn-ink nx-btn-lg" }, "Voltar à vitrine"),
            el("a", { href: "/banco", class: "nx-btn nx-btn-line nx-btn-lg" }, "Ver no app do banco"),
            el("a", { href: "/loja/pedidos", class: "nx-textbtn" }, "Todos os seus pedidos"),
          ),
        ),
        scenario?.outside && owner
          ? el("aside", { class: "nx-order-phone", "aria-label": "Conversa que levou à compra" }, phoneChat(scenario.id, scenario.outside, owner.name, owner.of, { autoplay: false }))
          : null,
      ),
    );
  }

  function verification() {
    const questions = tx.verification.questions;
    const q = questions[qStep];
    const last = qStep === questions.length - 1;
    return el(
      "div",
      { class: "cs-q" },
      el(
        "div",
        { class: "cs-q-progress" },
        el("span", { class: "num" }, `Pergunta ${qStep + 1} de ${questions.length}`),
        el("span", { class: "cs-q-bar", "aria-hidden": "true" }, questions.map((x, i) => el("i", { class: i <= qStep ? "on" : "" }))),
      ),
      el(
        "fieldset",
        { class: "cs-q-set" },
        el("legend", { class: "cs-q-prompt" }, q.prompt),
        q.help ? el("p", { class: "cs-q-help" }, q.help) : null,
        q.quote ? el("p", { class: "cs-q-quote" }, `“${q.quote}”`) : null,
        q.showAddress
          ? el(
              "p",
              { class: "cs-q-addr" },
              icon("pin", { size: 18 }),
              el("span", {}, el("strong", {}, tx.address.label), `${tx.address.line1}${tx.address.line2 ? `, ${tx.address.line2}` : ""} · ${tx.address.city}`),
            )
          : null,
        el(
          "div",
          { class: "cs-choices", role: "radiogroup", "aria-label": q.prompt },
          q.options.map((o) =>
            el(
              "label",
              { class: "choice" },
              el("input", { type: "radio", name: q.key, value: o.id, checked: answers[q.key] === o.id, onChange: () => { answers = { ...answers, [q.key]: o.id }; paint(); } }),
              el("span", { class: "dot", "aria-hidden": "true" }),
              el("span", {}, o.label),
            ),
          ),
        ),
      ),
      sendError ? el("p", { class: "cs-err", role: "alert" }, sendError.message) : null,
      el(
        "div",
        { class: "cs-actions" },
        el(
          "button",
          { type: "button", class: "cs-btn cs-btn-quiet", disabled: qStep === 0, onClick: () => { qStep -= 1; paint(); } },
          icon("back", { size: 18 }),
          " Voltar",
        ),
        last
          ? el(
              "button",
              {
                type: "button",
                class: "cs-btn cs-btn-primary",
                disabled: !answers[q.key] || sendBusy,
                "aria-busy": sendBusy ? "true" : null,
                onClick: async () => {
                  sendBusy = true;
                  sendError = null;
                  paint();
                  try {
                    tx = await api(`/api/transactions/${tx.id}/verification`, { method: "POST", body: { answers }, area: "customer" });
                    qStep = 0;
                    answers = {};
                  } catch (e) {
                    sendError = e;
                  } finally {
                    sendBusy = false;
                    paint();
                  }
                },
              },
              sendBusy ? el("span", { class: "nx-spinner", "aria-hidden": "true" }) : null,
              el("span", { class: "nx-btn-label" }, "Enviar respostas"),
            )
          : el(
              "button",
              { type: "button", class: "cs-btn cs-btn-primary", disabled: !answers[q.key], onClick: () => { qStep += 1; paint(); } },
              "Próxima ",
              icon("arrow", { size: 18 }),
            ),
      ),
    );
  }

  function intervention() {
    return el(
      "div",
      { class: "cs-q" },
      el("p", { class: "cs-q-help" }, "Se alguém está falando com você agora por telefone ou mensagem, desligue e ligue você mesmo para a central do banco: ", el("strong", { class: "num" }, "0800 000 0000"), "."),
      actError ? el("p", { class: "cs-err", role: "alert" }, actError.message) : null,
      el(
        "button",
        {
          type: "button",
          class: "cs-btn cs-btn-primary cs-btn-block",
          disabled: actBusy,
          "aria-busy": actBusy ? "true" : null,
          onClick: () => resolve("cancel"),
        },
        actBusy ? el("span", { class: "nx-spinner", "aria-hidden": "true" }) : null,
        el("span", { class: "nx-btn-label" }, "Cancelar esta compra"),
      ),
      el("div", { class: "cs-sep" }),
      el(
        "label",
        { class: "cs-check" },
        el("input", { type: "checkbox", checked: sure, onChange: (e) => { sure = e.target.checked; paint(); } }),
        el("span", {}, "Ninguém está me orientando. Eu decidi fazer esta compra por conta própria."),
      ),
      el("button", { type: "button", class: "cs-btn cs-btn-line", disabled: !sure || actBusy, onClick: () => resolve("proceed") }, "Continuar mesmo assim"),
    );
  }

  async function resolve(choice) {
    actBusy = true;
    actError = null;
    paint();
    try {
      tx = await api(`/api/transactions/${tx.id}/resolution`, { method: "POST", body: { choice }, area: "customer" });
    } catch (e) {
      actError = e;
    } finally {
      actBusy = false;
      paint();
    }
  }

  load();
  return { root, title: () => (tx ? `${STATUS[tx.status]?.customer ?? "Pedido"} · NEXA` : "Pedido · NEXA"), reload: load, get token() { return reloadToken; } };
}

function statusMark(status) {
  return el(
    "span",
    { class: "nx-mark", "aria-hidden": "true" },
    el(
      "svg",
      { viewBox: "0 0 24 24", width: "64", height: "64", fill: "none", "stroke-linecap": "round", "stroke-linejoin": "round" },
      el("circle", { class: "nx-mark-bg", cx: "12", cy: "12", r: "10.6" }),
      el("circle", { class: "nx-mark-ring", cx: "12", cy: "12", r: "10.6", pathLength: "1", transform: "rotate(-90 12 12)" }),
      el("path", { class: "nx-mark-glyph", d: GLYPH[status] ?? GLYPH.ANALYZING, pathLength: "1" }),
    ),
  );
}

function shield(title, tone, foot, body) {
  return el(
    "section",
    { class: `cs-sys cs-sys-${tone}`, "aria-labelledby": "cs-sys-title" },
    el(
      "header",
      { class: "cs-sys-head" },
      el("span", { class: "cs-sys-brand" }, mark(22), el("span", {}, el("strong", {}, "ContextShield"), el("span", {}, "Proteção do banco Aureon"))),
      el("span", { class: "cs-sys-lock" }, icon("lock", { size: 14 }), "Etapa do seu banco"),
    ),
    el("div", { class: "cs-sys-body" }, el("h2", { id: "cs-sys-title", class: "cs-sys-title" }, title), body),
    el("footer", { class: "cs-sys-foot" }, foot),
  );
}

function reasons(tx) {
  return el(
    "section",
    { class: "nx-reasons", "aria-labelledby": "h-motivos" },
    el("h2", { id: "h-motivos", class: "nx-h3" }, "O que chamou a atenção"),
    el(
      "ul",
      {},
      tx.reasons.map((r) =>
        el("li", {}, el("span", { class: "nx-reasons-ic", "aria-hidden": "true" }, icon("alert", { size: 18 })), el("span", {}, el("strong", {}, r.label), el("span", {}, r.detail))),
      ),
    ),
  );
}

function nextSteps(tx) {
  const where = `${tx.address.line1}${tx.address.line2 ? `, ${tx.address.line2}` : ""}`;
  const steps = {
    APPROVED: ["A confirmação e a nota fiscal chegam no seu e-mail.", `A entrega vai para ${where}.`, "A compra já aparece como aprovada no app Aureon."],
    APPROVED_WITH_ALERT: [
      "Abra o aviso que o app Aureon enviou e confirme se foi você.",
      "Se alguém pediu esta compra por telefone ou mensagem, ligue para 0800 000 0000 antes de qualquer outro passo.",
      "Se estiver tudo certo, não precisa fazer nada: a entrega segue normalmente.",
    ],
    BLOCKED: [
      "Nenhum valor foi cobrado no seu cartão.",
      "Não refaça a compra por outro caminho, mesmo que alguém insista.",
      el("span", {}, "Se alguém orientou você, registre em “Fui vítima” no ", el("a", { href: "/banco/relatar" }, "app Aureon"), ". O seu relato protege outras pessoas."),
    ],
    CANCELLED: [
      "Nenhum valor foi cobrado.",
      "Bloqueie o contato que pediu a compra e não responda mais.",
      el("span", {}, "Conte o que aconteceu em “Fui vítima” no ", el("a", { href: "/banco/relatar" }, "app Aureon"), ". O seu relato ajuda a proteger outras pessoas."),
    ],
    INCIDENT_RECORDED: ["A equipe do banco está acompanhando o caso.", "Você recebe as atualizações no app Aureon."],
  }[tx.status];
  if (!steps?.length) return null;
  return el(
    "section",
    { class: "nx-next", "aria-labelledby": "h-next" },
    el("h2", { id: "h-next", class: "nx-h3" }, "Próximos passos"),
    el("ol", {}, steps.map((s, i) => el("li", {}, el("span", { class: "nx-next-n num", "aria-hidden": "true" }, String(i + 1)), el("span", {}, s)))),
  );
}

function receipt(tx) {
  return el(
    "section",
    { class: "nx-receipt", "aria-label": "Resumo do pedido" },
    el("div", { class: "nx-receipt-head" }, el("span", { class: "nx-mono-meta" }, tx.id), statusPill(tx.status, { customer: true })),
    el(
      "ul",
      { class: "nx-sumlines" },
      tx.items.map((i) =>
        el(
          "li",
          {},
          i.image ? plate(i.image, "", { size: "sm" }).box : el("span"),
          el("span", { class: "nx-sumlines-txt" }, el("span", {}, i.name), el("span", { class: "num" }, `${i.quantity} × ${brl(i.unitPrice)}`)),
          el("span", { class: "num" }, brl(i.unitPrice * i.quantity)),
        ),
      ),
    ),
    el(
      "dl",
      { class: "nx-sum" },
      el(
        "div",
        {},
        el("dt", {}, "Entrega"),
        el(
          "dd",
          {},
          `${tx.address.line1}${tx.address.line2 ? `, ${tx.address.line2}` : ""}`,
          tx.status === "APPROVED" || tx.status === "APPROVED_WITH_ALERT"
            ? el("span", { class: "nx-sum-sub num" }, `chega até ${arrivalDate(2, new Date(tx.createdAt))}`)
            : null,
        ),
      ),
      el("div", { class: "nx-sum-total" }, el("dt", {}, "Total"), el("dd", { class: "num" }, brl(tx.amount))),
    ),
  );
}
