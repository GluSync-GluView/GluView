# Harness visual do GluView

É o equivalente Android do `apps/web/visual-audit/` do portal: mock determinístico, captura de telas e auditoria. Foi criado na Fase 0 do redesign visual.

Tudo aqui é teste JVM (Robolectric com gráficos nativos + Roborazzi). **Nada entra no APK.** Nunca rode teste instrumentado no S23 FE do paciente: o `am instrument` mata o processo e derruba a coleta do sensor.

## Peças

| Arquivo | O quê |
|---|---|
| `GlucoseFixtures` | Glicemia determinística, sem Random e sem relógio. `FIXED_NOW` = 2026-10-02 14:37 −03:00. |
| `Scenarios` | Estados de tela montados com as fixtures: Home, seguidor, paciente acompanhado e Histórico. |
| `ScreenHarness` | Perfis (S23 FE, 360dp e Pixel 6), matriz de fonte, tema e idioma, relógio fixo (`LocalClock`), barra inferior real (`MainBottomBar`) e captura. |
| `VisualAudit` | Auditoria por captura: texto cortado, reticências, palavra quebrada, fora da tela, alvo pequeno, sem rótulo e texto depois do gráfico (regra do Moto). |
| `VisualAuditSelfTest` | Controle positivo: uma tela com cada defeito de propósito precisa ser acusada. |
| `ScreensCaptureTest` | As capturas. É lento, então só roda com `-Pgluview.visualaudit.screens=true`. |
| `RendererOracleTest` + `oracle/` | Gate pixel a pixel do motor do gráfico contra a cópia congelada do renderer. |
| `GraphGeometryCharacterizationTest` | Os números de hoje da geometria do gráfico. |
| `StringsAuditTest` | check-strings: chaves de pt sem tradução em en e literais pt no código de interface. Só pode melhorar. |
| `HarnessProbeTest` | Teste-sonda: o ambiente funciona (RNG, determinismo e 450dpi). |

## Comandos

PowerShell, com o JDK do Android Studio:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd GluView

# Suíte normal (inclui oráculo, caracterização, check-strings e auditoria-controle; sem capturas):
.\gradlew.bat :app:testDebugUnitTest --rerun

# Capturas + auditoria de uma rodada (ex.: "antes", "fase3"):
.\gradlew.bat :app:testDebugUnitTest --tests "*ScreensCaptureTest*" --rerun `
  "-Pgluview.visualaudit.screens=true" "-Pgluview.visualaudit.run=fase3" "-Proborazzi.test.record=true"

# Bitmaps do oráculo para inspeção:
.\gradlew.bat :app:testDebugUnitTest --tests "*RendererOracleTest*" --rerun "-Pgluview.visualaudit.dump=true"

# Registrar uma melhoria de i18n na linha de base:
.\gradlew.bat :app:testDebugUnitTest --tests "*StringsAuditTest*" "-Pgluview.visualaudit.updateBaseline=true"
```

A saída fica em `app/build/visual-audit/`: as capturas vão para `screens/<run>/<tela>/` e a auditoria para os `audit-<parte>.md` e `.tsv`. Rodadas que valem como prova são copiadas para uma pasta fora do git.

## Regras que aprendemos

- **Use sempre `--rerun`.** Teste em cache compara código velho com código velho. É o equivalente ao "reinicie o Vite" do portal.
- **O `TextLayoutResult` que vem da semântica não serve sob o Robolectric**: a largura do parágrafo vem igual à restrição máxima. Por isso a auditoria re-mede o texto com o `TextMeasurer`.
- **O Compose estende a área de toque para 48dp** sozinho. Por isso "alvo pequeno" mede o toque real (< 48dp) e o tamanho visível (< 32dp).
- **O Scaffold do M3 põe a TopAppBar depois do conteúdo na árvore de semântica.** Por isso a regra "texto depois do gráfico" só olha o que fica na faixa do gráfico ou abaixo dele.
- **Os insets são aproximados.** Barra de status e de gestos ficam em `Profiles` até serem medidos no aparelho com `adb shell dumpsys window`.
- **A fonte é a Roboto do android-all, não a da Samsung.** O aparelho real continua sendo a prova final, com `adb exec-out screencap`.
