# F7.8 — Wearables genéricos, espelhamento de notificações e Pebble

## Qualquer pulseira pelo espelhamento de notificações do Android

O GluView mantém uma **notificação persistente** com glicemia atual, tendência/seta e idade da leitura (`NotificationHelper.buildPersistentNotification` / `updatePersistentNotification`, conteúdo em `notification/buildPersistentNotificationContent.kt`, atualizado por `GluSyncService`). Relógios/pulseiras sem driver nativo podem exibir esses dados quando o app do fabricante (ou um app de ponte) **espelha notificações** do GluView.

**Uso:** habilitar espelhamento da notificação do GluView no app do dispositivo; evitar otimização agressiva de bateria para o GluView.

**Limitações/privacidade:** expõe o texto da notificação no dispositivo de terceiros; sem soneca/controle fino de vibração pelo GluView além da notificação; depende de atualizações contínuas do serviço. **Sem teste sistemático por modelo**.

## Pebble

Sem PebbleKit integrado ao build e sem hardware: **suporte nativo não implementado**. Caminho atual: espelhamento de notificações (se suportado pelo companheiro Rebble/Pebble). App dedicado consumindo `/sgv.json` (porta 17580, `:mirror-core`) é possível no ecossistema Rebble, mas pendente de SDK/projeto e **Sem teste**.
