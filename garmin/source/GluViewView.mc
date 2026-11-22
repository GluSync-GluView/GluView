using Toybox.Application;
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

class GluViewView extends WatchUi.View {
    var _json = null;      // último /watch.json (Dictionary)
    var _rxSec = null;     // hora do relógio (s) em que chegou
    var _linkOk = false;   // a última busca deu certo
    var _style = 0;
    var _timer = null;

    function initialize() {
        View.initialize();
        var s = Storage.getValue("style");
        _style = (s instanceof Lang.Number) ? s % GluDraw.STYLE_COUNT : 0;
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
    }

    function requestGlucose() as Void {
        var params = { "minutes" => "180", "step" => "3" };
        var options = { :method => Communications.HTTP_REQUEST_METHOD_GET, :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON };
        Communications.makeWebRequest(GluDraw.URL, params, options, method(:onReceive));
    }

    function onReceive(responseCode as Lang.Number, data as Lang.Dictionary or Lang.String or Null) as Void {
        System.println("GluView resposta=" + responseCode);
        if (responseCode == 200 && data instanceof Lang.Dictionary) {
            _json = data;
            _rxSec = Time.now().value();
            _linkOk = true;
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
