# F7.5 — Garmin Connect IQ (app de relógio)

- **Objetivo:** app no relógio Garmin lendo a glicemia do servidor web local do GluView (F2), sem nuvem.
- **Endpoint:** `http://127.0.0.1:17580/pebble?count=1` (`LocalHttpServer`, porta 17580). Raiz = objeto, que é
  o que o `makeWebRequest` do Connect IQ aceita (o `/sgv.json` tem raiz lista e nunca chegava ao app).
  Campos usados: `bgs[0].sgv`, `bgs[0].direction`, `bgs[0].datetime` e `status[0].now` (hora do CELULAR,
  então hora errada no relógio não engana a idade).
- **Projeto:** `manifest.xml` (formato `iq:manifest`), `monkey.jungle`, `source/GluViewView.mc`, `resources/`.
  Produtos: venu3, venu3s, vivoactive5, fenix7, fr965. Permissão `Communications`.
- **Comportamento:** valor + seta desenhada (as fontes do relógio não têm →↗↘) + idade; busca a cada 60 s;
  leitura com 20 min ou mais, ou falha na busca → "Sem sinal" (valor some).
- **Não publicar em loja** sem decisão do Felipe (cadastro de desenvolvedor Garmin pendente).

## Como compilar e testar (SDK 9.3.0, 21/11/2026)

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; $env:Path = "$env:JAVA_HOME\bin;$env:Path"
$sdk = "$env:APPDATA\Garmin\ConnectIQ\Sdks\connectiq-sdk-win-9.3.0-2026-10-20-95888f262"
& "$sdk\bin\monkeyc.bat" -f monkey.jungle -o bin\GluView-fenix7.prg -d fenix7 -y C:\claude\_tools\garmin\developer_key.der
& "$sdk\bin\connectiq.bat"      # abre o simulador
& "$sdk\bin\monkeydo.bat" bin\GluView-fenix7.prg fenix7
```

- No simulador, desmarcar **Settings → Use Device HTTPS Requirements** (senão a busca em `http://` dá -1001;
  no celular Android o Garmin Connect libera o 127.0.0.1).
- Dados reais: `adb -s emulator-5554 forward tcp:17580 tcp:17580` com o GluView rodando no emulador.
- A chave de desenvolvedor (`developer_key.der`) fica FORA do Git, em `C:\claude\_tools\garmin\`.

## Testes no simulador (21/11/2026, Claude)
- fēnix 7 com o GluView do emulador: `115 →`, idade correndo (resposta 200).
- Servidor de teste no formato `/pebble`: `210 ↑↑` (3 min), `72 ↘` (1 min), leitura de 25 min → "Sem sinal".
- Venu 3, Forerunner 965 e fēnix 7 com `48 ↓↓`: layout certo nos três.
- Defeitos achados e corrigidos no teste: seta em texto saía "◆?" (agora desenhada); no Venu 3 as linhas se
  sobrepunham (agora posicionadas pela altura das fontes).
- Falta: relógio Garmin de verdade pareado com o Garmin Connect do celular.
