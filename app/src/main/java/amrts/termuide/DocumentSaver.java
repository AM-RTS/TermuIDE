package amrts.termuide;

import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongConsumer;

/** Application-owned source writes, serialized independently of recovery checkpoints. */
final class DocumentSaver {
    interface Writer { void write(File file, String text) throws IOException; }
    private final DocumentSessions sessions;
    private final Writer writer;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Source saves"); thread.setDaemon(true); return thread;
    });
    private final Map<String, CompletableFuture<Boolean>> pending = new HashMap<>();

    DocumentSaver(DocumentSessions sessions) { this(sessions, SourceWriter::write); }
    DocumentSaver(DocumentSessions sessions, Writer writer) { this.sessions = sessions; this.writer = writer; }

    boolean isSaving(String path) { return pending.containsKey(path); }
    boolean isSaving() { return !pending.isEmpty(); }
    CompletableFuture<Boolean> pending(String path) { return pending.get(path); }

    CompletableFuture<Boolean> save(String path, String text, Long expectedModified, LongConsumer applyResult) {
        return enqueue(path, () -> text, sessions.prepareSave(path), expectedModified, applyResult);
    }

    CompletableFuture<Boolean> saveFile(String path, Callable<String> text) {
        return enqueue(path, text, null, null, ignored -> {});
    }

    private CompletableFuture<Boolean> enqueue(String path, Callable<String> content,
            DocumentSessions.SavePoint point, Long expectedModified, LongConsumer applyResult) {
        if (isSaving(path)) throw new IllegalStateException("Wait for the previous source save");
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        pending.put(path, result);
        try { worker.execute(() -> {
            IOException failure = null;
            long modified = 0;
            try {
                // A failed recovery checkpoint must not be followed by a source replacement.
                sessions.flush();
                if (point != null && !sessions.owns(point)) throw new IOException("Document was closed before saving");
                File file = new File(path);
                long before = FileHelper.lastModified(file);
                String text = content.call();
                if ((expectedModified != null && FileHelper.lastModified(file) != expectedModified)
                        || (point == null && FileHelper.lastModified(file) != before))
                    throw new IOException("Source changed outside the editor");
                writer.write(file, text);
                modified = FileHelper.lastModified(file);
            } catch (IOException error) { failure = error; }
            catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                failure = new IOException("Unable to save source", error);
            }
            IOException error = failure;
            long timestamp = modified;
            main.post(() -> {
                pending.remove(path);
                try {
                    if (error == null) {
                        boolean clean = point == null || sessions.completeSave(point);
                        applyResult.accept(timestamp);
                        result.complete(clean);
                    } else result.completeExceptionally(error);
                } catch (RuntimeException completionError) { result.completeExceptionally(completionError); }
            });
        }); } catch (RejectedExecutionException error) {
            pending.remove(path);
            result.completeExceptionally(new IOException("Source save worker is unavailable", error));
        }
        return result;
    }

    void shutdown() { worker.shutdown(); }
}
