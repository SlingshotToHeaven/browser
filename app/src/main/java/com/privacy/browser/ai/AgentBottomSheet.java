package com.privacy.browser.ai;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.privacy.browser.MainActivity;
import com.privacy.browser.R;
import com.privacy.browser.TabsManager;
import com.privacy.browser.ThemeManager;

import java.util.List;

public class AgentBottomSheet implements AgentSessionManager.SessionCallback {

    private final Context context;
    private final Dialog dialog;
    private final AiConfigManager configManager;
    private final AgentSessionManager sessionManager;
    private final boolean isDark;

    private TextView tvHeaderTitle;
    private TextView tabStructured, tabRaw;
    private LinearLayout llRawPlaceholder;
    private RecyclerView rvMessages;
    private AiChatAdapter chatAdapter;
    private EditText etPrompt;
    private ImageButton btnSend, btnAttachVision, btnNewChat, btnHistory, btnSettings, btnClose, btnStop;

    private LinearLayout llEmptyState;
    private TextView tvEmptyTitle, tvEmptySubtitle;
    private TextView chipSummarize, chipExtract, chipInspect;

    private LinearLayout llPendingAttachment;
    private ImageView ivPendingAttachmentThumb;
    private TextView tvPendingAttachmentLabel;
    private ImageButton btnRemovePendingAttachment;
    private String pendingAttachmentPath = null;
    private WebView activeWebView;

    public AgentBottomSheet(Context context, WebView activeWebView, MainActivity activity, TabsManager tabsManager) {
        this.context = context;
        this.activeWebView = activeWebView;
        this.isDark = ThemeManager.isAppDarkTheme(context);
        this.configManager = new AiConfigManager(context);
        this.sessionManager = AgentSessionManager.getInstance(context);

        this.dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_agent_sheet, null);
        dialog.setContentView(view);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setWindowAnimations(R.style.Animation_AgentBottomSheet);
            window.setGravity(Gravity.BOTTOM);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }

        initViews(view);
        updateActiveContext(activeWebView, activity, tabsManager);

        // Register session callback
        sessionManager.setSessionCallback(this);
    }

    private void initViews(View view) {
        final View root = view.findViewById(R.id.ll_agent_sheet_root);
        View dragHandle = view.findViewById(R.id.v_drag_handle);
        View divider = view.findViewById(R.id.v_header_divider);
        tvHeaderTitle = view.findViewById(R.id.tv_agent_header_title);
        LinearLayout llInputContainer = view.findViewById(R.id.ll_input_area_container);
        LinearLayout llInputBar = view.findViewById(R.id.ll_input_bar_pill);
        TextView tvRawDesc = view.findViewById(R.id.tv_raw_mode_desc);

        View scrim = view.findViewById(R.id.v_scrim_dismiss);
        if (scrim != null) {
            scrim.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
        }

        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        final int defaultTopMargin = (int) (metrics.heightPixels * 0.22);

        FrameLayout.LayoutParams initialLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        initialLp.gravity = Gravity.BOTTOM;
        initialLp.topMargin = defaultTopMargin;
        root.setLayoutParams(initialLp);

        view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int availableHeight = bottom - top;
                int totalHeight = metrics.heightPixels;
                boolean keyboardOpen = (totalHeight - availableHeight) > (totalHeight * 0.15);

                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) root.getLayoutParams();
                if (keyboardOpen) {
                    int reducedMargin = (int) (metrics.heightPixels * 0.06);
                    if (lp.topMargin != reducedMargin) {
                        lp.topMargin = reducedMargin;
                        root.setLayoutParams(lp);
                    }
                } else {
                    if (lp.topMargin != defaultTopMargin) {
                        lp.topMargin = defaultTopMargin;
                        root.setLayoutParams(lp);
                    }
                }
            }
        });

        // Theme adaptive backgrounds
        root.setBackgroundResource(isDark ? R.drawable.bg_agent_sheet_surface : R.drawable.bg_agent_sheet_surface_light);
        dragHandle.setBackgroundResource(isDark ? R.drawable.bg_agent_drag_handle : R.drawable.bg_agent_drag_handle_light);
        if (llInputContainer != null) {
            llInputContainer.setBackgroundColor(isDark ? Color.parseColor("#151722") : Color.parseColor("#FFFFFF"));
        }
        if (llInputBar != null) {
            llInputBar.setBackgroundResource(isDark ? R.drawable.bg_agent_input_bar : R.drawable.bg_agent_input_bar_light);
        }
        divider.setBackgroundColor(isDark ? Color.parseColor("#242436") : Color.parseColor("#E2E8F0"));
        tvHeaderTitle.setTextColor(isDark ? Color.WHITE : Color.parseColor("#0F172A"));
        tvRawDesc.setTextColor(isDark ? Color.parseColor("#94A3B8") : Color.parseColor("#64748B"));

        // Header controls
        tabStructured = view.findViewById(R.id.tab_mode_structured);
        tabRaw = view.findViewById(R.id.tab_mode_raw);
        llRawPlaceholder = view.findViewById(R.id.ll_raw_mode_placeholder);
        btnNewChat = view.findViewById(R.id.btn_ai_sheet_new_chat);
        btnHistory = view.findViewById(R.id.btn_ai_sheet_history);
        btnSettings = view.findViewById(R.id.btn_ai_sheet_settings);
        btnStop = view.findViewById(R.id.btn_ai_sheet_stop);
        btnClose = view.findViewById(R.id.btn_ai_sheet_close);

        // Chat RecyclerView
        rvMessages = view.findViewById(R.id.rv_ai_messages);
        LinearLayoutManager llm = new LinearLayoutManager(context);
        rvMessages.setLayoutManager(llm);
        rvMessages.setItemViewCacheSize(20);
        rvMessages.setHasFixedSize(false);
        rvMessages.getRecycledViewPool().setMaxRecycledViews(AiChatAdapter.TYPE_USER, 15);
        rvMessages.getRecycledViewPool().setMaxRecycledViews(AiChatAdapter.TYPE_ASSISTANT, 15);
        rvMessages.getRecycledViewPool().setMaxRecycledViews(AiChatAdapter.TYPE_TOOL, 25);
        chatAdapter = new AiChatAdapter(isDark);
        rvMessages.setAdapter(chatAdapter);

        // Empty state
        llEmptyState = view.findViewById(R.id.ll_agent_empty_state);
        tvEmptyTitle = view.findViewById(R.id.tv_empty_state_title);
        tvEmptySubtitle = view.findViewById(R.id.tv_empty_state_subtitle);
        chipSummarize = view.findViewById(R.id.chip_suggest_summarize);
        chipExtract = view.findViewById(R.id.chip_suggest_extract);
        chipInspect = view.findViewById(R.id.chip_suggest_inspect);

        tvEmptyTitle.setTextColor(isDark ? Color.parseColor("#FFFFFF") : Color.parseColor("#0F172A"));
        tvEmptySubtitle.setTextColor(isDark ? Color.parseColor("#94A3B8") : Color.parseColor("#64748B"));

        int chipBgRes = isDark ? R.drawable.bg_agent_pill_unselected : R.drawable.bg_agent_pill_unselected_light;
        int chipTextColor = isDark ? Color.parseColor("#E2E8F0") : Color.parseColor("#1E293B");
        chipSummarize.setBackgroundResource(chipBgRes);
        chipSummarize.setTextColor(chipTextColor);
        chipExtract.setBackgroundResource(chipBgRes);
        chipExtract.setTextColor(chipTextColor);
        chipInspect.setBackgroundResource(chipBgRes);
        chipInspect.setTextColor(chipTextColor);

        setupEmptyStateChips();

        // Prompt input bar
        etPrompt = view.findViewById(R.id.et_ai_prompt);
        btnSend = view.findViewById(R.id.btn_ai_send);
        btnAttachVision = view.findViewById(R.id.btn_ai_attach_vision);

        // Pending attachment preview bar
        llPendingAttachment = view.findViewById(R.id.ll_pending_attachment_bar);
        ivPendingAttachmentThumb = view.findViewById(R.id.iv_pending_attachment_thumb);
        tvPendingAttachmentLabel = view.findViewById(R.id.tv_pending_attachment_label);
        btnRemovePendingAttachment = view.findViewById(R.id.btn_remove_pending_attachment);

        if (ivPendingAttachmentThumb != null) {
            ivPendingAttachmentThumb.setClipToOutline(true);
        }
        if (llPendingAttachment != null) {
            llPendingAttachment.setBackgroundResource(isDark ? R.drawable.bg_agent_pending_attachment : R.drawable.bg_agent_pending_attachment_light);
        }
        if (tvPendingAttachmentLabel != null) {
            tvPendingAttachmentLabel.setTextColor(isDark ? Color.parseColor("#94A3B8") : Color.parseColor("#475569"));
        }
        if (btnRemovePendingAttachment != null) {
            btnRemovePendingAttachment.setColorFilter(isDark ? Color.parseColor("#94A3B8") : Color.parseColor("#64748B"));
            btnRemovePendingAttachment.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clearPendingAttachment(true);
                }
            });
        }

        etPrompt.setTextColor(isDark ? Color.WHITE : Color.parseColor("#0F172A"));
        etPrompt.setHintTextColor(isDark ? Color.parseColor("#64748B") : Color.parseColor("#94A3B8"));

        int iconTint = isDark ? Color.parseColor("#94A3B8") : Color.parseColor("#64748B");
        btnNewChat.setColorFilter(iconTint);
        btnHistory.setColorFilter(iconTint);
        btnSettings.setColorFilter(iconTint);
        btnClose.setColorFilter(iconTint);
        btnAttachVision.setColorFilter(iconTint);

        updateModeUI();

        // Drag handle dismissal
        dragHandle.setOnTouchListener(new View.OnTouchListener() {
            private float initialY;
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        initialY = event.getRawY();
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        float diff = event.getRawY() - initialY;
                        if (diff > 0) {
                            root.setTranslationY(diff);
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        if (root.getTranslationY() > 200) {
                            dialog.dismiss();
                        }
                        root.animate().translationY(0).setDuration(200).start();
                        return true;
                }
                return false;
            }
        });

        // Mode Switching
        tabStructured.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                configManager.setMode(AiConfigManager.MODE_STRUCTURED);
                updateModeUI();
            }
        });

        tabRaw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                configManager.setMode(AiConfigManager.MODE_RAW);
                updateModeUI();
            }
        });

        btnNewChat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearPendingAttachment(true);
                sessionManager.startNewSession();
                Toast.makeText(context, "New conversation started", Toast.LENGTH_SHORT).show();
            }
        });

        btnHistory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AgentHistoryDialog historyDialog = new AgentHistoryDialog(context, new AgentHistoryDialog.OnSessionSelectedListener() {
                    @Override
                    public void onSessionSelected(String sessionId) {
                        sessionManager.loadSession(sessionId);
                    }

                    @Override
                    public void onNewChatRequested() {
                        sessionManager.startNewSession();
                        Toast.makeText(context, "New conversation started", Toast.LENGTH_SHORT).show();
                    }
                });
                historyDialog.show();
            }
        });

        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AiSettingsDialog.show(context, null);
            }
        });

        if (btnStop != null) {
            btnStop.setColorFilter(Color.parseColor("#EF4444"));
            btnStop.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    stopAndExitAgent();
                }
            });
        }

        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        btnAttachVision.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (activeWebView == null) {
                    Toast.makeText(context, "No active web page to capture", Toast.LENGTH_SHORT).show();
                    return;
                }
                VisionToolExecutor.captureViewportBase64(activeWebView, new VisionToolExecutor.VisionCallback() {
                    @Override
                    public void onCaptured(String base64Image) {
                        // Discard any existing unsent attachment
                        if (pendingAttachmentPath != null) {
                            AgentAttachmentManager.deleteAttachment(pendingAttachmentPath);
                        }
                        String savedPath = AgentAttachmentManager.saveBase64Webp(context, base64Image);
                        if (savedPath != null) {
                            pendingAttachmentPath = savedPath;
                            Bitmap thumb = AgentAttachmentManager.loadSampledBitmap(savedPath, 96, 96);
                            if (thumb != null && ivPendingAttachmentThumb != null) {
                                ivPendingAttachmentThumb.setImageBitmap(thumb);
                            }
                            if (llPendingAttachment != null) {
                                llPendingAttachment.setVisibility(View.VISIBLE);
                            }
                            btnAttachVision.setColorFilter(Color.parseColor("#4A88F7"));
                            Toast.makeText(context, "Screenshot captured & attached", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(context, "Failed to save screenshot", Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onError(String error) {
                        Toast.makeText(context, "Vision error: " + error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sessionManager.getSessionState() == AiFloatingButtonView.STATE_PROCESSING) {
                    stopAndExitAgent();
                } else {
                    handleSendUserPrompt();
                }
            }
        });

        chatAdapter.setOnConfirmationDecisionListener(new AiChatAdapter.OnConfirmationDecisionListener() {
            @Override
            public void onConfirmed(AiChatAdapter.ChatMessage message) {
                sessionManager.executeApprovedTool(message.toolName, message.toolArgs);
            }

            @Override
            public void onCancelled(AiChatAdapter.ChatMessage message) {
                sessionManager.cancelPendingTool(message.toolName);
            }
        });
    }

    private void setupEmptyStateChips() {
        chipSummarize.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submitPrompt("Please summarize the main content and key takeaways of this page.");
            }
        });

        chipExtract.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submitPrompt("Extract the main headings, links, and important text on this page.");
            }
        });

        chipInspect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submitPrompt("Inspect the interactive elements and inputs available on this page.");
            }
        });
    }

    private void updateModeUI() {
        boolean isStructured = AiConfigManager.MODE_STRUCTURED.equals(configManager.getMode());
        if (isStructured) {
            tabStructured.setBackgroundResource(R.drawable.bg_agent_pill_selected);
            tabStructured.setTextColor(Color.WHITE);
            tabRaw.setBackgroundResource(isDark ? R.drawable.bg_agent_pill_unselected : R.drawable.bg_agent_pill_unselected_light);
            tabRaw.setTextColor(isDark ? Color.parseColor("#94A3B8") : Color.parseColor("#64748B"));
            llRawPlaceholder.setVisibility(View.GONE);
        } else {
            tabRaw.setBackgroundResource(R.drawable.bg_agent_pill_selected);
            tabRaw.setTextColor(Color.WHITE);
            tabStructured.setBackgroundResource(isDark ? R.drawable.bg_agent_pill_unselected : R.drawable.bg_agent_pill_unselected_light);
            tabStructured.setTextColor(isDark ? Color.parseColor("#94A3B8") : Color.parseColor("#64748B"));
            llRawPlaceholder.setVisibility(View.VISIBLE);
        }
    }

    private void clearPendingAttachment(boolean deleteFile) {
        if (deleteFile && pendingAttachmentPath != null) {
            AgentAttachmentManager.deleteAttachment(pendingAttachmentPath);
        }
        pendingAttachmentPath = null;
        if (llPendingAttachment != null) {
            llPendingAttachment.setVisibility(View.GONE);
        }
        int iconTint = isDark ? Color.parseColor("#94A3B8") : Color.parseColor("#64748B");
        if (btnAttachVision != null) {
            btnAttachVision.setColorFilter(iconTint);
        }
    }

    private void handleSendUserPrompt() {
        String text = etPrompt.getText().toString().trim();
        if (text.isEmpty() && pendingAttachmentPath == null) return;
        submitPrompt(text);
    }

    private void submitPrompt(String text) {
        etPrompt.setText("");
        String imagePath = pendingAttachmentPath;
        clearPendingAttachment(false); // Retain file on disk because it is being sent with the message

        sessionManager.sendUserPrompt(text, imagePath);
    }

    public void updateActiveContext(WebView webView, MainActivity activity, TabsManager tabsManager) {
        this.activeWebView = webView;
        sessionManager.updateActiveContext(webView, activity, tabsManager);
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    public void show() {
        sessionManager.setSessionCallback(this);
        refreshUiFromSession();
        dialog.show();
    }

    public void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private void refreshUiFromSession() {
        List<AiChatAdapter.ChatMessage> messages = sessionManager.getActiveMessages();
        chatAdapter.setMessages(messages);

        if (messages.isEmpty()) {
            llEmptyState.setVisibility(View.VISIBLE);
            rvMessages.setVisibility(View.GONE);
        } else {
            llEmptyState.setVisibility(View.GONE);
            rvMessages.setVisibility(View.VISIBLE);
            rvMessages.scrollToPosition(messages.size() - 1);
        }

        tvHeaderTitle.setText("Agent");
        updateProcessingUI(sessionManager.getSessionState());
    }

    private void stopAndExitAgent() {
        sessionManager.stopActiveSession();
        Toast.makeText(context, "Agent stopped", Toast.LENGTH_SHORT).show();
        dialog.dismiss();
    }

    private void updateProcessingUI(int state) {
        boolean isProcessing = (state == AiFloatingButtonView.STATE_PROCESSING);
        if (btnStop != null) {
            btnStop.setVisibility(isProcessing ? View.VISIBLE : View.GONE);
        }
        if (btnSend != null) {
            if (isProcessing) {
                btnSend.setImageResource(R.drawable.ic_stop);
                btnSend.setBackgroundResource(R.drawable.bg_agent_stop_button);
                btnSend.setColorFilter(Color.WHITE);
                btnSend.setContentDescription("Stop Agent");
            } else {
                btnSend.setImageResource(R.drawable.ic_arrow_forward);
                btnSend.setBackgroundResource(R.drawable.bg_agent_pill_selected);
                btnSend.setColorFilter(Color.WHITE);
                btnSend.setContentDescription("Send Prompt");
            }
        }
    }

    // -------------------------------------------------------------------------
    // AgentSessionManager.SessionCallback Implementation
    // -------------------------------------------------------------------------

    @Override
    public void onMessageAdded(final AiChatAdapter.ChatMessage message) {
        rvMessages.post(new Runnable() {
            @Override
            public void run() {
                llEmptyState.setVisibility(View.GONE);
                rvMessages.setVisibility(View.VISIBLE);
                chatAdapter.addMessage(message);
                rvMessages.smoothScrollToPosition(chatAdapter.getItemCount() - 1);
            }
        });
    }

    @Override
    public void onToolStatusUpdated(final String text, final boolean inProgress) {
        rvMessages.post(new Runnable() {
            @Override
            public void run() {
                chatAdapter.updateLastToolStatus(text, inProgress);
            }
        });
    }

    @Override
    public void onThinkingState(final boolean isThinking, final String text) {
        rvMessages.post(new Runnable() {
            @Override
            public void run() {
                if (isThinking) {
                    chatAdapter.showThinking(text != null ? text : "Agent is thinking...");
                    rvMessages.smoothScrollToPosition(chatAdapter.getItemCount() - 1);
                } else {
                    chatAdapter.hideThinking();
                }
            }
        });
    }

    @Override
    public void onSessionLoaded(final String sessionId, final String title, final List<AiChatAdapter.ChatMessage> messages) {
        rvMessages.post(new Runnable() {
            @Override
            public void run() {
                chatAdapter.setMessages(messages);
                if (messages.isEmpty()) {
                    llEmptyState.setVisibility(View.VISIBLE);
                    rvMessages.setVisibility(View.GONE);
                } else {
                    llEmptyState.setVisibility(View.GONE);
                    rvMessages.setVisibility(View.VISIBLE);
                    rvMessages.scrollToPosition(messages.size() - 1);
                }
                tvHeaderTitle.setText("Agent");
            }
        });
    }

    @Override
    public void onSessionStateChanged(final int state) {
        if (rvMessages != null) {
            rvMessages.post(new Runnable() {
                @Override
                public void run() {
                    updateProcessingUI(state);
                }
            });
        }
    }
}
