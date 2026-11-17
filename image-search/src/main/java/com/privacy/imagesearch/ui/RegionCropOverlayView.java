package com.privacy.imagesearch.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Pure Java interactive bounding box crop view.
 * Overlays on top of the web content or screenshot bitmap, allowing the user
 * to drag-to-select an exact sub-region of the screen or webpage for reverse search.
 */
public class RegionCropOverlayView extends FrameLayout {

    public interface CropCallback {
        void onAreaCropped(@NonNull Bitmap croppedBitmap);
        void onCancelled();
    }

    private Bitmap sourceBitmap;
    private CropCallback callback;

    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF cropRect = new RectF();
    private float startX, startY;
    private boolean isSelecting = false;

    private LinearLayout actionContainer;
    private TextView btnSearch;

    public RegionCropOverlayView(@NonNull Context context) {
        super(context);
        init();
    }

    public RegionCropOverlayView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);

        scrimPaint.setColor(Color.parseColor("#99000000")); // 60% dim backdrop

        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        borderPaint.setColor(Color.parseColor("#89B4FA")); // Accent blue
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(4f);

        cornerPaint.setColor(Color.WHITE);
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(8f);

        buildActionButtons();
    }

    private void buildActionButtons() {
        actionContainer = new LinearLayout(getContext());
        actionContainer.setOrientation(LinearLayout.HORIZONTAL);
        actionContainer.setGravity(Gravity.CENTER);
        actionContainer.setPadding(32, 16, 32, 16);
        actionContainer.setBackgroundColor(Color.parseColor("#EE1E1E2E"));
        actionContainer.setVisibility(View.GONE);

        TextView btnCancel = new TextView(getContext());
        btnCancel.setText("Cancel");
        btnCancel.setTextColor(Color.parseColor("#CDD6F4"));
        btnCancel.setTextSize(14f);
        btnCancel.setPadding(24, 16, 24, 16);
        btnCancel.setOnClickListener(v -> {
            if (callback != null) callback.onCancelled();
            removeFromParent();
        });

        btnSearch = new TextView(getContext());
        btnSearch.setText("Search Region");
        btnSearch.setTextColor(Color.WHITE);
        btnSearch.setTextSize(14f);
        btnSearch.setPadding(32, 16, 32, 16);
        btnSearch.setBackgroundColor(Color.parseColor("#3B82F6"));
        btnSearch.setOnClickListener(v -> performCrop());

        actionContainer.addView(btnCancel);
        actionContainer.addView(btnSearch);

        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = 80;
        addView(actionContainer, lp);
    }

    public void setup(@NonNull Bitmap source, @NonNull CropCallback cb) {
        this.sourceBitmap = source;
        this.callback = cb;
        // Default box in center: 60% width & height
        post(() -> {
            int w = getWidth();
            int h = getHeight();
            if (w > 0 && h > 0) {
                float left = w * 0.15f;
                float top = h * 0.25f;
                float right = w * 0.85f;
                float bottom = h * 0.65f;
                cropRect.set(left, top, right, bottom);
                actionContainer.setVisibility(View.VISIBLE);
                invalidate();
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = x;
                startY = y;
                isSelecting = true;
                cropRect.set(x, y, x, y);
                actionContainer.setVisibility(View.GONE);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (isSelecting) {
                    cropRect.left = Math.min(startX, x);
                    cropRect.right = Math.max(startX, x);
                    cropRect.top = Math.min(startY, y);
                    cropRect.bottom = Math.max(startY, y);
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isSelecting = false;
                if (cropRect.width() > 40 && cropRect.height() > 40) {
                    actionContainer.setVisibility(View.VISIBLE);
                } else {
                    // Tap with no drag: reset or cancel
                    actionContainer.setVisibility(View.VISIBLE);
                }
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (sourceBitmap != null) {
            canvas.drawBitmap(sourceBitmap, 0, 0, null);
        }

        // Draw dim scrim layer with cleared crop hole
        int saveCount = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
        canvas.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);

        if (cropRect.width() > 0 && cropRect.height() > 0) {
            canvas.drawRect(cropRect, clearPaint);
            canvas.restoreToCount(saveCount);

            // Draw bounding border
            canvas.drawRect(cropRect, borderPaint);

            // Draw corner accent marks
            float cornerLen = Math.min(30f, Math.min(cropRect.width() / 4, cropRect.height() / 4));
            // Top-left
            canvas.drawLine(cropRect.left, cropRect.top, cropRect.left + cornerLen, cropRect.top, cornerPaint);
            canvas.drawLine(cropRect.left, cropRect.top, cropRect.left, cropRect.top + cornerLen, cornerPaint);
            // Top-right
            canvas.drawLine(cropRect.right, cropRect.top, cropRect.right - cornerLen, cropRect.top, cornerPaint);
            canvas.drawLine(cropRect.right, cropRect.top, cropRect.right, cropRect.top + cornerLen, cornerPaint);
            // Bottom-left
            canvas.drawLine(cropRect.left, cropRect.bottom, cropRect.left + cornerLen, cropRect.bottom, cornerPaint);
            canvas.drawLine(cropRect.left, cropRect.bottom, cropRect.left, cropRect.bottom - cornerLen, cornerPaint);
            // Bottom-right
            canvas.drawLine(cropRect.right, cropRect.bottom, cropRect.right - cornerLen, cropRect.bottom, cornerPaint);
            canvas.drawLine(cropRect.right, cropRect.bottom, cropRect.right, cropRect.bottom - cornerLen, cornerPaint);
        } else {
            canvas.restoreToCount(saveCount);
        }
    }

    private void performCrop() {
        if (sourceBitmap == null || cropRect.width() < 10 || cropRect.height() < 10) {
            if (callback != null) callback.onCancelled();
            removeFromParent();
            return;
        }

        int x = Math.max(0, (int) cropRect.left);
        int y = Math.max(0, (int) cropRect.top);
        int w = Math.min(sourceBitmap.getWidth() - x, (int) cropRect.width());
        int h = Math.min(sourceBitmap.getHeight() - y, (int) cropRect.height());

        if (w > 0 && h > 0) {
            Bitmap cropped = Bitmap.createBitmap(sourceBitmap, x, y, w, h);
            if (callback != null) {
                callback.onAreaCropped(cropped);
            }
        } else {
            if (callback != null) callback.onCancelled();
        }
        removeFromParent();
    }

    public void removeFromParent() {
        if (getParent() instanceof ViewGroup) {
            ((ViewGroup) getParent()).removeView(this);
        }
        if (sourceBitmap != null && !sourceBitmap.isRecycled()) {
            sourceBitmap.recycle();
            sourceBitmap = null;
        }
    }
}
