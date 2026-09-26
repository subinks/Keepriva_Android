package com.example.privatevault;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Objects;

/** Owns unlock-screen rendering and transient progress/error state, never a vault key. */
final class UnlockController implements VaultController {
    private final Activity activity;
    private final VaultViewFactory views;
    private final Gateway gateway;
    private final UnlockActions actions;
    private boolean closed;

    UnlockController(Activity activity, VaultViewFactory views, Gateway gateway, UnlockActions actions) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.views = Objects.requireNonNull(views, "views");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    View createView(boolean biometricConfigured) {
        ensureOpen();
        final int white = Color.WHITE;
        final int mutedWhite = Color.argb(215, 255, 255, 255);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(views.dp(26), views.dp(34), views.dp(26), views.dp(30));
        root.setBackgroundColor(activity.getColor(R.color.keepriva_primary_dark));

        ImageView logo = new ImageView(activity);
        logo.setImageResource(R.drawable.ic_keepriva_shield_leaf);
        logo.setContentDescription("Keepriva shield and leaf logo");
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(views.dp(88), views.dp(88));
        logoParams.bottomMargin = views.dp(10);
        root.addView(logo, logoParams);

        TextView appName = new TextView(activity);
        appName.setText("Keepriva");
        appName.setTextColor(white);
        appName.setTextSize(32);
        appName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        appName.setGravity(Gravity.CENTER);
        root.addView(appName, views.matchWidth());

        TextView tagline = new TextView(activity);
        tagline.setText("Your secrets. Your control.");
        tagline.setTextColor(mutedWhite);
        tagline.setTextSize(15);
        tagline.setGravity(Gravity.CENTER);
        tagline.setPadding(0, views.dp(4), 0, views.dp(22));
        root.addView(tagline, views.matchWidth());

        LinearLayout authCard = UiStyle.verticalCard(activity, 18);
        authCard.addView(UiStyle.sectionTitle(activity, "Unlock your vault"));
        authCard.addView(UiStyle.sectionCaption(activity,
                biometricConfigured
                        ? "Use biometrics or your master password."
                        : "Use your master password. Biometrics can be enabled later from Security."));

        if (biometricConfigured) {
            Button biometric = views.secondaryButton("Unlock with fingerprint / face");
            biometric.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_keepriva_fingerprint, 0, 0, 0);
            biometric.setCompoundDrawablePadding(views.dp(8));
            biometric.setContentDescription("Unlock Keepriva with biometrics");
            biometric.setOnClickListener(v -> {
                if (!closed) gateway.requestBiometricUnlock();
            });
            authCard.addView(biometric, views.matchWidth());

            TextView or = UiStyle.sectionCaption(activity, "or use your master password");
            or.setGravity(Gravity.CENTER);
            or.setPadding(0, views.dp(12), 0, views.dp(6));
            authCard.addView(or, views.matchWidth());
        }

        EditText pass = views.passwordField("Master password");
        pass.setContentDescription("Master password");
        authCard.addView(pass, views.matchWidth());

        ProgressBar progress = new ProgressBar(activity);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(views.dp(28), views.dp(28));
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.topMargin = views.dp(10);
        authCard.addView(progress, progressParams);

        Button unlock = views.primaryButton("Unlock");
        unlock.setContentDescription("Unlock with master password");
        Attempt attempt = new Attempt(pass, unlock, progress);
        View.OnClickListener action = v -> {
            if (closed || attempt.pending) return;
            String entered = pass.getText().toString();
            if (entered.isEmpty()) {
                pass.setError("Enter your master password");
                return;
            }
            attempt.pending = true;
            pass.setEnabled(false);
            unlock.setEnabled(false);
            unlock.setText("Unlocking…");
            progress.setVisibility(View.VISIBLE);
            gateway.requestPasswordUnlock(entered, attempt);
        };
        unlock.setOnClickListener(action);
        pass.setOnEditorActionListener((v, actionId, event) -> {
            action.onClick(v);
            return true;
        });

        LinearLayout.LayoutParams unlockParams = views.matchWidth();
        unlockParams.topMargin = views.dp(10);
        authCard.addView(unlock, unlockParams);
        root.addView(authCard, views.matchWidth());

        TextView footer = new TextView(activity);
        footer.setText("Fully offline  •  Secure  •  Private");
        footer.setTextColor(Color.argb(195, 255, 255, 255));
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, views.dp(22), 0, 0);
        root.addView(footer, views.matchWidth());

        ScrollView screen = new ScrollView(activity);
        screen.setFillViewport(true);
        screen.setBackgroundColor(activity.getColor(R.color.keepriva_primary_dark));
        screen.addView(root);
        return screen;
    }

    void completeSuccess(Attempt attempt) {
        if (closed || attempt == null || !attempt.pending) return;
        attempt.pending = false;
        attempt.pass.setText("");
        actions.onUnlockCompleted();
    }

    void completeFailure(Attempt attempt) {
        if (closed || attempt == null || !attempt.pending) return;
        attempt.pending = false;
        attempt.progress.setVisibility(View.GONE);
        attempt.pass.setEnabled(true);
        attempt.unlock.setEnabled(true);
        attempt.unlock.setText("Unlock");
        attempt.pass.requestFocus();
        gateway.showMessage("Incorrect master password or vault configuration is damaged.");
    }

    void completeBiometricSuccess() {
        if (!closed) actions.onUnlockCompleted();
    }

    @Override
    public void close() {
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Unlock controller is closed");
    }

    static final class Attempt {
        private final EditText pass;
        private final Button unlock;
        private final ProgressBar progress;
        private boolean pending;

        private Attempt(EditText pass, Button unlock, ProgressBar progress) {
            this.pass = pass;
            this.unlock = unlock;
            this.progress = progress;
        }
    }

    interface Gateway {
        void requestPasswordUnlock(String password, Attempt attempt);
        void requestBiometricUnlock();
        void showMessage(String message);
    }
}
