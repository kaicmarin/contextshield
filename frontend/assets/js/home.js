import { toast } from "./ui.js";
import { el, reducedMotion } from "./utils/dom.js";
import { setTitle } from "./utils/format.js";
import { icon, mark } from "./utils/icons.js";

const DECISIONS = [
  ["ok", "check", "Aprovar", "Compra habitual. Passa sem atrito."],
  ["warn", "alert", "Avisar", "Aprova e conta no app o que chamou atenção."],
  ["info", "question", "Perguntar", "Duas ou três perguntas sobre como a compra começou."],
  ["risk", "pause", "Pausar", "Segura a compra e explica o motivo."],
  ["stop", "stop", "Bloquear", "O contexto bate com um golpe já relatado."],
];
const W = 1000;
const H = 380;
const NODES = [
  ["ana", 80, 72, "Ana", "cliente Aureon", "user", "muted", 1, true],
  ["c1", 320, 72, "Compra guiada", "notebook de R$ 4.799", "bag", "warn", 1, false],
  ["p1", 560, 72, "Pausada", "sinais de indução", "pause", "risk", 2, false],
  ["r1", 840, 72, "Fui vítima", "relato no app", "flag", "info", 3, false],
  ["intel", 660, 190, "Inteligência", "ponto de retirada e telefone", "network", "info", 4, false],
  ["mar", 80, 308, "Mariana", "outra cliente", "user", "muted", 5, true],
  ["c2", 380, 308, "Mesmo ponto de retirada", "outro golpista", "pin", "warn", 5, false],
  ["b2", 800, 308, "Bloqueada", "antes do dinheiro sair", "stop", "stop", 6, false],
].map(([id, x, y, label, sub, ic, tone, step, person]) => ({ id, x, y, label, sub, ic, tone, step, person }));
const EDGES = [
  ["ana", "c1", 1, "warn"],
  ["c1", "p1", 2, "risk"],
  ["p1", "r1", 3, "info"],
  ["r1", "intel", 4, "info"],
  ["mar", "c2", 5, "warn"],
  ["intel", "c2", 5, "info", "R1 · entidade relatada"],
  ["c2", "b2", 6, "stop"],
].map(([from, to, step, tone, label]) => ({ from, to, step, tone, label }));
const STEPS = [
  ["A ligação", "Um golpista liga para a Ana dizendo ser do banco e a orienta a comprar um notebook “de validação”, com entrega num ponto de retirada."],
  ["A pausa", "No checkout, o ContextShield pergunta como a compra começou. As respostas mostram indução, e a compra é pausada com a explicação."],
  ["O relato", "A Ana cancela e, no app do banco, registra em “Fui vítima” o telefone do golpista e o endereço de entrega."],
  ["A inteligência", "O relato vira sinal para todas as próximas compras: aquele ponto de retirada e aquele telefone agora têm histórico."],
  ["A repetição", "Dias depois, outro golpista guia a Mariana. A conversa é diferente, mas o ponto de retirada é o mesmo."],
  ["O bloqueio", "A compra da Mariana é bloqueada na hora, sem precisar perguntar nada. O relato da Ana protegeu a Mariana."],
];
const DOORS = [
  ["/loja", "door-store", "/previews/loja.png", "Loja NEXA", "Quem compra", "O checkout pergunta como a compra começou e mostra a decisão na hora. Tem os cenários da apresentação prontos.", "Abrir a loja"],
  ["/banco", "door-bank", "/previews/banco.png", "App Aureon", "Quem paga", "Avisos, compras, “Fui vítima” e o “Confira antes de pagar”.", "Abrir o app"],
  ["/ops", "door-ops", "/previews/painel.png?v=2", "Painel ContextShield", "Quem analisa", "Fila, explicação de cada decisão, relatos, inteligência, motor e auditoria.", "Entrar no painel"],
];
const ACCOUNTS = [
  ["Ana Martins", "cliente", "ana.martins@correio.example", "Loja e app"],
  ["Mariana Rocha", "cliente", "mariana.rocha@correio.example", "Loja e app"],
  ["Lívia Moreira", "analista", "analista@contextshield.example", "Painel"],
  ["Administração", "admin", "admin@contextshield.example", "Painel e usuários"],
];
const byId = Object.fromEntries(NODES.map((n) => [n.id, n]));
const pct = (v, total) => `${(v / total) * 100}%`;
function edgePath(a, b) {
  if (a.y === b.y) return `M${a.x} ${a.y}H${b.x}`;
  const my = (a.y + b.y) / 2;
  return `M${a.x} ${a.y}C${a.x} ${my} ${b.x} ${my} ${b.x} ${b.y}`;
}

function story() {
  const reduced = reducedMotion();
  let step = 0;
  let playing = false;
  let started = false;
  const section = el("section", { class: "story", id: "como-funciona", "aria-labelledby": "story-title" });
  const stage = el("div", { class: "story-stage" });
  const caption = el("div", { class: "story-caption", "aria-live": "polite" });
  const controls = el("div", { class: "story-controls" });

  function draw() {
    const done = step >= STEPS.length && !playing;
    const current = STEPS[Math.max(0, Math.min(step, STEPS.length) - 1)];
    stage.dataset.step = String(step);
    stage.replaceChildren(
      el(
        "div",
        { class: "story-canvas", "aria-hidden": "true" },
        el(
          "svg",
          { viewBox: `0 0 ${W} ${H}`, class: "story-edges" },
          el("path", { d: "M40 190H960", class: "story-mid" }),
          EDGES.map((e) => el("path", { d: edgePath(byId[e.from], byId[e.to]), class: "sedge-ghost" })),
          EDGES.map((e) => el("path", { d: edgePath(byId[e.from], byId[e.to]), pathLength: "1", class: `sedge t-${e.tone}${step >= e.step ? " on" : ""}` })),
        ),
        el("span", { class: "lane-label", style: { top: pct(18, H) } }, "Semana 1"),
        el("span", { class: "lane-label", style: { top: pct(254, H) } }, "Semana 2"),
        EDGES.filter((e) => e.label).map((e) => {
          const a = byId[e.from];
          const b = byId[e.to];
          return el("span", { class: `edge-label${step >= e.step ? " on" : ""}`, style: { left: pct((a.x + b.x) / 2, W), top: pct((a.y + b.y) / 2, H) } }, e.label);
        }),
        NODES.map((n) =>
          el(
            "div",
            { class: `snode t-${n.tone}${n.person ? " person" : ""}${step >= n.step ? " on" : ""}${step === n.step ? " now" : ""}`, style: { left: pct(n.x, W), top: pct(n.y, H) } },
            el("span", { class: "snode-ic" }, icon(n.ic, { size: 18 })),
            el("span", { class: "snode-text" }, el("strong", {}, n.label), el("span", {}, n.sub)),
          ),
        ),
      ),
      el("ol", { class: "story-list" }, STEPS.map((s, i) => el("li", { class: step >= i + 1 ? "on" : "" }, el("span", { class: "story-list-n" }, String(i + 1)), el("div", {}, el("strong", {}, s[0]), el("p", {}, s[1]))))),
      el(
        "div",
        { class: "story-foot" },
        caption,
        controls,
      ),
    );
    caption.replaceChildren(step > 0 ? el("div", { class: "caption-in" }, el("span", { class: "mono" }, `${Math.min(step, STEPS.length)} de ${STEPS.length} · ${current[0]}`), el("p", {}, current[1])) : null);
    controls.replaceChildren(
      el("div", { class: "story-dots", role: "group", "aria-label": "Passos da história" }, STEPS.map((s, i) => el("button", { type: "button", "aria-label": `Passo ${i + 1}: ${s[0]}`, "aria-current": step === i + 1 ? "step" : null, class: step >= i + 1 ? "on" : "", onClick: () => { started = true; playing = false; step = i + 1; draw(); } }))),
      playing
        ? el("button", { type: "button", class: "hbtn hbtn-quiet", onClick: () => { playing = false; draw(); } }, icon("pause", { size: 16 }), " Pausar")
        : el("button", { type: "button", class: "hbtn hbtn-quiet", onClick: () => { if (done || step === 0) replay(); else { playing = true; draw(); tick(); } } }, icon(step === 0 || done ? (step === 0 ? "play" : "refresh") : "play", { size: 16 }), step === 0 ? " Assistir" : done ? " Rever" : " Continuar"),
    );
  }
  function tick() {
    if (!playing) return;
    if (step >= STEPS.length) {
      playing = false;
      draw();
      return;
    }
    setTimeout(() => {
      if (!playing) return;
      step += 1;
      draw();
      tick();
    }, 2800);
  }
  function replay() {
    started = true;
    if (reduced) {
      step = STEPS.length;
      draw();
      return;
    }
    step = 0;
    playing = false;
    draw();
    requestAnimationFrame(() => {
      step = 1;
      playing = true;
      draw();
      tick();
    });
  }
  const io = new IntersectionObserver(([e]) => {
    if (!e.isIntersecting || started) return;
    started = true;
    if (reduced) step = STEPS.length;
    else {
      step = 1;
      playing = true;
      tick();
    }
    draw();
  }, { threshold: 0.35 });
  draw();
  section.append(
    el("div", { class: "story-head" }, el("h2", { id: "story-title" }, "Como o relato da Ana protege a Mariana"), el("p", {}, "O golpe por indução engana o antifraude tradicional porque quem compra é a pessoa certa, no próprio celular. O que denuncia o golpe é o contexto, e um relato vira proteção para a próxima vítima.")),
    stage,
  );
  io.observe(section);
  return section;
}

function copyEmail(email) {
  return el("button", {
    type: "button",
    class: "copy",
    "aria-label": `Copiar ${email}`,
    onClick: async () => {
      try {
        await navigator.clipboard.writeText(email);
        toast("E-mail copiado", "ok");
      } catch {
        toast("Não foi possível copiar. Selecione o e-mail e copie.", "stop");
      }
    },
  }, icon("copy", { size: 16 }));
}

setTitle("");
document.body.append(
  el(
    "div",
    { class: "home" },
    el(
      "header",
      { class: "home-bar" },
      el("a", { href: "/", class: "home-brand", "aria-label": "ContextShield, início" }, mark(26), el("span", {}, "ContextShield")),
      el("nav", { "aria-label": "Seções" }, el("a", { href: "#como-funciona" }, "Como funciona"), el("a", { href: "#areas" }, "Demonstração"), el("a", { href: "/sistema" }, "Sistema visual"), el("a", { href: "/swagger-ui.html" }, "API")),
      el("a", { href: "/ops", class: "hbtn hbtn-ghost home-bar-cta" }, "Painel ", icon("arrow", { size: 16 })),
    ),
    el(
      "main",
      {},
      el(
        "section",
        { class: "hero", "aria-labelledby": "hero-title" },
        el("h1", { id: "hero-title", class: "rise", style: { "--i": "0" } }, "Uma compra pode ser legítima e, mesmo assim, ", el("em", {}, "não ser da vontade de quem paga.")),
        el("p", { class: "hero-lede rise", style: { "--i": "2" } }, "O ContextShield avalia o contexto de cada compra com cartão e responde com mais do que sim ou não. O foco são os golpes em que a própria vítima é guiada a comprar."),
        el("div", { class: "hero-cta rise", style: { "--i": "3" } }, el("a", { href: "#como-funciona", class: "hbtn hbtn-primary" }, icon("play", { size: 16 }), " Ver como funciona"), el("a", { href: "/loja", class: "hbtn hbtn-ghost" }, "Começar pela loja ", icon("arrow", { size: 16 }))),
        el(
          "div",
          { class: "ladder rise", style: { "--i": "4" }, "aria-label": "As cinco respostas do motor" },
          el("div", { class: "ladder-track", "aria-hidden": "true" }),
          el("ol", {}, DECISIONS.map((d, i) => el("li", { class: `t-${d[0]}`, style: { "--i": String(i) } }, el("span", { class: "ladder-dot" }, icon(d[1], { size: 18 })), el("strong", {}, d[2]), el("span", {}, d[3])))),
        ),
        el("p", { class: "hero-facts mono rise", style: { "--i": "5" } }, ["31 sinais", "11 regras explicáveis", "p95 de 364 ms com 50 usuários", "2 microsserviços Spring Boot", "76 testes automatizados"].map((t) => el("span", {}, t))),
      ),
      story(),
      el(
        "section",
        { class: "areas", id: "areas", "aria-labelledby": "areas-title" },
        el("div", { class: "areas-head" }, el("h2", { id: "areas-title" }, "Três telas, um mesmo caso"), el("p", {}, "Abra cada uma numa aba: o que acontece na loja aparece no app da cliente e no painel do analista.")),
        el("nav", { class: "doors", "aria-label": "Áreas da demonstração" }, DOORS.map((d) => el("a", { href: d[0], class: `door ${d[1]}` }, el("span", { class: "door-shot" }, el("img", { src: d[2], alt: "", loading: "lazy", width: "1360", height: "860" })), el("span", { class: "door-body" }, el("span", { class: "door-who mono" }, d[4]), el("strong", {}, d[3]), el("span", { class: "door-text" }, d[5]), el("span", { class: "door-go" }, d[6], " ", icon("arrow", { size: 16 })))))),
        el(
          "div",
          { class: "accounts", "aria-labelledby": "acc-title" },
          el("div", { class: "accounts-head" }, el("h3", { id: "acc-title" }, "Contas da demonstração"), el("p", {}, "Todas usam a senha ", el("span", { class: "mono pw" }, "contexto"), ". Os dados são fictícios.")),
          el("ul", {}, ACCOUNTS.map((a) => el("li", {}, el("span", { class: "acc-who" }, el("strong", {}, a[0]), el("span", {}, a[1])), el("span", { class: "acc-mail mono" }, a[2]), el("span", { class: "acc-where" }, a[3]), copyEmail(a[2])))),
        ),
      ),
    ),
    el(
      "footer",
      { class: "home-foot" },
      el("span", { class: "home-brand" }, mark(20), el("span", {}, "ContextShield")),
      el("span", {}, "Projeto A3 de Sistemas Distribuídos · Eric, Bruno e Thiago · 2026"),
      el("span", { class: "home-foot-links" }, el("a", { href: "/swagger-ui.html" }, "Documentação da API"), el("a", { href: "/sistema" }, "Sistema visual")),
    ),
  ),
);
