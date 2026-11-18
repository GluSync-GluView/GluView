# F7.3 — Zepp OS (mini-app + serviço lateral)

- **Objetivo:** mostrar glicemia do GluView no relógio Zepp OS lendo o servidor web local do celular (F2), sem nuvem.
- **Fonte:** `http://127.0.0.1:17580/sgv.json?count=1` (compatível Nightscout/xDrip), implementado em `mirror-core` (`LocalHttpServer`, porta `DEFAULT_PORT=17580`). Campos usados: `sgv`, `direction`, `date`.
- **Arquitetura:** `app-side/index.js` faz o fetch HTTP no lado do telefone; `page/index.js` exibe valor, direção e idade em minutos e atualiza periodicamente. Requer o servidor local do GluView ativo no celular pareado.
- **Maturidade:** **Sem teste em simulador Zepp OS/hardware real** (simulador não disponível neste ambiente). Não publicado em loja.
- **Prova esperada (quando houver simulador):** abrir o simulador Zepp OS, rodar o mini-app com o servidor local do GluView servindo `/sgv.json`, capturar print mostrando valor e idade; leitura velha deve aparecer com idade alta (o app mostra os minutos desde `date`).
- **Limitações conhecidas:** acesso a `127.0.0.1:17580` depende do bridge do Zepp OS no dispositivo; pode ser necessário ajustar permissão/rede conforme firmware. O bundle `shared/message` (MessageBuilder) deve ser o do template oficial do Zeus CLI no ambiente de build do simulador.
