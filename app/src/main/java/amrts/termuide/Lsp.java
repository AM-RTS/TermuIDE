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
    private volatile int generation;
    private boolean started;
    private final Set<String> opened = new HashSet<>();
    private JSONObject capabilities;
    Lsp() { this(LanguageServers.Server.CLANGD, null, Application.lsp_host, Application.lsp_port); }
    Lsp(LanguageServers.Server server, String root, String host, int port) {
        this.server = server; this.root = root; this.host = host; this.port = port;
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
        Request(int kind, String path, long revision, int[] selection) {
            this.kind = kind; this.path = path; this.revision = revision; start = selection[0]; end = selection[1];
        }
    }
    int request(File file, int kind) {
        String path = file.getAbsolutePath();
        long revision = -1;
        for (DocumentSession session : Application.getInstance().getDocuments().getState().getValue())
            if (path.equals(session.getPath())) { revision = session.getRevision(); break; }
        int id = nextRequest.getAndIncrement();
        requests.entrySet().removeIf(entry -> entry.getValue().kind == kind && entry.getValue().path.equals(path));
        MainHandler handler = Application.getInstance().hand;
        requests.put(id, new Request(kind, path, revision, handler == null ? new int[]{-1, -1} : handler.selection(path)));
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

    // The existing Termux stdio bridge works for both servers and exits when its socket closes.
    String[] launchArguments() {
        ArrayList<String> args = new ArrayList<>(Arrays.asList("nc", "-l", "-s", host, "-p", Integer.toString(port), "-w", "6"));
        if (server == LanguageServers.Server.CLANGD) args.addAll(Arrays.asList("nice", "-n", "-20"));
        args.addAll(Arrays.asList(server.command));
        return args.toArray(new String[0]);
    }

    boolean isStarted() { return started; }
    public void start(Context context, Handler read) {
        end();
        started = true;
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
        // Drain shutdown/exit before the sentinel closes the socket, including an in-flight connection.
        sender.offer(wrap("shutdown", "{}", 99).getBytes(StandardCharsets.UTF_8));
        sender.offer(wrap("exit", "{}", -1).getBytes(StandardCharsets.UTF_8));
        sender.offer(TERM);
        sender.stopped = true;
        started = false;
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
            sk = connection;
            sender.socket = connection;
            new Thread(sender).start();
            BufferedInputStream br = new BufferedInputStream(connection.getInputStream());
            final int ctlen = CONTENTLEN.length;
            final byte[] lnbf = new byte[128];
            while (true) {
                int len = 0;
                while ((i = Utils.readLine(br, lnbf)) > 0) {
                    if (i > ctlen && Utils.arrNEquals(lnbf, CONTENTLEN, ctlen)) {
                        len = Integer.parseInt(new String(lnbf, ctlen, i - ctlen));
                    }
                }
                if (i < 0)
                    break;
                Message msg = new Message();
                msg.what = tp;
                byte[] strb = new byte[len];
                for (i = 0; i < len;) {
                    int count = br.read(strb, i, len - i);
                    if (count < 0) throw new EOFException("Incomplete language-server message");
                    i += count;
                }
                msg.obj = new Incoming(this, epoch, new String(strb, StandardCharsets.UTF_8));
                if (sender == mSndr && !sender.stopped) reader.sendMessage(msg);
            }
		} catch (Exception ioe) {
			Log.e(TAG, "Language-server connection ended", ioe);
		} finally {
            sender.offer(TERM);
            try { if (connection != null) connection.close(); } catch (IOException ignored) { }
            if (sender == mSndr) {
                ready = false;
                requests.clear();
                Message message = Message.obtain();
                message.what = ERROR; message.obj = new Incoming(this, epoch, null);
                reader.sendMessage(message);
            }
        }
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
		sb.append(",\"capabilities\":{\"workspace\":{\"applyEdit\":true,\"workspaceFolders\":true},\"textDocument\":{\"completion\":{\"completionItem\":{\"snippetSupport\":false}},\"publishDiagnostics\":{\"versionSupport\":true}}}");
		if (root!=null) {
			sb.append(",\"rootUri\":");
			sb.append(JSONObject.quote(new File(root).toURI().toString()));
		}
		if (server == LanguageServers.Server.CLANGD)
            sb.append(",\"initializationOptions\":{\"fallbackFlags\":[\"-Wall\"]}");
        sb.append('}');
		mSndr.send("initialize", sb.toString(), 0);
	}

	public void setCompTrigs(char[] c) {
		compTrigs = c;
	}

	public byte isCompTrig(char c) {
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
		tp = INITIALIZED;
		mSndr.send("initialized", new HashMap<>(), -1);
        requests.clear();
        ready = true;
	}

	public void didClose(File f) {
        if (!opened.remove(LanguageServers.path(f))) return;
        requests.entrySet().removeIf(entry -> entry.getValue().path.equals(f.getAbsolutePath()));
		HashMap<String,String> m = new HashMap<>();
		m.put("uri", Uri.fromFile(f).toString());
		HashMap<String,HashMap<String,String>> k = new HashMap<>();
		k.put("textDocument", m);
		tp = CLOSE;
		mSndr.send("textDocument/didClose", k, -1);
	}

    public boolean didOpen(File f, String lang, int version, String ct) {
        if (!ready || isEnded() || !opened.add(LanguageServers.path(f))) return false;
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
        if (!opened.contains(LanguageServers.path(f))) return;
		StringBuilder s = new StringBuilder("{\"textDocument\":{\"uri\":");
		s.append(JSONObject.quote(Uri.fromFile(f).toString()));
		s.append("}}");
		tp = SAVE;
		mSndr.send("textDocument/didSave", s.toString(), -1);
	}

	public void didChange(File f, int version, String text) {
        if (!opened.contains(LanguageServers.path(f))) return;
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

	public void didChange(File f, int version, List<Range> chs) {
        if (!opened.contains(LanguageServers.path(f))) return;
		StringBuilder sb = new StringBuilder("{\"textDocument\":{\"uri\":");
		sb.append(JSONObject.quote(Uri.fromFile(f).toString()));
		sb.append(",\"version\":");
		sb.append(version);
		sb.append("},\"contentChanges\":[");
		for (int i=0,j=chs.size(); i<j; i++) {
			Range c = chs.get(i);
			sb.append("{\"range\":{\"start\":{\"line\":");
			sb.append(c.stl);
			sb.append(",\"character\":");
			sb.append(c.stc);
			sb.append("},\"end\":{\"line\":");
			sb.append(c.enl);
			sb.append(",\"character\":");
			sb.append(c.enc);
			sb.append("}},\"text\":");
			sb.append(JSONObject.quote(c.msg));
			sb.append("},");
		}
		if (!chs.isEmpty()) sb.setLength(sb.length() - 1);
        sb.append(']');
		sb.append('}');
		tp = CHANGE;
		mSndr.send("textDocument/didChange", sb.toString(), -1);
	}

	public boolean completionTry(File f, int l, int c, char tgc) {
        if (!supports("completionProvider")) return false;
		byte b = isCompTrig(tgc);
		if (b==0)
			return false;
		StringBuilder sb = new StringBuilder("{\"textDocument\":{\"uri\":");
		sb.append(JSONObject.quote(Uri.fromFile(f).toString()));
		sb.append("},\"position\":{\"line\":");
		sb.append(l);
		sb.append(",\"character\":");
		sb.append(c);
		sb.append("},\"context\":{\"triggerKind\":");
		sb.append(b);
		if (b==2) {
			sb.append(",\"triggerCharacter\":\"");
			sb.append(tgc);
			sb.append('"');
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
        sb.append(c);
        sb.append("},\"context\":{\"triggerKind\":2,\"triggerCharacter\":\"");
        sb.append(tgc);
        sb.append("\",\"isRetrigger\":");
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
		sb.append(range.stc);
		sb.append("},\"end\":{\"line\":");
		sb.append(range.enl);
		sb.append(",\"character\":");
		sb.append(range.enc);
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
		sb.append(c);
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
        sb.append(range.stc);
        sb.append("},\"end\":{\"line\":");
        sb.append(range.enl);
        sb.append(",\"character\":");
		sb.append(range.enc);
        sb.append("}},\"context\":{\"diagnostics\":[");
        for (ErrSpan er : diags) {
            sb.append("{\"range\":{\"start\":{\"line\":");
            sb.append(er.stl-1);
            sb.append(",\"character\":");
            sb.append(er.stc);
            sb.append("},\"end\":{\"line\":");
            sb.append(er.enl-1);
            sb.append(",\"character\":");
            sb.append(er.enc);
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
        sb.append(c);
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
        sb.append(c);
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
