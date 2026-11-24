/**
 * Roteador das áreas. Links da mesma área trocam a tela sem recarregar;
 * links para outra área (loja, banco, painel, início) seguem como navegação normal.
 */
export function createRouter({ base, routes, render }) {
  let seq = 0;

  function normalize(path) {
    if (path.length > 1 && path.endsWith("/")) return path.slice(0, -1);
    return path;
  }

  function locationOf() {
    const path = normalize(location.pathname);
    const rest = path === base ? "" : path.startsWith(`${base}/`) ? path.slice(base.length + 1) : null;
    return { path, rest: rest ?? "", search: new URLSearchParams(location.search), hash: location.hash, state: history.state };
  }

  function match(rest) {
    for (const route of routes) {
      const m = rest.match(route.pattern);
      if (m) return { name: route.name, params: m.groups ?? {} };
    }
    return { name: "notfound", params: {} };
  }

  async function draw() {
    const id = ++seq;
    const loc = locationOf();
    const route = match(loc.rest);
    await render({ ...loc, ...route, fresh: () => id === seq });
    if (id !== seq) return;
    if (loc.hash) {
      const target = document.getElementById(decodeURIComponent(loc.hash.slice(1)));
      if (target) target.scrollIntoView({ block: "start", behavior: reduced() });
    }
  }

  function reduced() {
    return matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth";
  }

  function go(url, { replace = false, state = null } = {}) {
    const next = new URL(url, location.origin);
    const same = next.pathname === location.pathname && next.search === location.search && next.hash === location.hash;
    if (!same) {
      const data = state ?? {};
      if (replace) history.replaceState(data, "", next.pathname + next.search + next.hash);
      else history.pushState(data, "", next.pathname + next.search + next.hash);
    }
    return draw();
  }

  document.addEventListener("click", (e) => {
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    const a = e.target.closest?.("a[href]");
    if (!a || a.target || a.hasAttribute("download")) return;
    if (a.origin !== location.origin) return;
    const path = normalize(a.pathname);
    const sameApp = path === base || path.startsWith(`${base}/`);
    if (!sameApp) return;
    if (a.hash && path === normalize(location.pathname)) return;
    e.preventDefault();
    go(a.pathname + a.search + a.hash);
  });

  window.addEventListener("popstate", () => {
    draw();
  });

  return { go, start: draw };
}
