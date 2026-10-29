package amrts.termuide;

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
	private WorkspaceSession workspace;
	private FileAdapter adp;
	private EditFragment lastFrag = null;
	private boolean runVisible, deleting;
    private File pwd, prj, root;
    private TextEditor codeEditor;
    private DebugPanel panel;
    private int reqCode;
    private Runnable uiRefresh;
    private Runnable searchUi;
    private EditFragment pendingReload, pendingRename;
    private String pendingRenameText;
    private boolean installPrompt, installPromptNoRemind, initPrompt, initPromptNoRemind;
    private boolean runtimeHostReady;


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
		if (app.hand == null) {
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
		return workspace.getItem(idx);
	}

    @Override
    protected void onCreate(Bundle savedInstanceState) {
		Object retained = getLastCustomNonConfigurationInstance();
		workspace = retained instanceof WorkspaceSession ? (WorkspaceSession)retained : new WorkspaceSession();
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
		workspace.setOnChangedListener(this);
		adp = new FileAdapter(pwd);
        panel = new DebugPanel(this);
		setContentView(TermuIDECompose.mainView(this));
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
            if (!workspace.contains(path) && (session.getDirty() || FileHelper.isFile(new File(path)))) {
                openFile(new File(path));
            }
        }
        getFragmentManager().executePendingTransactions();
        if (selected != null && workspace.contains(selected)) selectItem(workspace.indexOf(selected));
        else if (!workspace.isEmpty()) selectItem(workspace.getCount() - 1);
        if (savedInstanceState != null) {
            pendingRename = (EditFragment)getFragmentManager().findFragmentByTag(savedInstanceState.getString("pendingRename", ""));
            pendingRenameText = savedInstanceState.getString("pendingRenameText");
            pendingReload = (EditFragment)getFragmentManager().findFragmentByTag(savedInstanceState.getString("pendingReload", ""));
            installPrompt = savedInstanceState.getBoolean("installPrompt");
            installPromptNoRemind = savedInstanceState.getBoolean("installPromptNoRemind");
            initPrompt = savedInstanceState.getBoolean("initPrompt");
            initPromptNoRemind = savedInstanceState.getBoolean("initPromptNoRemind");
            notifyUiChanged();
        }
        runtimeHostReady = true;
        observePendingClose();
        getLifecycle().addObserver((androidx.lifecycle.LifecycleEventObserver)(source, event) -> {
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) finishPendingClose();
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
					workspace.pendingProjectPath = prj.getAbsolutePath();
				if (workspace.pendingProjectPath != null) notifyUiChanged();
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
                        selectItem(getSelectedItem());
                    }
                } catch (IllegalArgumentException error) {
                    toast(getString(R.string.run_config_invalid));
                    return;
                }
            }
            List<Integer> marks = new ArrayList<>();
            if (codeEditor != null) for (int i = 0; i < codeEditor.getText().getMarksCount(); i++) marks.add(codeEditor.getText().getMark(i));
            try {
                Application.getInstance().getRuns().start(new RunCommands.Request(action,
                    getSelectedFilePath(), lastFrag == null ? EditFragment.TYPE_TXT : lastFrag.type,
                    pwd.getAbsolutePath(), Project.snapshot(), Application.cflags, Collections.unmodifiableList(marks), configuration, getPythonEnvironment()));
            } catch (IllegalArgumentException error) { toast(getString(R.string.run_config_invalid)); }
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
            && Objects.equals(request.getDirectory(), pwd.getAbsolutePath()) && matchesRunConfiguration(request) && matchesPythonEnvironment(request) ? 1 : -1;
    }

    public String getRunConfigurationScope() {
        return Project.rootPath != null ? Project.rootPath : pwd.getAbsolutePath();
    }

    public RunConfiguration getRunConfiguration() {
        return RunConfiguration.load(this, getRunConfigurationScope());
    }

    public boolean saveRunConfiguration(String scope, RunConfiguration configuration) {
        if (!scope.equals(getRunConfigurationScope())) throw new IllegalStateException(getString(R.string.run_config_changed));
        String oldEnvironment = null;
        try { oldEnvironment = RunConfiguration.load(this, scope).getPythonEnvironment(); } catch (IllegalArgumentException ignored) { }
        if (!RunConfiguration.save(this, scope, configuration)) return false;
        Application.getInstance().getRuns().onConfigurationChanged();
        if (!Objects.equals(oldEnvironment, configuration.getPythonEnvironment())) {
            Application.getInstance().getRuns().cancelPreparation();
            Application.getInstance().languageServers.restartPythonEnvironment(this, LanguageServers.path(new File(scope)));
        }
        notifyUiChanged();
        return true;
    }

    public String getLanguageToolsScope() {
        if (Project.rootPath != null) return LanguageServers.path(new File(Project.rootPath));
        String selected = getSelectedFilePath();
        return selected == null ? LanguageServers.path(pwd) : LanguageServers.root(new File(selected));
    }

    public List<LanguageServers.Info> getLanguageServerStatus() {
        String selected = getSelectedFilePath();
        return Application.getInstance().languageServers.status(selected == null ? getLanguageToolsScope() : LanguageServers.root(new File(selected)));
    }

    public void restartLanguageServer(String scope, String server) {
        if (!scope.equals(getLanguageToolsScope())) throw new IllegalStateException(getString(R.string.run_config_changed));
        String selected = getSelectedFilePath();
        Application.getInstance().languageServers.restart(this, selected == null ? scope : LanguageServers.root(new File(selected)), server);
        notifyUiChanged();
    }

    public boolean savePythonEnvironment(String scope, String value) {
        if (!scope.equals(getLanguageToolsScope())) throw new IllegalStateException(getString(R.string.run_config_changed));
        RunConfiguration existing = RunConfiguration.load(this, scope);
        RunConfiguration changed = existing.withPythonEnvironment(value);
        if (existing.equals(changed)) return true;
        if (!RunConfiguration.save(this, scope, changed)) return false;
        Application app = Application.getInstance();
        app.getRuns().cancelPreparation();
        app.languageServers.restartPythonEnvironment(this, scope);
        notifyUiChanged();
        return true;
    }

    private String getPythonEnvironment() {
        if (!hasProject() && (lastFrag == null || !"python".equals(Languages.forType(lastFrag.type).getId()))) return "";
        String scope = getLanguageToolsScope();
        String environment = RunConfiguration.load(this, scope).pythonEnvironmentPath(scope);
        return environment == null ? "" : environment;
    }

    private boolean matchesPythonEnvironment(RunCommands.Request request) {
        try { return request.getPythonEnvironment().equals(getPythonEnvironment()); }
        catch (IllegalArgumentException error) { return false; }
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
		} else if (id == R.id.close && getSelectedItem() >= 0) {
			closeFile(getSelectedItem());
		} else if (id == R.id.prj_attr && Project.rootPath != null) {
			openFile(new File(Project.rootPath, Project.PROJ));
		} else if (id == R.id.prj_close && Project.rootPath != null) {
            closeProject();
		} else if (id == R.id.settings) {
			startActivityForResult(new Intent(this, SettingsActivity.class), SETTING);
		}
	}

    private void closeProject() {
        if (requestCloseIfDirty(new ArrayList<>(workspace), WorkspaceSession.CLOSE_PROJECT, null)) return;
        // A restored close prompt can outlive the process-local project configuration.
        if (Project.rootPath == null) { finishProjectClose(); return; }
        String configuration = new File(Project.rootPath, Project.PROJ).getAbsolutePath();
        if (Application.getInstance().saver.isSaving(configuration)) { toast(getString(R.string.saving)); return; }
        workspace.beginProjectClose(Application.getInstance().getDocuments());
        notifyUiChanged();
        observePendingClose();
    }

    @Override public Object onRetainCustomNonConfigurationInstance() { return workspace; }

    private void observePendingClose() {
        CompletableFuture<?> result = workspace.pendingResult();
        if (result != null) result.whenComplete((saved, error) -> finishPendingClose());
    }

    private void finishPendingClose() {
        CompletableFuture<?> result = workspace.pendingResult();
        if (result == null || !result.isDone() || isDestroyed() || isFinishing()
                || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                || getFragmentManager().isStateSaved()) return;
        WorkspaceSession.ProjectClose close = workspace.projectClose;
        workspace.sourceClose = null; workspace.projectClose = null; workspace.savingClose = false;
        try {
            Object saved = result.join();
            if (close != null && !Boolean.TRUE.equals(saved))
                throw new CompletionException(new IOException("Project save failed"));
        } catch (CompletionException error) {
            Log.e("Save", "Unable to finish closing documents", error);
            toast(getString(R.string.save_failed)); notifyUiChanged(); return;
        }
        Application app = Application.getInstance();
        if (close == null) {
            for (String path : workspace.pendingClosePaths()) if (app.getDocuments().isDirty(path)) {
                toast(getString(R.string.save_newer_changes)); notifyUiChanged(); return;
            }
            finishCloseDocuments(true);
        } else {
            if (!close.isCurrent(workspace, app.getDocuments(), app.saver)) {
                toast(getString(R.string.save_newer_changes)); notifyUiChanged(); return;
            }
            workspace.approvedDiscards = close.discards;
            try { finishProjectClose(); } finally { workspace.approvedDiscards = Collections.emptySet(); }
        }
    }

    private void finishProjectClose() {
        FragmentManager fm = getFragmentManager();
        FragmentTransaction transaction = fm.beginTransaction();
        LanguageServers servers = Application.getInstance().languageServers;
        for (int i = 0; i < workspace.getCount(); i++) {
            String path = workspace.getItem(i);
            closeDocumentSession(path);
            transaction.remove(fm.findFragmentByTag(path));
            servers.close(new File(path));
        }
        workspace.clearProject();
        transaction.commit();
        lastFrag = null;
        codeEditor = null;
        runVisible = false;
        servers.end();
        notifyUiChanged();
    }

	void setFileRunnable(boolean exec) {
		runVisible = Project.rootPath == null ? exec : true;
		notifyUiChanged();
	}

    void selectItem(int pos) {
        if (!workspace.select(pos)) return;
        Fragment fragment = getFragmentManager().findFragmentByTag(workspace.getItem(pos));
        if (fragment != null) {
            showFrag(fragment);
            lastFrag = (EditFragment)fragment;
            codeEditor = lastFrag.getView();
            setFileRunnable(EditFragment.isExecutable(lastFrag.type));
        }
        notifyUiChanged();
    }

    int getSelectedItem() {
        return workspace.getSelectedIndex();
    }

    int getItemCount() {
        return workspace.getCount();
    }


    boolean openFile(File file) {
        String path = file.getAbsolutePath();
        int index = workspace.indexOf(path);
        if (index < 0) {
            FragmentManager manager = getFragmentManager();
            EditFragment fragment = (EditFragment)manager.findFragmentByTag(path);
            if (fragment == null) {
                Application app = Application.getInstance();
                int type = EditFragment.fileType(file, app.getDocuments().isDirty(path));
                if (type < 0) return false;
                fragment = new EditFragment(file, type);
                FragmentTransaction transaction = manager.beginTransaction();
                transaction.add(R.id.editFrag, fragment, path);
                if (lastFrag != null) transaction.hide(lastFrag);
                transaction.show(fragment).commit();
                lastFrag = fragment;
                setFileRunnable(EditFragment.isExecutable(type));
            }
            workspace.add(path);
            index = workspace.indexOf(path);
        }
        selectItem(index);
        notifyUiChanged();
        return true;
    }

	public void confirmOpenProject() {
		String projectPath = workspace.pendingProjectPath;
		workspace.pendingProjectPath = null;
		if (projectPath == null) return;
        List<String> closing = new ArrayList<>(workspace);
        closing.remove(getSelectedFilePath());
        if (requestCloseIfDirty(closing, WorkspaceSession.OPEN_PROJECT, projectPath)) return;
		File config = new File(projectPath, Project.PROJ);
		if (!FileHelper.isFile(config)) {
			toast(getString(R.string.open_failed));
			return;
		}
		try {
            List<String> opens = workspace.openProject(config);
            openProjFiles(opens, getSelectedFilePath());
			setFileRunnable(true);
		} catch (IOException e) {
			e.printStackTrace();
			toast(getString(R.string.open_failed));
		}
		notifyUiChanged();
	}

    private void openProjFiles(List<String> opens, String retainedPath) {
        FragmentManager manager = getFragmentManager();
        FragmentTransaction transaction = manager.beginTransaction();
        LanguageServers servers = Application.getInstance().languageServers;
        for (String path : new ArrayList<>(workspace)) {
            if (path.equals(retainedPath)) continue;
            closeDocumentSession(path);
            workspace.remove(path);
            Fragment fragment = manager.findFragmentByTag(path);
            if (fragment != null) transaction.remove(fragment);
            servers.close(new File(path));
        }
        lastFrag = (EditFragment)manager.findFragmentByTag(workspace.getSelectedPath());
        codeEditor = lastFrag == null ? null : lastFrag.getView();
        transaction.commit();
        manager.executePendingTransactions();
        servers.end();
        if (lastFrag != null && lastFrag.getView() != null) {
            servers.ensure(this, lastFrag.getFile(), lastFrag.type);
            if ("s".equals(Application.completion)) lastFrag.onOpen(lastFrag.getView().getText());
        }
        for (String path : opens) {
            if (workspace.contains(path)) continue;
            File file = new File(path);
            if (FileHelper.isFile(file)) openFile(file);
        }
        manager.executePendingTransactions();
        selectItem(workspace.getSelectedIndex());
        notifyUiChanged();
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        performAction(menuItem.getItemId());
        return true;
    }

    public void closeFile(int pos) {
        if (pos < 0 || pos >= workspace.getCount()) return;
        String file = workspace.getItem(pos);
        if (requestCloseIfDirty(Collections.singletonList(file), WorkspaceSession.CLOSE_FILE, file)) return;
        closeDocumentSession(file);
        FragmentManager fm = getFragmentManager();
        FragmentTransaction tran = fm.beginTransaction();
        EditFragment frg = (EditFragment) fm.findFragmentByTag(file);
        boolean wasSelected = file.equals(getSelectedFilePath());
        workspace.remove(file);
        if (frg != null) tran.remove(frg);
        if (wasSelected && workspace.getCount() > 0) {
            lastFrag = (EditFragment)fm.findFragmentByTag(workspace.getSelectedPath());
            if (lastFrag != null) tran.show(lastFrag);
        }
        tran.commit();
        Application app = Application.getInstance();
        app.languageServers.close(new File(file));
        app.hand.cacheData.remove(file);
        app.load(file);
        if (workspace.getCount() == 0) {
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
        if (workspace.savingClose) { toast(getString(R.string.saving)); return true; }
        for (String path : paths) if (Application.getInstance().saver.isSaving(path)) {
            toast(getString(R.string.saving)); return true;
        }
        if (!workspace.requestClose(paths, kind, target, Application.getInstance().getDocuments())) return false;
        notifyUiChanged();
        return true;
    }

    public List<String> getPendingClosePaths() { return workspace.pendingClosePaths(); }

    private void closeDocumentSession(String path) {
        workspace.closeDocument(path, Application.getInstance().getDocuments());
    }

    public void cancelCloseDocuments() {
        workspace.cancelClose();
        notifyUiChanged();
    }

    public void resolveCloseDocuments(boolean save) {
        if (workspace.savingClose || workspace.pendingClosePaths().isEmpty()) return;
        for (String path : workspace.pendingClosePaths()) if (Application.getInstance().saver.isSaving(path)) {
            toast(getString(R.string.saving)); return;
        }
        if (save) {
            List<CompletableFuture<Boolean>> writes = new ArrayList<>();
            for (String path : workspace.pendingClosePaths()) {
                EditFragment fragment = (EditFragment)getFragmentManager().findFragmentByTag(path);
                if (fragment == null || fragment.getView() == null) { toast(getString(R.string.save_failed)); return; }
            }
            workspace.savingClose = true;
            try {
                for (String path : workspace.pendingClosePaths())
                    writes.add(((EditFragment)getFragmentManager().findFragmentByTag(path)).save());
            } catch (RuntimeException error) {
                CompletableFuture<Boolean> failed = new CompletableFuture<>();
                failed.completeExceptionally(error);
                writes.add(failed);
            }
            workspace.sourceClose = CompletableFuture.allOf(writes.toArray(new CompletableFuture[0]));
            notifyUiChanged();
            observePendingClose();
        } else finishCloseDocuments(false);
    }

    public boolean isSavingClose() { return workspace.savingClose; }

    private void finishCloseDocuments(boolean save) {
        WorkspaceSession.CloseRequest close = workspace.takeClose(save);
        notifyUiChanged();
        try {
            if (close.kind == WorkspaceSession.CLOSE_FILE) closeFile(workspace.indexOf(close.target));
            else if (close.kind == WorkspaceSession.CLOSE_PROJECT) closeProject();
            else if (close.kind == WorkspaceSession.OPEN_PROJECT) { workspace.pendingProjectPath = close.target; confirmOpenProject(); }
        } finally { workspace.approvedDiscards = Collections.emptySet(); }
    }

    public void inputKey(String key) {
        if (codeEditor == null) return;
        if ("⇥".equals(key)) codeEditor.sendPrintableChar(Language.TAB);
        else codeEditor.paste(key);
    }

    public String getPendingProjectPath() {
        return workspace.pendingProjectPath;
    }

    public void dismissProjectPrompt() {
        workspace.pendingProjectPath = null;
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
        return new ArrayList<>(workspace);
    }

    public String getSelectedFilePath() {
        return workspace.getSelectedPath();
    }

    public boolean hasProject() {
        return Project.rootPath != null;
    }

    public boolean canDebug() {
        try {
            String entry = getRunConfiguration().entryPath(getRunConfigurationScope(), getSelectedFilePath());
            EditFragment target = entry == null ? null : (EditFragment)getFragmentManager().findFragmentByTag(entry);
            return target != null && Languages.canDebug(target.type) && (hasProject() || EditFragment.isExecutable(target.type))
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

    public boolean createProject(String name, String template, boolean withClangd, boolean withFormat) {
        if (workspace.savingClose) { toast(getString(R.string.saving)); return false; }
        if (name.isEmpty()) {
            toast(getText(R.string.empty_name));
            return false;
        }
        File directory = new File(pwd, name);
        Project.Config previous = Project.snapshot();
        if (!Utils.extractTemplate(this, template, directory)) {
            toast(getString(R.string.project_create_failed));
            return false;
        }
        try {
            if (withClangd) Utils.dumpFile(getAssets().open("cld"), new File(directory, ".clangd"));
            if (withFormat) Utils.dumpFile(getAssets().open("fmt"), new File(directory, ".clang-format"));
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            Project.publish(previous);
            e.printStackTrace();
            toast(getString(R.string.project_create_failed));
            return false;
        }
        pwd = prj = directory;
        setFileRunnable(true);
        refresh();
        return true;
    }

    @Override
    protected void onRestoreInstanceState(Bundle bundle) {
        super.onRestoreInstanceState(bundle);
        pwd = new File(bundle.getString(PWD));
        adp.setPath(pwd);
        if (!(getLastCustomNonConfigurationInstance() instanceof WorkspaceSession)) workspace.restore(bundle);
        if (!workspace.isEmpty()) selectItem(workspace.getSelectedIndex());
		notifyUiChanged();
    }

    @Override
    public void onSaveInstanceState(Bundle bundle) {
        bundle.putString(PWD, pwd.getPath());
        workspace.save(bundle);
        bundle.putString("pendingRename", pendingRename == null ? null : pendingRename.getFile().getAbsolutePath());
        bundle.putString("pendingRenameText", pendingRenameText);
        bundle.putString("pendingReload", pendingReload == null ? null : pendingReload.getFile().getAbsolutePath());
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
                    app.languageServers.end();
					FragmentManager fm = getFragmentManager();
					Typeface tf = Application.typeface();
					for (int i=workspace.getCount() - 1;i >= 0;i--) {
						EditFragment f = (EditFragment)fm.findFragmentByTag(workspace.getItem(i));
						TextEditor ed = f.getView();
                        ed.setPureMode(Application.pure_mode);
						ed.setFormatter(s && f.hasLsp() ? f : null);
						ed.setAutoComplete("l" == Application.completion);
						ed.setTypeface(tf);
						ed.setWordWrap(Application.wordwrap);
						ed.setShowNonPrinting(Application.whitespace);
						ed.setUseSpace(Application.usespace);
						ed.setTabSpaces(Application.tabsize);
                        ed.setSuggestion(Application.suggestion);
                        ed.setAutoCaps(Application.auto_caps);
                        if (s && f.hasLsp()) {
                            app.languageServers.ensure(this, f.getFile(), f.type);
                            f.onOpen(ed.getText());
                        } else {
                            ed.getAutoCompletePanel().dismiss(); ed.getSigHelpPanel().hide();
                            app.hand.cacheData.remove(f.getFile().getAbsolutePath());
                            ed.getLanguage().setTypes(EditFragment.DEFTYPES);
                            ed.getText().setDiag(null); ed.getText().setHighlights(null);
                            ed.setSemanticTokens(Collections.emptyList());
                        }
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
        workspace.clearOnChangedListener(this);
        panel.clean();
        panel = null;
        adp = null;
        codeEditor = null;
        uiRefresh = null;
        searchUi = null;
        Application.getInstance().hand.updateActivity(null);
        super.onDestroy();
        if (isFinishing()) Application.getInstance().languageServers.end();
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
                int childCount = workspace.getCount();

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
