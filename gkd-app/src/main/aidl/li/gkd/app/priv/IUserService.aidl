package li.gkd.app.priv;

import android.graphics.Bitmap;
import android.graphics.Rect;
import li.gkd.app.priv.IScreenshotListener;

interface IUserService {
    void destroy() = 16777114;
    Bitmap takeScreenshot(in Rect crop, int rotation) = 1;
    boolean setScreenshotFileListener(String directoryPath, IScreenshotListener listener) = 2;
    void clearScreenshotFileListener() = 3;
    boolean setSnapshotKeyListener(IScreenshotListener listener) = 4;
    void clearSnapshotKeyListener() = 5;
}
