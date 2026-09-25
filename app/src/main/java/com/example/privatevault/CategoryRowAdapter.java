package com.example.privatevault;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Stable-ID adapter foundation used by the Phase 3 category-first browser. */
public final class CategoryRowAdapter
        extends RecyclerView.Adapter<CategoryRowAdapter.CategoryViewHolder> {

    public interface Listener {
        void onCategorySelected(CategoryRowModel category);
    }

    private final Listener listener;
    private List<CategoryRowModel> rows = Collections.emptyList();

    public CategoryRowAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submitRows(List<CategoryRowModel> newRows) {
        rows = newRows == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(newRows));
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        return rows.get(position).stableId;
    }

    @NonNull
    @Override
    public CategoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.row_vault_category, parent, false);
        return new CategoryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CategoryViewHolder holder, int position) {
        holder.bind(rows.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static final class CategoryViewHolder extends RecyclerView.ViewHolder {
        private final ImageView icon;
        private final TextView title;
        private final TextView summary;
        private final TextView indicator;

        CategoryViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.category_row_icon);
            title = itemView.findViewById(R.id.category_row_title);
            summary = itemView.findViewById(R.id.category_row_summary);
            indicator = itemView.findViewById(R.id.category_row_indicator);
        }

        void bind(CategoryRowModel row, Listener listener) {
            icon.setImageResource(R.drawable.ic_keepriva_folder);
            title.setText(row.label);
            summary.setText(row.entryCount + (row.entryCount == 1 ? " entry" : " entries"));
            indicator.setText(row.expanded ? "⌄" : "›");
            itemView.setPadding(
                    UiStyle.dp(itemView.getContext(), 12 + Math.min(row.depth, 5) * 20),
                    UiStyle.dp(itemView.getContext(), 10),
                    UiStyle.dp(itemView.getContext(), 8),
                    UiStyle.dp(itemView.getContext(), 10));
            itemView.setContentDescription(
                    (row.expanded ? "Close category " : "Open category ") + row.label);
            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onCategorySelected(row);
            });
        }
    }
}

