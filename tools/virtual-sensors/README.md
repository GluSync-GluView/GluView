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
| `val 100 95` | as próximas leituras ao vivo valem 100 e depois 95 mg/dL |
| `lo` / `hi` | próxima leitura "abaixo"/"acima da faixa" (com bit de defeito, como o real). O app só grava 39/401 se a leitura anterior veio de 5 a 29 min antes: use `--live-every 300` (ritmo real) com `val 100` antes do `lo` ou `val 300` antes do `hi` |
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

## Dexcom G6 (plano 14_, Fase V3b)

```
./.venv/Scripts/python.exe -u dexcomg6.py --novo --cycle 60 --window 20
```

Transmissor `8G1A2B` (anuncia como `Dexcom2B`, serviço 0xFEBC). No app: Sensor → Dexcom G6 → Transmitter
ID `8G1A2B` (com outro ID que termine em `2B` o transmissor recusa: cenário do ID errado). O G6 real anuncia a
cada 5 min (`--cycle 300`); `--cycle 60` só acelera o teste — as leituras continuam de 5 em 5 min. Sessão
começa 72 h antes (`--session-hours`); relógio do transmissor, sessão e "pareado" ficam em
`dexcom-<ID>-estado.json`. Outro transmissor ao mesmo tempo: `--id 8H3C4D --address F0:A1:B2:C3:D4:A7
--control control-dexcom2.txt`.

Limitação: o SMP do Bumble não funciona no netsim, então o pareamento do Android não termina. O
`BondRequest` (0x07) faz o transmissor virtual se considerar pareado, e o app segue pelo status que ele
informa — o mesmo que o xDrip faz. Um G6 real exige o pareamento de verdade para abrir a Control.

Cenários em `control-dexcom.txt`:

| Comando | Efeito |
|---|---|
| `away N` | some por N minutos; na volta o app tem furo e pede backfill |
| `newsession` | sessão nova agora: 2 h de aquecimento (estado 0x02) |
| `end` / `fail` | sessão encerrada (0x0F) / sensor com falha (0x0B) |
| `nosession` | sem sessão (estado 0x01) |
| `badcrc` | próxima glicemia com CRC errado |
| `unbond` | o transmissor esquece o pareamento (o app refaz KeepAlive + BondRequest) |
| `normal` | sessão em andamento de novo (3 dias) |
