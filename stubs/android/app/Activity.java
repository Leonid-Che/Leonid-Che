package android.app;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
public class Activity extends Context {
    public static final int RESULT_OK = -1;
    protected void onCreate(Bundle b) {}
    protected void onNewIntent(Intent i) {}
    public void setContentView(int layoutResID) {}
    public <T extends Object> T findViewById(int id) { return null; }
    public boolean dispatchKeyEvent(KeyEvent e) { return false; }
    public void startActivityForResult(Intent i, int req) {}
    public void finishActivity(int r) {}
    public void requestPermissions(String[] p, int req) {}
    public int getColor(int resId) { return 0; }
    public android.content.res.Resources getResources2() { return null; }
    public Intent getIntent() { return null; }
    public android.view.WindowManager getWindowManager2() { return null; }
}
