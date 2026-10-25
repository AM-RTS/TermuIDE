package amrts.termuide;
import java.io.*;
import android.net.*;
import android.content.*;
import android.database.*;
import android.provider.*;
import java.util.*;

import static android.provider.DocumentsContract.Document.*;

public class FileHelper {

	public static boolean isTermuxFile(File f) {
		return f.getAbsolutePath().startsWith("/data/data/com.termux");
	}

	public final static List<FileItem> lists(File f, FileFilter filter) {
		if (!isTermuxFile(f)) {
			File[] fs = f.listFiles(filter);
			FileItem[] fis;
			if (fs == null || fs.length == 0)
				fis = new FileItem[0];
			else {
				fis = new FileItem[fs.length];
				for (int i=0,l=fs.length; i<l; i++) {
					File fi = fs[i];
					fis[i] = toFileItem(fi.isDirectory(), fi.getName());
				}
			}
			return Arrays.asList(fis);
		}
		Application app = Application.getInstance();
		if (app.treeUri == null) return Collections.emptyList();
        try {
            Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(app.treeUri, f.getAbsolutePath());
            try (Cursor cursor = app.getContentResolver().query(uri,
                    new String[]{COLUMN_DOCUMENT_ID, COLUMN_MIME_TYPE}, null, null, null)) {
                if (cursor == null) return Collections.emptyList();
                ArrayList<FileItem> files = new ArrayList<>(cursor.getCount());
                while (cursor.moveToNext()) {
                    File file = new File(cursor.getString(0));
                    if (filter == null || filter.accept(file))
                        files.add(toFileItem(MIME_TYPE_DIR.equals(cursor.getString(1)), file.getName()));
                }
                return files;
            }
        } catch (SecurityException | IllegalArgumentException error) {
            return Collections.emptyList();
        }
	}

	private static FileItem toFileItem(boolean dir, String n) {
		int icon;
		if (dir)
			icon = R.drawable.ic_folder_24;
		else {
            icon = Languages.isCodeFile(n) ? R.drawable.ic_code_24 : R.drawable.ic_file_24;
		}
		return new FileItem(icon, n);
	}

	public final static InputStream openInputStream(File f) throws IOException {
		SourceWriter.recover(f);
		if (!isTermuxFile(f))
			return new FileInputStream(f);
		Application app = Application.getInstance();
        if (app.treeUri == null) throw new FileNotFoundException("Termux storage access is required");
        try {
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(app.treeUri, f.getAbsolutePath());
            InputStream input = app.getContentResolver().openInputStream(uri);
            if (input == null) throw new IOException("Document provider did not open the file");
            return input;
        } catch (SecurityException | IllegalArgumentException error) {
            throw new IOException("Cannot read the source document", error);
        }
	}

	public final static OutputStream openOutputStream(File f) throws IOException {
		if (!isTermuxFile(f))
			return new FileOutputStream(f);
		Application app = Application.getInstance();
        if (app.treeUri == null) throw new FileNotFoundException("Termux storage access is required");
        try {
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(app.treeUri, f.getAbsolutePath());
            ContentResolver resolver = app.getContentResolver();
            boolean exists;
            try (Cursor cursor = resolver.query(uri, new String[]{COLUMN_DOCUMENT_ID}, null, null, null)) {
                exists = cursor != null && cursor.moveToFirst();
            }
            if (!exists) {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(app.treeUri, f.getParent());
                uri = DocumentsContract.createDocument(resolver, parent, "text/plain", f.getName());
            }
            if (uri == null) throw new IOException("Document provider did not create the file");
            OutputStream output = resolver.openOutputStream(uri, "wt");
            if (output == null) throw new IOException("Document provider did not open the file for writing");
            return output;
        } catch (SecurityException | IllegalArgumentException error) {
            throw new IOException("Cannot write the source document", error);
        }
	}

	public final static long lastModified(File f) {
		if (!isTermuxFile(f))
			return f.lastModified();
		Application app = Application.getInstance();
		if (app.treeUri == null) return 0;
        try {
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(app.treeUri, f.getAbsolutePath());
            try (Cursor cursor = app.getContentResolver().query(uri,
                    new String[]{COLUMN_LAST_MODIFIED}, null, null, null)) {
                return cursor != null && cursor.moveToFirst() ? cursor.getLong(0) : 0;
            }
        } catch (SecurityException | IllegalArgumentException error) { return 0; }
	}

	public final static long length(File f) {
		if (!isTermuxFile(f)) {
			return f.length();
		}
		Application app = Application.getInstance();
		Uri u = DocumentsContract.buildDocumentUriUsingTree(app.treeUri, f.getAbsolutePath());
		Cursor cur = app.getContentResolver().query(u, new String[]{
			COLUMN_SIZE
		}, null, null, null);
		long l = 0;
		if (cur != null) {
			if (cur.moveToFirst()) {
				l = cur.getLong(0);
			}
			cur.close();
		}
		return l;
	}

	public final static boolean isDirectory(File f) {
		if (!isTermuxFile(f))
			return f.isDirectory();
		Application app = Application.getInstance();
		Uri u = DocumentsContract.buildDocumentUriUsingTree(app.treeUri, f.getAbsolutePath());
		Cursor cur = app.getContentResolver().query(u, new String[]{
														COLUMN_MIME_TYPE
													}, null, null, null);
		boolean isd = false;
		if (cur != null) {
			if (cur.moveToFirst())
				isd = MIME_TYPE_DIR.equals(cur.getString(0));
			cur.close();
		}
		return isd;
	}

	public final static boolean isFile(File f) {
		if (!isTermuxFile(f))
			return f.isFile();
		Application app = Application.getInstance();
		if (app.treeUri == null) return false;
        try {
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(app.treeUri, f.getAbsolutePath());
            try (Cursor cursor = app.getContentResolver().query(uri,
                    new String[]{COLUMN_MIME_TYPE}, null, null, null)) {
                return cursor != null && cursor.moveToFirst() && !MIME_TYPE_DIR.equals(cursor.getString(0));
            }
        } catch (SecurityException | IllegalArgumentException error) { return false; }
	}

	public final static Object[] queryFile(File f, String[] projs) {
		if (isTermuxFile(f)) {
			Application app = Application.getInstance();
			Uri u = DocumentsContract.buildDocumentUriUsingTree(app.treeUri, f.getAbsolutePath());
			Cursor cur = app.getContentResolver().query(u, projs, null, null, null);
			Object[] ret = null;
			if (cur == null) return ret;
			if (cur.moveToFirst()) {
				ret = new Object[projs.length];
				for (int i=0;i<projs.length;i++) {
					Object j;
					switch (cur.getType(i)) {
						case Cursor.FIELD_TYPE_INTEGER:
							j = cur.getLong(i);
							break;
						case Cursor.FIELD_TYPE_FLOAT:
							j = cur.getDouble(i);
							break;
						case Cursor.FIELD_TYPE_STRING:
							j = cur.getString(i);
							break;
						default:
							j = null;
							break;
					}
					ret[i] = j;
				}
			}
			cur.close();
			return ret;
		}
		Object[] ret = new Object[projs.length];
		for (int i=0;i<projs.length;i++) {
			switch (projs[i]) {
				case COLUMN_DISPLAY_NAME:
					ret[i] = f.getName();
					break;
				case COLUMN_SIZE:
					ret[i] = f.length();
					break;
				case COLUMN_LAST_MODIFIED:
					ret[i] = f.lastModified();
					break;
				case COLUMN_DOCUMENT_ID:
					ret[i] = f.getAbsolutePath();
					break;
				case COLUMN_MIME_TYPE:
					ret[i] = f.isDirectory() ? MIME_TYPE_DIR : "text/plain";
					break;
			}
		}
		return ret;
	}

	public final static boolean removeFiles(File f) {
		if (!isTermuxFile(f)) {
			return removeCommonFiles(f);
		}
		Application app = Application.getInstance();
		Uri u = DocumentsContract.buildDocumentUriUsingTree(app.treeUri, f.getAbsolutePath());
		ContentResolver crv = app.getContentResolver();
		Cursor cur = crv.query(u, new String[] {
			COLUMN_DOCUMENT_ID,
			COLUMN_MIME_TYPE
		}, null, null, null);
		if (cur != null) {
			try {
				if (cur.moveToFirst()) {
					if (MIME_TYPE_DIR.equals(cur.getString(1)))
						removeTermuxFiles(crv, app.treeUri, f.getAbsolutePath());
					return DocumentsContract.deleteDocument(crv, DocumentsContract.buildDocumentUriUsingTree(app.treeUri, cur.getString(0)));
				}
			} catch (FileNotFoundException fne) {
				fne.printStackTrace();
			} finally {
				cur.close();
			}
		}
		return false;
	}

	private static boolean removeCommonFiles(File dir) {
		File[] fl = dir.listFiles();
		if (fl != null)
			for (File f:fl)
				removeCommonFiles(f);
		return dir.delete();
	}

	private static boolean removeTermuxFiles(ContentResolver crv, Uri tree, String dir) throws FileNotFoundException {
		Uri u = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir);
		Cursor cur = crv.query(u, new String[]{
			COLUMN_DOCUMENT_ID,
			COLUMN_MIME_TYPE
		}, null, null, null);
		boolean ret = true;
		if (cur != null) {
			if(cur.moveToFirst())
				do {
					String docId = cur.getString(0);
					if (MIME_TYPE_DIR.equals(cur.getString(1)))
						removeTermuxFiles(crv, tree, docId);
					ret &= DocumentsContract.deleteDocument(crv, DocumentsContract.buildDocumentUriUsingTree(tree, docId));
				} while(cur.moveToNext());
			cur.close();
		}
		return ret;
	}

	public static boolean createFile(File f, boolean isDir) throws IOException {
		if (!isTermuxFile(f)) {
			return isDir ? f.mkdir() : f.createNewFile();
		}
		Application app = Application.getInstance();
		ContentResolver crv = app.getContentResolver();
		Uri parent = DocumentsContract.buildDocumentUriUsingTree(app.treeUri, f.getParent());
		return null != DocumentsContract.createDocument(crv, parent, isDir ? MIME_TYPE_DIR : "text/plain", f.getName());
	}

	public static boolean rename(File f, String n) {
		if (!isTermuxFile(f)) {
			return f.renameTo(new File(f.getParentFile(), n));
		}
		Application app = Application.getInstance();
		ContentResolver crv = app.getContentResolver();
		Uri file = DocumentsContract.buildDocumentUriUsingTree(app.treeUri, f.getAbsolutePath());
		try {
			return null != DocumentsContract.renameDocument(crv, file, n);
		} catch (FileNotFoundException fne) {
			return false;
		} catch (UnsupportedOperationException uo) {
			return false;
		}
	}
}
