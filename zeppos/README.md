# F7.3 — Zepp OS (mini-app + serviço lateral)

- **Objetivo:** mostrar a glicemia do GluView no relógio Zepp OS (Amazfit, Mi Band 7 e outros com Zepp OS 3.0+)
  lendo o servidor web local do celular (F2), sem nuvem.
- **Fonte:** `http://127.0.0.1:17580/sgv.json?count=1` (`LocalHttpServer`, porta 17580). Campos: `sgv`,
  `direction`, `date`.
- **Arquitetura (modelo oficial "fetch-api" do Zeus CLI, `@zeppos/zml`):**
  - `app-side/index.js` — serviço lateral, roda no app Zepp do CELULAR: busca o `/sgv.json` e devolve
    `{ok, sgv, direction, ageMs}`; a idade sai da hora do celular (relógio com hora errada não engana);
  - `page/index.js` — página do relógio: pede a cada 60 s, redesenha a idade a cada 30 s;
  - `utils/glucose.js` — lógica pura (`parseSgv`, `display`), testada com Node;
  - setas em imagem (`assets/common.{r,s}/arrow/*.png`, geradas por `tools/make_arrows.py`): não dá para
    garantir que a fonte do relógio tenha →↗↘ (no Garmin a seta em texto saía "◆?").
- **Regra de segurança:** leitura com 20 min ou mais, ou falha na busca → "Sem sinal" (valor some).
- **appId 1100921** (conta de desenvolvedor Zepp criada pelo Felipe em 21/11/2026). Não publicado em loja.

## Como montar e testar

```powershell
$env:Path = "C:\claude\_tools\node\node-v26.13.1-win-x64;C:\claude\_tools\node\npm-global;$env:Path"
cd C:\claude\GluView\zeppos
npm install            # só na primeira vez
node --test test/      # lógica (8 testes)
zeus build             # gera dist/*.zab
zeus dev               # manda para o simulador (precisa do simulador logado e com um relógio baixado)
```

## Estado em 21/11/2026 (Claude)
- `zeus build` monta (página compilada, 7 setas convertidas, serviço lateral com o módulo).
- `node --test test/`: 8/8, incluindo a resposta REAL do GluView do emulador (`115`, `2 min`, seta estável).
- **Falta o simulador:** o Zepp OS Simulator 2.1.2 está instalado, mas precisa do login do Felipe
  (senha, não digitada pelo Claude) e de baixar um relógio virtual (ex.: Amazfit Balance ou Active 2);
  ao abrir, o Windows pergunta sobre o Firewall — decisão do Felipe. Depois disso: `zeus dev`.
- Falta: relógio Zepp de verdade.
