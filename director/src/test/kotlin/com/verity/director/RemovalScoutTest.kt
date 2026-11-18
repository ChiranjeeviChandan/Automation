package com.verity.director

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemovalScoutTest {
    private val scout = RemovalScout(AnthropicOkHttpClient.builder().apiKey("test").build())

    @Test fun parsesPadsAndCapsSuggestions() {
        val item = """{"label":"man in red shirt","kind":"PERSON","box":{"left":0.1,"top":0.2,"right":0.3,"bottom":0.8},"reason":"photobomb"}"""
        val json = """{"suggestions":[${List(8) { item }.joinToString(",")}],"note":"two people behind the couple"}"""
        val r = assertNotNull(scout.parse(json))
        assertEquals(6, r.suggestions.size)
        val s = r.suggestions[0]
        assertEquals(RemovalKind.PERSON, s.kind)
        assertTrue(s.box.left < 0.1f && s.box.right > 0.3f && s.box.top < 0.2f && s.box.bottom > 0.8f)
    }

    @Test fun dropsDegenerateBoxesAndUnknownKinds() {
        val json = """{"suggestions":[{"label":"x","kind":"UFO","box":{"left":0.5,"top":0.5,"right":0.501,"bottom":0.9},"reason":""},
            {"label":"bin","kind":"UFO","box":{"left":0.6,"top":0.6,"right":0.7,"bottom":0.8},"reason":""}],"note":""}"""
        val r = assertNotNull(scout.parse(json))
        assertEquals(1, r.suggestions.size)
        assertEquals(RemovalKind.OBJECT, r.suggestions[0].kind)
    }

    @Test fun garbageIsNull() = assertNull(scout.parse("{"))

    @Test fun promptProtectsSubjectsAndCulture() {
        assertTrue(RemovalScout.SYSTEM.contains("Never suggest: the main subject"))
        assertTrue(RemovalScout.SYSTEM.contains("religious or cultural symbols"))
    }
}
