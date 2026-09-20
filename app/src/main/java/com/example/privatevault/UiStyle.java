package com.example.privatevault;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/**
 * Canonical Keepriva visual-style helpers.
 *
 * <p>The application builds most screens programmatically rather than from XML layouts.
 * Keeping the visual tokens and reusable component styling here prevents individual
 * screens from drifting to different colors, corner radii or text styles.</p>
 */
final class UiStyle {
    private UiStyle() { }

    static int color(Context c, int resId) {
        return c.getColor(resId);
    }

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
        e.setBackground(outlined(c, R.color.keepriva_surface, R.color.keepriva_outline, 10));
        e.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        e.setMinHeight(dp(c, 48));
    }

    static void styleSecondaryButton(Button b) {
        Context c = b.getContext();
        b.setAllCaps(false);
        b.setTextColor(color(c, R.color.keepriva_primary_dark));
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(outlined(c, R.color.keepriva_surface_soft, R.color.keepriva_outline, 10));
        b.setMinHeight(dp(c, 44));
        b.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
    }

    static void stylePrimaryButton(Button b) {
        Context c = b.getContext();
        b.setAllCaps(false);
        b.setTextColor(color(c, android.R.color.white));
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(rounded(c, R.color.keepriva_primary, 10));
        b.setMinHeight(dp(c, 48));
        b.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
    }

    static void styleDangerButton(Button b) {
        Context c = b.getContext();
        b.setAllCaps(false);
        b.setTextColor(color(c, android.R.color.white));
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(rounded(c, R.color.keepriva_danger, 10));
    }

    static void styleCard(View card) {
        Context c = card.getContext();
        card.setBackground(outlined(c, R.color.keepriva_surface, R.color.keepriva_outline, 12));
        card.setElevation(dp(c, 2));
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
