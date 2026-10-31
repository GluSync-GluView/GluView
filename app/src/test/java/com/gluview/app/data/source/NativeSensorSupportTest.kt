package com.gluview.app.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** L28 do redesign visual: o APK roda em aparelho 32 bits sem oferecer os sensores nativos. */
class NativeSensorSupportTest {

    @Test fun `só aceita processo 64 bits em aparelho arm64`() {
        assertTrue(NativeSensorSupport.supports(arrayOf("arm64-v8a"), is64BitProcess = true))   // S23 FE
        assertFalse(NativeSensorSupport.supports(emptyArray(), is64BitProcess = false))          // Moto Z2 Play (Android 32 bits)
        assertFalse(NativeSensorSupport.supports(null, is64BitProcess = false))
        assertFalse(NativeSensorSupport.supports(arrayOf("x86_64"), is64BitProcess = true))
        // APK forçado em 32 bits (adb install --abi armeabi-v7a) num aparelho arm64: sem as .so.
        assertFalse(NativeSensorSupport.supports(arrayOf("arm64-v8a"), is64BitProcess = false))
    }

    @Test fun `só Sibionics e Libre 2 dependem de biblioteca nativa`() {
        val native = DataSourceType.entries.filter { it.requiresNativeLibs }.toSet()
        assertEquals(
            setOf(DataSourceType.SIBIONICS_NACIONAL, DataSourceType.SIBIONICS_CHINES, DataSourceType.LIBRE2_PLUS),
            native
        )
        // GluSync (a fonte do seguidor) nunca depende de biblioteca nativa.
        assertFalse(DataSourceType.GLUSYNC.requiresNativeLibs)
    }
}
