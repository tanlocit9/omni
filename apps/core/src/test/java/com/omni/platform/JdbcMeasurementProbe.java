package com.omni.platform;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import com.zaxxer.hikari.HikariDataSource;

/** Test-only execution-call boundary; does not claim to count TCP packets. */
final class JdbcMeasurementProbe implements BeanPostProcessor {
    final AtomicLong executions = new AtomicLong(),
            acquisitions = new AtomicLong(),
            commits = new AtomicLong(),
            rollbacks = new AtomicLong();
    final AtomicLong active = new AtomicLong(),
            idle = new AtomicLong(),
            waiting = new AtomicLong(),
            total = new AtomicLong();
    final ConcurrentLinkedQueue<Long> waitNanos = new ConcurrentLinkedQueue<>();
    HikariDataSource pool;

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (name.equals("dataSource") && bean instanceof HikariDataSource hikari) {
            pool = hikari;
            return new MeasuredDataSource(hikari);
        }
        return bean;
    }

    final class MeasuredDataSource extends DelegatingDataSource implements AutoCloseable {
        MeasuredDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws java.sql.SQLException {
            return measured(super::getConnection);
        }

        @Override
        public Connection getConnection(String u, String p) throws java.sql.SQLException {
            return measured(() -> super.getConnection(u, p));
        }

        @Override
        public void close() {
            pool.close();
        }
    }

    private Connection measured(SqlConnectionSupplier supplier) throws java.sql.SQLException {
        samplePool();
        long start = System.nanoTime();
        Connection connection = supplier.get();
        waitNanos.add(System.nanoTime() - start);
        acquisitions.incrementAndGet();
        samplePool();
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("commit")) commits.incrementAndGet();
                            if (method.getName().equals("rollback")) rollbacks.incrementAndGet();
                            try {
                                Object value = method.invoke(connection, args);
                                if (value instanceof Statement statement) {
                                    Class<?> type =
                                            value instanceof java.sql.CallableStatement
                                                    ? java.sql.CallableStatement.class
                                                    : value instanceof java.sql.PreparedStatement
                                                            ? java.sql.PreparedStatement.class
                                                            : Statement.class;
                                    return Proxy.newProxyInstance(
                                            type.getClassLoader(),
                                            new Class<?>[] {type},
                                            (p, m, a) -> {
                                                if (m.getName().startsWith("execute"))
                                                    executions.incrementAndGet();
                                                try {
                                                    return m.invoke(statement, a);
                                                } catch (InvocationTargetException e) {
                                                    throw e.getCause();
                                                }
                                            });
                                }
                                return value;
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
    }

    void samplePool() {
        if (pool == null || pool.getHikariPoolMXBean() == null) return;
        var mx = pool.getHikariPoolMXBean();
        active.accumulateAndGet(mx.getActiveConnections(), Math::max);
        idle.accumulateAndGet(mx.getIdleConnections(), Math::max);
        waiting.accumulateAndGet(mx.getThreadsAwaitingConnection(), Math::max);
        total.accumulateAndGet(mx.getTotalConnections(), Math::max);
    }

    void reset() {
        executions.set(0);
        acquisitions.set(0);
        commits.set(0);
        rollbacks.set(0);
        active.set(0);
        idle.set(0);
        waiting.set(0);
        total.set(0);
        waitNanos.clear();
        samplePool();
    }

    Map<String, Object> snapshot() {
        samplePool();
        return Map.of(
                "jdbcExecuteCalls",
                executions.get(),
                "connectionAcquisitions",
                acquisitions.get(),
                "commits",
                commits.get(),
                "rollbacks",
                rollbacks.get(),
                "acquisitionMs",
                distribution(new ArrayList<>(waitNanos)),
                "applicationDatabaseCalls",
                executions.get() + commits.get() + rollbacks.get(),
                "poolNow",
                Map.of(
                        "active",
                        pool.getHikariPoolMXBean().getActiveConnections(),
                        "idle",
                        pool.getHikariPoolMXBean().getIdleConnections(),
                        "waiting",
                        pool.getHikariPoolMXBean().getThreadsAwaitingConnection(),
                        "total",
                        pool.getHikariPoolMXBean().getTotalConnections()),
                "poolPeak",
                Map.of(
                        "active",
                        active.get(),
                        "idle",
                        idle.get(),
                        "waiting",
                        waiting.get(),
                        "total",
                        total.get()));
    }

    static Map<String, Object> distribution(List<Long> nanos) {
        if (nanos.isEmpty()) return Map.of("samples", 0, "unavailableReason", "NO_SAMPLES");
        Collections.sort(nanos);
        int n = nanos.size();
        return Map.of(
                "samples",
                n,
                "p50",
                nanos.get((int) Math.ceil(n * .50) - 1) / 1e6,
                "p95",
                nanos.get((int) Math.ceil(n * .95) - 1) / 1e6,
                "p99",
                nanos.get((int) Math.ceil(n * .99) - 1) / 1e6,
                "min",
                nanos.getFirst() / 1e6,
                "max",
                nanos.getLast() / 1e6);
    }

    @FunctionalInterface
    interface SqlConnectionSupplier {
        Connection get() throws java.sql.SQLException;
    }
}
