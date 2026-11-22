// GluView no Zepp OS 1.0 — Mi Band 7 (192x490) e Amazfit Band 7 (194x368), telas de barra. Os mesmos 3
// estilos (Clássico, Glicemia em destaque, Completo) e o mesmo gráfico do app Zepp OS 3 e do visor da Mi
// Band, com a lógica compartilhada e testada de ../zeppos/utils/glucose.js. O Zepp OS 1.0 não tem CANVAS
// nem @zos/*: o gráfico vira widgets (utils/widgets.js), a hora vem do sensor TIME, o estilo e o último
// alerta ficam em hmFS.SysPro* e os timers usam timer.createTimer quando não há setTimeout. Mensagens
// pelo MessageBuilder do modelo oficial (shared/): a ZML quebra no Zepp OS 1.0.
//
// Tocar na tela troca o estilo. Busca a cada 60 s e redesenha a cada 30 s. Plano 21, F7.1: alerta novo do
// celular → vibra no padrão escolhido (cenas 27 = 600 ms e 28 = 1000 ms do sensor de vibração) e mostra a
// faixa vermelha com o título por 2 min.
import { display, chartOps, layoutBar, valueGroup, clockText, dateText, C, STYLES, newAlert, vibePlan } from "../../zeppos/utils/glucose";
import { toWidgets, arrowLines, timeAdapter, dotsAlong } from "../utils/widgets";

const { width: W, height: H } = hmSetting.getDeviceInfo();
const SCENE_DURATION = 27;       // forte, 600 ms (doc do sensor VIBRATE, Zepp OS 1.0)
const SCENE_DURATION_LONG = 28;  // forte, 1000 ms

// Widgets REAPROVEITADOS a cada redesenho: o hmUI.deleteWidget não existe no Zepp OS 1.0 da Band 7
// (simulador, 22/11/2026: "TypeError: not a function" no 2º desenho). Um estoque por papel; o que sobra
// fica escondido. Papéis desenhados sempre na mesma ordem: a ordem de empilhamento fica estável.
const pool = {};
const used = {};
let last = null;       // {ok, json}
let lastRx = 0;
let style = 0;
let alertText = null;
let alertUntil = 0;
let fetchTimer = null;
let drawTimer = null;
let vibeTimers = [];
let clock = null;
let vib = null;

// Registro do sistema (no Zepp OS 1.0 do relógio o console.log pode não existir).
let logger = null;
try { logger = DeviceRuntimeCore.HmLogger.getLogger("gluview"); } catch (e) { logger = null; }
function log(msg) { try { if (logger) logger.log(msg); } catch (e) { /* sem registro */ } }

// Erro dentro de timer/resposta derruba o app sem pilha ("not a function"): registra onde foi e segue.
function guard(label, fn) {
  return (...args) => {
    try { return fn(...args); } catch (e) { log("GluView ERRO em " + label + ": " + e + " " + (e && e.stack)); }
  };
}

// Timers: setTimeout/setInterval quando existem (o polyfill do shared/ cria), senão timer.createTimer.
// Cancelar confere cada função antes: no relógio pode haver setTimeout sem clearInterval, e o erro ao
// fechar a página aparecia como "TypeError: not a function" (simulador da Band 7, 22/11/2026).
function later(ms, fn) {
  if (typeof setTimeout === "function") return { t: setTimeout(fn, ms), kind: "timeout" };
  const ref = timer.createTimer(Math.max(1, ms), 2147483647, () => { timer.stopTimer(ref); fn(); }, {});
  return { t: ref, kind: "hm" };
}
function every(ms, fn) {
  if (typeof setInterval === "function") return { t: setInterval(fn, ms), kind: "interval" };
  return { t: timer.createTimer(ms, ms, fn, {}), kind: "hm" };
}
function cancel(h) {
  if (!h) return;
  try {
    if (h.kind === "timeout" && typeof clearTimeout === "function") clearTimeout(h.t);
    else if (h.kind === "interval" && typeof clearInterval === "function") clearInterval(h.t);
    else if (typeof timer !== "undefined" && timer.stopTimer) timer.stopTimer(h.t);
  } catch (e) { log("GluView ERRO ao cancelar timer: " + e); }
}

function getInt(key, def) {
  try { const v = hmFS.SysProGetInt(key); return typeof v === "number" ? v : def; } catch (e) { return def; }
}
function setInt(key, v) { try { hmFS.SysProSetInt(key, v); } catch (e) { /* sem persistência: segue */ } }
function getText(key) { try { return hmFS.SysProGetChars(key) || null; } catch (e) { return null; } }
function setText(key, v) { try { hmFS.SysProSetChars(key, v); } catch (e) { /* segue */ } }

function vibrate(segments) {
  vibeTimers.forEach(cancel);
  vibeTimers = [];
  try { vib = vib || hmSensor.createSensor(hmSensor.id.VIBRATE); } catch (e) { return; }
  for (const p of vibePlan(segments)) {
    vibeTimers.push(later(p.at, () => {
      try { vib.stop(); vib.scene = p.scene === "LONG" ? SCENE_DURATION_LONG : SCENE_DURATION; vib.start(); } catch (e) { /* sem motor */ }
    }));
  }
}

let onTap = null;      // trocar o estilo

// O toque vai para o widget de cima (texto, ponto…), não para o fundo: todos repassam o toque.
function add(role, type, opts) {
  if (!pool[role]) { pool[role] = []; used[role] = 0; }
  const i = used[role]++;
  let w = pool[role][i];
  if (!w) {
    w = hmUI.createWidget(type, opts);
    if (onTap) try { w.addEventListener(hmUI.event.CLICK_UP, onTap); } catch (e) { /* widget sem toque */ }
    pool[role].push(w);
  } else {
    w.setProperty(hmUI.prop.MORE, opts);
    w.setProperty(hmUI.prop.VISIBLE, true);
  }
  return w;
}

function beginDraw() { Object.keys(used).forEach((k) => { used[k] = 0; }); }

function endDraw() {
  Object.keys(pool).forEach((k) => {
    for (let i = used[k]; i < pool[k].length; i++) pool[k][i].setProperty(hmUI.prop.VISIBLE, false);
  });
}

function text(box, value, color, dx, role) {
  if (!box) return;
  add(role || "text", hmUI.widget.TEXT, {
    x: -(dx || 0), y: box.y, w: W, h: box.h, color, text_size: box.size, text: value,
    align_h: hmUI.align.CENTER_H, align_v: hmUI.align.CENTER_V, text_style: hmUI.text_style.NONE,
  });
}

function widget(spec, role) {
  const r = role || "chart";
  if (spec.type === "FILL_RECT") add(r + "-rect", hmUI.widget.FILL_RECT, { x: spec.x, y: spec.y, w: spec.w, h: spec.h, color: spec.color });
  else if (spec.type === "CIRCLE") add(r + "-dot", hmUI.widget.CIRCLE, { center_x: spec.x, center_y: spec.y, radius: spec.r, color: spec.color });
  else if (spec.type === "TEXT") {
    add(r + "-text", hmUI.widget.TEXT, { x: spec.x, y: spec.y, w: spec.w, h: spec.h, color: spec.color, text_size: spec.size, text: spec.text,
      align_h: hmUI.align.LEFT, align_v: hmUI.align.CENTER_V, text_style: hmUI.text_style.NONE });
  } else if (spec.type === "POLYLINE") {
    // No simulador da Band 7 (22/11/2026) o GRADKIENT_POLYLINE existe mas não desenha nada: discos encostados
    // (CIRCLE) desenham em todo Zepp OS 1.0.
    dotsAlong(spec).forEach((c) => widget(c, r));
  }
}

const { messageBuilder } = getApp()._options.globalData;

Page({
    state: {},
    build() {
      // A barra do sistema (nome do app + hora) cobriria a nossa hora no topo.
      try { hmUI.setStatusBarVisible(false); } catch (e) { /* firmware sem a chamada: segue */ }
      style = getInt("gluview_style", 0) % STYLES.length;
      try { clock = hmSensor.createSensor(hmSensor.id.TIME); } catch (e) { clock = null; }
      // Redesenha logo DEPOIS do toque (fora do tratamento do evento); toques seguidos antes do redesenho
      // contam uma vez só.
      let pending = null;
      onTap = () => {
        if (pending) return;
        style = (style + 1) % STYLES.length;
        setInt("gluview_style", style);
        pending = later(30, guard("toque", () => { pending = null; this.draw(); }));
      };
      const bg = hmUI.createWidget(hmUI.widget.FILL_RECT, { x: 0, y: 0, w: W, h: H, color: C.BLACK });
      bg.addEventListener(hmUI.event.CLICK_UP, onTap);
      this.draw();
      this.refresh();
      fetchTimer = every(60000, guard("busca", () => this.refresh()));
      drawTimer = every(30000, guard("relógio", () => this.draw()));
    },
    onDestroy() {
      try {
        cancel(fetchTimer);
        cancel(drawTimer);
        vibeTimers.forEach(cancel);
        if (vib) vib.stop();
      } catch (e) { log("GluView ERRO ao fechar: " + e); }
      vibeTimers = [];
      fetchTimer = drawTimer = null;
    },
    refresh() {
      messageBuilder.request({ method: "GET_WATCH" })
        .then(guard("resposta", (data) => this.receive(data)))
        .catch(guard("falha", (e) => { log("GluView busca falhou: " + e); this.receive(null); }));
    },
    receive(data) {
      if (!data || !data.ok) log("GluView sem dados do celular: " + (data && data.error ? data.error : "sem resposta"));
      if (data && data.ok) {
        last = data;
        lastRx = Date.now();
        const a = newAlert(data.json, getText("gluview_alert"));
        if (a) {
          setText("gluview_alert", a.id);
          alertText = a.title;
          alertUntil = Date.now() + 120000;
          vibrate(a.segments);
        }
      } else last = last ? { ok: false, json: last.json } : null;
      this.draw();
    },
    draw() {
      let step = "início";
      try {
        beginDraw();
        const nowMs = Date.now();
        step = "hora";
        const now = clock ? timeAdapter(clock) : new Date();
        const L = layoutBar(style, W, H);
        const d = display(last, lastRx, nowMs);
        // Os pontos a cada 3 min já formam a linha; a linha cinza entre eles (polilinha) ficaria cara em discos.
        step = "gráfico";
        toWidgets(chartOps(last, lastRx, nowMs, L.chart, L.k, L.labels)).filter((w) => w.type !== "POLYLINE").forEach((w) => widget(w, "chart"));
        step = "seta";
        const g = d.fresh ? valueGroup(d.value, L.value, W, d.dir) : { dx: 0, centers: [] };
        g.centers.forEach((p) => arrowLines(p.cx, p.cy, g.s, g.deg, d.color).forEach((w) => widget(w, "arrow")));
        step = "textos";
        if (L.date) text(L.date, dateText(now), C.GREY);
        text(L.time, clockText(now), C.WHITE);
        text(d.fresh ? L.value : { ...L.value, size: Math.round(L.value.size * 0.45) }, d.value, d.color, g.dx);
        text(L.deltaAge, d.deltaAge, C.GREY);
        step = "faixa";
        if (alertText && nowMs < alertUntil) {
          const bh = Math.round(H * 0.1);
          add("banner", hmUI.widget.FILL_RECT, { x: 0, y: 0, w: W, h: bh, color: C.URGENT });
          text({ y: 0, h: bh, size: Math.round(W * 0.11) }, alertText, C.WHITE, 0, "banner-text");
        }
        step = "esconder";
        endDraw();
      } catch (e) {
        log("GluView ERRO no desenho (" + step + "): " + e);
      }
    },
});
