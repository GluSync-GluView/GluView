// Lógica pura do GluView no Zepp OS (sem APIs do relógio), testada com Node em test/glucose.test.mjs.
//
// Pedido do Felipe (21/11/2026): gráfico igual ao da Mi Band e pelo menos 3 estilos desenhados no
// código. O gráfico é o mesmo do visor da Mi Band (GraphLayout.watch / GraphPalette.WATCH_* do app) e
// do Garmin (garmin/common/GluDraw.mc): fundo preto, faixa de urgência abaixo do "muito baixo",
// limiares baixo/alto tracejados com o valor, faixa 100/150 tracejada neutra, linha cinza e pontos
// coloridos pela faixa (o último maior), escala fixa 40–300 mg/dL, janela das últimas 3 h.
//
// Dados = /watch.json do servidor local do GluView: `pts` = [idadeMin, mg/dL, ...] do mais velho ao
// mais novo, idades pela hora do CELULAR. No relógio só entra a diferença desde a chegada (rxMs), então
// a hora absoluta do relógio não importa. Leitura com 20 min ou mais, ou falha na busca → "Sem sinal".

export const STALE_MIN = 20;
export const WINDOW_MIN = 180;
export const GAP_MIN = 15;
export const MIN_SGV = 40;
export const MAX_SGV = 300;

// Cores do visor da Mi Band (ARGB do app compostos sobre preto), 0xRRGGBB.
export const C = {
  BAND: 0x3a1515, HIPO: 0xef5350, HIPER: 0xffa726, SECONDARY: 0x666666, LINE: 0x505050,
  URGENT: 0xef5350, OUT: 0xff7043, IN: 0x66bb6a, GREY: 0xb0bec5, WHITE: 0xffffff, BLACK: 0x000000,
};

export const STYLES = ["Clássico", "Glicemia em destaque", "Completo"];

// No CELULAR (serviço lateral): corpo do /watch.json → resposta para o relógio.
export function parseWatch(body) {
  const d = typeof body === "string" ? JSON.parse(body) : body;
  if (!d || typeof d !== "object" || Array.isArray(d) || !Array.isArray(d.pts)) return { ok: false, error: "resposta inválida" };
  return { ok: true, json: d };
}

function th(json, k, def) {
  const v = json && json.th && json.th[k];
  return typeof v === "number" ? v : def;
}

export function zoneColor(sgv, json) {
  if (sgv <= th(json, "ul", 55)) return C.URGENT;
  if (sgv <= th(json, "l", 70)) return C.OUT;
  if (sgv >= th(json, "uh", 250)) return C.URGENT;
  if (sgv >= th(json, "h", 180)) return C.OUT;
  return C.IN;
}

function sinceRxMin(rxMs, nowMs) {
  return Math.max(0, Math.floor((nowMs - rxMs) / 60000));
}

// No RELÓGIO: o que mostrar como texto. data = resposta do serviço lateral ({ok, json}) ou null.
export function display(data, rxMs, nowMs) {
  const json = data && data.ok ? data.json : null;
  const linkOk = !!(data && data.ok);
  const last = data && data.json ? data.json : null;     // histórico continua mesmo com falha
  const ageMin = last && typeof last.age === "number" ? last.age + sinceRxMin(rxMs, nowMs) : null;
  const age = ageMin === null ? "" : ageMin + " min";
  const fresh = linkOk && json && json.txt != null && ageMin !== null && ageMin < STALE_MIN;
  if (!fresh) {
    return { fresh: false, value: last ? "Sem sinal" : "Sem dados", deltaAge: age, dir: null, color: C.WHITE };
  }
  const deltaAge = json.dtxt ? json.dtxt + "  ·  " + age : age;
  return { fresh: true, value: String(json.txt), deltaAge, dir: json.dir || null, color: zoneColor(json.sgv, json) };
}

// Ângulo da seta (0 = estável, 90 = subindo) e quantas; null = sem seta.
export function arrowOf(dir) {
  const m = { DoubleUp: [90, 2], SingleUp: [90, 1], FortyFiveUp: [45, 1], Flat: [0, 1],
    FortyFiveDown: [-45, 1], SingleDown: [-90, 1], DoubleDown: [-90, 2] };
  return m[dir] ? { deg: m[dir][0], count: m[dir][1] } : null;
}

// Polígono da seta de tamanho s centrada em (cx, cy) — o mesmo do Garmin e do mostrador do app.
export function arrowPoly(cx, cy, s, deg) {
  const t = s / 7, w = s / 2.6, h = s / 2;
  const pts = [[-h, -t], [0, -t], [0, -w], [h, 0], [0, w], [0, t], [-h, t]];
  const a = (deg * Math.PI) / 180, c = Math.cos(a), n = Math.sin(a);
  const out = pts.map(([x, y]) => ({ x: Math.round(cx + x * c - y * n), y: Math.round(cy - (x * n + y * c)) }));
  out.push(out[0]);   // fecha, como no exemplo oficial do drawPoly
  return out;
}

function yOf(sgv, y, h) {
  const s = Math.min(MAX_SGV, Math.max(MIN_SGV, sgv));
  return y + h - (h * (s - MIN_SGV)) / (MAX_SGV - MIN_SGV);
}

function dashed(ops, x1, x2, y, color, w, dash) {
  for (let x = x1; x < x2; x += dash * 2 - Math.floor(dash / 6)) {
    ops.push({ t: "line", x1: Math.round(x), y1: Math.round(y), x2: Math.round(Math.min(x + dash, x2)), y2: Math.round(y), c: color, w });
  }
}

// Comandos de desenho do gráfico no retângulo box {x, y, w, h}; k = escala da tela (480 px = 1).
export function chartOps(data, rxMs, nowMs, box, k = 1, labels = true) {
  const ops = [];
  const json = data && data.json ? data.json : null;
  const { x, y, w, h } = box;
  ops.push({ t: "rect", x1: Math.round(x), y1: Math.round(y), x2: Math.round(x + w), y2: Math.round(y + h), c: C.BLACK });
  const ul = th(json, "ul", 55), l = th(json, "l", 70), hi = th(json, "h", 180);
  ops.push({ t: "rect", x1: Math.round(x), y1: Math.round(yOf(ul, y, h)), x2: Math.round(x + w), y2: Math.round(y + h), c: C.BAND });
  const dash = Math.max(4, Math.round(10 * k));
  const pen = Math.max(1, Math.round(2.5 * k));
  dashed(ops, x, x + w, yOf(100, y, h), C.SECONDARY, 1, dash);
  dashed(ops, x, x + w, yOf(150, y, h), C.SECONDARY, 1, dash);
  dashed(ops, x, x + w, yOf(l, y, h), C.HIPO, pen, dash);
  dashed(ops, x, x + w, yOf(hi, y, h), C.HIPER, pen, dash);
  if (labels) {
    const size = Math.round(22 * k);
    ops.push({ t: "text", x: Math.round(x + 3), y: Math.round(yOf(l, y, h) - size - 2), s: String(l), size, c: C.HIPO });
    ops.push({ t: "text", x: Math.round(x + 3), y: Math.round(yOf(hi, y, h) - size - 2), s: String(hi), size, c: C.HIPER });
  }
  if (!json || !Array.isArray(json.pts)) return ops;
  const shift = sinceRxMin(rxMs, nowMs);
  const pts = [];
  for (let i = 0; i + 1 < json.pts.length; i += 2) {
    const age = json.pts[i] + shift, sgv = json.pts[i + 1];
    if (typeof age !== "number" || typeof sgv !== "number" || age > WINDOW_MIN) continue;
    pts.push({ age, sgv, px: Math.round(x + w - (w * age) / WINDOW_MIN), py: Math.round(yOf(sgv, y, h)) });
  }
  const lw = Math.max(1, Math.round(1.6 * k));
  for (let i = 1; i < pts.length; i++) {
    if (pts[i - 1].age - pts[i].age <= GAP_MIN) {
      ops.push({ t: "line", x1: pts[i - 1].px, y1: pts[i - 1].py, x2: pts[i].px, y2: pts[i].py, c: C.LINE, w: lw });
    }
  }
  pts.forEach((p, i) => {
    const r = Math.max(2, Math.round((i === pts.length - 1 ? 5.5 : 3.4) * k));
    ops.push({ t: "circle", x: p.px, y: p.py, r, c: zoneColor(p.sgv, json) });
  });
  return ops;
}

// Largura útil numa altura y (tela redonda corta os cantos).
export function usableW(W, H, round, y) {
  if (!round) return W;
  const r = W / 2, d = y - H / 2;
  return r * r - d * d <= 0 ? 0 : 2 * Math.sqrt(r * r - d * d);
}

function chartBox(W, H, round, top, h, maxFrac) {
  const cw = Math.min(usableW(W, H, round, top), usableW(W, H, round, top + h), W * maxFrac + 10) - 10;
  return { x: Math.round((W - cw) / 2), y: Math.round(top), w: Math.round(cw), h: Math.round(h) };
}

// Onde fica cada coisa em cada estilo (como os modelos da Mi Band: Clássico, Destaque, Completo).
// Textos: {y, h, size}; gráfico: {x, y, w, h}; tudo em px da tela.
export function layout(style, W, H, round) {
  const k = W / 480;
  const T = (y, h, size) => ({ y: Math.round(H * y), h: Math.round(H * h), size: Math.round(size * k) });
  if (style === 1) {
    return { k, labels: false, time: T(0.07, 0.11, 48), value: T(0.19, 0.26, 128), deltaAge: T(0.46, 0.08, 32),
      chart: chartBox(W, H, round, H * 0.57, H * 0.22, 0.76) };
  }
  if (style === 2) {
    return { k, labels: true, date: T(0.07, 0.06, 26), time: T(0.13, 0.10, 44), value: T(0.23, 0.15, 76),
      chart: chartBox(W, H, round, H * 0.40, H * 0.30, 0.86), deltaAge: T(0.72, 0.07, 26) };
  }
  return { k, labels: true, time: T(0.07, 0.10, 44), chart: chartBox(W, H, round, H * 0.19, H * 0.36, 0.90),
    value: T(0.57, 0.18, 88), deltaAge: T(0.76, 0.08, 32) };
}

// Valor + seta centralizados juntos: o texto (widget TEXT centralizado na tela) anda dx px para a
// esquerda e as setas vão à direita dele. Largura dos dígitos estimada em 0,56 do tamanho da fonte.
export function valueGroup(value, lv, W, dir) {
  const a = arrowOf(dir);
  const size = lv.size;
  const textW = String(value).length * size * 0.56;
  const s = Math.round(Math.min(size * 0.5, 60));
  const gap = Math.round(s * 0.35);
  const dx = a ? Math.round((gap + a.count * s) / 2) : 0;
  const textRight = W / 2 - dx + textW / 2;
  const cy = Math.round(lv.y + lv.h / 2);
  const centers = a ? Array.from({ length: a.count }, (_, i) => ({ cx: Math.round(textRight + gap + i * s + s / 2), cy })) : [];
  return { dx, s, deg: a ? a.deg : null, centers };
}

export function clockText(d) {
  return String(d.getHours()).padStart(2, "0") + ":" + String(d.getMinutes()).padStart(2, "0");
}

export function dateText(d) {
  const days = ["DOM", "SEG", "TER", "QUA", "QUI", "SEX", "SAB"];
  return days[d.getDay()] + " " + String(d.getDate()).padStart(2, "0") + "/" + String(d.getMonth() + 1).padStart(2, "0");
}
