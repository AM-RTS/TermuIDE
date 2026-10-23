package cn.rbc.termuc;
import android.util.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class Project
{
	private static long lastModified;
	private static final String 
	KEY_BUILDCMD = "buildCmd",
	KEY_COMPILE = "compileCmd",
	KEY_RUNCMD = "runCmd",
	KEY_OUTPUTDIR = "outputDir",
	KEY_OPENS = "opens";
	public final static String PROJ = "termuc.json";
	public static String rootPath, outputDir;
	public static String buildCmd, compileCmd, runCmd;

    public static final class Config {
        public final String rootPath, outputDir, buildCmd, compileCmd, runCmd;
        public final long modified;
        public final List<String> opens;
        public Config(String rootPath, String outputDir, String buildCmd, String compileCmd, String runCmd, long modified, List<String> opens) {
            this.rootPath = rootPath; this.outputDir = outputDir; this.buildCmd = buildCmd;
            this.compileCmd = compileCmd; this.runCmd = runCmd; this.modified = modified;
            this.opens = Collections.unmodifiableList(new ArrayList<>(opens));
        }
    }

    public static synchronized Config snapshot() {
        return new Config(rootPath, outputDir == null ? "build" : outputDir, buildCmd == null ? "" : buildCmd,
            compileCmd == null ? "" : compileCmd, runCmd == null ? "" : runCmd, lastModified, Collections.emptyList());
    }

    public static Config refreshed(Config original) throws IOException {
        if (original.rootPath == null) return original;
        File file = new File(original.rootPath, PROJ);
        return FileHelper.lastModified(file) == original.modified ? original : read(file);
    }

    public static synchronized void publish(Config config) {
        rootPath = config.rootPath; outputDir = config.outputDir; buildCmd = config.buildCmd;
        compileCmd = config.compileCmd; runCmd = config.runCmd; lastModified = config.modified;
    }

	private static void setDefault() {
		buildCmd = "clang $d/$f -o $o/$e -lm -Wall";
		compileCmd = "clang -c $d/$f -o $o/$e.o -lm -Wall";
		runCmd = "$x/$e";
		outputDir = "build";
	}

	public static void load(File conf, List<String> opens) throws IOException {
        Config config = read(conf);
        publish(config);
        if (opens != null) { opens.clear(); opens.addAll(config.opens); }
    }

    static Config read(File conf) throws IOException {
        long modified = FileHelper.lastModified(conf);
        String build = "clang $d/$f -o $o/$e -lm -Wall", compile = "clang -c $d/$f -o $o/$e.o -lm -Wall";
        String run = "$x/$e", output = "build";
        List<String> files = new ArrayList<>();
        try (JsonReader p = new JsonReader(new BufferedReader(new InputStreamReader(FileHelper.openInputStream(conf), java.nio.charset.StandardCharsets.UTF_8)))) {
		p.beginObject();
		while (p.hasNext()) {
			switch (p.nextName()) {
				case KEY_BUILDCMD: build = p.nextString(); break;
				case KEY_COMPILE: compile = p.nextString(); break;
				case KEY_RUNCMD: run = p.nextString(); break;
				case KEY_OUTPUTDIR: output = p.nextString(); break;
				case KEY_OPENS:
					p.beginArray();
					while (p.hasNext())
						files.add(p.nextString());
					p.endArray();
					break;
				default: p.skipValue();
			}
		}
		p.endObject();
        if (p.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing project configuration data");
        } catch (IllegalStateException | NumberFormatException error) { throw new IOException("Invalid project configuration", error); }
        if (FileHelper.lastModified(conf) != modified) throw new IOException("Project configuration changed while reading");
        return new Config(conf.getParent(), output, build, compile, run, modified, files);
	}

    public static CompletableFuture<Boolean> save(Iterable<String> opens) {
        Config original = snapshot();
        if (original.rootPath == null) return CompletableFuture.completedFuture(false);
        File file = new File(original.rootPath, PROJ);
        List<String> paths = new ArrayList<>();
        if (opens != null) for (String path : opens) paths.add(path);
        AtomicReference<Config> written = new AtomicReference<>();
        // Read current commands on the save worker, including edits just saved in the editor.
        return Application.getInstance().saver.saveFile(file.getAbsolutePath(), () -> {
                Config current = read(file); written.set(current); return encode(current, paths);
            })
            .thenApply(saved -> {
                if (saved && Objects.equals(rootPath, original.rootPath)) {
                    Config current = written.get();
                    publish(new Config(current.rootPath, current.outputDir, current.buildCmd, current.compileCmd,
                        current.runCmd, FileHelper.lastModified(file), paths));
                }
                return saved;
            });
    }

    private static String encode(Config config, Iterable<String> opens) throws IOException {
        StringWriter text = new StringWriter();
        try (JsonWriter w = new JsonWriter(text)) {
            w.setIndent("  ");
            w.beginObject();
            w.name(KEY_BUILDCMD).value(config.buildCmd);
            w.name(KEY_COMPILE).value(config.compileCmd);
            w.name(KEY_RUNCMD).value(config.runCmd);
            w.name(KEY_OUTPUTDIR).value(config.outputDir);
            w.name(KEY_OPENS).beginArray();
            if (opens != null) for (String path : opens) w.value(path);
            w.endArray();
            w.endObject();
        }
        return text.toString();
    }

	public static boolean create(File f) {
		rootPath = f.getAbsolutePath();
		setDefault();
		try {
			return FileHelper.createFile(f, true)
			&& FileHelper.createFile(new File(f, outputDir), true)
			&& writeNewConfiguration();
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
	}

    private static boolean writeNewConfiguration() throws IOException {
        File file = new File(rootPath, PROJ);
        SourceWriter.write(file, encode(snapshot(), null));
        lastModified = FileHelper.lastModified(file);
        return true;
    }

	public static void close() {
		rootPath = null;
	}

	public static long lastModified() {
		return lastModified;
	}

	public static void reload() throws IOException {
        publish(refreshed(snapshot()));
	}
	/**
	 * Local environment variables
	 * $f  file name
	 * $e  file name without suffix
	 * $d  file directory path
	 * $p  project root path (if in project)
	 * $o  outputDir
	 */
	public static StringBuilder buildEnvironment(File file) {
        return new StringBuilder(RunCommands.environment(snapshot(), file));
	}
}
