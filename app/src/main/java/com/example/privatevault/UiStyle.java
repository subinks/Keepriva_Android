package com.example.privatevault;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

/** Shared visual language for Keepriva's programmatic Android UI. */
final class UiStyle {
    private UiStyle() { }

    static int color(Context c, int resId) { return c.getColor(resId); }
    static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(Context c, int fillRes, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color(c, fillRes));
        d.setCornerRadius(dp(c, Math.round(radiusDp)));
        return d;
    }

    static GradientDrawable outlined(Context c, int fillRes, int strokeRes, float radiusDp) {
        GradientDrawable d = rounded(c, fillRes, radiusDp);
        d.setStroke(dp(c, 1), color(c, strokeRes));
        return d;
    }

    static void styleInput(EditText e) {
        Context c = e.getContext();
        e.setTextColor(color(c, R.color.keepriva_text_primary));
        e.setHintTextColor(color(c, R.color.keepriva_text_secondary));
        e.setTextSize(16);
        e.setBackground(outlined(c, R.color.keepriva_surface, R.color.keepriva_outline, 12));
        e.setPadding(dp(c, 14), dp(c, 11), dp(c, 14), dp(c, 11));
        e.setMinHeight(dp(c, 52));
    }

    static void styleSpinner(Spinner spinner) {
        Context c = spinner.getContext();
        spinner.setBackground(outlined(c, R.color.keepriva_surface, R.color.keepriva_outline, 12));
        spinner.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        spinner.setMinimumHeight(dp(c, 52));
    }

    static void styleSecondaryButton(Button b) {
        Context c = b.getContext();
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(color(c, R.color.keepriva_primary_dark));
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(outlined(c, R.color.keepriva_surface_soft, R.color.keepriva_outline, 12));
        b.setMinHeight(dp(c, 46));
        b.setPadding(dp(c, 14), dp(c, 9), dp(c, 14), dp(c, 9));
    }

    static void styleCompactButton(Button b) {
        styleSecondaryButton(b);
        Context c = b.getContext();
        b.setMinHeight(dp(c, 42));
        b.setTextSize(13);
        b.setPadding(dp(c, 10), dp(c, 7), dp(c, 10), dp(c, 7));
    }

    static void stylePrimaryButton(Button b) {
        Context c = b.getContext();
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(color(c, android.R.color.white));
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(rounded(c, R.color.keepriva_primary, 12));
        b.setMinHeight(dp(c, 52));
        b.setPadding(dp(c, 16), dp(c, 11), dp(c, 16), dp(c, 11));
        b.setElevation(dp(c, 2));
    }

    static void styleDangerButton(Button b) {
        Context c = b.getContext();
        b.setAllCaps(false);
        b.setTextColor(color(c, android.R.color.white));
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(rounded(c, R.color.keepriva_danger, 12));
        b.setMinHeight(dp(c, 44));
    }

    static void styleCard(View card) {
        Context c = card.getContext();
        card.setBackground(outlined(c, R.color.keepriva_surface, R.color.keepriva_outline, 14));
        card.setElevation(dp(c, 2));
    }

    static LinearLayout verticalCard(Context c, int paddingDp) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(c,paddingDp), dp(c,paddingDp), dp(c,paddingDp), dp(c,paddingDp));
        styleCard(card);
        return card;
    }

    static TextView sectionTitle(Context c, String text) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextSize(17);
        v.setTextColor(color(c, R.color.keepriva_text_primary));
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setGravity(Gravity.START);
        return v;
    }

    static TextView sectionCaption(Context c, String text) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextSize(13);
        v.setTextColor(color(c, R.color.keepriva_text_secondary));
        v.setPadding(0, dp(c,4), 0, dp(c,8));
        return v;
    }

    static void styleTitle(TextView v) {
        Context c = v.getContext();
        v.setTextColor(color(c, R.color.keepriva_primary_dark));
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    }
    static void styleBodyText(TextView v) {
        v.setTextColor(color(v.getContext(), R.color.keepriva_text_secondary));
    }
    static void styleLabel(TextView v) {
        Context c = v.getContext();
        v.setTextColor(color(c, R.color.keepriva_text_primary));
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    }
}