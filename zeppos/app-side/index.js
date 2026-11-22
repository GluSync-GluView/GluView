// GluView no Zepp OS (plano 21, F7.3) — serviço lateral, roda dentro do app Zepp no celular.
// Lê a glicemia do servidor web local do GluView (F2), no próprio celular, porta 17580:
// GET /sgv.json?count=1 (formato Nightscout/xDrip, só leitura). Feito sobre o modelo oficial
// "fetch-api" do Zeus CLI (Zepp OS 3.0, @zeppos/zml).
import { BaseSideService } from "@zeppos/zml/base-side";
import { parseSgv } from "../utils/glucose";

const SGV_URL = "http://127.0.0.1:17580/sgv.json?count=1";

async function fetchGlucose(res) {
  try {
    const response = await fetch({ url: SGV_URL, method: "GET" });
    res(null, parseSgv(response.body, Date.now()));
  } catch (error) {
    res(null, { ok: false, error: String(error) });
  }
}

AppSideService(
  BaseSideService({
    onInit() {},
    onRequest(req, res) {
      if (req.method === "GET_GLUCOSE") fetchGlucose(res);
    },
    onRun() {},
    onDestroy() {},
  })
);
