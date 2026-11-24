import { brl } from "./utils/format.js";

/** Fichas editoriais da vitrine. Descrevem os produtos de demonstração e não vêm da API. */

const SPECS = {
  "prod-001": [["Tela", "14″ · 2560 × 1600"], ["Memória", "16 GB"], ["Armazenamento", "SSD 512 GB"], ["Chassi", "Alumínio"], ["Peso", "1,3 kg"]],
  "prod-002": [["Tela", "6,3″ OLED · 120 Hz"], ["Armazenamento", "256 GB"], ["Câmera principal", "50 MP"], ["Rede", "5G"], ["Cor", "Grafite"]],
  "prod-003": [["Formato", "Over-ear"], ["Ruído", "Cancelamento ativo"], ["Conexão", "Bluetooth 5.3"], ["Cor", "Areia"]],
  "prod-004": [["Tela", "27″ IPS"], ["Resolução", "2560 × 1440 (QHD)"], ["Taxa de atualização", "144 Hz"], ["Entradas", "USB-C · HDMI · DisplayPort"]],
  "prod-005": [["Armazenamento", "SSD 1 TB"], ["Resolução", "Até 4K"], ["Na caixa", "1 controle sem fio"], ["Cor", "Branco"]],
  "prod-006": [["Caixa", "40 mm"], ["Localização", "GPS integrado"], ["Resistência", "5 ATM"], ["Pulseira", "Sálvia"]],
  "prod-007": [["Formato", "In-ear"], ["Ruído", "ANC adaptativo"], ["Estojo", "Carrega por USB-C"], ["Cor", "Branco"]],
  "prod-008": [["Som", "360°"], ["Proteção", "IP67"], ["Conexão", "Bluetooth 5.3"], ["Cor", "Sálvia"]],
  "prod-009": [["Teclas", "Perfil baixo · silenciosas"], ["Layout", "ABNT2"], ["Conexão", "Sem fio e USB-C"], ["Cor", "Grafite"]],
  "prod-010": [["Sensor", "8.000 DPI"], ["Formato", "Ergonômico"], ["Conexão", "Bluetooth e receptor USB"], ["Cor", "Grafite"]],
  "prod-011": [["Resolução", "2K"], ["Noite", "Visão noturna"], ["Rede", "Wi-Fi 2,4 GHz"], ["Uso", "Interno"]],
  "prod-012": [["Gatilhos", "Adaptáveis"], ["Conexão", "Sem fio e USB-C"], ["Compatível com", "Console Vertex"], ["Cor", "Meia-noite"]],
  "prod-013": [["Tela", "11″"], ["Armazenamento", "128 GB"], ["Rede", "Wi-Fi"], ["Cor", "Prata"]],
  "prod-014": [["Tipo", "Mirrorless"], ["Sensor", "APS-C"], ["Lente inclusa", "15–45 mm"], ["Vídeo", "4K"]],
  "prod-015": [["Capacidade", "5 litros"], ["Potência", "1.500 W"], ["Tensão", "127 V ou 220 V"], ["Cor", "Grafite"]],
  "prod-016": [["Pressão", "15 bar"], ["Leite", "Vaporizador"], ["Acabamento", "Inox"]],
  "prod-017": [["Navegação", "Mapeamento a laser"], ["Controle", "Pelo aplicativo"], ["Cor", "Branco"]],
  "prod-018": [["Encosto", "Tela respirável"], ["Lombar", "Apoio ajustável"], ["Carga máxima", "120 kg"], ["Cor", "Grafite"]],
  "prod-019": [["Luz", "LED"], ["Temperatura de cor", "Ajustável"], ["Cor", "Areia"]],
  "prod-020": [["Tensão", "12 V"], ["Baterias", "2 inclusas"], ["Na caixa", "Maleta"], ["Cor", "Sálvia"]],
  "prod-021": [["Peças", "110"], ["Na caixa", "Maleta"], ["Cor", "Argila"]],
  "prod-022": [["Capacidade", "20.000 mAh"], ["Potência", "30 W"], ["Saídas", "USB-C e USB-A"]],
  "prod-023": [["Notebook", "Até 15″"], ["Material", "Impermeável"], ["Cor", "Meia-noite"]],
  "prod-024": [["Armazenamento", "256 GB"], ["Condição", "Lacrado (informado pelo vendedor)"], ["Cor", "Meia-noite"]],
  "prod-025": [["Memória", "16 GB"], ["Condição", "Pronta entrega (informado pelo vendedor)"], ["Cor", "Grafite"]],
  "prod-026": [["Armazenamento", "128 GB"], ["Condição", "Lacrado (informado pelo vendedor)"], ["Cor", "Grafite"]],
  "prod-027": [["Versão", "Importada"], ["Formato", "In-ear"], ["Cor", "Grafite"]],
  "prod-028": [["Caixa", "44 mm"], ["Versão", "Importada"], ["Cor", "Meia-noite"]],
};

export function specsOf(p) {
  return SPECS[p.id] ?? p.line.split(" · ").map((v, i) => [i === 0 ? "Configuração" : "Detalhe", v]);
}

export const productNo = (p) => `Nº ${p.id.replace(/\D/g, "").padStart(3, "0").slice(-3)}`;
export const installmentText = (price, n) => (n > 1 ? `${n}x de ${brl(price / n)} sem juros` : "à vista");
export const discountOf = (p) => (p.oldPrice && p.oldPrice > p.price ? Math.round((1 - p.price / p.oldPrice) * 100) : 0);

const weekdayFmt = new Intl.DateTimeFormat("pt-BR", { weekday: "short", day: "numeric", month: "short" });

export function arrivalDate(businessDays, from = new Date()) {
  const d = new Date(from);
  let left = Math.max(1, businessDays);
  while (left > 0) {
    d.setDate(d.getDate() + 1);
    const wd = d.getDay();
    if (wd !== 0 && wd !== 6) left--;
  }
  return weekdayFmt.format(d);
}

export const fold = (s) => s.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase();

export function searchProducts(products, cats, q) {
  const terms = fold(q).split(/\s+/).filter(Boolean);
  if (!terms.length) return [];
  const catName = new Map((cats ?? []).map((c) => [c.slug, c.name]));
  return products
    .map((p) => {
      const name = fold(p.name);
      const hay = `${name} ${fold(p.line)} ${fold(catName.get(p.categorySlug) ?? "")} ${fold(p.sku)}`;
      if (!terms.every((t) => hay.includes(t))) return null;
      const score = (name.startsWith(terms[0]) ? 2 : 0) + (name.includes(terms[0]) ? 1 : 0) + p.rating / 10;
      return { p, score };
    })
    .filter(Boolean)
    .sort((a, b) => b.score - a.score)
    .map((x) => x.p);
}

export const SORTS = [
  { id: "relevancia", label: "Mais relevantes" },
  { id: "menor-preco", label: "Menor preço" },
  { id: "maior-preco", label: "Maior preço" },
  { id: "avaliacao", label: "Mais bem avaliados" },
];

export function sortProducts(list, sort) {
  const out = [...list];
  if (sort === "menor-preco") out.sort((a, b) => a.price - b.price);
  else if (sort === "maior-preco") out.sort((a, b) => b.price - a.price);
  else if (sort === "avaliacao") out.sort((a, b) => b.rating - a.rating || b.reviewCount - a.reviewCount);
  return out;
}

export const CURATED = ["prod-002", "prod-003", "prod-005", "prod-014", "prod-015", "prod-007"];
export const HERO_ID = "prod-001";
export const FEATURE_ID = "prod-003";
export const rating = (n) => n.toLocaleString("pt-BR", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
export const cutOf = (src) => src.replace(/^\/products\/([^/]+)\.jpg$/, "/products/cut/$1.webp");

const TINTS = {
  graphite: "#e9eaee",
  silver: "#ecedf0",
  white: "#e7edf6",
  midnight: "#e3e7f3",
  sage: "#e2ece4",
  sand: "#f3ebe0",
  clay: "#f3e4db",
};
export const tintOf = (src) => TINTS[/-([a-z]+)\.(jpg|webp)$/.exec(src)?.[1] ?? ""] ?? "#ecedf0";
