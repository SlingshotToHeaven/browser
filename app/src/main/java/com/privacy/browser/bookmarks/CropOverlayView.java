package com.privacy.browser.bookmarks;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Dimmed overlay that highlights an exact square crop frame in the center.
 * Touches pass through to the underlying view.
 */
public class CropOverlayView extends View {

    private final Paint dimPaint = new Paint();
    private final Paint clearPaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cropRect = new RectF();

    public CropOverlayView(Context context) {
        super(context);
        init();
    }

    public CropOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CropOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        // Semi-transparent pitch-black dim for outer area
        dimPaint.setColor(Color.parseColor("#B8000000"));

        // Clear paint for the hollow square
        clearPaint.setColor(Color.TRANSPARENT);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        // Thin cyan border bounding the crop square
        borderPaint.setColor(Color.parseColor("#8000E5FF"));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);

        // Vibrant cyan corner accents
        cornerPaint.setColor(Color.parseColor("#00E5FF"));
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(4f * getResources().getDisplayMetrics().density);
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    private float customSquareSize = -1f;
    private float cropY = 0f;
    private boolean isYInitialized = false;

    public void setCustomSquareSize(float sizePx) {
        this.customSquareSize = sizePx;
        calculateCropRect(getWidth(), getHeight());
        invalidate();
    }

    public void setCropY(float y) {
        this.cropY = y;
        this.isYInitialized = true;
        calculateCropRect(getWidth(), getHeight());
        invalidate();
    }

    public float getCropY() {
        return cropY;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        calculateCropRect(w, h);
    }

    private void calculateCropRect(int w, int h) {
        if (w <= 0 || h <= 0) return;

        float size;
        if (customSquareSize > 0) {
            size = Math.min(customSquareSize, Math.min(w, h));
        } else {
            float density = getResources().getDisplayMetrics().density;
            float totalSpacing = 24f * density;
            size = (w - totalSpacing) / 2.0f;
        }

        float left = (w - size) / 2.0f;

        // Position at top in the start (0), or clamped to [0, h - size] if user moved it
        float maxTop = Math.max(0, h - size);
        if (!isYInitialized) {
            cropY = 0f; // Start at the absolute top
            isYInitialized = true;
        } else {
            cropY = Math.max(0, Math.min(cropY, maxTop));
        }

        cropRect.set(left, cropY, left + size, cropY + size);
    }

    public boolean isTouchInCropField(float x, float y) {
        float touchMargin = 32f * getResources().getDisplayMetrics().density;
        RectF expanded = new RectF(
                cropRect.left - touchMargin,
                cropRect.top - touchMargin,
                cropRect.right + touchMargin,
                cropRect.bottom + touchMargin
        );
        return expanded.contains(x, y);
    }

    public RectF getCropRect() {
        return cropRect;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (cropRect.isEmpty()) {
            calculateCropRect(getWidth(), getHeight());
        }

        // 1. Draw outer dim
        canvas.drawRect(0, 0, getWidth(), getHeight(), dimPaint);

        // 2. Punch clear square hole
        canvas.drawRect(cropRect, clearPaint);

        // 3. Draw border around square
        canvas.drawRect(cropRect, borderPaint);

        // 4. Draw corner reticles
        float cornerLen = 20f * getResources().getDisplayMetrics().density;
        float l = cropRect.left;
        float t = cropRect.top;
        float r = cropRect.right;
        float b = cropRect.bottom;

        // Top-Left
        canvas.drawLine(l, t, l + cornerLen, t, cornerPaint);
        canvas.drawLine(l, t, l, t + cornerLen, cornerPaint);

        // Top-Right
        canvas.drawLine(r, t, r - cornerLen, t, cornerPaint);
        canvas.drawLine(r, t, r, t + cornerLen, cornerPaint);

        // Bottom-Left
        canvas.drawLine(l, b, l + cornerLen, b, cornerPaint);
        canvas.drawLine(l, b, l, b - cornerLen, cornerPaint);

        // Bottom-Right
        canvas.drawLine(r, b, r - cornerLen, b, cornerPaint);
        canvas.drawLine(r, b, r, b - cornerLen, cornerPaint);
    }
}
