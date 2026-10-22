package com.dshstatus.hud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * 环形连续滚动文本：单向、首尾相接、无接缝跳变。
 * 每帧计算 offset = (elapsed * speed) % 单份宽度，从左到右画多份文本铺满。
 */
public class RingTextView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String text = "";
    private float unitWidth = 0f;      // 单份文本宽度（含尾部间隔）
    private float speedPxPerSec = 0f;
    private boolean scrolling = false;
    private boolean centered = false;
    private long startTime = 0L;
    private float gapPx;
    private float padLeftPx;

    public RingTextView(Context c) {
        super(c);
        init();
    }

    public RingTextView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        paint.setTypeface(Typeface.DEFAULT);
        paint.setSubpixelText(true);
        paint.setAntiAlias(true);
        paint.setTextSize(13f * getResources().getDisplayMetrics().scaledDensity);
        gapPx = 28f * getResources().getDisplayMetrics().density;
        padLeftPx = 14f * getResources().getDisplayMetrics().density;
    }

    public void setContentCentered(String content, int color) {
        setContent(content, color, false, 0f);
        this.centered = true;
        invalidate();
    }

    public void setContent(String content, int color, boolean scroll, float speedDpPerSec) {
        this.text = content == null ? "" : content;
        paint.setColor(color);
        unitWidth = paint.measureText(this.text) + gapPx;
        this.scrolling = scroll;
        this.speedPxPerSec = speedDpPerSec * getResources().getDisplayMetrics().density;
        this.startTime = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (text.isEmpty() || unitWidth <= 0f) return;
        float h = getHeight();
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = h / 2f - (fm.ascent + fm.descent) / 2f;

        // 用户水平微调：动态读取 View padding，叠加在基础内边距上
        float baseX = padLeftPx + getPaddingLeft();
        if (!scrolling) {
            float x = centered ? Math.max(baseX, (getWidth() - unitWidth) / 2f) : baseX;
            canvas.drawText(text, x, baseline, paint);
            return;
        }

        long elapsed = SystemClock.uptimeMillis() - startTime;
        float offset = ((elapsed / 1000f) * speedPxPerSec) % unitWidth;
        for (float x = -offset; x < getWidth() + unitWidth; x += unitWidth) {
            canvas.drawText(text, x + baseX, baseline, paint);
        }
        postInvalidateOnAnimation();
    }
}
