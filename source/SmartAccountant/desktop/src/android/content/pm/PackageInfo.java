package android.content.pm;
public class PackageInfo {
    public String packageName, versionName;
    public int versionCode;
    public long firstInstallTime, lastUpdateTime;
    public Signature[] signatures = new Signature[0];
    public SigningInfo signingInfo = new SigningInfo();
    public long getLongVersionCode() { return versionCode; }
}
