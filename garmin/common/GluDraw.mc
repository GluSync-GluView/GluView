using Toybox.Graphics as Gfx;
using Toybox.Lang;
using Toybox.Math;
using Toybox.System;
using Toybox.Time;
using Toybox.Time.Gregorian;
using Toybox.ActivityMonitor;

// Plano 21, F7.5 — desenho compartilhado do app e do mostrador Garmin (pedido do Felipe, 21/11/2026:
// gráfico igual ao da Mi Band e pelo menos 3 estilos de mostrador desenhados no código).
//
// Gráfico = o mesmo do visor da Mi Band (GraphLayout.watch / GraphPalette.WATCH_* do app): fundo
// preto, faixa de urgência abaixo do "muito baixo", limiares baixo/alto tracejados com o valor,
// faixa 100/150 tracejada neutra, linha cinza e pontos coloridos pela faixa (o último maior),
// escala fixa 40–300 mg/dL, janela das últimas 3 h.
//
// Dados = /watch.json do servidor local do GluView: `pts` = [idadeMin, mg/dL, ...] do mais velho ao
// mais novo, idades pela hora do CELULAR; aqui só somamos o tempo passado desde a chegada (rxSec),
// então hora errada no relógio não engana. Leitura com 20 min ou mais, ou falha na busca: o valor
// some e aparece "Sem sinal" (o histórico continua no gráfico, cada ponto na sua hora).

module GluDraw {
    const STALE_MIN = 20;
    const WINDOW_MIN = 180;
    const GAP_MIN = 15;
    const MIN_SGV = 40;
    const MAX_SGV = 300;
    const URL = "http://127.0.0.1:17580/watch.json";

    // Cores do visor da Mi Band (ARGB do app compostos sobre preto, porque nem todo Garmin tem alfa).
    const C_BAND = 0x3A1515;       // 0x33EF5350
    const C_HIPO = 0xEF5350;
    const C_HIPER = 0xFFA726;
    const C_SECONDARY = 0x666666;  // 0x66FFFFFF
    const C_LINE = 0x505050;       // 0x50FFFFFF
    const C_URGENT = 0xEF5350;
    const C_OUT = 0xFF7043;
    const C_IN = 0x66BB6A;
    const C_GREY = 0xB0BEC5;

    const STYLE_CLASSIC = 0;
    const STYLE_FOCUS = 1;
    const STYLE_COMPLETE = 2;
    const STYLE_COUNT = 3;

    function num(v) {
        if (v == null) { return null; }
        if (v instanceof Lang.Number) { return v; }
        if (v instanceof Lang.Long || v instanceof Lang.Float || v instanceof Lang.Double) { return v.toNumber(); }
        return null;
    }

    function thr(json, key, def) {
        if (json == null) { return def; }
        var th = json["th"];
        if (th instanceof Lang.Dictionary) {
            var v = num(th[key]);
            if (v != null) { return v; }
        }
        return def;
    }

    // Minutos desde a chegada da resposta (pelo relógio, só diferença).
    function sinceRx(rxSec) {
        if (rxSec == null) { return 0; }
        var d = (Time.now().value() - rxSec) / 60;
        return d < 0 ? 0 : d;
    }

    function ageMin(json, rxSec) {
        if (json == null) { return null; }
        var a = num(json["age"]);
        if (a == null) { return null; }
        return a + sinceRx(rxSec);
    }

    function fresh(json, rxSec, linkOk) {
        var a = ageMin(json, rxSec);
        return linkOk && a != null && a < STALE_MIN && json["txt"] != null;
    }

    function zoneColor(sgv, json) {
        var ul = thr(json, "ul", 55);
        var l = thr(json, "l", 70);
        var h = thr(json, "h", 180);
        var uh = thr(json, "uh", 250);
        if (sgv <= ul) { return C_URGENT; }
        if (sgv <= l) { return C_OUT; }
        if (sgv >= uh) { return C_URGENT; }
        if (sgv >= h) { return C_OUT; }
        return C_IN;
    }

    function yOf(sgv, y, h) {
        var s = sgv < MIN_SGV ? MIN_SGV : (sgv > MAX_SGV ? MAX_SGV : sgv);
        return y + h - (h * (s - MIN_SGV)) / (MAX_SGV - MIN_SGV);
    }

    function dashedH(dc as Gfx.Dc, x1, x2, y, color, pen, dash) {
        dc.setColor(color, Gfx.COLOR_TRANSPARENT);
        dc.setPenWidth(pen);
        var x = x1;
        while (x < x2) {
            var e = x + dash;
            if (e > x2) { e = x2; }
            dc.drawLine(x, y, e, y);
            x = x + dash * 2 - dash / 6;
        }
        dc.setPenWidth(1);
    }

    // Gráfico de glicemia no retângulo (x, y, w, h).
    function drawChart(dc as Gfx.Dc, x, y, w, h, json, rxSec, labels) {
        var k = dc.getWidth() / 260.0;          // fēnix 7 (260 px) = 1
        if (k < 0.8) { k = 0.8; }
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_BLACK);
        dc.fillRectangle(x, y, w, h);
        var ul = thr(json, "ul", 55);
        var l = thr(json, "l", 70);
        var hi = thr(json, "h", 180);
        var yb = yOf(ul, y, h);
        dc.setColor(C_BAND, C_BAND);
        dc.fillRectangle(x, yb, w, y + h - yb);
        var dash = (6 * k).toNumber();
        var pen = (1.5 * k + 0.5).toNumber();
        dashedH(dc, x, x + w, yOf(100, y, h), C_SECONDARY, 1, dash);
        dashedH(dc, x, x + w, yOf(150, y, h), C_SECONDARY, 1, dash);
        dashedH(dc, x, x + w, yOf(l, y, h), C_HIPO, pen, dash);
        dashedH(dc, x, x + w, yOf(hi, y, h), C_HIPER, pen, dash);
        if (labels) {
            var fh = dc.getFontHeight(Gfx.FONT_XTINY);
            dc.setColor(C_HIPO, Gfx.COLOR_TRANSPARENT);
            dc.drawText(x + 2, yOf(l, y, h) - fh, Gfx.FONT_XTINY, l.toString(), Gfx.TEXT_JUSTIFY_LEFT);
            dc.setColor(C_HIPER, Gfx.COLOR_TRANSPARENT);
            dc.drawText(x + 2, yOf(hi, y, h) - fh, Gfx.FONT_XTINY, hi.toString(), Gfx.TEXT_JUSTIFY_LEFT);
        }
        if (json == null) { return; }
        var pts = json["pts"];
        if (!(pts instanceof Lang.Array) || pts.size() < 2) { return; }
        var shift = sinceRx(rxSec);
        var n = pts.size() / 2;
        var xs = new [n];
        var ys = new [n];
        var vs = new [n];
        var ages = new [n];
        var count = 0;
        for (var i = 0; i < n; i++) {
            var age = num(pts[2 * i]);
            var sgv = num(pts[2 * i + 1]);
            if (age == null || sgv == null) { continue; }
            age = age + shift;
            if (age > WINDOW_MIN) { continue; }
            xs[count] = x + w - (w * age) / WINDOW_MIN;
            ys[count] = yOf(sgv, y, h);
            vs[count] = sgv;
            ages[count] = age;
            count++;
        }
        dc.setColor(C_LINE, Gfx.COLOR_TRANSPARENT);
        dc.setPenWidth(k > 1.4 ? 2 : 1);
        for (var i = 1; i < count; i++) {
            if (ages[i - 1] - ages[i] <= GAP_MIN) { dc.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i]); }
        }
        dc.setPenWidth(1);
        for (var i = 0; i < count; i++) {
            var r = (i == count - 1) ? 3.5 * k : 2.2 * k;
            dc.setColor(zoneColor(vs[i], json), Gfx.COLOR_TRANSPARENT);
            dc.fillCircle(xs[i], ys[i], r < 2 ? 2 : r);
        }
    }

    // Ângulo da seta (0 = estável, 90 = subindo) e se é dupla; null = sem seta.
    function arrowDeg(d) {
        if (d == null) { return null; }
        if (d.equals("DoubleUp") || d.equals("SingleUp")) { return 90; }
        if (d.equals("FortyFiveUp")) { return 45; }
        if (d.equals("Flat")) { return 0; }
        if (d.equals("FortyFiveDown")) { return -45; }
        if (d.equals("SingleDown") || d.equals("DoubleDown")) { return -90; }
        return null;
    }

    function arrowCount(d) {
        return (d != null && (d.equals("DoubleUp") || d.equals("DoubleDown"))) ? 2 : 1;
    }

    // Seta desenhada (as fontes do relógio não têm →↗↘; em texto saía "◆?").
    function drawArrow(dc as Gfx.Dc, cx, cy, s, deg) {
        var t = s / 7.0;
        var wh = s / 2.6;
        var hh = s / 2.0;
        var pts = [[-hh, -t], [0.0, -t], [0.0, -wh], [hh, 0.0], [0.0, wh], [0.0, t], [-hh, t]];
        var a = deg * Math.PI / 180.0;
        var c = Math.cos(a);
        var n = Math.sin(a);
        var out = [];
        for (var i = 0; i < pts.size(); i++) {
            var px = pts[i][0];
            var py = pts[i][1];
            out.add([cx + px * c - py * n, cy - (px * n + py * c)]);
        }
        dc.fillPolygon(out);
    }

    // Valor + seta centralizados em cx; devolve a altura usada.
    function drawValue(dc as Gfx.Dc, cx, top, font, txt, dir, color) {
        var fh = dc.getFontHeight(font);
        var tw = dc.getTextWidthInPixels(txt, font);
        var deg = arrowDeg(dir);
        var cnt = deg == null ? 0 : arrowCount(dir);
        var s = (fh * 0.5).toNumber();
        if (s > 44) { s = 44; }
        var total = tw + (cnt > 0 ? s / 3 + cnt * s : 0);
        var x = cx - total / 2;
        dc.setColor(color, Gfx.COLOR_TRANSPARENT);
        dc.drawText(x, top, font, txt, Gfx.TEXT_JUSTIFY_LEFT);
        for (var i = 0; i < cnt; i++) {
            drawArrow(dc, x + tw + s / 3 + i * s + s / 2, top + fh / 2, s, deg);
        }
        return fh;
    }

    function clockText() {
        var t = System.getClockTime();
        var h = t.hour;
        if (!System.getDeviceSettings().is24Hour) { h = h % 12; if (h == 0) { h = 12; } }
        return h.format("%02d") + ":" + t.min.format("%02d");
    }

    function dateText() {
        var i = Gregorian.info(Time.now(), Time.FORMAT_MEDIUM);
        return i.day_of_week.toUpper() + " " + i.day.format("%02d") + "/" + Gregorian.info(Time.now(), Time.FORMAT_SHORT).month.format("%02d");
    }

    function ageText(json, rxSec) {
        var a = ageMin(json, rxSec);
        return a == null ? "" : a.toString() + " min";
    }

    function deltaAge(json, rxSec, isFresh) {
        var age = ageText(json, rxSec);
        var d = (isFresh && json["dtxt"] != null) ? json["dtxt"].toString() : null;
        if (d == null) { return age; }
        return age.length() == 0 ? d : d + "  ·  " + age;
    }

    // Valor principal: glicemia + seta, "Sem sinal" ou "Sem dados".
    function drawMain(dc as Gfx.Dc, cx, top, numFont, json, rxSec, linkOk) {
        if (fresh(json, rxSec, linkOk)) {
            return drawValue(dc, cx, top, numFont, json["txt"].toString(), json["dir"], zoneColor(num(json["sgv"]), json));
        }
        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
        var f = Gfx.FONT_MEDIUM;
        var fh = dc.getFontHeight(f);
        var hNum = dc.getFontHeight(numFont);
        dc.drawText(cx, top + (hNum - fh) / 2, f, json == null ? "Sem dados" : "Sem sinal", Gfx.TEXT_JUSTIFY_CENTER);
        return hNum;
    }

    // Largura útil numa altura y: tela redonda corta os cantos (corda do círculo), quadrada não.
    function usableW(dc as Gfx.Dc, y) {
        var w = dc.getWidth();
        if (System.getDeviceSettings().screenShape != System.SCREEN_SHAPE_ROUND) { return w; }
        var r = w / 2.0;
        var d = y - dc.getHeight() / 2.0;
        var c = r * r - d * d;
        return c <= 0 ? 0 : 2 * Math.sqrt(c);
    }

    // Gráfico centralizado entre top e top+ch, na largura que cabe nas duas bordas (até maxFrac da tela).
    function chartIn(dc as Gfx.Dc, top, ch, maxFrac, json, rxSec, labels) {
        var w = dc.getWidth();
        var cw = usableW(dc, top);
        var cb = usableW(dc, top + ch);
        if (cb < cw) { cw = cb; }
        cw = cw - 10;
        if (cw > w * maxFrac) { cw = w * maxFrac; }
        if (cw < w * 0.3 || ch < 12) { return; }
        drawChart(dc, (w - cw) / 2, top, cw, ch, json, rxSec, labels);
    }

    // Os 3 estilos (como os modelos da Mi Band: Clássico, Glicemia em destaque e Completo). Tudo é
    // posicionado pela altura real das fontes de cada relógio e o gráfico ocupa o espaço que sobra.
    function drawFace(dc as Gfx.Dc, style, json, rxSec, linkOk) {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var cx = w / 2;
        var bottom = h * 0.90;
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_BLACK);
        dc.clear();
        var isFresh = fresh(json, rxSec, linkOk);
        var fS = dc.getFontHeight(Gfx.FONT_SMALL);
        var fX = dc.getFontHeight(Gfx.FONT_XTINY);
        if (style == STYLE_FOCUS) {
            // Glicemia em destaque: hora no topo, glicemia grande ao centro, mini-gráfico embaixo.
            var y = h * 0.07;
            dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
            dc.drawText(cx, y, Gfx.FONT_NUMBER_MILD, clockText(), Gfx.TEXT_JUSTIFY_CENTER);
            y = y + dc.getFontHeight(Gfx.FONT_NUMBER_MILD);
            y = y + drawMain(dc, cx, y, Gfx.FONT_NUMBER_HOT, json, rxSec, linkOk);
            dc.setColor(C_GREY, Gfx.COLOR_TRANSPARENT);
            dc.drawText(cx, y, Gfx.FONT_SMALL, deltaAge(json, rxSec, isFresh), Gfx.TEXT_JUSTIFY_CENTER);
            y = y + fS + 3;
            var ch = bottom - y;
            if (ch > h * 0.20) { ch = h * 0.20; }
            chartIn(dc, y, ch, 0.76, json, rxSec, false);
        } else if (style == STYLE_COMPLETE) {
            // Completo: dia/data, hora, glicemia, gráfico com limiares, variação/idade, passos e bateria.
            var y = h * 0.07;
            dc.setColor(C_GREY, Gfx.COLOR_TRANSPARENT);
            dc.drawText(cx, y, Gfx.FONT_XTINY, dateText(), Gfx.TEXT_JUSTIFY_CENTER);
            y = y + fX;
            dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
            dc.drawText(cx, y, Gfx.FONT_NUMBER_MILD, clockText(), Gfx.TEXT_JUSTIFY_CENTER);
            y = y + dc.getFontHeight(Gfx.FONT_NUMBER_MILD);
            y = y + drawMain(dc, cx, y, Gfx.FONT_NUMBER_MEDIUM, json, rxSec, linkOk) + 2;
            var lines = 2;
            var ch = bottom - y - lines * fX - 4;
            if (ch < h * 0.16) { lines = 1; ch = bottom - y - fX - 4; }
            if (ch > h * 0.26) { ch = h * 0.26; }
            chartIn(dc, y, ch, 0.84, json, rxSec, true);
            y = y + ch + 2;
            dc.setColor(C_GREY, Gfx.COLOR_TRANSPARENT);
            var stats = System.getSystemStats();
            var info = ActivityMonitor.getInfo();
            var steps = (info != null && info.steps != null) ? info.steps.toString() + " passos" : "";
            var bat = stats.battery.toNumber().toString() + "%";
            if (lines == 2) {
                dc.drawText(cx, y, Gfx.FONT_XTINY, deltaAge(json, rxSec, isFresh), Gfx.TEXT_JUSTIFY_CENTER);
                dc.drawText(cx, y + fX, Gfx.FONT_XTINY, steps + (steps.length() > 0 ? "  ·  " : "") + bat, Gfx.TEXT_JUSTIFY_CENTER);
            } else {
                dc.drawText(cx, y, Gfx.FONT_XTINY, deltaAge(json, rxSec, isFresh) + "  ·  " + bat, Gfx.TEXT_JUSTIFY_CENTER);
            }
        } else {
            // Clássico (WatchDrip): hora no topo, gráfico grande, glicemia e variação embaixo.
            var y = h * 0.07;
            dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
            dc.drawText(cx, y, Gfx.FONT_MEDIUM, clockText(), Gfx.TEXT_JUSTIFY_CENTER);
            y = y + dc.getFontHeight(Gfx.FONT_MEDIUM) + 2;
            var below = dc.getFontHeight(Gfx.FONT_NUMBER_MEDIUM) + fS + 6;
            var ch = bottom - y - below;
            if (ch > h * 0.38) { ch = h * 0.38; }
            chartIn(dc, y, ch, 0.90, json, rxSec, true);
            y = y + ch + 4;
            y = y + drawMain(dc, cx, y, Gfx.FONT_NUMBER_MEDIUM, json, rxSec, linkOk);
            dc.setColor(C_GREY, Gfx.COLOR_TRANSPARENT);
            dc.drawText(cx, y, Gfx.FONT_SMALL, deltaAge(json, rxSec, isFresh), Gfx.TEXT_JUSTIFY_CENTER);
        }
    }
}
