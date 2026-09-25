package com.example.privatevault;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Objects;

/** Shared factory for the programmatic views retained during the legacy UI extraction. */
final class VaultViewFactory {
    private final Context context;

    VaultViewFactory(Context context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    LinearLayout verticalContainer(int ignoredGapDp) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));
        layout.setShowDividers(LinearLayout.SHOW_DIVIDER_NONE);
        layout.setBackgroundColor(context.getColor(R.color.keepriva_background));
        return layout;
    }

    ScrollView scroll(View child) {
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(context.getColor(R.color.keepriva_background));
        scroll.addView(child);
        return scroll;
    }

    TextView title(String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(26);
        view.setPadding(0, dp(8), 0, dp(12));
        UiStyle.styleTitle(view);
        return view;
    }

    TextView subtitle(String text) {
        TextView view = new TextView(context);
        view.setText(text == null || text.isEmpty() ? "—" : text);
        view.setTextSize(15);
        view.setPadding(0, dp(2), 0, dp(10));
        UiStyle.styleBodyText(view);
        return view;
    }

    TextView boldLabel(String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(14);
        view.setPadding(0, dp(8), 0, 0);
        UiStyle.styleLabel(view);
        return view;
    }

    EditText field(String hint, String value) {
        EditText field = new EditText(context);
        field.setHint(hint);
        field.setText(value == null ? "" : value);
        field.setTextSize(16);
        UiStyle.styleInput(field);
        LinearLayout.LayoutParams params = matchWidth();
        params.setMargins(0, dp(4), 0, dp(8));
        field.setLayoutParams(params);
        return field;
    }

    EditText passwordField(String hint) {
        EditText field = field(hint, "");
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return field;
    }

    EditText phoneField(String hint, String value) {
        EditText field = field(hint, value);
        field.setInputType(InputType.TYPE_CLASS_PHONE);
        return field;
    }

    Button secondaryButton(String text) {
        Button button = new Button(context);
        button.setText(text);
        UiStyle.styleSecondaryButton(button);
        return button;
    }

    Button primaryButton(String text) {
        Button button = new Button(context);
        button.setText(text);
        UiStyle.stylePrimaryButton(button);
        return button;
    }

    LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}
