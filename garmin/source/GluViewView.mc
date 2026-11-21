using Toybox.Application;
using Toybox.WatchUi;
using Toybox.Graphics;
using Toybox.Communications;
using Toybox.Lang;
using Toybox.System;
using Toybox.Time;
using Toybox.Timer;

// Plano 21, F7.5: Garmin Connect IQ (Sem teste em simulador/hardware).
// Lê a glicemia do servidor web local do GluView (F2): http://127.0.0.1:17580/sgv.json?count=1
// (o Garmin Connect do celular faz a requisição; 127.0.0.1 é o próprio celular).
//
// Auditoria 21/11/2026 (Claude):
//  - busca a cada 60 s (antes só uma vez ao abrir) e recalcula a idade a cada desenho;
//  - leitura com 20 min ou mais, ou falha na busca: o valor SOME e aparece "Sem sinal"
//    (antes o último valor ficava na tela como se fosse atual);
//  - idade em SEGUNDOS (Time.now().value() * 1000 estourava o Number de 32 bits) e o
//    `using Toybox.Time` que faltava.

class GluViewView extends WatchUi.View {
    const STALE_MIN = 20;
    var _sgv = null;          // texto do valor, ou null
    var _dir = "";
    var _dateSec = null;      // hora da medição em segundos (epoch), ou null
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
        if (_dateSec == null) { return null; }
        var age = (Time.now().value() - _dateSec) / 60;
        return age < 0 ? 0 : age;
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        var age = ageMin();
        var fresh = _sgv != null && age != null && age < STALE_MIN;
        var main = fresh ? (_sgv + " " + _dir) : "Sem sinal";
        var sub = age == null ? "" : (age.toString() + " min");
        dc.drawText(dc.getWidth() / 2, dc.getHeight() / 2 - 30, Graphics.FONT_LARGE, main, Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(dc.getWidth() / 2, dc.getHeight() / 2 + 20, Graphics.FONT_SMALL, sub, Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(dc.getWidth() / 2, dc.getHeight() / 2 + 50, Graphics.FONT_XTINY, "GluView (local)", Graphics.TEXT_JUSTIFY_CENTER);
    }

    function requestGlucose() as Void {
        var url = "http://127.0.0.1:17580/sgv.json";
        var params = { "count" => "1" };
        var options = { :method => Communications.HTTP_REQUEST_METHOD_GET, :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON };
        Communications.makeWebRequest(url, params, options, method(:onReceive));
    }

    function onReceive(responseCode as Lang.Number, data as Lang.Dictionary or Lang.String or Null) as Void {
        if (responseCode == 200 && data instanceof Lang.Array && data.size() > 0) {
            var e = data[0] as Lang.Dictionary;
            var sgv = e["sgv"];
            var dir = e["direction"];
            var date = e["date"];
            _sgv = sgv != null ? sgv.toString() : null;
            _dir = dir != null ? arrow(dir.toString()) : "";
            _dateSec = date != null ? (date.toLong() / 1000l).toNumber() : null;
        } else {
            // Falha na busca: não mantém valor antigo como se fosse atual.
            _sgv = null;
            _dir = "";
        }
        WatchUi.requestUpdate();
    }

    function arrow(d as Lang.String) as Lang.String {
        if (d.equals("DoubleUp")) { return "↑↑"; }
        if (d.equals("SingleUp")) { return "↑"; }
        if (d.equals("FortyFiveUp")) { return "↗"; }
        if (d.equals("Flat")) { return "→"; }
        if (d.equals("FortyFiveDown")) { return "↘"; }
        if (d.equals("SingleDown")) { return "↓"; }
        if (d.equals("DoubleDown")) { return "↓↓"; }
        return "";
    }
}

class GluViewApp extends Application.AppBase {
    function initialize() { AppBase.initialize(); }
    function getInitialView() { return [ new GluViewView() ]; }
}
