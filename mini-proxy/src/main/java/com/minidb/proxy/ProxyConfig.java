package com.minidb.proxy;

public record ProxyConfig(
        int listenPort,
        String proxyUsername,
        String proxyPassword,
        String backendUsername,
        String backendPassword,
        int backendPoolMaxSize,
        long borrowTimeoutMs,
        long idleTimeoutMs,
        long readAfterWriteWindowMs,
        int shardCount,
        int backendConnectTimeoutMs,
        String backendHost,
        int backendPortBase,
        String primaryDatabase,
        String replicaDatabase,
        String shardDatabasePrefix
) {
    public ProxyConfig(int listenPort,
                       String proxyUsername,
                       String proxyPassword,
                       String backendUsername,
                       String backendPassword,
                       int backendPoolMaxSize,
                       long borrowTimeoutMs,
                       long idleTimeoutMs,
                       long readAfterWriteWindowMs,
                       int shardCount,
                       int backendConnectTimeoutMs,
                       String backendHost,
                       int backendPortBase) {
        this(listenPort, proxyUsername, proxyPassword, backendUsername, backendPassword,
                backendPoolMaxSize, borrowTimeoutMs, idleTimeoutMs, readAfterWriteWindowMs,
                shardCount, backendConnectTimeoutMs, backendHost, backendPortBase,
                "minidb", "minidb", "minidb_shard_");
    }

    public static ProxyConfig defaults() {
        return new ProxyConfig(
                intSetting("MINIDB_PROXY_PORT", 3306),
                setting("MINIDB_PROXY_USERNAME", "proxy"),
                setting("MINIDB_PROXY_PASSWORD", "proxy123"),
                setting("MINIDB_BACKEND_USERNAME", "root"),
                setting("MINIDB_BACKEND_PASSWORD", "root123"),
                intSetting("MINIDB_BACKEND_POOL_MAX_SIZE", 16),
                longSetting("MINIDB_BACKEND_BORROW_TIMEOUT_MS", 5000),
                longSetting("MINIDB_BACKEND_IDLE_TIMEOUT_MS", 600_000),
                longSetting("MINIDB_READ_AFTER_WRITE_WINDOW_MS", 3000),
                intSetting("MINIDB_SHARD_COUNT", 4),
                intSetting("MINIDB_BACKEND_CONNECT_TIMEOUT_MS", 5000),
                setting("MINIDB_BACKEND_HOST", "127.0.0.1"),
                intSetting("MINIDB_BACKEND_PORT_BASE", 4407),
                setting("MINIDB_PRIMARY_DATABASE", "minidb"),
                setting("MINIDB_REPLICA_DATABASE", "minidb"),
                setting("MINIDB_SHARD_DATABASE_PREFIX", "minidb_shard_")
        );
    }

    /**
     * Returns a list of warnings for credentials still using built-in defaults.
     * Call this at startup to alert operators that environment variables should be set.
     */
    public java.util.List<String> validateCredentials() {
        var warnings = new java.util.ArrayList<String>();
        checkWarning("MINIDB_PROXY_USERNAME", "proxy", warnings);
        checkWarning("MINIDB_PROXY_PASSWORD", "proxy123", warnings);
        checkWarning("MINIDB_BACKEND_USERNAME", "root", warnings);
        checkWarning("MINIDB_BACKEND_PASSWORD", "root123", warnings);
        return warnings;
    }

    private static void checkWarning(String envName, String defaultVal, java.util.List<String> out) {
        String fromEnv = System.getenv(envName);
        if (fromEnv == null || fromEnv.isBlank()) {
            out.add("Using default " + envName + "='" + defaultVal
                    + "'. Set the environment variable for production use.");
        }
    }

    /** Host:port for PRIMARY data source */
    public String primaryHost() { return backendHost; }
    public int primaryPort() { return backendPortBase; }

    /** Host:port for REPLICA data source */
    public int replicaPort() { return backendPortBase + 1; }

    /** Host:port for shard N */
    public int shardPort(int index) { return backendPortBase + 2 + index; }
    public String shardDatabase(int index) { return shardDatabasePrefix + index; }

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

    private static String setting(String name, String fallback) {
        String property = System.getProperty(name);
        if (property != null && !property.isBlank()) return property;
        String env = System.getenv(name);
        return env == null || env.isBlank() ? fallback : env;
    }

    private static int intSetting(String name, int fallback) {
        return Integer.parseInt(setting(name, Integer.toString(fallback)));
    }

    private static long longSetting(String name, long fallback) {
        return Long.parseLong(setting(name, Long.toString(fallback)));
    }
}
