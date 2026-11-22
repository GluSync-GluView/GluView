# GluView Visor — mostrador Zepp OS 3+

- Mostrador (watchface) com o MESMO desenho da página do app `../zeppos` (glicemia, seta e gráfico de 3 h, 3 estilos
  ao tocar), com serviço lateral (ZML) lendo `/watch.json?app=zepp` do servidor local do celular.
- appId 1100922 (o app é 1100921). Alvos redondo 480 e quadrado 390 (Balance, Active e outros Zepp OS 3+).
- `zeus dev -t "Amazfit Balance"` (sem `-t` o Zeus montou para outro aparelho, fontes 414/415).

## Estado em 22/11/2026 (Claude)
- `zeus build` monta; instalado no simulador na Amazfit Balance v2.0.0 (aparece na lista de apps com o ícone de
  mostrador). NÃO verificado: no seletor de mostradores da Balance (toque longo) só apareceu o mostrador do sistema,
  então não deu para ativar e ver se o serviço lateral entrega dados a um mostrador (a documentação da Zepp lista
  `app-side` no app.json de mostrador, sem exemplo). Pendente com o simulador: achar o mostrador no seletor.
