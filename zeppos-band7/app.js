// GluView no Zepp OS 1.0 (Mi Band 7, Amazfit Band 7). Troca de mensagens do modelo oficial da Zeus CLI
// (shared/, ver shared/LEIA-ME.md): a ZML do app Zepp OS 3 quebra no Zepp OS 1.0.
import "./shared/device-polyfill";
import { MessageBuilder } from "./shared/message";

App({
  globalData: {
    messageBuilder: null,
  },
  onCreate() {
    const appId = hmApp.packageInfo().appId;
    this.globalData.messageBuilder = new MessageBuilder({ appId });
    this.globalData.messageBuilder.connect();
  },
  onDestroy() {
    try { if (this.globalData.messageBuilder) this.globalData.messageBuilder.disConnect(); } catch (e) { /* já fechado */ }
  },
});
