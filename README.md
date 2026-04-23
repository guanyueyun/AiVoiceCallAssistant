# AI 语音通话助手

这是一个 Android 原生 MVP 项目，当前只保留已经真实实现的功能，未实现的能力已经从界面中移除。

## 当前功能

- XML 布局主界面：通话、文本、模型三个页面。
- 类豆包语音助手的交互结构：中心角色、柔和浅色背景、顶部模型状态、底部语音/文本控制。
- Android 系统 `SpeechRecognizer` 语音识别。
- Android 系统 `TextToSpeech` 语音播报。
- OpenAI-compatible 文本模型调用：`POST {Base URL}/chat/completions`。
- 模型真实连通性测试：使用保存的 Base URL、API Key、Model ID 发起实际请求。
- 本地保存文本对话历史和最近一次通话 transcript。
- 2D 角色状态动画：待机、聆听、思考、说话、打断、异常。

## 已移除的假功能

- 假首页推荐角色。
- 假最近会话。
- 假设置项。
- 假 WebSocket 实时语音。
- 假 REST Provider。
- 假模型 fallback。
- 假模型测试。
- 离线模拟 AI 回复。

## 使用方式

先进入「模型」页面，填写并保存一个 OpenAI-compatible 模型：

```text
Base URL: https://api.openai.com/v1
API Key: 你的 API Key
Model ID: 支持 chat/completions 的模型 ID
```

保存后可以使用「文本」对话，也可以在「通话」页面用麦克风提问。未配置模型时，应用只会提示配置错误，不会生成假回答。

## 项目结构

```text
app/src/main/res/layout/activity_main.xml        主界面 XML
app/src/main/res/drawable/                       背景、按钮、输入框、气泡样式
app/src/main/java/com/example/aivoice/MainActivity.java
                                                   页面绑定、语音识别、TTS、状态切换
app/src/main/java/com/example/aivoice/ModelGateway.java
                                                   真实模型请求与连通性测试
app/src/main/java/com/example/aivoice/AssistantEngine.java
                                                   模型回复展示分段回调
app/src/main/java/com/example/aivoice/AvatarView.java
                                                   2D 角色绘制和口型动画
```

## 构建

用 Android Studio 打开：

```text
C:\Users\86176\Documents\Codex\2026-04-20-gt-js\AiVoiceCallAssistant
```

当前 Codex 环境没有 `java`、`gradle`、`ANDROID_HOME`，所以我无法在这里直接编译验证。请用 Android Studio 运行，如果有编译错误，把错误信息发给我继续修。
