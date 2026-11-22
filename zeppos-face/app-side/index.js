// GluView Visor — serviço lateral igual ao do app Zepp OS 3 (../zeppos/app-side).
import { BaseSideService } from "@zeppos/zml/base-side";
import { parseWatch } from "../../zeppos/utils/glucose";

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
