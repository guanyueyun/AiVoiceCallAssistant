# AiVoiceCallAssistant

豆芽语音通话助手是一个 Android 原生语音 AI 对话 Demo。项目重点实现实时语音对话、豆芽角色化 UI、情绪表情动画、OpenAI-compatible 文本模型配置，以及火山引擎端到端实时语音服务接入。

## 功能概览

- 语音模式自动监听：进入通话页后自动识别用户是否在说话。
- 实时语音 AI 对话：支持火山引擎端到端实时语音服务，接收用户语音并播放 AI 回复。
- 麦克风静音切换：通话页麦克风按钮用于静音/取消静音，不再作为手动开始识别按钮。
- 豆包风格底部控制区：通话页底部提供麦克风、功能入口、视频入口和红色 X 退出按钮。
- 通话返回文字页：底部红色 X 会结束当前语音通话并返回文字聊天界面。
- 豆芽角色 UI：强制竖屏，中心头像、字幕区、通话状态和底部控制区按语音对话场景优化。
- 情绪表情系统：根据用户实时识别内容和 AI 回复内容切换表情。
- 头像动画：说话时嘴型随音量能量变化，同时保留情绪表现。
- 表情氛围特效：不同情绪有柔光、粒子、爱心、雨滴、冲击线、思考气泡、Zzz 等视觉反馈。
- 字幕优化：通话字幕最多显示两行，超出内容自动省略。
- 文字聊天界面：参考豆包对话体验优化标题栏、消息气泡、快捷入口和底部输入栏。
- 文字对话：支持 OpenAI-compatible `chat/completions` 文本模型。
- 文字页语音输入：底部语音按钮可切换键盘/按住说话模式。
- 按住说话 UI：长按输入区时显示蓝色渐变语音浮层、声纹条和“松手发送，上移取消”提示。
- 通话记录同步：语音通话中的用户语音、豆芽回复和系统提示会同步显示在文字聊天历史中。
- 模型管理：在应用内配置 Base URL、API Key、模型 ID、超时时间和启用状态。
- 本地记录：保存文本聊天历史和最近通话 transcript。
- 应用图标：启动器图标使用豆芽语音头像形象，并保留语音声波识别特征。

## 技术栈

- Android 原生 Java
- Gradle / Android Gradle Plugin
- OkHttp 4.12.0
- Android `TextToSpeech`
- Android `SpeechRecognizer` 作为系统识别兜底
- 火山引擎 SpeechEngine SDK：`com.bytedance.speechengine:speechengine_tob:0.0.14.3-bugfix`
- 自定义 Canvas 头像：`AvatarView`

## 项目结构

```text
app/src/main/java/com/example/aivoice/MainActivity.java
  主界面、通话流程、语音状态、TTS、字幕、模型配置入口

app/src/main/java/com/example/aivoice/VolcSpeechClient.java
  火山端到端实时语音服务接入

app/src/main/java/com/example/aivoice/RealtimeSpeechClient.java
  OpenAI-compatible 云端语音识别接口兜底

app/src/main/java/com/example/aivoice/ModelGateway.java
  OpenAI-compatible 文本模型请求和连通性测试

app/src/main/java/com/example/aivoice/AvatarView.java
  豆芽头像、表情、口型、氛围特效 Canvas 绘制

app/src/main/res/layout/activity_main.xml
  主界面布局

app/src/main/res/drawable/
  背景、按钮、卡片、输入框、图标等 UI 资源

app/src/main/res/mipmap-anydpi-v26/
  自适应启动器图标配置
```

## 界面说明

### 语音通话页

- 进入页面后自动监听，不需要手动点击麦克风开始。
- 麦克风按钮只负责静音/取消静音；静音后按钮会变成白底红色斜杠麦克风。
- 红色 X 按钮会停止当前录音、停止自动监听、取消 AI 输出并返回文字聊天页。
- 豆芽头像会根据监听、思考、说话、错误等状态变化表情；AI 说话时嘴巴会同步动起来。
- 字幕区域最多展示两行，避免长文本遮挡头像和底部按钮。

### 文字聊天页

- 顶部展示“豆芽”和“内容由 AI 生成”提示。
- 用户消息使用右侧蓝色气泡，豆芽回复使用左侧浅灰气泡。
- 快捷入口包含“快速”、“AI 创作”、“拍题答疑”、“深入研究”。
- 底部输入栏包含拍照入口、输入区、语音/键盘切换按钮和更多功能按钮。
- 点击语音按钮后进入按住说话模式；按住中间输入区开始语音输入，松手发送，上移取消。
- 从语音通话页产生的对话记录会自动追加到文字聊天历史中，方便回看。

## 本地配置

项目不会提交 `local.properties`。本地调试时可在项目根目录创建或更新 `local.properties`：

```properties
sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk

VOLC_APP_ID=你的火山 App ID
VOLC_APP_KEY=你的火山 App Key
VOLC_TOKEN=你的火山 Token
VOLC_RESOURCE_ID=volc.speech.dialog
VOLC_CLUSTER=volcengine_streaming_common
VOLC_BOT_NAME=豆芽
VOLC_PAYLOAD=
```

Debug 包会从 `local.properties` 或环境变量读取 `VOLC_*` 凭据并注入 `BuildConfig`。Release 包不会注入这些 Debug 凭据。

## 火山语音配置

应用默认按火山端到端实时语音对话服务配置运行：

- `resource_id`: `volc.speech.dialog`
- `bot_name`: `豆芽`
- payload 内置系统提示词会强调助手身份为“豆芽”

如果服务返回 `invalid X-Api-App-Key`、`45000001`、`55000000` 等错误，优先检查：

- App ID、App Key、Token 是否属于同一个火山应用
- App Key 是否与控制台显示一致
- `resource_id` 是否开通并与当前服务匹配
- Token 是否有效
- Debug 包是否重新构建并安装到了设备

## 构建运行

确保本机安装 JDK 17 和 Android SDK 后执行：

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleDebug
```

构建成功后 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

也可以直接用 Android Studio 打开项目并运行到真机。语音功能需要麦克风权限和网络权限。

## 安全说明

- 不要提交 `local.properties`。
- 不要把火山、OpenAI 或其他模型服务的密钥写入源码。
- Debug 凭据只用于本地调试，Release 构建中对应字段为空。
- 如果密钥曾经误提交到远端，请立即在对应平台轮换密钥。

## 当前验证状态

最近一次本地验证命令：

```powershell
.\gradlew.bat assembleDebug
```

结果：构建成功。

当前仍有 Android Gradle Plugin 与 `compileSdk 35` 的兼容提示，以及部分过时 API 提示；这些提示不影响 Debug 包构建。
