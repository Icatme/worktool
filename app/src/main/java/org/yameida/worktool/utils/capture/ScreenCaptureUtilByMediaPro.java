package org.yameida.worktool.utils.capture;

import android.graphics.Bitmap;

/** @deprecated Use the user-authorized service and MediaProjectionHolder. */
@Deprecated
public final class ScreenCaptureUtilByMediaPro {
    private ScreenCaptureUtilByMediaPro() {}
    public static void init() {
        throw new UnsupportedOperationException("Start capture from the visible consent activity");
    }
    public static Bitmap getScreenCapHorizontal() { return MediaProjectionHolder.INSTANCE.capture(); }
    public static Bitmap getScreenCapVertical() { return MediaProjectionHolder.INSTANCE.capture(); }
    public static void stop() { MediaProjectionHolder.INSTANCE.stop(); }
}
