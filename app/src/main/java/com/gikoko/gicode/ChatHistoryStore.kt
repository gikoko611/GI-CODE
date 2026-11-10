package com.gikoko.gicode

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class StoredChatMessage(val id: Long, val conversationId: String, val role: String, val text: String, val createdAt: Long)

class ChatHistoryStore(context: Context) : SQLiteOpenHelper(context, "gicode_history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id TEXT NOT NULL, role TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_messages_conversation ON messages(conversation_id, id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations are additive; existing history is not dropped.
    }
    fun add(conversationId: String, role: String, body: String) {
        require(role in setOf("system", "user", "assistant") && body.length <= 100_000)
        val values = ContentValues().apply {
            put("conversation_id", conversationId); put("role", role)
            put("body", body); put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertOrThrow("messages", null, values)
    }
    fun load(conversationId: String, limit: Int = 200): List<StoredChatMessage> {
        val result = mutableListOf<StoredChatMessage>()
        readableDatabase.rawQuery(
            "SELECT id, conversation_id, role, body, created_at FROM messages WHERE conversation_id=? ORDER BY id DESC LIMIT ?",
            arrayOf(conversationId, limit.coerceIn(1, 500).toString())
        ).use { c -> while (c.moveToNext()) result.add(StoredChatMessage(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4))) }
        return result.asReversed()
    }
    fun conversations(limit: Int = 100): List<String> {
        val result = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT conversation_id FROM messages GROUP BY conversation_id ORDER BY MAX(id) DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 500).toString())
        ).use { c -> while (c.moveToNext()) result.add(c.getString(0)) }
        return result
    }
    fun deleteConversation(conversationId: String) {
        writableDatabase.delete("messages", "conversation_id=?", arrayOf(conversationId))
    }
}
