package cn.rbc.termuc;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;

/** Publishes a complete source file; never truncates the destination in place. */
final class SourceWriter {
    private SourceWriter() {}

    static void write(File file, String text) throws IOException {
        checkInterrupted();
        if (FileHelper.isTermuxFile(file)) writeProvider(file, text);
        else writeLocal(file, text);
    }

    private static void writeLocal(File file, String text) throws IOException {
        // Resolve symlinks so saving through a link updates its target.
        Path target = file.getCanonicalFile().toPath();
        if (Files.exists(target) && (!Files.isRegularFile(target) || !Files.isWritable(target)))
            throw new IOException("Source is not a writable regular file");
        Path temporary = Files.createTempFile(target.getParent(), ".termuc-save-", ".tmp");
        try {
            if (Files.exists(target)) {
                try {
                    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(target);
                    Files.setPosixFilePermissions(temporary, permissions);
                } catch (UnsupportedOperationException ignored) { /* Not a POSIX filesystem. */ }
            }
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            checkInterrupted();
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    static final class Journal {
        final File source, temporary, backup, record;
        Journal(File source) throws IOException {
            this.source = source;
            File directory = new File(Application.getInstance().getNoBackupFilesDir(), "source-saves");
            Files.createDirectories(directory.toPath());
            record = new File(directory, UUID.nameUUIDFromBytes(source.getAbsolutePath().getBytes(StandardCharsets.UTF_8)) + ".journal");
            if (record.isFile()) {
                try (DataInputStream input = new DataInputStream(new FileInputStream(record))) {
                    if (!input.readUTF().equals(source.getAbsolutePath())) throw new IOException("Invalid save journal path");
                    temporary = new File(source.getParentFile(), input.readUTF());
                    backup = new File(source.getParentFile(), input.readUTF());
                    if (!temporary.getParentFile().equals(source.getParentFile()) || !backup.getParentFile().equals(source.getParentFile())
                        || !temporary.getName().matches("\\.termuc-[0-9a-f-]+-pending")
                        || !backup.getName().matches("\\.termuc-[0-9a-f-]+-backup") || input.read() != -1)
                        throw new IOException("Invalid save journal staging paths");
                }
            } else {
                String id = ".termuc-" + UUID.randomUUID();
                temporary = new File(source.getParentFile(), id + "-pending");
                backup = new File(source.getParentFile(), id + "-backup");
            }
        }
        void write() throws IOException {
            File staging = new File(record.getPath() + ".tmp");
            try {
                try (FileOutputStream file = new FileOutputStream(staging)) {
                    DataOutputStream output = new DataOutputStream(file);
                    output.writeUTF(source.getAbsolutePath());
                    output.writeUTF(temporary.getName());
                    output.writeUTF(backup.getName());
                    output.flush(); file.getFD().sync();
                }
                Files.move(staging.toPath(), record.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(staging.toPath()); }
        }
    }

    /** A crash between provider renames leaves the old source available for restoration. */
    static void recover(File file) throws IOException {
        if (!FileHelper.isTermuxFile(file)) return;
        synchronized (SourceWriter.class) { recoverProvider(file); }
    }

    private static void recoverProvider(File file) throws IOException {
        Journal journal = new Journal(file);
        if (!journal.record.isFile()) return;
        File backup = journal.backup;
        if (!FileHelper.isFile(file) && FileHelper.isFile(backup)
            && !FileHelper.rename(backup, file.getName()))
            throw new IOException("Cannot restore interrupted save: " + backup);
    }

    private static synchronized void writeProvider(File file, String text) throws IOException {
        recover(file);
        Journal journal = new Journal(file);
        File temporary = journal.temporary, backup = journal.backup;
        boolean movedOriginal = false;
        boolean published = false;
        try {
            journal.write();
            if (FileHelper.isFile(temporary) && !FileHelper.removeFiles(temporary))
                throw new IOException("Cannot remove interrupted staging file");
            try (OutputStream output = FileHelper.openOutputStream(temporary)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
                output.flush();
                if (!(output instanceof FileOutputStream)) throw new IOException("Provider cannot sync staged source");
                ((FileOutputStream)output).getFD().sync();
            }
            checkInterrupted();
            if (FileHelper.isFile(backup) && !FileHelper.removeFiles(backup))
                throw new IOException("Cannot remove previous source backup");
            if (FileHelper.isFile(file)) {
                if (!FileHelper.rename(file, backup.getName())) throw new IOException("Provider cannot back up source");
                movedOriginal = true;
            }
            checkInterrupted();
            if (!FileHelper.rename(temporary, file.getName())) throw new IOException("Provider cannot publish staged source");
            movedOriginal = false;
            published = true;
        } catch (RuntimeException error) {
            throw new IOException("Document provider could not save source", error);
        } finally {
            if (movedOriginal) recover(file);
            // Cleanup is best effort after publication; it must not turn a successful write into a failed save.
            try {
                boolean removed = !FileHelper.isFile(temporary) || FileHelper.removeFiles(temporary);
                if (published) removed &= !FileHelper.isFile(backup) || FileHelper.removeFiles(backup);
                if (removed && !FileHelper.isFile(backup)) Files.deleteIfExists(journal.record.toPath());
            } catch (IOException | RuntimeException ignored) { /* Retry journal cleanup on the next save. */ }
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Source save interrupted");
    }
}
