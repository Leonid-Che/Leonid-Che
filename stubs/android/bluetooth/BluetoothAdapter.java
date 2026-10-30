package android.bluetooth;
public class BluetoothAdapter {
    public static BluetoothAdapter getDefaultAdapter() { return null; }
    public boolean isEnabled() { return false; }
    public java.util.Set<BluetoothDevice> getBondedDevices() { return null; }
    public BluetoothDevice getRemoteDevice(String address) { return null; }
    public void cancelDiscovery() {}
}
