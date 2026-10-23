package cn.rbc.termuc;

import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileFilter;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** File listing model shared by the Compose browser and font picker. */
public class FileAdapter implements Comparator<FileItem>, FileFilter {
    private static FileItem parent;
    private List<FileItem> data;
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

    public void setPath(File path) {
        if (FileHelper.isTermuxFile(path)) {
            Uri uri = Application.getInstance().treeUri;
            String root = uri == null ? path.getAbsolutePath() : DocumentsContract.getTreeDocumentId(uri);
            hasParent = !root.equals(path.getAbsolutePath());
        } else {
            hasParent = !Utils.ROOT.equals(path);
        }
        if (parent == null && hasParent) parent = new FileItem(R.drawable.ic_folder_24, "..");
        data = FileHelper.lists(path, this);
        Collections.sort(data, this);
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
