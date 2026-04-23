package com.example.aivoice;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 主界面控制器。
 *
 * 当前应用只有一个 Activity，里面通过三个页面容器切换功能：
 * - chat_screen：文字对话页面；
 * - call_screen：语音识别、模型回复、TTS 播放页面；
 * - model_screen：OpenAI-compatible 模型配置页面。
 *
 * 这个类只负责 Android 视图绑定和交互编排；模型存储、对话存储、
 * 网络请求、回复动画和头像绘制分别交给独立类处理，避免主界面类继续膨胀。
 */
public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final int REQ_RECORD_AUDIO = 7;
    private static final int REQ_RECOGNIZE_SPEECH = 8;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<ChatMessage> chatMessages = new ArrayList<>();

    private ModelStore modelStore;
    private ConversationStore conversationStore;
    private ModelGateway modelGateway;
    private AssistantEngine assistantEngine;

    private View callScreen;
    private View chatScreen;
    private View modelScreen;
    private View voiceHoldOverlay;
    private TextView modelBadge;
    private TextView callTimer;
    private AvatarView avatarView;
    private TextView callStatus;
    private TextView subtitle;
    private TextView transcript;
    private TextView voiceHoldHint;
    private TextView voiceHoldWave;
    private ImageButton micButton;
    private ImageButton chatVoiceButton;
    private EditText callInput;
    private EditText chatInput;
    private LinearLayout chatList;
    private ScrollView chatScroll;
    private LinearLayout modelList;
    private EditText modelNameInput;
    private EditText baseUrlInput;
    private EditText apiKeyInput;
    private EditText modelIdInput;
    private EditText speechModelIdInput;
    private EditText volcAppIdInput;
    private EditText volcAppKeyInput;
    private EditText volcTokenInput;
    private EditText volcResourceIdInput;
    private EditText volcClusterInput;
    private EditText volcBotNameInput;
    private EditText volcPayloadInput;
    private EditText timeoutInput;
    private CheckBox modelEnabledCheck;

    private SpeechRecognizer speechRecognizer;
    private SpeechCallback pendingSpeechCallback;
    private SpeechCallback activitySpeechCallback;
    private SpeechCallback cloudSpeechCallback;
    private VolcSpeechClient volcSpeechClient;
    private RealtimeSpeechClient realtimeSpeechClient;
    private MediaRecorder audioRecorder;
    private File cloudSpeechFile;
    private TextToSpeech tts;
    private Runnable speakingRunnable;
    private Runnable callTimerRunnable;
    private Runnable speechTimeoutRunnable;
    private Runnable cloudRecordingLimitRunnable;
    private Runnable autoListenRunnable;
    private Runnable aiSpeechEndRunnable;
    private long callStartedAt;
    private CallState callState = CallState.IDLE;
    private String currentAiExpression = "微笑";
    private boolean subtitlesEnabled = true;
    private boolean speechListening;
    private boolean cloudRecording;
    private boolean callMuted;
    private boolean chatVoiceMode;
    private boolean voiceHoldCancel;
    private String chatTextDraft = "";

    private interface SpeechCallback {
        // 对 Android SpeechRecognizer 回调做一层简化封装。
        // 聊天页语音输入和通话页语音输入都走同一套识别流程，只是最终处理不同。
        void onStatus(String message);
        void onPartial(String text);
        void onFinal(String text);
        default void onAiPartial(String text) {
        }
        default void onAiFinal(String text) {
        }
        default void onAiSpeechStart() {
        }
        default void onAiSpeechEnd() {
        }
        void onError(String message);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        modelStore = new ModelStore(this);
        conversationStore = new ConversationStore(this);
        modelGateway = new ModelGateway(handler);
        assistantEngine = new AssistantEngine(handler, modelGateway);
        chatMessages.addAll(conversationStore.loadMessages());

        bindViews();
        bindEvents();
        initTextToSpeech();
        showChat();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        pendingSpeechCallback = null;
        if (speechTimeoutRunnable != null) {
            handler.removeCallbacks(speechTimeoutRunnable);
        }
        if (autoListenRunnable != null) {
            handler.removeCallbacks(autoListenRunnable);
        }
        stopVolcSpeechInput();
        stopRealtimeSpeechInput();
        stopCloudRecording(false);
        if (tts != null) {
            tts.shutdown();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_RECOGNIZE_SPEECH) {
            return;
        }

        SpeechCallback callback = activitySpeechCallback;
        activitySpeechCallback = null;
        finishSpeechInput(false);
        if (callback == null) {
            return;
        }

        if (resultCode != RESULT_OK || data == null) {
            callback.onError("语音识别已取消或没有返回结果。");
            return;
        }

        ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        String text = results == null || results.isEmpty() ? "" : results.get(0);
        if (text.trim().isEmpty()) {
            callback.onError("没有识别到内容。");
        } else {
            callback.onFinal(text.trim());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_RECORD_AUDIO) {
            return;
        }

        SpeechCallback callback = pendingSpeechCallback;
        pendingSpeechCallback = null;
        if (callback == null) {
            return;
        }

        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            // 用户授权后继续执行刚才被权限中断的识别请求，不需要再点一次麦克风。
            startSpeechInput(callback);
        } else {
            callback.onError("麦克风权限被拒绝，无法使用语音识别。");
        }
    }

    private void bindViews() {
        // 集中绑定所有 XML 控件。
        // 如果后续修改 activity_main.xml 中的 id，崩溃位置会集中在这里，便于排查。
        callScreen = findViewById(R.id.call_screen);
        chatScreen = findViewById(R.id.chat_screen);
        modelScreen = findViewById(R.id.model_screen);
        voiceHoldOverlay = findViewById(R.id.voice_hold_overlay);
        modelBadge = findViewById(R.id.model_badge);
        callTimer = findViewById(R.id.call_timer);
        ((TextView) findViewById(R.id.douya_title)).setText("豆芽");
        avatarView = findViewById(R.id.avatar_view);
        callStatus = findViewById(R.id.call_status);
        subtitle = findViewById(R.id.subtitle);
        transcript = findViewById(R.id.transcript);
        voiceHoldHint = findViewById(R.id.voice_hold_hint);
        voiceHoldWave = findViewById(R.id.voice_hold_wave);
        micButton = findViewById(R.id.mic_button);
        chatVoiceButton = findViewById(R.id.chat_voice);
        callInput = findViewById(R.id.call_input);
        chatInput = findViewById(R.id.chat_input);
        chatList = findViewById(R.id.chat_list);
        chatScroll = findViewById(R.id.chat_scroll);
        modelList = findViewById(R.id.model_list);
        modelNameInput = findViewById(R.id.model_name_input);
        baseUrlInput = findViewById(R.id.base_url_input);
        apiKeyInput = findViewById(R.id.api_key_input);
        modelIdInput = findViewById(R.id.model_id_input);
        speechModelIdInput = findViewById(R.id.speech_model_id_input);
        volcAppIdInput = findViewById(R.id.volc_app_id_input);
        volcAppKeyInput = findViewById(R.id.volc_app_key_input);
        volcTokenInput = findViewById(R.id.volc_token_input);
        volcResourceIdInput = findViewById(R.id.volc_resource_id_input);
        volcClusterInput = findViewById(R.id.volc_cluster_input);
        volcBotNameInput = findViewById(R.id.volc_bot_name_input);
        volcPayloadInput = findViewById(R.id.volc_payload_input);
        timeoutInput = findViewById(R.id.timeout_input);
        modelEnabledCheck = findViewById(R.id.model_enabled_check);
    }

    private void bindEvents() {
        // 集中绑定点击事件，避免同一个按钮的逻辑分散在多个方法里。
        // 这里出现的控件才是当前版本真正可交互的功能入口。
        findViewById(R.id.nav_call).setOnClickListener(v -> showCall());
        findViewById(R.id.call_close).setOnClickListener(v -> exitCallToChat());
        findViewById(R.id.nav_model).setOnClickListener(v -> showModels());
        findViewById(R.id.model_back).setOnClickListener(v -> showChat());
        findViewById(R.id.call_send).setOnClickListener(v -> sendCallText());
        findViewById(R.id.call_video).setOnClickListener(v -> showUnsupportedCallFeature("视频通话功能暂未接入"));
        findViewById(R.id.mic_button).setOnClickListener(v -> toggleCallMute());
        avatarView.setOnLongClickListener(v -> {
            avatarView.playExpressionDemo();
            subtitle.setText("正在依次展示豆芽的所有表情");
            return true;
        });
        findViewById(R.id.chat_send).setOnClickListener(v -> handleChatPlusButton());
        findViewById(R.id.chat_camera).setOnClickListener(v ->
                Toast.makeText(this, "拍照识别稍后接入", Toast.LENGTH_SHORT).show());
        chatVoiceButton.setOnClickListener(v -> toggleChatVoiceMode());
        chatInput.setOnTouchListener((v, event) -> {
            if (!chatVoiceMode) {
                return false;
            }
            handleVoiceHoldTouch(event);
            return true;
        });
        findViewById(R.id.save_model_button).setOnClickListener(v -> saveModel());
        findViewById(R.id.suggestion_summary).setOnClickListener(v -> fillChatPrompt("请用简短直接的方式回答："));
        findViewById(R.id.suggestion_create).setOnClickListener(v -> fillChatPrompt("帮我写一段清晰自然的文案："));
        findViewById(R.id.suggestion_minutes).setOnClickListener(v -> fillChatPrompt("请帮我解答这道题："));
        findViewById(R.id.suggestion_qa).setOnClickListener(v -> fillChatPrompt("请深入研究并给出结构化分析："));
    }

    private void initTextToSpeech() {
        // 使用 Android 系统 TextToSpeech 播放模型回复。
        // 如果中文语言包不可用，系统可能回退到设备默认语言，但界面仍可继续使用。
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.CHINA);
            }
        });
    }

    private void showCall() {
        // 进入通话页时重置本次页面计时，同时保留上次通话 transcript。
        // 这样用户切回通话页时仍能看到最近一次语音内容。
        switchScreen(callScreen);
        refreshModelBadge();
        callStartedAt = System.currentTimeMillis();
        transcript.setText(lastTranscript());
        callMuted = false;
        updateMicButtonState();
        setCallState(CallState.IDLE, "期待", "豆芽正在准备自动监听");
        startCallTimer();
        scheduleAutoListening(250);
    }

    private void showChat() {
        // 每次显示聊天页都重新渲染列表，保证模型回复、语音输入、
        // 模型配置切换后的状态都能同步到界面。
        switchScreen(chatScreen);
        refreshModelBadge();
        cancelAutoListening();
        renderChatList();
    }

    private void exitCallToChat() {
        // 底部红色 X 是“结束本次语音通话并返回文字对话”，不是普通页面切换。
        // 离开前要立即停止收音、取消自动监听和播放，避免切回文字页后麦克风仍在后台工作。
        Log.d(TAG, "call close clicked, exit voice call to chat");
        stopCallAudioSession();
        callMuted = false;
        updateMicButtonState();
        showChat();
    }

    private void stopCallAudioSession() {
        // 这里使用“立即停止”语义：不再请求火山/实时识别输出最终结果，
        // 因为用户点击 X 或静音时表达的是停止收录声音，而不是提交当前半句话。
        cancelAutoListening();
        cancelAiSpeechEndDelay();
        stopCloudRecording(false);
        finishSpeechInput(true);
        assistantEngine.cancel();
        if (tts != null) {
            tts.stop();
        }
        if (speakingRunnable != null) {
            handler.removeCallbacks(speakingRunnable);
            speakingRunnable = null;
        }
        avatarView.setAudioEnergy(0f);
        setCallState(CallState.IDLE, "中性", "语音通话已结束");
    }

    private void showModels() {
        // 每次进入模型页都重建卡片，因为默认模型、启用状态可能刚刚被修改。
        switchScreen(modelScreen);
        refreshModelBadge();
        renderModelList();
    }

    private void switchScreen(View target) {
        // 当前版本用一个 Activity 切换三个容器，不引入 Fragment/Navigation。
        // 对这个轻量 MVP 来说，这样状态更直接，也减少样板代码。
        callScreen.setVisibility(target == callScreen ? View.VISIBLE : View.GONE);
        chatScreen.setVisibility(target == chatScreen ? View.VISIBLE : View.GONE);
        modelScreen.setVisibility(target == modelScreen ? View.VISIBLE : View.GONE);
        if (callTimerRunnable != null && target != callScreen) {
            handler.removeCallbacks(callTimerRunnable);
        }
        if (target != callScreen) {
            cancelAutoListening();
            if (speechListening) {
                stopSpeechInput();
            }
        }
    }

    private void sendCallText() {
        // call_input 当前是隐藏输入框，保留它是为了后续如果增加“键盘输入通话”
        // 可以复用和语音识别相同的模型请求流程。
        String userText = callInput.getText().toString().trim();
        if (userText.isEmpty()) {
            Toast.makeText(this, "当前语音模式会自动收录声音，文字输入稍后接入", Toast.LENGTH_SHORT).show();
            return;
        }
        callInput.setText("");
        appendTranscript("你：" + userText);
        askModelForCall(userText);
    }

    private void showUnsupportedCallFeature(String message) {
        // 底部保留豆包同款功能入口，但当前版本尚未接入视频通话等能力。
        // 给出明确提示，避免按钮点击后看起来没有任何反应。
        Log.d(TAG, "unsupported call feature clicked: " + message);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void sendChatText() {
        // 文本聊天采用“先显示、后更新”的方式：
        // 先插入用户消息和一条 AI 占位消息，模型返回过程中只更新这条占位消息。
        // 这样不会因为分段更新产生多条重复 AI 消息。
        String userText = chatInput.getText().toString().trim();
        if (userText.isEmpty()) {
            return;
        }
        chatInput.setText("");

        // 先把用户消息和“连接中”占位消息写入本地历史。
        // 后续模型成功、失败或分段返回时，只更新这条助手消息，避免消息顺序抖动。
        chatMessages.add(new ChatMessage(ChatMessage.USER_TEXT, userText, modelStore.activeModel().name));
        ChatMessage assistant = new ChatMessage(ChatMessage.ASSISTANT_TEXT, "正在连接模型...", modelStore.activeModel().name);
        chatMessages.add(assistant);
        conversationStore.saveMessages(chatMessages);
        renderChatList();

        int assistantIndex = chatMessages.size() - 1;
        assistantEngine.streamReply(userText, modelStore.activeModel(), new AssistantEngine.StreamCallback() {
            @Override
            public void onDelta(String partialText) {
                // 模型请求完成后，AssistantEngine 会用本地打字机效果持续回调这里。
                // index 指向上面创建的占位消息，只替换内容，不新增列表项。
                updateAssistantMessage(assistantIndex, partialText, false);
            }

            @Override
            public void onComplete(String finalText) {
                // 文本页收到完整回复后顺便朗读；如果不需要自动朗读，可以把 true 改为 false。
                updateAssistantMessage(assistantIndex, finalText, true);
            }

            @Override
            public void onError(String message) {
                // 错误也写进同一条 AI 气泡，用户可以长按复制完整错误信息。
                updateAssistantMessage(assistantIndex, message, false);
            }
        });
    }

    private void handleChatPlusButton() {
        // 参考豆包输入栏的“+”入口：空输入时作为更多能力入口；
        // 当前项目还没有附件面板，因此有文字时仍复用发送能力，避免丢失基础聊天操作。
        if (chatInput.getText().toString().trim().isEmpty()) {
            Toast.makeText(this, "更多功能稍后接入", Toast.LENGTH_SHORT).show();
            return;
        }
        sendChatText();
    }

    private void toggleChatVoiceMode() {
        setChatVoiceMode(!chatVoiceMode);
    }

    private void setChatVoiceMode(boolean enabled) {
        chatVoiceMode = enabled;
        hideVoiceHoldOverlay();
        if (enabled) {
            // 参考豆包输入栏：进入语音模式后中间区域只展示“按住说话”，右侧按钮变成键盘。
            chatTextDraft = chatInput.getText().toString();
            chatInput.setText("");
            chatInput.setHint("按住说话");
            chatInput.setGravity(Gravity.CENTER);
            chatInput.setFocusable(false);
            chatInput.setFocusableInTouchMode(false);
            chatInput.setCursorVisible(false);
            chatVoiceButton.setImageResource(R.drawable.ic_keyboard_circle);
            chatVoiceButton.setContentDescription("切换键盘输入");
            return;
        }
        chatInput.setGravity(Gravity.CENTER_VERTICAL);
        chatInput.setFocusable(true);
        chatInput.setFocusableInTouchMode(true);
        chatInput.setCursorVisible(true);
        chatInput.setHint("发消息或按住说话...");
        chatInput.setText(chatTextDraft);
        chatInput.setSelection(chatInput.length());
        chatVoiceButton.setImageResource(R.drawable.ic_voice_circle);
        chatVoiceButton.setContentDescription("语音输入");
    }

    private void handleVoiceHoldTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                voiceHoldCancel = false;
                showVoiceHoldOverlay(false);
                break;
            case MotionEvent.ACTION_MOVE:
                voiceHoldCancel = event.getY() < -dp(72);
                showVoiceHoldOverlay(voiceHoldCancel);
                break;
            case MotionEvent.ACTION_UP:
                hideVoiceHoldOverlay();
                if (voiceHoldCancel) {
                    Toast.makeText(this, "已取消语音输入", Toast.LENGTH_SHORT).show();
                } else {
                    fillChatInputBySpeech();
                }
                voiceHoldCancel = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                hideVoiceHoldOverlay();
                voiceHoldCancel = false;
                break;
            default:
                break;
        }
    }

    private void showVoiceHoldOverlay(boolean cancel) {
        voiceHoldOverlay.setVisibility(View.VISIBLE);
        voiceHoldHint.setText(cancel ? "松手取消" : "松手发送，上移取消");
        voiceHoldHint.setTextColor(cancel ? 0xFFFFFFFF : 0xFFEAF4FF);
        voiceHoldWave.setAlpha(cancel ? 0.35f : 1f);
    }

    private void hideVoiceHoldOverlay() {
        if (voiceHoldOverlay != null) {
            voiceHoldOverlay.setVisibility(View.GONE);
        }
    }

    private void updateAssistantMessage(int index, String content, boolean speakAfterUpdate) {
        // 异步回调可能晚于界面重建或未来的清空会话操作，所以先保护索引范围。
        if (index < 0 || index >= chatMessages.size()) {
            return;
        }
        chatMessages.get(index).content = content;
        conversationStore.saveMessages(chatMessages);
        renderChatList();
        if (speakAfterUpdate) {
            speak(content);
        }
    }

    private void askModelForCall(String userText) {
        // 通话页和文字页共用同一个文本模型接口。
        // 通话页额外做 transcript 记录，并在模型完成后调用 TTS 播放。
        setCallState(CallState.THINKING, "疑惑", "正在请求模型");
        subtitle.setText("正在请求模型...");

        // 通话页复用文本模型能力：系统语音识别得到文本后，请求 OpenAI-compatible chat 接口，
        // 再用 Android TTS 播放结果。这里不展示未实现的“实时音频流”能力。
        assistantEngine.streamReply(userText, modelStore.activeModel(), new AssistantEngine.StreamCallback() {
            @Override
            public void onDelta(String partialText) {
                // 通话页不展示聊天气泡，直接把分段文本放到字幕区域。
                subtitle.setText(partialText);
            }

            @Override
            public void onComplete(String finalText) {
                // 完整回复写入 transcript 后开始朗读，头像口型也在 startSpeaking 中启动。
                appendTranscript("豆芽：" + finalText);
                startSpeaking(finalText);
            }

            @Override
            public void onError(String message) {
                // 模型错误也写入 transcript，方便用户知道这次通话失败原因。
                appendTranscript("系统：" + message);
                subtitle.setText(message);
                setCallState(CallState.ERROR, "疑惑", "模型请求失败");
                scheduleAutoListening(1200);
            }
        });
    }

    private void toggleCallMute() {
        Log.d(TAG, "mic clicked, callMuted before toggle=" + callMuted + ", speechListening=" + speechListening);
        callMuted = !callMuted;
        updateMicButtonState();
        if (callMuted) {
            cancelAutoListening();
            cancelAiSpeechEndDelay();
            if (speechListening) {
                stopCallAudioCapture();
            }
            setCallState(CallState.IDLE, "中性", "麦克风已静音");
            subtitle.setText("已静音，点击麦克风可恢复自动识别");
            return;
        }
        subtitle.setText("已取消静音，正在恢复自动识别");
        setCallState(CallState.IDLE, "中性", "正在准备自动监听");
        scheduleAutoListening(150);
    }

    private void stopCallAudioCapture() {
        // 麦克风按钮只控制“是否继续收录声音”，不负责结束通话或切页。
        // 静音时立即关闭当前识别/录音链路，避免继续把声音送到系统识别或火山服务。
        stopCloudRecording(false);
        finishSpeechInput(true);
        avatarView.setAudioEnergy(0f);
    }

    private void scheduleAutoListening(long delayMs) {
        cancelAutoListening();
        autoListenRunnable = () -> {
            autoListenRunnable = null;
            startCallListeningOnce();
        };
        handler.postDelayed(autoListenRunnable, delayMs);
    }

    private void cancelAutoListening() {
        if (autoListenRunnable != null) {
            handler.removeCallbacks(autoListenRunnable);
            autoListenRunnable = null;
        }
    }

    private void cancelAiSpeechEndDelay() {
        if (aiSpeechEndRunnable != null) {
            handler.removeCallbacks(aiSpeechEndRunnable);
            aiSpeechEndRunnable = null;
        }
    }

    private boolean isCallScreenVisible() {
        return callScreen != null && callScreen.getVisibility() == View.VISIBLE;
    }

    private void startCallListeningOnce() {
        // 通话页进入后自动监听，不再要求用户手动点麦克风开始。
        if (!isCallScreenVisible() || callMuted || speechListening
                || callState == CallState.THINKING || callState == CallState.SPEAKING) {
            return;
        }
        setCallState(CallState.LISTENING, "好奇", "豆芽正在听你说");
        subtitle.setText("正在自动识别你的语音");
        startSpeechInput(new SpeechCallback() {
            @Override
            public void onStatus(String message) {
                subtitle.setText(message);
            }

            @Override
            public void onPartial(String text) {
                if (!callMuted && isCallScreenVisible() && callState == CallState.LISTENING) {
                    setCallState(CallState.LISTENING, expressionForUserText(text), "豆芽正在听你说");
                }
                subtitle.setText(text);
            }

            @Override
            public void onFinal(String text) {
                if (callMuted || !isCallScreenVisible()) {
                    return;
                }
                appendTranscript("你：" + text);
                if (isVolcSpeechRunning()) {
                    setCallState(CallState.THINKING, expressionForUserText(text), "豆芽正在思考");
                    subtitle.setText("豆芽正在思考...");
                    return;
                }
                askModelForCall(text);
            }

            @Override
            public void onAiPartial(String text) {
                if (callMuted || !isCallScreenVisible()) {
                    return;
                }
                currentAiExpression = expressionForReplyText(text);
                setCallState(callState == CallState.SPEAKING ? CallState.SPEAKING : CallState.THINKING,
                        currentAiExpression,
                        callState == CallState.SPEAKING ? "豆芽正在说话" : "豆芽正在回复");
                subtitle.setText(text);
            }

            @Override
            public void onAiFinal(String text) {
                if (callMuted || !isCallScreenVisible()) {
                    return;
                }
                appendTranscript("豆芽：" + text);
                currentAiExpression = expressionForReplyText(text);
                setCallState(CallState.SPEAKING, currentAiExpression, "豆芽正在说话");
                subtitle.setText(text);
            }

            @Override
            public void onAiSpeechStart() {
                if (callMuted || !isCallScreenVisible()) {
                    return;
                }
                cancelAiSpeechEndDelay();
                setCallState(CallState.SPEAKING, currentAiExpression, "豆芽正在说话");
            }

            @Override
            public void onAiSpeechEnd() {
                if (callMuted || !isCallScreenVisible()) {
                    return;
                }
                cancelAiSpeechEndDelay();
                aiSpeechEndRunnable = () -> {
                    aiSpeechEndRunnable = null;
                    if (!callMuted && isCallScreenVisible() && callState == CallState.SPEAKING) {
                        setCallState(CallState.LISTENING, "好奇", "豆芽正在继续听");
                    }
                };
                handler.postDelayed(aiSpeechEndRunnable, 900);
            }

            @Override
            public void onError(String message) {
                subtitle.setText(message);
                setCallState(CallState.ERROR, "疑惑", "语音识别失败");
                scheduleAutoListening(1200);
            }
        });
    }

    private void fillChatInputBySpeech() {
        // 聊天页语音识别只负责填入输入框，不自动发送。
        // 这样可以避免环境噪音或识别错误直接触发模型请求。
        if (isVolcSpeechRunning()) {
            requestVolcFinal();
            chatInput.setHint("正在结束火山实时识别...");
            return;
        }
        if (isRealtimeSpeechRunning()) {
            requestRealtimeFinal();
            chatInput.setHint("正在结束实时识别...");
            return;
        }
        if (cloudRecording) {
            stopCloudRecordingAndTranscribe();
            return;
        }
        startSpeechInput(new SpeechCallback() {
            @Override
            public void onStatus(String message) {
                chatInput.setHint(message);
            }

            @Override
            public void onPartial(String text) {
                chatInput.setText(text);
                chatInput.setSelection(chatInput.length());
            }

            @Override
            public void onFinal(String text) {
                chatInput.setText(text);
                chatInput.setSelection(chatInput.length());
            }

            @Override
            public void onError(String message) {
                chatInput.setHint("输入消息，或用语音提问");
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void fillChatPrompt(String prompt) {
        // 快捷建议只是“提示词起手式”，不是独立模式。
        // 填入后用户仍然可以继续编辑。
        if (chatVoiceMode) {
            setChatVoiceMode(false);
        }
        chatInput.setText(prompt);
        chatInput.setSelection(chatInput.length());
    }

    private void startSpeaking(String text) {
        // TTS 播放和头像口型通过估算时长做弱同步。
        // 当前没有服务端音频帧，也没有音素级口型数据，所以这里不伪装成实时音频流。
        currentAiExpression = expressionForReplyText(text);
        setCallState(CallState.SPEAKING, currentAiExpression, "豆芽正在说话");
        subtitle.setText(text);
        speak(text);

        // Android TTS 没有直接提供跨设备稳定的音频能量回调。
        // 这里仅用文本长度估算朗读持续时间，并驱动三档口型动画；这对应“系统 TTS 播放”的真实能力，
        // 不冒充服务端音频流或音素级 viseme。
        final int[] tick = {0};
        speakingRunnable = new Runnable() {
            @Override
            public void run() {
                if (callState != CallState.SPEAKING) {
                    // 状态已经切走时停止本轮口型刷新，避免打断后嘴巴继续动。
                    return;
                }
                // 用 tick 生成三档能量值，让口型在朗读期间有明显开合变化。
                float energy = (tick[0] % 4 == 0) ? 0.12f : (tick[0] % 3 == 0 ? 0.9f : 0.48f);
                avatarView.setAudioEnergy(energy);
                tick[0]++;
                handler.postDelayed(this, 120);
            }
        };
        handler.post(speakingRunnable);

        long estimatedMs = Math.max(1800, text.length() * 180L);
        handler.postDelayed(() -> {
            if (callState == CallState.SPEAKING) {
                // 到达估算播报时间后恢复待机状态；如果用户提前打断，状态已改变，不会进入这里。
                handler.removeCallbacks(speakingRunnable);
                setCallState(CallState.IDLE, "中性", callMuted ? "麦克风已静音" : "正在准备自动监听");
                if (!callMuted && isCallScreenVisible()) {
                    scheduleAutoListening(250);
                }
            }
        }, estimatedMs);
    }

    private void interruptSpeaking() {
        // 打断会停止本地 TTS、取消可见回复动画，并短暂显示“已打断”状态。
        if (tts != null) {
            tts.stop();
        }
        assistantEngine.cancel();
        cancelAiSpeechEndDelay();
        if (speakingRunnable != null) {
            handler.removeCallbacks(speakingRunnable);
        }
        setCallState(CallState.INTERRUPTED, "认真", "已打断");
        subtitle.setText("已打断");
        handler.postDelayed(() -> {
            setCallState(CallState.IDLE, "中性", callMuted ? "麦克风已静音" : "正在准备自动监听");
            if (!callMuted && isCallScreenVisible()) {
                scheduleAutoListening(150);
            }
        }, 450);
    }

    private void toggleSubtitles() {
        // 这里只控制字幕 TextView 是否显示，不影响 transcript 持久化或模型请求。
        subtitlesEnabled = !subtitlesEnabled;
        subtitle.setVisibility(subtitlesEnabled ? View.VISIBLE : View.GONE);
    }

    private void startSpeechInput(SpeechCallback callback) {
        // 在真正使用麦克风时检查权限和系统识别能力。
        // 聊天页与通话页共用这个方法，所以失败表现保持一致。
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingSpeechCallback = callback;
            callback.onStatus("请先授权麦克风权限");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            return;
        }
        if (startVolcSpeechInput(callback)) {
            return;
        }
        if (startRealtimeSpeechInput(callback)) {
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            startCloudSpeechInput(callback, "系统语音识别不可用，已切换为云端录音识别。再次点击麦克风结束录音。");
            return;
        }
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }

        // 使用 Android 系统 SpeechRecognizer。它的可用性取决于设备系统服务，
        // 所以失败时只展示错误，不回退到假转写。
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechListening = true;
        callback.onStatus("正在启动语音识别...");
        scheduleSpeechTimeout(callback);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { callback.onStatus("请开始说话"); }
            @Override public void onBeginningOfSpeech() { callback.onStatus("检测到语音"); }
            @Override public void onRmsChanged(float rmsdB) {
                if (callState == CallState.LISTENING) {
                    // 识别期间把系统返回的音量粗略映射到头像能量，让角色对用户声音有反馈。
                    avatarView.setAudioEnergy(Math.min(1f, Math.max(0f, rmsdB / 10f)));
                }
            }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { callback.onStatus("语音结束，正在识别"); }
            @Override public void onError(int error) {
                finishSpeechInput(false);
                if ((error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_SERVER)
                        && startCloudSpeechInput(callback, "系统语音服务异常，已切换为云端录音识别。再次点击麦克风结束录音。")) {
                    return;
                }
                callback.onError(speechErrorMessage(error));
            }
            @Override public void onResults(Bundle results) {
                finishSpeechInput(false);
                // 系统识别结果可能为空，先取候选列表第一项作为最终文本。
                ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = list == null || list.isEmpty() ? "" : list.get(0);
                if (text.isEmpty()) {
                    callback.onError("没有识别到内容。");
                } else {
                    callback.onFinal(text);
                }
            }
            @Override public void onPartialResults(Bundle partialResults) {
                // 部分结果用于实时字幕/输入框预填，不会立即发送给模型。
                ArrayList<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty()) {
                    callback.onPartial(list.get(0));
                }
            }
            @Override public void onEvent(int eventType, Bundle params) { }
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        // 使用自由表达模式，并固定中文识别语言，减少系统按设备语言误判。
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900);
        speechRecognizer.startListening(intent);
    }

    private boolean startVolcSpeechInput(SpeechCallback callback) {
        ModelConfig model = modelStore.activeModel();
        if (!VolcSpeechClient.canUse(model)) {
            return false;
        }
        stopVolcSpeechInput();
        volcSpeechClient = new VolcSpeechClient(this, getApplication(), handler);
        speechListening = true;
        volcSpeechClient.start(model, new VolcSpeechClient.Callback() {
            @Override
            public void onStatus(String message) {
                callback.onStatus(message);
            }

            @Override
            public void onVolume(float volume) {
                if ((callState == CallState.LISTENING || callState == CallState.SPEAKING) && avatarView != null) {
                    avatarView.setAudioEnergy(volume);
                }
            }

            @Override
            public void onPartial(String text) {
                if (callState == CallState.LISTENING && avatarView != null) {
                    avatarView.setAudioEnergy(0.62f);
                }
                callback.onPartial(text);
            }

            @Override
            public void onFinal(String text) {
                callback.onFinal(text);
            }

            @Override
            public void onAiPartial(String text) {
                callback.onAiPartial(text);
            }

            @Override
            public void onAiFinal(String text) {
                callback.onAiFinal(text);
            }

            @Override
            public void onAiSpeechStart() {
                callback.onAiSpeechStart();
            }

            @Override
            public void onAiSpeechEnd() {
                callback.onAiSpeechEnd();
            }

            @Override
            public void onError(String message) {
                finishSpeechInput(false);
                callback.onError(message);
            }
        });
        return true;
    }

    private boolean isVolcSpeechRunning() {
        return volcSpeechClient != null && volcSpeechClient.isRunning();
    }

    private void requestVolcFinal() {
        if (volcSpeechClient != null) {
            volcSpeechClient.requestFinal();
        }
    }

    private void stopVolcSpeechInput() {
        if (volcSpeechClient != null) {
            volcSpeechClient.stop();
            volcSpeechClient = null;
        }
    }

    private void stopVolcSpeechInputAsync() {
        VolcSpeechClient client = volcSpeechClient;
        volcSpeechClient = null;
        if (client == null) {
            return;
        }
        new Thread(() -> {
            try {
                client.stop();
            } catch (Exception exception) {
                Log.e(TAG, "stop volc speech client failed", exception);
            }
        }, "stop-volc-speech").start();
    }

    private boolean startRealtimeSpeechInput(SpeechCallback callback) {
        ModelConfig model = modelStore.activeModel();
        if (!RealtimeSpeechClient.canUse(model)) {
            return false;
        }
        stopRealtimeSpeechInput();
        realtimeSpeechClient = new RealtimeSpeechClient(this, handler);
        speechListening = true;
        realtimeSpeechClient.start(model, new RealtimeSpeechClient.Callback() {
            @Override
            public void onStatus(String message) {
                callback.onStatus(message);
            }

            @Override
            public void onPartial(String text) {
                callback.onPartial(text);
            }

            @Override
            public void onFinal(String text) {
                finishSpeechInput(false);
                callback.onFinal(text);
            }

            @Override
            public void onError(String message) {
                finishSpeechInput(false);
                callback.onError(message);
            }
        });
        return true;
    }

    private boolean isRealtimeSpeechRunning() {
        return realtimeSpeechClient != null && realtimeSpeechClient.isRunning();
    }

    private void requestRealtimeFinal() {
        if (realtimeSpeechClient != null) {
            realtimeSpeechClient.requestFinal();
        }
    }

    private void stopRealtimeSpeechInput() {
        if (realtimeSpeechClient != null) {
            realtimeSpeechClient.stop();
            realtimeSpeechClient = null;
        }
    }

    private void stopRealtimeSpeechInputAsync() {
        RealtimeSpeechClient client = realtimeSpeechClient;
        realtimeSpeechClient = null;
        if (client == null) {
            return;
        }
        new Thread(() -> {
            try {
                client.stop();
            } catch (Exception exception) {
                Log.e(TAG, "stop realtime speech client failed", exception);
            }
        }, "stop-realtime-speech").start();
    }

    private boolean startSpeechActivityFallback(SpeechCallback callback) {
        // 某些设备没有可绑定的 RecognitionService，但仍提供系统语音输入界面。
        // 这种情况下使用 startActivityForResult 作为兜底方案。
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "请开始说话");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        if (intent.resolveActivity(getPackageManager()) == null) {
            return false;
        }
        activitySpeechCallback = callback;
        speechListening = true;
        callback.onStatus("正在打开系统语音输入...");
        try {
            startActivityForResult(intent, REQ_RECOGNIZE_SPEECH);
            return true;
        } catch (Exception exception) {
            activitySpeechCallback = null;
            finishSpeechInput(false);
            return false;
        }
    }

    private boolean startCloudSpeechInput(SpeechCallback callback, String status) {
        ModelConfig model = modelStore.activeModel();
        if (!model.enabled || model.baseUrl.trim().isEmpty() || model.apiKey.trim().isEmpty()) {
            callback.onError("云端语音识别需要先在模型配置页填写 Base URL、API Key，并启用模型。");
            return false;
        }
        try {
            cloudSpeechFile = File.createTempFile("speech_", ".m4a", getCacheDir());
            audioRecorder = new MediaRecorder();
            audioRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            audioRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            audioRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            audioRecorder.setAudioEncodingBitRate(96000);
            audioRecorder.setAudioSamplingRate(44100);
            audioRecorder.setOutputFile(cloudSpeechFile.getAbsolutePath());
            audioRecorder.prepare();
            audioRecorder.start();
            cloudSpeechCallback = callback;
            cloudRecording = true;
            speechListening = true;
            callback.onStatus(status);
            scheduleCloudRecordingLimit();
            return true;
        } catch (Exception exception) {
            stopCloudRecording(false);
            callback.onError("无法启动云端录音识别：" + safeMessage(exception));
            return false;
        }
    }

    private void scheduleCloudRecordingLimit() {
        if (cloudRecordingLimitRunnable != null) {
            handler.removeCallbacks(cloudRecordingLimitRunnable);
        }
        cloudRecordingLimitRunnable = () -> {
            if (cloudRecording) {
                stopCloudRecordingAndTranscribe();
            }
        };
        handler.postDelayed(cloudRecordingLimitRunnable, 30000);
    }

    private void stopCloudRecordingAndTranscribe() {
        SpeechCallback callback = cloudSpeechCallback;
        File audioFile = cloudSpeechFile;
        boolean stopped = stopCloudRecording(true);
        if (callback == null) {
            return;
        }
        if (!stopped || audioFile == null || !audioFile.exists() || audioFile.length() < 512) {
            callback.onError("录音太短或没有声音，请重新录制。");
            return;
        }
        callback.onStatus("录音完成，正在云端识别...");
        modelGateway.transcribeAudio(modelStore.activeModel(), audioFile, new ModelGateway.ChatCallback() {
            @Override
            public void onSuccess(String reply) {
                callback.onFinal(reply);
                if (audioFile.exists()) {
                    audioFile.delete();
                }
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
                if (audioFile.exists()) {
                    audioFile.delete();
                }
            }
        });
    }

    private boolean stopCloudRecording(boolean saveFile) {
        boolean stopped = false;
        if (cloudRecordingLimitRunnable != null) {
            handler.removeCallbacks(cloudRecordingLimitRunnable);
            cloudRecordingLimitRunnable = null;
        }
        if (audioRecorder != null) {
            try {
                audioRecorder.stop();
                stopped = true;
            } catch (Exception ignored) {
                stopped = false;
            }
            audioRecorder.release();
            audioRecorder = null;
        }
        cloudRecording = false;
        speechListening = false;
        cloudSpeechCallback = null;
        if (!saveFile && cloudSpeechFile != null && cloudSpeechFile.exists()) {
            cloudSpeechFile.delete();
        }
        if (!saveFile) {
            cloudSpeechFile = null;
        }
        return stopped;
    }

    private void stopSpeechInput() {
        // 主动停止只结束当前识别会话，让系统尽快回调 onEndOfSpeech/onResults。
        if (isVolcSpeechRunning()) {
            requestVolcFinal();
            return;
        }
        if (isRealtimeSpeechRunning()) {
            requestRealtimeFinal();
            return;
        }
        if (cloudRecording) {
            stopCloudRecordingAndTranscribe();
            return;
        }
        if (speechRecognizer != null && speechListening) {
            speechRecognizer.stopListening();
        }
        finishSpeechInput(false);
    }

    private void scheduleSpeechTimeout(SpeechCallback callback) {
        if (speechTimeoutRunnable != null) {
            handler.removeCallbacks(speechTimeoutRunnable);
        }
        speechTimeoutRunnable = () -> {
            if (!speechListening) {
                return;
            }
            finishSpeechInput(true);
            callback.onError("语音识别超时，请靠近麦克风后重试。");
        };
        handler.postDelayed(speechTimeoutRunnable, 20000);
    }

    private void finishSpeechInput(boolean cancelRecognizer) {
        // 所有识别结束路径都走这里，统一清理超时任务和识别状态。
        speechListening = false;
        if (speechTimeoutRunnable != null) {
            handler.removeCallbacks(speechTimeoutRunnable);
            speechTimeoutRunnable = null;
        }
        if (cancelRecognizer && speechRecognizer != null) {
            speechRecognizer.cancel();
        }
        if (cancelRecognizer) {
            stopVolcSpeechInputAsync();
            stopRealtimeSpeechInputAsync();
        }
    }

    private String speechErrorMessage(int error) {
        // 把系统错误码转换成用户能理解的中文说明，同时保留原始错误码便于调试。
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "录音失败，请检查麦克风。错误码 " + error;
            case SpeechRecognizer.ERROR_CLIENT:
                return "语音识别客户端异常，请重试。错误码 " + error;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "缺少麦克风权限。错误码 " + error;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "语音识别网络异常，请检查网络。错误码 " + error;
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "没有识别到清晰语音，请再说一次。错误码 " + error;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "语音识别服务忙，请稍后重试。错误码 " + error;
            case SpeechRecognizer.ERROR_SERVER:
                return "系统语音识别服务异常。错误码 " + error;
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "没有检测到语音，请靠近麦克风再试。错误码 " + error;
            default:
                return "语音识别失败，错误码 " + error;
        }
    }

    private void renderChatList() {
        // 当前消息数量很小，直接重建列表比维护增量更新状态更简单可靠。
        chatList.removeAllViews();
        if (chatMessages.isEmpty()) {
            // 没有历史消息时显示空状态，引导用户直接输入或语音提问。
            View empty = getLayoutInflater().inflate(R.layout.empty_chat_state, chatList, false);
            chatList.addView(empty, new LinearLayout.LayoutParams(-1, dp(360)));
            return;
        }
        LayoutInflater inflater = getLayoutInflater();
        long lastTime = 0L;
        for (ChatMessage message : chatMessages) {
            if (lastTime == 0L || message.createdAt - lastTime > 5 * 60 * 1000L) {
                TextView time = new TextView(this);
                time.setText(formatChatTime(message.createdAt));
                time.setTextColor(0xFFB0B4BA);
                time.setTextSize(13);
                time.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(-1, dp(28));
                timeLp.setMargins(0, dp(8), 0, dp(8));
                chatList.addView(time, timeLp);
            }
            lastTime = message.createdAt;
            // 用户消息靠右、AI/系统消息靠左，通过左右 margin 形成聊天气泡布局。
            boolean user = ChatMessage.USER_TEXT.equals(message.type) || ChatMessage.USER_AUDIO.equals(message.type);
            TextView bubble = (TextView) inflater.inflate(R.layout.item_chat_message, chatList, false);
            bubble.setText(message.content);
            bubble.setTextColor(user ? 0xFFFFFFFF : 0xFF111111);
            bubble.setTextSize(17);
            bubble.setBackgroundResource(user ? R.drawable.bg_user_bubble : R.drawable.bg_assistant_card);
            bubble.setOnLongClickListener(v -> {
                // 长按复制当前气泡内容，保持和界面展示一致。
                copy(message.content);
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(user ? dp(86) : 0, dp(8), user ? 0 : dp(54), dp(8));
            chatList.addView(bubble, lp);
        }
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private String formatChatTime(long timeMs) {
        // 聊天页只需要轻量时间分隔，展示小时和分钟即可。
        return new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(timeMs));
    }

    private void renderModelList() {
        // 模型数量预计很少，手动 inflate 卡片即可；这里不引入 RecyclerView。
        modelList.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        for (ModelConfig model : modelStore.loadModels()) {
            LinearLayout card = (LinearLayout) inflater.inflate(R.layout.item_model_card, modelList, false);
            TextView name = card.findViewById(R.id.model_card_name);
            TextView url = card.findViewById(R.id.model_card_url);
            Button active = card.findViewById(R.id.model_card_active);
            Button test = card.findViewById(R.id.model_card_test);
            boolean isActive = model.name.equals(modelStore.activeModel().name);
            // 卡片主标题展示模型名称和启用状态，副标题展示 Base URL 方便快速排错。
            name.setText(model.name + (model.enabled ? " · 已启用" : " · 未启用"));
            url.setText(model.baseUrl.isEmpty() ? "Base URL 未填写" : model.baseUrl);
            active.setText(isActive ? "当前默认" : "设为默认");
            // 当前默认模型不允许再次点击，降低误操作和视觉噪声。
            active.setEnabled(!isActive);
            active.setAlpha(isActive ? 0.62f : 1f);
            card.setElevation(dp(1));
            active.setOnClickListener(v -> {
                modelStore.setActiveModel(model.name);
                refreshModelBadge();
                renderModelList();
            });
            test.setOnClickListener(v -> testModel(model));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, 0, 0, dp(8));
            modelList.addView(card, lp);
        }
    }

    private void saveModel() {
        // 保存表单时只做基础 trim，不在这里发请求。
        // 模型是否真正可用由 ModelGateway 在请求前统一判断。
        ModelConfig config = new ModelConfig();
        config.name = valueOr(modelNameInput, "OpenAI Compatible");
        config.provider = "OpenAI-compatible";
        config.protocol = ModelConfig.PROTOCOL_OPENAI;
        config.baseUrl = baseUrlInput.getText().toString().trim();
        config.apiKey = apiKeyInput.getText().toString().trim();
        config.modelId = modelIdInput.getText().toString().trim();
        config.speechModelId = valueOr(speechModelIdInput, "gpt-4o-mini-transcribe");
        config.volcAppId = volcAppIdInput.getText().toString().trim();
        config.volcAppKey = volcAppKeyInput.getText().toString().trim();
        config.volcToken = volcTokenInput.getText().toString().trim();
        config.volcResourceId = volcResourceIdInput.getText().toString().trim();
        config.volcCluster = valueOr(volcClusterInput, "volcengine_streaming_common");
        config.volcBotName = valueOr(volcBotNameInput, "豆芽");
        config.volcPayload = volcPayloadInput.getText().toString().trim();
        config.timeoutMs = parseInt(timeoutInput.getText().toString(), 15000);
        config.enabled = modelEnabledCheck.isChecked();

        List<ModelConfig> models = modelStore.loadModels();
        // 当前版本允许同名模型追加保存；如果后续要做编辑模式，可以在这里按名称替换。
        models.add(config);
        modelStore.saveModels(models);
        modelStore.setActiveModel(config.name);
        refreshModelBadge();
        renderModelList();
        Toast.makeText(this, "已保存。文本和通话会使用这个真实模型。", Toast.LENGTH_LONG).show();
    }

    private void testModel(ModelConfig model) {
        // 测试按钮会真实请求模型，避免只校验本地字段造成“看起来可用”的假结果。
        Toast.makeText(this, "正在真实请求模型...", Toast.LENGTH_SHORT).show();
        modelGateway.test(model, result -> Toast.makeText(
                this,
                (result.success ? "测试通过" : "测试失败") + "：耗时 " + result.totalMs + "ms，" + result.message,
                Toast.LENGTH_LONG
        ).show());
    }

    private void setCallState(CallState state, String expression, String label) {
        // 集中更新通话状态文字和头像状态，保证视觉状态和业务状态一致。
        callState = state;
        if (avatarView != null) {
            avatarView.setState(state);
            avatarView.setExpression(expression);
            if (state != CallState.SPEAKING) {
                avatarView.setAudioEnergy(0f);
            }
        }
        callStatus.setText(label);
    }

    private String expressionForUserText(String text) {
        String value = text == null ? "" : text;
        if (containsAny(value, "生气", "气死", "烦死", "讨厌", "别再")) {
            return "生气";
        }
        if (containsAny(value, "不要", "拒绝", "不想", "算了", "别说了", "停一下")) {
            return "拒绝";
        }
        if (containsAny(value, "震惊", "吓我", "不会吧", "真的假的", "怎么可能")) {
            return "震惊";
        }
        if (containsAny(value, "惊吓", "吓一跳", "吓到了", "突然吓到")) {
            return "惊吓";
        }
        if (containsAny(value, "害怕", "恐惧", "吓人", "怕了", "有点怕")) {
            return "害怕";
        }
        if (containsAny(value, "担忧", "担心死", "不放心", "忧虑", "发愁")) {
            return "担忧";
        }
        if (containsAny(value, "紧张", "担心", "来不及", "急")) {
            return "紧张";
        }
        if (containsAny(value, "困", "累", "疲惫", "想睡", "没精神")) {
            return "疲惫";
        }
        if (containsAny(value, "害羞", "不好意思", "脸红", "羞")) {
            return "害羞";
        }
        if (containsAny(value, "尴尬", "尬住", "社死", "好尬")) {
            return "尴尬";
        }
        if (containsAny(value, "无语", "离谱", "服了", "没话说")) {
            return "无语";
        }
        if (containsAny(value, "难过", "委屈", "伤心", "想哭", "失望")) {
            return "难过";
        }
        if (containsAny(value, "感动", "暖心", "破防", "谢谢你帮我")) {
            return "感动";
        }
        if (containsAny(value, "骄傲", "自豪", "厉害吧", "棒吧")) {
            return "骄傲";
        }
        if (containsAny(value, "佩服", "膜拜", "太强了", "牛啊")) {
            return "佩服";
        }
        if (containsAny(value, "庆祝", "恭喜", "太好了", "成功了", "完成了")) {
            return "庆祝";
        }
        if (containsAny(value, "鼓励", "加油", "支持我", "给我打气")) {
            return "鼓励";
        }
        if (containsAny(value, "调皮", "开玩笑", "逗你", "哈哈哈")) {
            return "调皮";
        }
        if (containsAny(value, "放松", "轻松", "舒服", "不急", "慢慢来")) {
            return "放松";
        }
        if (containsAny(value, "喜欢", "爱你", "真棒", "厉害", "可爱")) {
            return "喜欢";
        }
        if (containsAny(value, "同意", "对", "没错", "可以", "赞同")) {
            return "赞同";
        }
        if (containsAny(value, "想想", "思考", "考虑", "推理", "分析一下")) {
            return "思考";
        }
        if (containsAny(value, "冷静", "慢慢", "分析", "理一下")) {
            return "冷静";
        }
        if (containsAny(value, "惊喜", "哇", "居然", "太神奇")) {
            return "惊喜";
        }
        if (containsAny(value, "为什么", "怎么", "吗", "？", "?")) {
            return "好奇";
        }
        if (containsAny(value, "谢谢", "太好了", "开心", "喜欢", "成功")) {
            return "开心";
        }
        if (containsAny(value, "错", "失败", "不行", "崩", "报错", "问题")) {
            return "认真";
        }
        if (containsAny(value, "难过", "烦", "压力", "焦虑")) {
            return "安慰";
        }
        return "专注";
    }

    private String expressionForReplyText(String text) {
        String value = text == null ? "" : text;
        if (containsAny(value, "不要", "不建议", "拒绝", "不能这样", "先不要")) {
            return "拒绝";
        }
        if (containsAny(value, "震惊", "没想到", "竟然", "出乎意料", "不会吧")) {
            return "震惊";
        }
        if (containsAny(value, "吓一跳", "惊吓", "突然", "猝不及防")) {
            return "惊吓";
        }
        if (containsAny(value, "危险", "害怕", "恐惧", "吓人", "风险很高")) {
            return "害怕";
        }
        if (containsAny(value, "担忧", "不放心", "需要留意", "有点担心")) {
            return "担忧";
        }
        if (containsAny(value, "别急", "我会", "马上", "先稳住")) {
            return "紧张";
        }
        if (containsAny(value, "困了", "累了", "疲惫", "休息一下", "先休息")) {
            return "疲惫";
        }
        if (containsAny(value, "不好意思", "有点害羞", "害羞", "脸红")) {
            return "害羞";
        }
        if (containsAny(value, "尴尬", "有点尴尬", "不好处理", "卡住了")) {
            return "尴尬";
        }
        if (containsAny(value, "无语", "离谱", "确实离谱", "没话说")) {
            return "无语";
        }
        if (containsAny(value, "严肃", "必须", "风险", "注意安全")) {
            return "严肃";
        }
        if (containsAny(value, "真棒", "值得骄傲", "很厉害", "做得好")) {
            return "骄傲";
        }
        if (containsAny(value, "佩服", "很强", "太强了", "厉害呀")) {
            return "佩服";
        }
        if (containsAny(value, "恭喜", "庆祝", "成功了", "完成啦", "太好了")) {
            return "庆祝";
        }
        if (containsAny(value, "加油", "你可以", "相信你", "继续保持")) {
            return "鼓励";
        }
        if (containsAny(value, "开个玩笑", "逗你", "调皮", "哈哈")) {
            return "调皮";
        }
        if (containsAny(value, "放松", "轻松一点", "慢慢来", "不着急")) {
            return "放松";
        }
        if (containsAny(value, "可爱", "喜欢", "谢谢你", "很棒")) {
            return "喜欢";
        }
        if (containsAny(value, "惊喜", "太棒", "很厉害", "哇")) {
            return "兴奋";
        }
        if (containsAny(value, "我想想", "思考", "推理", "分析一下", "判断一下")) {
            return "思考";
        }
        if (containsAny(value, "冷静", "一步一步", "先分析", "理清")) {
            return "冷静";
        }
        if (containsAny(value, "难过", "委屈", "伤心")) {
            return "安慰";
        }
        if (containsAny(value, "抱歉", "不好意思", "失败", "错误", "无法")) {
            return "抱歉";
        }
        if (containsAny(value, "同意", "没错", "对的", "可以", "赞同")) {
            return "赞同";
        }
        if (containsAny(value, "太好了", "完成", "成功", "当然")) {
            return "开心";
        }
        if (containsAny(value, "注意", "建议", "需要", "检查", "步骤")) {
            return "认真";
        }
        if (containsAny(value, "别担心", "没关系", "我来", "可以慢慢")) {
            return "安慰";
        }
        if (containsAny(value, "哇", "惊喜", "厉害")) {
            return "惊喜";
        }
        return "微笑";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private void updateMicButtonState() {
        if (micButton == null) {
            return;
        }
        // 通话页参考豆包的底部控制区：正常收音是浅灰底黑麦克风；
        // 静音后切换为白底红色斜杠麦克风，让“正在静音”一眼可见。
        micButton.setImageResource(callMuted ? R.drawable.ic_mic_muted_red : R.drawable.ic_mic);
        micButton.setBackgroundResource(callMuted
                ? R.drawable.bg_doubao_call_button_muted
                : R.drawable.bg_doubao_call_button);
        micButton.setImageTintList(ColorStateList.valueOf(callMuted ? 0xFFFF2D2D : 0xFF18191C));
        micButton.setContentDescription(callMuted ? "取消静音" : "静音");
    }

    private void appendTranscript(String line) {
        // transcript 在一次通话中按行追加，并在每次追加后立即保存。
        // 这样页面切换或 Activity 重建后仍能恢复最近通话内容。
        String current = transcript.getText().toString();
        String next = current.startsWith("Transcript") ? line : current + "\n" + line;
        transcript.setText(next);
        conversationStore.saveTranscript(next);
        appendCallMessageToChat(line);
    }

    private void appendCallMessageToChat(String line) {
        // 语音通话和文字聊天共用同一份历史记录，用户从通话页返回后不应该丢失刚才的对话。
        // transcript 行里带有“你：/豆芽：/系统：”前缀，这里只把前缀转换成消息类型，气泡里仍然只展示干净内容。
        String type = ChatMessage.SYSTEM_TIP;
        String content = line;
        if (line.startsWith("你：")) {
            type = ChatMessage.USER_AUDIO;
            content = line.substring("你：".length()).trim();
        } else if (line.startsWith("豆芽：")) {
            type = ChatMessage.ASSISTANT_TEXT;
            content = line.substring("豆芽：".length()).trim();
        } else if (line.startsWith("系统：")) {
            type = ChatMessage.SYSTEM_TIP;
            content = line.substring("系统：".length()).trim();
        }
        if (content.isEmpty()) {
            return;
        }
        chatMessages.add(new ChatMessage(type, content, modelStore.activeModel().name));
        conversationStore.saveMessages(chatMessages);
        if (chatScreen.getVisibility() == View.VISIBLE) {
            renderChatList();
        }
    }

    private String lastTranscript() {
        // 没有历史 transcript 时显示占位文案，避免通话页出现空白卡片。
        String value = conversationStore.loadTranscript();
        return value.isEmpty() ? "Transcript 会保留最近一次通话内容。" : value;
    }

    private void refreshModelBadge() {
        // 聊天页参考豆包样式，顶部只保留“内容由 AI 生成”的轻提示。
        modelBadge.setText("内容由 AI 生成");
    }

    private void requestMicIfNeeded() {
        // 这里先触发系统权限弹窗；startSpeechInput 内部仍会再次严格检查权限。
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
        }
    }

    private void startCallTimer() {
        // 计时器只表示用户停留在通话页的时长，不代表模型耗时或语音识别耗时。
        if (callTimerRunnable != null) {
            handler.removeCallbacks(callTimerRunnable);
        }
        callTimerRunnable = new Runnable() {
            @Override
            public void run() {
                long seconds = (System.currentTimeMillis() - callStartedAt) / 1000;
                callTimer.setText(String.format(Locale.CHINA, "实时语音对话 · %02d:%02d", seconds / 60, seconds % 60));
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(callTimerRunnable);
    }

    private void speak(String text) {
        // QUEUE_FLUSH 表示新回复会打断旧播报，避免多个回复排队朗读。
        if (tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant_reply");
        }
    }

    private TextView simpleText(String value, int sp, int color) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setTextColor(color);
        textView.setLineSpacing(3f, 1.05f);
        return textView;
    }

    private String valueOr(EditText editText, String fallback) {
        // 只用于模型名称这类可选字段；Base URL/API Key/Model ID 必须由用户明确填写。
        String value = editText.getText().toString().trim();
        return value.isEmpty() ? fallback : value;
    }

    private int parseInt(String raw, int fallback) {
        // 超时时间输入错误不应导致保存崩溃，解析失败就使用默认值。
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private void copy(String value) {
        // 长按任意消息气泡时复制展示文本，包含说话人前缀。
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("message", value));
    }

    private int dp(int value) {
        // Java 代码里动态设置 margin/elevation 时用 dp，保持和 XML 尺寸体系一致。
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
