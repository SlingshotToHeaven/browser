package com.privacy.downloader.engine;

public class SpeedLimiter {
    private long maxBytesPerSecond;
    private long tokens;
    private long lastRefillTime;

    public SpeedLimiter(long maxBytesPerSecond) {
        this.maxBytesPerSecond = maxBytesPerSecond;
        this.tokens = maxBytesPerSecond;
        this.lastRefillTime = System.currentTimeMillis();
    }

    public synchronized void setMaxBytesPerSecond(long maxBytesPerSecond) {
        this.maxBytesPerSecond = maxBytesPerSecond;
        this.tokens = Math.min(tokens, maxBytesPerSecond);
    }

    public synchronized long getMaxBytesPerSecond() {
        return maxBytesPerSecond;
    }

    public synchronized void acquire(int bytes) {
        if (maxBytesPerSecond <= 0) return; // Unlimited speed

        while (bytes > 0) {
            refill();
            if (tokens >= bytes) {
                tokens -= bytes;
                return;
            }

            long needed = bytes - tokens;
            tokens = 0;
            long sleepMs = (needed * 1000) / maxBytesPerSecond;
            if (sleepMs < 1) sleepMs = 1;

            try {
                wait(sleepMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void refill() {
        long now = System.currentTimeMillis();
        long delta = now - lastRefillTime;
        if (delta > 0) {
            tokens = Math.min(maxBytesPerSecond, tokens + (delta * maxBytesPerSecond) / 1000);
            lastRefillTime = now;
            notifyAll();
        }
    }
}
