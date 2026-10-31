package com.gluview.app.ui.screen.terms

/**
 * F7/T54 — Termos de uso do GluView com isenção de responsabilidade por saúde.
 * Disponível em português (padrão) e inglês — [TermsScreen] escolhe pelo idioma do app
 * ([com.gluview.app.data.datastore.AppPreferences.appLanguage]).
 *
 * Incremente [TERMS_VERSION] sempre que o TEXTO mudar (em qualquer idioma): o app exigirá
 * novo aceite de todos os usuários (o gate compara a versão aceita com esta).
 *
 * Mesmo o GluView não estando na Google Play por ora, este texto já segue as exigências da
 * Play Store para apps de saúde sem certificação médica: aviso de "não é dispositivo médico"
 * logo no início, dependência de hardware externo declarada, e descrição do tratamento de
 * dados pessoais/sensíveis (seção 6).
 */
const val TERMS_VERSION = 2

val TERMS_TEXT = """
GluView — Termos de Uso e Isenção de Responsabilidade
Versão $TERMS_VERSION

Ao usar o GluView, você declara que leu, entendeu e concorda com os termos abaixo. Leia com atenção.

1. NATUREZA DO APLICATIVO
O GluView é uma ferramenta pessoal de VISUALIZAÇÃO e ACOMPANHAMENTO de dados de glicemia. Ele NÃO é um dispositivo médico, NÃO possui certificação ou registro de órgão regulador (ANVISA, FDA ou equivalente) e NÃO substitui equipamentos médicos, o aplicativo oficial do seu sensor, nem um medidor de glicemia de ponta de dedo. O GluView depende de hardware externo (sensor de glicemia contínua compatível e, opcionalmente, smartwatch) para funcionar — ele não gera nem estima glicemia sem um sensor conectado.

2. PRECISÃO DOS DADOS
As glicemias exibidas são estimativas geradas a partir do sinal do sensor e podem conter erros, atrasos, lacunas ou valores incorretos, inclusive por falhas de Bluetooth, bateria, posicionamento do sensor, calibração ou idade do sensor. Os valores podem divergir do seu medidor de referência.

3. DECISÕES DE SAÚDE SÃO SUA RESPONSABILIDADE
NUNCA tome decisões de tratamento — em especial doses de insulina, ingestão de carboidratos ou medicamentos — baseando-se apenas no GluView. Sempre confirme com um medidor de ponta de dedo e siga as orientações do seu médico ou equipe de saúde. A gestão da sua saúde é de sua exclusiva responsabilidade.

4. EMERGÊNCIAS
O GluView não envia chamadas de emergência nem garante alertas em tempo real. Não dependa dele para detectar hipoglicemias ou hiperglicemias graves — isso vale também para quem usa o app como seguidor/cuidador de outra pessoa. Em caso de emergência, procure atendimento médico imediatamente.

5. ISENÇÃO DE RESPONSABILIDADE
O desenvolvedor do GluView fornece o aplicativo "no estado em que se encontra", sem garantias de qualquer tipo. Na máxima extensão permitida por lei, o desenvolvedor NÃO se responsabiliza por quaisquer danos, prejuízos, agravos à saúde ou consequências decorrentes do uso ou da impossibilidade de uso do aplicativo ou da confiança nos dados por ele exibidos.

6. DADOS E PRIVACIDADE
Se você usar o GluView no modo 100% local, suas glicemias ficam salvas apenas neste aparelho — nada é enviado a servidores. Se você criar uma conta e usar o GluSync, suas leituras também são armazenadas em um servidor na nuvem, associadas à sua conta, para permitir sincronização entre aparelhos e a função de Acompanhamento. Seus dados só ficam visíveis a outra pessoa se você aceitar explicitamente um vínculo de acompanhamento (seguidor ↔ paciente) com ela — você pode revogar esse acesso a qualquer momento. O GluView não vende nem compartilha seus dados de saúde com terceiros para fins de publicidade. Você é responsável por manter suas credenciais e seu dispositivo seguros.

7. ACEITE
Ao tocar em "Li e aceito os termos", você confirma estar ciente de todas as limitações acima e assume total responsabilidade pelo uso do GluView no acompanhamento da sua saúde ou da saúde de quem você acompanha.
""".trimIndent()

val TERMS_TEXT_EN = """
GluView — Terms of Use and Disclaimer
Version $TERMS_VERSION

By using GluView, you confirm that you have read, understood, and agree to the terms below. Please read carefully.

1. NATURE OF THE APPLICATION
GluView is a personal tool for VIEWING and TRACKING glucose data. It is NOT a medical device, holds NO certification or registration with any regulatory body (ANVISA, FDA, or equivalent), and does NOT replace medical equipment, your sensor's official app, or a fingerstick glucose meter. GluView depends on external hardware (a compatible continuous glucose sensor and, optionally, a smartwatch) to function — it does not generate or estimate glucose values without a connected sensor.

2. DATA ACCURACY
Displayed glucose values are estimates generated from the sensor signal and may contain errors, delays, gaps, or incorrect values, including due to Bluetooth failures, battery issues, sensor placement, calibration, or sensor age. Values may differ from your reference meter.

3. HEALTH DECISIONS ARE YOUR RESPONSIBILITY
NEVER make treatment decisions — especially insulin doses, carbohydrate intake, or medication — based solely on GluView. Always confirm with a fingerstick meter and follow the guidance of your doctor or healthcare team. Managing your health is your sole responsibility.

4. EMERGENCIES
GluView does not place emergency calls and does not guarantee real-time alerts. Do not rely on it to detect severe hypoglycemia or hyperglycemia — this also applies if you use the app as a follower/caregiver tracking someone else. In an emergency, seek medical care immediately.

5. DISCLAIMER OF LIABILITY
GluView's developer provides the app "as is," without warranties of any kind. To the fullest extent permitted by law, the developer is NOT liable for any damages, losses, health harm, or consequences arising from the use or inability to use the app, or from reliance on the data it displays.

6. DATA AND PRIVACY
If you use GluView in 100% local mode, your glucose readings are stored only on this device — nothing is sent to any server. If you create an account and use GluSync, your readings are also stored on a cloud server, tied to your account, to enable syncing across devices and the Following feature. Your data is only visible to another person if you explicitly accept a following link (follower ↔ patient) with them — you can revoke that access at any time. GluView does not sell or share your health data with third parties for advertising purposes. You are responsible for keeping your credentials and device secure.

7. ACCEPTANCE
By tapping "I have read and accept the terms," you confirm you are aware of all the limitations above and take full responsibility for using GluView to track your own health or the health of someone you follow.
""".trimIndent()
