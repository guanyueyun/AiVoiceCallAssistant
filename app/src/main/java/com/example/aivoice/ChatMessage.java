package com.example.aivoice;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 单条聊天消息的数据结构。
 *
 * 当前应用只保存一条扁平消息列表，不区分多个会话；这符合当前版本的范围：
 * 一个文字聊天记录 + 最近一次通话 transcript。后续如果要支持多会话，
 * 可以在这个对象上追加 conversationId，不需要推翻每条消息的结构。
 */
final class ChatMessage {
    // 消息类型会写入 JSON 持久化。除非做数据迁移，否则不要随便改这些字符串。
    // 旧记录类型无法识别时会回退成系统提示。
    static final String USER_TEXT = "user_text";
    static final String USER_AUDIO = "user_audio";
    static final String ASSISTANT_TEXT = "assistant_text";
    static final String SYSTEM_TIP = "system_tip";

    final String type;
    String content;
    final String modelName;
    final long createdAt;

    ChatMessage(String type, String content, String modelName) {
        this(type, content, modelName, System.currentTimeMillis());
    }

    private ChatMessage(String type, String content, String modelName, long createdAt) {
        this.type = type;
        this.content = content;
        this.modelName = modelName;
        this.createdAt = createdAt;
    }

    JSONObject toJson() throws JSONException {
        // JSON 字段显式写出，保持本地存储格式稳定。
        // 当前没有数据库迁移层，所以字段改名会直接影响旧数据读取。
        JSONObject json = new JSONObject();
        json.put("type", type);
        json.put("content", content);
        json.put("modelName", modelName);
        json.put("createdAt", createdAt);
        return json;
    }

    static ChatMessage fromJson(JSONObject json) {
        // 使用 opt* 读取，兼容旧版本或部分损坏的本地记录。
        // type 缺失时回退为 SYSTEM_TIP，避免未知内容伪装成用户或 AI 消息。
        return new ChatMessage(
                json.optString("type", SYSTEM_TIP),
                json.optString("content", ""),
                json.optString("modelName", ""),
                json.optLong("createdAt", System.currentTimeMillis())
        );
    }

    String displayText() {
        // 展示前缀在渲染时临时拼接，持久化的 content 保持干净。
        // 未来如果做新的 UI，不需要从 content 里再剥离“你/AI/系统”前缀。
        if (USER_TEXT.equals(type) || USER_AUDIO.equals(type)) {
            return "你：" + content;
        }
        if (ASSISTANT_TEXT.equals(type)) {
            return "AI：" + content;
        }
        return "系统：" + content;
    }
}
