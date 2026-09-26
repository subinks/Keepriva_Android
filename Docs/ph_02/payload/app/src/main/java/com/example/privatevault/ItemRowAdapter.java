package com.example.privatevault;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Stable-ID adapter foundation shared by browser and search-result screens. */
public final class ItemRowAdapter extends RecyclerView.Adapter<ItemRowAdapter.ItemViewHolder> {

    public interface Listener {
        void onItemSelected(long itemId);
    }

    private final Listener listener;
    private List<ItemRowModel> rows = Collections.emptyList();

    public ItemRowAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submitRows(List<ItemRowModel> newRows) {
        rows = newRows == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(newRows));
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        return rows.get(position).itemId;
    }

    @NonNull
    @Override
    public ItemViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.row_vault_item, parent, false);
        return new ItemViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ItemViewHolder holder, int position) {
        holder.bind(rows.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static final class ItemViewHolder extends RecyclerView.ViewHolder {
        private final TextView title;
        private final TextView category;
        private final TextView secondary;

        ItemViewHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.item_row_title);
            category = itemView.findViewById(R.id.item_row_category);
            secondary = itemView.findViewById(R.id.item_row_secondary);
        }

        void bind(ItemRowModel row, Listener listener) {
            title.setText(row.title.isEmpty() ? "Untitled" : row.title);
            category.setText(row.categoryLabel);
            secondary.setText(row.secondaryText);
            secondary.setVisibility(row.secondaryText.isEmpty() ? View.GONE : View.VISIBLE);
            itemView.setContentDescription("Open entry " + (row.title.isEmpty() ? "Untitled" : row.title));
            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onItemSelected(row.itemId);
            });
        }
    }
}

