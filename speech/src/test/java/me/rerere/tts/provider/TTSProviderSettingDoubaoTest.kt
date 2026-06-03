package me.rerere.tts.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TTSProviderSettingDoubaoTest {
    @Test
    fun doubao_defaults_are_expected() {
        val setting = TTSProviderSetting.Doubao()

        assertEquals("Doubao TTS", setting.name)
        assertEquals("http://localhost:1547/v1", setting.baseUrl)
        assertEquals("female-shaonv", setting.voice)
        assertEquals("sk-wei123", setting.apiKey)
        assertEquals(1.0f, setting.speed)
        assertEquals(0.0f, setting.pitch)
    }

    @Test
    fun doubao_is_registered_in_provider_types() {
        assertTrue(TTSProviderSetting.Types.contains(TTSProviderSetting.Doubao::class))
    }
}
