/*
 * Biblioteca de marcação para armeabi-v7a (aparelhos 32 bits). Não é carregada por ninguém: só
 * garante a pasta lib/armeabi-v7a no APK (e a instalação no Android 32 bits) mesmo que as
 * dependências androidx deixem de trazer .so para essa ABI.
 * Ver CMakeLists.txt e com.gluview.app.data.source.NativeSensorSupport.
 */
int gluview_abi_marker(void) { return 32; }
