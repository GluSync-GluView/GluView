# Gadgetbridge-derived (AGPL-3.0) - F7.4

Este diretório guarda referências/arquivos copiados do Gadgetbridge (AGPL-3.0,
https://codeberg.org/Freeyourgadget/Gadgetbridge) para o porte do suporte a
Mi Band 7/8/9 e dispositivos Xiaomi novos. Os arquivos originais mantêm cabeçalhos
de licença quando copiados para código-fonte compilado do módulo.

Coordenadores inspecionados no clone local `C:\claude\_research\gadgetbridge`:
`devices/xiaomi/watches/MiBand7ProCoordinator.java`,
`MiBand8Coordinator.java`, `MiBand8ProCoordinator.java`,
`MiBand9Coordinator.java`, `MiBand9ProCoordinator.java`.

Nesta etapa os coordenadores copiados não foram integrados à compilação porque
dependem da base de classes/recursos do Gadgetbridge; a integração compilável do
módulo fica em `src/main/java/com/gluview/wearables/xiaomi/` com maturidade
**Sem teste com hardware real**.
