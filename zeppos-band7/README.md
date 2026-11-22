# GluView no Zepp OS 1.0 — Mi Band 7 e Amazfit Band 7

- **Objetivo:** a glicemia do GluView na Xiaomi Mi Band 7 (192×490) e na Amazfit Band 7 (194×368),
  pulseiras de tela de barra com **Zepp OS 1.0**, lendo o servidor web local do celular, sem nuvem.
  O app do `../zeppos` é Zepp OS 3 (Amazfit Balance, Active etc.) e não instala nelas.
- **Aparelhos (`deviceSource`, conferidos nos coordenadores do Gadgetbridge):** Amazfit Band 7 = 252, 253,
  254; Mi Band 7 = 260, 262, 263, 264, 265. O simulador da Zepp só tem a **Amazfit Band 7** (não há
  Xiaomi Mi Band 7 no catálogo dele), então a Mi Band 7 vai pelo mesmo pacote e o mesmo código, sem teste
  em simulador próprio.
- **O que reaproveita:** a lógica testada de `../zeppos/utils/glucose.js` (dados, cores, gráfico,
  `layoutBar` para tela de barra, alerta F7.1), importada direto pelo caminho relativo.
- **O que muda no Zepp OS 1.0** (achados no simulador da Band 7, 22/11/2026):
  - a ZML (`@zeppos/zml`) quebra (`cannot read property 'EventBus' of undefined`): a troca de mensagens
    é a do modelo oficial `fetch-api` da Zeus CLI (`shared/`, licença ISC, ver `shared/LEIA-ME.md`);
  - não há CANVAS: o gráfico vira FILL_RECT/CIRCLE/TEXT (`utils/widgets.js`, testado);
  - o `GRADKIENT_POLYLINE` existe mas não desenha nada: a seta é feita de discos encostados (`dotsAlong`);
  - não há `hmUI.deleteWidget`: os widgets são reaproveitados (`setProperty(MORE)` + `VISIBLE`);
  - a barra do sistema cobre o topo: `hmUI.setStatusBarVisible(false)`;
  - hora pelo sensor TIME (`timeAdapter`), estilo e último alerta em `hmFS.SysPro*`.
- **Alerta (plano 21, F7.1):** pede `/watch.json?…&app=zepp`; alerta de id novo → vibra no padrão
  escolhido no celular (cenas 27 = 600 ms e 28 = 1000 ms do sensor VIBRATE, pela documentação do Zepp OS
  1.0; o simulador não vibra, então a cena não foi conferida) e mostra a faixa vermelha por 2 min.
- **appId 1100921**, o mesmo do app Zepp OS 3. Não publicado em loja.

## Como montar e testar

```powershell
$env:Path = "C:\claude\_tools\node\node-v26.13.1-win-x64;C:\claude\_tools\node\npm-global;$env:Path"
cd C:\claude\GluView\zeppos-band7
node --test test/      # conversão para widgets, setas, hora (4 testes)
zeus build             # gera dist/*.zab
zeus dev               # manda para o simulador (Band 7 baixada e o emulador ligado no simulador)
```

O `zeus dev` não percebe mudanças feitas por fora (editor/script): pare e rode de novo. Clicar na janela
do emulador prende o mouse nela (Ctrl+Alt+G solta; com o foco em outra janela esse atalho abre a busca
do Google Drive).

## Estado em 22/11/2026 (Claude)
- Simulador (Amazfit Band 7 v1.0.2): os 3 estilos desenham com dados vindos do celular pelo serviço lateral
  (servidor de teste com curva sintética): Clássico, Glicemia em destaque, Completo com a data; tocar troca o
  estilo e ele fica salvo; a faixa do alerta apareceu; a hora anda e os redesenhos (30 s) não derrubam mais
  o app.
- Depois de muitas reinstalações seguidas pelo `zeus dev`, o `fetch` do serviço lateral passou a falhar
  (DevTools: `ws://localhost:7833 … ERR_CONNECTION_RESET`, ponte do simulador caída) e o relógio mostrava
  "Sem dados" — o mesmo caminho tinha entregado os dados antes. Para retomar: reiniciar o simulador.
- Ainda aparece um "TypeError: not a function" sem pilha logo no 1º pedido de dados da instância que o
  `zeus dev` acabou de instalar (a página é recarregada e segue normal); não foi achado — os trechos do
  app estão protegidos e registram o erro com `GluView ERRO em …`, e nenhum desses apareceu.
- Falta: Mi Band 7 e Band 7 de verdade (vibração e a altura de 490 px só conferidas no teste de layout).
