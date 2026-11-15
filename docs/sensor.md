# Configurar o sensor (paciente)

O GluView lê alguns sensores de glicemia direto por Bluetooth/NFC. Ele **não substitui o
aplicativo oficial do fabricante**: use-o como complemento, mantenha o app ou leitor oficial como
sua referência e siga as instruções de uso do fabricante, inclusive o prazo de uso do sensor.
Veja o [aviso médico](aviso-medico.md).

## Sensores, por modelo e região de compra

**Nenhum sensor precisa de liberação**: todos os da lista podem ser escolhidos por qualquer conta,
sem pedido nem aprovação. Só a nuvem e o acompanhamento são por pedido (veja
[Primeiros passos](primeiros-passos.md)).

A região é a de **venda** do sensor, não a de fabricação. Ela importa porque o mesmo modelo usa
protocolos diferentes conforme o mercado.

Cada sensor aparece na lista com um rótulo honesto:

- **Funciona** — validado com sensor real no GluView.
- **Em teste** — está sendo testado com sensor real, sem conclusão ainda.
- **Sem teste com sensor real** — implementado e testado só no computador/simulador. Ao escolher,
  o app avisa; confira as leituras com o glicosímetro antes de confiar nelas.

| Na lista do app | Situação |
|---|---|
| FreeStyle Libre 2 Plus **SA** (América do Sul) | Funciona — testado com sensor vendido no Brasil |
| FreeStyle Libre 2 Plus **NA** / Libre 2 **NA** (América do Norte) | Sem teste com sensor real — mesmo protocolo do brasileiro, ainda sem teste com sensor dos EUA |
| FreeStyle Libre 2 / 2 Plus **EU** (Europa) | Em breve — protocolo europeu ainda não implementado |
| Sibionics GS1 **CN** (China) | Funciona — caixa chinesa 硅基动感 |
| Sibionics GS1 **SA** (América do Sul) | Em teste |
| Accu-Chek SmartGuide | Sem teste com sensor real |
| Smart 2.0 Medlevensohn | Sem teste com sensor real |
| Dexcom G6 | Sem teste com sensor real — pede o ID do transmissor (6 caracteres) |
| Dexcom G7 | Sem teste com sensor real — exige o G7 já pareado pelo app oficial da Dexcom no mesmo celular e o código de pareamento de 4 dígitos |

Na ativação do FreeStyle Libre por NFC o app lê a região gravada no sensor e corrige a escolha
sozinho. Se o sensor for europeu, o app avisa que essa versão ainda não é suportada.

A leitura direta do FreeStyle Libre e do Sibionics exige aparelho de 64 bits. Ela usa bibliotecas
dos próprios fabricantes, que não fazem parte do código-fonte do GluView, só para que você acesse
os dados do **seu** sensor. Sem essas bibliotecas no aparelho, o sensor aparece como
"indisponível neste aparelho".

## Pareamento

1. Na aba **Sensor**, toque em **Trocar de sensor** (ou **Configurar sensor**, na primeira vez).
2. Escolha o seu sensor pelo modelo e pela região de compra e siga o assistente: Sibionics lê o
   código da caixa e conecta por Bluetooth; FreeStyle Libre é ativado encostando o celular no
   sensor (NFC); Accu-Chek SmartGuide pede o número de série e o PIN da tampa; Dexcom G6 pede o ID
   do transmissor e Dexcom G7 pede o código de pareamento de 4 dígitos do aplicador.
3. Depois de pareado, a Home mostra a glicemia ao vivo e o status do sensor (conectado, dias de
   uso, bateria).

## Dexcom

O GluView **não substitui o app oficial da Dexcom**, que continua sendo a referência. No **G7**, o
primeiro pareamento do sensor precisa ser feito pelo app oficial neste mesmo celular (o pareamento
inicial usa credenciais do app oficial, que o GluView não tem). Depois disso o GluView lê o sensor
com o código de pareamento de 4 dígitos, mostra o aquecimento (30 min), o fim da vida (10 dias + 12 h
de tolerância) e falhas como estado do sensor — nunca como glicemia — e busca no sensor as leituras
perdidas enquanto o celular esteve longe (até 24 h).

## Avisos do sensor

Em **Sensor › Aviso de saúde do sensor**, dá pra configurar avisos de "sem sinal" e "sensor além
da vida nominal" — são só informativos, **nunca interrompem a leitura**. Usar o sensor além do
prazo indicado pelo fabricante é contra a orientação dele e fica por sua conta e risco.

## Histórico de sensores

A tela de pareamento mostra os últimos sensores usados (até 3 meses), útil pra saber quando
trocar o próximo.
