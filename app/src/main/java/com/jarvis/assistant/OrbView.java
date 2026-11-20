package com.jarvis.assistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** The glowing arc-reactor orb. States: 0 idle, 1 listening, 2 thinking, 3 speaking. */
public class OrbView extends View {
    public static final int IDLE = 0, LISTENING = 1, THINKING = 2, SPEAKING = 3;

    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float angle = 0f;
    private float level = 0f;
    private int state = IDLE;
    private final ValueAnimator anim;

    public OrbView(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(4f);
        ring.setColor(Color.argb(140, 63, 240, 255));
        ring.setPathEffect(new DashPathEffect(new float[]{18f, 12f}, 0f));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(6f);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(Color.rgb(63, 240, 255));
        glow.setStyle(Paint.Style.FILL);

        anim = ValueAnimator.ofFloat(0f, 360f);
        anim.setDuration(6000);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            angle = (Float) a.getAnimatedValue();
            invalidate();
        });
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); anim.start(); }
    @Override protected void onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow(); }

    public void setState(int s) {
        state = s;
        anim.setDuration(s == LISTENING ? 1500 : s == THINKING ? 900 : 6000);
        if (s != LISTENING) level = 0f;
        invalidate();
    }

    /** Mic loudness from SpeechRecognizer (roughly -2..10). */
    public void setLevel(float rms) {
        level = Math.max(0f, Math.min(1f, (rms + 2f) / 12f));
    }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f * 0.62f;

        float scale = 1f;
        if (state == LISTENING) scale = 1.05f + level * 0.12f;
        else if (state == THINKING) scale = 0.95f + 0.05f * (float) Math.sin(Math.toRadians(angle * 2));
        else if (state == SPEAKING) scale = 1.0f + 0.04f * (float) Math.sin(Math.toRadians(angle * 4));
        float rr = r * scale;

        int glowColor = state == SPEAKING ? Color.argb(90, 124, 255, 178) : Color.argb(90, 63, 240, 255);
        glow.setShader(new RadialGradient(cx, cy, rr * 1.6f,
                new int[]{glowColor, Color.TRANSPARENT}, new float[]{0.4f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, rr * 1.6f, glow);

        core.setShader(new RadialGradient(cx, cy, rr,
                new int[]{Color.rgb(233, 254, 255), Color.rgb(63, 240, 255), Color.rgb(11, 93, 122), Color.rgb(3, 26, 38)},
                new float[]{0f, 0.25f, 0.65f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, rr, core);

        cv.save();
        cv.rotate(angle / 3f, cx, cy);
        cv.drawCircle(cx, cy, r * 1.22f, ring);
        cv.restore();

        float ar = r * 1.42f;
        box.set(cx - ar, cy - ar, cx + ar, cy + ar);
        cv.drawArc(box, angle, 70f, false, arc);
        cv.drawArc(box, angle + 180f, 70f, false, arc);
    }
}
