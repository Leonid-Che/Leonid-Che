package android.view;
public class MotionEvent {
    public static final int ACTION_DOWN=0, ACTION_UP=1, ACTION_MOVE=2, ACTION_POINTER_DOWN=5, ACTION_POINTER_UP=6;
    public int getAction() { return 0; }
    public int getActionIndex() { return 0; }
    public int getPointerCount() { return 1; }
    public float getX() { return 0; }
    public float getY() { return 0; }
    public float getX(int i) { return 0; }
    public float getY(int i) { return 0; }
    public void recycle() {}
}
