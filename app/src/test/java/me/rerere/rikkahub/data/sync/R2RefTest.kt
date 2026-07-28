package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.sync.r2.R2Ref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class R2RefTest {

    @Test
    fun testParseAndToString() {
        val ref = R2Ref("acct123", "chat-uploads/photo.jpg")
        assertEquals("r2://acct123/chat-uploads/photo.jpg", ref.toString())

        val parsed = R2Ref.parse("r2://acct123/chat-uploads/photo.jpg")
        assertEquals("acct123", parsed?.acctId)
        assertEquals("chat-uploads/photo.jpg", parsed?.key)

        assertNull(R2Ref.parse("http://example.com/photo.jpg"))
        assertNull(R2Ref.parse("r2://invalid"))
    }
}
