using Toybox.Application;
using Toybox.WatchUi;
using Toybox.Graphics;
using Toybox.Communications;
using Toybox.Lang;
using Toybox.System;

// Plano 21, F7.5: Garmin Connect IQ (Sem teste em simulador/hardware).
// Lê glicemia do servidor web local do GluView (F2): http://127.0.0.1:17580/sgv.json?count=1
// Exibe valor, direção e idade; se a leitura ficar velha, a idade cresce (não mostra como atual).

class GluViewView extends WatchUi.View {
    var _sgv = "--";
    var _dir = "";
    var _age = "?";

    function initialize() { View.initialize(); }

    function onLayout(dc as Dc) as Void { }

    function onShow() as Void { requestGlucose(); }

    function onUpdate(dc as Dc) as Void {
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(dc.getWidth()/2, dc.getHeight()/2 - 30, Graphics.FONT_LARGE, _sgv + " " + _dir, Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(dc.getWidth()/2, dc.getHeight()/2 + 20, Graphics.FONT_SMALL, _age + " min", Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(dc.getWidth()/2, dc.getHeight()/2 + 50, Graphics.FONT_XTINY, "GluView (local)", Graphics.TEXT_JUSTIFY_CENTER);
    }

    function requestGlucose() as Void {
        var url = "http://127.0.0.1:17580/sgv.json";
        var params = { "count" => "1" };
        var options = { :method => Communications.HTTP_REQUEST_METHOD_GET, :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON };
        Communications.makeWebRequest(url, params, options, method(:onReceive));
    }

    function onReceive(responseCode as Number, data as Dictionary or String or Null) as Void {
        if (responseCode == 200 and data instanceof Lang.Array and data.size() > 0) {
            var e = data[0] as Lang.Dictionary;
            var sgv = e["sgv"];
            var dir = e["direction"];
            var date = e["date"];
            if (sgv != null) { _sgv = sgv.toString(); }
            if (dir != null) { _dir = dir.toString(); }
            if (date != null) {
                var now = System.getTimer(); // ms since boot not epoch; fallback: use device time
                var epochNow = Time.now().value() * 1000;
                var ageMs = epochNow - (date as Lang.Number);
                if (ageMs < 0) { ageMs = 0; }
                _age = (ageMs / 60000).toString();
            }
        } else {
            _age = "?";
        }
        WatchUi.requestUpdate();
    }
}

class GluViewApp extends Application.AppBase {
    function initialize() { AppBase.initialize(); }
    function getInitialView() { return [ new GluViewView() ] as Lang.Array<WatchUi.Views or WatchUi.InputDelegates>; }
}
