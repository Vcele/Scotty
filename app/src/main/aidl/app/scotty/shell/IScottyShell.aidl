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
}
