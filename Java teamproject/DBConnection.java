import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * DBConnection - Production-Ready JDBC Utility for KEC Autonomous Hostel Engine.
 * 
 * Supports distributed multi-tier deployment on Oracle Cloud Infrastructure (OCI).
 * Student 1 (App Host) connects remotely to Student 2 (DB Host).
 * 
 * Priority order for configuration parameters:
 * 1. Programmatically configured parameters (via setters / overloaded getConnection)
 * 2. Environment Variables (KEC_DB_HOST, KEC_DB_PORT, KEC_DB_NAME, KEC_DB_USER, KEC_DB_PASSWORD)
 * 3. JVM System Properties (-Dkec.db.host, -Dkec.db.port, etc.)
 * 4. Default fallback constants (optimized for local/default OCI VM setups)
 */
public class DBConnection {

    private static final Logger LOGGER = Logger.getLogger(DBConnection.class.getName());

    // Configurable Default Fallbacks
    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 3306;
    public static final String DEFAULT_DB = "kec_hostel_db";
    public static final String DEFAULT_USER = "kec_admin";
    public static final String DEFAULT_PASS = "KecHostel@2026";
    public static final String DEFAULT_DB_TYPE = "mysql"; // "mysql" or "postgresql"

    // Active Resolved Configurations
    private static String dbType;
    private static String host;
    private static int port;
    private static String dbName;
    private static String user;
    private static String password;
    private static String jdbcUrl;

    // Cache connectivity status
    private static Boolean remoteDbAvailable = null;

    static {
        initDefaults();
        loadDriver();
    }

    /**
     * Initializes default configuration from Environment Variables, System Properties, or Constants.
     */
    public static synchronized void initDefaults() {
        dbType = resolveConfig("KEC_DB_TYPE", "kec.db.type", DEFAULT_DB_TYPE).toLowerCase();
        host = resolveConfig("KEC_DB_HOST", "kec.db.host", DEFAULT_HOST);

        String portStr = resolveConfig("KEC_DB_PORT", "kec.db.port", String.valueOf(DEFAULT_PORT));
        try {
            port = Integer.parseInt(portStr.trim());
        } catch (NumberFormatException e) {
            port = DEFAULT_PORT;
        }

        dbName = resolveConfig("KEC_DB_NAME", "kec.db.name", DEFAULT_DB);
        user = resolveConfig("KEC_DB_USER", "kec.db.user", DEFAULT_USER);
        password = resolveConfig("KEC_DB_PASSWORD", "kec.db.password", DEFAULT_PASS);

        rebuildJdbcUrl();
    }

    private static void rebuildJdbcUrl() {
        if ("postgresql".equalsIgnoreCase(dbType)) {
            jdbcUrl = String.format(
                "jdbc:postgresql://%s:%d/%s?connectTimeout=5&sslmode=prefer",
                host, port, dbName
            );
        } else {
            jdbcUrl = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Kolkata&connectTimeout=5000&socketTimeout=5000",
                host, port, dbName
            );
        }
    }

    /**
     * Resolves configuration with priority: Env Var -> System Property -> Default.
     */
    private static String resolveConfig(String envKey, String sysPropKey, String defaultValue) {
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.trim().isEmpty()) {
            return envVal.trim();
        }
        String sysVal = System.getProperty(sysPropKey);
        if (sysVal != null && !sysVal.trim().isEmpty()) {
            return sysVal.trim();
        }
        return defaultValue;
    }

    /**
     * Robust JDBC Driver loader with automatic fallback to locate and register
     * mysql-connector-j-*.jar from current working directory if not present on system classpath.
     */
    public static void loadDriver() {
        try {
            if ("postgresql".equalsIgnoreCase(dbType)) {
                Class.forName("org.postgresql.Driver");
            } else {
                Class.forName("com.mysql.cj.jdbc.Driver");
            }
            LOGGER.info(() -> String.format("[DBConnection] Loaded %s JDBC Driver from classpath.", dbType.toUpperCase()));
            return;
        } catch (ClassNotFoundException ignored) {
            // Driver not in system classpath, search directory
        }

        // Search current directory for mysql connector JAR
        File currentDir = new File(".");
        File[] matchingJars = currentDir.listFiles((dir, name) -> 
            name.toLowerCase().startsWith("mysql-connector") && name.toLowerCase().endsWith(".jar")
        );

        if (matchingJars != null && matchingJars.length > 0) {
            File jarFile = matchingJars[0];
            try {
                URLClassLoader classLoader = new URLClassLoader(
                    new URL[]{ jarFile.toURI().toURL() }, 
                    DBConnection.class.getClassLoader()
                );
                Class<?> driverClass = Class.forName("com.mysql.cj.jdbc.Driver", true, classLoader);
                Driver rawDriver = (Driver) driverClass.getDeclaredConstructor().newInstance();
                DriverManager.registerDriver(new DriverShim(rawDriver));
                LOGGER.info(() -> String.format("[DBConnection] Successfully loaded and registered MySQL Driver from %s", jarFile.getName()));
                return;
            } catch (Exception e) {
                LOGGER.warning(() -> "[DBConnection] Failed to load driver from local JAR: " + e.getMessage());
            }
        }

        LOGGER.info(() -> String.format(
            "[DBConnection] %s JDBC Driver not detected. In-memory engine fallback active.",
            dbType.toUpperCase()
        ));
    }

    /**
     * Obtains a fresh live JDBC Connection using the current resolved parameters.
     *
     * @return Connection object
     * @throws SQLException if connection fails
     */
    public static Connection getConnection() throws SQLException {
        DriverManager.setLoginTimeout(5);
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    /**
     * Obtains a live JDBC Connection using custom host, port, database, user, and password parameters.
     */
    public static Connection getConnection(String customHost, int customPort, String customDb, 
                                           String customUser, String customPass) throws SQLException {
        DriverManager.setLoginTimeout(5);
        String url;
        if ("postgresql".equalsIgnoreCase(dbType)) {
            url = String.format("jdbc:postgresql://%s:%d/%s?connectTimeout=5&sslmode=prefer", customHost, customPort, customDb);
        } else {
            url = String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Kolkata&connectTimeout=5000&socketTimeout=5000", customHost, customPort, customDb);
        }
        return DriverManager.getConnection(url, customUser, customPass);
    }

    /**
     * Obtains a live JDBC Connection using a direct JDBC URL and credentials.
     */
    public static Connection getConnection(String directUrl, String customUser, String customPass) throws SQLException {
        DriverManager.setLoginTimeout(5);
        return DriverManager.getConnection(directUrl, customUser, customPass);
    }

    /**
     * Tests connectivity to the remote database host.
     * 
     * @return true if connection can be opened and validated, false otherwise.
     */
    public static boolean testConnection() {
        try (Connection conn = getConnection()) {
            boolean valid = conn.isValid(3);
            remoteDbAvailable = valid;
            if (valid) {
                LOGGER.info(() -> String.format("[DBConnection] Connected successfully to %s@%s:%d/%s", user, host, port, dbName));
            }
            return valid;
        } catch (Throwable t) {
            remoteDbAvailable = false;
            LOGGER.info(() -> String.format(
                "[DBConnection] Remote DB host (%s:%d/%s) is offline. In-memory engine activated.",
                host, port, dbName
            ));
            return false;
        }
    }

    /**
     * Returns whether the remote database is accessible.
     */
    public static boolean isRemoteDbAvailable() {
        if (remoteDbAvailable == null) {
            testConnection();
        }
        return remoteDbAvailable != null && remoteDbAvailable;
    }

    /**
     * Displays resolved configuration parameters (with masked password).
     */
    public static void printConfiguration() {
        String maskedPass = (password == null || password.isEmpty()) 
            ? "(none)" 
            : (password.length() <= 3 ? "***" : password.substring(0, 2) + "******");

        System.out.println("================================================================================");
        System.out.println("  KEC HOSTEL ENGINE - DATABASE CONNECTION PROFILE (OCI MULTI-TIER)");
        System.out.println("================================================================================");
        System.out.printf("  Database Type : %s%n", dbType.toUpperCase());
        System.out.printf("  Remote Host   : %s%n", host);
        System.out.printf("  Port          : %d%n", port);
        System.out.printf("  Database Name : %s%n", dbName);
        System.out.printf("  Username      : %s%n", user);
        System.out.printf("  Password      : %s%n", maskedPass);
        System.out.printf("  JDBC URL      : %s%n", jdbcUrl);
        System.out.printf("  Connection    : %s%n", (isRemoteDbAvailable() ? "[ONLINE - REMOTE DB READY]" : "[OFFLINE - IN-MEMORY FALLBACK ACTIVE]"));
        System.out.println("================================================================================");
    }

    /**
     * Programmatic configuration setters.
     */
    public static synchronized void configure(String newHost, int newPort, String newDb, String newUser, String newPass) {
        host = newHost;
        port = newPort;
        dbName = newDb;
        user = newUser;
        password = newPass;
        rebuildJdbcUrl();
        remoteDbAvailable = null; // Reset cache
    }

    public static synchronized void setHost(String newHost) { host = newHost; rebuildJdbcUrl(); remoteDbAvailable = null; }
    public static synchronized void setPort(int newPort) { port = newPort; rebuildJdbcUrl(); remoteDbAvailable = null; }
    public static synchronized void setDbName(String newDb) { dbName = newDb; rebuildJdbcUrl(); remoteDbAvailable = null; }
    public static synchronized void setUser(String newUser) { user = newUser; remoteDbAvailable = null; }
    public static synchronized void setPassword(String newPass) { password = newPass; remoteDbAvailable = null; }

    // Getters
    public static String getHost() { return host; }
    public static int getPort() { return port; }
    public static String getDbName() { return dbName; }
    public static String getUser() { return user; }
    public static String getJdbcUrl() { return jdbcUrl; }
    public static String getDbType() { return dbType; }

    /**
     * Quietly closes JDBC resources.
     */
    public static void closeQuietly(AutoCloseable... resources) {
        for (AutoCloseable res : resources) {
            if (res != null) {
                try {
                    res.close();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Standalone main method to test DB connectivity directly from command line or IDE.
     */
    public static void main(String[] args) {
        System.out.println("Testing KEC Hostel Engine Database Connection...");
        printConfiguration();
        boolean success = testConnection();
        if (success) {
            System.out.println("[SUCCESS] Remote database is reachable and authenticated!");
        } else {
            System.out.println("[NOTICE] Remote database is currently unreachable.");
            System.out.println("         (This is normal if Student 2's OCI VM is not yet powered on).");
            System.out.println("         The engine will run locally using In-Memory Fallback mode.");
            System.out.println("         Refer to 'Architecture_Verification_Doc.md' to configure the remote host.");
        }
    }

    /**
     * DriverShim wrapper class allowing DriverManager to accept drivers loaded via URLClassLoader.
     */
    private static class DriverShim implements Driver {
        private final Driver driver;

        public DriverShim(Driver driver) {
            this.driver = driver;
        }

        @Override
        public boolean acceptsURL(String u) throws SQLException {
            return driver.acceptsURL(u);
        }

        @Override
        public Connection connect(String u, Properties p) throws SQLException {
            return driver.connect(u, p);
        }

        @Override
        public int getMajorVersion() {
            return driver.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return driver.getMinorVersion();
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String u, Properties p) throws SQLException {
            return driver.getPropertyInfo(u, p);
        }

        @Override
        public boolean jdbcCompliant() {
            return driver.jdbcCompliant();
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return null;
        }
    }
}
