using Toybox.Application;
using Toybox.Application.Storage;
using Toybox.Application.Properties;
using Toybox.Background;
using Toybox.Communications;
using Toybox.Graphics;
using Toybox.Lang;
using Toybox.System;
using Toybox.Time;
using Toybox.WatchUi;

// Mostrador GluView (plano 21, F7.5). O desenho é o mesmo do app (../common/GluDraw.mc): gráfico
// igual ao da Mi Band e 3 estilos (Clássico, Glicemia em destaque, Completo).
//
// Dados: mostrador não pode buscar na rede em primeiro plano; o serviço em segundo plano busca o
// /watch.json a cada 5 min (mínimo que o Garmin permite) e entrega em onBackgroundData. A idade
// continua andando a cada minuto pelo relógio (só diferença desde a chegada), e com 20 min ou mais,
// ou falha na busca, o valor some ("Sem sinal").

const BG_PERIOD_S = 300;

(:background)
class GluBgService extends System.ServiceDelegate {
    function initialize() { ServiceDelegate.initialize(); }

    function onTemporalEvent() as Void {
        var options = { :method => Communications.HTTP_REQUEST_METHOD_GET, :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON };
        Communications.makeWebRequest("http://127.0.0.1:17580/watch.json", { "minutes" => "180", "step" => "5" }, options, method(:onReceive));
    }

    function onReceive(code as Lang.Number, data as Lang.Dictionary or Lang.String or Null) as Void {
        // Dictionary = deu certo; número = código do erro (o primeiro plano mostra "Sem sinal").
        Background.exit((code == 200 && data instanceof Lang.Dictionary) ? data : code);
    }
}

(:background)
class GluFaceApp extends Application.AppBase {
    function initialize() { AppBase.initialize(); }

    function getServiceDelegate() { return [ new GluBgService() ]; }

    function onBackgroundData(data) as Void {
        if (data instanceof Lang.Dictionary) {
            Storage.setValue("json", data);
            Storage.setValue("rx", Time.now().value());
            Storage.setValue("ok", true);
        } else {
            Storage.setValue("ok", false);
        }
        WatchUi.requestUpdate();
    }

    function getInitialView() {
        schedule();
        return [ new GluFaceView() ];
    }

    function onSettingsChanged() as Void { WatchUi.requestUpdate(); }

    // Primeira busca o quanto antes (respeitando os 5 min desde a última) e depois a cada 5 min.
    function schedule() as Void {
        if (!(Toybox.System has :ServiceDelegate)) { return; }
        var last = Background.getLastTemporalEventTime();
        try {
            if (last == null || Time.now().subtract(last).value() >= BG_PERIOD_S) {
                Background.registerForTemporalEvent(Time.now());
            }
        } catch (e) { }
        Background.registerForTemporalEvent(new Time.Duration(BG_PERIOD_S));
    }
}

class GluFaceView extends WatchUi.WatchFace {
    function initialize() { WatchFace.initialize(); }

    function onLayout(dc as Graphics.Dc) as Void { }

    function onUpdate(dc as Graphics.Dc) as Void {
        var style = Properties.getValue("FaceStyle");
        if (!(style instanceof Lang.Number)) { style = GluDraw.STYLE_CLASSIC; }
        GluDraw.drawFace(dc, style % GluDraw.STYLE_COUNT, Storage.getValue("json"), Storage.getValue("rx"), Storage.getValue("ok") == true);
    }
}
