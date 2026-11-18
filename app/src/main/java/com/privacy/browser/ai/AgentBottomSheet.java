package com.privacy.browser.ai;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.PopupMenu;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.privacy.browser.MainActivity;
import com.privacy.browser.R;
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

    private final MainActivity owner;
    private final AiFloatingButtonView button;
    private AlertDialog cleanupConfirmation;

    public AgentBottomSheet(MainActivity activity, AiFloatingButtonView button) {
        this(activity, button, AgentSessionManager.getInstance(activity));
    }

    // Instance-local wiring for the Android fixture. Production always supplies the real manager.
    AgentBottomSheet(MainActivity activity, AiFloatingButtonView button, AgentSessionManager manager) {
        Context context = activity;
        this.context = context;
        this.owner = activity;
        this.button = button;
        this.isDark = ThemeManager.isAppDarkTheme(context);
        this.configManager = new AiConfigManager(context);
        this.sessionManager = manager;

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
        dialog.setOnCancelListener(d -> closeSheet());
        dialog.setOnDismissListener(d -> { if (!isShowing()) closeSheet(); });
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
                    dismiss();
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
                            dismiss();
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
                if (!AiConfigManager.MODE_STRUCTURED.equals(configManager.getMode())) sessionManager.settingsChanged();
                configManager.setMode(AiConfigManager.MODE_STRUCTURED);
                updateModeUI();
            }
        });

        tabRaw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!AiConfigManager.MODE_RAW.equals(configManager.getMode())) sessionManager.settingsChanged();
                configManager.setMode(AiConfigManager.MODE_RAW);
                updateModeUI();
            }
        });

        btnNewChat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
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

        btnSettings.setContentDescription("Agent options");
        btnSettings.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(context, btnSettings);
            menu.getMenu().add(0, 1, 0, "Agent settings");
            menu.getMenu().add(0, 2, 1, "Review a recipe");
            menu.getMenu().add(0, 3, 2, "Recipe reminders");
            menu.getMenu().add(0, 4, 3, "Clean up old Agent data");
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) {
                    sessionManager.stopActiveSession();
                    AiSettingsDialog.show(context, () -> {
                        sessionManager.settingsChanged();
                        updateRecipient();
                    });
                }
                else if (item.getItemId() == 2) AutomationRecipeRunner.paste(owner, sessionManager, this::isShowing);
                else if (item.getItemId() == 3) AutomationRecipeRunner.reminders(owner, sessionManager, this::isShowing);
                else if (item.getItemId() == 4) confirmLegacyCleanup();
                return true;
            });
            menu.show();
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
                dismiss();
            }
        });

        btnAttachVision.setContentDescription("Image attachments unavailable");
        btnAttachVision.setOnClickListener(v -> Toast.makeText(context,
                "Image attachments are unavailable; no image will be captured or uploaded.", Toast.LENGTH_LONG).show());

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

    }

    private void confirmLegacyCleanup() {
        if (!isShowing() || owner.isFinishing() || owner.isDestroyed() || cleanupConfirmation != null) return;
        if (sessionManager.getSessionState() == AiFloatingButtonView.STATE_PROCESSING) {
            Toast.makeText(context, "Stop the current Agent request before cleaning up old data.", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog confirm = new AlertDialog.Builder(owner, ThemeManager.getDialogTheme(owner))
                .setTitle("Clean up old Agent data?")
                .setMessage("Remove old provider replay data, including any inline images and pending calls, "
                        + "unused saved action arguments, and unused legacy Agent image files. Saved chat text and "
                        + "images still used by a chat stay. This does not clear browsing data or credentials, send "
                        + "anything, or securely erase other copies of this data.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clean up", null).create();
        cleanupConfirmation = confirm;
        confirm.setOnDismissListener(d -> { if (cleanupConfirmation == confirm) cleanupConfirmation = null; });
        confirm.show();
        View clean = confirm.getButton(AlertDialog.BUTTON_POSITIVE);
        clean.setFilterTouchesWhenObscured(true);
        clean.setOnClickListener(v -> {
            if (cleanupConfirmation != confirm || !confirm.isShowing() || !isShowing()
                    || owner.isFinishing() || owner.isDestroyed()) return;
            dismissCleanupConfirmation();
            boolean queued = AgentAttachmentManager.scheduleLegacyCleanup(AgentDbHelper.getInstance(context), result -> {
                if (owner.isFinishing() || owner.isDestroyed()) return;
                String message;
                if (!result.replayCleared) message = "Couldn't confirm old Agent data was removed. Try again.";
                else message = "Old provider data cleared. Unused image files removed: " + result.files.removed
                        + (result.files.complete ? "." : ". Some old files could not be checked or removed.");
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            });
            if (!queued) Toast.makeText(context, "Agent storage is busy. Try again shortly.", Toast.LENGTH_SHORT).show();
        });
    }

    private void dismissCleanupConfirmation() {
        AlertDialog confirm = cleanupConfirmation;
        cleanupConfirmation = null;
        if (confirm != null) confirm.dismiss();
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

    private void handleSendUserPrompt() {
        String text = etPrompt.getText().toString().trim();
        if (text.isEmpty()) return;
        submitPrompt(text);
    }

    private void submitPrompt(String text) {
        etPrompt.setText("");
        sessionManager.sendUserPrompt(text, null);
    }

    public void pageChanged() { sessionManager.pageChanged(owner); }
    public void ownerPaused() {
        dismissCleanupConfirmation();
        sessionManager.pauseActivity(owner);
    }

    public void disabled() {
        sessionManager.settingsChanged();
        dismiss();
    }

    public boolean isShowing() { return dialog.isShowing(); }

    public void show() {
        sessionManager.openSheet(owner, this, button);
        refreshUiFromSession();
        updateRecipient();
        dialog.show();
    }

    private void updateRecipient() {
        etPrompt.setHint(configManager.connection() != null ? "Send a prompt to Google AI Studio" : "Set up cloud chat in Agent settings");
    }

    private void closeSheet() {
        dismissCleanupConfirmation();
        sessionManager.closeSheet(owner, this);
    }

    public void dismiss() {
        closeSheet();
        if (dialog.isShowing()) dialog.dismiss();
    }

    public void dispose() {
        dismiss();
        sessionManager.detachActivity(owner, this);
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
        dismiss();
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

    @Override public void onMessageAdded(AiChatAdapter.ChatMessage message) {
        if (!isShowing()) return;
        llEmptyState.setVisibility(View.GONE);
        rvMessages.setVisibility(View.VISIBLE);
        chatAdapter.addMessage(message);
        rvMessages.smoothScrollToPosition(chatAdapter.getItemCount() - 1);
    }

    @Override public boolean canReview() { return isShowing(); }

    @Override public void onThinkingState(boolean isThinking, String text) {
        if (!isShowing()) return;
        if (isThinking) {
            chatAdapter.showThinking(text != null ? text : "Agent is thinking…");
            rvMessages.smoothScrollToPosition(chatAdapter.getItemCount() - 1);
        } else chatAdapter.hideThinking();
    }

    @Override public void onSessionLoaded(String sessionId, String title, List<AiChatAdapter.ChatMessage> messages) {
        if (isShowing()) refreshUiFromSession();
    }

    @Override public void onSessionStateChanged(int state) { updateProcessingUI(state); }
}
