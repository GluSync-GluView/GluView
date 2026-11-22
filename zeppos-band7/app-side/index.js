// GluView no Zepp OS 1.0 — serviço lateral, igual ao do app Zepp OS 3 (../zeppos/app-side): lê o
// /watch.json do servidor web local do GluView no próprio celular (porta 17580), com app=zepp para receber
// o alerta pendente com a vibração escolhida (plano 21, F7.1). Mensagens pelo MessageBuilder do modelo
// oficial da Zeus CLI (shared/).
import { MessageBuilder } from "../shared/message";
import { parseWatch } from "../../zeppos/utils/glucose";

const WATCH_URL = "http://127.0.0.1:17580/watch.json?minutes=180&step=3&app=zepp";
const messageBuilder = new MessageBuilder();

// Erro do fetch do Zepp pode vir como objeto (no simulador: "[object Object]"): vira texto legível.
function describe(error) {
  if (error instanceof Error) return error.message;
  try { return typeof error === "string" ? error : JSON.stringify(error); } catch (e) { return String(error); }
}

async function fetchWatch(ctx) {
  try {
    const response = await fetch({ url: WATCH_URL, method: "GET" });
    ctx.response({ data: parseWatch(response.body) });
  } catch (error) {
    console.log("GluView lado do celular: busca falhou " + describe(error));
    ctx.response({ data: { ok: false, error: describe(error) } });
  }
}

AppSideService({
  onInit() {
    messageBuilder.listen(() => {});
    messageBuilder.on("request", (ctx) => {
      const req = messageBuilder.buf2Json(ctx.request.payload);
      if (req.method === "GET_WATCH") return fetchWatch(ctx);
    });
  },
  onRun() {},
  onDestroy() {},
});
