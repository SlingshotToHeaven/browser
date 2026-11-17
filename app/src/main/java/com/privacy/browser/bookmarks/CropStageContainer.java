package com.privacy.browser.bookmarks;

import android.content.Context;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Custom FrameLayout that intercepts vertical drag gestures when initiated on the crop field,
 * forwarding other touches to the scaled WebView.
 */
public class CropStageContainer extends FrameLayout {

    private CropOverlayView cropOverlayView;
    private View webViewWrapper;
    private WebView webView;
    private float previewScale = 1.0f;

    private float lastTouchY = 0f;
    private boolean isDraggingCrop = false;

    public CropStageContainer(@NonNull Context context) {
        super(context);
    }

    public CropStageContainer(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public CropStageContainer(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void bindViews(CropOverlayView cropOverlayView, View webViewWrapper, WebView webView) {
        this.cropOverlayView = cropOverlayView;
        this.webViewWrapper = webViewWrapper;
        this.webView = webView;
    }

    public void setPreviewScale(float scale) {
        this.previewScale = scale;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (cropOverlayView == null) return super.onInterceptTouchEvent(ev);

        int action = ev.getActionMasked();
        float x = ev.getX();
        float y = ev.getY();

        if (action == MotionEvent.ACTION_DOWN) {
            lastTouchY = y;
            isDraggingCrop = cropOverlayView.isTouchInCropField(x, y);
            if (isDraggingCrop) {
                // Intercept so child views don't steal the gesture
                return true;
            }
        }
        return isDraggingCrop || super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (cropOverlayView == null) return super.onTouchEvent(event);

        float x = event.getX();
        float y = event.getY();
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            lastTouchY = y;
            isDraggingCrop = cropOverlayView.isTouchInCropField(x, y);
            if (isDraggingCrop) {
                return true;
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (isDraggingCrop) {
                float deltaY = y - lastTouchY;
                lastTouchY = y;
                cropOverlayView.setCropY(cropOverlayView.getCropY() + deltaY);
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (isDraggingCrop) {
                isDraggingCrop = false;
                return true;
            }
        }

        // Forward touches outside crop field to scaled WebView
        if (webView != null && previewScale > 0) {
            int stageWidth = getWidth();
            float scaledWidth = stageWidth * previewScale;
            float leftOffset = (stageWidth - scaledWidth) / 2.0f;

            float transformedX = (x - leftOffset) / previewScale;
            float transformedY = y / previewScale;

            MotionEvent transformed = MotionEvent.obtain(
                    event.getDownTime(),
                    event.getEventTime(),
                    event.getAction(),
                    transformedX,
                    transformedY,
                    event.getMetaState()
            );

            boolean handled = webView.dispatchTouchEvent(transformed);
            transformed.recycle();
            return handled;
        }

        return super.onTouchEvent(event);
    }
}
