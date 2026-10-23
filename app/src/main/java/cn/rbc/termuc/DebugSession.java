package cn.rbc.termuc;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/** Owns GDB transport and protocol state without an Activity, View or Android handler. */
final class DebugSession implements AutoCloseable {
    static final int IDLE = 0, RUNNING = 1, STOPPED = 2;
    static final class Variable {
        final String id, expression, value, type;
        final boolean hasChildren;
        Variable(Map<String, Object> fields, String expression) {
            id = string(fields, "name"); this.expression = expression;
            value = string(fields, "value"); type = string(fields, "type");
            hasChildren = !string(fields, "numchild").isEmpty() && !"0".equals(string(fields, "numchild"));
        }
    }
    static final class Snapshot {
        final int status, hitBreakpoint;
        final boolean connected, exited;
        final List<Variable> variables;
        final List<String> frames;
        final String console, debug;
        Snapshot(int status, int hitBreakpoint, boolean connected, boolean exited,
                Collection<Variable> variables, List<String> frames, String console, String debug) {
            this.status = status; this.hitBreakpoint = hitBreakpoint;
            this.connected = connected; this.exited = exited;
            this.variables = Collections.unmodifiableList(new ArrayList<>(variables));
            this.frames = Collections.unmodifiableList(new ArrayList<>(frames));
            this.console = console; this.debug = debug;
        }
        static Snapshot empty() { return new Snapshot(IDLE, 0, false, false, Collections.emptyList(), Collections.emptyList(), "", ""); }
    }
    private static final class PendingVariable {
        final int generation, slot;
        final String expression;
        PendingVariable(int generation, int slot, String expression) {
            this.generation = generation; this.slot = slot; this.expression = expression;
        }
    }
    private final Consumer<Snapshot> changed;
    private final int port;
    private final TreeMap<Integer, Variable> variables = new TreeMap<>();
    private final List<String> frames = new ArrayList<>();
    private final Set<String> variableRoots = new LinkedHashSet<>();
    private final Map<String, PendingVariable> pendingVariables = new HashMap<>();
    private final Map<String, Integer> pendingBreakpoints = new HashMap<>();
    private final List<String> breakpointIds = new ArrayList<>();
    private final Map<Integer, Boolean> breakpointEnabled = new HashMap<>();
    private final StringBuilder console = new StringBuilder(), debug = new StringBuilder();
    private Sender sender;
    private Socket socket;
    private Thread reader;
    private boolean closed, exited;
    private int status, hitBreakpoint, sequence, generation, nextSlot;
    private String argumentsToken, localsToken, framesToken, childrenToken;

    DebugSession(Consumer<Snapshot> changed) { this(48456, changed); }
    DebugSession(int port, Consumer<Snapshot> changed) { this.port = port; this.changed = changed; }

    synchronized void connect(List<String> breakpoints) {
        if (reader != null || closed) return;
        List<String> initialBreakpoints = new ArrayList<>(breakpoints);
        reader = new Thread(() -> read(initialBreakpoints), "GDB reader");
        reader.setDaemon(true); reader.start();
    }

    private void read(List<String> breakpoints) {
        try {
            for (int attempt = 0; attempt < 20; attempt++) {
                Socket connection = new Socket();
                synchronized (this) { if (closed) { connection.close(); return; } socket = connection; }
                try { connection.connect(new InetSocketAddress("127.0.0.1", port), 500); break; }
                catch (IOException error) {
                    connection.close();
                    if (attempt == 19) throw error;
                    Thread.sleep(250);
                }
            }
            Socket connection;
            synchronized (this) {
                if (closed) return;
                connection = socket;
                sender = new Sender(connection);
                Thread output = new Thread(sender, "GDB sender"); output.setDaemon(true); output.start();
                send("-gdb-set mi-async 1"); send("-enable-pretty-printing");
                for (String breakpoint : breakpoints) {
                    int index = breakpointIds.size(); breakpointIds.add("");
                    pendingBreakpoints.put(request("-break-insert \"" + escape(breakpoint) + "\""), index);
                }
                send("-exec-run"); publish();
            }
            try (BufferedReader input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = input.readLine()) != null;) accept(line);
            }
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            synchronized (this) { if (!closed) debug.append(error.toString()).append('\n'); }
        } finally {
            synchronized (this) {
                if (sender != null) { sender.offer(""); sender = null; }
                try { if (socket != null) socket.close(); } catch (IOException ignored) { }
                status = IDLE;
                if (!closed) publish();
            }
        }
    }

    synchronized void send(String command) { if (!closed && sender != null) sender.offer(command); }
    private String request(String command) {
        String token = Integer.toString(++sequence); send(token + command); return token;
    }

    synchronized void accept(String line) {
        if (closed) return;
        hitBreakpoint = 0;
        try {
            GdbMi record = GdbMi.parse(line);
            if (record == null) { if (!line.equals("(gdb) ") && !line.equals("(gdb)")) debug.append(line).append('\n'); }
            else if (record.kind == '~' || record.kind == '@' || record.kind == '&') console.append(record.text);
            else if (record.kind == '*' && record.name.equals("running")) {
                invalidateInspection(); status = RUNNING;
            }
            else if (record.kind == '*' && record.name.equals("stopped")) {
                invalidateInspection(); status = STOPPED;
                exited = string(record.values, "reason").startsWith("exited");
                if (exited) status = IDLE;
                String number = string(record.values, "bkptno").split("\\.")[0];
                hitBreakpoint = number.isEmpty() ? 0 : breakpointIds.indexOf(number) + 1;
            } else if (record.kind == '^') result(record);
            else debug.append(line).append('\n');
        } catch (IOException | RuntimeException error) { debug.append(line).append('\n').append(error.toString()).append('\n'); }
        publish();
    }

    private void result(GdbMi record) {
        if (record.name.equals("error")) {
            pendingVariables.remove(record.token);
            pendingBreakpoints.remove(record.token);
            if (record.token.equals(argumentsToken)) {
                argumentsToken = null; localsToken = request("-stack-list-locals 0");
            } else if (record.token.equals(localsToken)) localsToken = null;
            else if (record.token.equals(framesToken)) framesToken = null;
            else if (record.token.equals(childrenToken)) childrenToken = null;
            debug.append(string(record.values, "msg")).append('\n');
            return;
        }
        if (!record.name.equals("done")) return;
        Integer breakpoint = pendingBreakpoints.remove(record.token);
        if (breakpoint != null) {
            String id = string(tuple(record.values.get("bkpt")), "number");
            breakpointIds.set(breakpoint, id);
            if (!id.isEmpty() && Boolean.FALSE.equals(breakpointEnabled.get(breakpoint))) send("-break-disable " + id);
            return;
        }
        PendingVariable pending = pendingVariables.remove(record.token);
        if (pending != null) {
            String id = string(record.values, "name");
            if (!id.isEmpty()) {
                if (pending.generation == generation) {
                    variableRoots.add(id);
                    variables.put(pending.slot, new Variable(record.values, pending.expression));
                } else send("-var-delete " + id);
            } else debug.append("Missing GDB variable object name\n");
        } else if (record.token.equals(argumentsToken)) {
            for (Object item : list(record.values.get("stack-args"))) {
                Map<String, Object> frame = tuple(tuple(item).get("frame"));
                addVariables(list(frame.get("args")));
            }
            localsToken = request("-stack-list-locals 0"); argumentsToken = null;
        } else if (record.token.equals(localsToken)) {
            addVariables(list(record.values.get("locals"))); localsToken = null;
        } else if (record.token.equals(framesToken)) {
            frames.clear();
            for (Object item : list(record.values.get("stack"))) {
                Map<String, Object> frame = tuple(tuple(item).get("frame"));
                String file = string(frame, "file");
                if (file.isEmpty()) file = string(frame, "from");
                String line = string(frame, "line");
                frames.add("#" + string(frame, "level") + " " + string(frame, "func") + "() " + file + (line.isEmpty() ? "" : ":" + line));
            }
            framesToken = null;
        } else if (record.token.equals(childrenToken)) {
            generation++; variables.clear(); nextSlot = 0;
            for (Object item : list(record.values.get("children"))) {
                Map<String, Object> child = tuple(tuple(item).get("child"));
                if (!string(child, "name").isEmpty())
                    variables.put(nextSlot++, new Variable(child, string(child, "exp")));
            }
            childrenToken = null;
        }
    }

    private void addVariables(List<?> fields) {
        for (Object item : fields) {
            String expression = string(tuple(item), "name");
            if (expression.isEmpty()) continue;
            String token = request("-var-create - * \"" + escape(expression) + "\"");
            pendingVariables.put(token, new PendingVariable(generation, nextSlot++, expression));
        }
    }
    private void invalidateInspection() {
        generation++;
        argumentsToken = null; localsToken = null; framesToken = null; childrenToken = null;
        variables.clear(); frames.clear();
    }
    synchronized void refreshVariables() {
        if (status != STOPPED || closed) return;
        for (String root : variableRoots) send("-var-delete " + root);
        variableRoots.clear();
        generation++; nextSlot = 0; variables.clear(); localsToken = null; childrenToken = null;
        argumentsToken = request("-stack-list-arguments 0 0 0"); publish();
    }
    synchronized void refreshFrames() { if (status == STOPPED) framesToken = request("-stack-list-frames"); }
    synchronized void expandVariable(Variable variable) {
        if (status == STOPPED && variable.hasChildren) childrenToken = request("-var-list-children --all-values " + variable.id);
    }
    synchronized void toggleBreakpoint(int index, boolean enabled) {
        if (closed || index < 0 || index >= breakpointIds.size()) return;
        breakpointEnabled.put(index, enabled);
        String id = breakpointIds.get(index);
        if (!id.isEmpty()) send((enabled ? "-break-enable " : "-break-disable ") + id);
    }
    synchronized void console(String command) {
        if (closed || sender == null || command.isEmpty()) return;
        console.append("(gdb) ").append(command).append('\n');
        send("-interpreter-exec console \"" + escape(command) + "\""); publish();
    }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"); }
    private void publish() {
        changed.accept(new Snapshot(status, hitBreakpoint, sender != null && !closed, exited,
            variables.values(), frames, console.toString(), debug.toString()));
        hitBreakpoint = 0;
    }
    private static String string(Map<String, Object> fields, String key) { Object value = fields.get(key); return value instanceof String ? (String)value : ""; }
    @SuppressWarnings("unchecked") private static Map<String, Object> tuple(Object value) { return value instanceof Map ? (Map<String, Object>)value : Collections.emptyMap(); }
    private static List<?> list(Object value) { return value instanceof List ? (List<?>)value : Collections.emptyList(); }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (sender != null) { sender.offer("-gdb-exit"); sender.offer(""); sender = null; }
        else try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        if (reader != null) reader.interrupt();
    }

    static final class Sender extends LinkedBlockingQueue<String> implements Runnable {
        private final Socket socket;
        Sender(Socket socket) { this.socket = socket; }
        @Override public void run() {
            try (Socket connection = socket; OutputStream output = connection.getOutputStream()) {
                for (String command; !(command = take()).isEmpty();) {
                    output.write((command + "\n").getBytes(StandardCharsets.UTF_8)); output.flush();
                }
            } catch (Exception error) { if (error instanceof InterruptedException) Thread.currentThread().interrupt(); }
        }
    }
}
