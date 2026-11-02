package android.bluetooth;
public class BluetoothAdapter {
    public static final String ACTION_REQUEST_ENABLE = "android.bluetooth.adapter.action.REQUEST_ENABLE";
    public static BluetoothAdapter getDefaultAdapter() { return null; }
    public boolean isEnabled() { return false; }
    public java.util.Set<BluetoothDevice> getBondedDevices() { return null; }
    public BluetoothDevice getRemoteDevice(String address) { return null; }
    public void cancelDiscovery() {}
}
