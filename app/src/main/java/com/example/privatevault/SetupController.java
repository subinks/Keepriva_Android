package com.example.privatevault;

import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.Objects;

/** Builds the setup screen and emits an event only after local validation succeeds. */
final class SetupController implements VaultController {
    private final VaultViewFactory views;
    private final Gateway gateway;
    private final SetupActions actions;
    private boolean closed;

    SetupController(VaultViewFactory views, Gateway gateway, SetupActions actions) {
        this.views = Objects.requireNonNull(views, "views");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    View createView() {
        ensureOpen();
        LinearLayout root = views.verticalContainer(24);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(views.title("Create Keepriva"));
        root.addView(views.subtitle("Your master password never leaves this device. If you forget it, the encrypted vault cannot be recovered."));

        EditText pass = views.passwordField("Master password (12+ characters)");
        EditText confirm = views.passwordField("Confirm master password");
        root.addView(pass);
        root.addView(confirm);

        Button create = views.primaryButton("Create encrypted vault");
        create.setOnClickListener(v -> {
            if (closed) return;
            String password = pass.getText().toString();
            String confirmation = confirm.getText().toString();
            String strengthError = MasterPasswordPolicy.validate(password);
            if (strengthError != null) {
                gateway.showMessage(strengthError);
                return;
            }
            if (!password.equals(confirmation)) {
                gateway.showMessage("Passwords do not match.");
                return;
            }
            try {
                gateway.initializeVault(password);
                pass.setText("");
                confirm.setText("");
                actions.onSetupCompleted();
            } catch (Exception error) {
                gateway.showMessage("Unable to initialize vault: " + error.getMessage());
            }
        });
        root.addView(create);
        return views.scroll(root);
    }

    @Override
    public void close() {
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Setup controller is closed");
    }

    interface Gateway {
        void initializeVault(String password) throws Exception;
        void showMessage(String message);
    }
}
