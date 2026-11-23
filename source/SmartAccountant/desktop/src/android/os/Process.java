package android.os;
public class Process {
    public static int myPid() { return (int) ProcessHandle.current().pid(); }
    public static int myUid() { return 1000; }
    public static void killProcess(int pid) { if (pid == myPid()) System.exit(0); }
}
