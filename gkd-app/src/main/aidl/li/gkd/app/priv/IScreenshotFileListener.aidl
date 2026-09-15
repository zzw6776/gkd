package li.gkd.app.priv;

oneway interface IScreenshotFileListener {
    void onScreenshot() = 1;
    void onStateChanged(boolean watching, String message) = 2;
}
