package me.rerere.rikkahub.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.sync.d1.D1Table
import org.junit.Assert.assertEquals
import org.junit.Test

@Serializable
private data class TestItem(
    val id: String,
    val name: String,
    val value: Long,
)

private class TestTable : D1Table<TestItem>("test_table", "id", TestItem.serializer()) {
    override fun toRow(item: TestItem): Map<String, Any?> = mapOf(
        "id" to item.id,
        "name" to item.name,
        "value" to item.value,
    )

    override fun fromRow(row: JsonObject): TestItem? {
        val id = row.string("id") ?: return null
        val name = row.string("name") ?: ""
        val value = row.long("value") ?: 0L
        return TestItem(id, name, value)
    }
}

class D1TableTest {

    @Test
    fun testToRowAndFromRow() {
        val table = TestTable()
        val item = TestItem("123", "hello", 100L)
        val row = table.toRow(item)

        assertEquals("123", row["id"])
        assertEquals("hello", row["name"])
        assertEquals(100L, row["value"])

        val jsonRow = JsonObject(
            mapOf(
                "id" to JsonPrimitive("123"),
                "name" to JsonPrimitive("hello"),
                "value" to JsonPrimitive("100"),
            )
        )
        val restored = table.fromRow(jsonRow)
        assertEquals(item, restored)
    }
}
