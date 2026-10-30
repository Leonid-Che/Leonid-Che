package android.view;
import android.content.Context;
public class View {
    public View() {}
    public View(Context c) {}
    public Context getContext() { return null; }
    public void setBackgroundColor(int color) {}
    public void setBackgroundResource(int resid) {}
    public boolean post(Runnable r) { return true; }
    public int getWidth() { return 0; }
    public int getHeight() { return 0; }
    public void invalidate() {}
    public void setOnTouchListener(OnTouchListener l) {}
    public interface OnTouchListener { boolean onTouch(View v, MotionEvent e); }
    public interface OnClickListener { void onClick(View v); }
}
