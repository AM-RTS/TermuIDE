package amrts.termuide;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.util.*;

/** Owns lazily launched servers, shared by documents in the same language and folder/project. */
final class LanguageServers {
    enum Server {
        CLANGD("clangd", "clangd", "--header-insertion-decorators=0"),
        PYTHON("pylsp", "pylsp");

        final String id;
        final String[] command;
        Server(String id, String... command) { this.id = id; this.command = command; }
        static Server forType(int type) {
            String id = Languages.forType(type).getId();
            return "c".equals(id) || "cpp".equals(id) ? CLANGD : "python".equals(id) ? PYTHON : null;
        }
    }

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
        String root = root(file), key = server.id + "\n" + root, path = path(file);
        Lsp current = documents.get(path);
        Lsp session = sessions.get(key);
        if (current != null && current != session) close(file);
        if (session == null) {
            session = new Lsp(server, root, Application.lsp_host, allocatePort());
            sessions.put(key, session);
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
        Lsp session = session(file, type);
        if (session == null || session.isStarted()) return;
        try {
            session.start(context, Application.getInstance().hand);
            session.initialize(session.root);
        } catch (SecurityException | IllegalStateException | IllegalArgumentException error) {
            Log.w("LSP", "Language server unavailable; the document remains editable", error);
        }
    }

    Lsp find(File file) { return documents.get(path(file)); }

    boolean owns(Lsp session, File file) { return documents.get(path(file)) == session; }

    void close(File file) {
        Lsp session = documents.remove(path(file));
        if (session == null) return;
        session.didClose(file);
        if (!documents.containsValue(session)) {
            session.end();
            sessions.values().remove(session);
        }
    }

    void end() {
        for (Lsp session : sessions.values()) session.end();
        sessions.clear();
        documents.clear();
    }
}
