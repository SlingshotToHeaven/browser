package com.privacy.browser.ai;

import android.graphics.Color;
import android.text.Spanned;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.privacy.browser.R;


import java.util.ArrayList;
import java.util.List;

public class AiChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int TYPE_USER = 1;
    public static final int TYPE_ASSISTANT = 2;
    public static final int TYPE_TOOL = 3;
    public static final int TYPE_CONFIRMATION = 4;
    public static final int TYPE_THINKING = 5;
    public static final int TYPE_ERROR = 6;

    public static class ChatMessage {
        public final int type;
        public final String content;
        public final String toolName;
        public final boolean inProgress;
        public final String imagePath;

        private Spanned cachedFormattedText;
        private Boolean cachedIsDark;

        public ChatMessage(int type, String content) {
            this(type, content, null, false, null);
        }

        ChatMessage(int type, String content, String toolName, boolean inProgress, String imagePath) {
            this.type = type;
            this.content = content != null ? content : "";
            this.toolName = toolName;
            this.inProgress = inProgress;
            this.imagePath = imagePath;
        }

        public Spanned getFormattedText(boolean isDark) {
            if (cachedFormattedText != null && cachedIsDark != null && cachedIsDark == isDark) {
                return cachedFormattedText;
            }
            cachedFormattedText = MarkdownRenderer.format(content, isDark);
            cachedIsDark = isDark;
            return cachedFormattedText;
        }
    }

    private final List<ChatMessage> messages = new ArrayList<>();
    private final boolean isDark;

    public AiChatAdapter(boolean isDark) {
        this.isDark = isDark;
    }

    public void addMessage(ChatMessage message) {
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    public void setMessages(List<ChatMessage> newMessages) {
        messages.clear();
        if (newMessages != null) {
            messages.addAll(newMessages);
        }
        notifyDataSetChanged();
    }

    public void clearMessages() {
        messages.clear();
        notifyDataSetChanged();
    }

    public void showThinking(String text) {
        if (!messages.isEmpty()) {
            int lastIdx = messages.size() - 1;
            ChatMessage last = messages.get(lastIdx);
            if (last.type == TYPE_THINKING) {
                messages.set(lastIdx, new ChatMessage(TYPE_THINKING, text, null, true, null));
                notifyItemChanged(lastIdx);
                return;
            }
        }
        messages.add(new ChatMessage(TYPE_THINKING, text, null, true, null));
        notifyItemInserted(messages.size() - 1);
    }

    public void hideThinking() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).type == TYPE_THINKING) {
                messages.remove(i);
                notifyItemRemoved(i);
            }
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    @Override
    public int getItemViewType(int position) {
        int type = messages.get(position).type;
        if (type == TYPE_THINKING) {
            return TYPE_TOOL;
        }
        return type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_USER:
                return new UserViewHolder(inflater.inflate(R.layout.item_ai_message_user, parent, false));
            case TYPE_ASSISTANT:
                return new AssistantViewHolder(inflater.inflate(R.layout.item_ai_message_assistant, parent, false), isDark);
            case TYPE_TOOL:
                return new ToolViewHolder(inflater.inflate(R.layout.item_ai_message_tool, parent, false));
            case TYPE_CONFIRMATION:
                return new ConfirmationViewHolder(inflater.inflate(R.layout.item_ai_message_confirmation, parent, false));
            case TYPE_ERROR:
                return new ErrorViewHolder(inflater.inflate(R.layout.item_ai_message_error, parent, false));
            default:
                return new AssistantViewHolder(inflater.inflate(R.layout.item_ai_message_assistant, parent, false), isDark);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder, int position) {
        final ChatMessage msg = messages.get(position);
        if (holder instanceof UserViewHolder) {
            ((UserViewHolder) holder).bind(msg);
        } else if (holder instanceof AssistantViewHolder) {
            ((AssistantViewHolder) holder).bind(msg, isDark);
        } else if (holder instanceof ToolViewHolder) {
            ((ToolViewHolder) holder).bind(msg, isDark);
        } else if (holder instanceof ConfirmationViewHolder) {
            ((ConfirmationViewHolder) holder).bind(msg, isDark);
        } else if (holder instanceof ErrorViewHolder) {
            ((ErrorViewHolder) holder).bind(msg, isDark);
        }
    }

    static class UserViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivUserAttachment;
        final TextView tvUser;
        final int bottomMarginPx;
        private long binding;

        public UserViewHolder(@NonNull View itemView) {
            super(itemView);
            ivUserAttachment = itemView.findViewById(R.id.iv_user_attachment);
            if (ivUserAttachment != null) {
                ivUserAttachment.setClipToOutline(true);
            }
            tvUser = itemView.findViewById(R.id.tv_user_message);
            bottomMarginPx = (int) (6 * itemView.getContext().getResources().getDisplayMetrics().density + 0.5f);
        }

        public void bind(ChatMessage msg) {
            long requested = ++binding;
            boolean hasText = !msg.content.trim().isEmpty();
            tvUser.setText(msg.content);
            tvUser.setVisibility(hasText ? View.VISIBLE : View.GONE);
            if (ivUserAttachment == null) return;
            ivUserAttachment.setImageDrawable(null);
            ivUserAttachment.setVisibility(View.GONE);
            if (msg.imagePath == null || msg.imagePath.isEmpty()) return;
            if (!hasText) {
                tvUser.setText("Saved image — local preview only");
                tvUser.setVisibility(View.VISIBLE);
            }
            AgentAttachmentManager.preview(itemView.getContext(), msg.imagePath, bitmap -> {
                if (binding != requested) return;
                if (bitmap == null) {
                    tvUser.setText((hasText ? msg.content + "\n" : "") + "Saved image preview unavailable");
                    tvUser.setVisibility(View.VISIBLE);
                    return;
                }
                ivUserAttachment.setImageBitmap(bitmap);
                ivUserAttachment.setVisibility(View.VISIBLE);
                if (!hasText) tvUser.setVisibility(View.GONE);
                if (ivUserAttachment.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) ivUserAttachment.getLayoutParams();
                    lp.bottomMargin = hasText ? bottomMarginPx : 0;
                    ivUserAttachment.setLayoutParams(lp);
                }
            });
        }

        void recycle() {
            binding++;
            if (ivUserAttachment != null) ivUserAttachment.setImageDrawable(null);
        }
    }

    @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof UserViewHolder) ((UserViewHolder) holder).recycle();
        super.onViewRecycled(holder);
    }

    static class AssistantViewHolder extends RecyclerView.ViewHolder {
        final TextView tvAssistant;

        public AssistantViewHolder(@NonNull View itemView, boolean isDark) {
            super(itemView);
            tvAssistant = itemView.findViewById(R.id.tv_assistant_message);
            if (tvAssistant != null) {
                tvAssistant.setLinkTextColor(isDark ? Color.parseColor("#60A5FA") : Color.parseColor("#2563EB"));
                tvAssistant.setBackgroundResource(isDark ? R.drawable.bg_ai_assistant_bubble : R.drawable.bg_ai_assistant_bubble_light);
                tvAssistant.setTextColor(isDark ? Color.parseColor("#E2E8F0") : Color.parseColor("#0F172A"));
            }
        }

        public void bind(ChatMessage msg, boolean isDark) {
            if (tvAssistant != null) {
                tvAssistant.setText(msg.getFormattedText(isDark));
            }
        }
    }

    static class ToolViewHolder extends RecyclerView.ViewHolder {
        final LinearLayout llToolBubble;
        final ProgressBar pbToolProgress;
        final TextView tvToolStatus;

        public ToolViewHolder(@NonNull View itemView) {
            super(itemView);
            llToolBubble = itemView.findViewById(R.id.ll_tool_bubble);
            pbToolProgress = itemView.findViewById(R.id.pb_tool_progress);
            tvToolStatus = itemView.findViewById(R.id.tv_tool_status);
        }

        public void bind(ChatMessage msg, boolean isDark) {
            if (llToolBubble != null) {
                llToolBubble.setBackgroundResource(isDark ? R.drawable.bg_ai_tool_bubble : R.drawable.bg_ai_tool_bubble_light);
            }
            if (tvToolStatus != null) {
                tvToolStatus.setText(msg.content);
                tvToolStatus.setTextColor(isDark ? Color.parseColor("#60A5FA") : Color.parseColor("#2563EB"));
            }
            if (pbToolProgress != null) {
                pbToolProgress.setVisibility(msg.inProgress ? View.VISIBLE : View.GONE);
            }
        }
    }

    static class ConfirmationViewHolder extends RecyclerView.ViewHolder {
        final LinearLayout llConfirmationCard;
        final TextView tvConfirmTitle;
        final TextView tvConfirmSummary;
        final Button btnCancel;
        final Button btnExecute;

        public ConfirmationViewHolder(@NonNull View itemView) {
            super(itemView);
            llConfirmationCard = itemView.findViewById(R.id.ll_confirmation_card);
            tvConfirmTitle = itemView.findViewById(R.id.tv_confirmation_title);
            tvConfirmSummary = itemView.findViewById(R.id.tv_confirmation_summary);
            btnCancel = itemView.findViewById(R.id.btn_confirm_cancel);
            btnExecute = itemView.findViewById(R.id.btn_confirm_execute);
        }

        public void bind(final ChatMessage msg, boolean isDark) {
            if (llConfirmationCard != null) {
                llConfirmationCard.setBackgroundResource(isDark ? R.drawable.bg_ai_confirmation_card : R.drawable.bg_ai_confirmation_card_light);
            }
            if (tvConfirmTitle != null) {
                tvConfirmTitle.setText("Expired review: " + (msg.toolName != null ? msg.toolName : "Action"));
                tvConfirmTitle.setTextColor(isDark ? Color.parseColor("#F87171") : Color.parseColor("#DC2626"));
            }
            if (tvConfirmSummary != null) {
                tvConfirmSummary.setText(msg.content);
                tvConfirmSummary.setTextColor(isDark ? Color.parseColor("#E2E8F0") : Color.parseColor("#1E293B"));
            }
            if (btnCancel != null) btnCancel.setVisibility(View.GONE);
            if (btnExecute != null) btnExecute.setVisibility(View.GONE);
        }
    }

    static class ErrorViewHolder extends RecyclerView.ViewHolder {
        final LinearLayout llErrorCard;
        final TextView tvErrorTitle;
        final TextView tvErrorMessage;

        public ErrorViewHolder(@NonNull View itemView) {
            super(itemView);
            llErrorCard = itemView.findViewById(R.id.ll_error_card);
            tvErrorTitle = itemView.findViewById(R.id.tv_error_title);
            tvErrorMessage = itemView.findViewById(R.id.tv_error_message);
        }

        public void bind(ChatMessage msg, boolean isDark) {
            if (llErrorCard != null) {
                llErrorCard.setBackgroundResource(isDark ? R.drawable.bg_ai_error_card : R.drawable.bg_ai_error_card_light);
            }
            if (tvErrorTitle != null) {
                tvErrorTitle.setTextColor(isDark ? Color.parseColor("#F87171") : Color.parseColor("#DC2626"));
            }
            if (tvErrorMessage != null) {
                tvErrorMessage.setText(msg.content);
                tvErrorMessage.setTextColor(isDark ? Color.parseColor("#FECACA") : Color.parseColor("#991B1B"));
            }
        }
    }
}
