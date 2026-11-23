package android.os;

import java.io.*;

public class ParcelFileDescriptor implements Closeable {
    public static final int MODE_READ_ONLY = 0x10000000, MODE_WRITE_ONLY = 0x20000000, MODE_READ_WRITE = 0x30000000, MODE_CREATE = 0x08000000, MODE_TRUNCATE = 0x04000000;
    private final RandomAccessFile f;
    private ParcelFileDescriptor(RandomAccessFile f) { this.f = f; }
    public static ParcelFileDescriptor open(File file, int mode) throws FileNotFoundException {
        return new ParcelFileDescriptor(new RandomAccessFile(file, (mode & MODE_WRITE_ONLY) != 0 ? "rw" : "r"));
    }
    public static int parseMode(String m) {
        int r = 0;
        if (m.contains("rw")) r = MODE_READ_WRITE; else if (m.contains("w")) r = MODE_WRITE_ONLY; else r = MODE_READ_ONLY;
        if (m.contains("t")) r |= MODE_TRUNCATE;
        if (m.contains("w")) r |= MODE_CREATE;
        return r;
    }
    public FileDescriptor getFileDescriptor() { try { return f.getFD(); } catch (IOException e) { throw new RuntimeException(e); } }
    public int getFd() { return 0; }
    public long getStatSize() { try { return f.length(); } catch (IOException e) { return -1; } }
    @Override public void close() throws IOException { f.close(); }
    public static class AutoCloseOutputStream extends FileOutputStream {
        public AutoCloseOutputStream(ParcelFileDescriptor p) { super(p.getFileDescriptor()); }
    }
    public static class AutoCloseInputStream extends FileInputStream {
        public AutoCloseInputStream(ParcelFileDescriptor p) { super(p.getFileDescriptor()); }
    }
}
