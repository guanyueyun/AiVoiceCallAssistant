package com.example.aivoice;

/**
 * 通话运行状态枚举，业务流程和 AvatarView 都会使用。
 *
 * label/statusText 保留用户可见的中文文案，方便以后把状态展示统一收口到枚举中。
 * 当前 MainActivity 里部分状态会临时覆盖文案，但枚举本身仍保留默认描述。
 */
enum CallState {
    IDLE("待机", "AI 已准备好"),
    LISTENING("聆听", "正在听你说"),
    THINKING("思考", "正在思考"),
    SPEAKING("说话", "正在说话"),
    INTERRUPTED("被打断", "已停止播报"),
    ERROR("异常", "连接异常");

    final String label;
    final String statusText;

    CallState(String label, String statusText) {
        this.label = label;
        this.statusText = statusText;
    }
}
