package com.example.privatevault;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** XML-backed reusable view factories for Phase 2 and later full-screen workflows. */
public final class VaultUiComponents {
    private VaultUiComponents() { }

    public static View toolbar(
            Context context,
            String titleText,
            String subtitleText,
            int actionIcon,
            String actionDescription,
            View.OnClickListener action) {
        View root = inflate(context, R.layout.view_vault_toolbar);
        TextView title = root.findViewById(R.id.toolbar_title);
        TextView subtitle = root.findViewById(R.id.toolbar_subtitle);
        ImageButton actionButton = root.findViewById(R.id.toolbar_action);
        title.setText(safe(titleText));
        subtitle.setText(safe(subtitleText));
        subtitle.setVisibility(safe(subtitleText).isEmpty() ? View.GONE : View.VISIBLE);
        if (actionIcon == 0 || action == null) {
            actionButton.setVisibility(View.GONE);
        } else {
            actionButton.setImageResource(actionIcon);
            actionButton.setContentDescription(safe(actionDescription));
            actionButton.setTooltipText(safe(actionDescription));
            actionButton.setOnClickListener(action);
        }
        return root;
    }

    public static View emptyState(
            Context context,
            String titleText,
            String messageText,
            boolean loading,
            String actionText,
            View.OnClickListener action) {
        View root = inflate(context, R.layout.view_vault_empty_state);
        TextView title = root.findViewById(R.id.empty_state_title);
        TextView message = root.findViewById(R.id.empty_state_message);
        ProgressBar progress = root.findViewById(R.id.empty_state_progress);
        Button button = root.findViewById(R.id.empty_state_action);
        title.setText(safe(titleText));
        message.setText(safe(messageText));
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (action == null || safe(actionText).isEmpty()) {
            button.setVisibility(View.GONE);
        } else {
            button.setText(actionText);
            button.setOnClickListener(action);
        }
        return root;
    }

    public static View fieldRow(
            Context context,
            String labelText,
            String valueText,
            String copyDescription,
            View.OnClickListener copyAction) {
        View root = inflate(context, R.layout.row_vault_field);
        ((TextView) root.findViewById(R.id.field_row_label)).setText(safe(labelText));
        ((TextView) root.findViewById(R.id.field_row_value)).setText(safe(valueText));
        ImageButton copy = root.findViewById(R.id.field_row_copy);
        if (copyAction == null) {
            copy.setVisibility(View.GONE);
        } else {
            copy.setContentDescription(safe(copyDescription));
            copy.setTooltipText(safe(copyDescription));
            copy.setOnClickListener(copyAction);
        }
        return root;
    }

    public static View actionRow(
            Context context,
            int iconResource,
            String titleText,
            String descriptionText,
            View.OnClickListener action) {
        View root = inflate(context, R.layout.row_vault_action);
        ((ImageView) root.findViewById(R.id.action_row_icon)).setImageResource(iconResource);
        ((TextView) root.findViewById(R.id.action_row_title)).setText(safe(titleText));
        ((TextView) root.findViewById(R.id.action_row_description)).setText(safe(descriptionText));
        root.setContentDescription(safe(titleText));
        root.setOnClickListener(action);
        return root;
    }

    private static View inflate(Context context, int layout) {
        return LayoutInflater.from(context).inflate(layout, null, false);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}

