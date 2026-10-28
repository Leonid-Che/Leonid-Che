package android.graphics;
public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1;
    public static final Style FILL = Style.FILL;
    public static final Style STROKE = Style.STROKE;
    public enum Style { FILL, STROKE }
    public Paint(int flags) {}
    public void setStyle(Style s) {}
    public void setColor(int c) {}
    public void setStrokeWidth(float w) {}
    public void setTextSize(float s) {}
    public void setTextAlign(Align a) {}
    public enum Align { LEFT, CENTER, RIGHT }
}
