package com.infott.tdx.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Consumer;

/**
 * ETF 产品信息导入服务。
 *
 * 解析 ETF 分类文本文件（# T+0 / # BOND / # CASH 三个段落），
 * 合并重复代码，从 ETF_DAILY_QUOTE 补齐 stock_name / market / list_date / delist_date，
 * 最后 MERGE 写入 ETF_PRODUCT 表。
 */
public class EtfProductService implements AutoCloseable {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    // ── DDL / DML ────────────────────────────────────────────────────

    private static final String ENSURE_TABLE_DDL =
            "CREATE TABLE ETF_PRODUCT (" +
            "  stock_code     VARCHAR2(6)    NOT NULL," +
            "  stock_name     VARCHAR2(50)," +
            "  market         VARCHAR2(2)    NOT NULL," +
            "  etf_type       VARCHAR2(10)   NOT NULL," +
            "  trade_mode     VARCHAR2(2)    NOT NULL," +
            "  list_date      VARCHAR2(8)    NOT NULL," +
            "  delist_date    VARCHAR2(8)    NOT NULL," +
            "  insert_time    TIMESTAMP      DEFAULT SYSTIMESTAMP," +
            "  update_time    TIMESTAMP      DEFAULT SYSTIMESTAMP," +
            "  CONSTRAINT pk_etf_product PRIMARY KEY (stock_code, market)" +
            ")";

    private static final String MERGE_SQL =
            "MERGE INTO ETF_PRODUCT t " +
            "USING (SELECT ? AS stock_code, ? AS stock_name, ? AS market, " +
            "              ? AS etf_type, ? AS trade_mode, " +
            "              ? AS list_date, ? AS delist_date " +
            "       FROM dual) s " +
            "ON (t.stock_code = s.stock_code AND t.market = s.market) " +
            "WHEN MATCHED THEN UPDATE SET " +
            "  stock_name = s.stock_name, etf_type = s.etf_type, " +
            "  trade_mode = s.trade_mode, list_date = s.list_date, " +
            "  delist_date = s.delist_date, update_time = SYSTIMESTAMP " +
            "WHEN NOT MATCHED THEN INSERT (" +
            "  stock_code, stock_name, market, etf_type, trade_mode, " +
            "  list_date, delist_date, insert_time, update_time" +
            ") VALUES (" +
            "  s.stock_code, s.stock_name, s.market, s.etf_type, s.trade_mode, " +
            "  s.list_date, s.delist_date, SYSTIMESTAMP, SYSTIMESTAMP" +
            ")";

    private static final String QUERY_STOCK_INFO_SQL =
            "SELECT market, stock_name, MIN(trade_date) AS list_date, MAX(trade_date) AS latest_date " +
            "FROM ETF_DAILY_QUOTE " +
            "WHERE stock_code = ? " +
            "GROUP BY stock_code, market, stock_name";

    // ── 字段 ─────────────────────────────────────────────────────────

    private final Connection conn;
    private final Consumer<String> log;

    public EtfProductService(DbConfig config, Consumer<String> log) throws SQLException {
        this.conn = DriverManager.getConnection(config.url(), config.user(), config.password());
        this.log = log;
        conn.setAutoCommit(false);
        ensureTable();
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }

    // ═══════════════════════════════════════════════════════════════════
    // 公共入口
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 解析文件并导入数据库。
     *
     * @param file ETF 分类文本文件
     * @return 统计信息
     */
    public ImportStats importFile(File file) throws Exception {
        long t0 = System.currentTimeMillis();

        // 1. 解析文件
        Map<String, EtfRow> etfMap = parseFile(file);
        log.accept("  解析到 " + etfMap.size() + " 个 ETF 代码");

        if (etfMap.isEmpty()) {
            log.accept("  [信息] 文件中未找到有效 ETF 代码");
            return new ImportStats(0, 0, 0, System.currentTimeMillis() - t0);
        }

        // 2. 从 ETF_DAILY_QUOTE 补齐信息
        int filled = fillStockInfo(etfMap);
        log.accept("  从行情表补齐 " + filled + " 只 ETF 信息");

        // 3. 写入数据库
        int inserted = 0;
        int updated = 0;
        int total = 0;

        try (PreparedStatement ps = conn.prepareStatement(MERGE_SQL)) {
            int batchCnt = 0;
            for (EtfRow row : etfMap.values()) {
                if (row.market == null || row.stockName == null) {
                    log.accept("  [跳过] " + row.stockCode + "  行情表中无记录");
                    continue;
                }

                ps.setString(1, row.stockCode);
                ps.setString(2, row.stockName);
                ps.setString(3, row.market);
                ps.setString(4, row.etfType);
                ps.setString(5, row.tradeMode);
                ps.setString(6, row.listDate);
                ps.setString(7, row.delistDate);
                ps.addBatch();
                total++;

                if (++batchCnt % 200 == 0) {
                    int[] results = ps.executeBatch();
                    for (int r : results) {
                        if (r == 1) inserted++; else updated++;
                    }
                    conn.commit();
                }
            }
            int[] results = ps.executeBatch();
            for (int r : results) {
                if (r == 1) inserted++; else updated++;
            }
            conn.commit();
        }

        long elapsed = System.currentTimeMillis() - t0;

        ImportStats stats = new ImportStats(total, inserted, updated, elapsed);
        log.accept("  导入完成: " + total + " 条 (新增 " + inserted + ", 更新 " + updated + ")");
        log.accept("  耗时: " + formatMs(elapsed));
        return stats;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 文件解析
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 解析 ETF 分类文本文件。
     * 格式：
     *   # T+0
     *   159001
     *   ...
     *   # BOND
     *   511010
     *   ...
     *   # CASH
     *   159001
     *   ...
     *
     * 同一代码可能出现在多个段落中 → 合并属性。
     */
    private Map<String, EtfRow> parseFile(File file) throws IOException {
        Map<String, EtfRow> map = new LinkedHashMap<>();
        String currentSection = null;

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();

                // 跳过空行
                if (trimmed.isEmpty()) continue;

                // 段落标题
                if (trimmed.startsWith("#")) {
                    String section = trimmed.substring(1).trim().toUpperCase();
                    if (section.startsWith("T+0") || section.startsWith("T0")) {
                        currentSection = "T0";
                    } else if (section.equals("BOND")) {
                        currentSection = "BOND";
                    } else if (section.equals("CASH")) {
                        currentSection = "CASH";
                    }
                    continue;
                }

                // ETF 代码（6 位数字）
                String code = trimmed;
                if (code.length() != 6 || !code.matches("\\d{6}")) {
                    log.accept("  [跳过] 无效代码: " + code);
                    continue;
                }

                EtfRow row = map.computeIfAbsent(code, EtfRow::new);

                switch (currentSection) {
                    case "T0"  -> row.tradeMode = "T0";
                    case "BOND" -> row.etfType = "BOND";
                    case "CASH" -> row.etfType = "CASH";
                }
            }
        }
        return map;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 行情表补齐
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 从 ETF_DAILY_QUOTE 查询 stock_name、market、list_date、delist_date 并回填。
     *
     * @return 成功补齐的 ETF 数量
     */
    private int fillStockInfo(Map<String, EtfRow> etfMap) throws SQLException {
        int filled = 0;
        LocalDate today = LocalDate.now();

        try (PreparedStatement ps = conn.prepareStatement(QUERY_STOCK_INFO_SQL)) {
            for (EtfRow row : etfMap.values()) {
                ps.setString(1, row.stockCode);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        row.market    = rs.getString("market");
                        row.stockName = rs.getString("stock_name");
                        row.listDate  = rs.getString("list_date");

                        String latestDateStr = rs.getString("latest_date");
                        if (latestDateStr != null && !latestDateStr.isEmpty()) {
                            LocalDate latestDate = LocalDate.parse(latestDateStr, YYYYMMDD);
                            long daysSince = ChronoUnit.DAYS.between(latestDate, today);
                            if (daysSince > 180) {
                                row.delistDate = latestDateStr;
                            }
                        }
                        filled++;
                    }
                }
            }
        }
        return filled;
    }

    // ═══════════════════════════════════════════════════════════════════
    // DTO
    // ═══════════════════════════════════════════════════════════════════

    /** 一只 ETF 的解析/补齐结果 */
    static class EtfRow {
        final String stockCode;
        String etfType   = "STOCK";   // 默认 STOCK
        String tradeMode = "T1";      // 默认 T1
        String stockName;             // 从 ETF_DAILY_QUOTE 查询
        String market;                // "sh" / "sz"
        String listDate  = "19700101";
        String delistDate = "99991231";

        EtfRow(String stockCode) { this.stockCode = stockCode; }
    }

    /** 导入统计 */
    public record ImportStats(int total, int inserted, int updated, long elapsedMs) {}

    // ═══════════════════════════════════════════════════════════════════
    // 工具
    // ═══════════════════════════════════════════════════════════════════

    private void ensureTable() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(ENSURE_TABLE_DDL);
            conn.commit();
        } catch (SQLException e) {
            if (e.getErrorCode() != 955 && e.getErrorCode() != 1) throw e;
        }
    }

    private static String formatMs(long ms) {
        if (ms < 1000) return ms + "ms";
        return String.format("%.1fs", ms / 1000.0);
    }
}
