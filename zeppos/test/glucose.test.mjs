// Testes da lógica do GluView no Zepp OS. Rodar na pasta zeppos: node --test test/
import { test } from "node:test";
import assert from "node:assert/strict";
import { parseSgv, display, STALE_MIN, ARROW_IMAGES } from "../utils/glucose.js";
import { existsSync } from "node:fs";

const PHONE_NOW = 1_800_000_000_000;

test("parseSgv usa a hora do celular para a idade", () => {
  const body = JSON.stringify([{ sgv: 132, direction: "FortyFiveUp", date: PHONE_NOW - 3 * 60000 }]);
  assert.deepEqual(parseSgv(body, PHONE_NOW), { ok: true, sgv: 132, direction: "FortyFiveUp", ageMs: 180000 });
});

test("parseSgv aceita corpo já em objeto e recusa lista vazia ou campos errados", () => {
  assert.equal(parseSgv([{ sgv: 90, date: PHONE_NOW }], PHONE_NOW).ok, true);
  assert.equal(parseSgv("[]", PHONE_NOW).ok, false);
  assert.equal(parseSgv([{ sgv: "90", date: PHONE_NOW }], PHONE_NOW).ok, false);
  assert.equal(parseSgv({ status: [] }, PHONE_NOW).ok, false);
});

test("parseSgv não deixa idade negativa (hora do GluView à frente)", () => {
  assert.equal(parseSgv([{ sgv: 90, date: PHONE_NOW + 5000 }], PHONE_NOW).ageMs, 0);
});

test("display ignora a hora absoluta do relógio (só a diferença conta)", () => {
  const data = { ok: true, sgv: 132.4, direction: "Flat", ageMs: 2 * 60000 };
  const watchWrong = 5_000; // relógio com hora absurda
  assert.deepEqual(display(data, watchWrong, watchWrong + 60000), { value: "132", age: "3 min", arrow: "arrow/flat.png" });
});

test("display: leitura de 20 min ou mais vira Sem sinal, com idade visível", () => {
  const data = { ok: true, sgv: 100, direction: "Flat", ageMs: (STALE_MIN - 1) * 60000 };
  assert.equal(display(data, 0, 0).value, "100");
  assert.deepEqual(display(data, 0, 60000), { value: "Sem sinal", age: `${STALE_MIN} min`, arrow: null });
});

test("display: falha na busca não mantém valor antigo", () => {
  assert.deepEqual(display(null, 0, 0), { value: "Sem sinal", age: "", arrow: null });
  assert.deepEqual(display({ ok: false, error: "x" }, 0, 0), { value: "Sem sinal", age: "", arrow: null });
});

test("display: direção desconhecida fica sem seta", () => {
  assert.equal(display({ ok: true, sgv: 80, direction: "NOT COMPUTABLE", ageMs: 0 }, 0, 0).arrow, null);
});

test("toda seta tem imagem nos dois alvos", () => {
  for (const src of Object.values(ARROW_IMAGES)) {
    for (const target of ["common.r", "common.s"]) {
      assert.ok(existsSync(new URL(`../assets/${target}/${src}`, import.meta.url)), `${target}/${src}`);
    }
  }
});
