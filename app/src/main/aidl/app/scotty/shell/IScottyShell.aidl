package app.scotty.shell;

/** Runs inside a process with shell (ADB) privileges, started through Shizuku. */
interface IScottyShell {
    /** Shizuku's reserved transaction for tearing the service down. */
    void destroy() = 16777114;

    /** Runs a shell command; returns "<exit code>\n<output>". */
    String exec(String command) = 1;

    /**
     * Captures the whole audio output (REMOTE_SUBMIX) as 16-bit LE stereo PCM into the returned
     * pipe. While capturing, Android routes media to the capture instead of the phone speaker.
     */
    ParcelFileDescriptor startCapture(int sampleRate) = 2;

    void stopCapture() = 3;

    /**
     * Advertises (or stops advertising) this phone as a Wi-Fi Display *source* in its Wi-Fi Direct
     * information element. Needs NETWORK_SETTINGS, which shell has; regular apps are refused.
     * Returns "ok" or a reason.
     */
    String setWfdSource(boolean enable, int controlPort) = 4;

    /** Sets the Wi-Fi Direct device name (what TVs show). Needs NETWORK_SETTINGS. Returns "ok" or a reason. */
    String setP2pDeviceName(String name) = 5;
}
