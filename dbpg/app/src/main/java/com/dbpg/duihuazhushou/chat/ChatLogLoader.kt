package com.dbpg.duihuazhushou.chat

import android.content.Context
import android.net.Uri
import com.dbpg.stcore.jsonl.SillyTavernChatLogReader

/**
 * .jsonl 聊天记录读取的 Android 薄封装：
 * 只负责「打开文件流 → 交给 stcore 的解析器 → 转成 ChatItem」。解析逻辑全在 stcore（已单测）。
 */
object ChatLogLoader {

    fun load(context: Context, uri: Uri): List<ChatItem> {
        val text = try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val entries = SillyTavernChatLogReader.parse(text)
        return entries.mapIndexed { i, e ->
            ChatItem(id = "jsonl:$i", role = e.role, content = e.content)
        }
    }
}
