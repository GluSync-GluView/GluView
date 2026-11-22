// Lógica pura do GluView no Zepp OS (sem APIs do relógio), testada com Node em test/glucose.test.mjs.

export const STALE_MIN = 20;

// Seta → imagem em assets/<alvo>/arrow/*.png (as fontes do relógio podem não ter →↗↘; no Garmin
// a seta em texto saía "◆?").
export const ARROW_IMAGES = {
  DoubleUp: "arrow/double_up.png",
  SingleUp: "arrow/up.png",
  FortyFiveUp: "arrow/up45.png",
  Flat: "arrow/flat.png",
  FortyFiveDown: "arrow/down45.png",
  SingleDown: "arrow/down.png",
  DoubleDown: "arrow/double_down.png",
};

// No CELULAR (serviço lateral): corpo do /sgv.json → resposta para o relógio. A idade sai da hora do
// celular (phoneNowMs), que é a mesma do GluView; relógio com hora errada não engana.
export function parseSgv(body, phoneNowMs) {
  const data = typeof body === "string" ? JSON.parse(body) : body;
  const first = Array.isArray(data) ? data[0] : null;
  if (!first || typeof first.sgv !== "number" || typeof first.date !== "number") {
    return { ok: false, error: "sem leitura" };
  }
  return { ok: true, sgv: first.sgv, direction: first.direction || "", ageMs: Math.max(0, phoneNowMs - first.date) };
}

// No RELÓGIO: resposta + hora em que chegou (rxMs) + agora (nowMs), ambos do relógio → o que mostrar.
// Só a diferença entre dois instantes do relógio entra na conta, então a hora absoluta dele não importa.
export function display(data, rxMs, nowMs) {
  if (!data || !data.ok) return { value: "Sem sinal", age: "", arrow: null };
  const ageMin = Math.floor((data.ageMs + Math.max(0, nowMs - rxMs)) / 60000);
  const age = ageMin + " min";
  if (ageMin >= STALE_MIN) return { value: "Sem sinal", age, arrow: null };
  return { value: String(Math.round(data.sgv)), age, arrow: ARROW_IMAGES[data.direction] || null };
}
