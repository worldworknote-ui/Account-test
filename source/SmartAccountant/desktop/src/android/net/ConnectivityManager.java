package android.net;
public class ConnectivityManager {
    public static final int TYPE_WIFI = 1, TYPE_MOBILE = 0;
    public NetworkInfo getActiveNetworkInfo() { return new NetworkInfo(); }
    public Network getActiveNetwork() { return new Network(); }
    public NetworkCapabilities getNetworkCapabilities(Network n) { return new NetworkCapabilities(); }
    public boolean isActiveNetworkMetered() { return false; }
    public void registerDefaultNetworkCallback(NetworkCallback cb) {}
    public void unregisterNetworkCallback(NetworkCallback cb) {}
    public static class NetworkCallback { public void onAvailable(Network n) {} public void onLost(Network n) {} }
}
