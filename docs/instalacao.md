# Instalação

O GluView não está na Google Play Store. Por enquanto a distribuição é **privada**: o app só é
entregue a quem tem uma conta aprovada no GluSync, e depois de instalado ele mesmo se atualiza,
sem cabo e sem baixar nada à mão.

## 1. Peça acesso e receba o app

O cadastro é por aprovação. Depois que o seu acesso for aprovado, você recebe o arquivo de
instalação (APK) diretamente de quem administra o projeto. Essa é a única vez em que o app chega
"de fora": as versões seguintes vêm pelo próprio app (veja o passo 4).

## 2. Permita instalação de fontes desconhecidas

No Android, ao abrir o arquivo pela primeira vez, o sistema vai pedir permissão ("instalar apps
desconhecidos") — é normal para apps fora da Play Store. Autorize só para o app usado pra abrir
o arquivo (navegador, gerenciador de arquivos ou mensageiro).

## 3. Abra o app

Na primeira abertura, você escolhe:

- **Criar conta / entrar** — sincroniza com o servidor GluSync, permite acompanhamento
  (seguidor/paciente) e acesso aos dados de qualquer aparelho.
- **Usar sem servidor (100% local)** — tudo fica só neste aparelho; mais simples, mas sem
  acompanhamento nem sincronização entre dispositivos. Dá pra mudar de ideia depois saindo
  da conta local em Ajustes.

Requer Android 8.0 (API 26) ou mais recente.

## 4. Atualizações dentro do app

Em **Ajustes › Atualizações** aparecem a versão instalada e a versão nova, quando houver. O app
também procura sozinho a cada 6 horas e avisa com uma notificação discreta (nunca de madrugada).
Ele **nunca instala sozinho**: a atualização começa quando você toca em **Atualizar**.

- **Primeira vez:** o Android pede que você permita ao GluView "instalar apps desconhecidos".
  É uma autorização única, só para o GluView.
- **Hora de instalar:** se houver motivo para esperar — glicemia baixa ou em queda, alarme
  ativo, alarmes em soneca, sensor sendo configurado, sem leitura recente ou madrugada — o app
  mostra o motivo e pergunta: **Atualizar agora** ou **Mais tarde**. Deixar para mais tarde não
  tem consequência nenhuma; a versão nova continua disponível.
- **Durante a instalação** o monitoramento (sensor, alarmes e envio à nuvem) fica parado de
  alguns segundos a alguns minutos e volta sozinho. Cerca de 10 minutos depois o app confere se
  o monitoramento voltou e se alguma permissão (como SMS ou ligação de alerta) se perdeu, e
  avisa se for o caso.
- **Android 9 e anteriores:** o sistema sempre pede uma confirmação final ("Instalar"). Nos mais
  novos, depois da primeira atualização feita pelo app, normalmente não pede mais.
- **Modo 100% local:** baixar atualizações exige entrar com uma conta aprovada.
- **Se o Android bloquear:** em aparelhos Samsung, confira o **Bloqueador automático**
  (Configurações › Segurança e privacidade); no Android 16, a **Proteção avançada** também
  impede atualizar apps instalados fora de uma loja.

## Por que não a Play Store?

A política da Google Play exige que apps de saúde, como os médicos, sejam publicados por uma
conta de desenvolvedor do tipo **Organização**, com número D-U-N-S
([política da Play Console](https://support.google.com/googleplay/android-developer/answer/10788890)).
Hoje o projeto usa conta individual, então a distribuição é direta e privada, com a atualização
feita pelo próprio app.

Para quem publica versões: veja `tools/publish-release.ps1`.
