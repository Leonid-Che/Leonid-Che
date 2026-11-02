package android.bluetooth;
import java.util.List;
import java.util.ArrayList;
public class BluetoothManager {
    public BluetoothAdapter getAdapter() { return null; }
    public List<BluetoothDevice> getConnectedDevices(int profile) { return new ArrayList<>(); }
    public int getConnectionState(BluetoothDevice device) { return 0; }
}
