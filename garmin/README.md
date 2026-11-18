# F7.5 — Garmin Connect IQ (watch-app/widget de dados)

- **Objetivo:** mostrador/app de dados no relógio Garmin lendo glicemia do servidor web local do GluView (F2), sem nuvem/loja.
- **Endpoint:** `http://127.0.0.1:17580/sgv.json?count=1` (Nightscout/xDrip compatível, `LocalHttpServer` do `:mirror-core`, porta 17580). Campos: `sgv`, `direction`, `date` (epoch ms).
- **Projeto:** `manifest.xml`, `monkey.jungle`, `source/GluViewView.mc`, recursos em `resources/`. Permissão `Communications`. Produtos de exemplo no manifest (ajustar conforme simulador/dispositivo).
- **Comportamento:** exibe valor, direção e idade em minutos calculada a partir de `date`; leitura velha permanece visível apenas com idade crescente (não como atual).
- **Maturidade:** **Sem teste em simulador Connect IQ/hardware real** neste ambiente. **Não publicar em loja.**
- **Prova esperada (simulador Connect IQ):** importar o projeto no SDK Connect IQ, executar no simulador com o servidor local do GluView ativo e capturar print com valor e idade.
- **Observação:** acesso HTTP local a partir do app Connect IQ depende do ambiente (simulador/telefone pareado). Pode ser necessário usar o host exposto pelo Garmin Connect Mobile no dispositivo real; documentar ajuste se houver.
