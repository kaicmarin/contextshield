/** Formatação e vocabulário visual compartilhados (iguais aos da versão anterior). */

const brlFmt = new Intl.NumberFormat("pt-BR", { style: "currency", currency: "BRL" });
export const brl = (n) => (n == null || Number.isNaN(n) ? "—" : brlFmt.format(n));

const dateFmt = new Intl.DateTimeFormat("pt-BR", { day: "2-digit", month: "short" });
const dateTimeFmt = new Intl.DateTimeFormat("pt-BR", { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" });
const timeFmt = new Intl.DateTimeFormat("pt-BR", { hour: "2-digit", minute: "2-digit" });

const parse = (s) => (s ? new Date(s.length === 10 ? `${s}T12:00:00` : s) : null);

export const date = (s) => {
  const d = parse(s);
  return d ? dateFmt.format(d).replace(".", "") : "—";
};
export const dateTime = (s) => {
  const d = parse(s);
  return d ? dateTimeFmt.format(d).replace(".", "") : "—";
};
export const time = (s) => {
  const d = parse(s);
  return d ? timeFmt.format(d) : "—";
};

export function relative(s) {
  const d = parse(s);
  if (!d) return "—";
  const diff = (Date.now() - d.getTime()) / 1000;
  if (diff < 60) return "agora";
  if (diff < 3600) return `há ${Math.floor(diff / 60)} min`;
  if (diff < 86400 && new Date().getDate() === d.getDate()) return `hoje, ${time(s)}`;
  if (diff < 172800) return `ontem, ${time(s)}`;
  return date(s);
}

export const STATUS = {
  RECEIVED: { label: "Recebida", customer: "Recebida", tone: "muted" },
  ANALYZING: { label: "Em análise", customer: "Em análise", tone: "muted" },
  APPROVED: { label: "Aprovada", customer: "Aprovada", tone: "ok" },
  APPROVED_WITH_ALERT: { label: "Aprovada com aviso", customer: "Aprovada com aviso", tone: "warn" },
  CONTEXT_REQUIRED: { label: "Pede contexto", customer: "Aguardando sua confirmação", tone: "info" },
  INTERVENTION: { label: "Intervenção", customer: "Pausada para sua proteção", tone: "risk" },
  BLOCKED: { label: "Bloqueada", customer: "Interrompida", tone: "stop" },
  CANCELLED: { label: "Cancelada", customer: "Cancelada", tone: "muted" },
  INCIDENT_RECORDED: { label: "Golpe relatado", customer: "Golpe relatado", tone: "stop" },
};

export const DECISION = {
  APPROVE: "Aprovar",
  APPROVE_WITH_ALERT: "Aprovar com aviso",
  REQUEST_CONTEXT: "Pedir contexto",
  INTERVENE: "Intervir",
  BLOCK: "Bloquear",
};

export const BAND_TONE = { LOW: "ok", MEDIUM: "warn", HIGH: "risk", CRITICAL: "stop" };
export const BAND = { LOW: "Baixo", MEDIUM: "Médio", HIGH: "Alto", CRITICAL: "Crítico" };

export const ORIGIN = {
  self: "Por conta própria",
  ad: "Anúncio ou promoção",
  link: "Link recebido",
  guided: "Indicada por alguém",
};

export const INCIDENT_TYPE = {
  guided: "Alguém me orientou a comprar",
  message: "Mensagem falsa",
  link: "Link falso",
  impersonation: "Fingiram ser o banco ou a loja",
  unrecognized: "Não reconheço a compra",
  other: "Outro",
};

export const CHANNEL = {
  whatsapp: "WhatsApp",
  phone: "Ligação",
  sms: "SMS",
  email: "E-mail",
  social: "Rede social",
  site: "Site",
  none: "Nenhum",
};

export const INCIDENT_STATUS = {
  OPEN: { label: "Aberto", tone: "info" },
  IN_REVIEW: { label: "Em análise", tone: "warn" },
  CLOSED: { label: "Encerrado", tone: "muted" },
};

export const SOURCE = {
  history: "Histórico",
  device: "Dispositivo",
  checkout: "Checkout",
  merchant: "Loja",
  intelligence: "Inteligência",
  customer: "Contexto declarado",
  verification: "Verificação",
};

export const ENTITY_KIND = {
  phone: "Telefone",
  domain: "Domínio",
  account: "Conta",
  destination: "Endereço",
  seller: "Vendedor",
  merchant: "Loja",
};

export const pct = (n) => `${Math.round(n)}%`;
export const ms = (n) => (n == null ? "—" : n < 10 ? `${n.toFixed(1)} ms` : `${Math.round(n)} ms`);

export const STATUS_ICON = {
  APPROVED: "check",
  APPROVED_WITH_ALERT: "alert",
  CONTEXT_REQUIRED: "question",
  INTERVENTION: "pause",
  BLOCKED: "stop",
  CANCELLED: "x",
  INCIDENT_RECORDED: "flag",
  ANALYZING: "clock",
  RECEIVED: "clock",
};

export function setTitle(title) {
  document.title = title ? `${title} · ContextShield` : "ContextShield";
}
