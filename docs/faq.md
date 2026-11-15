# Perguntas frequentes

**Preciso de conta pra usar o app?**
Sim, mas ela é **grátis**, sem anúncios e sem exploração de dados. Com a conta, o app funciona
inteiro no aparelho; suas leituras só vão para a nuvem se você pedir e a nuvem for liberada. Veja
[Primeiros passos](primeiros-passos.md).

**Por que a nuvem e o acompanhamento precisam de liberação?**
Porque têm custo de servidor e o projeto é gratuito e sem fins lucrativos. O pedido é feito em
**Ajustes › Conta › Nuvem e acompanhamento**. Quem só acompanha outra pessoa não precisa pedir.

**Preciso pedir liberação para usar um sensor?**
**Não.** Todos os sensores da lista estão liberados para qualquer conta. O rótulo de cada um
("Funciona", "Em teste", "Sem teste com sensor real") diz o quanto ele já foi validado. Só a nuvem
e o acompanhamento são por pedido.

**O GluView substitui o app oficial do sensor ou meu medidor de ponta de dedo?**
**Não.** O GluView é um complemento independente. Use o app e os equipamentos oficiais como
referência. Veja o [aviso médico](aviso-medico.md).

**O GluView é do fabricante do meu sensor?**
Não. É um projeto independente, de código aberto e sem fins lucrativos, sem afiliação com nenhum
fabricante.

**Qual opção escolho na lista de sensores?**
O modelo e a **região onde o sensor foi vendido** (ex.: FreeStyle Libre 2 Plus SA para sensor
comprado no Brasil). Veja [Configurar o sensor](sensor.md). O FreeStyle Libre corrige a região
sozinho na ativação.

**O que é o Perfil AGP?**
É o relatório padrão internacional de monitorização contínua (Ambulatory Glucose Profile): percentis
da glicose por hora do dia, glicose média, GMI, variabilidade (CV), tempo com sensor ativo e tempo
em cada faixa (muito baixa < 54, baixa 54–69, alvo 70–180, alta 181–250, muito alta > 250 mg/dL).
Fica em **Relatório › Perfil AGP**, para 7, 14 ou 30 dias, e exporta em PDF para levar ao médico.
O consenso pede pelo menos 14 dias e 70% de sensor ativo; com menos, o app mostra o AGP com um
aviso. É calculado no aparelho, sem nuvem, e **não é diagnóstico**.

**Os alarmes tocam com o Não Perturbe ligado?**
Depende do que você escolher em **Configurar alertas**, faixa por faixa:
- **Ignora o Não perturbe**: o alerta toca mesmo assim, como um alarme. Com o **acesso ao Não
  perturbe** dado ao GluView, ele passa sempre (menos no Silêncio total). Sem esse acesso, ele só
  passa se o seu Não perturbe deixar alarmes passarem (o padrão do Android); o app avisa na tela e
  tem um botão para permitir o acesso.
- **Vibra no Não perturbe**: o alerta respeita o Não Perturbe, mas vibra em vez de ficar mudo.
- Nenhum dos dois: o alerta respeita o Não Perturbe e fica em silêncio.

A hipoglicemia urgente já vem marcada para ignorar o Não Perturbe; se você desmarcar, o app mostra
um aviso permanente. No modo **Silêncio total** o Android não deixa nenhum app tocar. O histórico
de alertas registra quando um alerta foi silenciado, só vibrou ou não conseguiu furar o Não Perturbe.

**Dá para ver a glicemia na barra de status (Android 16 / barra Now da Samsung)?**
Sim, no Android 16: ligue **Ajustes › Geral › Glicemia na barra de status**. A notificação fixa vira
uma "notificação ao vivo": a glicemia aparece num chip na barra de status e na tela de bloqueio (na
Samsung com One UI 8, na barra Now), com a posição nas faixas. Nesse modo a notificação fica sem o
gráfico. Sem leitura recente, o chip mostra `--`. Em Android mais antigo, ou se o Android não
permitir, a notificação continua a de sempre.

**O app funciona sem internet?**
A leitura do sensor e tudo que é local (gráficos, alertas, notificação, Mi Band) funciona sem
internet. Enviar e receber dados da nuvem precisa de conexão.

**Por que a leitura às vezes demora um pouco pra atualizar em dados móveis?**
Pra poupar dados móveis, o app manda só a leitura mais recente a cada alguns minutos nesse modo —
o histórico completo é sincronizado de uma vez ao conectar no Wi-Fi. Dá pra ajustar o intervalo em
**Ajustes › Sincronização**.

**O Android disse que o app é de um "desenvolvedor não verificado". É seguro?**
Se o arquivo veio das [Releases do GitHub](https://github.com/GluSync-GluView/GluView/releases/latest),
é o app oficial: o aviso aparece porque o registro do GluView na verificação de desenvolvedor do
Google ainda está em andamento. Veja [Instalação](instalacao.md#verificação-de-desenvolvedor-do-google).

**A versão 1.0.11 trocou a chave de assinatura. Preciso fazer alguma coisa?**
Não. A atualização instala por cima, como sempre, e mantém dados, conta e pareamento. A chave nova
está em [Instalação](instalacao.md#chave-de-assinatura).

**Posso ser paciente e seguidor ao mesmo tempo?**
Sim — marque os dois perfis ao criar a conta.

**Meus dados ficam seguros?**
Veja a [política de privacidade](privacidade.md). Outra pessoa só vê seus dados se você aceitar
um vínculo de acompanhamento com ela, e você pode revogá-lo quando quiser.

**Como reporto um problema?**
Abra uma issue no GitHub, sem colocar sua glicemia, e-mail ou número de série, ou use **Ajustes ›
Diagnóstico › Ver e exportar logs**. Problemas de segurança: siga o
[`SECURITY.md`](../SECURITY.md), nunca uma issue pública.
