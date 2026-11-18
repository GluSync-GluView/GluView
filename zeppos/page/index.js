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
        }).catch(() => {});
      }
    } catch (e) {}
  },

  update(data) {
    try {
      const arr = Array.isArray(data) ? data : (data && data.result) || [];
      const first = Array.isArray(arr) ? arr[0] : null;
      if (!first) { this.state.text && this.state.text.setProperty(prop.TEXT, "Sem leitura"); return; }
      const sgv = first.sgv || "?";
      const dir = first.direction || "";
      const ageMin = first.date ? Math.max(0, Math.round((Date.now() - first.date) / 60000)) : "?";
      const line = `${sgv} mg/dL ${dir}\n${ageMin} min`;
      this.state.text && this.state.text.setProperty(prop.TEXT, line);
    } catch (e) {}
  }
});
