// setTimeout/setInterval do Zepp OS 1.0 em cima de timer.createTimer, instalados ANTES do polyfill do
// modelo oficial (shared/setTimeout.js). O polyfill cria o timer com período Number.MAX_SAFE_INTEGER, e
// isso derrubava o app no simulador da Band 7 ("TypeError: not a function" sem pilha: no 1º toque, logo
// depois de cada pedido de dados — a troca de mensagens usa setTimeout para o tempo limite — e na abertura
// quando a página usava esperas). Aqui o período é a própria espera e o timer é parado no 1º disparo.

export function makeTimers(hm) {
  const PERIOD_MAX = 2147483647; // int32: o maior período seguro para o lado nativo
  const clamp = (ms) => Math.min(PERIOD_MAX, Math.max(1, Math.round(Number(ms) || 0)));
  const stop = (ref) => { if (ref !== undefined && ref !== null) { try { hm.stopTimer(ref); } catch (e) { /* já parado */ } } };
  return {
    setTimeout(fn, ms) {
      const delay = clamp(ms);
      let ref = null;
      ref = hm.createTimer(delay, delay, () => { stop(ref); if (typeof fn === "function") fn(); }, {});
      return ref;
    },
    clearTimeout: stop,
    setInterval(fn, ms) {
      const period = clamp(ms);
      return hm.createTimer(period, period, () => { if (typeof fn === "function") fn(); }, {});
    },
    clearInterval: stop,
    setImmediate(fn) {
      let ref = null;
      ref = hm.createTimer(1, 1, () => { stop(ref); if (typeof fn === "function") fn(); }, {});
      return ref;
    },
    clearImmediate: stop,
  };
}

// Instala no escopo global quando o relógio não tem os timers do JS (Zepp OS 1.0).
export function installTimers(globalNS) {
  if (typeof setTimeout === "function" || typeof timer === "undefined") return false;
  const t = makeTimers(timer);
  Object.keys(t).forEach((k) => { globalNS[k] = t[k]; });
  return true;
}
