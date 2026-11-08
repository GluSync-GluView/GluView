# Sensores virtuais (plano 14_simulacao_sensores_virtuais.md)

Periférico Bluetooth LE feito com o [Bumble](https://github.com/google/bumble), ligado ao Bluetooth
virtual (netsim) do emulador do Android Studio. O GluView no emulador conversa com ele como se fosse o
sensor de verdade. Só dados sintéticos.

## Preparar (uma vez)

```bash
cd tools/virtual-sensors
python -m venv .venv
./.venv/Scripts/python.exe -m pip install bumble grpcio protobuf
```

Emulador com Google APIs (x86_64) aberto antes. Build `debug` do GluView: os sensores "Em breve" ficam
liberados (`DEV_SENSOR_UNLOCK`), sem beta.

## Accu-Chek SmartGuide

```bash
./.venv/Scripts/python.exe -u smartguide.py --live-every 60
```

Nome `AC-1R009999990`, PIN `123456`. No app: Sensor → Accu-Chek SmartGuide → serial `1R009999990` →
PIN `123456`. Uma leitura por minuto (`--live-every`). O início do sensor fica em
`smartguide-<serial>-inicio.txt` (o relógio não volta atrás); `--novo` começa um sensor novo. O
pareamento fica em `AC-<serial>-pareamentos.json`.

Cenários: escreva um comando por linha em `control.txt` (com o simulador rodando):

| Comando | Efeito |
|---|---|
| `lo` / `hi` | próxima leitura "abaixo"/"acima da faixa" (com bit de defeito, como o real) |
| `error N` | N leituras seguidas com defeito (NaN) |
| `temp` / `battery` / `calib` | próxima leitura com aviso de temperatura / bateria fraca / calibração |
| `crcbad` | próxima leitura com CRC errado |
| `multi` | histórico com 2 registros por notificação (só se o MTU couber; com MTU 23 é ignorado) |
| `end` | sessão encerrada |
| `away N` | some por N minutos (para de anunciar); ao voltar, o app deve pedir o histórico |
| `drop` | derruba a conexão |
| `forget` | o sensor esquece o pareamento (e pede outro) |
| `refuse` / `accept` | o sensor esquece e passa a recusar / volta a aceitar pareamento |
| `restart` | sensor trocado: a contagem recomeça do minuto 0 |
| `normal` | volta tudo ao normal |
