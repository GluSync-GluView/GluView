# F7.5 — Garmin Connect IQ (app + mostrador)

**21/11/2026 (pedido do Felipe): gráfico igual ao da Mi Band e 3 estilos desenhados no código.**
- `common/GluDraw.mc` — desenho compartilhado: gráfico do visor da Mi Band (fundo preto, faixa de
  urgência, limiares baixo/alto tracejados com o valor, faixa 100/150, linha cinza, pontos pela faixa,
  40–300 mg/dL, últimas 3 h) e 3 estilos: **Clássico** (gráfico grande), **Glicemia em destaque** e
  **Completo** (dia/data, hora, glicemia, gráfico, variação/idade, passos e bateria). Posições pela
  altura das fontes e pela largura útil da tela redonda.
- App (esta pasta): busca a cada 60 s; tocar na tela troca o estilo (fica salvo).
- **Mostrador** (`face/`, tipo watchface, id próprio): busca em segundo plano a cada 5 min (mínimo do
  Garmin para mostradores); estilo escolhido nos ajustes do app no Garmin Connect (`FaceStyle`).
- Dados: `/watch.json` do GluView (rota nova do `LocalHttpServer`): última leitura formatada na
  unidade, limites do paciente e pontos de 3 h com idades pela hora do celular.

- **Objetivo:** app no relógio Garmin lendo a glicemia do servidor web local do GluView (F2), sem nuvem.
- **Endpoint:** `http://127.0.0.1:17580/watch.json` (`LocalHttpServer`, porta 17580). Raiz = objeto, que é
  o que o `makeWebRequest` do Connect IQ aceita (o `/sgv.json` tem raiz lista e nunca chegava ao app).
  Idades pela hora do CELULAR, então hora errada no relógio não engana.
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

Mostrador: `cd face` e o mesmo `monkeyc` com `-o bin\GluFace-fenix7.prg`.

## Testes dos 3 estilos (21/11/2026, Claude)
- Servidor de teste `/watch.json` com curva sintética de 3 h (baixa, alta, buraco de 20 min): app nos
  3 estilos no fēnix 7 e no Venu 3; mostrador no fēnix 7 com a busca em segundo plano trazendo os dados
  na hora e os estilos 1 e 2 pelo `FaceStyle`; idade andando com os dados salvos (3 → 5 min).
- Defeitos achados e corrigidos: no "Destaque" a variação ficava atrás do gráfico e no "Completo" a
  última linha saía da tela redonda.
- O editor de ajustes do simulador ("App Settings Editor") disse não achar o arquivo de ajustes, embora
  o `GluFace-*-settings.json` seja gerado e o simulador crie o `.SET`; o estilo foi testado mudando o
  padrão. No celular quem mostra os ajustes é o Garmin Connect (não testado).

## Testes no simulador (21/11/2026, Claude, antes dos estilos)
- fēnix 7 com o GluView do emulador: `115 →`, idade correndo (resposta 200).
- Servidor de teste no formato `/pebble`: `210 ↑↑` (3 min), `72 ↘` (1 min), leitura de 25 min → "Sem sinal".
- Venu 3, Forerunner 965 e fēnix 7 com `48 ↓↓`: layout certo nos três.
- Defeitos achados e corrigidos no teste: seta em texto saía "◆?" (agora desenhada); no Venu 3 as linhas se
  sobrepunham (agora posicionadas pela altura das fontes).
- Falta: relógio Garmin de verdade pareado com o Garmin Connect do celular.
