package amrts.termuide;
import android.content.*;
import android.graphics.*;
import android.net.*;
import android.os.Handler;
import android.os.Looper;
import android.preference.*;
import android.util.*;
import cn.rbc.codeeditor.util.*;
import java.util.*;
import java.io.File;
import java.io.IOException;

public class Application extends android.app.Application {
	final static String
    KEY_THEME = "theme",
	KEY_PUREMODE = "puremode",
    KEY_NAVTAB = "navtab",
	KEY_FONT = "font",
	KEY_MYFONT = "myfont",
	KEY_WORDWRAP = "wordwrap",
    KEY_AUTOSAVE = "autosave",
	KEY_WHITESPACE = "whitespace",
	KEY_TEXTSIZE = "fontsize",
	KEY_USESPACE = "usespace",
	KEY_TABSIZE = "tabsize",
    KEY_SUGGUESTION = "suggestion",
    KEY_AUTOCAPS = "autocaps",
    KEY_SYMS = "custom_sym",
	KEY_SHOW_HIDDEN = "showhidden",
	KEY_CHECKAPP = "checkapp",
	KEY_INITAPP = "initapp",
	KEY_CFLAGS = "cflags",
	KEY_COMPLETION = "completion",
	KEY_LSP_HOST = "lsphost",
	KEY_LSP_PORT = "lspport",
	KEY_TERMUX_URI = "_termux_uri";

	public static boolean pure_mode, navtab, wordwrap, whitespace, show_hidden, usespace, suggestion, auto_caps, auto_save;
	public static String theme, font, syms, cflags, completion, lsp_host;
	public static int lsp_port, textsize, tabsize;

    MainHandler hand;
    Lsp lsp;
    Uri treeUri;
    private Map<String,Document> ls;
    private static Application app;
    private static Typeface customTypeface;
    private DocumentSessions documents;
    DocumentSaver saver;
    private RunController runs;
    private boolean recoveryWarningShown;

	@Override
	public void onCreate() {
        super.onCreate();
		PreferenceManager.setDefaultValues(this, R.xml.settings, false);
		initConfs();
        ls = new ArrayMap<>();
        app = this;
        Handler main = new Handler(Looper.getMainLooper());
        documents = new DocumentSessions(new File(getNoBackupFilesDir(), "documents"), error -> {
            Log.e("Recovery", "Unable to persist document recovery data", error);
            main.post(() -> {
                if (!recoveryWarningShown) {
                    recoveryWarningShown = true;
                    HelperUtils.show(android.widget.Toast.makeText(this, R.string.recovery_failed, android.widget.Toast.LENGTH_LONG));
                }
            });
        }, 500L);
        saver = new DocumentSaver(documents);
        runs = new RunController(this, () -> documents,
            path -> hand.saveForRun(path),
            request -> hand == null ? 0 : hand.matchRunContext(request),
            request -> { if (hand != null) hand.startDebugger(request); });
	}

    @Override
    public void onTerminate() {
        runs.shutdown();
        saver.shutdown();
        if (lsp != null) lsp.end();
        try { documents.close(); } catch (IOException error) { Log.e("Recovery", "Unable to flush drafts", error); }
        ls.clear();
        super.onTerminate();
        ls = null;
        app = null;
        customTypeface = null;
    }

    void store(String key, Document obj) {
        ls.put(key, obj);
    }

    Document load(String key) {
        return ls.remove(key);
    }

    public static Application getInstance() {
        return app;
    }

    public DocumentSessions getDocuments() { return documents; }
    public RunController getRuns() { return runs; }

    void sourceSaved(String path, Document document, long timestamp) {
        if (hand != null) hand.sourceSaved(path, document, timestamp);
    }

	void saveTermuxUri(Uri uri) {
		treeUri = uri;
		SharedPreferences.Editor se = PreferenceManager.getDefaultSharedPreferences(this).edit();
		se.putString(KEY_TERMUX_URI, uri.toString());
		se.commit();
	}

    void initConfs() {
		SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        theme = sp.getString(KEY_THEME, getResources().getString(R.string.def_thm));
        pure_mode = sp.getBoolean(KEY_PUREMODE, false);
        navtab = sp.getBoolean(KEY_NAVTAB, false);
		String f = sp.getString(KEY_FONT, "m");
		if ("c".equals(f))
			f = sp.getString(KEY_MYFONT, "");
		font = f;
		textsize = integerSetting(sp, KEY_TEXTSIZE, 14, 8, 32);
        wordwrap = sp.getBoolean(KEY_WORDWRAP, false);
        auto_save = sp.getBoolean(KEY_AUTOSAVE, false);
		whitespace = sp.getBoolean(KEY_WHITESPACE, false);
		usespace = sp.getBoolean(KEY_USESPACE, false);
		tabsize = integerSetting(sp, KEY_TABSIZE, 4, 1, 16);
        suggestion = sp.getBoolean(KEY_SUGGUESTION, false);
        auto_caps = sp.getBoolean(KEY_AUTOCAPS, false);
        syms = sp.getString(KEY_SYMS, "{}();,.=\"|&![]<>#+-/*?:_");
		show_hidden = sp.getBoolean(KEY_SHOW_HIDDEN, true);
        cflags = sp.getString(KEY_CFLAGS, "-lm -Wall");
		completion = sp.getString(KEY_COMPLETION, "s").intern();
		lsp_host = sp.getString(KEY_LSP_HOST, "127.0.0.1");
		lsp_port = integerSetting(sp, KEY_LSP_PORT, 48455, 1, 65535);
		String uri = sp.getString(KEY_TERMUX_URI, "");
		if (!uri.isEmpty())
			treeUri = Uri.parse(uri);
    }

    private static int integerSetting(SharedPreferences prefs, String key, int fallback, int minimum, int maximum) {
        Object stored = prefs.getAll().get(key);
        try {
            int value = stored instanceof String ? Integer.parseInt((String)stored) : fallback;
            return value >= minimum && value <= maximum ? value : fallback;
        } catch (NumberFormatException error) { return fallback; }
    }

	static Typeface typeface() {
		try {
			return "n".equals(font) ? Typeface.SANS_SERIF
				: "s".equals(font) ? Typeface.SERIF
				: "m".equals(font) ? Typeface.MONOSPACE
				: customTypeface == null ? (customTypeface = Typeface.createFromFile(font)) : customTypeface;
		} catch (RuntimeException re) {
			return Typeface.MONOSPACE;
		}
	}

    static void clearCache() {
        customTypeface = null;
    }
}
