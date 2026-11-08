package com.questlens.recorder;

final class CaptureTiming {
    private CaptureTiming() {}

    static boolean isComplete(long elapsedMs, long videoMs) {
        // Frame production can start late and a still screen may produce frames irregularly.
        long allowanceMs = Math.max(2_500, elapsedMs / 5);
        return videoMs > 0 && elapsedMs - videoMs <= allowanceMs;
    }
}
