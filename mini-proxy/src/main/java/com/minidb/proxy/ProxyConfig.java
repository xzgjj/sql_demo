package com.minidb.proxy;

public record ProxyConfig(
        int listenPort,
        String proxyUsername,
        String proxyPassword,
        int backendPoolMaxSize,
        long borrowTimeoutMs,
        long idleTimeoutMs,
        long readAfterWriteWindowMs,
        int shardCount
) {
    public static ProxyConfig defaults() {
        return new ProxyConfig(
                3307,
                "proxy",
                "proxy123",
                16,
                5000,
                600_000,
                3000,
                2
        );
    }

    public static class BackendServer {
        public final String host;
        public final int port;
        public final String username;
        public final String password;

        public BackendServer(String host, int port, String username, String password) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
        }
    }
}
