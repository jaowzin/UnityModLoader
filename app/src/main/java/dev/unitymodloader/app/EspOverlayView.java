package dev.unitymodloader.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Locale;

/**
 * Transparent through-wall ESP for the authorized Fire Zone CTF target.
 * Native code returns hostile AI screen coordinates; this view only renders them.
 */
public final class EspOverlayView extends View {
    private static final long FRAME_DELAY_MS = 40L;

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float[] targets = new float[0];

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!isAttachedToWindow()) return;
            try {
                float[] latest = NativeBridge.getFireZoneEspTargets();
                if (latest != null) targets = latest;
            } catch (Throwable ignored) {
                targets = new float[0];
            }
            invalidate();
            postDelayed(this, FRAME_DELAY_MS);
        }
    };

    public EspOverlayView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setBackgroundColor(Color.TRANSPARENT);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(dp(8));
        glowPaint.setColor(Color.argb(52, 54, 232, 255));

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2));
        linePaint.setColor(Color.argb(235, 54, 232, 255));

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.argb(22, 54, 232, 255));

        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(10));
        textPaint.setFakeBoldText(true);

        badgePaint.setStyle(Paint.Style.FILL);
        badgePaint.setColor(Color.argb(188, 4, 20, 28));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(refresh);
        post(refresh);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(refresh);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float[] data = targets;
        if (data == null || data.length < 7) return;

        float unityWidth = data[0];
        float unityHeight = data[1];
        if (unityWidth <= 1f || unityHeight <= 1f || getWidth() <= 0 || getHeight() <= 0) return;

        float sx = getWidth() / unityWidth;
        float sy = getHeight() / unityHeight;

        for (int i = 2; i + 4 < data.length; i += 5) {
            float x = data[i] * sx;
            float feetY = getHeight() - data[i + 1] * sy;
            float headY = getHeight() - data[i + 2] * sy;
            float depth = data[i + 3];
            float hp = data[i + 4];

            if (depth <= 0f) continue;

            float top = Math.min(headY, feetY);
            float bottom = Math.max(headY, feetY);
            float height = bottom - top;
            if (height < dp(18) || height > getHeight() * 1.6f) continue;

            float width = Math.max(dp(20), height * 0.40f);
            float left = x - width * 0.5f;
            float right = x + width * 0.5f;
            RectF box = new RectF(left, top, right, bottom);

            canvas.drawRoundRect(box, dp(8), dp(8), fillPaint);
            canvas.drawRoundRect(box, dp(8), dp(8), glowPaint);
            canvas.drawRoundRect(box, dp(8), dp(8), linePaint);

            drawSkeleton(canvas, x, top, bottom, width);
            drawLabel(canvas, left, top, hp, depth);
        }
    }

    private void drawSkeleton(Canvas canvas, float x, float top, float bottom, float width) {
        float h = bottom - top;
        float headRadius = Math.max(dp(4), h * 0.065f);
        float headY = top + h * 0.10f;
        float neckY = top + h * 0.20f;
        float shoulderY = top + h * 0.29f;
        float hipY = top + h * 0.58f;
        float kneeY = top + h * 0.79f;
        float shoulderHalf = width * 0.30f;
        float hipHalf = width * 0.16f;

        canvas.drawCircle(x, headY, headRadius + dp(3), glowPaint);
        canvas.drawCircle(x, headY, headRadius, linePaint);

        drawGlowLine(canvas, x, headY + headRadius, x, neckY);
        drawGlowLine(canvas, x - shoulderHalf, shoulderY, x + shoulderHalf, shoulderY);
        drawGlowLine(canvas, x, neckY, x, hipY);

        drawGlowLine(canvas, x - shoulderHalf, shoulderY, x - width * 0.38f, top + h * 0.48f);
        drawGlowLine(canvas, x + shoulderHalf, shoulderY, x + width * 0.38f, top + h * 0.48f);

        drawGlowLine(canvas, x - hipHalf, hipY, x - width * 0.22f, kneeY);
        drawGlowLine(canvas, x + hipHalf, hipY, x + width * 0.22f, kneeY);
        drawGlowLine(canvas, x - width * 0.22f, kneeY, x - width * 0.25f, bottom);
        drawGlowLine(canvas, x + width * 0.22f, kneeY, x + width * 0.25f, bottom);
    }

    private void drawGlowLine(Canvas canvas, float x1, float y1, float x2, float y2) {
        canvas.drawLine(x1, y1, x2, y2, glowPaint);
        canvas.drawLine(x1, y1, x2, y2, linePaint);
    }

    private void drawLabel(Canvas canvas, float left, float top, float hp, float depth) {
        String label = String.format(Locale.ROOT, "ENEMY  HP %.0f  %.0fm", hp, depth);
        float paddingX = dp(6);
        float badgeHeight = dp(20);
        float textWidth = textPaint.measureText(label);
        float badgeWidth = textWidth + paddingX * 2f;
        float badgeBottom = Math.max(badgeHeight, top - dp(5));
        float badgeTop = badgeBottom - badgeHeight;

        RectF badge = new RectF(left, badgeTop, left + badgeWidth, badgeBottom);
        canvas.drawRoundRect(badge, dp(6), dp(6), badgePaint);
        canvas.drawText(label, left + paddingX, badgeTop + dp(14), textPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
