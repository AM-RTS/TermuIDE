package amrts.termuide;
import android.content.*;
import android.net.*;
import android.os.*;
import android.util.*;
import cn.rbc.codeeditor.util.*;
import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;
import org.json.*;

import cn.rbc.codeeditor.util.Range;

public final class Lsp implements Runnable {
	final static int INITIALIZE = 0, INITIALIZED = 1,
	OPEN = 2, CLOSE = 3,
	COMPLETION = 4, FIX = 5, CHANGE = 6, SAVE = 7, NOTI = 8, SIGN_HELP = 9,
    FORMAT = 10, HIGHLIGHT = 11, CODEACTION = 12, EXECCMD = 13, SEMTOK = 14,
    SEMTOKD = 15, DEFIN = 16, RENAM = 17,
	ERROR = -1;
    static enum SemToken {
        NAMESPACE, TYPE, CLASS, ENUM, INTERFACE, STRUCT, TYPEPARAMETER, PARAMETER, VARIABLE, PROPERTY, ENUMMEMBER, EVENT, FUNCTION, METHOD, MACRO, KEYWORD, MODIFIER, COMMENT, STRING, NUMBER, REGEXP, OPERATOR, UNKNOWN
    };
	private final static String TAG = "LSP";
	private final static byte[] CONTENTLEN = "Content-Length: ".getBytes();
	private int tp;
    private final java.util.concurrent.atomic.AtomicInteger nextRequest = new java.util.concurrent.atomic.AtomicInteger(100);
    final LanguageServers.Server server;
    final String root, host;
    final int port;
    final String environment;
    private String executable;
    private LanguageServers.State state = LanguageServers.State.STOPPED;
    private String detail;
    String configurationError;
    private volatile int generation;
    private boolean started;
    private volatile boolean failed;
    private static final class OpenDocument {
        int version;
        String text;
        OpenDocument(int version, String text) { this.version = version; this.text = text; }
    }
    private final Map<String, OpenDocument> opened = new HashMap<>();
    private JSONObject capabilities;
    Lsp() { this(LanguageServers.Server.CLANGD, null, Application.lsp_host, Application.lsp_port); }
    Lsp(LanguageServers.Server server, String root, String host, int port) { this(server, root, host, port, ""); }
    Lsp(LanguageServers.Server server, String root, String host, int port, String environment) {
        this.server = server; this.root = root; this.host = host; this.port = port; this.environment = environment;
    }
    LanguageServers.State state() { return state; }
    String detail() { return detail; }
    void setState(LanguageServers.State state, String detail) {
        this.state = state; this.detail = detail;
        Application app = Application.getInstance();
        if (app != null && app.hand != null) app.hand.languageServerChanged();
    }
    void configureRuntime(String executable) { this.executable = executable; }
    void initializationFailed(String message) {
        end(); failed = true; setState(LanguageServers.State.FAILED, message);
    }
    void connectionFailed() {
        setState(state == LanguageServers.State.READY ? LanguageServers.State.DISCONNECTED : LanguageServers.State.FAILED,
            Application.getInstance().getString(R.string.lsp_connection_ended));
    }
    static final class Incoming {
        final Lsp source;
        final int generation;
        final String json;
        Incoming(Lsp source, int generation, String json) { this.source = source; this.generation = generation; this.json = json; }
        boolean isCurrent() { return generation == source.generation; }
    }
    Incoming incoming(String json) { return new Incoming(this, generation, json); }
    private final ConcurrentHashMap<Integer, Request> requests = new ConcurrentHashMap<>();
    static final class Request {
        final int kind;
        final String path;
        final long revision;
        final int start, end;
        final Map<String, Long> revisions;
        Request(int kind, String path, long revision, int[] selection, Map<String, Long> revisions) {
            this.kind = kind; this.path = path; this.revision = revision; start = selection[0]; end = selection[1]; this.revisions = revisions;
        }
    }
    int request(File file, int kind) {
        String path = file.getAbsolutePath();
        MainHandler handler = Application.getInstance().hand;
        if (handler != null) handler.synchronize(path);
        long revision = -1;
        Map<String, Long> revisions = kind == RENAM || kind == CODEACTION ? new HashMap<>() : Collections.emptyMap();
        for (DocumentSession session : Application.getInstance().getDocuments().getState().getValue()) {
            if (kind == RENAM || kind == CODEACTION) revisions.put(LanguageServers.path(new File(session.getPath())), session.getRevision());
            if (path.equals(session.getPath())) revision = session.getRevision();
        }
        int id = nextRequest.getAndIncrement();
        requests.entrySet().removeIf(entry -> entry.getValue().kind == kind && entry.getValue().path.equals(path));
        requests.put(id, new Request(kind, path, revision, handler == null ? new int[]{-1, -1} : handler.selection(path), revisions));
        return id;
    }
    Request takeRequest(int id) { return requests.remove(id); }

	private volatile Socket sk = new Socket();
	private volatile Sender mSndr = new Sender();
	private char[] compTrigs = {}, sigTrigs = {};
    private SemToken[] semToks = {};
    private static final byte[] TERM = {};
	private Handler mRead;
    private volatile boolean ready;

    // All language servers share the Termux stdio bridge and exit when its socket closes.
    String[] launchArguments() {
        ArrayList<String> args = new ArrayList<>(Arrays.asList("nc", "-l", "-s", host, "-p", Integer.toString(port), "-w", "6"));
        if (server == LanguageServers.Server.CLANGD) args.addAll(Arrays.asList("nice", "-n", "-20"));
        if (server == LanguageServers.Server.PYTHON && !environment.isEmpty()) {
            args.addAll(Arrays.asList(Utils.PREF + "/usr/bin/bash", "-c",
                "unset PYTHONHOME;export VIRTUAL_ENV=\"$1\";export PATH=\"$1/bin:$PATH\";shift;exec \"$@\"", "termuide-lsp", environment));
        }
        if (executable == null) args.addAll(Arrays.asList(server.command));
        else {
            args.add(executable);
            if (server == LanguageServers.Server.CLANGD) args.add("--header-insertion-decorators=0");
        }
        if (server == LanguageServers.Server.PYTHON) args.addAll(RunCommands.pythonLanguageServerArguments());
        return args.toArray(new String[0]);
    }

    boolean isStarted() { return started; }
    boolean hasFailed() { return failed; }
    public void start(Context context, Handler read) {
        end();
        started = true;
        setState(LanguageServers.State.STARTING, null);
        failed = false;
        ready = false;
        capabilities = null;
        compTrigs = new char[0]; sigTrigs = new char[0]; semToks = new SemToken[0];
        mRead = read;
        Sender sender = new Sender();
        mSndr = sender;
        Utils.run(context, "/system/bin/toybox", launchArguments(), root == null ? Utils.ROOT.getAbsolutePath() : root, true);
        int epoch = generation;
        new Thread(() -> run(sender, read, epoch), "LSP-" + server.id).start();
    }

    public void end() {
        ++generation;
        ready = false;
        opened.clear();
        requests.clear();
        Sender sender = mSndr;
        sender.stopped = true;
        sender.clear();
        // Pending edits/requests are obsolete; allow a short graceful exit, then force socket release.
        Socket connection = sender.socket;
        if (connection != null && mRead != null) mRead.postDelayed(() -> {
            try { connection.close(); } catch (IOException ignored) { }
        }, 1000);
        sender.offer(wrap("shutdown", "{}", 99).getBytes(StandardCharsets.UTF_8));
        sender.offer(wrap("exit", "{}", -1).getBytes(StandardCharsets.UTF_8));
        sender.offer(TERM);
        started = false;
        setState(LanguageServers.State.STOPPED, null);
    }

	public boolean isEnded() {
		return mSndr.stopped || sk.isClosed() || !sk.isConnected();
	}

	public void run() {
		run(mSndr, mRead, generation);
    }

    private void run(Sender sender, Handler reader, int epoch) {
		int i = 0;
		Socket connection = null;
        Runnable initializationDeadline = null;
		try{
			do {
                if (sender.stopped || sender != mSndr) return;
				try {
                    connection = new Socket();
                    connection.connect(new InetSocketAddress(host, port), 500);
				} catch (IOException s) {
                    if (connection != null) connection.close();
					Thread.sleep(250L);
				}
				i++;
			} while (i<=20 && (connection == null || !connection.isConnected()));
			if (connection == null || !connection.isConnected())
				throw new IOException("Connection failed");
            if (sender.stopped || sender != mSndr) return;
            final Socket transport = connection;
            initializationDeadline = () -> {
                if (!ready && epoch == generation && sender == mSndr) {
                    try { transport.close(); } catch (IOException ignored) { }
                }
            };
            reader.postDelayed(initializationDeadline, 30000);
            sk = connection;
            sender.socket = connection;
            new Thread(sender).start();
            BufferedInputStream br = new BufferedInputStream(connection.getInputStream());
            for (String payload; (payload = readPayload(br)) != null;) {
                Message msg = Message.obtain();
                msg.obj = new Incoming(this, epoch, payload);
                if (sender == mSndr && !sender.stopped) reader.sendMessage(msg);
            }
		} catch (Exception ioe) {
			Log.e(TAG, "Language-server connection ended", ioe);
		} finally {
            if (initializationDeadline != null) reader.removeCallbacks(initializationDeadline);
            sender.offer(TERM);
            try { if (connection != null) connection.close(); } catch (IOException ignored) { }
            if (sender == mSndr) {
                if (epoch == generation) failed = true;
                ready = false;
                requests.clear();
                Message message = Message.obtain();
                message.what = ERROR; message.obj = new Incoming(this, epoch, null);
                reader.sendMessage(message);
            }
        }
	}

    static String readPayload(InputStream input) throws IOException {
        int length = -1, headerBytes = 0;
        for (;;) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            for (;;) {
                int value = input.read();
                if (value < 0) {
                    if (headerBytes == 0) return null;
                    throw new EOFException("Incomplete language-server header");
                }
                if (++headerBytes > 8192) throw new IOException("Language-server header is too large");
                if (value == '\r') {
                    if (input.read() != '\n') throw new IOException("Malformed language-server header");
                    if (++headerBytes > 8192) throw new IOException("Language-server header is too large");
                    break;
                }
                if (value == '\n' || value > 127) throw new IOException("Malformed language-server header");
                line.write(value);
            }
            String header = line.toString(StandardCharsets.US_ASCII.name());
            if (header.isEmpty()) break;
            int colon = header.indexOf(':');
            if (colon < 1) throw new IOException("Malformed language-server header");
            if ("Content-Length".equalsIgnoreCase(header.substring(0, colon))) {
                if (length >= 0) throw new IOException("Duplicate Content-Length");
                try { length = Integer.parseInt(header.substring(colon + 1).trim()); }
                catch (NumberFormatException error) { throw new IOException("Invalid Content-Length", error); }
                // Bound server-controlled allocations; large/malformed replies must not kill recovery.
                if (length <= 0 || length > 64 * 1024 * 1024) throw new IOException("Invalid Content-Length");
            }
        }
        if (length < 0) throw new IOException("Missing Content-Length");
        byte[] payload = new byte[length];
        for (int offset = 0; offset < length;) {
            int count = input.read(payload, offset, length - offset);
            if (count < 0) throw new EOFException("Incomplete language-server message");
            offset += count;
        }
        return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(payload)).toString();
    }

	protected static String wrap(String m, Object p, int id) {
		StringBuilder s = new StringBuilder("{\"jsonrpc\":\"2.0\"");
		if (id >= 0) {
			s.append(",\"id\":");s.append(id);
        }
		s.append(",\"method\":\"");s.append(m);
		s.append("\",\"params\":");s.append(JSONObject.wrap(p));
		s.append("}");
		return s.toString();
	}

	public void initialize(String root) {
		tp = INITIALIZE;
		StringBuilder sb = new StringBuilder("{\"processId\":");
		sb.append("null"); // Termux cannot use this app's PID to monitor its parent.
		sb.append(",\"capabilities\":{\"general\":{\"positionEncodings\":[\"utf-16\",\"utf-32\"]},\"workspace\":{\"applyEdit\":true,\"workspaceFolders\":true},\"textDocument\":{\"completion\":{\"completionItem\":{\"snippetSupport\":false}},\"publishDiagnostics\":{\"versionSupport\":true}}}");
		if (root!=null) {
			sb.append(",\"rootUri\":");
			sb.append(JSONObject.quote(new File(root).toURI().toString()));
		}
		if (server == LanguageServers.Server.CLANGD)
            sb.append(",\"initializationOptions\":{\"fallbackFlags\":[\"-Wall\"]}");
        if (server == LanguageServers.Server.PYTHON && executable != null)
            sb.append(",\"initializationOptions\":{\"pylsp\":{\"plugins\":{\"jedi\":{\"environment\":")
                .append(JSONObject.quote(executable)).append("}}}}");
        sb.append('}');
		mSndr.send("initialize", sb.toString(), 0);
	}

	public void setCompTrigs(char[] c) {
		compTrigs = c;
	}

    public byte isCompTrig(char c) { return isCompTrig((int)c); }
	public byte isCompTrig(int c) {
		if (Character.isJavaIdentifierPart(c))
			return 1;
		for (int i=0,l=compTrigs.length; i<l; i++)
			if (compTrigs[i] == c)
				return 2;
		return 0;
	}

    public void setSigTrigs(char[] c) {
        sigTrigs = c;
    }

    public boolean isSigTrig(char c) {
        char[] sigs = sigTrigs;
        for (int i=0,l=sigs.length; i<l; i++)
            if (sigs[i] == c)
                return true;
        return false;
    }

    public void setSemToks(SemToken[] toks) {
        semToks = toks;
    }

    void setCapabilities(JSONObject capabilities) { this.capabilities = capabilities == null ? new JSONObject() : capabilities; }
    boolean supports(String capability) {
        // Java starts with completion and diagnostics; advanced tools need separate adapter validation.
        if (server == LanguageServers.Server.JAVA && !"completionProvider".equals(capability)) return false;
        if (capabilities == null) return true;
        Object value = capabilities.opt(capability);
        return value != null && value != JSONObject.NULL && !Boolean.FALSE.equals(value);
    }

    public int nativeToken(int semTok) {
        if (semTok < 0 || semTok >= semToks.length) return Tokenizer.UNKNOWN;
        switch (semToks[semTok]) {
            case NAMESPACE:
            case TYPE:
            case CLASS:
            case ENUM:
            case STRUCT:
            case INTERFACE: return Tokenizer.TYPE;
            case KEYWORD: return Tokenizer.KEYWORD;
        }
        return Tokenizer.UNKNOWN;
    }

    static SemToken semValueOf(String str) {
        try {
            return SemToken.valueOf(str);
        } catch (IllegalArgumentException ie) {
            return SemToken.UNKNOWN;
        }
    }

	public void initialized() {
        if (isEnded()) return;
		tp = INITIALIZED;
		mSndr.send("initialized", new HashMap<>(), -1);
        if (server == LanguageServers.Server.JAVA && root != null) {
            // An explicit classpath keeps this initial integration out of Maven/Bazel auto-import.
            mSndr.send("workspace/didChangeConfiguration", Collections.singletonMap("settings",
                Collections.singletonMap("java", Collections.singletonMap("classPath", Collections.singletonList(root)))), -1);
        }
        requests.clear();
        ready = true;
        setState(LanguageServers.State.READY, null);
	}

	public void didClose(File f) {
        OpenDocument document = opened.get(LanguageServers.path(f));
        if (document == null || document.text == null) return;
        document.text = null;
        requests.entrySet().removeIf(entry -> entry.getValue().path.equals(f.getAbsolutePath()));
		HashMap<String,String> m = new HashMap<>();
		m.put("uri", Uri.fromFile(f).toString());
		HashMap<String,HashMap<String,String>> k = new HashMap<>();
		k.put("textDocument", m);
		tp = CLOSE;
		mSndr.send("textDocument/didClose", k, -1);
	}

    public boolean didOpen(File f, String lang, int version, String ct) {
        if (!ready || isEnded()) return false;
        OpenDocument previous = opened.get(LanguageServers.path(f));
        if (previous != null && previous.text != null) return false;
        version = Math.max(version, previous == null ? (server == LanguageServers.Server.PYTHON ? 1 : 0) : previous.version + 1);
        opened.put(LanguageServers.path(f), new OpenDocument(version, ct));
		StringBuilder m = new StringBuilder("{\"textDocument\":{\"uri\":");
		m.append(JSONObject.quote(Uri.fromFile(f).toString()));
		m.append(",\"languageId\":\"");m.append(lang);
		m.append("\",\"version\":"); m.append(version); m.append(",\"text\":");
		m.append(JSONObject.quote(ct));
		m.append("}}");
		tp = OPEN;
		mSndr.send("textDocument/didOpen", m.toString(), -1);
        return true;
	}

	public void didSave(File f) {
        if (text(f) == null) return;
		StringBuilder s = new StringBuilder("{\"textDocument\":{\"uri\":");
		s.append(JSONObject.quote(Uri.fromFile(f).toString()));
		s.append("}}");
		tp = SAVE;
		mSndr.send("textDocument/didSave", s.toString(), -1);
	}

	public void didChange(File f, int version, String text) {
        if (text(f) == null || !ready || isEnded()) return;
        opened.put(LanguageServers.path(f), new OpenDocument(version, text));
		StringBuilder sb = new StringBuilder("{\"textDocument\":{\"uri\":");
		sb.append(JSONObject.quote(Uri.fromFile(f).toString()));
		sb.append(",\"version\":");
		sb.append(version);
		sb.append("},\"contentChanges\":[{\"text\":");
		sb.append(JSONObject.quote(text));
		sb.append("}]}").toString();
		tp = CHANGE;
		mSndr.send("textDocument/didChange", sb.toString(), -1);
	}

    public void didChange(File file, int version, List<Range> changes) {
        String original = text(file);
        if (original == null) return;
        StringBuilder updated = new StringBuilder(original);
        for (Range change : changes) {
            TextPositions positions = new TextPositions(updated.toString());
            int start = positions.offset(change.stl, change.stc), end = positions.offset(change.enl, change.enc);
            if (end < start) throw new IllegalArgumentException("Reversed change range");
            updated.replace(start, end, change.msg);
        }
        didChange(file, version, updated.toString());
    }

    String text(File file) {
        OpenDocument document = opened.get(LanguageServers.path(file));
        return document == null ? null : document.text;
    }
    int documentVersion(File file) {
        OpenDocument document = opened.get(LanguageServers.path(file));
        return document == null ? -1 : document.version;
    }
    boolean codePointPositions() {
        String encoding = capabilities == null ? "" : capabilities.optString("positionEncoding");
        // pylsp/Jedi currently counts Python code points without advertising positionEncoding.
        return "utf-32".equals(encoding) || (encoding.isEmpty() && server == LanguageServers.Server.PYTHON);
    }
    int column(File file, int line, int utf16Column) {
        MainHandler handler = Application.getInstance().hand;
        if (handler != null) handler.synchronize(file.getAbsolutePath());
        String text = text(file);
        return !codePointPositions() || text == null ? utf16Column : new TextPositions(text).codePointColumn(line, utf16Column);
    }
    int offset(TextPositions positions, int line, int column) {
        return codePointPositions() ? positions.codePointOffset(line, column) : positions.offset(line, column);
    }

    public boolean completionTry(File f, int line, int column, char trigger) { return completionTry(f, line, column, (int)trigger); }
	public boolean completionTry(File f, int l, int c, int tgc) {
        if (!supports("completionProvider")) return false;
		byte b = isCompTrig(tgc);
		if (b==0)
			return false;
		StringBuilder sb = new StringBuilder("{\"textDocument\":{\"uri\":");
		sb.append(JSONObject.quote(Uri.fromFile(f).toString()));
		sb.append("},\"position\":{\"line\":");
		sb.append(l);
		sb.append(",\"character\":");
		sb.append(column(f, l, c));
		sb.append("},\"context\":{\"triggerKind\":");
		sb.append(b);
		if (b==2) {
			sb.append(",\"triggerCharacter\":");
            sb.append(JSONObject.quote(new String(Character.toChars(tgc))));
		}
		sb.append("}}");
		tp = COMPLETION;
		//Log.d(TAG, sb.toString());
		mSndr.send("textDocument/completion", sb.toString(), request(f, COMPLETION));
		return true;
	}

    public boolean signatureHelpTry(File f, int l, int c, char tgc, boolean retrig) {
        if (!supports("signatureHelpProvider")) return false;
        if (!isSigTrig(tgc)) return false;
        StringBuilder sb = new StringBuilder("{\"textDocument\":{\"uri\":");
        sb.append(JSONObject.quote(Uri.fromFile(f).toString()));
        sb.append("},\"position\":{\"line\":");
        sb.append(l);
        sb.append(",\"character\":");
        sb.append(column(f, l, c));
        sb.append("},\"context\":{\"triggerKind\":2,\"triggerCharacter\":");
        sb.append(JSONObject.quote(Character.toString(tgc)));
        sb.append(",\"isRetrigger\":");
        sb.append(retrig);
        sb.append("}}");
        tp = SIGN_HELP;
        mSndr.send("textDocument/signatureHelp", sb.toString(), request(f, SIGN_HELP));
        return true;
    }

	public void formatting(File fl, int tabSize, boolean useSpace) {
        if (!supports("documentFormattingProvider")) return;
		StringBuilder sb = new StringBuilder("{\"textDocument\":{\"uri\":");
		sb.append(JSONObject.quote(Uri.fromFile(fl).toString()));
		sb.append("},\"options\":{\"tabSize\":");
		sb.append(tabSize);
		sb.append(",\"insertSpaces\":");
		sb.append(useSpace);
		sb.append("}}");
        tp = FORMAT;
		//Log.d(TAG, sb.toString());
		mSndr.send("textDocument/formatting", sb.toString(), request(fl, FORMAT));
	}

	public void rangeFormatting(File fl, Range range, int tabSize, boolean useSpace) {
        if (!supports("documentRangeFormattingProvider")) return;
		StringBuilder sb = new StringBuilder("{\"textDocument\":{\"uri\":");
		sb.append(JSONObject.quote(Uri.fromFile(fl).toString()));
		sb.append("},\"range\":{\"start\":{\"line\":");
		sb.append(range.stl);
		sb.append(",\"character\":");
		sb.append(column(fl, range.stl, range.stc));
		sb.append("},\"end\":{\"line\":");
		sb.append(range.enl);
		sb.append(",\"character\":");
		sb.append(column(fl, range.enl, range.enc));
		sb.append("}},\"options\":{\"tabSize\":");
		sb.append(tabSize);
		sb.append(",\"insertSpaces\":");
		sb.append(useSpace);
		sb.append("}}");
        tp = FORMAT;
		mSndr.send("textDocument/rangeFormatting", sb.toString(), request(fl, FORMAT));
	}

	public void documentHighlight(File fl, int l, int c) {
        if (!supports("documentHighlightProvider")) return;
		StringBuilder sb = new StringBuilder("{\"textDocument\":{\"uri\":");
		sb.append(JSONObject.quote(Uri.fromFile(fl).toString()));
		sb.append("},\"position\":{\"line\":");
		sb.append(l);
		sb.append(",\"character\":");
		sb.append(column(fl, l, c));
		sb.append("}}");
        tp = HIGHLIGHT;
		mSndr.send("textDocument/documentHighlight", sb.toString(), request(fl, HIGHLIGHT));
	}

    public void codeAction(File fl, Range range, List<ErrSpan> diags) {
        if (!supports("codeActionProvider")) return;
        StringBuilder sb = new StringBuilder("{\"textDocument\":{\"uri\":");
        sb.append(JSONObject.quote(Uri.fromFile(fl).toString()));
        sb.append("},\"range\":{\"start\":{\"line\":");
        sb.append(range.stl);
        sb.append(",\"character\":");
        sb.append(column(fl, range.stl, range.stc));
        sb.append("},\"end\":{\"line\":");
        sb.append(range.enl);
        sb.append(",\"character\":");
		sb.append(column(fl, range.enl, range.enc));
        sb.append("}},\"context\":{\"diagnostics\":[");
        for (ErrSpan er : diags) {
            sb.append("{\"range\":{\"start\":{\"line\":");
            sb.append(er.stl-1);
            sb.append(",\"character\":");
            sb.append(column(fl, er.stl-1, er.stc));
            sb.append("},\"end\":{\"line\":");
            sb.append(er.enl-1);
            sb.append(",\"character\":");
            sb.append(column(fl, er.enl-1, er.enc));
            sb.append("}},\"message\":");
            sb.append(JSONObject.quote(er.msg));
            sb.append(",\"severity\":");
            sb.append(er.severity+1);
            Diagnostic dg;
            if (er instanceof Diagnostic && (dg = (Diagnostic)er).code != null) {
                
                sb.append(",\"code\":");
                if (dg.code instanceof String)
                    sb.append(JSONObject.quote((String)dg.code));
                 else
                    sb.append(dg.code);
            }
            sb.append("},");
        }
        if (!diags.isEmpty()) sb.setLength(sb.length() - 1);
        sb.append(']');
        sb.append("}}");
        tp = EXECCMD;
        mSndr.send("textDocument/codeAction", sb.toString(), request(fl, CODEACTION));
    }

    public void executeCommand(Command cmd) {
        StringBuilder sb = new StringBuilder("{\"command\":");
        sb.append(JSONObject.quote(cmd.command));
        sb.append(",\"arguments\":");
        sb.append(cmd.args);
        sb.append('}');
        mSndr.send("workspace/executeCommand", sb.toString(), EXECCMD);
    }

    public void reply(Object id, String result) {
        StringBuilder sb = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":");
        sb.append(id instanceof String ? JSONObject.quote((String)id) : id);
        sb.append(",\"result\":");
        sb.append(result);
        sb.append('}');
        mSndr.offer(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    void replyError(Object id, int code, String message) throws JSONException {
        JSONObject response = new JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", new JSONObject().put("code", code).put("message", message));
        mSndr.offer(response.toString().getBytes(StandardCharsets.UTF_8));
    }

    public void semanticTokensFull(File f) {
        if (!supports("semanticTokensProvider")) return;
        if (capabilities != null) {
            JSONObject provider = capabilities.optJSONObject("semanticTokensProvider");
            if (provider == null || !provider.has("full") || Boolean.FALSE.equals(provider.opt("full"))) return;
        }
        StringBuilder sb = new StringBuilder("{\"textDocument\":{\"uri\":");
        sb.append(JSONObject.quote(Uri.fromFile(f).toString()));
        sb.append("}}");
        tp = SEMTOK;
        mSndr.send("textDocument/semanticTokens/full", sb.toString(), request(f, SEMTOK));
    }

    public void definition(File f, int l, int c) {
        if (!supports("definitionProvider")) return;
        StringBuilder sb = new StringBuilder("{\"textDocument\":{\"uri\":");
        sb.append(JSONObject.quote(Uri.fromFile(f).toString()));
        sb.append("},\"position\":{\"line\":");
        sb.append(l);
        sb.append(",\"character\":");
        sb.append(column(f, l, c));
        sb.append("}}");
        tp = DEFIN;
        mSndr.send("textDocument/definition", sb.toString(), request(f, DEFIN));
    }

    public void rename(File f, int l, int c, String newName) {
        if (!supports("renameProvider")) return;
        StringBuilder sb = new StringBuilder("{\"textDocument\":{\"uri\":");
        sb.append(JSONObject.quote(Uri.fromFile(f).toString()));
        sb.append("},\"position\":{\"line\":");
        sb.append(l);
        sb.append(",\"character\":");
        sb.append(column(f, l, c));
        sb.append("},\"newName\":");
        sb.append(JSONObject.quote(newName));
        sb.append('}');
        tp = RENAM;
        mSndr.send("textDocument/rename", sb.toString(), request(f, RENAM));
    }

	public void shutdown() {
		mSndr.send("shutdown", "{}", 0);
	}

	public void exit() {
		mSndr.send("exit", "{}", -1);
	}

	public boolean isConnected() {
		return !isEnded();
	}

	class Sender extends LinkedBlockingQueue<byte[]> implements Runnable {
        volatile boolean stopped;
        Socket socket;
		public final void send(String cmd, Object hm, int id) {
            if (!"initialize".equals(cmd) && !"initialized".equals(cmd) && (!ready || isEnded())) {
                requests.remove(id);
                return;
            }
			offer(wrap(cmd, hm, id).getBytes(StandardCharsets.UTF_8));
		}

		public synchronized void run() {
			try {
                OutputStream ow = socket.getOutputStream();
                for (;;) {
                    byte[] s = take();
                    if (s == TERM) {
                        break;
                    }
				    ow.write(CONTENTLEN);
                    ow.write((s.length+"\r\n\r\n").getBytes());
				    ow.write(s);
				    ow.flush();
                }
			} catch (Exception ioe) {
				ioe.printStackTrace();
			} finally {
                stopped = true;
                try { socket.close(); } catch (IOException ignored) { }
                clear();
            }
		}

        public Stream<byte[]> stream() {
            return null;
        }

        public Stream<byte[]> parallelStream() {
            return null;
        }
	}
}
