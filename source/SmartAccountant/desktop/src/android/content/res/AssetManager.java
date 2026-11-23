package android.content.res;
public class AssetManager {
    public java.io.InputStream open(String name) throws java.io.IOException {
        java.io.InputStream in = AssetManager.class.getResourceAsStream("/res/" + name);
        if (in == null) throw new java.io.FileNotFoundException(name);
        return in;
    }
    public String[] list(String path) { return new String[0]; }
}
