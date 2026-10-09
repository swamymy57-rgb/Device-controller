package com.balaswamy.voicecontrol.data.secure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiApiKeyRepositoryTest {
    private val storage = InMemoryApiKeyStorage()
    private val repository = GeminiApiKeyRepository(storage)

    @Test
    fun tracksConfiguredStateAcrossSaveAndClear() {
        assertFalse(repository.isConfigured())
        assertNull(repository.getForRequest())

        repository.save("  test-key-not-a-credential  ")

        assertTrue(repository.isConfigured())
        assertEquals("test-key-not-a-credential", repository.getForRequest())

        repository.clear()

        assertFalse(repository.isConfigured())
        assertNull(repository.getForRequest())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankKeys() {
        repository.save(" \n ")
    }

    private class InMemoryApiKeyStorage : GeminiApiKeyStorage {
        private var savedKey: String? = null

        override fun saveGeminiApiKey(apiKey: String) {
            savedKey = apiKey
        }

        override fun getGeminiApiKey(): String? = savedKey

        override fun hasGeminiApiKey(): Boolean = savedKey != null

        override fun clearGeminiApiKey() {
            savedKey = null
        }
    }
}
