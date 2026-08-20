package de.polocloud.database

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DatabaseKeyTest {

    private data class Plain(val x: Int)

    @RepositoryName("custom_table_name")
    private data class Named(val x: Int)

    @Test
    fun `falls back to the class's simple name when RepositoryName is absent`() {
        assertEquals("Plain", DatabaseKey(Plain::class).id())
    }

    @Test
    fun `uses the RepositoryName annotation's value when present`() {
        assertEquals("custom_table_name", DatabaseKey(Named::class).id())
    }
}
