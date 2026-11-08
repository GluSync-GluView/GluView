# ── DTOs / Entidades (Gson + Room) ────────────────────────────────────────────
-keep class com.gluview.app.data.remote.dto.** { *; }
-keep class com.gluview.app.data.local.entity.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# ── Retrofit ──────────────────────────────────────────────────────────────────
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# ── Gson ──────────────────────────────────────────────────────────────────────
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# ── OkHttp ────────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── Room ──────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-dontwarn androidx.room.**

# ── Hilt / Dagger ─────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class *
-keep @dagger.hilt.android.AndroidEntryPoint class *
-keep @dagger.hilt.android.lifecycle.HiltViewModel class *
-dontwarn dagger.hilt.**

# ── SignalR ───────────────────────────────────────────────────────────────────
-keep class com.microsoft.signalr.** { *; }
-dontwarn com.microsoft.signalr.**
# O SLF4J procura esta implementação opcional por reflexão. O SignalR funciona com o logger
# fallback quando ela não está empacotada, portanto não há classe obrigatória a manter.
-dontwarn org.slf4j.impl.StaticLoggerBinder

# ── Glance Widget ─────────────────────────────────────────────────────────────
-keep class androidx.glance.** { *; }
-dontwarn androidx.glance.**
-keep class com.gluview.app.widget.** { *; }

# ── Biometric ─────────────────────────────────────────────────────────────────
-keep class androidx.biometric.** { *; }
-dontwarn androidx.biometric.**

# ── Coroutines ────────────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ── Kotlin ────────────────────────────────────────────────────────────────────
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}

# ── DataStore ─────────────────────────────────────────────────────────────────
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ── Sibionics JNI (Frente B) ──────────────────────────────────────────────────
# Ambas as .so usam GetFieldID com nomes exatos — obfuscação quebraria o binding em runtime.
# E8 do plano OTA: antes só v1_1_0 era mantido, mas o app usa a v1_1_2 (Sibionics China), carregada
# também por Class.forName em SibionicsV112Protocol — o release quebraria a leitura em silêncio.
-keep class com.algorithm.** { *; }
-keep class com.no.sisense.enanddecryption.** { *; }

# ── Abbott / Libre 2 Plus (lib nativa da Abbott) ─────────────────────────────
# A .so registra ~47 métodos native via RegisterNatives casando com DataProcessingNative pelo nome,
# e o subenv (jnisub.cpp) responde FindClass/GetFieldID com os nomes reais das classes de saída.
# AbbottNative também carrega por Class.forName. Nada aqui pode ser renomeado ou removido.
-keep class com.abbottdiabetescare.** { *; }

# ── Envio ao GluSync pelo SignalR (Gson interno do cliente SignalR) ─────────
# Sem isto o R8 renomeia os campos e o servidor recebe "a".."e" em vez de date/sgv/direction.
-keep class com.gluview.app.data.remote.GluSyncUploader$Entry { *; }

# ── Mi Band: config.json do visor lido com Gson ──────────────────────────────
-keep class com.gluview.app.watchface.miband.firmware.WatchFaceParts.ConfigPOJO.** { *; }

# ── Stack traces legíveis no release ─────────────────────────────────────────
# Mantém arquivo/linha; o mapping.txt de cada versão é arquivado pelo tools/publish-release.ps1.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Plano 16 (visor no Wear OS): validador oficial do Watch Face Push no celular ──────────────
# O validador e o Xerces (validação do XML do mostrador) carregam classes por reflexão/serviço;
# sem isto o R8 pode cortar algo que só falha em tempo de execução, no release.
-keep class com.google.android.wearable.watchface.validator.** { *; }
-keep class org.apache.xerces.** { *; }
-keep class org.eclipse.wst.xml.xpath2.** { *; }
# Referência do Xerces (asserções XSD 1.1) a uma classe que não vem no jar do xpath2 — igual no debug.
-dontwarn org.eclipse.wst.xml.xpath2.processor.PsychoPathXPathTypeHelper
# Recurso opcional do Xerces (resolvedor de catálogos XML), não usado pelo validador e ausente do jar.
-dontwarn org.apache.xml.resolver.**
