package amrts.termuide;

import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileFilter;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** File listing model shared by the Compose browser and font picker. */
public class FileAdapter implements Comparator<FileItem>, FileFilter {
    private static final FileItem parent = new FileItem(R.drawable.ic_folder_24, "..");
    private List<FileItem> data = Collections.emptyList();
    private long generation;
    private boolean loading, closed;
    private File path, projectDirectory;
    private Runnable changed;
    private boolean hasParent;
    private final FileFilter filter;

    public FileAdapter(File path) { this(path, null); }

    public FileAdapter(File path, FileFilter filter) {
        this.filter = filter;
        setPath(path);
    }

    public FileItem getItem(int index) {
        if (hasParent) return index == 0 ? parent : data.get(index - 1);
        return data.get(index);
    }

    public int getCount() { return hasParent ? data.size() + 1 : data.size(); }

    public void setOnChanged(Runnable listener) { changed = listener; }
    public boolean isLoading() { return loading; }
    public File getProjectDirectory() { return projectDirectory; }
    public boolean containsFile(String name) {
        for (FileItem item : data) if (item.name.equals(name)) return item.icon != R.drawable.ic_folder_24;
        return false;
    }
    public void release() { closed = true; generation++; changed = null; }

    public void setPath(File directory) {
        long expected = ++generation;
        if (!directory.equals(path)) { data = Collections.emptyList(); hasParent = false; projectDirectory = null; }
        path = directory;
        loading = true;
        Application.getInstance().fileTask(() -> {
            boolean parentVisible;
            File root = Utils.ROOT;
            if (FileHelper.isTermuxFile(directory)) {
                Uri uri = Application.getInstance().treeUri;
                root = uri == null ? directory : new File(DocumentsContract.getTreeDocumentId(uri));
            }
            parentVisible = !root.equals(directory);
            List<FileItem> entries = FileHelper.lists(directory, this);
            Collections.sort(entries, this);
            File project = null;
            for (File folder = directory; folder != null && !folder.equals(root); folder = folder.getParentFile()) {
                if (FileHelper.isFile(new File(folder, Project.PROJ))) { project = folder; break; }
            }
            return new Listing(entries, parentVisible, project);
        }).whenComplete((listing, error) -> {
            if (closed || expected != generation) return;
            loading = false;
            if (error == null) { data = listing.entries; hasParent = listing.parent; projectDirectory = listing.project; }
            else { data = Collections.emptyList(); hasParent = false; projectDirectory = null; }
            if (changed != null) changed.run();
        });
    }

    private static final class Listing {
        final List<FileItem> entries;
        final boolean parent;
        final File project;
        Listing(List<FileItem> entries, boolean parent, File project) { this.entries = entries; this.parent = parent; this.project = project; }
    }

    @Override
    public int compare(FileItem a, FileItem b) {
        boolean aDirectory = a.icon == R.drawable.ic_folder_24;
        boolean bDirectory = b.icon == R.drawable.ic_folder_24;
        return aDirectory == bDirectory ? a.name.compareToIgnoreCase(b.name) : aDirectory ? -1 : 1;
    }

    @Override
    public boolean accept(File file) {
        return (Application.show_hidden || !file.isHidden()) && (filter == null || filter.accept(file));
    }
}
