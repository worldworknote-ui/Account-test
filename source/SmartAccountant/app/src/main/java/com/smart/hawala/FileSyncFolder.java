package com.smart.hawala;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** 10.0: مجلد مزامنة عادي على القرص (الماك والويندوز والاختبارات). */
public final class FileSyncFolder implements SyncFolder {
    private final File dir;

    public FileSyncFolder(File dir) { this.dir = dir; }

    @Override public List<String> list(String prefix) throws IOException {
        List<String> out = new ArrayList<>();
        String[] names = dir.list();
        if (names == null) throw new IOException("مجلد المزامنة غير متاح: " + dir);
        for (String n : names) if (n.startsWith(prefix) && n.endsWith(".sab") || n.startsWith(prefix) && n.endsWith(".json")) out.add(n);
        return out;
    }

    @Override public boolean exists(String name) { return new File(dir, name).isFile(); }

    @Override public byte[] read(String name) throws IOException {
        File f = new File(dir, name);
        if (!f.isFile()) return null;
        return Files.readAllBytes(f.toPath());
    }

    @Override public void write(String name, byte[] data) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("تعذر إنشاء مجلد المزامنة: " + dir);
        File tmp = new File(dir, ".tmp-" + name + "-" + System.nanoTime());
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(data);
            o.getFD().sync();
        }
        try {
            Files.move(tmp.toPath(), new File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp.toPath(), new File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override public String describe() { return dir.getAbsolutePath(); }

    @Override public boolean canDelete() { return true; }

    @Override public void delete(String name) throws IOException {
        File f = new File(dir, name);
        if (f.isFile() && !f.delete()) throw new IOException("تعذر حذف " + name);
    }

    @Override public java.util.Map<String, long[]> stat(String prefix) throws IOException {
        java.util.Map<String, long[]> out = new java.util.HashMap<>();
        for (String n : list(prefix)) {
            File f = new File(dir, n);
            out.put(n, new long[]{f.length(), f.lastModified()});
        }
        return out;
    }
}
