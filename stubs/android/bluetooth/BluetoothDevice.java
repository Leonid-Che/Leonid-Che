package android.bluetooth;
public class BluetoothDevice {
    public static final int BOND_NONE = 10, BOND_BONDING = 11, BOND_BONDED = 12;
    public String getName() { return null; }
    public String getAddress() { return null; }
    public int getBondState() { return BOND_NONE; }
    public BluetoothSocket createRfcommSocketToServiceRecord(java.util.UUID uuid) throws java.io.IOException { return null; }
    public BluetoothSocket createInsecureRfcommSocketToServiceRecord(java.util.UUID uuid) throws java.io.IOException { return null; }
}
