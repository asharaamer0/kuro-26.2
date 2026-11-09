package dev.kuro.client.util;

public final class SilentRaycastHelper {
    private static final ThreadLocal<Boolean> IN_SILENT_RAYCAST = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private SilentRaycastHelper() {}

    public static boolean isInSilentRaycast() {
        return IN_SILENT_RAYCAST.get();
    }

    public static void setInSilentRaycast(boolean inSilentRaycast) {
        IN_SILENT_RAYCAST.set(inSilentRaycast);
    }
}
