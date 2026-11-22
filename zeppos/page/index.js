// GluView no Zepp OS (plano 21, F7.3) — página do relógio com 3 estilos (Clássico, Glicemia em
// destaque, Completo) e o gráfico igual ao da Mi Band, desenhados no código (CANVAS + TEXT).
// Tocar na tela troca o estilo, que fica salvo. Pede os dados ao serviço lateral a cada 60 s e
// redesenha a cada 30 s (a idade anda). Leitura com 20 min ou mais, ou falha na busca: "Sem sinal"
// (o histórico continua no gráfico, cada ponto na sua hora). Lógica em utils/glucose.js (testada).
import * as hmUI from "@zos/ui";
import { localStorage } from "@zos/storage";
import { BasePage } from "@zeppos/zml/base-page";
import { ROUND } from "zosLoader:./index.[pf].layout.js";
import { DEVICE_WIDTH as W, DEVICE_HEIGHT as H } from "../utils/config/device";
import { display, chartOps, layout, valueGroup, arrowPoly, clockText, dateText, C, STYLES } from "../utils/glucose";

let canvas = null;
let texts = {};
let fetchTimer = null;
let drawTimer = null;
let last = null;      // {ok, json}: última resposta (json fica mesmo após falha, para o histórico)
let lastRx = 0;
let style = 0;

function text(key, box, color) {
  if (texts[key]) hmUI.deleteWidget(texts[key]);
  texts[key] = hmUI.createWidget(hmUI.widget.TEXT, {
    x: 0, y: box.y, w: W, h: box.h, color, text_size: box.size, text: "",
    align_h: hmUI.align.CENTER_H, align_v: hmUI.align.CENTER_V,
  });
}

function run(ops) {
  for (const o of ops) {
    if (o.t === "rect") canvas.drawRect({ x1: o.x1, y1: o.y1, x2: o.x2, y2: o.y2, color: o.c });
    else if (o.t === "line") { canvas.setPaint({ color: o.c, line_width: o.w }); canvas.drawLine({ x1: o.x1, y1: o.y1, x2: o.x2, y2: o.y2, color: o.c }); }
    else if (o.t === "circle") canvas.drawCircle({ center_x: o.x, center_y: o.y, radius: o.r, color: o.c });
    else if (o.t === "poly") canvas.drawPoly({ data_array: o.pts, color: o.c });
    else if (o.t === "text") canvas.drawText({ x: o.x, y: o.y, text: o.s, text_size: o.size, color: o.c });
  }
}

Page(
  BasePage({
    state: {},
    build() {
      try { style = Number(localStorage.getItem("style", 0)) % STYLES.length || 0; } catch (e) { style = 0; }
      canvas = hmUI.createWidget(hmUI.widget.CANVAS, { x: 0, y: 0, w: W, h: H });
      canvas.addEventListener(hmUI.event.CLICK_UP, () => {
        style = (style + 1) % STYLES.length;
        try { localStorage.setItem("style", style); } catch (e) { /* sem persistência: segue */ }
        this.rebuild();
      });
      this.rebuild();
      this.refresh();
      fetchTimer = setInterval(() => this.refresh(), 60000);
      drawTimer = setInterval(() => this.draw(), 30000);
    },
    onDestroy() {
      if (fetchTimer) clearInterval(fetchTimer);
      if (drawTimer) clearInterval(drawTimer);
      fetchTimer = null;
      drawTimer = null;
    },
    rebuild() {
      const L = layout(style, W, H, ROUND);
      text("time", L.time, C.WHITE);
      text("value", L.value, C.WHITE);
      text("deltaAge", L.deltaAge, C.GREY);
      if (L.date) text("date", L.date, C.GREY);
      else if (texts.date) { hmUI.deleteWidget(texts.date); delete texts.date; }
      this.draw();
    },
    refresh() {
      this.request({ method: "GET_WATCH" })
        .then((data) => this.receive(data))
        .catch(() => this.receive(null));
    },
    receive(data) {
      if (data && data.ok) { last = data; lastRx = Date.now(); }
      else last = last ? { ok: false, json: last.json } : null;
      this.draw();
    },
    draw() {
      const now = new Date();
      const L = layout(style, W, H, ROUND);
      const d = display(last, lastRx, now.getTime());
      canvas.clear({ x: 0, y: 0, w: W, h: H });
      canvas.drawRect({ x1: 0, y1: 0, x2: W, y2: H, color: C.BLACK });
      run(chartOps(last, lastRx, now.getTime(), L.chart, L.k, L.labels));
      const g = d.fresh ? valueGroup(d.value, L.value, W, d.dir) : { dx: 0, centers: [] };
      g.centers.forEach((p) => run([{ t: "poly", pts: arrowPoly(p.cx, p.cy, g.s, g.deg), c: d.color }]));
      texts.time.setProperty(hmUI.prop.TEXT, clockText(now));
      texts.value.setProperty(hmUI.prop.MORE, { x: -g.dx, y: L.value.y, w: W, h: L.value.h, text: d.value, color: d.color,
        text_size: d.fresh ? L.value.size : Math.round(L.value.size * 0.45) });
      texts.deltaAge.setProperty(hmUI.prop.TEXT, d.deltaAge);
      if (texts.date) texts.date.setProperty(hmUI.prop.TEXT, dateText(now));
    },
  })
);
