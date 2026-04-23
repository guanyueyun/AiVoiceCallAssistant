package com.example.aivoice;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.util.Base64;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 实时语音识别客户端。
 *
 * 这个类只负责“麦克风音频流 -> WebSocket 实时转写结果”：
 * 1. 使用 AudioRecord 采集 24kHz、单声道、16-bit PCM 原始音频；
 * 2. 将每个音频块 Base64 后发送给 Realtime transcription session；
 * 3. 解析服务端返回的增量字幕和最终转写文本；
 * 4. 所有 UI 回调都切回主线程，避免 Activity 直接跨线程更新控件。
 */
final class RealtimeSpeechClient {
    private static final int SAMPLE_RATE = 24000;
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int NORMAL_CLOSE = 1000;

    interface Callback {
        void onStatus(String message);
        void onPartial(String text);
        void onFinal(String text);
        void onError(String message);
    }

    private final Context context;
    private final Handler mainHandler;
    private final OkHttpClient client;

    private WebSocket webSocket;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private Callback callback;
    private final StringBuilder partialTranscript = new StringBuilder();
    private volatile boolean running;
    private volatile boolean closing;
    private volatile boolean finalRequested;

    RealtimeSpeechClient(Context context, Handler mainHandler) {
        this.context = context.getApplicationContext();
        this.mainHandler = mainHandler;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
    }

    static boolean canUse(ModelConfig config) {
        String baseUrl = config.baseUrl.trim();
        return config.enabled
                && !config.apiKey.trim().isEmpty()
                && !config.speechModelId.trim().isEmpty()
                && (baseUrl.startsWith("ws") || baseUrl.contains("api.openai.com"));
    }

    void start(ModelConfig config, Callback callback) {
        this.callback = callback;
        this.closing = false;
        this.finalRequested = false;
        Request request = new Request.Builder()
                .url(realtimeUrl(config.baseUrl))
                .addHeader("Authorization", "Bearer " + config.apiKey.trim())
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build();
        postStatus("正在连接实时语音识别服务...");
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                running = true;
                socket.send(sessionUpdate(config.speechModelId.trim()));
                postStatus("实时语音识别已连接，请开始说话");
                startAudioCapture();
            }

            @Override
            public void onMessage(WebSocket socket, String text) {
                handleServerEvent(text);
            }

            @Override
            public void onFailure(WebSocket socket, Throwable throwable, Response response) {
                stopAudioCapture();
                running = false;
                if (!closing) {
                    String message = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
                    postError("实时语音识别连接失败：" + message);
                }
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                stopAudioCapture();
                running = false;
            }
        });
    }

    boolean isRunning() {
        return running || webSocket != null;
    }

    void requestFinal() {
        if (webSocket == null) {
            stop();
            return;
        }
        finalRequested = true;
        stopAudioCapture();
        postStatus("正在结束实时识别...");
        webSocket.send("{\"type\":\"input_audio_buffer.commit\"}");
        mainHandler.postDelayed(() -> {
            if (finalRequested && webSocket != null) {
                postError("实时识别等待最终结果超时，请重试");
                stop();
            }
        }, 8000);
    }

    void stop() {
        closing = true;
        finalRequested = false;
        stopAudioCapture();
        running = false;
        if (webSocket != null) {
            webSocket.close(NORMAL_CLOSE, "client stop");
            webSocket = null;
        }
    }

    @SuppressLint("MissingPermission")
    private void startAudioCapture() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            postError("缺少麦克风权限，无法启动实时语音识别");
            stop();
            return;
        }
        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING);
        if (minBufferSize <= 0) {
            postError("当前设备不支持 24kHz PCM 麦克风采集");
            stop();
            return;
        }

        int bufferSize = Math.max(minBufferSize, SAMPLE_RATE / 5);
        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferSize
        );
        audioRecord.startRecording();

        audioThread = new Thread(() -> {
            byte[] buffer = new byte[Math.max(2048, minBufferSize)];
            while (running && audioRecord != null && !Thread.currentThread().isInterrupted()) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0 && webSocket != null) {
                    sendAudioChunk(buffer, read);
                }
            }
        }, "RealtimeSpeechAudio");
        audioThread.start();
    }

    private void stopAudioCapture() {
        if (audioThread != null) {
            audioThread.interrupt();
            audioThread = null;
        }
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception ignored) {
                // stop 可能在录音尚未完全启动时抛异常，释放资源即可。
            }
            audioRecord.release();
            audioRecord = null;
        }
    }

    private void sendAudioChunk(byte[] buffer, int length) {
        try {
            String audio = Base64.encodeToString(buffer, 0, length, Base64.NO_WRAP);
            JSONObject event = new JSONObject();
            event.put("type", "input_audio_buffer.append");
            event.put("audio", audio);
            webSocket.send(event.toString());
        } catch (Exception exception) {
            postError("实时音频发送失败：" + safeMessage(exception));
            stop();
        }
    }

    private void handleServerEvent(String text) {
        try {
            JSONObject json = new JSONObject(text);
            String type = json.optString("type", "");
            if ("conversation.item.input_audio_transcription.delta".equals(type)) {
                String delta = json.optString("delta", "").trim();
                if (!delta.isEmpty()) {
                    partialTranscript.append(delta);
                    postPartial(partialTranscript.toString());
                }
                return;
            }
            if ("conversation.item.input_audio_transcription.completed".equals(type)) {
                String transcript = json.optString("transcript", "").trim();
                if (transcript.isEmpty()) {
                    transcript = partialTranscript.toString().trim();
                }
                finalRequested = false;
                partialTranscript.setLength(0);
                if (transcript.isEmpty()) {
                    postError("实时语音识别结果为空");
                } else {
                    postFinal(transcript);
                }
                stop();
                return;
            }
            if ("input_audio_buffer.speech_started".equals(type)) {
                partialTranscript.setLength(0);
                postStatus("检测到语音");
                return;
            }
            if ("input_audio_buffer.speech_stopped".equals(type)) {
                postStatus("语音结束，正在实时转写");
                return;
            }
            if ("error".equals(type)) {
                JSONObject error = json.optJSONObject("error");
                String message = error == null ? json.toString() : error.optString("message", error.toString());
                postError("实时语音识别错误：" + message);
                stop();
            }
        } catch (Exception exception) {
            postError("实时语音事件解析失败：" + safeMessage(exception));
            stop();
        }
    }

    private String sessionUpdate(String model) {
        try {
            JSONObject transcription = new JSONObject()
                    .put("model", model)
                    .put("prompt", "")
                    .put("language", "zh");
            JSONObject turnDetection = new JSONObject()
                    .put("type", "server_vad")
                    .put("threshold", 0.5)
                    .put("prefix_padding_ms", 300)
                    .put("silence_duration_ms", 700);
            JSONObject noiseReduction = new JSONObject()
                    .put("type", "near_field");
            JSONObject event = new JSONObject()
                    .put("type", "transcription_session.update")
                    .put("input_audio_format", "pcm16")
                    .put("input_audio_transcription", transcription)
                    .put("turn_detection", turnDetection)
                    .put("input_audio_noise_reduction", noiseReduction);
            return event.toString();
        } catch (Exception exception) {
            return "{\"type\":\"transcription_session.update\",\"input_audio_format\":\"pcm16\"}";
        }
    }

    private String realtimeUrl(String baseUrl) {
        String trimmed = baseUrl.trim();
        if (trimmed.startsWith("ws://") || trimmed.startsWith("wss://")) {
            return withTranscriptionIntent(trimmed);
        }
        if (trimmed.endsWith("/chat/completions")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/chat/completions".length());
        }
        if (trimmed.endsWith("/audio/transcriptions")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/audio/transcriptions".length());
        }
        if (!trimmed.endsWith("/realtime")) {
            trimmed = trimmed.endsWith("/") ? trimmed + "realtime" : trimmed + "/realtime";
        }
        if (trimmed.startsWith("https://")) {
            trimmed = "wss://" + trimmed.substring("https://".length());
        } else if (trimmed.startsWith("http://")) {
            trimmed = "ws://" + trimmed.substring("http://".length());
        }
        return withTranscriptionIntent(trimmed);
    }

    private String withTranscriptionIntent(String url) {
        if (url.contains("intent=transcription")) {
            return url;
        }
        return url.contains("?") ? url + "&intent=transcription" : url + "?intent=transcription";
    }

    private void postStatus(String message) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onStatus(message);
            }
        });
    }

    private void postPartial(String text) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onPartial(text);
            }
        });
    }

    private void postFinal(String text) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onFinal(text);
            }
        });
    }

    private void postError(String message) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onError(message);
            }
        });
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
