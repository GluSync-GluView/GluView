/* app.js - F7.3 GluView Zepp OS (Sem teste). */
App({
  globalData: { messaging: null },
  onCreate() {
    try {
      const { MessageBuilder } = require("./shared/message");
      this.globalData.messaging = new MessageBuilder({ appId: 20921 });
      this.globalData.messaging.connect();
    } catch (e) {}
  },
  onDestroy() {}
});
