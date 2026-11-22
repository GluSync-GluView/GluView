// GluView no Zepp OS 1.0 (Mi Band 7, Amazfit Band 7). Troca de mensagens do modelo oficial da Zeus CLI
// (shared/, ver shared/LEIA-ME.md): a ZML do app Zepp OS 3 quebra no Zepp OS 1.0.
import "./timers-first";
import "./shared/device-polyfill";
import { MessageBuilder } from "./shared/message";

App({
  globalData: {
    messageBuilder: null,
  },
  onCreate() {
    let step = "appId";
    try {
      const appId = hmApp.packageInfo().appId;
      step = "MessageBuilder";
      this.globalData.messageBuilder = new MessageBuilder({ appId });
      step = "connect";
      this.globalData.messageBuilder.connect();
    } catch (e) {
      try { DeviceRuntimeCore.HmLogger.getLogger("gluview").log("GluView ERRO no app (" + step + "): " + e); } catch (e2) { /* sem registro */ }
    }
  },
  onDestroy() {
    try { if (this.globalData.messageBuilder) this.globalData.messageBuilder.disConnect(); } catch (e) { /* já fechado */ }
  },
});
