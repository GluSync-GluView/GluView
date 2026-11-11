# Wearables

## Mi Band 6

O GluView gera e envia um visor personalizado direto pra Mi Band 6 (sem precisar do app
Zepp/Mi Fitness aberto) — mostra a glicemia, seta de tendência e um mini-gráfico.

1. Aba **Wearables → Mi Band 6 → Configurar**.
2. Pareie: informe o MAC e a chave de autenticação da band (ou use o botão de parear, se
   disponível no seu Android).
3. Toque em **Enviar visor com glicemia atual** pra testar.
4. Ative **Atualização automática** pra manter o visor sempre atualizado — configure o
   intervalo (ex.: a cada 5 min de dia, a cada 30 min à noite).

## Construtor de visor

Em **Wearables → Editar visor**, dá pra personalizar posição, tamanho, cor e fonte de cada
elemento (glicemia, gráfico, seta, hora) — inclui modelos prontos pra começar rápido.

## Relógios Wear OS (beta)

> **Beta, testado em emulador.** Ainda não foi validado num relógio de verdade — se você usa
> Galaxy Watch ou Pixel Watch e puder testar, conte pra gente o que funcionou.

Funciona em relógios com **Wear OS 3 ou mais novo**: Galaxy Watch 4 em diante, Pixel Watch e
outros. Não funciona no Galaxy Watch 3 ou anteriores (sistema Tizen), Apple Watch, Garmin nem
nos relógios Xiaomi com sistema próprio (Watch S1/S3).

**Instalação:** o app do relógio vem na mesma Release do celular, como `GluView-Wear-<versão>.apk`.
Instale pelo computador (`adb install GluView-Wear-<versão>.apk` com o relógio em modo
desenvolvedor e depuração por Wi-Fi) ou por um app instalador de relógio. O app do relógio precisa
ser da **mesma versão e da mesma fonte** do app do celular — senão os dois não se conversam.

**O que aparece no relógio:**

- **Complication** "Glicemia (GluView)": coloque em qualquer mostrador, inclusive os da Samsung
  (toque e segure o mostrador → Personalizar → escolha um espaço → GluView). Mostra o valor e a
  seta; nos tipos maiores, a variação, a hora da medição ou o minigráfico.
- **Tile**: valor grande na cor da faixa, seta, variação, minigráfico e hora da medição.
- **App**: a mesma informação, com o botão **Sincronizar** (busca na nuvem na hora).

**Como os dados chegam:** o celular manda cada leitura nova para o relógio pelo Bluetooth. Se o
celular sumir, o relógio busca sozinho no GluSync (precisa de conta na nuvem) com uma credencial
própria, **só de leitura**, que vale 30 dias e o celular renova sozinho. Ela aparece na sua lista de
chaves como "Relógio Wear OS" e pode ser revogada lá; pedir uma nova (trocar de relógio, por exemplo)
cancela a anterior. No modo só-local, o relógio depende do celular.

**Alertas:** os alertas de glicemia do GluView chegam ao relógio como notificação do celular,
espelhada pelo próprio sistema (app Pixel Watch ou Galaxy Wearable) — vibram no pulso. No Galaxy
Wearable, confira em **Notificações** que o GluView está ligado. O som de alarme continua tocando no
celular; o relógio vibra conforme as configurações dele. Ainda não testado num relógio de verdade.

**Dado velho nunca aparece como atual:** sem leitura nova pelo tempo de "sem sinal" configurado no
app do celular (padrão 20 min), o relógio mostra **--** na complication, no tile e no app.

**Status no celular:** em **Wearables → Relógio Wear OS** aparece se o relógio está conectado e se
o GluView está instalado nele. Tocar ali reenvia a leitura atual.

### Visor personalizado no Wear OS 6

Em relógios com **Wear OS 6** (Galaxy Watch 5, 6, 7, Ultra e FE já atualizados; Watch 4 a
confirmar), o construtor de visor também envia o **seu** visor para o relógio:

1. Em **Wearables → Criar visor**, escolha o relógio em *Wearable e compatibilidade* (ex.: Galaxy
   Watch6) e monte o visor ou use um modelo pronto. Componentes que não existem no Wear OS aparecem
   como *Não disponível no Wear OS*.
2. Salve e toque em **Enviar para o relógio**. O celular monta o mostrador, confere com o
   validador oficial do Google e manda.
3. No relógio, abra o GluView e toque em **Usar o visor do GluView** (o relógio pede permissão uma
   vez). Se negar, dá pra escolher à mão: toque e segure o mostrador e escolha *GluView*.

O Wear OS 6 guarda um visor por app: enviar outro visor substitui o anterior. Hora e data são
desenhadas pelo próprio relógio; glicemia, variação, hora da medição e minigráfico vêm da
complication do GluView.

## Outros wearables

Outros relógios e pulseiras (Garmin, Amazfit/Zepp, Huawei, Mi Bands mais novas) ainda não têm envio
automático. Apps compatíveis com Nightscout apontados para o GluSync podem mostrar a glicemia
neles.
