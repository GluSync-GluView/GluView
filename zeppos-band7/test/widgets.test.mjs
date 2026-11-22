// Testes da conversão para widgets do Zepp OS 1.0. Rodar na pasta zeppos-band7: node --test test/
import { test } from "node:test";
import assert from "node:assert/strict";
import { toWidgets, arrowLines, timeAdapter, dotsAlong } from "../utils/widgets.js";
import { chartOps, layoutBar, clockText, dateText } from "../../zeppos/utils/glucose.js";

const json = {
  now: 1_800_000_000_000, unit: "MG_DL", th: { ul: 55, l: 70, h: 180, uh: 250 },
  sgv: 132, txt: "132", age: 2, dir: "Flat", dtxt: "+3",
  // 4 pontos encadeados, um buraco de 40 min, 2 pontos
  pts: [178, 150, 175, 140, 172, 130, 169, 120, 120, 90, 117, 95],
};

test("gráfico vira retângulos, polilinhas encadeadas, círculos e textos, na ordem", () => {
  const L = layoutBar(0, 192, 490);
  const ops = chartOps({ ok: true, json }, 0, 0, L.chart, L.k, L.labels);
  const ws = toWidgets(ops);
  const types = ws.map((w) => w.type);
  // fundo + faixa de urgência primeiro
  assert.deepEqual(types.slice(0, 2), ["FILL_RECT", "FILL_RECT"]);
  // as duas sequências de linha cinza (antes e depois do buraco) viram 2 polilinhas
  const polys = ws.filter((w) => w.type === "POLYLINE");
  assert.equal(polys.length, 2);
  assert.equal(polys[0].pts.length, 4);
  assert.equal(polys[1].pts.length, 2);
  // 6 pontos, por cima das linhas
  assert.equal(ws.filter((w) => w.type === "CIRCLE").length, 6);
  assert.ok(types.lastIndexOf("POLYLINE") < types.indexOf("CIRCLE"));
  // limites com o valor (estilo Clássico tem rótulos)
  assert.deepEqual(ws.filter((w) => w.type === "TEXT").map((w) => w.text), ["70", "180"]);
  // tracejado horizontal vira retângulos finos dentro do gráfico
  const dashes = ws.filter((w) => w.type === "FILL_RECT" && w.h <= 3);
  assert.ok(dashes.length > 10);
  for (const d of dashes) assert.ok(d.x >= L.chart.x && d.x + d.w <= L.chart.x + L.chart.w + 1);
});

test("seta com haste e ponta, apontando para o lado certo", () => {
  const [shaft, head] = arrowLines(100, 100, 30, 90, 0xffffff);   // para cima
  assert.equal(shaft.pts[0].x, shaft.pts[1].x);
  assert.ok(shaft.pts[1].y < shaft.pts[0].y);
  assert.equal(head.pts[1].y, 85);                                // ponta em cima
  const [, down] = arrowLines(100, 100, 30, -90, 0xffffff);
  assert.equal(down.pts[1].y, 115);
  assert.ok(shaft.width >= 2);
});

test("hora pelo sensor do relógio", () => {
  const d = timeAdapter({ hour: 7, minute: 5, week: 7, day: 22, month: 11 });
  assert.equal(clockText(d), "07:05");
  assert.equal(dateText(d), "DOM 22/11");
  assert.equal(dateText(timeAdapter({ hour: 0, minute: 0, week: 1, day: 3, month: 1 })), "SEG 03/01");
});

test("sem polilinha no firmware: discos encostados ao longo do caminho", () => {
  const d = dotsAlong({ type: "POLYLINE", color: 0xff0000, width: 6, pts: [{ x: 0, y: 0 }, { x: 30, y: 0 }, { x: 30, y: 30 }] });
  assert.ok(d.every((c) => c.type === "CIRCLE" && c.r === 3 && c.color === 0xff0000));
  assert.deepEqual(d[0], { type: "CIRCLE", x: 0, y: 0, r: 3, color: 0xff0000 });
  assert.deepEqual(d[d.length - 1], { type: "CIRCLE", x: 30, y: 30, r: 3, color: 0xff0000 });
  // sem buraco: vizinhos a no máximo um raio
  for (let i = 1; i < d.length; i++) assert.ok(Math.hypot(d[i].x - d[i - 1].x, d[i].y - d[i - 1].y) <= 3.01);
  // canto não duplica
  assert.equal(d.filter((c) => c.x === 30 && c.y === 0).length, 1);
});
