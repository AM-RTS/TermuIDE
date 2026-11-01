package amrts.termuide;

import android.app.Activity;
import android.os.Bundle;

/** Activity-owned setup prompts; both hosts use the same persistence and actions. */
public final class TermuxPrompts {
    boolean install, installNoRemind, initialize, initNoRemind;
    private final Runnable changed;
    TermuxPrompts(Runnable changed) { this.changed = changed; }
    void showInstall(boolean reminder) { install = true; installNoRemind = reminder; changed.run(); }
    void showInitialize(boolean reminder) { initialize = true; initNoRemind = reminder; changed.run(); }
    void resolveInstall(Activity context, boolean launch, boolean skip) {
        install = false;
        if (launch) Utils.startTermuxInstall(context);
        else if (skip) Utils.skipTermuxInstallReminder(context);
        changed.run();
    }
    void resolveInitialize(Activity context, boolean launch, boolean skip) {
        initialize = false;
        if (launch) Utils.copyJump(context);
        else if (skip) Utils.skipTermuxInitReminder(context);
        changed.run();
    }
    void restore(Bundle state) {
        if (state == null) return;
        install = state.getBoolean("installPrompt"); installNoRemind = state.getBoolean("installPromptNoRemind");
        initialize = state.getBoolean("initPrompt"); initNoRemind = state.getBoolean("initPromptNoRemind");
    }
    void save(Bundle state) {
        state.putBoolean("installPrompt", install); state.putBoolean("installPromptNoRemind", installNoRemind);
        state.putBoolean("initPrompt", initialize); state.putBoolean("initPromptNoRemind", initNoRemind);
    }
}
