package com.example.aivoice;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * 文字聊天历史和最近一次通话 transcript 的小型持久化层。
 *
 * 当前数据量很小，而且只有一个活动会话，用 SharedPreferences 足够。
 * 如果后续增加多会话、搜索或大量历史记录，这个类就是迁移到 Room/SQLite 的边界。
 */
final class ConversationStore {
    private static final String PREF = "ai_voice_conversations";
    private static final String KEY_MESSAGES = "active_messages";
    private static final String KEY_TRANSCRIPT = "last_call_transcript";

    private final SharedPreferences prefs;

    ConversationStore(Context context) {
        prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    List<ChatMessage> loadMessages() {
        List<ChatMessage> messages = new ArrayList<>();
        String raw = prefs.getString(KEY_MESSAGES, "[]");
        try {
            // 本地 JSON 损坏不应该导致应用启动崩溃。
            // 解析失败时返回空列表，至少让用户能继续使用新会话。
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                messages.add(ChatMessage.fromJson(array.getJSONObject(i)));
            }
        } catch (JSONException ignored) {
            messages.clear();
        }
        return messages;
    }

    void saveMessages(List<ChatMessage> messages) {
        JSONArray array = new JSONArray();
        try {
            // 先完整构造 JSONArray，再一次性写入 SharedPreferences。
            // 如果某条消息序列化失败，不会留下半截历史记录。
            for (ChatMessage message : messages) {
                array.put(message.toJson());
            }
        } catch (JSONException ignored) {
            return;
        }
        prefs.edit().putString(KEY_MESSAGES, array.toString()).apply();
    }

    void saveTranscript(String transcript) {
        // 当前版本只保留最近一次通话 transcript，不做完整通话历史。
        prefs.edit().putString(KEY_TRANSCRIPT, transcript).apply();
    }

    String loadTranscript() {
        return prefs.getString(KEY_TRANSCRIPT, "");
    }
}
