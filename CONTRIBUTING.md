# Como contribuir

Obrigado pelo interesse em ajudar o GluView.

## Antes de abrir uma issue

- **Nunca** inclua a sua glicemia real, nome, e-mail, número de série de sensor, token ou log com
  dados pessoais. Use dados sintéticos.
- Vulnerabilidade de segurança: siga o [`SECURITY.md`](SECURITY.md), não abra issue pública.

## Pull requests

1. Abra uma issue antes de mudanças grandes, para combinar a abordagem.
2. Rode os testes: `./gradlew :app:testDebugUnitTest`.
3. Não envie bibliotecas proprietárias (`.so` da Abbott/Sibionics), chaves de assinatura,
   `local.properties` ou APKs — o `.gitignore` já bloqueia os casos conhecidos.
4. Ao enviar código, você concorda em licenciá-lo sob a GPLv3 ou posterior, a mesma do projeto.

## Segurança do paciente

Mudanças em leitura de sensor, cálculo de glicemia, alertas ou tendência precisam de teste e de
explicação clara no PR. Na dúvida, o comportamento mais conservador vence: o app nunca deve mostrar
um valor que não tenha certeza de estar correto.
