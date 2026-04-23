package com.example.aivoice;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.util.Log;

import com.bytedance.speech.speechengine.SpeechEngine;
import com.bytedance.speech.speechengine.SpeechEngineDefines;
import com.bytedance.speech.speechengine.SpeechEngineGenerator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * 火山引擎端到端实时语音 SDK 封装。
 *
 * 文档要求 Dialog 引擎直接通过 DIRECTIVE_START_ENGINE 启动，并把会话参数作为
 * payload 传入；不需要手动调用 DIALOG_START_CONNECTION / DIALOG_START_SESSION。
 */
final class VolcSpeechClient {
    private static final String TAG = "VolcSpeechClient";
    private static final String DEFAULT_ASSISTANT_PROMPT =
            "你叫豆芽，是一个实时语音 AI 助手。请用自然、温暖、简洁的中文口语回答；"
                    + "你会主动配合用户的语气和任务场景表达情绪，但不要说自己叫豆包。";

    interface Callback {
        void onStatus(String message);
        void onVolume(float volume);
        void onPartial(String text);
        void onFinal(String text);
        void onAiPartial(String text);
        void onAiFinal(String text);
        void onAiSpeechStart();
        void onAiSpeechEnd();
        void onError(String message);
    }

    private final Context context;
    private final Application application;
    private final Handler handler;

    private SpeechEngine engine;
    private Callback callback;
    private ModelConfig activeConfig;
    private Runnable startTimeoutRunnable;
    private boolean running;
    private boolean engineStarted;

    VolcSpeechClient(Context context, Application application, Handler handler) {
        this.context = context.getApplicationContext();
        this.application = application;
        this.handler = handler;
    }

    static boolean canUse(ModelConfig config) {
        return (config.enabled || hasDebugVolcCredentials())
                && !volcAppId(config).isEmpty()
                && !volcAppKey(config).isEmpty()
                && !volcToken(config).isEmpty()
                && !volcResourceId(config).isEmpty();
    }

    void start(ModelConfig config, Callback callback) {
        this.callback = callback;
        this.activeConfig = config;
        if (!canUse(config)) {
            postError("请先填写火山语音 AppID、AppKey、Token、Resource ID。");
            return;
        }
        try {
            postStatus("正在初始化火山端到端实时语音服务...");
            if (!SpeechEngineGenerator.PrepareEnvironment(context, application)) {
                postError("火山语音 SDK 环境初始化失败。");
                return;
            }

            engine = SpeechEngineGenerator.getInstance();
            engine.setContext(context);
            engine.setListener(this::handleSpeechMessage);
            engine.createEngine();
            applyOptions(config);

            int initCode = engine.initEngine();
            if (initCode != SpeechEngineDefines.ERR_NO_ERROR) {
                postError("火山语音引擎初始化失败：" + errorText(initCode));
                destroyEngine();
                return;
            }

            engine.sendDirective(SpeechEngineDefines.DIRECTIVE_SYNC_STOP_ENGINE, "");
            int startCode = engine.sendDirective(SpeechEngineDefines.DIRECTIVE_START_ENGINE, startPayload());
            if (startCode != SpeechEngineDefines.ERR_NO_ERROR) {
                postError("火山语音引擎启动失败：" + errorText(startCode));
                destroyEngine();
                return;
            }
            running = true;
            postStatus("正在启动火山端到端实时语音服务...");
            scheduleStartTimeout();
        } catch (Exception exception) {
            postError("火山语音识别异常：" + safeMessage(exception));
            destroyEngine();
        }
    }

    boolean isRunning() {
        return running;
    }

    void requestFinal() {
        if (!running || engine == null) {
            stop();
            return;
        }
        postStatus("正在结束火山端到端实时语音服务...");
        try {
            engine.sendDirective(SpeechEngineDefines.DIRECTIVE_SYNC_STOP_ENGINE, "");
        } catch (Exception exception) {
            postError("结束火山语音识别失败：" + safeMessage(exception));
            stop();
        }
    }

    void stop() {
        running = false;
        engineStarted = false;
        cancelStartTimeout();
        destroyEngine();
    }

    private void applyOptions(ModelConfig config) {
        engine.setOptionString(SpeechEngineDefines.PARAMS_KEY_ENGINE_NAME_STRING, SpeechEngineDefines.DIALOG_ENGINE);
        engine.setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_ID_STRING, volcAppId(config));
        engine.setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_KEY_STRING, volcAppKey(config));
        engine.setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_TOKEN_STRING, volcToken(config));
        engine.setOptionString(SpeechEngineDefines.PARAMS_KEY_RESOURCE_ID_STRING, volcResourceId(config));
        engine.setOptionString(SpeechEngineDefines.PARAMS_KEY_UID_STRING, "ai_voice_call_user");
        engine.setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_VERSION_STRING, "1.0");

        engine.setOptionInt(SpeechEngineDefines.PARAMS_KEY_PROTOCOL_TYPE_INT, SpeechEngineDefines.PROTOCOL_TYPE_SPEECH);
        engine.setOptionString(SpeechEngineDefines.PARAMS_KEY_RECORDER_TYPE_STRING, SpeechEngineDefines.RECORDER_TYPE_RECORDER);
        engine.setOptionInt(SpeechEngineDefines.PARAMS_KEY_RECORDER_PRESET_INT, SpeechEngineDefines.RECORDER_PRESET_VOICE_RECOGNITION);
        engine.setOptionInt(SpeechEngineDefines.PARAMS_KEY_SAMPLE_RATE_INT, 16000);
        engine.setOptionBoolean(SpeechEngineDefines.PARAMS_KEY_ENABLE_GET_VOLUME_BOOL, true);
        engine.setOptionBoolean(SpeechEngineDefines.PARAMS_KEY_ENABLE_RECORDER_AUDIO_CALLBACK_BOOL, true);

        engine.setOptionString(SpeechEngineDefines.PARAMS_KEY_DIALOG_ADDRESS_STRING, "wss://openspeech.bytedance.com");
        engine.setOptionString(SpeechEngineDefines.PARAMS_KEY_DIALOG_URI_STRING, "/api/v3/realtime/dialogue");
        // Dialog 引擎只配置 Dialog 协议本身。ASR_ENGINE 的 work_mode / scenario / cluster
        // 不混用到这里，否则服务端会把启动请求判定为参数非法。
        engine.setOptionInt(SpeechEngineDefines.PARAMS_KEY_DIALOG_WORK_MODE_INT, SpeechEngineDefines.DIALOG_WORK_MODE_DEFAULT);
        engine.setOptionBoolean(SpeechEngineDefines.PARAMS_KEY_DIALOG_ENABLE_PLAYER_BOOL, true);
        engine.setOptionBoolean(SpeechEngineDefines.PARAMS_KEY_DIALOG_ENABLE_RECORDER_AUDIO_CALLBACK_BOOL, true);
        engine.setOptionBoolean(SpeechEngineDefines.PARAMS_KEY_DIALOG_ENABLE_PLAYER_AUDIO_CALLBACK_BOOL, true);
        engine.setOptionString(SpeechEngineDefines.PARAMS_KEY_START_ENGINE_PAYLOAD_STRING, startPayload());
    }

    private void handleSpeechMessage(int type, byte[] data, int length) {
        String payload = data == null || length <= 0 ? "" : new String(data, 0, length, StandardCharsets.UTF_8);
        Log.d(TAG, "message type=" + type + ", payload=" + redactSecrets(compact(payload)));
        if (type == SpeechEngineDefines.MESSAGE_TYPE_ENGINE_START) {
            engineStarted = true;
            cancelStartTimeout();
            postStatus("火山语音服务已启动，请开始说话");
            return;
        }
        if (type == SpeechEngineDefines.MESSAGE_TYPE_VOLUME_LEVEL) {
            postVolume(parseVolume(payload));
            return;
        }
        if (type == SpeechEngineDefines.MESSAGE_TYPE_DIALOG_RECORDER_AUDIO
                || type == SpeechEngineDefines.MESSAGE_TYPE_RECORDER_AUDIO_DATA) {
            postVolume(audioEnergy(data, length));
            return;
        }
        if (type == SpeechEngineDefines.MESSAGE_TYPE_DIALOG_ASR_RESPONSE
                || type == SpeechEngineDefines.MESSAGE_TYPE_EVENT_ASR_RESPONSE
                || type == SpeechEngineDefines.MESSAGE_TYPE_PARTIAL_RESULT) {
            String text = extractText(payload);
            if (!text.isEmpty()) {
                postPartial(text);
            }
            return;
        }
        if (type == SpeechEngineDefines.MESSAGE_TYPE_DIALOG_ASR_INFO
                || type == SpeechEngineDefines.MESSAGE_TYPE_EVENT_ASR_INFO) {
            postStatus("检测到语音，正在识别");
            return;
        }
        if (type == SpeechEngineDefines.MESSAGE_TYPE_DIALOG_ASR_ENDED
                || type == SpeechEngineDefines.MESSAGE_TYPE_EVENT_ASR_ENDED
                || type == SpeechEngineDefines.MESSAGE_TYPE_FINAL_RESULT) {
            String text = extractText(payload);
            if (text.isEmpty()) {
                postStatus("火山语音识别已结束");
            } else {
                postFinal(text);
            }
            postStatus("正在等待 AI 回复");
            return;
        }
        if (type == SpeechEngineDefines.MESSAGE_TYPE_DIALOG_CHAT_RESPONSE
                || type == SpeechEngineDefines.MESSAGE_TYPE_EVENT_CHAT_RESPONSE) {
            String text = extractText(payload);
            if (!text.isEmpty()) {
                postAiPartial(normalizeAssistantName(text));
            }
            return;
        }
        if (type == SpeechEngineDefines.MESSAGE_TYPE_DIALOG_CHAT_ENDED
                || type == SpeechEngineDefines.MESSAGE_TYPE_EVENT_CHAT_ENDED) {
            String text = extractText(payload);
            if (!text.isEmpty()) {
                postAiFinal(normalizeAssistantName(text));
            }
            postStatus("AI 回复完成，继续监听");
            return;
        }
        if (type == SpeechEngineDefines.MESSAGE_TYPE_DIALOG_TTS_SENTENCE_START
                || type == SpeechEngineDefines.MESSAGE_TYPE_EVENT_TTS_SENTENCE_START
                || type == SpeechEngineDefines.MESSAGE_TYPE_PLAYER_START_PLAY_AUDIO) {
            postAiSpeechStart();
            return;
        }
        if (type == SpeechEngineDefines.MESSAGE_TYPE_DIALOG_TTS_SENTENCE_END
                || type == SpeechEngineDefines.MESSAGE_TYPE_EVENT_TTS_SENTENCE_END
                || type == SpeechEngineDefines.MESSAGE_TYPE_PLAYER_FINISH_PLAY_AUDIO) {
            postStatus("豆芽还在说话");
            return;
        }
        if (type == SpeechEngineDefines.MESSAGE_TYPE_DIALOG_TTS_ENDED
                || type == SpeechEngineDefines.MESSAGE_TYPE_EVENT_TTS_ENDED) {
            postAiSpeechEnd();
            return;
        }
        if (type == SpeechEngineDefines.MESSAGE_TYPE_DIALOG_PLAYER_AUDIO
                || type == SpeechEngineDefines.MESSAGE_TYPE_PLAYER_AUDIO_DATA
                || type == SpeechEngineDefines.MESSAGE_TYPE_DECODER_AUDIO_DATA) {
            postVolume(audioEnergy(data, length));
            return;
        }
        if (type == SpeechEngineDefines.MESSAGE_TYPE_DIALOG_CONNECTION_FAILED
                || type == SpeechEngineDefines.MESSAGE_TYPE_DIALOG_SESSION_FAILED
                || type == SpeechEngineDefines.MESSAGE_TYPE_EVENT_CONNECTION_FAILED
                || type == SpeechEngineDefines.MESSAGE_TYPE_EVENT_SESSION_FAILED
                || type == SpeechEngineDefines.MESSAGE_TYPE_ENGINE_ERROR) {
            postError("火山语音服务错误，事件 " + type
                    + "，原始返回：" + compact(errorPayload(type, payload))
                    + "，配置摘要：" + diagnosticInfo());
            stop();
            return;
        }
        if (type == SpeechEngineDefines.MESSAGE_TYPE_ENGINE_STOP) {
            stop();
        }
    }

    private String startPayload() {
        try {
            String rawPayload = volcPayload(activeConfig);
            if (!rawPayload.isEmpty()) {
                JSONObject payload = new JSONObject(rawPayload);
                injectAssistantPrompt(payload);
                return payload.toString();
            }
            JSONObject payload = new JSONObject();
            payload.put("dialog", defaultDialogPayload());
            return payload.toString();
        } catch (Exception exception) {
            postStatus("火山 Payload JSON 无效，已回退到 bot_name 模式：" + safeMessage(exception));
            try {
                return new JSONObject()
                        .put("dialog", defaultDialogPayload())
                        .toString();
            } catch (Exception ignored) {
                return "{}";
            }
        }
    }

    private JSONObject defaultDialogPayload() throws Exception {
        JSONObject dialog = new JSONObject();
        dialog.put("bot_name", volcBotName(activeConfig));
        dialog.put("system_prompt", DEFAULT_ASSISTANT_PROMPT);
        dialog.put("prompt", DEFAULT_ASSISTANT_PROMPT);
        dialog.put("persona", DEFAULT_ASSISTANT_PROMPT);
        dialog.put("assistant_name", "豆芽");
        dialog.put("name", "豆芽");
        return dialog;
    }

    private void injectAssistantPrompt(JSONObject payload) throws Exception {
        JSONObject dialog = payload.optJSONObject("dialog");
        if (dialog == null) {
            dialog = new JSONObject();
            payload.put("dialog", dialog);
        }
        if (!dialog.has("bot_name")) {
            dialog.put("bot_name", volcBotName(activeConfig));
        }
        dialog.put("system_prompt", DEFAULT_ASSISTANT_PROMPT);
        dialog.put("prompt", DEFAULT_ASSISTANT_PROMPT);
        dialog.put("persona", DEFAULT_ASSISTANT_PROMPT);
        dialog.put("assistant_name", "豆芽");
        dialog.put("name", "豆芽");
    }

    private String extractText(String payload) {
        String trimmed = payload == null ? "" : payload.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        try {
            Object json = trimmed.startsWith("[") ? new JSONArray(trimmed) : new JSONObject(trimmed);
            return findText(json).trim();
        } catch (Exception ignored) {
            return trimmed;
        }
    }

    private String findText(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String[] keys = {"text", "transcript", "utterance", "content", "result"};
            for (String key : keys) {
                String direct = object.optString(key, "");
                if (!direct.isEmpty()) {
                    return direct;
                }
            }
            JSONArray names = object.names();
            if (names == null) {
                return "";
            }
            for (int i = 0; i < names.length(); i++) {
                String nested = findText(object.opt(names.getString(i)));
                if (!nested.isEmpty()) {
                    return nested;
                }
            }
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                builder.append(findText(array.get(i)));
            }
            return builder.toString();
        }
        return "";
    }

    private void scheduleStartTimeout() {
        cancelStartTimeout();
        startTimeoutRunnable = () -> {
            if (running && !engineStarted) {
                postError("火山语音启动超时：未收到 ENGINE_START 回调。请检查 Token、Resource ID、服务开通状态和网络。");
                stop();
            }
        };
        handler.postDelayed(startTimeoutRunnable, 12000);
    }

    private void cancelStartTimeout() {
        if (startTimeoutRunnable != null) {
            handler.removeCallbacks(startTimeoutRunnable);
            startTimeoutRunnable = null;
        }
    }

    private void destroyEngine() {
        cancelStartTimeout();
        if (engine != null) {
            try {
                engine.destroyEngine();
            } catch (Exception ignored) {
                // SDK 内部可能已经释放，重复释放时忽略即可。
            }
            engine = null;
        }
    }

    private static String volcAppId(ModelConfig config) {
        return debugFallback(config.volcAppId, BuildConfig.DEBUG_VOLC_APP_ID);
    }

    private static String volcAppKey(ModelConfig config) {
        return debugFallback(config.volcAppKey, BuildConfig.DEBUG_VOLC_APP_KEY);
    }

    private static String volcToken(ModelConfig config) {
        return debugFallback(config.volcToken, BuildConfig.DEBUG_VOLC_TOKEN);
    }

    private static String volcResourceId(ModelConfig config) {
        return debugFallback(config.volcResourceId, BuildConfig.DEBUG_VOLC_RESOURCE_ID);
    }

    private static String volcCluster(ModelConfig config) {
        return debugFallback(valueOr(config.volcCluster, "volcengine_streaming_common"), BuildConfig.DEBUG_VOLC_CLUSTER);
    }

    private static String volcBotName(ModelConfig config) {
        return debugFallback(valueOr(config.volcBotName, "豆芽"), BuildConfig.DEBUG_VOLC_BOT_NAME);
    }

    private String normalizeAssistantName(String text) {
        return text == null ? "" : text.replace("豆包", "豆芽");
    }

    private static String volcPayload(ModelConfig config) {
        return debugFallback(config.volcPayload, BuildConfig.DEBUG_VOLC_PAYLOAD);
    }

    private static String debugFallback(String value, String debugValue) {
        String debugTrimmed = debugValue == null ? "" : debugValue.trim();
        if (BuildConfig.DEBUG && !debugTrimmed.isEmpty()) {
            return debugTrimmed;
        }
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.isEmpty()) {
            return trimmed;
        }
        return "";
    }

    private static boolean hasDebugVolcCredentials() {
        return BuildConfig.DEBUG
                && !BuildConfig.DEBUG_VOLC_APP_ID.trim().isEmpty()
                && !BuildConfig.DEBUG_VOLC_APP_KEY.trim().isEmpty()
                && !BuildConfig.DEBUG_VOLC_TOKEN.trim().isEmpty();
    }

    private static String valueOr(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private String errorText(int code) {
        if (code == SpeechEngineDefines.ERR_INVALID_ARGUMENTS) {
            return "参数非法，重点检查 AppID、AppKey、Token、Resource ID。错误码：" + code;
        }
        if (code == SpeechEngineDefines.ERR_ADDRESS_INVALID) {
            return "服务地址非法。错误码：" + code;
        }
        if (code == SpeechEngineDefines.ERR_AUTHENTICATION_FAILED) {
            return "鉴权失败，请检查 Token、AppID、AppKey、Resource ID 是否匹配。错误码：" + code;
        }
        if (code == SpeechEngineDefines.ERR_REC_CHECK_ENVIRONMENT_FAILED) {
            return "录音环境检查失败，请确认麦克风权限已授权。错误码：" + code;
        }
        if (code == SpeechEngineDefines.ERR_CREATE_OBJ_INS_FAILED) {
            return "SDK 引擎对象创建失败，请检查 native so 是否正确打包。错误码：" + code;
        }
        return "错误码：" + code;
    }

    private String errorPayload(int type, String payload) {
        if (payload != null && !payload.trim().isEmpty()) {
            return payload;
        }
        if (engine == null) {
            return "";
        }
        try {
            String result = engine.fetchResult(type);
            return result == null ? "" : result;
        } catch (Exception ignored) {
            return "";
        }
    }

    private String diagnosticInfo() {
        if (activeConfig == null) {
            return "{}";
        }
        try {
            return new JSONObject()
                    .put("resource_id", volcResourceId(activeConfig))
                    .put("bot_name", volcBotName(activeConfig))
                    .put("payload", new JSONObject(startPayload()))
                    .toString();
        } catch (Exception exception) {
            return "{payload=" + compact(startPayload()) + "}";
        }
    }

    private void postStatus(String message) {
        handler.post(() -> {
            if (callback != null) {
                callback.onStatus(message);
            }
        });
    }

    private void postVolume(float volume) {
        handler.post(() -> {
            if (callback != null) {
                callback.onVolume(volume);
            }
        });
    }

    private void postPartial(String text) {
        handler.post(() -> {
            if (callback != null) {
                callback.onPartial(text);
            }
        });
    }

    private void postFinal(String text) {
        handler.post(() -> {
            if (callback != null) {
                callback.onFinal(text);
            }
        });
    }

    private void postAiPartial(String text) {
        handler.post(() -> {
            if (callback != null) {
                callback.onAiPartial(text);
            }
        });
    }

    private void postAiFinal(String text) {
        handler.post(() -> {
            if (callback != null) {
                callback.onAiFinal(text);
            }
        });
    }

    private void postAiSpeechStart() {
        handler.post(() -> {
            if (callback != null) {
                callback.onAiSpeechStart();
            }
        });
    }

    private void postAiSpeechEnd() {
        handler.post(() -> {
            if (callback != null) {
                callback.onAiSpeechEnd();
            }
        });
    }

    private void postError(String message) {
        String safeMessage = redactSecrets(message);
        Log.e(TAG, safeMessage);
        handler.post(() -> {
            if (callback != null) {
                callback.onError(safeMessage);
            }
        });
    }

    private static String redactSecrets(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text
                .replaceAll("(?i)(X-Api-App-Key:\\s*)[^,}\\]\\s]+", "$1***")
                .replaceAll("(?i)(expected:\\[)[^\\]]+(\\])", "$1***$2")
                .replaceAll("(?i)(AppKey[、\\s:=]*)([A-Za-z0-9_\\-]{8,})", "$1***");
    }

    private float parseVolume(String payload) {
        try {
            String text = payload == null ? "" : payload.replaceAll("[^0-9.]", "");
            if (text.isEmpty()) {
                return 0f;
            }
            float value = Float.parseFloat(text);
            return Math.max(0f, Math.min(1f, value > 1f ? value / 100f : value));
        } catch (Exception ignored) {
            return 0f;
        }
    }

    private float audioEnergy(byte[] data, int length) {
        if (data == null || length < 2) {
            return 0f;
        }
        long sum = 0;
        int samples = 0;
        int max = length - (length % 2);
        for (int i = 0; i < max; i += 2) {
            int sample = (data[i] & 0xFF) | (data[i + 1] << 8);
            sum += (long) sample * sample;
            samples++;
        }
        if (samples == 0) {
            return 0f;
        }
        double rms = Math.sqrt(sum / (double) samples) / 32768.0;
        return (float) Math.max(0f, Math.min(1f, rms * 4.5f));
    }

    private String compact(String value) {
        String text = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() > 220 ? text.substring(0, 220) + "..." : text;
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
