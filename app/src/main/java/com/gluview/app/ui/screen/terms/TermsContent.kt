package com.gluview.app.ui.screen.terms

/**
 * F7/T54 — Termos de uso, isenção de responsabilidade e aviso de privacidade do GluView.
 * Disponível em português (padrão) e inglês — [TermsScreen] escolhe pelo idioma do app
 * ([com.gluview.app.data.datastore.AppPreferences.appLanguage]).
 *
 * Incremente [TERMS_VERSION] sempre que o TEXTO mudar (em qualquer idioma): o app exigirá
 * novo aceite de todos os usuários (o gate compara a versão aceita com esta).
 *
 * Versão 3 (ADR-0030, publicação como código aberto): deixa explícito que o app NÃO substitui o
 * app oficial do fabricante, a independência do projeto (sem afiliação, sem fins lucrativos), o
 * uso de boa-fé das bibliotecas dos fabricantes e o tratamento de dado de saúde pela LGPD
 * (dado sensível, art. 11 — o aceite desta tela é o consentimento específico e destacado, e fica
 * registrado também no servidor quando há conta). O mesmo texto está em docs/privacidade.md.
 *
 * Versão 4 (plano 17, aprovada pelo Felipe em 14/11/2026): diário, lembretes, Health Connect e
 * Padrões — o que é guardado, onde fica, que padrões e lembretes não são recomendação de dose.
 */
const val TERMS_VERSION = 4

val TERMS_TEXT = """
GluView — Termos de Uso, Isenção de Responsabilidade e Aviso de Privacidade
Versão $TERMS_VERSION

Ao usar o GluView, você declara que leu, entendeu e concorda com os termos abaixo. Leia com atenção.

1. NATUREZA DO APLICATIVO
O GluView é uma ferramenta pessoal de VISUALIZAÇÃO e ACOMPANHAMENTO de dados de glicemia. Ele NÃO é um dispositivo médico e NÃO possui certificação ou registro de órgão regulador (ANVISA, FDA ou equivalente). O GluView depende de hardware externo (sensor de glicemia contínua compatível e, opcionalmente, smartwatch) — ele não gera nem estima glicemia sem um sensor conectado.

2. NÃO SUBSTITUI O APLICATIVO OFICIAL
O GluView NÃO substitui o aplicativo oficial do fabricante do seu sensor, o leitor oficial, nem um medidor de glicemia de ponta de dedo. Use sempre o aplicativo e os equipamentos oficiais como sua referência principal, siga as instruções de uso do fabricante (inclusive o prazo de uso do sensor) e mantenha o suporte oficial do fabricante como seu canal para defeitos do sensor. O GluView é um complemento independente.

3. PROJETO INDEPENDENTE E DE BOA-FÉ
O GluView é um projeto independente, de código aberto (licença GPLv3) e sem fins lucrativos. Ele NÃO é afiliado, patrocinado, autorizado nem aprovado por Abbott, Sibionics, Dexcom, Roche, Xiaomi ou qualquer outro fabricante. As marcas citadas pertencem aos seus donos e aparecem apenas para indicar compatibilidade. A leitura direta de alguns sensores usa bibliotecas dos próprios fabricantes, que não fazem parte do código-fonte do GluView, com o único objetivo de permitir que você acesse os dados do SEU próprio sensor (interoperabilidade). O GluView não altera o firmware do sensor. Fabricantes ou titulares de direitos que tenham qualquer objeção podem escrever para glusync.gluview@gmail.com — toda manifestação será tratada de boa-fé e com prioridade.

4. PRECISÃO DOS DADOS
As glicemias exibidas são estimativas geradas a partir do sinal do sensor e podem conter erros, atrasos, lacunas ou valores incorretos, inclusive por falhas de Bluetooth, bateria, posicionamento do sensor, calibração ou idade do sensor. Os valores podem divergir do aplicativo oficial e do seu medidor de referência.

5. DECISÕES DE SAÚDE SÃO SUA RESPONSABILIDADE
NUNCA tome decisões de tratamento — em especial doses de insulina, ingestão de carboidratos ou medicamentos — baseando-se apenas no GluView. Sempre confirme com um medidor de ponta de dedo ou com o aplicativo oficial e siga as orientações do seu médico ou equipe de saúde. A gestão da sua saúde é de sua exclusiva responsabilidade. O diário, a base de alimentos (valores aproximados — confira o rótulo), os lembretes, os avisos e a tela Padrões são apenas registro e observação do que já aconteceu: nada disso é recomendação de dose ou de tratamento, e um lembrete pode atrasar ou não tocar.

6. EMERGÊNCIAS
O GluView não envia chamadas de emergência nem garante alertas em tempo real. Não dependa dele para detectar hipoglicemias ou hiperglicemias graves — isso vale também para quem usa o app como seguidor/cuidador de outra pessoa. Em caso de emergência, procure atendimento médico imediatamente.

7. ISENÇÃO DE RESPONSABILIDADE
O GluView é fornecido "no estado em que se encontra", sem garantias de qualquer tipo. Na máxima extensão permitida por lei, os desenvolvedores NÃO se responsabilizam por quaisquer danos, prejuízos, agravos à saúde ou consequências decorrentes do uso ou da impossibilidade de uso do aplicativo ou da confiança nos dados por ele exibidos.

8. PRIVACIDADE E DADOS PESSOAIS (LGPD)
8.1 Responsável: o mantenedor do projeto GluView/GluSync (pessoa física). Contato para qualquer assunto de privacidade: glusync.gluview@gmail.com.
8.2 O que tratamos: dados da conta (e-mail, nome de exibição e senha, guardada só como resumo criptográfico); dados de saúde (glicemia, tendência, informações do sensor e, se você usar, dados de bomba de insulina); dados do diário que você registrar (refeições, carboidratos, insulina, ponta de dedo, cetona, remédios, exercício, notas) e seus lembretes; dados lidos do Health Connect, se você ligar (exercício, sono, ciclo menstrual, refeições e glicemia de outros apps); e dados técnicos (identificador e estado do sensor e do aparelho e registros de diagnóstico).
8.3 Onde ficam: as leituras e o diário ficam sempre no seu aparelho e você pode usar o GluView 100% local. Leituras e diário só vão para a nuvem (servidor GluSync) se a nuvem estiver liberada para a sua conta. Os lembretes e os dados lidos do Health Connect nunca saem do aparelho. Registros de diagnóstico só saem do aparelho quando você toca em "Enviar log para o suporte".
8.4 Para quê: exibir suas leituras, gerar alertas e relatórios, sincronizar entre aparelhos e permitir o acompanhamento por quem você autorizar. Dados de saúde são tratados com base no seu consentimento específico, dado ao aceitar estes termos; você pode revogá-lo a qualquer momento.
8.5 O que NÃO fazemos: não vendemos, não alugamos e não compartilhamos seus dados com terceiros para publicidade ou marketing; não exibimos anúncios; não usamos seus dados para criar perfis comerciais.
8.6 Compartilhamento: seus dados só ficam visíveis a outra pessoa se você aceitar explicitamente um vínculo de acompanhamento com ela, e você pode revogá-lo a qualquer momento. Para funcionar, a nuvem usa provedores de hospedagem, banco de dados e envio de e-mail, que podem armazenar os dados fora do Brasil, sob as salvaguardas da LGPD para transferência internacional.
8.7 Por quanto tempo: na nuvem, as leituras são mantidas por até 400 dias; no aparelho, pelo prazo que você escolher em Ajustes (padrão de 100 dias); o diário fica no aparelho até você apagar; registros de diagnóstico, por 7 dias.
8.8 Segurança: a comunicação com o servidor é criptografada (HTTPS) e as credenciais de acesso ficam cifradas no aparelho. Você é responsável por manter sua senha e seu aparelho seguros.
8.9 Seus direitos: você pode pedir acesso, correção, cópia ou exclusão dos seus dados e revogar o consentimento pelo e-mail glusync.gluview@gmail.com.
8.10 Menores de 18 anos só podem usar o GluView com o acompanhamento e o consentimento de um responsável legal.
8.11 Health Connect: é desligado por padrão e cada tipo de dado liga separado. Se você ligar, o GluView pode gravar no Health Connect as leituras do sensor, as pontas de dedo e as refeições do diário, para outros apps de saúde do seu celular verem, e ler de outros apps exercício, sono, ciclo menstrual, refeições e glicemia. O que é lido fica só no aparelho, serve ao diário e à tela Padrões e não é enviado ao GluSync nem a terceiros. Você pode desligar cada tipo e revogar as permissões no próprio Health Connect a qualquer momento.
8.12 Relógio Wear OS: o que você registra no relógio vai para o diário do celular pelo próprio Android (Wear OS) e segue as mesmas regras do diário.

9. CÓDIGO ABERTO E VERSÕES
O código-fonte do GluView é público. Versões modificadas e distribuídas por terceiros não são o GluView oficial e não são cobertas por estes termos. O app oficial é distribuído pelas Releases do repositório GluSync-GluView/GluView e se atualiza pelo próprio app.

10. ACEITE
Ao tocar em "Li e aceito os termos", você confirma estar ciente de todas as limitações acima, consente com o tratamento dos seus dados de saúde nos termos da seção 8 e assume total responsabilidade pelo uso do GluView no acompanhamento da sua saúde ou da saúde de quem você acompanha.
""".trimIndent()

val TERMS_TEXT_EN = """
GluView — Terms of Use, Disclaimer and Privacy Notice
Version $TERMS_VERSION

By using GluView, you confirm that you have read, understood, and agree to the terms below. Please read carefully.

1. NATURE OF THE APPLICATION
GluView is a personal tool for VIEWING and TRACKING glucose data. It is NOT a medical device and holds NO certification or registration with any regulatory body (ANVISA, FDA, or equivalent). GluView depends on external hardware (a compatible continuous glucose sensor and, optionally, a smartwatch) — it does not generate or estimate glucose values without a connected sensor.

2. IT DOES NOT REPLACE THE OFFICIAL APP
GluView does NOT replace your sensor manufacturer's official app, the official reader, or a fingerstick glucose meter. Always use the official app and equipment as your main reference, follow the manufacturer's instructions for use (including the sensor wear period), and keep the manufacturer's official support as your channel for sensor defects. GluView is an independent complement.

3. INDEPENDENT, GOOD-FAITH PROJECT
GluView is an independent, open-source (GPLv3), non-profit project. It is NOT affiliated with, sponsored, authorized, or endorsed by Abbott, Sibionics, Dexcom, Roche, Xiaomi, or any other manufacturer. The trademarks mentioned belong to their owners and appear only to indicate compatibility. Direct reading of some sensors uses the manufacturers' own libraries, which are not part of GluView's source code, solely so that you can access the data of YOUR own sensor (interoperability). GluView does not modify the sensor firmware. Manufacturers or rights holders with any objection can write to glusync.gluview@gmail.com — every request will be handled in good faith and with priority.

4. DATA ACCURACY
Displayed glucose values are estimates generated from the sensor signal and may contain errors, delays, gaps, or incorrect values, including due to Bluetooth failures, battery issues, sensor placement, calibration, or sensor age. Values may differ from the official app and from your reference meter.

5. HEALTH DECISIONS ARE YOUR RESPONSIBILITY
NEVER make treatment decisions — especially insulin doses, carbohydrate intake, or medication — based solely on GluView. Always confirm with a fingerstick meter or the official app and follow the guidance of your doctor or healthcare team. Managing your health is your sole responsibility. The diary, the food database (approximate values — check the label), reminders, prompts and the Patterns screen are only records and observations of what already happened: none of it is dose or treatment advice, and a reminder may be late or not ring.

6. EMERGENCIES
GluView does not place emergency calls and does not guarantee real-time alerts. Do not rely on it to detect severe hypoglycemia or hyperglycemia — this also applies if you use the app as a follower/caregiver tracking someone else. In an emergency, seek medical care immediately.

7. DISCLAIMER OF LIABILITY
GluView is provided "as is," without warranties of any kind. To the fullest extent permitted by law, its developers are NOT liable for any damages, losses, health harm, or consequences arising from the use or inability to use the app, or from reliance on the data it displays.

8. PRIVACY AND PERSONAL DATA (Brazil's LGPD)
8.1 Controller: the maintainer of the GluView/GluSync project (an individual). Contact for any privacy matter: glusync.gluview@gmail.com.
8.2 What we process: account data (email, display name and password, stored only as a cryptographic hash); health data (glucose, trend, sensor information and, if you use it, insulin pump data); diary data you log (meals, carbs, insulin, finger sticks, ketones, medications, exercise, notes) and your reminders; data read from Health Connect, if you turn it on (exercise, sleep, menstrual cycle, meals and glucose from other apps); and technical data (sensor and device identifiers and status, and diagnostic logs).
8.3 Where it is stored: readings and the diary always stay on your device, and you can use GluView 100% locally. Readings and the diary only go to the cloud (GluSync server) if cloud access is approved for your account. Reminders and data read from Health Connect never leave the device. Diagnostic logs only leave the device when you tap "Send log to support".
8.4 Why: to display your readings, generate alerts and reports, sync across devices, and allow following by people you authorize. Health data is processed based on your specific consent, given by accepting these terms; you can withdraw it at any time.
8.5 What we do NOT do: we do not sell, rent, or share your data with third parties for advertising or marketing; we show no ads; we do not use your data to build commercial profiles.
8.6 Sharing: your data is only visible to another person if you explicitly accept a following link with them, and you can revoke it at any time. To work, the cloud uses hosting, database, and email providers, which may store data outside Brazil under the LGPD safeguards for international transfer.
8.7 Retention: in the cloud, readings are kept for up to 400 days; on the device, for the period you choose in Settings (100 days by default); the diary stays on the device until you delete it; diagnostic logs, for 7 days.
8.8 Security: communication with the server is encrypted (HTTPS) and access credentials are encrypted on the device. You are responsible for keeping your password and device secure.
8.9 Your rights: you can request access, correction, a copy, or deletion of your data, and withdraw consent, by emailing glusync.gluview@gmail.com.
8.10 People under 18 may only use GluView with the supervision and consent of a legal guardian.
8.11 Health Connect: it is off by default and each data type is turned on separately. If you turn it on, GluView may write sensor readings, finger sticks and diary meals to Health Connect, so other health apps on your phone can see them, and read exercise, sleep, menstrual cycle, meals and glucose from other apps. What is read stays only on the device, is used for the diary and the Patterns screen, and is not sent to GluSync or third parties. You can turn each type off and revoke permissions in Health Connect itself at any time.
8.12 Wear OS watch: what you log on the watch goes to the phone's diary through Android itself (Wear OS) and follows the same rules as the diary.

9. OPEN SOURCE AND VERSIONS
GluView's source code is public. Versions modified and distributed by third parties are not the official GluView and are not covered by these terms. The official app is distributed through the Releases of the GluSync-GluView/GluView repository and updates itself from within the app.

10. ACCEPTANCE
By tapping "I have read and accept the terms," you confirm you are aware of all the limitations above, consent to the processing of your health data as described in section 8, and take full responsibility for using GluView to track your own health or the health of someone you follow.
""".trimIndent()
