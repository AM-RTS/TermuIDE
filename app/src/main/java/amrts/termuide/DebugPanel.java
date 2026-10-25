package amrts.termuide;

import android.app.Activity;
import android.content.*;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.view.*;
import android.widget.Toast;
import cn.rbc.codeeditor.util.HelperUtils;
import java.util.*;
import static android.view.WindowManager.LayoutParams.*;

/** Activity-owned overlay presentation; DebugSession owns transport and protocol state. */
public class DebugPanel implements Runnable {
    private final Activity mCtx;
    private final Handler main;
    private WindowManager wm;
    private WindowManager.LayoutParams params;
    private View ctrlr;
    private DebugSession session;
    private DebugSession.Snapshot state = DebugSession.Snapshot.empty();
    private long generation;
    private final ArrayList<String> breakpoints = new ArrayList<>();
    private final ArrayList<Boolean> breakpointEnabled = new ArrayList<>();
    private boolean detailsOpen;
    private int selectedTab;
    private Runnable uiRefresh;

    public DebugPanel(Activity context) {
        mCtx = context; main = new Handler(context.getMainLooper()); init();
    }

    private void init() {
        WindowManager wm = (WindowManager)mCtx.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        this.wm = wm;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.type = TYPE_APPLICATION_OVERLAY;
        params.flags = FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN;
        params.softInputMode = SOFT_INPUT_ADJUST_RESIZE;
        params.format = PixelFormat.RGBA_8888;
        params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        params.width = WRAP_CONTENT;
        params.height = WRAP_CONTENT;
        this.params = params;
        ctrlr = TermuIDECompose.debugView((androidx.activity.ComponentActivity)mCtx, this);
    }

    @Override public void run() {
        if (Settings.canDrawOverlays(mCtx)) show();
        else mCtx.startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + mCtx.getPackageName())), MainActivity.SHOW_FLOATING);
    }

    public void connect() {
        if (session != null) return;
        // A detached overlay's window recomposer belongs to its previous window.
        ctrlr = TermuIDECompose.debugView((androidx.activity.ComponentActivity)mCtx, this);
        final long expected = ++generation;
        session = new DebugSession(update -> main.post(() -> {
            if (generation == expected) applyState(update);
        }));
        session.connect(breakpoints);
    }

    void applyState(DebugSession.Snapshot update) {
        boolean wasConnected = state.connected;
        boolean newlyStopped = state.status != DebugSession.STOPPED && update.status == DebugSession.STOPPED;
        state = update;
        if (update.exited) { hide(); return; }
        if (update.connected && !wasConnected) run();
        if (!update.connected && session != null) {
            session.close(); session = null; generation++;
        }
        if (update.hitBreakpoint > 0 && update.hitBreakpoint <= breakpoints.size())
            HelperUtils.show(Toast.makeText(mCtx, mCtx.getString(R.string.bkpt_at,
                breakpoints.get(update.hitBreakpoint - 1)), Toast.LENGTH_SHORT));
        if (newlyStopped && detailsOpen && session != null) {
            if (selectedTab == 0) session.refreshVariables();
            else if (selectedTab == 1) session.refreshFrames();
        }
        notifyUiChanged();
    }

    private void send(String command) { if (session != null) session.send(command); }

    public void hide() {
        generation++;
        if (session != null) { session.close(); session = null; }
        if (ctrlr.isAttachedToWindow()) wm.removeView(ctrlr);
        detailsOpen = false;
        setDetailsWindow(false);
        breakpoints.clear(); breakpointEnabled.clear();
        state = new DebugSession.Snapshot(DebugSession.IDLE, 0, false, false,
            state.variables, state.frames, state.console, state.debug);
        notifyUiChanged();
    }

    public void clean() { hide(); state = DebugSession.Snapshot.empty(); notifyUiChanged(); }

    void show() {
        Intent it = new Intent(Intent.ACTION_MAIN);
        it.setPackage(Utils.TERMUX);
        mCtx.startActivity(it);
        if (!ctrlr.isAttachedToWindow()) wm.addView(ctrlr, params);
    }

    public void onAction(String action) {
        if (!"close".equals(action)) {
            Intent it = new Intent(Intent.ACTION_MAIN);
            it.setPackage(Utils.TERMUX);
            mCtx.startActivity(it);
        }
        switch (action) {
            case "continue":
                send(isStopped() ? "-exec-continue" : "-exec-interrupt");
                break;
            case "next":
                send("-exec-next");
                break;
            case "step":
                send("-exec-step");
                break;
            case "finish":
                send("-exec-finish");
                break;
            case "more":
                showDialog();
                break;
            case "close":
                hide();
                break;
        }
    }

    private void showDialog() {
        detailsOpen = true;
        setDetailsWindow(true);
        onTabChanged(selectedTab == 0 ? "var" : selectedTab == 1 ? "frame" : "");
        notifyUiChanged();
    }

    public void closeDetails() {
        detailsOpen = false;
        setDetailsWindow(false);
        notifyUiChanged();
    }

    private void setDetailsWindow(boolean expanded) {
        params.width = expanded ? MATCH_PARENT : WRAP_CONTENT;
        params.height = expanded ? MATCH_PARENT : WRAP_CONTENT;
        params.gravity = expanded ? Gravity.CENTER : Gravity.END | Gravity.CENTER_VERTICAL;
        params.flags = expanded ? FLAG_LAYOUT_IN_SCREEN : FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN;
        if (ctrlr.isAttachedToWindow()) wm.updateViewLayout(ctrlr, params);
    }

    public void addBkpt(String file, int lineno) {
        breakpoints.add(file + ":" + lineno);
        breakpointEnabled.add(true);
        notifyUiChanged();
    }

    public void setUiRefresh(Runnable callback) { uiRefresh = callback; }
    private void notifyUiChanged() { if (uiRefresh != null) uiRefresh.run(); }
    public boolean isDetailsOpen() { return detailsOpen; }
    public boolean isStopped() { return state.status == DebugSession.STOPPED; }
    public boolean isRunning() { return state.status == DebugSession.RUNNING; }
    public boolean isConnected() { return state.connected; }
    public int getSelectedTab() { return selectedTab; }
    public DebugSession.Snapshot getSessionState() { return state; }
    public List<String> getBreakpoints() { return new ArrayList<>(breakpoints); }
    public boolean isBreakpointEnabled(int index) { return index >= 0 && index < breakpointEnabled.size() && breakpointEnabled.get(index); }

    public void moveOverlay(float dx, float dy) {
        if (detailsOpen) return;
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(metrics);
        int horizontal = Math.max(0, metrics.widthPixels - ctrlr.getWidth());
        int vertical = Math.max(0, (metrics.heightPixels - ctrlr.getHeight()) / 2);
        int shift = ctrlr.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? (int)dx : -(int)dx;
        params.x = Math.max(0, Math.min(horizontal, params.x + shift));
        params.y = Math.max(-vertical, Math.min(vertical, params.y + (int)dy));
        if (ctrlr.isAttachedToWindow()) wm.updateViewLayout(ctrlr, params);
    }

    public void expandVariable(DebugSession.Variable variable) {
        if (session != null) session.expandVariable(variable);
    }
    public void sendConsole(String command) {
        if (session != null && command != null) session.console(command);
    }
    public void toggleBreakpoint(int index, boolean enabled) {
        if (index < 0 || index >= breakpointEnabled.size()) return;
        breakpointEnabled.set(index, enabled);
        if (session != null) session.toggleBreakpoint(index, enabled);
        notifyUiChanged();
    }

    public void selectTab(int index) {
        selectedTab = Math.max(0, Math.min(4, index));
        switch (selectedTab) {
            case 0: onTabChanged("var"); break;
            case 1: onTabChanged("frame"); break;
            default: notifyUiChanged(); break;
        }
    }

    public void onTabChanged(String tab) {
        selectedTab = "var".equals(tab) ? 0 : "frame".equals(tab) ? 1 : "bkpt".equals(tab) ? 2
            : "cmd".equals(tab) ? 3 : "msg".equals(tab) ? 4 : selectedTab;
        if (session != null) {
            if ("var".equals(tab)) session.refreshVariables();
            else if ("frame".equals(tab)) session.refreshFrames();
        }
        notifyUiChanged();
    }
}
