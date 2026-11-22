# F7.3 — Zepp OS (mini-app + serviço lateral)

- **Objetivo:** mostrar a glicemia do GluView no relógio Zepp OS 3.0+ (Amazfit Balance, Active e outros)
  lendo o servidor web local do celular (F2), sem nuvem. **Mi Band 7 e Amazfit Band 7 são Zepp OS 1.0:**
  ficam no projeto irmão `../zeppos-band7`, que importa a lógica daqui (`utils/glucose.js`).
- **Alerta (plano 21, F7.1, 22/11/2026):** o serviço lateral pede `&app=zepp`; alerta de id novo no
  `/watch.json` → vibra no padrão escolhido no celular (cenas do Zepp OS, `vibePlan`) e mostra a faixa
  vermelha com o título por 2 min.
- **Fonte:** `http://127.0.0.1:17580/watch.json?minutes=180&step=3` (`LocalHttpServer`, porta 17580).
- **Arquitetura (modelo oficial "fetch-api" do Zeus CLI, `@zeppos/zml`):**
  - `app-side/index.js` — serviço lateral, roda no app Zepp do CELULAR: busca o `/watch.json` do
    GluView (última leitura formatada, limites, pontos de 3 h com idades pela hora do celular);
  - `page/index.js` — página do relógio: **3 estilos** (Clássico, Glicemia em destaque, Completo) com o
    **gráfico igual ao da Mi Band**, desenhados num CANVAS + widgets TEXT; tocar troca o estilo (salvo);
    pede a cada 60 s, redesenha a cada 30 s;
  - `utils/glucose.js` — lógica pura (dados, cores da faixa, comandos de desenho do gráfico, layout dos
    estilos, seta como polígono), testada com Node (10 testes);
  - seta desenhada com `drawPoly` (a fonte do relógio pode não ter →↗↘; no Garmin saía "◆?").
- **Regra de segurança:** leitura com 20 min ou mais, ou falha na busca → "Sem sinal" (valor some).
- **appId 1100921** (conta de desenvolvedor Zepp criada pelo Felipe em 21/11/2026). Não publicado em loja.

## Como montar e testar

```powershell
$env:Path = "C:\claude\_tools\node\node-v26.13.1-win-x64;C:\claude\_tools\node\npm-global;$env:Path"
cd C:\claude\GluView\zeppos
npm install            # só na primeira vez
node --test test/      # lógica (10 testes)
zeus build             # gera dist/*.zab
zeus dev               # manda para o simulador (precisa do simulador logado e com um relógio baixado)
```

## Estado em 21/11/2026 (Claude)
- `zeus build` monta (página com CANVAS, serviço lateral com o módulo).
- `node --test test/`: 10/10 (layout dos 3 estilos sem sobrepor em 480 redonda, 390×450 quadrada e 454
  redonda; cores; buraco quebra a linha; idade pela diferença do relógio).
- Prévia dos 3 estilos gerada a partir dos MESMOS comandos de desenho (sem o simulador).
- **Mostrador Zepp (watchface):** a documentação lista `app-side` também no `app.json` de mostrador, mas
  sem exemplo de mostrador recebendo dados do celular; fica para testar quando o simulador estiver logado.
- **Falta o simulador:** o Zepp OS Simulator 2.1.2 está instalado, mas precisa do login do Felipe
  (senha, não digitada pelo Claude) e de baixar um relógio virtual (ex.: Amazfit Balance ou Active 2);
  ao abrir, o Windows pergunta sobre o Firewall — decisão do Felipe. Depois disso: `zeus dev`.
- Falta: relógio Zepp de verdade.
