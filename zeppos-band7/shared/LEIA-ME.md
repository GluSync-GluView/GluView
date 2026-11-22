# shared/

Troca de mensagens relógio ↔ celular do Zepp OS 1.0, copiada sem mudanças do modelo oficial `fetch-api`
da Zeus CLI (`@zeppos/zeus-cli`, licença ISC — `private-modules/zeppos-app-utils/dist/public/template/os1.0/app/fetch-api/shared`).

A ZML (`@zeppos/zml`, usada no app Zepp OS 3 em `../zeppos`) quebra no Zepp OS 1.0 (Band 7, testado no
simulador em 22/11/2026: `cannot read property 'EventBus' of undefined`), por isso este projeto usa o
`MessageBuilder` do modelo oficial.
