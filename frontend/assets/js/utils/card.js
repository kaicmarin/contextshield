/** Máscaras e validação do cartão digitado no checkout. Os dígitos não são enviados à API. */

const digits = (s) => String(s ?? "").replace(/\D/g, "");
export const maskCard = (s) => digits(s).slice(0, 19).replace(/(\d{4})(?=\d)/g, "$1 ");
export const maskCvv = (s) => digits(s).slice(0, 4);
export const maskCep = (s) => {
  const d = digits(s).slice(0, 8);
  return d.length > 5 ? `${d.slice(0, 5)}-${d.slice(5)}` : d;
};
export function maskExp(s) {
  let d = digits(s).slice(0, 4);
  if (d.length === 1 && Number(d) > 1) d = `0${d}`;
  return d.length > 2 ? `${d.slice(0, 2)}/${d.slice(2)}` : d;
}

function luhn(num) {
  let sum = 0;
  let dbl = false;
  for (let i = num.length - 1; i >= 0; i--) {
    let n = Number(num[i]);
    if (dbl) {
      n *= 2;
      if (n > 9) n -= 9;
    }
    sum += n;
    dbl = !dbl;
  }
  return sum % 10 === 0;
}

export function cardErrors(c, now = new Date()) {
  const e = {};
  const n = digits(c.number);
  if (n.length < 13 || !luhn(n)) e.number = "Confira o número do cartão: ele tem de 13 a 19 dígitos.";
  if (c.name.trim().length < 3) e.name = "Escreva o nome como está impresso no cartão.";
  const m = /^(\d{2})\/(\d{2})$/.exec(c.exp);
  if (!m || Number(m[1]) < 1 || Number(m[1]) > 12) e.exp = "Use mês e ano, como 08/29.";
  else {
    const yy = now.getFullYear() % 100;
    const mm = now.getMonth() + 1;
    const y = Number(m[2]);
    if (y < yy || (y === yy && Number(m[1]) < mm)) e.exp = "Este cartão está vencido.";
  }
  if (!/^\d{3,4}$/.test(c.cvv)) e.cvv = "O código tem 3 ou 4 dígitos e fica no verso do cartão.";
  return e;
}

export const emptyCard = () => ({ mode: "saved", number: "", name: "", exp: "", cvv: "" });
