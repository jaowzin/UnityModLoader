package dev.unitymodloader.app;

public final class BackendStatus {
    private final boolean ready;
    private final String message;

    public BackendStatus(boolean ready, String message) {
        this.ready = ready;
        this.message = message;
    }

    public boolean isReady() { return ready; }
    public String getMessage() { return message; }

    public static BackendStatus ready(String message) {
        return new BackendStatus(true, message);
    }

    public static BackendStatus blocked(String message) {
        return new BackendStatus(false, message);
    }
}
