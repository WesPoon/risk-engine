package com.example.riskengine.infra;

import com.example.riskengine.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL repository backed by H2 in-memory database.
 * Manages both the risk_limit and risk_result tables.
 */
public class H2RiskRepository {

    private static final Logger log = LoggerFactory.getLogger(H2RiskRepository.class);
    private final Connection conn;

    public H2RiskRepository(String jdbcUrl) throws SQLException {
        this.conn = DriverManager.getConnection(jdbcUrl);
        ddl();
        seedLimits();
    }

    // ------------------------------------------------------------------ //
    //  Schema & seed data
    // ------------------------------------------------------------------ //

    private void ddl() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS risk_limit (
                    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    dimension    VARCHAR(20) NOT NULL,
                    bucket_value VARCHAR(100) NOT NULL,
                    max_abs_net_delta DOUBLE NOT NULL,
                    max_abs_net_vega  DOUBLE NOT NULL
                )""");

            st.execute("""
                CREATE TABLE IF NOT EXISTS risk_result (
                    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    dimension       VARCHAR(20)  NOT NULL,
                    bucket_value    VARCHAR(100) NOT NULL,
                    net_delta       DOUBLE NOT NULL,
                    net_gamma       DOUBLE NOT NULL,
                    net_vega        DOUBLE NOT NULL,
                    net_theta       DOUBLE NOT NULL,
                    limit_breached  BOOLEAN NOT NULL,
                    calculated_at   TIMESTAMP NOT NULL
                )""");
        }
        log.info("DDL executed – tables ready.");
    }

    private void seedLimits() throws SQLException {
        String sql = "INSERT INTO risk_limit(dimension,bucket_value,max_abs_net_delta,max_abs_net_vega) " +
                     "VALUES (?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            Object[][] seeds = {
                { "PORTFOLIO", "BOOK_A", 50_000.0, 10_000.0 },
                { "PORTFOLIO", "BOOK_B", 30_000.0,  8_000.0 },
                { "GICS",      "45",     80_000.0, 20_000.0 },  // IT sector
                { "GICS",      "40",     60_000.0, 15_000.0 },  // Financials
                { "COUNTRY",   "US",    200_000.0, 50_000.0 },
                { "COUNTRY",   "GB",     80_000.0, 20_000.0 },
            };
            for (Object[] row : seeds) {
                ps.setString(1, (String) row[0]);
                ps.setString(2, (String) row[1]);
                ps.setDouble(3, (Double) row[2]);
                ps.setDouble(4, (Double) row[3]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        log.info("Seeded {} risk limits.", 6);
    }

    // ------------------------------------------------------------------ //
    //  RiskLimit queries
    // ------------------------------------------------------------------ //

    public List<RiskLimit> findAllLimits() throws SQLException {
        List<RiskLimit> result = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM risk_limit")) {
            while (rs.next()) {
                result.add(new RiskLimit(
                        rs.getLong("id"),
                        AggKey.valueOf(rs.getString("dimension")),
                        rs.getString("bucket_value"),
                        rs.getDouble("max_abs_net_delta"),
                        rs.getDouble("max_abs_net_vega")));
            }
        }
        return result;
    }

    public java.util.Optional<RiskLimit> findLimit(AggKey dim, String bucket) throws SQLException {
        String sql = "SELECT * FROM risk_limit WHERE dimension=? AND bucket_value=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dim.name());
            ps.setString(2, bucket);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return java.util.Optional.of(new RiskLimit(
                        rs.getLong("id"), dim, bucket,
                        rs.getDouble("max_abs_net_delta"),
                        rs.getDouble("max_abs_net_vega")));
            }
        }
        return java.util.Optional.empty();
    }

    // ------------------------------------------------------------------ //
    //  RiskResult persistence
    // ------------------------------------------------------------------ //

    public void saveResult(RiskResult r) throws SQLException {
        String sql = """
            INSERT INTO risk_result
              (dimension, bucket_value, net_delta, net_gamma, net_vega, net_theta,
               limit_breached, calculated_at)
            VALUES (?,?,?,?,?,?,?,?)""";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, r.dimension().name());
            ps.setString(2, r.bucketValue());
            ps.setDouble(3, r.netDelta());
            ps.setDouble(4, r.netGamma());
            ps.setDouble(5, r.netVega());
            ps.setDouble(6, r.netTheta());
            ps.setBoolean(7, r.limitBreached());
            ps.setTimestamp(8, Timestamp.from(r.calculatedAt()));
            ps.executeUpdate();
        }
    }

    public List<RiskResult> findAllResults() throws SQLException {
        List<RiskResult> results = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT * FROM risk_result ORDER BY calculated_at DESC")) {
            while (rs.next()) {
                results.add(new RiskResult(
                        AggKey.valueOf(rs.getString("dimension")),
                        rs.getString("bucket_value"),
                        rs.getDouble("net_delta"),
                        rs.getDouble("net_gamma"),
                        rs.getDouble("net_vega"),
                        rs.getDouble("net_theta"),
                        rs.getBoolean("limit_breached"),
                        rs.getTimestamp("calculated_at").toInstant()));
            }
        }
        return results;
    }
}
