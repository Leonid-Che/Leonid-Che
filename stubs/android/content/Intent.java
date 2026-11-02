package android.content;
public class Intent {
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public Intent() {}
    public Intent(Object ctx, Class<?> cls) {}
    public Intent(String action) {}
    public void addFlags(int f) {}
}
