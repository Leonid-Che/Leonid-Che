package android.content;
public interface SharedPreferences {
    String getString(String k, String def);
    int getInt(String k, int def);
    Editor edit();
    interface Editor {
        Editor putString(String k, String v);
        Editor putInt(String k, int v);
        void apply();
    }
}
