package android.app;
public class Application extends android.content.Context {
    static final Application INSTANCE = new Application();
    public Application() { super(); }
    public void onCreate() {}
    public void onTerminate() {}
    public void registerActivityLifecycleCallbacks(Object c) {}
}
