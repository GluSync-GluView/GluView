/* Plano 21, F7.3 (Sem teste em simulador/hardware): serviço lateral do Zepp OS.
 * Lê a glicemia do servidor web local do GluView no celular (F2), porta 17580,
 * endpoint compatível Nightscout/xDrip: /sgv.json?count=1 (só leitura).
 * Referência de dados: mirror-core LocalHttpServer.kt (/sgv.json).
 */
import { MessageBuilder } from "../shared/message";

const SGV_URL = "http://127.0.0.1:17580/sgv.json?count=1";

AppSideService({
  onInit() {},
  onRun() {},
  onDestroy() {},

  onRequest(req, res) {
    if (req && req.method === "GET_GLUCOSE") {
      this.fetchGlucose(res);
    }
  },

  fetchGlucose(res) {
    try {
      fetch({
        url: SGV_URL,
        method: "GET",
        headers: { "Accept": "application/json" }
      }).then((response) => {
        const status = response.status || 0;
        return response.body ? response.body : response.text();
      }).then((data) => {
        let body = data;
        try { body = typeof data === "string" ? JSON.parse(data) : data; } catch (e) {}
        res(null, { result: body });
      }).catch((err) => {
        res(null, { error: String(err) });
      });
    } catch (e) {
      res(null, { error: String(e) });
    }
  }
});
