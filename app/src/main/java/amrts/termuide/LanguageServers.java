package amrts.termuide;

import android.content.Context;
import android.app.PendingIntent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.util.*;

/** Owns lazily launched servers, shared by documents in the same language and folder/project. */
final class LanguageServers {
    enum Server {
        CLANGD("clangd", "clangd", "--header-insertion-decorators=0"),
        PYTHON("pylsp", "python", "-m", "pylsp");

        final String id;
        final String[] command;
        Server(String id, String... command) { this.id = id; this.command = command; }
        static Server forType(int type) {
            String id = Languages.forType(type).getId();
            return "c".equals(id) || "cpp".equals(id) ? CLANGD : "python".equals(id) ? PYTHON : null;
        }
    }

    static final String RESULT_SCHEME = "termuide-lsp";
    enum State { STOPPED, CHECKING, STARTING, READY, MISSING, DISCONNECTED, FAILED, DISABLED }
    static final class Info {
        final Server server;
        final State state;
        final String detail;
        final boolean restartable;
        Info(Server server, State state, String detail, boolean restartable) {
            this.server = server; this.state = state; this.detail = detail; this.restartable = restartable;
        }
    }
    private static final class Probe {
        final Lsp session;
        final PendingIntent callback;
        final Runnable timeout;
        Probe(Lsp session, PendingIntent callback, Runnable timeout) { this.session = session; this.callback = callback; this.timeout = timeout; }
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Probe> pending = new HashMap<>();
    private final Map<String, Lsp> sessions = new LinkedHashMap<>();
    private final Map<String, Lsp> documents = new HashMap<>();
    private int configuredPort = -1, nextPort;

    static String path(File file) { return file.getAbsoluteFile().toPath().normalize().toString(); }
    static String root(File file) {
        String project = Project.rootPath;
        java.nio.file.Path path = file.getAbsoluteFile().toPath().normalize();
        if (project != null && path.startsWith(new File(project).getAbsoluteFile().toPath().normalize()))
            return path(new File(project));
        return path(file.getAbsoluteFile().getParentFile());
    }

    Lsp session(File file, int type) {
        Server server = Server.forType(type);
        if (server == null) return null;
        String root = root(file), path = path(file);
        String configurationScope = Project.rootPath == null ? root : path(new File(Project.rootPath));
        String environment = null, configurationError = null;
        try { if (server == Server.PYTHON) environment = RunConfiguration.load(Application.getInstance(), configurationScope).pythonEnvironmentPath(configurationScope); }
        catch (IllegalArgumentException error) { configurationError = error.getMessage(); }
        if (environment == null) environment = "";
        String key = server.id + "\n" + root + "\n" + environment + (configurationError == null ? "" : "\ninvalid");
        Lsp current = documents.get(path);
        Lsp session = sessions.get(key);
        if (current != null && current != session) close(file);
        if (session == null) {
            session = new Lsp(server, root, Application.lsp_host, allocatePort(), environment);
            sessions.put(key, session);
            if (configurationError != null) {
                session.configurationError = configurationError;
                session.setState(State.FAILED, configurationError);
            }
        }
        documents.put(path, session);
        return session;
    }

    private int allocatePort() {
        Set<Integer> used = new HashSet<>();
        for (Lsp session : sessions.values()) used.add(session.port);
        if (configuredPort != Application.lsp_port) {
            configuredPort = Application.lsp_port;
            nextPort = configuredPort;
        }
        int port = nextPort;
        while (used.contains(port)) port = port == 65535 ? 1024 : port + 1;
        // A stopped Termux bridge can still be draining shutdown; do not immediately reuse its port.
        nextPort = port == 65535 ? 1024 : port + 1;
        return port;
    }

    void ensure(Context context, File file, int type) {
        if (!"s".equals(Application.completion)) return;
        Lsp session;
        try { session = session(file, type); }
        catch (IllegalArgumentException error) { return; } // Configuration errors remain visible in Language tools.
        if (session == null || session.configurationError != null || session.state() == State.CHECKING || (session.isStarted() && !session.hasFailed())) return;
        if (session.hasFailed()) {
            Lsp previous = session;
            previous.end();
            cancel(previous);
            Lsp replacement = new Lsp(previous.server, previous.root, Application.lsp_host, allocatePort(), previous.environment);
            sessions.replaceAll((key, value) -> value == previous ? replacement : value);
            documents.replaceAll((key, value) -> value == previous ? replacement : value);
            session = replacement;
        }
        beginProbe(context, session);
    }

    private void beginProbe(Context context, Lsp session) {
        session.setState(State.CHECKING, null);
        Context app = context.getApplicationContext();
        String token = UUID.randomUUID().toString();
        PendingIntent callback = RunController.resultCallback(context, RESULT_SCHEME, token);
        Runnable timeout = () -> {
            Probe probe = pending.remove(token);
            if (probe == null) return;
            probe.callback.cancel();
            session.setState(State.FAILED, app.getString(R.string.runtime_timeout));
        };
        pending.put(token, new Probe(session, callback, timeout));
        main.postDelayed(timeout, RunController.PROBE_TIMEOUT);
        try {
            RunCommands.Plan plan = RunCommands.languageServerProbe(session.server.id, session.root, session.environment);
            Utils.run(context, plan.getExecutable(), plan.getArguments().toArray(new String[0]), plan.getDirectory(), true, callback);
        } catch (RuntimeException error) {
            cancel(session);
            session.setState(State.FAILED, error.getMessage() == null ? context.getString(R.string.runtime_setup_required) : error.getMessage());
        }
    }

    void receive(String token, Bundle result) {
        Probe probe = pending.remove(token);
        if (probe == null) return;
        main.removeCallbacks(probe.timeout);
        probe.callback.cancel();
        Lsp session = probe.session;
        if (!sessions.containsValue(session) || !documents.containsValue(session)) return;
        String tool = session.server == Server.PYTHON ? "python" : "clangd";
        RunController.ProbeResult outcome = RunController.readProbeResult(Application.getInstance(), result, Collections.singletonList(tool));
        if (outcome.getTools() == null) {
            session.setState(outcome.getMissing() ? State.MISSING : State.FAILED, outcome.getMessage());
            return;
        }
        try {
            session.configureRuntime(outcome.getTools().get(tool));
            session.start(Application.getInstance(), Application.getInstance().hand);
            session.initialize(session.root);
        } catch (RuntimeException error) {
            session.end();
            session.setState(State.FAILED, error.getMessage());
        }
    }

    private void cancel(Lsp session) {
        Iterator<Probe> iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            Probe probe = iterator.next();
            if (probe.session != session) continue;
            iterator.remove(); main.removeCallbacks(probe.timeout); probe.callback.cancel();
        }
    }

    List<Info> status(String root) {
        List<Info> result = new ArrayList<>();
        for (Server server : Server.values()) {
            Lsp found = null;
            for (Lsp session : sessions.values()) if (server == session.server && root.equals(session.root)) { found = session; break; }
            result.add(new Info(server, !"s".equals(Application.completion) ? State.DISABLED : found == null ? State.STOPPED : found.state(), found == null ? null : found.detail(), found != null));
        }
        return result;
    }

    void restart(Context context, String root, String serverId) {
        Server server = Arrays.stream(Server.values()).filter(value -> value.id.equals(serverId)).findFirst().orElseThrow(IllegalArgumentException::new);
        restartMatching(context, value -> value.server == server && root.equals(value.root));
    }

    void restartPythonEnvironment(Context context, String scope) {
        boolean project = Project.rootPath != null && scope.equals(path(new File(Project.rootPath)));
        restartMatching(context, value -> value.server == Server.PYTHON && (project || scope.equals(value.root)));
    }

    private void restartMatching(Context context, java.util.function.Predicate<Lsp> predicate) {
        Map<String, Server> files = new LinkedHashMap<>();
        for (Map.Entry<String, Lsp> document : documents.entrySet())
            if (predicate.test(document.getValue())) files.put(document.getKey(), document.getValue().server);
        for (String file : files.keySet()) close(new File(file));
        for (Map.Entry<String, Server> file : files.entrySet())
            ensure(context, new File(file.getKey()), file.getValue() == Server.PYTHON ? EditFragment.TYPE_PYTHON : EditFragment.TYPE_CPP);
    }

    Lsp find(File file) { return documents.get(path(file)); }

    boolean owns(Lsp session, File file) { return documents.get(path(file)) == session; }

    void close(File file) {
        Lsp session = documents.remove(path(file));
        if (session == null) return;
        session.didClose(file);
        if (!documents.containsValue(session)) {
            cancel(session);
            session.end();
            sessions.values().remove(session);
        }
    }

    void end() {
        for (Lsp session : sessions.values()) { cancel(session); session.end(); }
        sessions.clear();
        documents.clear();
    }
}
