package com.example.aivoice;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * 直接用 Canvas 绘制的轻量 2D 助手头像。
 *
 * 这样不需要维护位图资源，也能根据 CallState 实时改变表现：
 * 聆听时头部轻微倾斜，思考时表情变化，说话时根据本地音量能量驱动嘴型，
 * 错误和打断状态会让嘴型停止。绘制逻辑集中在这个 View 内，XML 只负责摆放。
 */
public class AvatarView extends View {
    private static final long MIN_EXPRESSION_HOLD_MS = 900L;
    private static final long EXPRESSION_TRANSITION_MS = 520L;
    private static final String[] DEMO_EXPRESSIONS = {
            "期待", "好奇", "专注", "认真", "微笑", "开心", "热情", "安慰",
            "惊喜", "兴奋", "喜欢", "得意", "抱歉", "委屈", "难过", "紧张",
            "冷静", "严肃", "生气", "疑惑", "害羞", "疲惫", "震惊", "赞同",
            "拒绝", "思考", "害怕", "骄傲", "庆祝", "感动", "尴尬", "无语",
            "调皮", "放松", "佩服", "鼓励", "担忧", "惊吓"
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private CallState state = CallState.IDLE;
    private String expression = "中性";
    private String queuedExpression;
    private long lastExpressionAt;
    private float phase;
    private float mouthLevel;
    private float audioEnergy;
    private float expressionProgress = 1f;
    private ValueAnimator animator;
    private ValueAnimator expressionAnimator;
    private Runnable queuedExpressionRunnable;

    public AvatarView(Context context) {
        super(context);
        init();
    }

    public AvatarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    void setState(CallState state) {
        // 切到非说话类状态时重置嘴型，避免头像看起来还在说话。
        this.state = state;
        if (state == CallState.INTERRUPTED || state == CallState.ERROR || state == CallState.LISTENING) {
            mouthLevel = 0f;
        } else if (state == CallState.SPEAKING && mouthLevel < 0.25f) {
            mouthLevel = 0.25f;
        }
        invalidate();
    }

    void setExpression(String expression) {
        // expression 是绘制方法识别的中文表情标记。
        // 业务状态到表情标记的映射由 MainActivity 控制。
        setExpressionInternal(expression, false);
    }

    void playExpressionDemo() {
        if (queuedExpressionRunnable != null) {
            removeCallbacks(queuedExpressionRunnable);
            queuedExpressionRunnable = null;
        }
        long delay = 0L;
        for (String demoExpression : DEMO_EXPRESSIONS) {
            postDelayed(() -> setExpressionInternal(demoExpression, true), delay);
            delay += 850L;
        }
    }

    private void setExpressionInternal(String nextExpression, boolean force) {
        if (nextExpression == null || nextExpression.trim().isEmpty()) {
            return;
        }
        String normalized = nextExpression.trim();
        if (normalized.equals(expression) && queuedExpressionRunnable == null) {
            return;
        }
        if (!force) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastExpressionAt;
            if (elapsed < MIN_EXPRESSION_HOLD_MS) {
                queuedExpression = normalized;
                if (queuedExpressionRunnable == null) {
                    queuedExpressionRunnable = () -> {
                        String pending = queuedExpression;
                        queuedExpression = null;
                        queuedExpressionRunnable = null;
                        setExpressionInternal(pending, true);
                    };
                    postDelayed(queuedExpressionRunnable, MIN_EXPRESSION_HOLD_MS - elapsed);
                }
                return;
            }
        }
        expression = normalized;
        lastExpressionAt = System.currentTimeMillis();
        startExpressionTransition();
    }

    private void startExpressionTransition() {
        if (expressionAnimator != null) {
            expressionAnimator.cancel();
        }
        expressionAnimator = ValueAnimator.ofFloat(0f, 1f);
        expressionAnimator.setDuration(EXPRESSION_TRANSITION_MS);
        expressionAnimator.setInterpolator(new DecelerateInterpolator());
        expressionAnimator.addUpdateListener(animation -> {
            expressionProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        expressionAnimator.start();
    }

    void setAudioEnergy(float energy) {
        // 外部传入的系统音量或模拟能量可能超出范围，先压到 0~1。
        // 当前嘴型只需要低/中/高三档，不需要精细音频分析。
        audioEnergy = Math.max(0f, Math.min(1f, energy));
        if (state == CallState.SPEAKING) {
            if (audioEnergy > 0.66f) {
                mouthLevel = 1f;
            } else if (audioEnergy > 0.24f) {
                mouthLevel = 0.55f;
            } else {
                mouthLevel = 0.12f;
            }
        }
        invalidate();
    }

    private void init() {
        // 使用软件层让阴影和透明度绘制在旧设备上更稳定。
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1800);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            phase = (float) animation.getAnimatedValue();
            if (state == CallState.SPEAKING) {
                // Android TTS 这里没有稳定的实时能量回调。
                // 用本地正弦包络补充口型变化，避免朗读时嘴巴僵住。
                float envelope = (float) (0.5f + 0.5f * Math.sin(phase * Math.PI * 8f));
                setAudioEnergy(Math.max(audioEnergy * 0.88f, envelope));
            } else {
                invalidate();
            }
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // 所有尺寸都从当前 View 宽高推导，适配不同屏幕或头像容器尺寸。
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f + 12f;
        float breathe = (float) Math.sin(phase * Math.PI * 2f);
        // 不同状态给头部不同幅度的倾斜/呼吸感，让静态头像看起来更有反馈。
        float headTilt = state == CallState.LISTENING ? -7f : state == CallState.THINKING ? 6f * breathe : 2f * breathe;
        if (isExpression("生气", "严肃", "无语")) {
            headTilt -= 3f;
        } else if (isExpression("兴奋", "惊喜", "震惊", "庆祝", "佩服", "鼓励")) {
            headTilt += 4f * breathe;
        } else if (isExpression("委屈", "难过", "抱歉", "疲惫", "害怕", "担忧", "惊吓", "尴尬")) {
            headTilt -= 5f;
        } else if (isExpression("调皮")) {
            headTilt += 7f;
        }
        float headY = cy + breathe * (state == CallState.IDLE ? 5f : 2f);

        float avatarSize = Math.min(w, h);
        drawSoftExpressionGlow(canvas, cx, cy, avatarSize);
        drawAtmosphere(canvas, cx, cy, avatarSize);
        drawExpressionAura(canvas, cx, cy, avatarSize);
        canvas.save();
        float expressionPop = 1f + (1f - Math.abs(expressionProgress * 2f - 1f)) * 0.035f;
        canvas.scale(expressionPop, expressionPop, cx, headY);
        canvas.rotate(headTilt, cx, headY);
        drawBody(canvas, cx, headY + 120f, w);
        drawHead(canvas, cx, headY, avatarSize);
        canvas.restore();
    }

    private void drawAtmosphere(Canvas canvas, float cx, float cy, float size) {
        // 外围同心圆用于表达当前状态，不额外增加图片资源。
        int color = Color.rgb(112, 153, 255);
        if (state == CallState.SPEAKING) {
            color = Color.rgb(87, 180, 255);
        } else if (state == CallState.THINKING) {
            color = Color.rgb(147, 137, 255);
        } else if (state == CallState.ERROR) {
            color = Color.rgb(239, 90, 96);
        }
        if (isExpression("开心", "热情", "兴奋", "惊喜", "骄傲", "赞同", "庆祝", "佩服", "鼓励")) {
            color = Color.rgb(255, 181, 70);
        } else if (isExpression("喜欢", "害羞", "感动", "调皮")) {
            color = Color.rgb(255, 118, 168);
        } else if (isExpression("安慰", "难过", "委屈", "疲惫", "担忧")) {
            color = Color.rgb(111, 149, 235);
        } else if (isExpression("生气", "拒绝", "严肃", "无语")) {
            color = Color.rgb(239, 68, 68);
        } else if (isExpression("思考", "好奇", "疑惑", "尴尬")) {
            color = Color.rgb(147, 137, 255);
        } else if (isExpression("放松")) {
            color = Color.rgb(45, 212, 191);
        }
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 3; i++) {
            // phase 让圆环半径轻微变化，形成呼吸/声波的感觉。
            float radius = size * (0.20f + i * 0.10f + phase * 0.035f);
            paint.setColor(withAlpha(color, 42 - i * 10));
            paint.setStrokeWidth(3.2f - i * 0.5f);
            canvas.drawCircle(cx, cy, radius, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawSoftExpressionGlow(Canvas canvas, float cx, float cy, float size) {
        // 柔光层把不同情绪的主色提前铺在头像后面，让特效和人物融为一体。
        int primary = expressionPrimaryColor();
        int secondary = expressionSecondaryColor();
        float pulse = (float) (0.5f + 0.5f * Math.sin(phase * Math.PI * 2f));
        float radius = size * (0.42f + pulse * 0.035f);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(
                cx,
                cy,
                radius,
                new int[]{
                        withAlpha(primary, 58),
                        withAlpha(secondary, 28),
                        Color.TRANSPARENT
                },
                new float[]{0f, 0.62f, 1f},
                Shader.TileMode.CLAMP
        ));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);

        // 底部椭圆阴影让头像有落点，避免大量粒子漂在空中没有重心。
        paint.setColor(withAlpha(primary, 28));
        rect.set(cx - size * 0.24f, cy + size * 0.37f, cx + size * 0.24f, cy + size * 0.46f);
        canvas.drawOval(rect, paint);
    }

    private void drawExpressionAura(Canvas canvas, float cx, float cy, float size) {
        // 这一层专门负责头像周围的“情绪氛围”，绘制在头像背后，避免遮挡口型和五官。
        float pulse = (float) (0.5f + 0.5f * Math.sin(phase * Math.PI * 2f));
        if (isExpression("开心", "热情", "兴奋", "惊喜", "骄傲", "庆祝", "佩服", "鼓励")) {
            drawRibbonArc(canvas, cx, cy, size, Color.rgb(255, 183, 77), pulse, 0f);
            drawRadiatingLines(canvas, cx, cy, size, Color.rgb(255, 183, 77), pulse);
            drawFloatingDots(canvas, cx, cy, size, Color.rgb(255, 214, 102), 10, 0.44f);
            return;
        }
        if (isExpression("喜欢", "害羞", "感动", "调皮")) {
            drawRibbonArc(canvas, cx, cy, size, Color.rgb(255, 97, 151), pulse, 45f);
            drawOrbitHearts(canvas, cx, cy, size, pulse);
            drawFloatingDots(canvas, cx, cy, size, Color.rgb(255, 151, 188), 8, 0.38f);
            return;
        }
        if (isExpression("赞同")) {
            drawRibbonArc(canvas, cx, cy, size, Color.rgb(16, 185, 129), pulse, 20f);
            drawCheckRain(canvas, cx, cy, size);
            return;
        }
        if (isExpression("生气", "拒绝", "无语")) {
            drawDangerHalo(canvas, cx, cy, size, pulse);
            drawImpactLines(canvas, cx, cy, size, Color.rgb(239, 68, 68), pulse);
            return;
        }
        if (isExpression("震惊", "害怕", "紧张", "惊吓", "担忧")) {
            drawAlertHalo(canvas, cx, cy, size, pulse);
            drawShakeMarks(canvas, cx, cy, size, pulse);
            return;
        }
        if (isExpression("难过", "委屈", "抱歉", "尴尬")) {
            drawRibbonArc(canvas, cx, cy, size, Color.rgb(96, 165, 250), pulse, 180f);
            drawRainDrops(canvas, cx, cy, size);
            return;
        }
        if (isExpression("疲惫")) {
            drawSleepClouds(canvas, cx, cy, size);
            return;
        }
        if (isExpression("思考", "疑惑", "好奇") || state == CallState.THINKING) {
            drawRibbonArc(canvas, cx, cy, size, Color.rgb(129, 140, 248), pulse, 90f);
            drawThoughtBubbles(canvas, cx, cy, size, pulse);
            return;
        }
        if (isExpression("放松")) {
            drawRibbonArc(canvas, cx, cy, size, Color.rgb(45, 212, 191), pulse, 315f);
            drawFloatingDots(canvas, cx, cy, size, Color.rgb(94, 234, 212), 7, 0.36f);
            return;
        }
        if (isExpression("冷静", "专注", "认真")) {
            drawRibbonArc(canvas, cx, cy, size, Color.rgb(59, 130, 246), pulse, 270f);
            drawFocusDots(canvas, cx, cy, size);
        }
    }

    private void drawRibbonArc(Canvas canvas, float cx, float cy, float size, int color, float pulse, float startOffset) {
        // 半透明弧带比完整圆环更轻，能提示动势但不会像边框一样把头像框死。
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(size * 0.014f);
        paint.setColor(withAlpha(color, (int) (54 + pulse * 54)));
        rect.set(cx - size * 0.39f, cy - size * 0.33f, cx + size * 0.39f, cy + size * 0.33f);
        canvas.drawArc(rect, startOffset + phase * 90f, 96f, false, paint);
        paint.setStrokeWidth(size * 0.006f);
        paint.setColor(withAlpha(color, (int) (45 + pulse * 42)));
        rect.set(cx - size * 0.45f, cy - size * 0.38f, cx + size * 0.45f, cy + size * 0.38f);
        canvas.drawArc(rect, startOffset + 165f - phase * 70f, 62f, false, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeWidth(1f);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawDangerHalo(Canvas canvas, float cx, float cy, float size, float pulse) {
        // 冷色界面里红色情绪容易显得突兀，用低透明块面先铺一层压迫感。
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(Color.rgb(239, 68, 68), (int) (30 + pulse * 35)));
        for (int i = 0; i < 6; i++) {
            float angle = (float) (i * Math.PI * 2f / 6f + phase * Math.PI * 0.4f);
            float x = cx + (float) Math.cos(angle) * size * 0.36f;
            float y = cy + (float) Math.sin(angle) * size * 0.30f;
            rect.set(x - size * 0.030f, y - size * 0.018f, x + size * 0.030f, y + size * 0.018f);
            canvas.drawRoundRect(rect, size * 0.012f, size * 0.012f, paint);
        }
    }

    private void drawAlertHalo(Canvas canvas, float cx, float cy, float size, float pulse) {
        // 震惊和害怕用断续警示环，比单个感叹号更有“空气突然紧张”的效果。
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(size * 0.008f);
        paint.setColor(withAlpha(Color.rgb(251, 146, 60), (int) (75 + pulse * 80)));
        rect.set(cx - size * 0.40f, cy - size * 0.34f, cx + size * 0.40f, cy + size * 0.34f);
        for (int i = 0; i < 4; i++) {
            canvas.drawArc(rect, i * 90f + phase * 55f, 30f, false, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeWidth(1f);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawFloatingDots(Canvas canvas, float cx, float cy, float size, int color, int count, float radiusScale) {
        // 环绕小圆点用于表达轻盈、活跃的情绪；每个点错开相位，形成漂浮感。
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < count; i++) {
            float angle = (float) (phase * Math.PI * 2f + i * Math.PI * 2f / count);
            float orbit = size * (radiusScale + (i % 3) * 0.025f);
            float x = cx + (float) Math.cos(angle) * orbit;
            float y = cy + (float) Math.sin(angle * 1.15f) * orbit * 0.78f;
            int alpha = 74 + (i % 4) * 18;
            paint.setColor(withAlpha(color, alpha));
            canvas.drawCircle(x, y, size * (0.011f + (i % 2) * 0.006f), paint);
        }
    }

    private void drawRadiatingLines(Canvas canvas, float cx, float cy, float size, int color, float pulse) {
        // 放射线表现兴奋、惊喜和自信，长度随 phase 呼吸，避免静态贴纸感。
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(size * 0.007f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(withAlpha(color, (int) (90 + pulse * 90)));
        for (int i = 0; i < 12; i++) {
            float angle = (float) (i * Math.PI * 2f / 12f + phase * Math.PI * 0.35f);
            float inner = size * (0.36f + pulse * 0.02f);
            float outer = size * (0.43f + pulse * 0.04f + (i % 2) * 0.025f);
            canvas.drawLine(
                    cx + (float) Math.cos(angle) * inner,
                    cy + (float) Math.sin(angle) * inner,
                    cx + (float) Math.cos(angle) * outer,
                    cy + (float) Math.sin(angle) * outer,
                    paint
            );
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(1f);
    }

    private void drawOrbitHearts(Canvas canvas, float cx, float cy, float size, float pulse) {
        // 喜欢和害羞使用小爱心环绕，但透明度压低，保持豆芽整体 UI 不幼稚。
        paint.setStyle(Paint.Style.FILL);
        int alpha = (int) (105 + pulse * 70);
        for (int i = 0; i < 5; i++) {
            float angle = (float) (phase * Math.PI * 2f + i * Math.PI * 2f / 5f);
            float orbit = size * (0.38f + (i % 2) * 0.04f);
            float x = cx + (float) Math.cos(angle) * orbit;
            float y = cy + (float) Math.sin(angle) * orbit * 0.82f;
            drawHeart(canvas, x, y, size * (0.022f + i % 2 * 0.006f), withAlpha(Color.rgb(255, 97, 151), alpha));
        }
    }

    private void drawHeart(Canvas canvas, float cx, float cy, float s, int color) {
        // 用 Path 画心形，避免依赖字体中的特殊符号。
        paint.setColor(color);
        path.reset();
        path.moveTo(cx, cy + s * 0.75f);
        path.cubicTo(cx - s * 1.45f, cy - s * 0.15f, cx - s * 0.95f, cy - s * 1.25f, cx, cy - s * 0.48f);
        path.cubicTo(cx + s * 0.95f, cy - s * 1.25f, cx + s * 1.45f, cy - s * 0.15f, cx, cy + s * 0.75f);
        canvas.drawPath(path, paint);
    }

    private void drawImpactLines(Canvas canvas, float cx, float cy, float size, int color, float pulse) {
        // 生气和拒绝使用更硬的折线冲击感，和开心的柔和放射线区分开。
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(size * 0.010f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(withAlpha(color, (int) (120 + pulse * 90)));
        for (int i = 0; i < 8; i++) {
            float side = i < 4 ? -1f : 1f;
            float y = cy - size * 0.26f + (i % 4) * size * 0.13f;
            float x1 = cx + side * size * (0.40f + pulse * 0.03f);
            float x2 = cx + side * size * (0.50f + pulse * 0.04f);
            float x3 = cx + side * size * (0.46f + pulse * 0.04f);
            path.reset();
            path.moveTo(x1, y);
            path.lineTo(x2, y - size * 0.035f);
            path.lineTo(x3, y + size * 0.035f);
            canvas.drawPath(path, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(1f);
    }

    private void drawShakeMarks(Canvas canvas, float cx, float cy, float size, float pulse) {
        // 紧张、害怕、震惊用短波纹和感叹点，强调周围空气在抖动。
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(size * 0.008f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(withAlpha(Color.rgb(99, 102, 241), (int) (120 + pulse * 80)));
        for (int i = 0; i < 6; i++) {
            float side = i % 2 == 0 ? -1f : 1f;
            float x = cx + side * size * (0.39f + (i / 2) * 0.035f);
            float y = cy - size * 0.24f + (i / 2) * size * 0.18f;
            path.reset();
            path.moveTo(x, y);
            path.lineTo(x + side * size * 0.035f, y + size * 0.025f);
            path.lineTo(x, y + size * 0.055f);
            canvas.drawPath(path, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(Color.rgb(239, 68, 68), (int) (110 + pulse * 90)));
        canvas.drawCircle(cx + size * 0.43f, cy - size * 0.38f, size * 0.014f, paint);
        canvas.drawCircle(cx - size * 0.43f, cy - size * 0.36f, size * 0.010f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeWidth(1f);
    }

    private void drawRainDrops(Canvas canvas, float cx, float cy, float size) {
        // 低落类情绪使用向下漂移的雨滴，位置随 phase 循环，避免固定在脸上。
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(Color.rgb(96, 165, 250), 115));
        for (int i = 0; i < 9; i++) {
            float x = cx - size * 0.42f + i * size * 0.105f;
            float dropPhase = (phase + i * 0.17f) % 1f;
            float y = cy - size * 0.44f + dropPhase * size * 0.42f;
            rect.set(x - size * 0.010f, y - size * 0.022f, x + size * 0.010f, y + size * 0.030f);
            canvas.drawOval(rect, paint);
        }
    }

    private void drawSleepClouds(Canvas canvas, float cx, float cy, float size) {
        // 疲惫表情在外圈画轻薄云团和 Z 轨迹，和脸上的 Zzz 标记形成层次。
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(Color.rgb(148, 163, 184), 54));
        for (int i = 0; i < 3; i++) {
            float x = cx - size * 0.32f + i * size * 0.26f;
            float y = cy + size * (0.34f + i % 2 * 0.035f);
            canvas.drawCircle(x, y, size * 0.055f, paint);
            canvas.drawCircle(x + size * 0.055f, y - size * 0.018f, size * 0.070f, paint);
            canvas.drawCircle(x + size * 0.120f, y, size * 0.052f, paint);
        }
        paint.setColor(withAlpha(Color.rgb(99, 102, 241), 110));
        paint.setFakeBoldText(true);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(size * 0.038f);
        canvas.drawText("Z", cx + size * 0.36f, cy - size * 0.30f - phase * size * 0.05f, paint);
        canvas.drawText("Z", cx + size * 0.43f, cy - size * 0.39f - phase * size * 0.05f, paint);
        paint.setFakeBoldText(false);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawThoughtBubbles(Canvas canvas, float cx, float cy, float size, float pulse) {
        // 思考、疑惑、好奇使用逐级上浮的小气泡，强化“正在想”的状态。
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(size * 0.006f);
        paint.setColor(withAlpha(Color.rgb(129, 140, 248), (int) (95 + pulse * 65)));
        for (int i = 0; i < 5; i++) {
            float radius = size * (0.018f + i * 0.010f);
            float x = cx + size * (0.27f + i * 0.035f);
            float y = cy - size * (0.28f + i * 0.055f) - pulse * size * 0.018f;
            canvas.drawCircle(x, y, radius, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(1f);
    }

    private void drawFocusDots(Canvas canvas, float cx, float cy, float size) {
        // 专注和认真不使用夸张贴纸，只给外圈加稳定的定位点，表现“集中注意力”。
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(Color.rgb(59, 130, 246), 82));
        for (int i = 0; i < 4; i++) {
            float angle = (float) (Math.PI / 4f + i * Math.PI / 2f);
            canvas.drawCircle(
                    cx + (float) Math.cos(angle) * size * 0.41f,
                    cy + (float) Math.sin(angle) * size * 0.34f,
                    size * 0.014f,
                    paint
            );
        }
    }

    private void drawCheckRain(Canvas canvas, float cx, float cy, float size) {
        // 赞同使用一圈轻量对勾，和脸侧的大对勾形成呼应。
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(withAlpha(Color.rgb(16, 185, 129), 118));
        for (int i = 0; i < 6; i++) {
            float angle = (float) (phase * Math.PI * 2f + i * Math.PI * 2f / 6f);
            float x = cx + (float) Math.cos(angle) * size * 0.41f;
            float y = cy + (float) Math.sin(angle) * size * 0.33f;
            drawCheck(canvas, x, y, size * 0.022f);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(1f);
    }

    private void drawBody(Canvas canvas, float cx, float cy, float w) {
        // 身体由两层圆角矩形组成，保持简洁的拟物卡通风格。
        paint.setColor(Color.rgb(229, 236, 248));
        rect.set(cx - w * 0.26f, cy - 28f, cx + w * 0.26f, cy + 92f);
        canvas.drawRoundRect(rect, 52f, 52f, paint);
        paint.setColor(Color.rgb(255, 255, 255));
        rect.set(cx - w * 0.19f, cy - 6f, cx + w * 0.19f, cy + 64f);
        canvas.drawRoundRect(rect, 36f, 36f, paint);
    }

    private void drawHead(Canvas canvas, float cx, float cy, float size) {
        // 头部大小跟随容器最短边缩放，避免横竖屏比例异常。
        float r = size * 0.31f;
        paint.setShader(new RadialGradient(
                cx - r * 0.25f,
                cy - r * 0.28f,
                r * 1.25f,
                new int[]{
                        Color.rgb(255, 238, 225),
                        Color.rgb(255, 219, 203),
                        Color.rgb(248, 195, 178)
                },
                new float[]{0f, 0.72f, 1f},
                Shader.TileMode.CLAMP
        ));
        canvas.drawCircle(cx, cy, r, paint);
        paint.setShader(null);

        // 轻微高光让脸部不再是纯色圆形，表情变化时更像一个完整角色。
        paint.setColor(withAlpha(Color.WHITE, 54));
        canvas.drawCircle(cx - r * 0.28f, cy - r * 0.24f, r * 0.28f, paint);
        paint.setColor(Color.rgb(47, 50, 60));
        rect.set(cx - r * 0.86f, cy - r * 1.02f, cx + r * 0.86f, cy - r * 0.1f);
        canvas.drawArc(rect, 180f, 180f, true, paint);
        paint.setColor(withAlpha(Color.WHITE, 36));
        rect.set(cx - r * 0.56f, cy - r * 0.96f, cx + r * 0.20f, cy - r * 0.30f);
        canvas.drawArc(rect, 205f, 55f, false, paint);

        drawEyes(canvas, cx, cy, r);
        drawBrows(canvas, cx, cy, r);
        drawMouth(canvas, cx, cy, r);
        if (isWarmExpression() || isExpression("惊喜", "喜欢", "兴奋", "得意", "害羞", "骄傲", "赞同",
                "庆祝", "感动", "调皮", "佩服", "鼓励", "放松")) {
            // 正向表情加腮红，强化“正在回应用户”的情绪反馈。
            int blushAlpha = isExpression("害羞", "感动", "调皮") ? 150 : (int) (92 * expressionProgress);
            paint.setColor(withAlpha(Color.rgb(255, 165, 185), blushAlpha));
            canvas.drawCircle(cx - r * 0.52f, cy + r * 0.18f, r * 0.09f, paint);
            canvas.drawCircle(cx + r * 0.52f, cy + r * 0.18f, r * 0.09f, paint);
        }
        drawEmotionMarks(canvas, cx, cy, r);
    }

    private void drawEyes(Canvas canvas, float cx, float cy, float r) {
        // IDLE 后段模拟眨眼，开心表情则让眼睛稍微压扁成微笑眼。
        float blink = state == CallState.IDLE && phase > 0.88f ? 0.15f : 1f;
        if (isExpression("开心", "得意", "热情", "兴奋", "骄傲", "赞同", "庆祝", "佩服", "鼓励", "放松")) {
            blink = 0.45f;
        } else if (isExpression("惊喜", "震惊", "惊吓")) {
            blink = 1.25f;
        } else if (isExpression("委屈", "抱歉", "难过", "害怕", "感动", "担忧", "尴尬")) {
            blink = 0.72f;
        } else if (isExpression("冷静", "严肃", "生气", "拒绝", "疲惫", "无语")) {
            blink = 0.55f;
        } else if (isExpression("害羞", "调皮")) {
            blink = 0.35f;
        }
        paint.setColor(Color.rgb(35, 35, 45));
        rect.set(cx - r * 0.52f, cy - r * 0.12f, cx - r * 0.24f, cy + r * 0.12f * blink);
        canvas.drawOval(rect, paint);
        rect.set(cx + r * 0.24f, cy - r * 0.12f, cx + r * 0.52f, cy + r * 0.12f * blink);
        canvas.drawOval(rect, paint);
        if (!isExpression("冷静", "严肃", "生气", "拒绝", "疲惫", "无语")) {
            // 眼睛高光会显著提升角色精致度，负向或疲惫表情则降低高光以保留情绪。
            paint.setColor(withAlpha(Color.WHITE, 150));
            canvas.drawCircle(cx - r * 0.43f, cy - r * 0.06f, r * 0.030f, paint);
            canvas.drawCircle(cx + r * 0.33f, cy - r * 0.06f, r * 0.030f, paint);
        }
        if (isExpression("喜欢", "感动")) {
            paint.setColor(Color.rgb(255, 99, 132));
            canvas.drawCircle(cx - r * 0.38f, cy - r * 0.04f, r * 0.06f, paint);
            canvas.drawCircle(cx - r * 0.30f, cy - r * 0.04f, r * 0.06f, paint);
            canvas.drawCircle(cx + r * 0.34f, cy - r * 0.04f, r * 0.06f, paint);
            canvas.drawCircle(cx + r * 0.42f, cy - r * 0.04f, r * 0.06f, paint);
        } else if (isExpression("惊喜", "兴奋", "震惊", "庆祝", "佩服", "鼓励", "惊吓")) {
            paint.setColor(Color.WHITE);
            canvas.drawCircle(cx - r * 0.38f, cy - r * 0.06f, r * 0.045f, paint);
            canvas.drawCircle(cx + r * 0.38f, cy - r * 0.06f, r * 0.045f, paint);
        }
        if (isExpression("委屈", "难过", "抱歉", "感动")) {
            paint.setColor(withAlpha(Color.rgb(147, 197, 253), 150));
            rect.set(cx - r * 0.50f, cy + r * 0.08f, cx - r * 0.34f, cy + r * 0.14f);
            canvas.drawOval(rect, paint);
            rect.set(cx + r * 0.34f, cy + r * 0.08f, cx + r * 0.50f, cy + r * 0.14f);
            canvas.drawOval(rect, paint);
        } else if (isExpression("紧张", "害怕", "震惊", "担忧", "惊吓", "尴尬")) {
            paint.setColor(withAlpha(Color.WHITE, 118));
            canvas.drawCircle(cx - r * 0.31f, cy - r * 0.12f, r * 0.020f, paint);
            canvas.drawCircle(cx + r * 0.46f, cy - r * 0.12f, r * 0.020f, paint);
        }
        if (state == CallState.THINKING) {
            // 思考状态给眼睛加高光，视觉上更像正在处理问题。
            paint.setColor(Color.WHITE);
            canvas.drawCircle(cx - r * 0.34f, cy - r * 0.06f, r * 0.035f, paint);
            canvas.drawCircle(cx + r * 0.42f, cy - r * 0.06f, r * 0.035f, paint);
        }
    }

    private void drawBrows(Canvas canvas, float cx, float cy, float r) {
        // 眉毛偏移用于区分疑惑/思考/安慰等表情。
        paint.setColor(Color.rgb(52, 46, 55));
        paint.setStrokeWidth(r * 0.035f);
        float offset = (isExpression("疑惑", "好奇", "思考", "尴尬", "无语") || state == CallState.THINKING) ? r * 0.06f : 0f;
        if (isExpression("安慰", "抱歉", "委屈", "难过", "害怕", "疲惫", "感动", "担忧", "惊吓")) {
            offset = r * 0.04f;
        }
        if (isExpression("生气", "严肃", "拒绝", "无语")) {
            canvas.drawLine(cx - r * 0.58f, cy - r * 0.40f, cx - r * 0.25f, cy - r * 0.30f, paint);
            canvas.drawLine(cx + r * 0.25f, cy - r * 0.30f, cx + r * 0.58f, cy - r * 0.40f, paint);
        } else if (isExpression("惊喜", "开心", "兴奋", "震惊", "赞同", "骄傲", "庆祝", "佩服", "鼓励")) {
            canvas.drawLine(cx - r * 0.58f, cy - r * 0.38f, cx - r * 0.25f, cy - r * 0.42f, paint);
            canvas.drawLine(cx + r * 0.25f, cy - r * 0.42f, cx + r * 0.58f, cy - r * 0.38f, paint);
        } else if (isExpression("抱歉", "委屈", "难过", "害怕", "疲惫", "担忧", "感动", "尴尬", "惊吓")) {
            canvas.drawLine(cx - r * 0.58f, cy - r * 0.34f, cx - r * 0.25f, cy - r * 0.30f, paint);
            canvas.drawLine(cx + r * 0.25f, cy - r * 0.30f, cx + r * 0.58f, cy - r * 0.34f, paint);
        } else {
            canvas.drawLine(cx - r * 0.58f, cy - r * 0.32f + offset, cx - r * 0.25f, cy - r * 0.36f - offset, paint);
            canvas.drawLine(cx + r * 0.25f, cy - r * 0.36f - offset, cx + r * 0.58f, cy - r * 0.32f + offset, paint);
        }
        paint.setStrokeWidth(1f);
    }

    private void drawMouth(Canvas canvas, float cx, float cy, float r) {
        if (state == CallState.INTERRUPTED || state == CallState.ERROR || state == CallState.LISTENING) {
            // 用户说话、被打断或出错时，AI 不应继续张嘴。
            mouthLevel = 0f;
        }
        float activeMouthLevel = mouthLevel;
        if (state == CallState.SPEAKING) {
            float envelope = (float) (0.5f + 0.5f * Math.sin(phase * Math.PI * 10f));
            activeMouthLevel = Math.max(activeMouthLevel, 0.18f + envelope * 0.72f);
        }
        paint.setColor(Color.rgb(127, 52, 70));
        if (activeMouthLevel <= 0.08f) {
            // 闭嘴状态画成弧线；微笑类表情会把弧线向上抬。
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(r * 0.035f);
            if (isExpression("惊喜", "兴奋", "震惊", "惊吓")) {
                canvas.drawCircle(cx, cy + r * 0.28f, r * 0.075f, paint);
            } else {
                float smile = isWarmExpression() || isExpression("得意", "喜欢", "赞同", "骄傲", "害羞",
                        "庆祝", "感动", "调皮", "佩服", "鼓励", "放松") ? 18f : 0f;
                if (isExpression("抱歉", "委屈", "难过", "疲惫", "害怕", "担忧", "尴尬", "惊吓")) {
                    smile = -10f;
                } else if (isExpression("生气", "严肃", "拒绝", "无语")) {
                    smile = -15f;
                }
                rect.set(cx - r * 0.23f, cy + r * 0.23f - smile, cx + r * 0.23f, cy + r * 0.38f + smile);
                canvas.drawArc(rect, smile < 0f ? 200f : 20f, smile < 0f ? 140f : 140f, false, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeWidth(1f);
        } else {
            // 张嘴状态同时放大宽高，用少量计算模拟说话口型，不需要逐帧图片。
            float emotionWidth = isWarmExpression() || isExpression("得意", "喜欢", "兴奋", "骄傲", "赞同",
                    "庆祝", "感动", "调皮", "佩服", "鼓励", "放松") ? 1.16f : 1f;
            float emotionHeight = isExpression("惊喜", "兴奋", "震惊", "庆祝", "惊吓") ? 1.22f : 1f;
            if (isExpression("抱歉", "委屈", "难过", "紧张", "害怕", "疲惫", "担忧", "尴尬")) {
                emotionWidth = 0.84f;
                emotionHeight = 0.82f;
            } else if (isExpression("生气", "严肃", "冷静", "拒绝", "无语")) {
                emotionWidth = 1.05f;
                emotionHeight = 0.56f;
            }
            float mouthW = r * (0.24f + activeMouthLevel * 0.24f) * emotionWidth;
            float mouthH = r * (0.08f + activeMouthLevel * 0.28f) * emotionHeight;
            float mouthY = cy + r * 0.22f;
            if (isWarmExpression() || isExpression("喜欢", "兴奋", "赞同", "骄傲", "害羞", "庆祝",
                    "感动", "调皮", "佩服", "鼓励", "放松")) {
                mouthY -= r * 0.03f;
            } else if (isExpression("抱歉", "委屈", "难过", "生气", "严肃", "害怕", "拒绝", "疲惫",
                    "担忧", "尴尬", "无语")) {
                mouthY += r * 0.03f;
            }
            rect.set(cx - mouthW, mouthY - mouthH / 2f, cx + mouthW, mouthY + mouthH);
            canvas.drawOval(rect, paint);
            paint.setColor(withAlpha(Color.WHITE, 180));
            rect.set(cx - mouthW * 0.55f, mouthY - mouthH * 0.28f, cx + mouthW * 0.55f, mouthY + mouthH * 0.02f);
            canvas.drawOval(rect, paint);
            if (isWarmExpression() || isExpression("喜欢", "得意", "庆祝", "感动", "调皮", "佩服", "鼓励")) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(r * 0.022f);
                paint.setColor(withAlpha(Color.rgb(255, 210, 220), 170));
                rect.set(cx - mouthW * 0.72f, mouthY - mouthH * 0.46f, cx + mouthW * 0.72f, mouthY + mouthH * 0.28f);
                canvas.drawArc(rect, 20f, 140f, false, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setStrokeWidth(1f);
            }
        }
    }

    private void drawEmotionMarks(Canvas canvas, float cx, float cy, float r) {
        if (isExpression("惊喜", "兴奋", "开心", "骄傲", "庆祝", "佩服", "鼓励")) {
            paint.setColor(withAlpha(Color.rgb(255, 196, 77), (int) (255 * expressionProgress)));
            drawSpark(canvas, cx + r * 0.70f, cy - r * 0.58f, r * 0.13f);
            drawSpark(canvas, cx - r * 0.74f, cy - r * 0.44f, r * 0.09f);
            if (isExpression("庆祝")) {
                drawSpark(canvas, cx, cy - r * 0.82f, r * 0.10f);
            }
            return;
        }
        if (isExpression("感动")) {
            paint.setColor(withAlpha(Color.rgb(255, 99, 132), (int) (220 * expressionProgress)));
            drawHeart(canvas, cx + r * 0.66f, cy - r * 0.52f, r * 0.12f, withAlpha(Color.rgb(255, 99, 132), 220));
            paint.setColor(withAlpha(Color.rgb(93, 169, 232), (int) (180 * expressionProgress)));
            rect.set(cx - r * 0.50f, cy + r * 0.02f, cx - r * 0.40f, cy + r * 0.20f);
            canvas.drawOval(rect, paint);
            return;
        }
        if (isExpression("抱歉", "紧张", "害怕", "尴尬", "担忧", "惊吓")) {
            paint.setColor(withAlpha(Color.rgb(72, 185, 235), (int) (255 * expressionProgress)));
            rect.set(cx + r * 0.56f, cy - r * 0.34f, cx + r * 0.70f, cy - r * 0.10f);
            canvas.drawOval(rect, paint);
            return;
        }
        if (isExpression("委屈", "难过")) {
            paint.setColor(withAlpha(Color.rgb(93, 169, 232), (int) (255 * expressionProgress)));
            rect.set(cx - r * 0.54f, cy + r * 0.04f, cx - r * 0.42f, cy + r * 0.25f);
            canvas.drawOval(rect, paint);
            rect.set(cx + r * 0.42f, cy + r * 0.04f, cx + r * 0.54f, cy + r * 0.25f);
            canvas.drawOval(rect, paint);
            return;
        }
        if (isExpression("生气")) {
            paint.setColor(withAlpha(Color.rgb(239, 68, 68), (int) (255 * expressionProgress)));
            paint.setStrokeWidth(r * 0.035f);
            canvas.drawLine(cx + r * 0.58f, cy - r * 0.62f, cx + r * 0.72f, cy - r * 0.78f, paint);
            canvas.drawLine(cx + r * 0.72f, cy - r * 0.78f, cx + r * 0.82f, cy - r * 0.60f, paint);
            paint.setStrokeWidth(1f);
            return;
        }
        if (isExpression("震惊", "惊吓")) {
            paint.setColor(withAlpha(Color.rgb(239, 68, 68), (int) (255 * expressionProgress)));
            drawExclamation(canvas, cx + r * 0.72f, cy - r * 0.52f, r);
            return;
        }
        if (isExpression("赞同")) {
            paint.setColor(withAlpha(Color.rgb(16, 185, 129), (int) (255 * expressionProgress)));
            drawCheck(canvas, cx + r * 0.68f, cy - r * 0.52f, r * 0.16f);
            return;
        }
        if (isExpression("拒绝")) {
            paint.setColor(withAlpha(Color.rgb(239, 68, 68), (int) (255 * expressionProgress)));
            drawCross(canvas, cx + r * 0.68f, cy - r * 0.52f, r * 0.14f);
            return;
        }
        if (isExpression("思考")) {
            paint.setColor(withAlpha(Color.rgb(250, 204, 21), (int) (255 * expressionProgress)));
            drawBulb(canvas, cx + r * 0.68f, cy - r * 0.52f, r * 0.16f);
            return;
        }
        if (isExpression("疲惫")) {
            drawZzz(canvas, cx + r * 0.60f, cy - r * 0.58f, r);
            return;
        }
        if (isExpression("害羞")) {
            paint.setColor(withAlpha(Color.rgb(255, 115, 160), (int) (190 * expressionProgress)));
            canvas.drawCircle(cx - r * 0.64f, cy + r * 0.18f, r * 0.055f, paint);
            canvas.drawCircle(cx - r * 0.52f, cy + r * 0.20f, r * 0.04f, paint);
            canvas.drawCircle(cx + r * 0.64f, cy + r * 0.18f, r * 0.055f, paint);
            canvas.drawCircle(cx + r * 0.52f, cy + r * 0.20f, r * 0.04f, paint);
            return;
        }
        if (isExpression("调皮")) {
            paint.setColor(withAlpha(Color.rgb(255, 99, 132), (int) (210 * expressionProgress)));
            drawHeart(canvas, cx + r * 0.66f, cy - r * 0.48f, r * 0.10f, withAlpha(Color.rgb(255, 99, 132), 210));
            paint.setColor(withAlpha(Color.rgb(255, 196, 77), (int) (230 * expressionProgress)));
            drawSpark(canvas, cx - r * 0.70f, cy - r * 0.46f, r * 0.08f);
            return;
        }
        if (isExpression("无语")) {
            paint.setColor(withAlpha(Color.rgb(100, 116, 139), (int) (230 * expressionProgress)));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(r * 0.20f);
            paint.setFakeBoldText(true);
            canvas.drawText("...", cx + r * 0.66f, cy - r * 0.46f, paint);
            paint.setFakeBoldText(false);
            paint.setTextAlign(Paint.Align.LEFT);
            return;
        }
        if (isExpression("放松")) {
            paint.setColor(withAlpha(Color.rgb(45, 212, 191), (int) (220 * expressionProgress)));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(r * 0.24f);
            paint.setFakeBoldText(true);
            canvas.drawText("♪", cx + r * 0.66f, cy - r * 0.48f, paint);
            paint.setFakeBoldText(false);
            paint.setTextAlign(Paint.Align.LEFT);
            return;
        }
        if (isExpression("疑惑", "好奇")) {
            paint.setColor(withAlpha(Color.rgb(99, 102, 241), (int) (255 * expressionProgress)));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(r * 0.28f);
            paint.setFakeBoldText(true);
            canvas.drawText("?", cx + r * 0.68f, cy - r * 0.46f, paint);
            paint.setFakeBoldText(false);
            paint.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawSpark(Canvas canvas, float cx, float cy, float size) {
        paint.setStrokeWidth(size * 0.22f);
        canvas.drawLine(cx, cy - size, cx, cy + size, paint);
        canvas.drawLine(cx - size, cy, cx + size, cy, paint);
        paint.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(cx, cy - size * 0.54f);
        path.lineTo(cx + size * 0.22f, cy);
        path.lineTo(cx, cy + size * 0.54f);
        path.lineTo(cx - size * 0.22f, cy);
        path.close();
        canvas.drawPath(path, paint);
        paint.setStrokeWidth(1f);
    }

    private void drawCheck(Canvas canvas, float cx, float cy, float size) {
        paint.setStrokeWidth(size * 0.22f);
        canvas.drawLine(cx - size, cy, cx - size * 0.25f, cy + size * 0.7f, paint);
        canvas.drawLine(cx - size * 0.25f, cy + size * 0.7f, cx + size, cy - size * 0.7f, paint);
        paint.setStrokeWidth(1f);
    }

    private void drawCross(Canvas canvas, float cx, float cy, float size) {
        paint.setStrokeWidth(size * 0.22f);
        canvas.drawLine(cx - size, cy - size, cx + size, cy + size, paint);
        canvas.drawLine(cx + size, cy - size, cx - size, cy + size, paint);
        paint.setStrokeWidth(1f);
    }

    private void drawExclamation(Canvas canvas, float cx, float cy, float r) {
        paint.setStrokeWidth(r * 0.04f);
        canvas.drawLine(cx, cy - r * 0.16f, cx, cy + r * 0.10f, paint);
        canvas.drawCircle(cx, cy + r * 0.22f, r * 0.035f, paint);
        paint.setStrokeWidth(1f);
    }

    private void drawBulb(Canvas canvas, float cx, float cy, float size) {
        canvas.drawCircle(cx, cy, size, paint);
        paint.setStrokeWidth(size * 0.18f);
        canvas.drawLine(cx - size * 0.45f, cy + size * 1.05f, cx + size * 0.45f, cy + size * 1.05f, paint);
        canvas.drawLine(cx - size * 0.28f, cy + size * 1.38f, cx + size * 0.28f, cy + size * 1.38f, paint);
        paint.setStrokeWidth(1f);
    }

    private void drawZzz(Canvas canvas, float cx, float cy, float r) {
        paint.setColor(withAlpha(Color.rgb(99, 102, 241), (int) (210 * expressionProgress)));
        paint.setFakeBoldText(true);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(r * 0.18f);
        canvas.drawText("Z", cx, cy, paint);
        paint.setTextSize(r * 0.13f);
        canvas.drawText("Z", cx + r * 0.16f, cy - r * 0.16f, paint);
        paint.setTextSize(r * 0.10f);
        canvas.drawText("Z", cx + r * 0.29f, cy - r * 0.30f, paint);
        paint.setFakeBoldText(false);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private int expressionPrimaryColor() {
        // 表情主色用于外圈、柔光和粒子，保证同一个情绪的视觉语言一致。
        if (isExpression("开心", "热情", "兴奋", "惊喜", "骄傲", "庆祝", "佩服", "鼓励")) {
            return Color.rgb(255, 183, 77);
        }
        if (isExpression("喜欢", "害羞", "感动", "调皮")) {
            return Color.rgb(255, 97, 151);
        }
        if (isExpression("赞同")) {
            return Color.rgb(16, 185, 129);
        }
        if (isExpression("生气", "拒绝", "严肃", "无语")) {
            return Color.rgb(239, 68, 68);
        }
        if (isExpression("震惊", "害怕", "紧张", "担忧", "惊吓", "尴尬")) {
            return Color.rgb(251, 146, 60);
        }
        if (isExpression("难过", "委屈", "抱歉", "安慰", "疲惫")) {
            return Color.rgb(96, 165, 250);
        }
        if (isExpression("思考", "疑惑", "好奇")) {
            return Color.rgb(129, 140, 248);
        }
        if (isExpression("放松")) {
            return Color.rgb(45, 212, 191);
        }
        if (isExpression("冷静", "专注", "认真")) {
            return Color.rgb(59, 130, 246);
        }
        return Color.rgb(112, 153, 255);
    }

    private int expressionSecondaryColor() {
        // 辅助色用于柔光边缘，让氛围不是单色圆盘。
        if (isExpression("开心", "热情", "兴奋", "惊喜", "骄傲", "庆祝", "佩服", "鼓励")) {
            return Color.rgb(255, 226, 122);
        }
        if (isExpression("喜欢", "害羞", "感动", "调皮")) {
            return Color.rgb(255, 182, 210);
        }
        if (isExpression("生气", "拒绝", "严肃", "无语")) {
            return Color.rgb(251, 113, 133);
        }
        if (isExpression("震惊", "害怕", "紧张", "担忧", "惊吓", "尴尬")) {
            return Color.rgb(253, 186, 116);
        }
        if (isExpression("难过", "委屈", "抱歉", "安慰", "疲惫")) {
            return Color.rgb(191, 219, 254);
        }
        if (isExpression("思考", "疑惑", "好奇")) {
            return Color.rgb(196, 181, 253);
        }
        if (isExpression("放松")) {
            return Color.rgb(153, 246, 228);
        }
        return Color.rgb(186, 230, 253);
    }

    private int withAlpha(int color, int alpha) {
        // 保留原色 RGB，只替换透明度，减少重复计算颜色常量。
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private boolean isWarmExpression() {
        return "开心".equals(expression)
                || "微笑".equals(expression)
                || "安慰".equals(expression)
                || "热情".equals(expression)
                || "期待".equals(expression)
                || "庆祝".equals(expression)
                || "感动".equals(expression)
                || "调皮".equals(expression)
                || "鼓励".equals(expression)
                || "放松".equals(expression);
    }

    private boolean isExpression(String... values) {
        for (String value : values) {
            if (value.equals(expression)) {
                return true;
            }
        }
        return false;
    }
}
