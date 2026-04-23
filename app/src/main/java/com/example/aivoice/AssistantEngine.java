package com.example.aivoice;

import android.os.Handler;

/**
 * 模型回复进入 UI 前的协调层。
 *
 * ModelGateway 当前返回的是完整的非流式回复；AssistantEngine 在本地增加
 * 打字机式展示动画，让聊天页和通话页都能使用 onDelta/onComplete/onError
 * 这组统一回调。注意：这里不能生成兜底假回答，最终内容必须来自真实模型。
 */
final class AssistantEngine {
    interface StreamCallback {
        void onDelta(String partialText);
        void onComplete(String finalText);
        void onError(String message);
    }

    private final Handler handler;
    private final ModelGateway gateway;
    private Runnable currentStream;

    AssistantEngine(Handler handler, ModelGateway gateway) {
        this.handler = handler;
        this.gateway = gateway;
    }

    void streamReply(String userText, ModelConfig model, StreamCallback callback) {
        // 同一时间只允许一条 AI 回复做展示动画。
        // 用户连续发送消息或打断朗读时，先取消旧动画，避免过期文本继续刷新 UI。
        cancel();
        callback.onDelta("正在连接模型...");

        // 这里先发起真实网络请求，拿到完整回复后再按字符分段回调给 UI。
        // 这样做的原因是 OpenAI-compatible 的非流式接口最通用；如果后续要接 SSE stream，
        // 只需要替换 ModelGateway.chat 的实现，Activity 的 UI 更新逻辑不用改。
        gateway.chat(model, userText, new ModelGateway.ChatCallback() {
            @Override
            public void onSuccess(String reply) {
                // 网络层已经拿到完整文本，这里只负责把完整文本拆成渐进显示。
                showReply(reply, callback);
            }

            @Override
            public void onError(String message) {
                // 错误直接透传给界面，由界面决定显示在气泡还是字幕中。
                callback.onError(message);
            }
        });
    }

    void cancel() {
        // 这里只取消本地展示动画。
        // 当前网络请求由 ModelGateway 后台线程持有，暂未做 HttpURLConnection 级取消。
        if (currentStream != null) {
            handler.removeCallbacks(currentStream);
            currentStream = null;
        }
    }

    private void showReply(String fullText, StreamCallback callback) {
        final int[] cursor = {0};
        currentStream = new Runnable() {
            @Override
            public void run() {
                // UI 层需要“边出字边朗读前的等待感”，但这里不伪造模型流式能力。
                // fullText 已经是真实模型结果，下面只是本地展示动画。
                cursor[0] = Math.min(fullText.length(), cursor[0] + 3);
                callback.onDelta(fullText.substring(0, cursor[0]));
                if (cursor[0] < fullText.length()) {
                    // 还没展示完整文本时继续延迟刷新，形成打字机效果。
                    handler.postDelayed(this, 35);
                } else {
                    // 完整文本展示完毕后再触发完成回调，通话页会在这里开始 TTS。
                    callback.onComplete(fullText);
                }
            }
        };
        handler.post(currentStream);
    }
}
