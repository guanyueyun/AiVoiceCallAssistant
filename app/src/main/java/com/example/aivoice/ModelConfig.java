package com.example.aivoice;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户可编辑的模型连接配置。
 *
 * 当前版本只真正支持 OpenAI-compatible chat/completions。
 * provider、capabilities、priority 等字段保留下来，是为了以后扩展模型列表时
 * 不必再次调整本地存储格式。
 */
final class ModelConfig {
    static final String PROTOCOL_OPENAI = "OpenAI Compatible";

    String name = "未配置模型";
    String provider = "OpenAI-compatible";
    String baseUrl = "";
    String apiKey = "";
    String modelId = "";
    String speechModelId = "gpt-4o-mini-transcribe";
    String volcAppId = "";
    String volcAppKey = "";
    String volcToken = "";
    String volcResourceId = "volc.speech.dialog";
    String volcCluster = "volcengine_streaming_common";
    String volcBotName = "豆芽";
    String volcPayload = "";
    String protocol = PROTOCOL_OPENAI;
    List<String> capabilities = new ArrayList<>();
    int timeoutMs = 15000;
    boolean enabled = false;
    int priority = 1;

    ModelConfig() {
        // 当前 MainActivity 只消费 chat 能力。
        capabilities.add("chat");
    }

    JSONObject toJson() throws JSONException {
        // 显式保存所有字段，保证用户在配置页填写的内容重启后能完整还原。
        JSONObject json = new JSONObject();
        json.put("name", name);
        json.put("provider", provider);
        json.put("baseUrl", baseUrl);
        json.put("apiKey", apiKey);
        json.put("modelId", modelId);
        json.put("speechModelId", speechModelId);
        json.put("volcAppId", volcAppId);
        json.put("volcAppKey", volcAppKey);
        json.put("volcToken", volcToken);
        json.put("volcResourceId", volcResourceId);
        json.put("volcCluster", volcCluster);
        json.put("volcBotName", volcBotName);
        json.put("volcPayload", volcPayload);
        json.put("protocol", protocol);
        json.put("timeoutMs", timeoutMs);
        json.put("enabled", enabled);
        json.put("priority", priority);
        JSONArray caps = new JSONArray();
        for (String capability : capabilities) {
            caps.put(capability);
        }
        json.put("capabilities", caps);
        return json;
    }

    static ModelConfig fromJson(JSONObject json) throws JSONException {
        ModelConfig config = new ModelConfig();
        // 使用 opt* 读取，新增字段后旧配置仍能正常加载。
        config.name = json.optString("name", config.name);
        config.provider = json.optString("provider", config.provider);
        config.baseUrl = json.optString("baseUrl", config.baseUrl);
        config.apiKey = json.optString("apiKey", config.apiKey);
        config.modelId = json.optString("modelId", config.modelId);
        config.speechModelId = json.optString("speechModelId", config.speechModelId);
        config.volcAppId = json.optString("volcAppId", config.volcAppId);
        config.volcAppKey = json.optString("volcAppKey", config.volcAppKey);
        config.volcToken = json.optString("volcToken", config.volcToken);
        config.volcResourceId = json.optString("volcResourceId", config.volcResourceId);
        config.volcCluster = json.optString("volcCluster", config.volcCluster);
        config.volcBotName = json.optString("volcBotName", config.volcBotName);
        config.volcPayload = json.optString("volcPayload", config.volcPayload);
        config.protocol = json.optString("protocol", config.protocol);
        config.timeoutMs = json.optInt("timeoutMs", config.timeoutMs);
        config.enabled = json.optBoolean("enabled", config.enabled);
        config.priority = json.optInt("priority", config.priority);
        config.capabilities.clear();
        JSONArray caps = json.optJSONArray("capabilities");
        if (caps != null) {
            for (int i = 0; i < caps.length(); i++) {
                config.capabilities.add(caps.optString(i));
            }
        }
        if (config.capabilities.isEmpty()) {
            // 旧配置或损坏 JSON 没有 capabilities 时，补一个最小可用能力。
            config.capabilities.add("chat");
        }
        return config;
    }

    String capabilityText() {
        // 给未来模型列表展示能力标签预留；当前卡片主要展示 endpoint 和状态。
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < capabilities.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(capabilities.get(i));
        }
        return builder.toString();
    }
}
