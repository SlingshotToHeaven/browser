package com.privacy.browser.search;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.privacy.browser.R;

import java.util.ArrayList;
import java.util.List;

public class OmniboxSuggestionsAdapter extends BaseAdapter {

    private final Context context;
    private final List<OmniboxSuggestion> items = new ArrayList<>();
    private long firstRowId;

    public OmniboxSuggestionsAdapter(Context context) {
        this.context = context;
    }

    public void updateItems(List<OmniboxSuggestion> newItems) {
        firstRowId += items.size();
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    public void clear() {
        updateItems(null);
    }

    /** A queued click must refer to the same list publication and the view bound to that row. */
    public OmniboxSuggestion clickedItem(View view, int position, long id) {
        if (position < 0 || position >= items.size() || id != getItemId(position) || view == null) return null;
        Object tag = view.getTag();
        if (!(tag instanceof ViewHolder)) return null;
        ViewHolder holder = (ViewHolder) tag;
        OmniboxSuggestion item = getItem(position);
        return holder.id == id && holder.item == item ? item : null;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public OmniboxSuggestion getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return firstRowId + position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_omnibox_suggestion, parent, false);
            holder = new ViewHolder();
            holder.icon = convertView.findViewById(R.id.suggestion_icon);
            holder.title = convertView.findViewById(R.id.suggestion_title);
            holder.subtitle = convertView.findViewById(R.id.suggestion_subtitle);
            holder.badge = convertView.findViewById(R.id.suggestion_badge);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        OmniboxSuggestion item = getItem(position);
        holder.id = getItemId(position);
        holder.item = item;
        holder.title.setText(item.getTitle());
        holder.subtitle.setText(item.getSubtitle());

        if (item.getIconResId() != 0) {
            holder.icon.setImageResource(item.getIconResId());
        }

        switch (item.getType()) {
            case BOOKMARK:
                holder.badge.setText("BOOKMARK");
                holder.badge.setTextColor(Color.parseColor("#00E5FF")); // Cyan
                holder.icon.setColorFilter(Color.parseColor("#00E5FF"));
                break;
            case HISTORY:
                holder.badge.setText("HISTORY");
                holder.badge.setTextColor(Color.parseColor("#8E8EA8")); // Soft Grey
                holder.icon.setColorFilter(Color.parseColor("#8E8EA8"));
                break;
            case BANG:
                holder.badge.setText("BANG");
                holder.badge.setTextColor(Color.parseColor("#BB86FC")); // Purple Accent
                holder.icon.setColorFilter(Color.parseColor("#BB86FC"));
                break;
            case CLIPBOARD:
                holder.badge.setText("CLIPBOARD");
                holder.badge.setTextColor(Color.parseColor("#FF9800")); // Amber/Orange
                holder.icon.setColorFilter(Color.parseColor("#FF9800"));
                break;
            case SEARCH:
            default:
                holder.badge.setText("SEARCH");
                holder.badge.setTextColor(Color.parseColor("#4CAF50")); // Green
                holder.icon.setColorFilter(Color.parseColor("#4CAF50"));
                break;
        }

        return convertView;
    }

    private static class ViewHolder {
        long id;
        OmniboxSuggestion item;
        ImageView icon;
        TextView title;
        TextView subtitle;
        TextView badge;
    }
}
