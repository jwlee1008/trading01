package com.signallab.api.global.config;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

final class NoopDataSource implements DataSource {

    @Override
    public Connection getConnection() {
        throw new UnsupportedOperationException("Database connections are unavailable when DATA_STORE=mock");
    }

    @Override
    public Connection getConnection(String username, String password) {
        throw new UnsupportedOperationException("Database connections are unavailable when DATA_STORE=mock");
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(int seconds) {
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLException("NoopDataSource cannot be unwrapped");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return false;
    }
}
