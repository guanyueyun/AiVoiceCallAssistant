package com.example.aivoice;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型配置的持久化和默认模型选择逻辑。
 *
 * 这个类是唯一读写模型 SharedPreferences 的地方。
 * 聊天页、通话页、模型配置页都通过它获取模型，保证默认模型和协议过滤逻辑一致。
 */
final class ModelStore {
    private static final String PREF = "ai_voice_models";
    private static final String KEY_MODELS = "models";
    private static final String KEY_ACTIVE = "active_model";

    private final SharedPreferences prefs;

    ModelStore(Context context) {
        prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (loadModels().isEmpty()) {
            // 首次启动时写入一个禁用的占位模型。
            // 这样配置页有明确内容可显示，但不会在未配置密钥时发起假请求。
            saveModels(seedModels());
            setActiveModel("未配置模型");
        }
    }

    List<ModelConfig> loadModels() {
        List<ModelConfig> models = new ArrayList<>();
        String raw = prefs.getString(KEY_MODELS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                ModelConfig model = ModelConfig.fromJson(array.getJSONObject(i));
                // 防御性过滤：旧版本或手动改过的 prefs 可能包含 UI 已不再暴露的协议。
                if (ModelConfig.PROTOCOL_OPENAI.equals(model.protocol)) {
                    models.add(model);
                }
            }
        } catch (JSONException ignored) {
            models.clear();
        }
        return models;
    }

    void saveModels(List<ModelConfig> models) {
        JSONArray array = new JSONArray();
        try {
            // 先序列化完整数组，成功后再写入。
            // 如果某个配置序列化失败，不会用损坏数据覆盖原有配置。
            for (ModelConfig model : models) {
                array.put(model.toJson());
            }
        } catch (JSONException ignored) {
            return;
        }
        prefs.edit().putString(KEY_MODELS, array.toString()).apply();
    }

    ModelConfig activeModel() {
        String active = prefs.getString(KEY_ACTIVE, "未配置模型");
        for (ModelConfig model : loadModels()) {
            if (model.name.equals(active)) {
                return model;
            }
        }
        // 如果保存的默认模型名已经不存在，回退到第一个可用模型，调用方不用处理 null。
        List<ModelConfig> models = loadModels();
        return models.isEmpty() ? new ModelConfig() : models.get(0);
    }

    void setActiveModel(String name) {
        prefs.edit().putString(KEY_ACTIVE, name).apply();
    }

    private List<ModelConfig> seedModels() {
        List<ModelConfig> models = new ArrayList<>();
        ModelConfig defaultModel = new ModelConfig();
        // 默认禁用：用户保存真实 OpenAI-compatible 凭据之前，请求必须真实失败。
        defaultModel.name = "未配置模型";
        defaultModel.provider = "自定义";
        defaultModel.baseUrl = "";
        defaultModel.apiKey = "";
        defaultModel.modelId = "";
        defaultModel.speechModelId = "gpt-4o-mini-transcribe";
        defaultModel.volcResourceId = "volc.speech.dialog";
        defaultModel.volcCluster = "volcengine_streaming_common";
        defaultModel.volcBotName = "豆芽";
        defaultModel.volcPayload = "";
        defaultModel.enabled = false;
        defaultModel.priority = 1;
        models.add(defaultModel);
        return models;
    }
}
