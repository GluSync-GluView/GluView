// GluView no Zepp OS 1.0 (Mi Band 7, Amazfit Band 7) — o Zepp OS 1.0 não tem CANVAS, então o desenho do
// gráfico (as mesmas operações de ../zeppos/utils/glucose.js, testadas) vira widgets que existem nele:
// FILL_RECT (fundo, faixa e tracejados), CIRCLE (pontos; e discos encostados para as setas, ver dotsAlong) e
// TEXT (valores dos limites). Lógica pura, testada com Node em test/widgets.test.mjs.

const samePt = (a, x, y) => a && a.x === x && a.y === y;

// Operações do chartOps → lista de widgets na mesma ordem (o que vem depois fica por cima).
// Segmentos de linha encadeados com a mesma cor/espessura viram UMA polilinha (menos widgets).
export function toWidgets(ops) {
  const out = [];
  let run = null;
  const flush = () => {
    if (run && run.pts.length >= 2) out.push(run);
    run = null;
  };
  for (const o of ops) {
    if (o.t === "rect") {
      flush();
      out.push({ type: "FILL_RECT", x: o.x1, y: o.y1, w: Math.max(1, o.x2 - o.x1), h: Math.max(1, o.y2 - o.y1), color: o.c });
    } else if (o.t === "line" && o.y1 === o.y2) {
      flush();
      const w = Math.max(1, o.w || 1);
      out.push({ type: "FILL_RECT", x: Math.min(o.x1, o.x2), y: Math.round(o.y1 - w / 2), w: Math.max(1, Math.abs(o.x2 - o.x1)), h: w, color: o.c });
    } else if (o.t === "line") {
      if (run && run.color === o.c && run.width === o.w && samePt(run.pts[run.pts.length - 1], o.x1, o.y1)) {
        run.pts.push({ x: o.x2, y: o.y2 });
      } else {
        flush();
        run = { type: "POLYLINE", color: o.c, width: Math.max(1, o.w || 1), pts: [{ x: o.x1, y: o.y1 }, { x: o.x2, y: o.y2 }] };
      }
    } else if (o.t === "circle") {
      flush();
      out.push({ type: "CIRCLE", x: o.x, y: o.y, r: o.r, color: o.c });
    } else if (o.t === "text") {
      flush();
      out.push({ type: "TEXT", x: o.x, y: o.y, w: Math.round(o.size * 3), h: Math.round(o.size * 1.3), size: o.size, text: o.s, color: o.c });
    } else if (o.t === "poly") {
      flush();
      out.push({ type: "POLYLINE", color: o.c, width: 2, pts: o.pts });
    }
  }
  flush();
  return out;
}

// Seta desenhada com traços grossos (haste + ponta), já que não há polígono preenchido no Zepp OS 1.0.
// Mesma geometria do arrowPoly: deg 0 = →, 90 = ↑, -90 = ↓, ±45 = diagonais.
export function arrowLines(cx, cy, s, deg, color) {
  const a = (deg * Math.PI) / 180, c = Math.cos(a), n = Math.sin(a);
  const P = (x, y) => ({ x: Math.round(cx + x * c - y * n), y: Math.round(cy - (x * n + y * c)) });
  const h = s / 2, w = s / 2.6;
  const width = Math.max(2, Math.round(s / 6));
  return [
    { type: "POLYLINE", color, width, pts: [P(-h, 0), P(h * 0.8, 0)] },
    { type: "POLYLINE", color, width, pts: [P(0, w), P(h, 0), P(0, -w)] },
  ];
}

// Hora local pelo sensor de tempo do relógio (o Date do Zepp OS 1.0 não garante o fuso) no formato
// que clockText/dateText esperam. week do sensor: 1 = segunda … 7 = domingo.
export function timeAdapter(t) {
  return {
    getHours: () => t.hour,
    getMinutes: () => t.minute,
    getDay: () => t.week % 7,
    getDate: () => t.day,
    getMonth: () => t.month - 1,
  };
}

// Firmware sem GRADKIENT_POLYLINE (a Amazfit Band 7 no simulador, 22/11/2026: a seta não aparecia): a
// polilinha vira discos (CIRCLE) encostados ao longo do caminho, com o diâmetro da espessura da linha.
export function dotsAlong(spec) {
  const r = Math.max(1, Math.round(spec.width / 2));
  const step = Math.max(1, r);
  const out = [];
  const pts = spec.pts || [];
  for (let i = 1; i < pts.length; i++) {
    const a = pts[i - 1], b = pts[i];
    const len = Math.hypot(b.x - a.x, b.y - a.y);
    const n = Math.max(1, Math.round(len / step));
    for (let j = i === 1 ? 0 : 1; j <= n; j++) {
      out.push({ type: "CIRCLE", x: Math.round(a.x + ((b.x - a.x) * j) / n), y: Math.round(a.y + ((b.y - a.y) * j) / n), r, color: spec.color });
    }
  }
  return out;
}
