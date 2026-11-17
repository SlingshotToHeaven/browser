package com.privacy.browser.ai;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.privacy.browser.R;
import com.privacy.browser.ThemeManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AgentHistoryDialog {

    public interface OnSessionSelectedListener {
        void onSessionSelected(String sessionId);
        void onNewChatRequested();
    }

    private final Context context;
    private final Dialog dialog;
    private final boolean isDark;
    private final AgentDbHelper dbHelper;
    private final AgentSessionManager sessionManager;
    private final OnSessionSelectedListener listener;

    private RecyclerView rvHistory;
    private LinearLayout llEmptyState;
    private TextView tvEmptyTitle, tvEmptySubtitle;
    private HistoryAdapter adapter;
    private final List<AgentDbHelper.SessionRecord> sessionList = new ArrayList<>();

    public AgentHistoryDialog(Context context, OnSessionSelectedListener listener) {
        this.context = context;
        this.listener = listener;
        this.isDark = ThemeManager.isAppDarkTheme(context);
        this.dbHelper = AgentDbHelper.getInstance(context);
        this.sessionManager = AgentSessionManager.getInstance(context);

        this.dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_agent_history, null);
        dialog.setContentView(view);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setWindowAnimations(R.style.Animation_AgentBottomSheet);
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }

        initViews(view);
    }

    private void initViews(View view) {
        View scrim = view.findViewById(R.id.v_history_scrim_dismiss);
        View root = view.findViewById(R.id.ll_agent_history_sheet_root);
        View dragHandle = view.findViewById(R.id.v_history_drag_handle);
        TextView tvTitle = view.findViewById(R.id.tv_history_header_title);
        View divider = view.findViewById(R.id.v_history_header_divider);
        TextView btnNewChat = view.findViewById(R.id.btn_history_new_chat);
        ImageButton btnClose = view.findViewById(R.id.btn_history_close);

        rvHistory = view.findViewById(R.id.rv_agent_history_list);
        llEmptyState = view.findViewById(R.id.ll_history_empty_state);

        // Find empty state textviews
        if (llEmptyState != null && llEmptyState.getChildCount() >= 3) {
            if (llEmptyState.getChildAt(1) instanceof TextView) {
                tvEmptyTitle = (TextView) llEmptyState.getChildAt(1);
            }
            if (llEmptyState.getChildAt(2) instanceof TextView) {
                tvEmptySubtitle = (TextView) llEmptyState.getChildAt(2);
            }
        }

        // Theme adaptive styling
        root.setBackgroundResource(isDark ? R.drawable.bg_agent_sheet_surface : R.drawable.bg_agent_sheet_surface_light);
        if (dragHandle != null) {
            dragHandle.setBackgroundResource(isDark ? R.drawable.bg_agent_drag_handle : R.drawable.bg_agent_drag_handle_light);
        }
        tvTitle.setTextColor(isDark ? Color.WHITE : Color.parseColor("#0F172A"));
        divider.setBackgroundColor(isDark ? Color.parseColor("#242436") : Color.parseColor("#E2E8F0"));

        int iconTint = isDark ? Color.parseColor("#94A3B8") : Color.parseColor("#64748B");
        btnClose.setColorFilter(iconTint);

        if (tvEmptyTitle != null) {
            tvEmptyTitle.setTextColor(isDark ? Color.WHITE : Color.parseColor("#0F172A"));
        }
        if (tvEmptySubtitle != null) {
            tvEmptySubtitle.setTextColor(isDark ? Color.parseColor("#94A3B8") : Color.parseColor("#64748B"));
        }

        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        btnNewChat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (listener != null) {
                    listener.onNewChatRequested();
                }
            }
        });

        rvHistory.setLayoutManager(new LinearLayoutManager(context));
        adapter = new HistoryAdapter();
        rvHistory.setAdapter(adapter);

        loadData();
    }

    private void loadData() {
        sessionList.clear();
        sessionList.addAll(dbHelper.getAllSessions());
        adapter.notifyDataSetChanged();

        if (sessionList.isEmpty()) {
            llEmptyState.setVisibility(View.VISIBLE);
            rvHistory.setVisibility(View.GONE);
        } else {
            llEmptyState.setVisibility(View.GONE);
            rvHistory.setVisibility(View.VISIBLE);
        }
    }

    public void show() {
        loadData();
        dialog.show();
    }

    private class HistoryAdapter extends RecyclerView.Adapter<HistoryViewHolder> {

        private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, yyyy - HH:mm", Locale.getDefault());

        @NonNull
        @Override
        public HistoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_agent_history_row, parent, false);
            return new HistoryViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull HistoryViewHolder holder, int position) {
            final AgentDbHelper.SessionRecord record = sessionList.get(position);

            boolean isCurrent = record.sessionId.equals(sessionManager.getCurrentSessionId());

            // Theme row card and icon tile
            holder.itemView.setBackgroundResource(isDark ? R.drawable.bg_settings_card : R.drawable.bg_settings_card_light);
            if (holder.llTile != null) {
                holder.llTile.setBackgroundResource(isDark ? R.drawable.bg_history_icon_tile : R.drawable.bg_history_icon_tile_light);
            }

            holder.tvTitle.setText(record.title);
            holder.tvTitle.setTextColor(isDark ? Color.WHITE : Color.parseColor("#0F172A"));

            String dateStr = dateFormat.format(new Date(record.updatedAt));
            String msgCountStr = record.messageCount + (record.messageCount == 1 ? " message" : " messages");
            holder.tvSubtitle.setText(dateStr + " - " + msgCountStr);
            holder.tvSubtitle.setTextColor(isDark ? Color.parseColor("#94A3B8") : Color.parseColor("#64748B"));

            // Theme active badge
            holder.tvActiveBadge.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
            holder.tvActiveBadge.setBackgroundResource(isDark ? R.drawable.bg_settings_badge_connected : R.drawable.bg_settings_badge_connected_light);
            holder.tvActiveBadge.setTextColor(isDark ? Color.parseColor("#34D399") : Color.parseColor("#059669"));

            // Theme delete button
            holder.btnDelete.setColorFilter(isDark ? Color.parseColor("#94A3B8") : Color.parseColor("#64748B"));

            holder.btnDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sessionManager.deleteSession(record.sessionId);
                    loadData();
                }
            });

            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    if (listener != null) {
                        listener.onSessionSelected(record.sessionId);
                    }
                }
            });
        }

        @Override
        public int getItemCount() {
            return sessionList.size();
        }
    }

    static class HistoryViewHolder extends RecyclerView.ViewHolder {
        LinearLayout llTile;
        TextView tvTitle, tvSubtitle, tvActiveBadge;
        ImageButton btnDelete;

        public HistoryViewHolder(@NonNull View itemView) {
            super(itemView);
            llTile = itemView.findViewById(R.id.ll_history_tile);
            tvTitle = itemView.findViewById(R.id.tv_history_session_title);
            tvSubtitle = itemView.findViewById(R.id.tv_history_session_subtitle);
            tvActiveBadge = itemView.findViewById(R.id.tv_history_active_badge);
            btnDelete = itemView.findViewById(R.id.btn_history_delete);
        }
    }
}
