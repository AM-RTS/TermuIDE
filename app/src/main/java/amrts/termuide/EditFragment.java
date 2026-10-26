package amrts.termuide;
import android.app.*;
import android.content.*;
import android.content.res.*;
import android.os.*;
import android.util.*;
import android.view.*;
import android.widget.*;
import cn.rbc.codeeditor.common.*;
import cn.rbc.codeeditor.lang.*;
import cn.rbc.codeeditor.lang.c.*;
import cn.rbc.codeeditor.util.*;
import cn.rbc.codeeditor.view.*;
import java.io.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;

import cn.rbc.codeeditor.lang.Formatter;
import cn.rbc.codeeditor.util.Range;

public class EditFragment extends Fragment
implements OnTextChangeListener, Formatter, OnCaretScrollListener,
ActionMode.Callback, TextEditor.OnEditedListener {
	public final static int
	TYPE_C = 1,
	TYPE_CPP = 2,
    // Language IDs occupy the low five bits; keep legacy header/saved-state bits unchanged.
    TYPE_PYTHON = 3,
    TYPE_JAVA = 4,
    TYPE_JAVASCRIPT = 8,
    TYPE_JSON = 16,
	TYPE_HEADER = 32,
	TYPE_TXT = 0,
	TYPE_BLOB = 0x80000000,
	TYPE_MASK = TYPE_HEADER - 1;
    private final static int ACTION_BASE_ID = 0x80000000;
	final static String FL = "f", TP = "t", TS = "s", VS = "v";
	private File fl;
	int type;
	private String C;
	private long lastModified;
    private Runnable languageChange;
    private Runnable autoSave;
    private boolean autoSaveWarningShown;
    private boolean sourceLoaded;
    static final Set<String> DEFTYPES = new HashSet<>(0);
    private Supplier<String> documentText;

	public EditFragment() {
	}

	public EditFragment(File path, int type) {
		fl = path;
		this.type = type;
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		final MainActivity ma = (MainActivity)getActivity();
		TextEditor editor = ma.newEditor();
		if ("d".equals(Application.theme)
            || "s".equals(Application.theme)
            && ((getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)) {
            editor.setColorScheme(ColorSchemeDark.getInstance());
        }
        editor.setPureMode(Application.pure_mode);
		DisplayMetrics dm = getResources().getDisplayMetrics();
		editor.setTypeface(Application.typeface());
		editor.setTextSize((int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, Application.textsize, dm));
		editor.setWordWrap(Application.wordwrap);
		editor.setShowNonPrinting(Application.whitespace);
		editor.setUseSpace(Application.usespace);
		editor.setTabSpaces(Application.tabsize);
        editor.setSuggestion(Application.suggestion);
        editor.setAutoCaps(Application.auto_caps);
		editor.setLayoutParams(new ViewGroup.LayoutParams(
								   ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        editor.setOnEditedListener(this);
        editor.addCaretListener(this);
        editor.setClipboardCallback(this, ClipboardPanel.CREATE_BEFORE|ClipboardPanel.CREATE_AFTER);
        Document doc = null;
        if (savedInstanceState != null) {
            String pth = (String)savedInstanceState.getCharSequence(FL);
            fl = new File(pth);
            type = savedInstanceState.getInt(TP, type);
            mVer = savedInstanceState.getInt(VS, mVer);
            doc = Application.getInstance().load(pth);
            editor.setTextSize(savedInstanceState.getInt(TS));
        }
        Integer knownType = Languages.fileType(fl.getName());
        if (knownType != null) type = knownType;
        invalidateType(editor);
        Lsp retainedServer = Application.getInstance().languageServers.find(fl);
        if (retainedServer != null) mVer = Math.max(mVer, retainedServer.documentVersion(fl));
        DocumentSessions sessions = Application.getInstance().getDocuments();
        String recovery = sessions.recoveryText(fl.getAbsolutePath());
        boolean recoveredSourceLoaded = sessions.isSourceLoaded(fl.getAbsolutePath())
            && (savedInstanceState == null || savedInstanceState.getBoolean("sourceLoaded", true));
        if (doc != null) {
            sourceLoaded = recoveredSourceLoaded;
            doc.setMetrics(editor);
            doc.resetRowTable();
            editor.setDocument(doc);
        } else {
            ma.setEditor(editor);
            try {
                if (recovery != null) {
                    doc = editor.getText();
                    doc.setText(recovery);
                    doc.resetUndos();
                    doc.markUnsaved();
                    doc.clearSpans();
                    doc.analyzeWordWrap();
                    sourceLoaded = recoveredSourceLoaded;
                } else doc = load(editor);
                if ("s" == Application.completion)
                    onOpen(doc);
            } catch (IOException fnf) {
                sourceLoaded = false;
                fnf.printStackTrace();
                HelperUtils.show(Toast.makeText(ma, getString(R.string.open_failed) + fnf.getMessage(), Toast.LENGTH_SHORT));
            }
        }
        editor.refreshDocument();
        final Document currentDocument = editor.getText();
        documentText = () -> {
            synchronized (currentDocument) { return currentDocument.toString(); }
        };
        sessions.open(fl.getAbsolutePath(), documentText, sourceLoaded);
        if (sourceLoaded && hasLsp() && "s".equals(Application.completion)) {
            Application.getInstance().languageServers.ensure(ma, fl, type);
            onOpen(currentDocument);
        }
        currentDocument.setOnTextChangeListener(this);
        editor.setEdited(sessions.isDirty(fl.getAbsolutePath()));
        if (savedInstanceState != null) {
            editor.setSelection(savedInstanceState.getInt("selectionStart"),
                Math.max(0, savedInstanceState.getInt("selectionEnd") - savedInstanceState.getInt("selectionStart")));
            int scrollX = savedInstanceState.getInt("scrollX"), scrollY = savedInstanceState.getInt("scrollY");
            editor.post(() -> {
                if (editor.getSoraEditor().isReleased()) return;
                editor.getSoraEditor().getScroller().startScroll(0, 0, scrollX, scrollY, 0);
                editor.getSoraEditor().invalidate();
            });
        }
		if ((type & TYPE_MASK) != TYPE_TXT) {
			if (hasLsp() && "s" == Application.completion)
				editor.setFormatter(this);
			editor.setAutoComplete("l" == Application.completion);
		}
		lastModified = FileHelper.lastModified(fl);
		return editor;
	}

	public String getC() {
		return C;
	}

    static boolean isExecutable(int type) {
        return Languages.canRun(type);
    }

    final boolean isText() {
        return (type & TYPE_MASK) == TYPE_TXT;
    }

    final boolean hasLsp() {
        return Languages.forType(type).getHasLsp();
    }

    final Lsp languageServer() { return Application.getInstance().languageServers.session(fl, type); }

    private void invalidateType(TextEditor editor) {
        Languages.Definition language = Languages.forType(type);
        C = language.getCompiler();
        editor.setLanguage(language.highlighting());
    }

    @Override
    public final TextEditor getView() {
        return (TextEditor) super.getView();
    }

    @Override
	public void updateCaret(int caretIndex) {
        if ("s" != Application.completion || !hasLsp()) return;
        // in lsp
        TextEditor ed = getView();
		Document text = ed.getText();
		if (ed.isSelectText2()
            || !(Character.isUnicodeIdentifierPart(text.charAt(caretIndex))
            || caretIndex>0 && Character.isUnicodeIdentifierPart(text.charAt(caretIndex-1)))) {
			text.setHighlights(null);
			return;
		}
		Lsp ls = languageServer();
        int stl = ed.getSoraEditor().getCursor().getRightLine();
        int stc = ed.getSoraEditor().getCursor().getRightColumn();
		ls.documentHighlight(fl, stl, stc);
	}

	@Override
	public void format(Document txt, int width) {
        if (!hasLsp()) return;
        flushLanguageChange();
        TextEditor ed = getView();
		int start = ed.getSelectionStart(), end = ed.getSelectionEnd();
		Lsp lsp = languageServer();
        if (start == end)
			lsp.formatting(fl, width, ed.isUseSpace());
		else {
			Range range = new Range();
			Document text = ed.getText();
	            TextPositions positions = new TextPositions(text.toString());
            int[] left = positions.position(start), right = positions.position(end);
            range.stl = left[0]; range.stc = left[1]; range.enl = right[0]; range.enc = right[1];
			lsp.rangeFormatting(fl, range, width, ed.isUseSpace());
	    }
	}

	private volatile int mVer = 0;
    int getVersion() { return mVer; }

	public void onChanged(String c, int start, boolean ins, boolean typ) {
        Lsp currentServer = Application.getInstance().languageServers.find(fl);
        mVer = Math.max(mVer + 1, currentServer == null ? 0 : currentServer.documentVersion(fl) + 1);
        Application.getInstance().getDocuments().changed(fl.getAbsolutePath());
        queueAutoSave();
        if ("s" != Application.completion || !hasLsp())
            return;
		TextEditor editor = getView();
        if (editor == null) return;
        editor.getText().setHighlights(null);
        if (languageChange != null) editor.removeCallbacks(languageChange);
        // Document callbacks run before mutation; send the completed buffer and capture the final caret.
        languageChange = () -> {
            languageChange = null;
            if (getView() != editor || editor.getSoraEditor().isReleased() || "s" != Application.completion) return;
            try {
                Lsp lsp = Application.getInstance().languageServers.find(fl);
                if (lsp == null) return;
                if (!onOpen(editor.getText())) lsp.didChange(fl, mVer, editor.getText().toString());
                if (ins && typ && c.codePointCount(0, c.length()) == 1) {
                    int[] position = new TextPositions(editor.getText().toString()).position(editor.getCaretPosition());
                    if (c.length() == 1) lsp.signatureHelpTry(fl, position[0], position[1], c.charAt(0), editor.getSigHelpPanel().isShowing());
                    lsp.completionTry(fl, position[0], position[1], c.codePointAt(0));
                }
                lsp.semanticTokensFull(fl);
            } catch (RuntimeException error) { Log.e("LSP", "Unable to synchronize document", error); }
        };
        editor.post(languageChange);
	}

    final void flushLanguageChange() {
        Runnable pending = languageChange;
        if (pending != null && getView() != null) {
            getView().removeCallbacks(pending);
            pending.run();
        }
    }

    private void cancelAutoSave() {
        if (autoSave != null && getView() != null) getView().removeCallbacks(autoSave);
        autoSave = null;
    }

    private void queueAutoSave() {
        cancelAutoSave();
        TextEditor editor = getView();
        if (!Application.auto_save || editor == null) return;
        autoSave = () -> {
            autoSave = null;
            MainActivity activity = (MainActivity)getActivity();
            if (!Application.auto_save || activity == null || getView() != editor || editor.getSoraEditor().isReleased()
                || !Application.getInstance().getDocuments().isDirty(fl.getAbsolutePath())
                || activity.isSavingClose() || activity.getPendingClosePaths().contains(fl.getAbsolutePath())
                || activity.getPendingReload() == this) return;
            if (Application.getInstance().saver.isSaving(fl.getAbsolutePath())) { queueAutoSave(); return; }
            save(true).whenComplete((clean, error) -> {
              if (error != null) {
                Log.e("AutoSave", "Unable to save source; retaining dirty document", error);
                if (!autoSaveWarningShown) {
                    autoSaveWarningShown = true;
                    HelperUtils.show(Toast.makeText(activity, R.string.auto_save_failed, Toast.LENGTH_LONG));
                }
              }
            });
        };
        editor.postDelayed(autoSave, 1000L);
    }

	@Override
	public void onResume() {
		super.onResume();
		if (isVisible())
			refresh();
	}

	private void refresh() {
		if (Application.getInstance().saver.isSaving(fl.getAbsolutePath())) return;
		long mLast = FileHelper.lastModified(fl);
		if (mLast != lastModified) {
			lastModified = mLast;
			((MainActivity)getActivity()).requestReload(this);
		} else if ("s" == Application.completion) {// TODO: delay for throttle
            Set<String> typs = Application.getInstance().hand.cacheData.get(fl.toString());
            if (typs == null) typs = DEFTYPES;
            Language lang = getView().getLanguage();
            if (typs != lang.getTypes()) {
                lang.setTypes(typs);
                getView().refreshDocument();
            }
        }
	}

    public boolean onOpen(Document doc) {
        if (hasLsp() && sourceLoaded) {
            Lsp lsp = languageServer();
            if (!lsp.didOpen(fl, Languages.forType(type).getId(), mVer, doc.toString())) return false;
            mVer = lsp.documentVersion(fl);
            doc.setDiag(null); doc.setHighlights(null);
            if (getView() != null) {
                getView().getAutoCompletePanel().dismiss(); getView().getSigHelpPanel().hide();
                getView().setSemanticTokens(Collections.emptyList());
                getView().postInvalidateOnAnimation();
            }
            MainActivity activity = (MainActivity)getActivity();
            if (activity != null) activity.notifyUiChanged();
            lsp.semanticTokensFull(fl);
            return true;
        }
        return false;
    }

	public void reloadFromDisk() {
        if (Application.getInstance().saver.isSaving(fl.getAbsolutePath())) return;
        TextEditor ed = getView();
		try {
			Document cs = load(ed);
            if (languageChange != null) ed.removeCallbacks(languageChange);
            languageChange = null;
            Application.getInstance().getDocuments().saved(fl.getAbsolutePath());
            ed.setEdited(false);
            ed.refreshDocument();
            if ("s" == Application.completion && hasLsp()) {
			    Lsp lsp = languageServer();
                lsp.didChange(fl, ++mVer, cs.toString());
                lsp.semanticTokensFull(fl);
            }
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
	}

    public void renameSelection(String name) {
        flushLanguageChange();
        TextEditor ed = getView();
        int pos = ed.getSelectionStart();
        Document doc = ed.getText();
        int[] position = new TextPositions(doc.toString()).position(pos);
        languageServer().rename(fl, position[0], position[1], name);
    }

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		if (!hidden) {
			MainActivity ma = (MainActivity)getActivity();
			if (ma == null) return;
			TextEditor ed = getView();
			ma.setEditor(ed);
			ma.setFileRunnable(Languages.canRun(type));
			invalidateType(ed);
			refresh();
			ed.requestFocus();
		}
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
        String pth = fl.getAbsolutePath();
        Application.getInstance().store(pth, getView().getText());
		outState.putCharSequence(FL, pth);
		outState.putInt(TP, type);
		outState.putInt(TS, (int)getView().getTextSize());
		outState.putInt(VS, mVer);
        outState.putBoolean("sourceLoaded", sourceLoaded);
        outState.putInt("selectionStart", getView().getSelectionStart());
        outState.putInt("selectionEnd", getView().getSelectionEnd());
        outState.putInt("scrollX", getView().getSoraEditor().getOffsetX());
        outState.putInt("scrollY", getView().getSoraEditor().getOffsetY());
	}

    @Override
    public void onDestroyView() {
        cancelAutoSave();
        flushLanguageChange();
        if (languageChange != null && getView() != null) getView().removeCallbacks(languageChange);
        languageChange = null;
        if (documentText != null) {
            Application.getInstance().getDocuments().detach(fl.getAbsolutePath(), documentText);
            documentText = null;
        }
        if (getView() != null) getView().release();
        super.onDestroyView();
    }

    public CompletableFuture<Boolean> save() { return save(false); }

    private CompletableFuture<Boolean> save(boolean automatic) {
        cancelAutoSave();
        Application app = Application.getInstance();
        String path = fl.getAbsolutePath();
        CompletableFuture<Boolean> pending = app.saver.pending(path);
        if (pending != null) return pending.handle((clean, error) -> null).thenCompose(ignored -> save(automatic));
        TextEditor editor = getView();
        // A failed read leaves an empty editor; never publish it over the source.
        if (!sourceLoaded || editor == null || editor.getSoraEditor().isReleased()) {
            CompletableFuture<Boolean> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IOException("Document unavailable"));
            return failed;
        }
        if (automatic && (!Application.auto_save || !app.getDocuments().isDirty(path)))
            return CompletableFuture.completedFuture(false);
        Document doc = editor.getText();
        String text = doc.toString();
        TextBuffer.SavePoint point = doc.captureSavePoint();
        TextBuffer.SavePoint previous = doc.markedSavePoint();
        doc.markUnsaved();
        editor.setEdited(true);
        flushLanguageChange();
        return app.saver.save(path, text, automatic ? lastModified : null, timestamp -> {
            doc.markSaved(point);
            app.sourceSaved(path, doc, timestamp);
            autoSaveWarningShown = false;
            if ("s" == Application.completion && hasLsp()) {
                try {
                    Lsp server = app.languageServers.find(fl);
                    if (server != null) server.didSave(fl);
                } catch (RuntimeException error) { Log.e("LSP", "Unable to report saved source", error); }
            }
        }).whenComplete((clean, error) -> {
            if (error != null) {
                doc.markSaved(previous);
                app.sourceSaved(path, doc, lastModified);
            }
        });
    }

    void sourceSaved(Document savedDocument, long timestamp) {
        lastModified = timestamp;
        TextEditor editor = getView();
        if (editor == null || editor.getSoraEditor().isReleased()) return;
        Document current = editor.getText();
        if (current != savedDocument) {
            if (Application.getInstance().getDocuments().isDirty(fl.getAbsolutePath())) current.markUnsaved();
            else current.markVersion();
        }
        editor.setEdited(current.getMarkedVersion() != current.getCurrentVersion());
    }

	public Document load(TextEditor ed) throws IOException {
		char[] buf = new char[1024];
        int i;
        Document doc = ed.getText();
        StringBuilder sb = new StringBuilder();
        try (Reader reader = new InputStreamReader(FileHelper.openInputStream(fl))) {
            while ((i = reader.read(buf)) != -1) sb.append(buf, 0, i);
        }
        doc.setOnTextChangeListener(null);
        doc.setText(sb);
        doc.resetUndos();
        doc.markVersion();
        doc.clearSpans();
        doc.analyzeWordWrap();
        doc.setOnTextChangeListener(this);
		sourceLoaded = true;
        Application.getInstance().getDocuments().setSourceLoaded(fl.getAbsolutePath(), true);
		return doc;
	}

	public File getFile() {
		return fl;
	}

	public static int fileType(File pwd) {
        return fileType(pwd, false);
    }

    static int fileType(File pwd, boolean recovering) {
		String _it = pwd.getName();
        Integer known = Languages.fileType(_it);
        if (known != null) return known;
        if (!recovering && Utils.isBlob(pwd))
            return TYPE_BLOB;
        return TYPE_TXT | TYPE_HEADER;
	}


    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu)
    {
        if (menu.size() == 0) {
            TypedArray tv = getActivity().getTheme().obtainStyledAttributes(
                new int[]{
                    android.R.attr.actionModeFindDrawable
                });
            menu.add(0, R.id.search, 0, R.string.find).setIcon(tv.getDrawable(0)).setShowAsActionFlags(2);
            tv.recycle();
        } else {
            menu.findItem(ClipboardPanel.ID_PASTE).setShowAsActionFlags(1);
            menu.add(0, R.id.rename, 0, R.string.rename).setShowAsActionFlags(1);
            menu.add(0, R.id.goto_, 0, R.string.goto_).setShowAsActionFlags(1);
        }
        return false;
    }

    private List<Command> tActs;
    void setActions(List<Command> actions) {
        tActs = actions;
        if (getView() != null) getView().getClipboardPanel().invalidate();
    }

    @Override
    public boolean onPrepareActionMode(ActionMode p1, Menu p2)
    {
        TextEditor te = getView();
        
        int start = te.getSelectionStart(), end = te.getSelectionEnd();
        p2.removeGroup(1);
        List<Command> acts = tActs;
        int flag = acts == null ? 2 : 1;
        p2.findItem(R.id.search).setVisible(start != end).setShowAsActionFlags(flag);
        p2.findItem(R.id.rename).setVisible(start != end && "s" == Application.completion && hasLsp()).setShowAsActionFlags(flag);
        p2.findItem(ClipboardPanel.ID_SELECTALL).setShowAsActionFlags(flag);
        p2.findItem(ClipboardPanel.ID_CUT).setShowAsActionFlags(flag);
        p2.findItem(ClipboardPanel.ID_COPY).setShowAsActionFlags(flag);
        if (acts != null) {
            for (int i = 0, l = acts.size(); i<l; i++) {
                int id = i + ACTION_BASE_ID;
                p2.add(1, id, 0, acts.get(i).title).setShowAsActionFlags(2);
            }
            p1.setTag(acts);
            tActs = null;
            return true;
        }
        List<ErrSpan> dg;
        if (hasLsp() && "s" == Application.completion && (dg = te.getText().getDiag()) != null && !dg.isEmpty()) {
            TextPositions positions = new TextPositions(te.getText().toString());
            int[] left = positions.position(start), right = positions.position(end);
            Range rng = new Range();
            rng.stl = left[0]; rng.stc = left[1]; rng.enl = right[0]; rng.enc = right[1];
            List<ErrSpan> overlapping = new ArrayList<>();
            for (ErrSpan diagnostic : dg) {
                if (diagnostic.msg == null) continue;
                try {
                    if (positions.offset(diagnostic.stl - 1, diagnostic.stc) <= end &&
                        positions.offset(diagnostic.enl - 1, diagnostic.enc) >= start) overlapping.add(diagnostic);
                } catch (IllegalArgumentException staleDiagnostic) { /* Diagnostics may precede the latest edit. */ }
            }
            if (!overlapping.isEmpty()) languageServer().codeAction(fl, rng, overlapping);
        }
        return true;
    }

    @Override
    public boolean onActionItemClicked(ActionMode p1, MenuItem p2)
    {
        int id = p2.getItemId();
        TextEditor ed = getView();
        switch (id) {
            case R.id.goto_:
                if ("s" == Application.completion && hasLsp()) {
                    languageServer().definition(fl, ed.getSoraEditor().getCursor().getRightLine(),
                        ed.getSoraEditor().getCursor().getRightColumn());
                }
                p1.finish();
                break;
            case R.id.search:
                ed.getText().setHighlights(null);
                MainActivity ma = (MainActivity)getActivity();
                ma.onOptionsItemSelected(p2);
                p1.finish();
                break;
            case R.id.rename:
                TextEditor te = getView();
                String selTxt = te.getSelectedText();
                p1.finish();
                ((MainActivity)getActivity()).requestRename(this, selTxt);
                te.clearFocus();
                break;
            default:
                id -= ACTION_BASE_ID;
                if (id < 0)
                    break;
                List<Command> cmds = (List<Command>)p1.getTag();
                if (cmds != null && id < cmds.size()) {
                    Application.getInstance().hand.executeCodeAction(cmds.get(id));
                    p1.finish();
                }
                break;
        }
        return true;
    }

    @Override
    public void onDestroyActionMode(ActionMode p1) {
        p1.setTag(null);
    }

    @Override
    public void onEdited(TextEditor te, boolean edited) {
        Application.getInstance().getDocuments().setDirty(fl.getAbsolutePath(), edited);

    }
}
