package de.polocloud.database

import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DatabaseSerializerTest {

    @Serializable
    private data class Payload(val id: Int, val label: String, val active: Boolean)

    @Test
    fun `serializes and deserializes a round trip`() {
        val original = Payload(7, "hello", active = true)

        val json = DatabaseSerializer.serialize(original, Payload::class)
        val restored = DatabaseSerializer.deserialize(json, Payload::class)

        assertEquals(original, restored)
    }

    @Test
    fun `unknown JSON keys are ignored rather than failing deserialization`() {
        val restored = DatabaseSerializer.deserialize(
            """{"id":1,"label":"x","active":false,"extraField":"ignored"}""",
            Payload::class
        )

        assertEquals(Payload(1, "x", active = false), restored)
    }

    private class NotSerializable(val value: Int)

    @Test
    fun `serializing a class without the Serializable annotation fails`() {
        assertThrows(Exception::class.java) {
            DatabaseSerializer.serialize(NotSerializable(1), NotSerializable::class)
        }
    }
}
