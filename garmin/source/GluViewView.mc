using Toybox.Application;
using Toybox.Attention;
using Toybox.Application.Storage;
using Toybox.WatchUi;
using Toybox.Graphics;
using Toybox.Communications;
using Toybox.Lang;
using Toybox.System;
using Toybox.Time;
using Toybox.Timer;

// Plano 21, F7.5: app Garmin Connect IQ (SDK 9.3, testado no simulador em 21/11/2026).
// Lê o /watch.json do servidor web local do GluView (F2) a cada 60 s — o Garmin Connect do celular
// faz a requisição; 127.0.0.1 é o próprio celular. Desenho em common/GluDraw.mc, o MESMO do mostrador
// (pasta face/): gráfico igual ao da Mi Band e 3 estilos; tocar na tela (ou o botão de seleção)
// troca o estilo, que fica salvo.
//
// Histórico da auditoria/testes de 21/11/2026: busca periódica, "Sem sinal" com leitura de 20 min ou
// mais ou falha na busca, idade sem estouro de 32 bits, seta desenhada (a fonte não tem →↗↘) e
// layout pela altura das fontes (no Venu 3 as linhas se sobrepunham).
//
// Plano 21, F7.1 (22/11/2026): pede `app=garmin`; quando o /watch.json traz um `alert` de id novo, vibra no
// padrão escolhido no celular (`v` = liga, pausa, … ms; até 8 trechos, limite do Attention.vibrate) e mostra
// a faixa vermelha com o título por 2 min. O id visto fica salvo: reabrir o app não vibra de novo.

class GluViewView extends WatchUi.View {
    var _json = null;      // último /watch.json (Dictionary)
    var _rxSec = null;     // hora do relógio (s) em que chegou
    var _linkOk = false;   // a última busca deu certo
    var _style = 0;
    var _timer = null;
    var _alertId = null;     // id do último alerta já vibrado (texto)
    var _alertText = null;
    var _alertUntil = 0;     // s do relógio até quando a faixa aparece

    function initialize() {
        View.initialize();
        var s = Storage.getValue("style");
        _style = (s instanceof Lang.Number) ? s % GluDraw.STYLE_COUNT : 0;
        var a = Storage.getValue("alertId");
        _alertId = (a instanceof Lang.String) ? a : null;
    }

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

    function nextStyle() as Void {
        _style = (_style + 1) % GluDraw.STYLE_COUNT;
        Storage.setValue("style", _style);
        WatchUi.requestUpdate();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        GluDraw.drawFace(dc, _style, _json, _rxSec, _linkOk);
        if (_alertText != null && Time.now().value() < _alertUntil) {
            var w = dc.getWidth();
            var h = dc.getHeight();
            var bh = h * 18 / 100;
            dc.setColor(GluDraw.C_HIPO, GluDraw.C_HIPO);
            dc.fillRectangle(0, 0, w, bh);
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            dc.drawText(w / 2, bh * 62 / 100, Graphics.FONT_XTINY, _alertText, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }

    // F7.1: alerta novo no /watch.json → vibra no padrão escolhido e mostra a faixa.
    function handleAlert(data as Lang.Dictionary) as Void {
        var a = data["alert"];
        if (!(a instanceof Lang.Dictionary) || a["id"] == null) { return; }
        var id = a["id"].toString();
        if (id.equals(_alertId)) { return; }
        _alertId = id;
        Storage.setValue("alertId", id);
        var t = a["t"];
        _alertText = (t instanceof Lang.String) ? t : "GluView";
        _alertUntil = Time.now().value() + 120;
        System.println("GluView alerta " + id + " " + _alertText);
        vibrate(a["v"]);
    }

    function vibrate(v) as Void {
        if (!(Attention has :vibrate) || !(v instanceof Lang.Array) || v.size() == 0) { return; }
        var n = v.size() < 8 ? v.size() : 8;
        var profiles = [];
        for (var i = 0; i < n; i++) {
            var ms = GluDraw.num(v[i]);
            if (ms == null) { ms = 0; }
            profiles.add(new Attention.VibeProfile(i % 2 == 0 ? 100 : 0, ms));
        }
        Attention.vibrate(profiles);
        System.println("GluView vibra " + n + " trechos");
    }

    function requestGlucose() as Void {
        var params = { "minutes" => "180", "step" => "3", "app" => "garmin" };
        var options = { :method => Communications.HTTP_REQUEST_METHOD_GET, :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON };
        Communications.makeWebRequest(GluDraw.URL, params, options, method(:onReceive));
    }

    function onReceive(responseCode as Lang.Number, data as Lang.Dictionary or Lang.String or Null) as Void {
        System.println("GluView resposta=" + responseCode);
        if (responseCode == 200 && data instanceof Lang.Dictionary) {
            _json = data;
            _rxSec = Time.now().value();
            _linkOk = true;
            handleAlert(data);
        } else {
            // Falha: o valor some ("Sem sinal"); o histórico fica no gráfico, cada ponto na sua hora.
            _linkOk = false;
        }
        WatchUi.requestUpdate();
    }
}

class GluViewDelegate extends WatchUi.BehaviorDelegate {
    var _view;
    function initialize(view) { BehaviorDelegate.initialize(); _view = view; }
    function onSelect() as Lang.Boolean { _view.nextStyle(); return true; }
    function onTap(evt as WatchUi.ClickEvent) as Lang.Boolean { _view.nextStyle(); return true; }
}

class GluViewApp extends Application.AppBase {
    function initialize() { AppBase.initialize(); }
    function getInitialView() {
        var v = new GluViewView();
        return [ v, new GluViewDelegate(v) ];
    }
}
