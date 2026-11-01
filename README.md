# GluView

App Android de monitoramento de glicemia para quem tem diabetes (paciente) e para quem acompanha
alguém com diabetes (seguidor). Projeto **sem fins lucrativos**, feito para a comunidade.

- Leitura direta de sensores via Bluetooth/NFC: FreeStyle Libre 2 Plus e Sibionics
- Funciona **no aparelho**: alertas, gráficos, tempo no alvo, relatório, widgets, notificação
  persistente, Android Auto e visor para Mi Band 6 — sem enviar nada para a internet
- Nuvem e acompanhamento (seguidor vendo a glicemia em tempo real) pelo servidor GluSync, liberados
  por pedido dentro do app

## ⚠️ Aviso médico e de boa-fé

- O GluView **não é um dispositivo médico** e **não substitui o aplicativo oficial do fabricante
  do seu sensor**, o leitor oficial, o medidor de ponta de dedo nem orientação profissional. Use o
  app oficial como referência; o GluView é um complemento.
- É um projeto **independente e sem fins lucrativos**, sem afiliação, patrocínio ou aprovação de
  Abbott, Sibionics, Dexcom, Roche, Xiaomi ou qualquer fabricante. As marcas pertencem aos donos e
  aparecem só para indicar compatibilidade.
- As bibliotecas dos fabricantes usadas na leitura direta **não fazem parte deste repositório**;
  servem só para que cada pessoa acesse os dados do próprio sensor.
- Fabricantes ou titulares de direitos com qualquer objeção: `glusync.gluview@gmail.com`. Toda
  manifestação será tratada de boa-fé e com prioridade.

Leia [`docs/aviso-medico.md`](docs/aviso-medico.md) e a
[política de privacidade](docs/privacidade.md) antes de usar.

## Conta gratuita, sem exploração de dados

O app pede uma conta. A conta é **gratuita**, não tem anúncios e os seus dados **não são vendidos,
compartilhados nem usados para marketing**. Sem pedir a nuvem, as leituras ficam só no seu aparelho.

## Documentação

Guia de uso em [`docs/`](docs/index.md): instalação, primeiros passos, sensor, acompanhamento,
wearables e perguntas frequentes.

## Compilar

Requisitos: Android Studio (JDK 17), Android SDK 35, NDK 27.0.12077973 e CMake 3.22.1.

```bash
./gradlew :app:assembleDebug        # Windows: gradlew.bat :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

**Sensores nativos.** A leitura do FreeStyle Libre 2 Plus e do Sibionics usa bibliotecas
proprietárias da Abbott e da Sibionics, que **não podem ser redistribuídas** e por isso **não estão
neste repositório**. Sem elas o app compila e funciona normalmente, só esses sensores ficam
indisponíveis. Para incluí-las, extraia dos APKs oficiais que você mesmo baixou — veja
[`docs/build-native-libs.md`](docs/build-native-libs.md).

**Servidor.** O build usa o servidor oficial por padrão. Para outro servidor GluSync:
`./gradlew :app:assembleDebug -Pgluview.serverUrl=https://seu-servidor`.

Stack: Kotlin, Jetpack Compose, Hilt, Room, Retrofit, SignalR, C++ (NDK).

## Licença

[GNU General Public License v3.0 ou posterior](LICENSE). Você pode usar, estudar, modificar e
redistribuir; quem distribuir uma versão modificada precisa publicar o código-fonte dela sob a
mesma licença. Créditos de terceiros e regras de uso do nome em [`NOTICE.md`](NOTICE.md).

## Segurança e contribuição

- Vulnerabilidade: **não abra issue pública** — veja [`SECURITY.md`](SECURITY.md).
- Como contribuir: [`CONTRIBUTING.md`](CONTRIBUTING.md).
