# Instalação

O GluView é gratuito, de código aberto e não está na Google Play Store. O app oficial é publicado
nas [Releases do GitHub](https://github.com/GluSync-GluView/GluView/releases/latest); depois de
instalado, ele mesmo se atualiza, sem cabo e sem baixar nada à mão.

Requer Android 9 (API 28) ou mais recente. A leitura direta de sensores (FreeStyle Libre 2 Plus e
Sibionics) exige aparelho de 64 bits; em aparelhos de 32 bits o app funciona como seguidor e com a
nuvem.

## 1. Baixe o app oficial

No celular, abra a
[última versão](https://github.com/GluSync-GluView/GluView/releases/latest) e toque no arquivo
`GluView-<versão>.apk`.

Baixe sempre deste endereço. Versões modificadas por terceiros, mesmo com o mesmo nome, não são o
GluView oficial. Quem quiser conferir o arquivo encontra o SHA-256 ao lado dele, no arquivo
`.sha256`.

### Chave de assinatura

Desde a versão 1.0.11, o GluView oficial é assinado com esta chave (SHA-256 do certificado):

```
EC:EB:6C:6B:D9:12:89:59:48:3C:DF:9D:CB:56:28:EF:C3:51:3B:EA:73:DB:86:13:02:47:69:BB:01:B5:E0:34
```

Até a 1.0.10 ele usava outra chave. A 1.0.11 leva junto a autorização da chave antiga para a nova,
então quem tem a 1.0.10 ou anterior atualiza por cima, como sempre, sem perder dados, conta nem
pareamento do sensor. Se o Android recusar a atualização por "assinatura diferente", o app instalado
não é o oficial: exporte o que precisar, desinstale e instale o oficial.

## 2. Permita a instalação

Ao abrir o arquivo, o Android pede permissão para "instalar apps desconhecidos" — é normal para
apps fora da Play Store. Autorize só para o app usado para abrir o arquivo (navegador ou
gerenciador de arquivos). O Play Protect pode mostrar um aviso de app desconhecido; para instalar,
toque em **Mais detalhes › Instalar mesmo assim**.

### Verificação de desenvolvedor do Google

Desde 30/09/2026, no Brasil, o Google exige que apps Android venham de desenvolvedores registrados
([página do Google](https://developer.android.com/developer-verification)). Por enquanto a regra
vale para as lojas participantes (Google Play, Galaxy Store, Xiaomi GetApps e outras); em 2027 ela
passa a valer para todos os apps, no mundo todo. O registro do GluView está em andamento. Se o seu
aparelho mostrar que o app é de um **desenvolvedor não verificado**, o arquivo continua sendo o
oficial desde que tenha vindo do endereço acima; confira o SHA-256 se quiser ter certeza.

## 3. Abra o app e crie sua conta grátis

Na primeira abertura, leia o aviso médico e os termos e crie sua conta (veja
[Primeiros passos](primeiros-passos.md)). A conta é **gratuita**, sem anúncios e sem exploração de
dados. Com ela, o app funciona inteiro no aparelho; a nuvem e o acompanhamento você pede depois.

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
- **Se o Android bloquear:** em aparelhos Samsung, confira o **Bloqueador automático**
  (Configurações › Segurança e privacidade); no Android 16, a **Proteção avançada** também
  impede atualizar apps instalados fora de uma loja.

## Por que não a Play Store?

A política da Google Play exige que apps de saúde sejam publicados por uma conta de desenvolvedor
do tipo **Organização**, com número D-U-N-S
([política da Play Console](https://support.google.com/googleplay/android-developer/answer/10788890)).
O GluView é um projeto individual e sem fins lucrativos, por isso a distribuição é direta, pelo
GitHub, com a atualização feita pelo próprio app.

Para quem publica versões: veja `tools/publish-release.ps1` (assinatura com a chave B:
`-Signer B`) e, para a prova de posse do pacote no registro do Google,
`tools/prova-registro-google.ps1`.
