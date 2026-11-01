# Configurar o sensor (paciente)

O GluView lê alguns sensores de glicemia direto por Bluetooth/NFC. Ele **não substitui o
aplicativo oficial do fabricante**: use-o como complemento, mantenha o app ou leitor oficial como
sua referência e siga as instruções de uso do fabricante, inclusive o prazo de uso do sensor.
Veja o [aviso médico](aviso-medico.md).

## Sensores, por modelo e região de compra

A região é a de **venda** do sensor, não a de fabricação. Ela importa porque o mesmo modelo usa
protocolos diferentes conforme o mercado.

| Na lista do app | Situação |
|---|---|
| FreeStyle Libre 2 Plus **SA** (América do Sul) | Funciona — testado com sensor vendido no Brasil |
| FreeStyle Libre 2 Plus **NA** / Libre 2 **NA** (América do Norte) | Mesmo protocolo do brasileiro; ainda sem teste com sensor dos EUA |
| FreeStyle Libre 2 / 2 Plus **EU** (Europa) | Em breve — protocolo europeu ainda não implementado |
| Sibionics GS1 **CN** (China) | Funciona — caixa chinesa 硅基动感 |
| Sibionics GS1 **SA** (América do Sul) | Em teste |

Na ativação do FreeStyle Libre por NFC o app lê a região gravada no sensor e corrige a escolha
sozinho. Se o sensor for europeu, o app avisa que essa versão ainda não é suportada.

A leitura direta exige aparelho de 64 bits. Ela usa bibliotecas dos próprios fabricantes, que não
fazem parte do código-fonte do GluView, só para que você acesse os dados do **seu** sensor.

## Pareamento

1. **Sensor › Fonte de dados**, escolha o seu sensor.
2. Toque em **Trocar de sensor** e siga o assistente: Sibionics lê o código da caixa e conecta por
   Bluetooth; FreeStyle Libre é ativado encostando o celular no sensor (NFC).
3. Depois de pareado, a Home mostra a glicemia ao vivo e o status do sensor (conectado, dias de
   uso, bateria).

## Avisos do sensor

Em **Sensor › Aviso de saúde do sensor**, dá pra configurar avisos de "sem sinal" e "sensor além
da vida nominal" — são só informativos, **nunca interrompem a leitura**. Usar o sensor além do
prazo indicado pelo fabricante é contra a orientação dele e fica por sua conta e risco.

## Histórico de sensores

A tela de pareamento mostra os últimos sensores usados (até 3 meses), útil pra saber quando
trocar o próximo.
