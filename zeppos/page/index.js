// GluView no Zepp OS (plano 21, F7.3) — página do relógio.
// Pede a glicemia ao serviço lateral a cada 60 s e mostra valor, seta (imagem) e idade; a idade é
// redesenhada a cada 30 s. Leitura com 20 min ou mais, ou falha na busca: "Sem sinal" (nunca deixa
// um valor velho parecendo atual). Lógica em utils/glucose.js (testada com Node).
import * as hmUI from "@zos/ui";
import { BasePage } from "@zeppos/zml/base-page";
import { VALUE_TEXT, ARROW_IMG, AGE_TEXT } from "zosLoader:./index.[pf].layout.js";
import { display } from "../utils/glucose";

let valueWidget = null;
let arrowWidget = null;
let ageWidget = null;
let fetchTimer = null;
let drawTimer = null;
let last = null;
let lastRx = 0;

Page(
  BasePage({
    state: {},
    build() {
      valueWidget = hmUI.createWidget(hmUI.widget.TEXT, { ...VALUE_TEXT, text: "GluView" });
      arrowWidget = hmUI.createWidget(hmUI.widget.IMG, { ...ARROW_IMG, src: "arrow/flat.png" });
      arrowWidget.setProperty(hmUI.prop.VISIBLE, false);
      ageWidget = hmUI.createWidget(hmUI.widget.TEXT, { ...AGE_TEXT, text: "..." });
      this.refresh();
      fetchTimer = setInterval(() => this.refresh(), 60000);
      drawTimer = setInterval(() => this.draw(), 30000);
    },
    onDestroy() {
      if (fetchTimer) clearInterval(fetchTimer);
      if (drawTimer) clearInterval(drawTimer);
      fetchTimer = null;
      drawTimer = null;
    },
    refresh() {
      this.request({ method: "GET_GLUCOSE" })
        .then((data) => this.receive(data))
        .catch(() => this.receive(null));
    },
    receive(data) {
      last = data;
      lastRx = Date.now();
      this.draw();
    },
    draw() {
      const d = display(last, lastRx, Date.now());
      valueWidget && valueWidget.setProperty(hmUI.prop.TEXT, d.value);
      ageWidget && ageWidget.setProperty(hmUI.prop.TEXT, d.age);
      if (arrowWidget) {
        if (d.arrow) arrowWidget.setProperty(hmUI.prop.SRC, d.arrow);
        arrowWidget.setProperty(hmUI.prop.VISIBLE, !!d.arrow);
      }
    },
  })
);
