package cn.rbc.termuc;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.*;
import android.graphics.*;
import android.net.*;
import android.os.*;
import android.provider.*;
import android.util.*;
import android.view.*;
import android.view.ViewTreeObserver.*;
import android.view.inputmethod.*;
import android.widget.*;
import cn.rbc.codeeditor.lang.*;
import cn.rbc.codeeditor.util.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static android.Manifest.permission.*;

public class MainActivity extends androidx.activity.ComponentActivity implements HeaderAdapter.OnChangedListener, Utils.ComposeDialogHost {

	public final static int SETTING = 0, ACCESS_FILE = 1, SHOW_FLOATING = 2, REQ_FOLDER = 3;
	public final static String PWD = "p", FILES = "o", TESTAPP = "t", INITAPP = "i";
	private HeaderAdapter hda;
	private FileAdapter adp;
	private EditFragment lastFrag = null;
	private boolean runVisible, deleting;
    private File pwd, prj, root;
    private TextEditor codeEditor;
	private String pendingProjectPath;
    private DebugPanel panel;
    private int reqCode, selectedIndex = -1;
    private Runnable uiRefresh;
    private Runnable searchUi;
    private EditFragment pendingReload, pendingRename;
    private String pendingRenameText;
    private boolean installPrompt, installPromptNoRemind, initPrompt, initPromptNoRemind;
    private List<String> pendingClosePaths = Collections.emptyList();
    private static final int CLOSE_FILE = 1, CLOSE_PROJECT = 2, OPEN_PROJECT = 3;
    private int pendingCloseKind;
    private String pendingCloseTarget;
    private Set<String> approvedDiscards = Collections.emptySet();
    private boolean savingClose;
    private boolean runtimeHostReady;
    private ProjectClose projectClose;
    private static final class ProjectClose {
        final String root;
        final List<String> tabs;
        final List<DocumentSession> revisions;
        final Set<String> discards;
        final CompletableFuture<Boolean> result;
        ProjectClose(String root, List<String> tabs, List<DocumentSession> revisions,
                Set<String> discards, CompletableFuture<Boolean> result) {
            this.root = root; this.tabs = tabs; this.revisions = revisions;
            this.discards = discards; this.result = result;
        }
    }


	private void envInit(SharedPreferences pref) {
		pwd = new File(pref.getString(PWD, Utils.ROOT.getPath()));
		Application app = Application.getInstance();
        if (FileHelper.isTermuxFile(pwd) && app.treeUri == null) pwd = Utils.ROOT;
		if (app.treeUri != null && FileHelper.isTermuxFile(pwd)) {
			root = new File(DocumentsContract.getTreeDocumentId(app.treeUri));
		} else {
			root = Utils.ROOT;
		}
		for (File f = pwd; f != null && !f.equals(root); f = f.getParentFile()) {
			if (FileHelper.isFile(new File(f, Project.PROJ))) {
				prj = f;
				break;
			}
		}
		if (app.lsp == null) {
            app.lsp = new Lsp();
			app.hand = new MainHandler(this);
		} else
			app.hand.updateActivity(this);
	}

	private void showFrag(Fragment frag) {
		if (frag == lastFrag)
			return;
		FragmentTransaction mTans = getFragmentManager().beginTransaction();
		if (lastFrag != null)
			mTans.hide(lastFrag);
		mTans.show(frag).commit();
		lastFrag = (EditFragment)frag;
	}

	String getTag(int idx) {
		return hda.getItem(idx);
	}

    @Override
    protected void onCreate(Bundle savedInstanceState) {
		SharedPreferences pref = getPreferences(MODE_PRIVATE);
		envInit(pref);
		Utils.setNightMode(this, Application.theme);
        Configuration conf = getResources().getConfiguration();
        if (conf.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
        super.onCreate(savedInstanceState);
		hda = new HeaderAdapter();
		hda.setOnChangedListener(this);
		adp = new FileAdapter(pwd);
        panel = new DebugPanel(this);
		setContentView(TermuCCompose.mainView(this));
		final int sdk = android.os.Build.VERSION.SDK_INT;
		String[] s;
		if (sdk >= android.os.Build.VERSION_CODES.R) {
			if (!Environment.isExternalStorageManager()) {
				Intent it = new Intent();
				it.setAction(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
				it.setData(Uri.parse("package:" + getPackageName()));
				startActivityForResult(it, ACCESS_FILE);
			}
			s = new String[]{Utils.PERM_EXEC};
		} else
			s = new String[]{
					Utils.PERM_EXEC,
					READ_EXTERNAL_STORAGE,
					WRITE_EXTERNAL_STORAGE,
				};
		requestPermissions(s, PackageManager.PERMISSION_GRANTED);
		if (pref.getBoolean(INITAPP, true))
			Utils.initBack(this, false);
        if (pref.getBoolean(TESTAPP, true))
			Utils.testApp(this, false);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        String selected = getSelectedFilePath();
        DocumentSessions sessions = Application.getInstance().getDocuments();
        for (DocumentSession session : sessions.getState().getValue()) {
            String path = session.getPath();
            if (!hda.contains(path) && (session.getDirty() || FileHelper.isFile(new File(path)))) {
                openFile(new File(path));
            }
        }
        getFragmentManager().executePendingTransactions();
        if (selected != null && hda.contains(selected)) selectItem(hda.indexOf(selected));
        else if (!hda.isEmpty()) selectItem(hda.getCount() - 1);
        if (savedInstanceState != null) {
            pendingProjectPath = savedInstanceState.getString("pendingProject");
            pendingRename = (EditFragment)getFragmentManager().findFragmentByTag(savedInstanceState.getString("pendingRename", ""));
            pendingRenameText = savedInstanceState.getString("pendingRenameText");
            pendingReload = (EditFragment)getFragmentManager().findFragmentByTag(savedInstanceState.getString("pendingReload", ""));
            ArrayList<String> closing = savedInstanceState.getStringArrayList("pendingClosePaths");
            if (closing != null) pendingClosePaths = closing;
            pendingCloseKind = savedInstanceState.getInt("pendingCloseKind");
            pendingCloseTarget = savedInstanceState.getString("pendingCloseTarget");
            installPrompt = savedInstanceState.getBoolean("installPrompt");
            installPromptNoRemind = savedInstanceState.getBoolean("installPromptNoRemind");
            initPrompt = savedInstanceState.getBoolean("initPrompt");
            initPromptNoRemind = savedInstanceState.getBoolean("initPromptNoRemind");
            notifyUiChanged();
        }
        runtimeHostReady = true;
        Object retained = getLastCustomNonConfigurationInstance();
        if (retained instanceof ProjectClose) {
            projectClose = (ProjectClose)retained; savingClose = true;
            projectClose.result.whenComplete((saved, error) -> finishPendingProjectClose());
        }
        getLifecycle().addObserver((androidx.lifecycle.LifecycleEventObserver)(source, event) -> {
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) finishPendingProjectClose();
        });
        Application.getInstance().getRuns().onHostReady();
    }

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == PackageManager.PERMISSION_GRANTED) {
			for (int i:grantResults)
				if (i!=PackageManager.PERMISSION_GRANTED) {
					toast(getText(R.string.request_failed));
					break;
				}
			if (grantResults.length>1 && grantResults[1]==PackageManager.PERMISSION_GRANTED)
				refresh();
		}
	}

    private void refresh() {
		adp.setPath(pwd);
		notifyUiChanged();
    }

	void notifyUiChanged() {
		Runnable listener = uiRefresh;
		if (listener != null) listener.run();
	}

	void setUiRefresh(Runnable listener) {
		uiRefresh = listener;
	}

	public void setSearchUi(Runnable listener) {
		searchUi = listener;
	}

	public EditFragment getPendingReload() { return pendingReload; }
	public EditFragment getPendingRename() { return pendingRename; }
	public String getPendingRenameText() { return pendingRenameText; }

	public void requestReload(EditFragment fragment) {
		pendingReload = fragment;
		notifyUiChanged();
	}

	public void dismissReload() {
		pendingReload = null;
		notifyUiChanged();
	}

	public void confirmReload() {
		EditFragment fragment = pendingReload;
        if (fragment != null && Application.getInstance().saver.isSaving(fragment.getFile().getAbsolutePath())) {
            toast(getString(R.string.saving)); return;
        }
		pendingReload = null;
		if (fragment != null) fragment.reloadFromDisk();
		notifyUiChanged();
	}

	public void requestRename(EditFragment fragment, String selection) {
		pendingRename = fragment;
		pendingRenameText = selection;
		notifyUiChanged();
	}

	public void dismissRename() {
		pendingRename = null;
		pendingRenameText = null;
		notifyUiChanged();
	}

	public void confirmRename(String name) {
		EditFragment fragment = pendingRename;
		pendingRename = null;
		pendingRenameText = null;
		if (fragment != null) fragment.renameSelection(name);
		notifyUiChanged();
	}

	@Override public void showTermuxInstallPrompt(boolean showNeverRemind) {
		installPrompt = true;
		installPromptNoRemind = showNeverRemind;
		notifyUiChanged();
	}
	@Override public void showTermuxInitPrompt(boolean showAlreadyInitialized) {
		initPrompt = true;
		initPromptNoRemind = showAlreadyInitialized;
		notifyUiChanged();
	}
	public boolean isInstallPromptOpen() { return installPrompt; }
	public boolean isInstallPromptNoRemind() { return installPromptNoRemind; }
	public boolean isInitPromptOpen() { return initPrompt; }
	public boolean isInitPromptNoRemind() { return initPromptNoRemind; }
	public void resolveInstallPrompt(boolean launchInstaller, boolean neverRemind) {
		installPrompt = false;
		if (launchInstaller) Utils.startTermuxInstall(this);
		else if (neverRemind) Utils.skipTermuxInstallReminder(this);
		notifyUiChanged();
	}
	public void resolveInitPrompt(boolean initialize, boolean alreadyInitialized) {
		initPrompt = false;
		if (initialize) Utils.copyJump(this);
		else if (alreadyInitialized) Utils.skipTermuxInitReminder(this);
		notifyUiChanged();
	}

	public void onFileSelected(String _it) {
		if ("..".equals(_it)) {
			if (FileHelper.isFile(new File(pwd, Project.PROJ)))
				prj = null;
			pwd = pwd.getParentFile();
		} else {
			File f = new File(pwd, _it);
			if (FileHelper.isFile(f)) {
				openFile(f);
				if (Project.rootPath == null && prj!=null)
					pendingProjectPath = prj.getAbsolutePath();
				if (pendingProjectPath != null) notifyUiChanged();
				return;
			}
			File trj = new File(f, Project.PROJ);
			if (FileHelper.isFile(trj))
				prj = f;
			pwd = f;
		}
		refresh();
	}

    public void performAction(int id) {
        RunCommands.Action action = id == R.id.run ? RunCommands.Action.RUN : id == R.id.debug ? RunCommands.Action.DEBUG
            : id == R.id.build ? RunCommands.Action.BUILD : id == R.id.compile ? RunCommands.Action.COMPILE : null;
        if (action != null) {
            if (action == RunCommands.Action.DEBUG && !canDebug()) return;
            RunConfiguration configuration = new RunConfiguration();
            if (action == RunCommands.Action.RUN || action == RunCommands.Action.DEBUG) {
                try {
                    configuration = getRunConfiguration();
                    String entry = configuration.entryPath(getRunConfigurationScope(), getSelectedFilePath());
                    if (entry != null && !entry.equals(getSelectedFilePath())) {
                        if (getFragmentManager().findFragmentByTag(entry) == null &&
                            !FileHelper.isFile(new File(entry)) && !Application.getInstance().getDocuments().isDirty(entry)) {
                            toast(getString(R.string.run_entry_failed));
                            return;
                        }
                        if (!openFile(new File(entry))) { toast(getString(R.string.run_entry_failed)); return; }
                        getFragmentManager().executePendingTransactions();
                        selectItem(selectedIndex);
                    }
                } catch (IllegalArgumentException error) {
                    toast(getString(R.string.run_config_invalid));
                    return;
                }
            }
            List<Integer> marks = new ArrayList<>();
            if (codeEditor != null) for (int i = 0; i < codeEditor.getText().getMarksCount(); i++) marks.add(codeEditor.getText().getMark(i));
            Application.getInstance().getRuns().start(new RunCommands.Request(action,
                getSelectedFilePath(), lastFrag == null ? EditFragment.TYPE_TXT : lastFrag.type,
                pwd.getAbsolutePath(), Project.snapshot(), Application.cflags, Collections.unmodifiableList(marks), configuration));
            return;
        }
        if (id == R.id.save && lastFrag != null) {
            EditFragment fragment = lastFrag;
            fragment.save().whenComplete((clean, error) -> {
                if (isDestroyed() || isFinishing()) return;
                if (error != null) { Log.e("Save", "Unable to save source", error); toast(getString(R.string.save_failed)); }
                else if (!clean) toast(getString(R.string.save_newer_changes));
                else toast(getText(R.string.saved));
            });
            return;
        }
        performSavedAction(id);
    }

    int matchRunContext(RunCommands.Request request) {
        if (!runtimeHostReady) return 0;
        return Objects.equals(request.getPath(), getSelectedFilePath())
            && Objects.equals(request.getProject().rootPath, Project.rootPath)
            && Objects.equals(request.getDirectory(), pwd.getAbsolutePath()) && matchesRunConfiguration(request) ? 1 : -1;
    }

    public String getRunConfigurationScope() {
        return Project.rootPath != null ? Project.rootPath : pwd.getAbsolutePath();
    }

    public RunConfiguration getRunConfiguration() {
        return RunConfiguration.load(this, getRunConfigurationScope());
    }

    public boolean saveRunConfiguration(String scope, RunConfiguration configuration) {
        if (!scope.equals(getRunConfigurationScope())) throw new IllegalStateException(getString(R.string.run_config_changed));
        if (!RunConfiguration.save(this, scope, configuration)) return false;
        Application.getInstance().getRuns().onConfigurationChanged();
        notifyUiChanged();
        return true;
    }

    private boolean matchesRunConfiguration(RunCommands.Request request) {
        if (request.getAction() != RunCommands.Action.RUN && request.getAction() != RunCommands.Action.DEBUG) return true;
        try { return request.getConfiguration().equals(getRunConfiguration()); }
        catch (IllegalArgumentException error) { return false; }
    }

    void startDebugger(RunCommands.Request request) {
        if (panel == null || request.getPath() == null) return;
        String name = new File(request.getPath()).getName();
        for (int line : request.getBreakpoints()) panel.addBkpt(name, line);
        panel.connect();
    }

    private void performSavedAction(int id) {
        if (id == R.id.exstorage) {
			pwd = root = Utils.ROOT;
			prj = null;
			refresh();
			return;
		} else if (id == R.id.tmxstorage) {
			Uri u = Application.getInstance().treeUri;
			if (u == null) {
				Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
				startActivityForResult(it, REQ_FOLDER);
			} else {
				pwd = root = new File(DocumentsContract.getTreeDocumentId(u));
				prj = null;
				refresh();
			}
			return;
		} else if (id == R.id.undo && codeEditor != null) {
			codeEditor.undo();
		} else if (id == R.id.redo && codeEditor != null) {
			codeEditor.redo();
		} else if (id == R.id.search) {
			if (searchUi != null && codeEditor != null) searchUi.run();
		} else if (id == R.id.close && selectedIndex >= 0) {
			closeFile(selectedIndex);
		} else if (id == R.id.prj_attr && Project.rootPath != null) {
			openFile(new File(Project.rootPath, Project.PROJ));
		} else if (id == R.id.prj_close && Project.rootPath != null) {
            closeProject();
		} else if (id == R.id.settings) {
			startActivityForResult(new Intent(this, SettingsActivity.class), SETTING);
		}
	}

    private void closeProject() {
        if (requestCloseIfDirty(new ArrayList<>(hda), CLOSE_PROJECT, null)) return;
        // A restored close prompt can outlive the process-local project configuration.
        if (Project.rootPath == null) { finishProjectClose(); return; }
        List<String> tabs = new ArrayList<>(hda);
        String configuration = new File(Project.rootPath, Project.PROJ).getAbsolutePath();
        if (Application.getInstance().saver.isSaving(configuration)) { toast(getString(R.string.saving)); return; }
        projectClose = new ProjectClose(Project.rootPath, tabs, documentState(tabs),
            new HashSet<>(approvedDiscards), Project.save(tabs));
        savingClose = true;
        notifyUiChanged();
        projectClose.result.whenComplete((saved, error) -> finishPendingProjectClose());
    }

    @Override public Object onRetainCustomNonConfigurationInstance() { return projectClose; }

    private void finishPendingProjectClose() {
        ProjectClose close = projectClose;
        if (close == null || !close.result.isDone() || isDestroyed() || isFinishing()
                || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                || getFragmentManager().isStateSaved()) return;
        projectClose = null; savingClose = false;
        try {
            if (!Boolean.TRUE.equals(close.result.join())) throw new CompletionException(new IOException("Project save failed"));
        } catch (CompletionException error) {
            Log.e("Project", "Unable to save project configuration", error);
            toast(getString(R.string.save_failed)); notifyUiChanged(); return;
        }
        if (!Objects.equals(close.root, Project.rootPath) || !close.tabs.equals(hda)
                || close.tabs.stream().anyMatch(path -> Application.getInstance().saver.isSaving(path))
                || !close.revisions.equals(documentState(close.tabs))) {
            toast(getString(R.string.save_newer_changes)); notifyUiChanged(); return;
        }
        approvedDiscards = close.discards;
        try { finishProjectClose(); } finally { approvedDiscards = Collections.emptySet(); }
    }

    private List<DocumentSession> documentState(List<String> paths) {
        List<DocumentSession> state = new ArrayList<>();
        for (DocumentSession session : Application.getInstance().getDocuments().getState().getValue())
            if (paths.contains(session.getPath())) state.add(session);
        return state;
    }

    private void finishProjectClose() {
        Project.close();
        FragmentManager fm = getFragmentManager();
        FragmentTransaction transaction = fm.beginTransaction();
        Lsp lsp = Application.getInstance().lsp;
        for (int i = 0; i < hda.getCount(); i++) {
            String path = hda.getItem(i);
            closeDocumentSession(path);
            transaction.remove(fm.findFragmentByTag(path));
            lsp.didClose(new File(path));
        }
        hda.clear();
        transaction.commit();
        lastFrag = null;
        codeEditor = null;
        selectedIndex = -1;
        runVisible = false;
        lsp.end();
        notifyUiChanged();
    }

	void setFileRunnable(boolean exec) {
		runVisible = Project.rootPath == null ? exec : true;
		notifyUiChanged();
	}

    void selectItem(int pos) {
        if (pos < 0 || pos >= hda.getCount()) return;
        selectedIndex = pos;
        Fragment fragment = getFragmentManager().findFragmentByTag(hda.getItem(pos));
        if (fragment != null) {
            showFrag(fragment);
            lastFrag = (EditFragment)fragment;
            codeEditor = lastFrag.getView();
            setFileRunnable(EditFragment.isExecutable(lastFrag.type));
        }
        notifyUiChanged();
    }

    int getSelectedItem() {
        return selectedIndex;
    }

    int getItemCount() {
        return hda.getCount();
    }


	boolean openFile(File f) {
		String _it = f.getAbsolutePath();
		int _i;
		if (getFragmentManager().findFragmentByTag(_it) != null) {
			for (_i = hda.getCount() - 1; _i >= 0 && !_it.equals(hda.getItem(_i)); _i--);
		} else if ((_i = EditFragment.fileType(f, Application.getInstance().getDocuments().isDirty(_it))) >= 0) {
            Application app = Application.getInstance();
            Lsp lsp;
            EditFragment ef = new EditFragment(f, _i);
			if ("s" == Application.completion && ef.hasLsp() && (lsp=app.lsp).isEnded()) {
				lsp.end();
                try {
                    lsp.start(this, app.hand);
                    lsp.initialize(Project.rootPath);
                } catch (SecurityException | IllegalStateException | IllegalArgumentException error) {
                    Log.w("LSP", "Language server unavailable; the document remains editable", error);
                }
			}
			FragmentTransaction mts = getFragmentManager().beginTransaction();
			mts.add(R.id.editFrag, ef, _it);
			if (lastFrag != null)
				mts.hide(lastFrag);
			mts.show(ef).commit();
			lastFrag = ef;
			hda.add(_it);
			setFileRunnable(EditFragment.isExecutable(_i));
			_i = hda.getCount() - 1;
		}
		boolean b = _i>=0;
		if (b) {
            selectItem(_i);
		}
		notifyUiChanged();
		return b;
	}

	public void confirmOpenProject() {
		String projectPath = pendingProjectPath;
		pendingProjectPath = null;
		if (projectPath == null) return;
        List<String> closing = new ArrayList<>(hda);
        closing.remove(getSelectedFilePath());
        if (requestCloseIfDirty(closing, OPEN_PROJECT, projectPath)) return;
		File config = new File(projectPath, Project.PROJ);
		if (!FileHelper.isFile(config)) {
			toast(getString(R.string.open_failed));
			return;
		}
		try {
			List<String> opens = new ArrayList<>();
			Project.load(config, opens);
			openProjFiles(opens, selectedIndex >= 0 ? hda.getItem(selectedIndex) : null);
			setFileRunnable(true);
		} catch (IOException e) {
			e.printStackTrace();
			toast(getString(R.string.open_failed));
		}
		notifyUiChanged();
	}

	private void openProjFiles(List<String> opens, String pth) {
		FragmentManager fm = getFragmentManager();
		FragmentTransaction fts = fm.beginTransaction();
        Lsp lsp = Application.getInstance().lsp;
		for (int i=0;i < hda.getCount();) {
			String str = hda.getItem(i);
			if (str.equals(pth)) {
				i++;
			} else {
                closeDocumentSession(str);
				hda.remove(str);
				fts.remove(fm.findFragmentByTag(str));
				lsp.didClose(new File(str));
			}
		}
		selectedIndex = hda.indexOf(pth);
		boolean s = "s" == Application.completion;
		if (s && lsp.isEnded()) {
			lsp.start(this, Application.getInstance().hand);
			lsp.initialize(Project.rootPath);
		}
		int tp;
		EditFragment ef = null;
		for (String i:opens) {
			if (i.equals(pth)) {
                if (s) {
                    EditFragment last = lastFrag;
                    last.onOpen(last.getView().getText());
                }
				continue;
			}
			File f = new File(i);
			if (FileHelper.isFile(f) && (tp = EditFragment.fileType(f)) >= 0) {
				ef = new EditFragment(f, tp);
				fts.add(R.id.editFrag, ef, i);
				fts.hide(ef);
				hda.add(i);
			}
		}
		if (ef!=null) {
			selectItem(hda.getCount()-1);
			if (ef!=lastFrag && lastFrag!=null) {
				fts.hide(lastFrag);
				lastFrag = ef;
			}
			fts.show(ef);
		}
		fts.commit();
		notifyUiChanged();
	}

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        performAction(menuItem.getItemId());
        return true;
    }

    public void closeFile(int pos) {
        if (pos < 0 || pos >= hda.getCount()) return;
        String file = hda.getItem(pos);
        if (requestCloseIfDirty(Collections.singletonList(file), CLOSE_FILE, file)) return;
        closeDocumentSession(file);
        FragmentManager fm = getFragmentManager();
        FragmentTransaction tran = fm.beginTransaction();
        EditFragment frg = (EditFragment) fm.findFragmentByTag(file);
        boolean wasSelected = pos == selectedIndex;
        hda.remove(file);
        if (frg != null) tran.remove(frg);
        if (wasSelected && hda.getCount() > 0) {
            selectedIndex = Math.max(0, pos - 1);
            lastFrag = (EditFragment)fm.findFragmentByTag(hda.getItem(selectedIndex));
            if (lastFrag != null) tran.show(lastFrag);
        } else if (pos < selectedIndex) {
            selectedIndex--;
        }
        tran.commit();
        Application app = Application.getInstance();
        if (frg != null) app.lsp.didClose(frg.getFile());
        app.hand.cacheData.remove(file);
        app.load(file);
        if (hda.getCount() == 0) {
            selectedIndex = -1;
            lastFrag = null;
            codeEditor = null;
            runVisible = false;
        } else if (wasSelected && lastFrag != null) {
            codeEditor = lastFrag.getView();
            setFileRunnable(EditFragment.isExecutable(lastFrag.type));
        }
        notifyUiChanged();
    }

    private boolean requestCloseIfDirty(List<String> paths, int kind, String target) {
        if (savingClose) { toast(getString(R.string.saving)); return true; }
        for (String path : paths) if (Application.getInstance().saver.isSaving(path)) {
            toast(getString(R.string.saving)); return true;
        }
        List<String> dirty = new ArrayList<>();
        DocumentSessions sessions = Application.getInstance().getDocuments();
        for (String path : paths) if (sessions.isDirty(path) && !approvedDiscards.contains(path)) dirty.add(path);
        if (dirty.isEmpty()) return false;
        pendingClosePaths = dirty;
        pendingCloseKind = kind;
        pendingCloseTarget = target;
        notifyUiChanged();
        return true;
    }

    public List<String> getPendingClosePaths() { return pendingClosePaths; }

    private void closeDocumentSession(String path) {
        DocumentSessions sessions = Application.getInstance().getDocuments();
        if (approvedDiscards.contains(path)) sessions.discard(path);
        else sessions.closeDocument(path);
    }

    public void cancelCloseDocuments() {
        if (savingClose) return;
        pendingClosePaths = Collections.emptyList();
        pendingCloseKind = 0;
        pendingCloseTarget = null;
        notifyUiChanged();
    }

    public void resolveCloseDocuments(boolean save) {
        if (savingClose || pendingClosePaths.isEmpty()) return;
        for (String path : pendingClosePaths) if (Application.getInstance().saver.isSaving(path)) {
            toast(getString(R.string.saving)); return;
        }
        if (save) {
            List<CompletableFuture<Boolean>> writes = new ArrayList<>();
            for (String path : pendingClosePaths) {
                EditFragment fragment = (EditFragment)getFragmentManager().findFragmentByTag(path);
                if (fragment == null || fragment.getView() == null) { toast(getString(R.string.save_failed)); return; }
            }
            savingClose = true;
            for (String path : pendingClosePaths)
                writes.add(((EditFragment)getFragmentManager().findFragmentByTag(path)).save());
            notifyUiChanged();
            CompletableFuture.allOf(writes.toArray(new CompletableFuture[0])).whenComplete((ignored, error) -> {
                savingClose = false;
                if (isDestroyed() || isFinishing()) return;
                if (error != null) { toast(getString(R.string.save_failed)); notifyUiChanged(); return; }
                for (String path : pendingClosePaths) if (Application.getInstance().getDocuments().isDirty(path)) {
                    toast(getString(R.string.save_newer_changes)); notifyUiChanged(); return;
                }
                finishCloseDocuments(true);
            });
        } else finishCloseDocuments(false);
    }

    public boolean isSavingClose() { return savingClose; }

    private void finishCloseDocuments(boolean save) {
        int kind = pendingCloseKind;
        String target = pendingCloseTarget;
        if (!save) approvedDiscards = new HashSet<>(pendingClosePaths);
        cancelCloseDocuments();
        try {
            if (kind == CLOSE_FILE) closeFile(hda.indexOf(target));
            else if (kind == CLOSE_PROJECT) closeProject();
            else if (kind == OPEN_PROJECT) { pendingProjectPath = target; confirmOpenProject(); }
        } finally { approvedDiscards = Collections.emptySet(); }
    }

    public void inputKey(String key) {
        if (codeEditor == null) return;
        if ("⇥".equals(key)) codeEditor.sendPrintableChar(Language.TAB);
        else codeEditor.paste(key);
    }

    public String getPendingProjectPath() {
        return pendingProjectPath;
    }

    public void dismissProjectPrompt() {
        pendingProjectPath = null;
        notifyUiChanged();
    }

    public List<String> getDirectoryItems() {
        ArrayList<String> items = new ArrayList<>(adp.getCount());
        for (int i = 0; i < adp.getCount(); i++) items.add(adp.getItem(i).name);
        return items;
    }

	public boolean isOpenableFile(String name) {
		File file = new File(pwd, name);
		return FileHelper.isFile(file) && EditFragment.fileType(file) >= 0;
	}

    public int getDirectoryIcon(int index) {
        return adp.getItem(index).icon;
    }

    public String getDirectoryPath() {
        return pwd.getAbsolutePath();
    }

    public List<String> getOpenFilePaths() {
        ArrayList<String> files = new ArrayList<>(hda.getCount());
        for (String path : hda) files.add(path);
        return files;
    }

    public String getSelectedFilePath() {
        return selectedIndex >= 0 && selectedIndex < hda.getCount() ? hda.getItem(selectedIndex) : null;
    }

    public boolean hasProject() {
        return Project.rootPath != null;
    }

    public boolean canDebug() {
        try {
            String entry = getRunConfiguration().entryPath(getRunConfigurationScope(), getSelectedFilePath());
            EditFragment target = entry == null ? null : (EditFragment)getFragmentManager().findFragmentByTag(entry);
            return target != null && target.hasLsp() && (hasProject() || EditFragment.isExecutable(target.type))
                && target.getView() != null && target.getView().getText().getMarksCount() > 0;
        } catch (IllegalArgumentException error) { return false; }
    }

    public boolean canCompile() {
        return lastFrag != null && !lastFrag.isText();
    }

    public boolean isRunVisible() {
        if (runVisible) return true;
        try { return !getRunConfiguration().getEntryFile().isEmpty(); }
        catch (IllegalArgumentException error) { return false; }
    }

    public boolean isDeleting() {
        return deleting;
    }

    public void renameFile(String from, String to) {
        if (Application.getInstance().saver.isSaving()) { toast(getString(R.string.saving)); return; }
        File source = new File(from);
        if (!source.isAbsolute()) source = new File(pwd, from);
        if (!to.isEmpty() && FileHelper.rename(source, to)) refresh();
        else toast(getString(R.string.rename_failed));
    }

    public void deleteBrowserEntry(String name) {
        if (Application.getInstance().saver.isSaving()) { toast(getString(R.string.saving)); return; }
        final File target = new File(name).isAbsolute() ? new File(name) : new File(pwd, name);
        deleting = true;
        notifyUiChanged();
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean removed = FileHelper.removeFiles(target);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        deleting = false;
                        if (removed) {
                            toast(getText(R.string.deleted));
                            refresh();
                        } else notifyUiChanged();
                    }
                });
            }
        }).start();
    }

    public void createFile(String name, boolean directory) {
        if (name.isEmpty()) {
            toast(getText(R.string.empty_name));
            return;
        }
        try {
            FileHelper.createFile(new File(pwd, name), directory);
            refresh();
        } catch (IOException e) {
            e.printStackTrace();
            toast(e.getMessage());
        }
    }

    public void createProject(String name, String template, boolean withClangd, boolean withFormat) {
        if (name.isEmpty()) {
            toast(getText(R.string.empty_name));
            return;
        }
        File directory = new File(pwd, name);
        if (!Utils.extractTemplate(this, template, directory)) return;
        try {
            if (withClangd) Utils.dumpFile(getAssets().open("cld"), new File(directory, ".clangd"));
            if (withFormat) Utils.dumpFile(getAssets().open("fmt"), new File(directory, ".clang-format"));
        } catch (IOException e) {
            e.printStackTrace();
        }
        pwd = prj = directory;
        setFileRunnable(true);
        refresh();
    }

    @Override
    protected void onRestoreInstanceState(Bundle bundle) {
        super.onRestoreInstanceState(bundle);
        pwd = new File(bundle.getString(PWD));
        adp.setPath(pwd);
		List<String> files = bundle.getStringArrayList(FILES);
		if (files != null) {
			FragmentManager fm = getFragmentManager();
			for (String path : files) hda.add(path);
			selectedIndex = bundle.getInt("selected", 0);
			if (selectedIndex < 0 || selectedIndex >= hda.getCount()) selectedIndex = 0;
			if (!hda.isEmpty()) selectItem(selectedIndex);
		}
		notifyUiChanged();
    }

    @Override
    public void onSaveInstanceState(Bundle bundle) {
        bundle.putString(PWD, pwd.getPath());
		bundle.putInt("selected", selectedIndex);
		ArrayList<String> al = new ArrayList<>(hda.getCount());
		for (String s:hda)
			al.add(s);
		bundle.putStringArrayList(FILES, al);
        bundle.putString("pendingProject", pendingProjectPath);
        bundle.putString("pendingRename", pendingRename == null ? null : pendingRename.getFile().getAbsolutePath());
        bundle.putString("pendingRenameText", pendingRenameText);
        bundle.putString("pendingReload", pendingReload == null ? null : pendingReload.getFile().getAbsolutePath());
        bundle.putStringArrayList("pendingClosePaths", new ArrayList<>(pendingClosePaths));
        bundle.putInt("pendingCloseKind", pendingCloseKind);
        bundle.putString("pendingCloseTarget", pendingCloseTarget);
        bundle.putBoolean("installPrompt", installPrompt);
        bundle.putBoolean("installPromptNoRemind", installPromptNoRemind);
        bundle.putBoolean("initPrompt", initPrompt);
        bundle.putBoolean("initPromptNoRemind", initPromptNoRemind);
        super.onSaveInstanceState(bundle);
    }

	@Override
	protected void onResume() {
		super.onResume();
		refresh();
        if (reqCode != SHOW_FLOATING) {
            panel.clean();
            reqCode = 0;
        }
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		switch (requestCode) {
			case SETTING:
				if (resultCode == RESULT_OK) {
					boolean s = "s" == Application.completion;
                    Application app = Application.getInstance();
                    Lsp lsp = app.lsp;
					boolean chg = s==lsp.isEnded();
					if (chg) {
						lsp.end();
						if (s) {
                            try {
                                lsp.start(this, app.hand);
                                lsp.initialize(Project.rootPath);
                            } catch (RuntimeException error) {
                                android.util.Log.e("LSP", "Unable to start language server", error);
                                toast(error.toString());
                            }
						}
						chg = s;
					}
					FragmentManager fm = getFragmentManager();
					Typeface tf = Application.typeface();
					for (int i=hda.getCount() - 1;i >= 0;i--) {
						EditFragment f = (EditFragment)fm.findFragmentByTag(hda.getItem(i));
						TextEditor ed = f.getView();
                        ed.setPureMode(Application.pure_mode);
						ed.setFormatter(s ? f : null);
						ed.setAutoComplete("l" == Application.completion);
						ed.setTypeface(tf);
						ed.setWordWrap(Application.wordwrap);
						ed.setShowNonPrinting(Application.whitespace);
						ed.setUseSpace(Application.usespace);
						ed.setTabSpaces(Application.tabsize);
                        ed.setSuggestion(Application.suggestion);
                        ed.setAutoCaps(Application.auto_caps);
                        if (chg) f.onOpen(ed.getText());
					}
					notifyUiChanged();
				} else if (resultCode == RESULT_FIRST_USER) {
					recreate();
				}
				break;
			case ACCESS_FILE:
				if (resultCode == RESULT_OK)
					refresh();
				break;
            case SHOW_FLOATING:
                reqCode = requestCode;
                if (Settings.canDrawOverlays(this)) {
                    panel.show();
                } else {
                    panel.clean();
					toast(getString(R.string.request_failed));
				}
                break;
			case REQ_FOLDER:
				if (resultCode == RESULT_OK) {
					Uri dat = data.getData();
					File tmxfile = new File(DocumentsContract.getTreeDocumentId(dat));
					if (FileHelper.isTermuxFile(tmxfile)) {
						getContentResolver().takePersistableUriPermission(dat, Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
						Application.getInstance().saveTermuxUri(dat);
						pwd = tmxfile;
						refresh();
					} else
						toast(getString(R.string.not_termux_dir));
				}
				break;
		}
	}

    @Override
    protected void onStop() {
        try {
            Application.getInstance().getDocuments().flush();
        } catch (IOException error) {
            Log.e("Recovery", "Unable to flush unsaved documents", error);
        }
        getPreferences(MODE_PRIVATE).edit().putString(PWD, pwd.getPath()).commit();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        runtimeHostReady = false;
        if (isFinishing()) Application.getInstance().getRuns().cancelPreparation();
        hda = null;
        panel.clean();
        panel = null;
        adp = null;
        codeEditor = null;
        uiRefresh = null;
        searchUi = null;
        Application.getInstance().hand.updateActivity(null);
        super.onDestroy();
    }

    final void toast(CharSequence charSequence) {
        HelperUtils.show(Toast.makeText(this, charSequence, Toast.LENGTH_SHORT));
    }

	public void setEditor(TextEditor edit) {
		codeEditor = edit;
		notifyUiChanged();
	}

	public TextEditor getEditor() {
		return codeEditor;
	}

    public TextEditor newEditor() {
        return new TextEditor(this);
    }

    @Override
    public void onAdd(String item) {
		notifyUiChanged();
    }

    @Override
    public void onRemove(String item) {
		notifyUiChanged();
    }

    @Override
    public void onClear() {
		selectedIndex = -1;
		lastFrag = null;
		codeEditor = null;
		notifyUiChanged();
    }

    @Override
    public boolean onKeyShortcut(int keyCode, KeyEvent event) {
        boolean ctrl = event.hasModifiers(KeyEvent.META_CTRL_ON);
        boolean ctrlShift = event.hasModifiers(KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON);
        int action = 0;
        if ((ctrl || ctrlShift) && keyCode == KeyEvent.KEYCODE_Z && codeEditor != null) {
            action = ctrlShift ? R.id.redo : R.id.undo;
        } else if (ctrl) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_S:
                    if (lastFrag != null) action = R.id.save;
                    break;
                case KeyEvent.KEYCODE_F:
                    if (codeEditor != null && searchUi != null) action = R.id.search;
                    break;
                case KeyEvent.KEYCODE_R:
                    if (runVisible && (lastFrag != null || Project.rootPath != null)) action = R.id.run;
                    break;
            }
        }
        if (action == 0) return super.onKeyShortcut(keyCode, event);
        performAction(action);
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Handle Alt+Number shortcuts
        int metaState;
        if (Application.navtab && (metaState = event.getMetaState()) != 0 && (metaState & KeyEvent.META_ALT_MASK) == metaState) {
            int childIndex = -2;

            // Map key codes to indices (-1~8)
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                childIndex = keyCode - KeyEvent.KEYCODE_1;
            } else if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
                childIndex = keyCode - KeyEvent.KEYCODE_NUMPAD_1;
            }

            if (childIndex != -2) {
                int childCount = hda.getCount();

                // Special case: -1 to last child
                if (childIndex == -1) {
                    childIndex = childCount - 1;
                }

                if (childIndex >= 0 && childIndex < childCount) {
                    selectItem(childIndex);
                    return true;
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

}
