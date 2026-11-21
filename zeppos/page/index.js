import { createWidget, widget, prop } from "@zos/ui";
import { push } from "@zos/router";
import { getText } from "@zos/i18n";

const { MESSAGE } = getApp()._options.globalData || {};

Page({
  state: { text: null },

  build() {
    this.state.text = createWidget(widget.TEXT, {
      x: 20, y: 80, w: 440, h: 200,
      color: 0xffffff, text_size: 36, align_h: 1, align_v: 1,
      text: "GluView\n..."
    });
    this.request();
    setInterval(() => this.request(), 60000);
  },

  request() {
    try {
      const messaging = getApp()._options.globalData.messaging;
      if (messaging) {
        messaging.request({ method: "GET_GLUCOSE" }).then((ctx) => {
          this.update(ctx.result || ctx);
        }).catch(() => this.update(null));
      }
    } catch (e) {}
  },

  // Auditoria 21/11/2026 (Claude): leitura com 20 min ou mais, ou falha na busca, mostra "Sem sinal"
  // (antes o último valor ficava na tela como se fosse atual).
  update(data) {
    const STALE_MIN = 20;
    let line = "Sem sinal";
    try {
      const arr = Array.isArray(data) ? data : (data && data.result) || [];
      const first = Array.isArray(arr) ? arr[0] : null;
      if (first && first.date) {
        const ageMin = Math.max(0, Math.round((Date.now() - first.date) / 60000));
        line = ageMin < STALE_MIN && first.sgv
          ? `${first.sgv} mg/dL ${first.direction || ""}
${ageMin} min`
          : `Sem sinal
${ageMin} min`;
      }
    } catch (e) {}
    this.state.text && this.state.text.setProperty(prop.TEXT, line);
  }
});
