package com.example.aivoice;

import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 极简 OpenAI-compatible chat/completions 客户端。
 *
 * 主要职责：
 * - 判断保存的模型配置是否可用；
 * - 组装 HTTP 请求体；
 * - 从响应中解析第一条 assistant 回复；
 * - 通过 Handler 保证所有回调回到主线程。
 *
 * 这里故意不引入第三方 SDK，保持项目结构简单，方便直接用 Android Studio 打开运行。
 */
final class ModelGateway {
    static final class TestResult {
        // firstPacketMs 预留给未来流式请求做首包耗时统计。
        // 当前非流式请求里，成功时它等于总耗时，失败时为 0。
        final boolean success;
        final long totalMs;
        final long firstPacketMs;
        final int errorCode;
        final String message;

        TestResult(boolean success, long totalMs, long firstPacketMs, int errorCode, String message) {
            this.success = success;
            this.totalMs = totalMs;
            this.firstPacketMs = firstPacketMs;
            this.errorCode = errorCode;
            this.message = message;
        }
    }

    interface Callback {
        void onResult(TestResult result);
    }

    interface ChatCallback {
        void onSuccess(String reply);
        void onError(String message);
    }

    private final Handler handler;

    ModelGateway(Handler handler) {
        this.handler = handler;
    }

    void test(ModelConfig config, Callback callback) {
        long startedAt = System.currentTimeMillis();

        // “真实测试”不做本地字段假验证，而是实际调用一次 chat/completions。
        // 如果 API Key、Base URL、Model ID 或网络不可用，会返回真实错误信息。
        chat(config, "请用一句中文回复：连接测试", new ChatCallback() {
            @Override
            public void onSuccess(String reply) {
                long elapsed = System.currentTimeMillis() - startedAt;
                callback.onResult(new TestResult(true, elapsed, elapsed, 0, "模型返回：" + compact(reply)));
            }

            @Override
            public void onError(String message) {
                long elapsed = System.currentTimeMillis() - startedAt;
                callback.onResult(new TestResult(false, elapsed, 0, 500, message));
            }
        });
    }

    void chat(ModelConfig config, String userText, ChatCallback callback) {
        if (!isUsable(config)) {
            // 配置不可用时直接在主线程回调错误。
            // 这样 UI 层不需要关心错误来自本地校验还是后台网络请求。
            handler.post(() -> callback.onError("请先配置已启用的 OpenAI-compatible 模型、Base URL、API Key 和 Model ID。"));
            return;
        }

        // 网络请求放到后台线程，所有 callback 都切回主线程，避免直接更新 UI 时崩溃。
        // 这里使用 HttpURLConnection，保持项目不依赖第三方库，方便 Android Studio 直接打开运行。
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(endpoint(config.baseUrl));
                connection = (HttpURLConnection) url.openConnection();
                // 连接超时和读取超时都使用用户配置，避免请求长时间卡住 UI 状态。
                connection.setConnectTimeout(config.timeoutMs);
                connection.setReadTimeout(config.timeoutMs);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Authorization", "Bearer " + config.apiKey);
                connection.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("model", config.modelId);
                // 当前版本使用兼容性最高的非流式请求。
                // 本地分段展示由 AssistantEngine 处理，不在网络层伪造 stream。
                body.put("stream", false);
                JSONArray messages = new JSONArray();

                // 系统提示把回复约束为适合语音播报的中文短回答。
                // 这样文本聊天和语音通话共用同一个真实模型接口时，体验更接近语音助手。
                messages.put(new JSONObject()
                        .put("role", "system")
                        .put("content", "你叫豆芽，是一个语音优先的中文 AI 助手。回答要简洁、自然、温暖，适合朗读；不要说自己叫豆包。"));
                messages.put(new JSONObject().put("role", "user").put("content", userText));
                body.put("messages", messages);

                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = connection.getOutputStream()) {
                    // JSON 必须按 UTF-8 写出，保证中文提示词不会在服务端乱码。
                    output.write(payload);
                }

                int code = connection.getResponseCode();
                InputStream responseStream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
                if (responseStream == null) {
                    // 某些异常 HTTP 状态可能没有响应体，此时至少返回状态码。
                    handler.post(() -> callback.onError("模型请求失败：HTTP " + code));
                    return;
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream, StandardCharsets.UTF_8));
                StringBuilder raw = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    // chat/completions 非流式响应通常是一段 JSON，逐行拼回完整字符串。
                    raw.append(line);
                }
                if (code < 200 || code >= 300) {
                    // 供应商错误直接展示给用户，但先压缩长度，避免 Toast/气泡过长。
                    String error = raw.length() == 0 ? "HTTP " + code : raw.toString();
                    handler.post(() -> callback.onError("模型请求失败：" + compact(error)));
                    return;
                }
                JSONObject json = new JSONObject(raw.toString());
                JSONArray choices = json.optJSONArray("choices");
                String reply = "";
                if (choices != null && choices.length() > 0) {
                    // OpenAI-compatible 常见返回结构是 choices[0].message.content。
                    // 这里用 optJSONObject/optString 提高兼容性，字段缺失时返回空回复错误。
                    JSONObject message = choices.getJSONObject(0).optJSONObject("message");
                    if (message != null) {
                        reply = message.optString("content", "");
                    }
                }
                String finalReply = reply.trim();
                handler.post(() -> {
                    if (finalReply.isEmpty()) {
                        callback.onError("模型返回为空。");
                    } else {
                        callback.onSuccess(finalReply);
                    }
                });
            } catch (Exception exception) {
                // 网络错误、URL 错误、JSON 解析错误都会走这里，统一转换成中文错误文案。
                String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                handler.post(() -> callback.onError("模型请求异常：" + message));
            } finally {
                if (connection != null) {
                    // 主动断开连接，释放底层 socket 资源。
                    connection.disconnect();
                }
            }
        }).start();
    }

    void transcribeAudio(ModelConfig config, File audioFile, ChatCallback callback) {
        if (!isSpeechUsable(config)) {
            handler.post(() -> callback.onError("请先配置已启用的 Base URL、API Key，并填写语音识别模型 ID，例如 whisper-1。"));
            return;
        }
        if (audioFile == null || !audioFile.exists() || audioFile.length() == 0) {
            handler.post(() -> callback.onError("录音文件为空，无法识别。"));
            return;
        }

        new Thread(() -> {
            HttpURLConnection connection = null;
            String boundary = "----AiVoiceBoundary" + System.currentTimeMillis();
            try {
                URL url = new URL(audioEndpoint(config.baseUrl));
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(config.timeoutMs);
                connection.setReadTimeout(Math.max(config.timeoutMs, 30000));
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Authorization", "Bearer " + config.apiKey);
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                connection.setDoOutput(true);

                try (OutputStream output = connection.getOutputStream()) {
                    writeFormField(output, boundary, "model", config.speechModelId.trim());
                    writeFormField(output, boundary, "language", "zh");
                    writeFileField(output, boundary, "file", "speech.m4a", "audio/mp4", audioFile);
                    output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                }

                int code = connection.getResponseCode();
                InputStream responseStream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
                if (responseStream == null) {
                    handler.post(() -> callback.onError("语音转写失败：HTTP " + code));
                    return;
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream, StandardCharsets.UTF_8));
                StringBuilder raw = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    raw.append(line);
                }
                if (code < 200 || code >= 300) {
                    handler.post(() -> callback.onError("语音转写失败：" + compact(raw.toString())));
                    return;
                }

                JSONObject json = new JSONObject(raw.toString());
                String text = json.optString("text", "").trim();
                handler.post(() -> {
                    if (text.isEmpty()) {
                        callback.onError("语音转写返回为空。");
                    } else {
                        callback.onSuccess(text);
                    }
                });
            } catch (Exception exception) {
                String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                handler.post(() -> callback.onError("语音转写异常：" + message));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private boolean isUsable(ModelConfig config) {
        // 只允许当前已经真实实现的 OpenAI-compatible 协议。
        // 未实现的 REST、WebSocket realtime 不在 UI 中出现，也不会从旧缓存中被使用。
        return config.enabled
                && ModelConfig.PROTOCOL_OPENAI.equals(config.protocol)
                && config.baseUrl.trim().startsWith("http")
                && !config.apiKey.trim().isEmpty()
                && !config.modelId.trim().isEmpty();
    }

    private boolean isSpeechUsable(ModelConfig config) {
        return config.enabled
                && config.baseUrl.trim().startsWith("http")
                && !config.apiKey.trim().isEmpty()
                && !config.speechModelId.trim().isEmpty();
    }

    private String endpoint(String baseUrl) {
        // 兼容用户填写两种常见形式：
        // 1. https://host/v1
        // 2. https://host/v1/chat/completions
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/chat/completions")) {
            return trimmed;
        }
        if (trimmed.endsWith("/")) {
            return trimmed + "chat/completions";
        }
        return trimmed + "/chat/completions";
    }

    private String audioEndpoint(String baseUrl) {
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/audio/transcriptions")) {
            return trimmed;
        }
        if (trimmed.endsWith("/chat/completions")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/chat/completions".length());
        }
        if (trimmed.endsWith("/")) {
            return trimmed + "audio/transcriptions";
        }
        return trimmed + "/audio/transcriptions";
    }

    private void writeFormField(OutputStream output, String boundary, String name, String value) throws Exception {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void writeFileField(OutputStream output, String boundary, String name, String fileName, String contentType, File file) throws Exception {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] buffer = new byte[8192];
        try (FileInputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String compact(String value) {
        // 压缩服务商错误或模型回复摘要，避免测试结果 Toast 太长。
        String text = value.replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }
}
