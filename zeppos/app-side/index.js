// GluView no Zepp OS (plano 21, F7.3) — serviço lateral, roda dentro do app Zepp no celular.
// Lê o /watch.json do servidor web local do GluView (F2), no próprio celular, porta 17580: última
// leitura já formatada, limites do paciente e os pontos das últimas 3 h (idades pela hora do
// celular). Feito sobre o modelo oficial "fetch-api" do Zeus CLI (Zepp OS 3.0, @zeppos/zml).
import { BaseSideService } from "@zeppos/zml/base-side";
import { parseWatch } from "../utils/glucose";

// app=zepp: o servidor junta o alerta pendente para o Zepp (F7.1), com a vibração escolhida no celular.
const WATCH_URL = "http://127.0.0.1:17580/watch.json?minutes=180&step=3&app=zepp";

async function fetchWatch(res) {
  try {
    const response = await fetch({ url: WATCH_URL, method: "GET" });
    res(null, parseWatch(response.body));
  } catch (error) {
    res(null, { ok: false, error: String(error) });
  }
}

AppSideService(
  BaseSideService({
    onInit() {},
    onRequest(req, res) {
      if (req.method === "GET_WATCH") fetchWatch(res);
    },
    onRun() {},
    onDestroy() {},
  })
);
