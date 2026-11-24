import { ICON_NAMES } from "./utils/icons.js";
import { icon, mark } from "./utils/icons.js";
import { el } from "./utils/dom.js";
import { setTitle } from "./utils/format.js";

const skip = new Set(["check", "alert", "question", "pause", "stop", "clock", "informatica", "celulares", "audio", "foto", "games", "casa", "eletrodomesticos", "escritorio", "ferramentas", "acessorios"]);
const groups = [
  ["Navegação e ações", ICON_NAMES.filter((n) => !skip.has(n))],
  ["Estados da compra", ["check", "alert", "question", "pause", "stop", "clock"]],
  ["Categorias da loja", ["informatica", "celulares", "audio", "foto", "games", "casa", "eletrodomesticos", "escritorio", "ferramentas", "acessorios"]],
];

setTitle("Sistema visual");
document.body.append(
  el(
    "main",
    { class: "ds" },
    el(
      "div",
      { class: "ds-inner" },
      el("a", { href: "/", class: "ds-back" }, icon("back", { size: 18 }), " Início"),
      el(
        "header",
        { class: "ds-head" },
        mark(56),
        el("div", {}, el("h1", {}, "Sistema visual"), el("p", {}, "Uma família de ícones desenhada para o projeto. Grade de 24, traço de 1,6 e o traço de contexto: a parte que acende quando o estado muda.")),
      ),
      groups.map(([title, names]) =>
        el(
          "section",
          { class: "ds-group", "aria-labelledby": `g-${title}` },
          el("h2", { id: `g-${title}` }, title),
          el("ul", { class: "ds-icons" }, names.map((n) => el("li", {}, icon(n, { size: 28 }), el("span", {}, n)))),
        ),
      ),
    ),
  ),
);
