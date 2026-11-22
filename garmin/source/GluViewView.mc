using Toybox.Application;
using Toybox.WatchUi;
using Toybox.Graphics;
using Toybox.Communications;
using Toybox.Lang;
using Toybox.Math;
using Toybox.System;
using Toybox.Time;
using Toybox.Timer;

// Plano 21, F7.5: Garmin Connect IQ. Compilado no SDK 9.3 e testado no simulador (21/11/2026).
// Lê a glicemia do servidor web local do GluView (F2): http://127.0.0.1:17580/pebble?count=1
// (o Garmin Connect do celular faz a requisição; 127.0.0.1 é o próprio celular). A idade sai da
// hora do CELULAR (status.now − datetime), então relógio de pulso com hora errada não engana.
//
// Auditoria 21/11/2026 (Claude):
//  - busca a cada 60 s (antes só uma vez ao abrir) e recalcula a idade a cada desenho;
//  - leitura com 20 min ou mais, ou falha na busca: o valor SOME e aparece "Sem sinal"
//    (antes o último valor ficava na tela como se fosse atual);
//  - idade em SEGUNDOS (Time.now().value() * 1000 estourava o Number de 32 bits) e o
//    `using Toybox.Time` que faltava.
//  - seta DESENHADA (polígono girado): as fontes do relógio não têm →↗↘ e o simulador mostrava "◆?";
//  - linhas posicionadas pela altura das fontes (no Venu 3 se sobrepunham).

class GluViewView extends WatchUi.View {
    const STALE_MIN = 20;
    var _sgv = null;          // texto do valor, ou null
    var _dirDeg = null;       // ângulo da seta (0 = estável, 90 = subindo), ou null sem seta
    var _dirCount = 1;        // 2 = seta dupla
    var _ageAtRxMin = null;   // idade da leitura (min) no momento em que chegou, pela hora do CELULAR
    var _rxSec = null;        // hora do relógio (s) em que a resposta chegou
    var _timer = null;

    function initialize() { View.initialize(); }

    function onLayout(dc as Graphics.Dc) as Void { }

    function onShow() as Void {
        _timer = new Timer.Timer();
        _timer.start(method(:onTick), 60000, true);
        requestGlucose();
    }

    function onHide() as Void {
        if (_timer != null) { _timer.stop(); _timer = null; }
    }

    function onTick() as Void { requestGlucose(); }

    function ageMin() {
        if (_ageAtRxMin == null || _rxSec == null) { return null; }
        var age = _ageAtRxMin + (Time.now().value() - _rxSec) / 60;
        return age < 0 ? 0 : age;
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        var age = ageMin();
        var fresh = _sgv != null && age != null && age < STALE_MIN;
        var sub = age == null ? "" : (age.toString() + " min");
        // Linhas empilhadas pela altura REAL das fontes (no Venu 3 as distâncias fixas em pixels sobrepunham).
        var hL = dc.getFontHeight(Graphics.FONT_LARGE);
        var hS = dc.getFontHeight(Graphics.FONT_SMALL);
        var hT = dc.getFontHeight(Graphics.FONT_XTINY);
        var top = (dc.getHeight() - (hL + hS + hT)) / 2;
        if (fresh) {
            var fh = hL;
            var tw = dc.getTextWidthInPixels(_sgv, Graphics.FONT_LARGE);
            var s = (fh * 0.55).toNumber();
            var arrows = _dirDeg == null ? 0 : _dirCount;
            var total = tw + (arrows > 0 ? s / 3 + arrows * s : 0);
            var x = dc.getWidth() / 2 - total / 2;
            dc.drawText(x, top, Graphics.FONT_LARGE, _sgv, Graphics.TEXT_JUSTIFY_LEFT);
            for (var i = 0; i < arrows; i++) {
                drawArrow(dc, x + tw + s / 3 + i * s + s / 2, top + fh / 2, s, _dirDeg);
            }
        } else {
            dc.drawText(dc.getWidth() / 2, top, Graphics.FONT_LARGE, "Sem sinal", Graphics.TEXT_JUSTIFY_CENTER);
        }
        dc.drawText(dc.getWidth() / 2, top + hL, Graphics.FONT_SMALL, sub, Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(dc.getWidth() / 2, top + hL + hS, Graphics.FONT_XTINY, "GluView (local)", Graphics.TEXT_JUSTIFY_CENTER);
    }

    // /pebble (raiz = objeto): o Connect IQ entrega a resposta como Dictionary; o /sgv.json tem raiz
    // lista e nunca chegaria aqui (o compilador avisava "Statement is not reachable" no instanceof Array).
    function requestGlucose() as Void {
        var url = "http://127.0.0.1:17580/pebble";
        var params = { "count" => "1" };
        var options = { :method => Communications.HTTP_REQUEST_METHOD_GET, :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON };
        Communications.makeWebRequest(url, params, options, method(:onReceive));
    }

    function onReceive(responseCode as Lang.Number, data as Lang.Dictionary or Lang.String or Null) as Void {
        System.println("GluView resposta=" + responseCode);
        var ok = false;
        if (responseCode == 200 && data instanceof Lang.Dictionary) {
            var bgs = data["bgs"];
            var status = data["status"];
            if (bgs instanceof Lang.Array && bgs.size() > 0 && status instanceof Lang.Array && status.size() > 0) {
                var e = bgs[0] as Lang.Dictionary;
                var st = status[0] as Lang.Dictionary;
                var sgv = e["sgv"];
                var dir = e["direction"];
                var when = e["datetime"];
                var now = st["now"];
                if (sgv != null && when != null && now != null) {
                    _sgv = sgv.toString();
                    setDirection(dir != null ? dir.toString() : "");
                    var ageMs = now.toLong() - when.toLong();
                    _ageAtRxMin = ageMs <= 0 ? 0 : (ageMs / 60000l).toNumber();
                    _rxSec = Time.now().value();
                    ok = true;
                }
            }
        }
        if (!ok) {
            // Falha na busca: não mantém valor antigo como se fosse atual.
            _sgv = null;
            _dirDeg = null;
        }
        WatchUi.requestUpdate();
    }

    function setDirection(d as Lang.String) as Void {
        _dirCount = 1;
        if (d.equals("DoubleUp")) { _dirDeg = 90; _dirCount = 2; }
        else if (d.equals("SingleUp")) { _dirDeg = 90; }
        else if (d.equals("FortyFiveUp")) { _dirDeg = 45; }
        else if (d.equals("Flat")) { _dirDeg = 0; }
        else if (d.equals("FortyFiveDown")) { _dirDeg = -45; }
        else if (d.equals("SingleDown")) { _dirDeg = -90; }
        else if (d.equals("DoubleDown")) { _dirDeg = -90; _dirCount = 2; }
        else { _dirDeg = null; }
    }

    // Seta de tamanho s centrada em (cx, cy), apontando para deg graus (anti-horário, 0 = direita).
    function drawArrow(dc as Graphics.Dc, cx, cy, s, deg) as Void {
        var t = s / 7.0;     // meia espessura da haste
        var w = s / 2.6;     // meia largura da ponta
        var h = s / 2.0;
        var pts = [[-h, -t], [0.0, -t], [0.0, -w], [h, 0.0], [0.0, w], [0.0, t], [-h, t]];
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
}

class GluViewApp extends Application.AppBase {
    function initialize() { AppBase.initialize(); }
    function getInitialView() { return [ new GluViewView() ]; }
}
