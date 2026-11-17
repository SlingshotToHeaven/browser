package com.privacy.browser.ai;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.privacy.browser.R;

public class AiFloatingButtonView extends FrameLayout {

    public static final int STATE_IDLE = 0;
    public static final int STATE_ACTIVE = 1;
    public static final int STATE_PROCESSING = 2;

    public interface OnAiButtonClickListener {
        void onAiButtonClick();
    }

    private ImageView floatingIcon;
    private View vActiveBadge;
    private View vProcessingRing;
    private OnAiButtonClickListener clickListener;
    private ObjectAnimator pulseAnimator;
    private ObjectAnimator ringAnimator;
    private int currentState = STATE_IDLE;

    private float dX, dY;
    private float startX, startY;
    private static final int CLICK_THRESHOLD = 15; // pixels

    public AiFloatingButtonView(Context context) {
        super(context);
        init(context);
    }

    public AiFloatingButtonView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public AiFloatingButtonView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.view_ai_floating_button, this, true);
        floatingIcon = findViewById(R.id.iv_ai_floating_icon);
        vActiveBadge = findViewById(R.id.v_ai_active_badge);
        vProcessingRing = findViewById(R.id.v_ai_processing_ring);

        startPulse();
        setupDrag();
    }

    public void setOnAiButtonClickListener(OnAiButtonClickListener listener) {
        this.clickListener = listener;
    }

    public void setSessionState(final int state) {
        this.currentState = state;
        post(new Runnable() {
            @Override
            public void run() {
                applySessionState(state);
            }
        });
    }

    public int getSessionState() {
        return currentState;
    }

    private void applySessionState(int state) {
        if (vActiveBadge == null || vProcessingRing == null) return;

        switch (state) {
            case STATE_PROCESSING:
                vActiveBadge.setVisibility(View.VISIBLE);
                vActiveBadge.setBackgroundResource(R.drawable.bg_agent_status_dot_processing);
                vProcessingRing.setVisibility(View.VISIBLE);
                startRingPulse();
                break;

            case STATE_ACTIVE:
                vActiveBadge.setVisibility(View.VISIBLE);
                vActiveBadge.setBackgroundResource(R.drawable.bg_agent_status_dot_active);
                vProcessingRing.setVisibility(View.GONE);
                stopRingPulse();
                break;

            case STATE_IDLE:
            default:
                vActiveBadge.setVisibility(View.GONE);
                vProcessingRing.setVisibility(View.GONE);
                stopRingPulse();
                break;
        }
    }

    private void startPulse() {
        if (floatingIcon == null) return;
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
                floatingIcon,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.08f, 1.0f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.08f, 1.0f)
        );
        pulseAnimator.setDuration(2400);
        pulseAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        pulseAnimator.start();
    }

    private void startRingPulse() {
        if (ringAnimator != null && ringAnimator.isRunning()) return;
        if (vProcessingRing == null) return;

        ringAnimator = ObjectAnimator.ofPropertyValuesHolder(
                vProcessingRing,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.95f, 1.15f, 0.95f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.95f, 1.15f, 0.95f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.4f, 1.0f, 0.4f)
        );
        ringAnimator.setDuration(1200);
        ringAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        ringAnimator.start();
    }

    private void stopRingPulse() {
        if (ringAnimator != null) {
            ringAnimator.cancel();
            ringAnimator = null;
        }
        if (vProcessingRing != null) {
            vProcessingRing.setScaleX(1.0f);
            vProcessingRing.setScaleY(1.0f);
            vProcessingRing.setAlpha(1.0f);
        }
    }

    private void setupDrag() {
        setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                View root = (View) getParent();

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getRawX();
                        startY = event.getRawY();
                        dX = getTranslationX() - startX;
                        dY = getTranslationY() - startY;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float targetTransX = event.getRawX() + dX;
                        float targetTransY = event.getRawY() + dY;

                        if (root != null) {
                            int rootWidth = root.getWidth();
                            int rootHeight = root.getHeight();

                            float originalLeft = getLeft();
                            float originalTop = getTop();
                            float targetW = getWidth();
                            float targetH = getHeight();

                            float minTransX = -originalLeft;
                            float maxTransX = rootWidth - originalLeft - targetW;
                            float minTransY = -originalTop;
                            float maxTransY = rootHeight - originalTop - targetH;

                            targetTransX = Math.max(minTransX, Math.min(targetTransX, maxTransX));
                            targetTransY = Math.max(minTransY, Math.min(targetTransY, maxTransY));
                        }

                        setTranslationX(targetTransX);
                        setTranslationY(targetTransY);
                        return true;

                    case MotionEvent.ACTION_UP:
                        float diffX = Math.abs(event.getRawX() - startX);
                        float diffY = Math.abs(event.getRawY() - startY);
                        if (diffX < CLICK_THRESHOLD && diffY < CLICK_THRESHOLD) {
                            if (clickListener != null) {
                                clickListener.onAiButtonClick();
                            }
                        }
                        return true;

                    default:
                        return false;
                }
            }
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }
        stopRingPulse();
    }
}
