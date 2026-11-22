// Testes da lógica do GluView no Zepp OS. Rodar na pasta zeppos: node --test test/
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  parseWatch, display, chartOps, layout, valueGroup, arrowPoly, arrowOf, zoneColor, usableW,
  clockText, dateText, STALE_MIN, C, newAlert, vibePlan, layoutBar,
} from "../utils/glucose.js";

const json = {
  now: 1_800_000_000_000, unit: "MG_DL", th: { ul: 55, l: 70, h: 180, uh: 250 },
  sgv: 132, txt: "132", age: 2, dir: "FortyFiveUp", dtxt: "+3",
  pts: [178, 150, 120, 60, 90, 75, 60, 200, 2, 132],
};
const ok = { ok: true, json };

test("parseWatch aceita o /watch.json e recusa o resto", () => {
  assert.deepEqual(parseWatch(JSON.stringify(json)), { ok: true, json });
  assert.equal(parseWatch("[]").ok, false);
  assert.equal(parseWatch({ sgv: 1 }).ok, false);
});

test("display: valor, seta, cor da faixa e idade pela diferença do relógio", () => {
  const rx = 5_000; // relógio com hora absurda: só a diferença conta
  const d = display(ok, rx, rx + 60_000);
  assert.equal(d.fresh, true);
  assert.equal(d.value, "132");
  assert.equal(d.deltaAge, "+3  ·  3 min");
  assert.equal(d.dir, "FortyFiveUp");
  assert.equal(d.color, C.IN);
});

test("display: 20 min ou mais vira Sem sinal, com a idade", () => {
  const d = display(ok, 0, (STALE_MIN - 2) * 60_000);
  assert.equal(d.value, "Sem sinal");
  assert.equal(d.deltaAge, `${STALE_MIN} min`);
  assert.equal(d.dir, null);
});

test("display: falha na busca some com o valor; sem nada = Sem dados", () => {
  assert.equal(display({ ok: false, json }, 0, 0).value, "Sem sinal");
  assert.equal(display(null, 0, 0).value, "Sem dados");
});

test("cores iguais às do visor da Mi Band (o limite conta como fora)", () => {
  assert.equal(zoneColor(55, json), C.URGENT);
  assert.equal(zoneColor(70, json), C.OUT);
  assert.equal(zoneColor(71, json), C.IN);
  assert.equal(zoneColor(180, json), C.OUT);
  assert.equal(zoneColor(250, json), C.URGENT);
});

test("gráfico: faixa de urgência, limiares, linha quebrada no buraco e último ponto maior", () => {
  const box = { x: 40, y: 100, w: 400, h: 200 };
  const ops = chartOps(ok, 0, 0, box, 1, true);
  const rects = ops.filter((o) => o.t === "rect");
  assert.equal(rects[1].c, C.BAND);
  assert.ok(ops.some((o) => o.t === "text" && o.s === "70") && ops.some((o) => o.t === "text" && o.s === "180"));
  const dots = ops.filter((o) => o.t === "circle");
  assert.equal(dots.length, 5);
  assert.deepEqual(dots.map((o) => o.c), [C.IN, C.OUT, C.IN, C.OUT, C.IN]); // 150, 60, 75, 200, 132
  assert.ok(dots[4].r > dots[3].r);
  assert.equal(dots[4].x, 440 - Math.round((400 * 2) / 180));
  // 178→120 e 120→90 têm buracos > 15 min: só 90→60? (30) não; 60→2 não. Nenhuma linha cinza.
  assert.equal(ops.filter((o) => o.t === "line" && o.c === C.LINE).length, 0);
  const dense = { ok: true, json: { ...json, pts: [15, 100, 10, 110, 5, 120, 0, 130] } };
  assert.equal(chartOps(dense, 0, 0, box).filter((o) => o.t === "line" && o.c === C.LINE).length, 3);
});

test("gráfico: pontos saem pela esquerda depois de 3 h, contando o tempo desde a chegada", () => {
  const ops = chartOps(ok, 0, 5 * 60_000, { x: 0, y: 0, w: 180, h: 100 });
  assert.equal(ops.filter((o) => o.t === "circle").length, 4); // o de 178 min passou de 180
});

test("layout dos 3 estilos cabe na tela redonda e na quadrada, sem sobrepor", () => {
  for (const [W, H, round] of [[480, 480, true], [390, 450, false], [454, 454, true]]) {
    for (const st of [0, 1, 2]) {
      const L = layout(st, W, H, round);
      const blocks = [L.date, L.time, L.value, L.deltaAge].filter(Boolean).map((b) => [b.y, b.y + b.h]);
      blocks.push([L.chart.y, L.chart.y + L.chart.h]);
      blocks.sort((a, b) => a[0] - b[0]);
      for (let i = 1; i < blocks.length; i++) assert.ok(blocks[i][0] >= blocks[i - 1][1] - 1, `estilo ${st} ${W}x${H}`);
      assert.ok(blocks[blocks.length - 1][1] <= H * 0.86, `estilo ${st} cabe embaixo`);
      assert.ok(L.chart.w > W * 0.4 && L.chart.x >= 0 && L.chart.x + L.chart.w <= W);
      if (round) {
        assert.ok(L.chart.w <= usableW(W, H, true, L.chart.y) && L.chart.w <= usableW(W, H, true, L.chart.y + L.chart.h));
      }
    }
  }
});

test("seta: polígono fechado, dupla tem 2 centros e o grupo fica centrado", () => {
  assert.deepEqual(arrowOf("DoubleDown"), { deg: -90, count: 2 });
  assert.equal(arrowOf("NOT COMPUTABLE"), null);
  const p = arrowPoly(100, 100, 40, 0);
  assert.deepEqual(p[0], p[p.length - 1]);
  assert.equal(Math.max(...p.map((q) => q.x)), 120);
  const lv = { y: 100, h: 100, size: 80 };
  const g = valueGroup("132", lv, 480, "DoubleUp");
  assert.equal(g.centers.length, 2);
  assert.ok(g.dx > 0);
  assert.equal(valueGroup("132", lv, 480, "").dx, 0);
});

test("hora e data em português", () => {
  const d = new Date(2026, 10, 21, 9, 5);
  assert.equal(clockText(d), "09:05");
  assert.equal(dateText(d), "SAB 21/11");
});

test("alerta novo vibra uma vez só, e o plano segue os trechos", () => {
  const j = { ...json, alert: { id: 1764000000000, k: "LOW", t: "Glicose baixa", b: "62 mg/dL", v: [500, 200, 500, 200, 500] } };
  const a = newAlert(j, null);
  assert.equal(a.id, "1764000000000");
  assert.equal(a.title, "Glicose baixa");
  assert.deepEqual(a.segments, [500, 200, 500, 200, 500]);
  assert.equal(newAlert(j, "1764000000000"), null);   // já vibrado
  assert.equal(newAlert(json, null), null);            // sem alerta
  assert.equal(newAlert({ ...json, alert: { t: "x" } }, null), null);
  assert.deepEqual(vibePlan(a.segments), [{ at: 0, scene: "DURATION" }, { at: 700, scene: "DURATION" }, { at: 1400, scene: "DURATION" }]);
  assert.deepEqual(vibePlan([1500]), [{ at: 0, scene: "LONG" }]);
  assert.deepEqual(vibePlan([]), []);
});

test("tela de barra (Mi Band 7, Band 7): 3 estilos empilhados, sem sobrepor, valor com seta dupla cabe", () => {
  for (const [W, H] of [[192, 490], [194, 368]]) {
    for (const st of [0, 1, 2]) {
      const L = layoutBar(st, W, H);
      const blocks = [L.date, L.time, L.value, L.deltaAge].filter(Boolean).map((b) => [b.y, b.y + b.h]);
      blocks.push([L.chart.y, L.chart.y + L.chart.h]);
      blocks.sort((a, b) => a[0] - b[0]);
      for (let i = 1; i < blocks.length; i++) assert.ok(blocks[i][0] >= blocks[i - 1][1] - 1, `estilo ${st} ${W}x${H}`);
      assert.ok(blocks[blocks.length - 1][1] <= H * 0.86, `estilo ${st} cabe embaixo`);
      assert.ok(L.chart.x >= 0 && L.chart.x + L.chart.w <= W && L.chart.w > W * 0.9);
      // "123" com seta dupla: texto + setas dentro da largura
      const g = valueGroup("123", L.value, W, "DoubleUp");
      const textW = 3 * L.value.size * 0.56;
      const left = W / 2 - g.dx - textW / 2;
      const right = g.centers[g.centers.length - 1].cx + g.s / 2;
      assert.ok(left >= 0 && right <= W, `estilo ${st} ${W}x${H}: ${left}..${right}`);
      assert.ok(L.value.size >= 44, "valor legível");
      for (const b of [L.date, L.time, L.value, L.deltaAge].filter(Boolean)) assert.ok(b.size <= b.h, `fonte cabe na faixa (${st} ${W}x${H})`);
    }
  }
});
