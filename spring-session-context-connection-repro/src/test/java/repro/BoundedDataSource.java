package repro;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * Minimal stand-in for a connection pool: tracks borrowed connections and, like UCP/Hikari, fails after a wait timeout
 * once {@code maxSize} connections are borrowed.
 */
final class BoundedDataSource implements DataSource {

    private final DataSource target;
    private final int maxSize;
    private final long waitTimeoutMillis;
    private final Semaphore permits;
    private final AtomicInteger borrowed = new AtomicInteger();
    private final AtomicInteger maxBorrowed = new AtomicInteger();

    BoundedDataSource(DataSource target, int maxSize, long waitTimeoutMillis) {
        this.target = target;
        this.maxSize = maxSize;
        this.waitTimeoutMillis = waitTimeoutMillis;
        this.permits = new Semaphore(maxSize);
    }

    int borrowed() {
        return borrowed.get();
    }

    int maxBorrowed() {
        return maxBorrowed.get();
    }

    void resetStatistics() {
        maxBorrowed.set(borrowed.get());
    }

    @Override
    public Connection getConnection() throws SQLException {
        try {
            if (!permits.tryAcquire(waitTimeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new SQLException("Pool exhausted: all " + maxSize + " connections are in use");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException(e);
        }
        Connection connection;
        try {
            connection = target.getConnection();
        } catch (SQLException | RuntimeException e) {
            permits.release();
            throw e;
        }
        maxBorrowed.accumulateAndGet(borrowed.incrementAndGet(), Math::max);
        return wrap(connection);
    }

    private Connection wrap(Connection connection) {
        AtomicBoolean closed = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("close") && closed.compareAndSet(false, true)) {
                        borrowed.decrementAndGet();
                        permits.release();
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                });
    }

    @Override
    public Connection getConnection(String username, String password) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return target.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        target.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        target.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return target.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Not a wrapper for " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
