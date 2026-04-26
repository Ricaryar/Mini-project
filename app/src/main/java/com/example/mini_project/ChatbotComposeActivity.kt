package com.example.mini_project

import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)

class ChatbotComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocalChatBot.init(this)
        setContent {
            MaterialTheme {
                ChatbotScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatbotScreen() {
    val context = LocalContext.current
    val input = remember { mutableStateOf("") }
    val isLoading = remember { mutableStateOf(false) }
    val messages: SnapshotStateList<ChatMessage> = remember {
        mutableStateListOf<ChatMessage>().apply {
            val saved = loadChatHistory(context)
            if (saved.isEmpty()) {
                add(ChatMessage(LocalChatBot.welcomeMessage(), false))
            } else {
                addAll(saved)
            }
        }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val maxMessages = 120
    val quickQuestions = listOf("幫助", "NFC 怎麼演示", "WiFi 連不上", "項目介紹")

    LaunchedEffect(messages.size) {
        if (messages.size > maxMessages) {
            val overflow = messages.size - maxMessages
            repeat(overflow) { messages.removeAt(0) }
        }
        saveChatHistory(context, messages)
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本地智慧助理") },
                actions = {
                    TextButton(
                        onClick = {
                            messages.clear()
                            messages.add(ChatMessage(LocalChatBot.welcomeMessage(), false))
                            clearChatHistory(context)
                        },
                    ) {
                        Text("清空記錄")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 8.dp),
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    count = messages.size,
                    key = { index -> index },
                ) { index ->
                    MessageBubble(messages[index])
                }
            }
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = "你可以試試：",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            ) {
                items(quickQuestions.size) { idx ->
                    val q = quickQuestions[idx]
                    AssistChip(
                        onClick = { input.value = q },
                        label = { Text(q) },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input.value,
                    onValueChange = { input.value = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("輸入問題…") },
                    singleLine = false,
                    maxLines = 3,
                )
                Button(
                    enabled = !isLoading.value && input.value.trim().isNotEmpty(),
                    onClick = {
                        val text = input.value.trim()
                        if (text.isEmpty()) return@Button
                        messages.add(ChatMessage(text, true))
                        input.value = ""
                        isLoading.value = true

                        scope.launch {
                            // 人工延迟用于显示“加载中”状态，便于演示智能问答流程。
                            delay(600)
                            messages.add(ChatMessage(LocalChatBot.reply(text), false))
                            isLoading.value = false
                        }
                    },
                ) {
                    Text("發送")
                }
            }
            if (isLoading.value) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp, start = 6.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "思考中…",
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (msg.isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = msg.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = formatTime(msg.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val CHAT_PREFS = "chatbot_prefs"
private const val CHAT_HISTORY_KEY = "chat_history_json"

private fun saveChatHistory(context: Context, messages: List<ChatMessage>) {
    val arr = JSONArray()
    for (m in messages) {
        val obj = JSONObject()
        obj.put("text", m.text)
        obj.put("isUser", m.isUser)
        obj.put("timestamp", m.timestamp)
        arr.put(obj)
    }
    context.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(CHAT_HISTORY_KEY, arr.toString())
        .apply()
}

private fun loadChatHistory(context: Context): List<ChatMessage> {
    val json = context.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)
        .getString(CHAT_HISTORY_KEY, null)
        ?: return emptyList()
    return try {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                add(
                    ChatMessage(
                        text = obj.optString("text"),
                        isUser = obj.optBoolean("isUser", false),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    ),
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun clearChatHistory(context: Context) {
    context.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(CHAT_HISTORY_KEY)
        .apply()
}

private fun formatTime(ts: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
}
