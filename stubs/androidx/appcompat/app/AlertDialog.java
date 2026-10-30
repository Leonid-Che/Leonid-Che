package androidx.appcompat.app;
import android.content.Context;
public class AlertDialog {
    public static class Builder {
        public Builder(Context c) {}
        public Builder setTitle(int r) { return this; }
        public Builder setMessage(int r) { return this; }
        public Builder setPositiveButton(int r, Object l) { return this; }
        public AlertDialog show() { return new AlertDialog(); }
    }
}
