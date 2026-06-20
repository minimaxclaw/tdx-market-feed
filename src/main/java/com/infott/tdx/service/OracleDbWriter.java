package com.infott.tdx.service;

import com.infott.tdx.model.Market;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 将 CSV 数据导入 Oracle 表 ETF_DAILY_QUOTE。
 *
 * 导入策略（逐个ETF）：
 *   1. 新上市 ETF → 全量 INSERT
 *   2. gbbq 权息变动 → DELETE 该 ETF 全部旧数据 + 全量 INSERT（前复权价格已变）
 *   3. 漏交易日 → 仅 INSERT 缺失的交易日数据
 *   4. 正常 → MERGE 当日数据（覆盖更新，盘中多次执行）
 *
 * 使用跟踪表 ETF_IMPORT_TRACK：
 *   CREATE TABLE ETF_IMPORT_TRACK (
 *       market          VARCHAR2(2)   NOT NULL,
 *       stock_code      VARCHAR2(6)   NOT NULL,
 *       gbbq_hash       VARCHAR2(64),
 *       last_trade_date VARCHAR2(8),
 *       last_import_time TIMESTAMP,
 *       CONSTRAINT pk_etf_import_track PRIMARY KEY (market, stock_code)
 *   );
 */
public class OracleDbWriter {

    private static final int    BATCH_SIZE = 200;
    private static final String TRACK_DDL =
            "CREATE TABLE ETF_IMPORT_TRACK (" +
            "  market          VARCHAR2(2)   NOT NULL," +
            "  stock_code      VARCHAR2(6)   NOT NULL," +
            "  gbbq_hash       VARCHAR2(64)," +
            "  last_trade_date VARCHAR2(8)," +
            "  last_import_time TIMESTAMP," +
            "  CONSTRAINT pk_etf_import_track PRIMARY KEY (market, stock_code)" +
            ")";

    private static final String MERGE_SQL =
            "MERGE INTO ETF_DAILY_QUOTE t " +
            "USING (SELECT ? AS trade_date, ? AS market, ? AS stock_code, ? AS stock_name, " +
            "              ? AS open_price, ? AS high_price, ? AS low_price, ? AS close_price, " +
            "              ? AS volume, ? AS turnover, ? AS change_pct, " +
            "              ? AS last_trade_date, ? AS status " +
            "       FROM dual) s " +
            "ON (t.trade_date = s.trade_date AND t.market = s.market AND t.stock_code = s.stock_code) " +
            "WHEN MATCHED THEN UPDATE SET " +
            "  stock_name = s.stock_name, open_price = s.open_price, high_price = s.high_price, " +
            "  low_price = s.low_price, close_price = s.close_price, volume = s.volume, " +
            "  turnover = s.turnover, change_pct = s.change_pct, last_trade_date = s.last_trade_date, " +
            "  status = s.status, update_time = SYSTIMESTAMP " +
            "WHEN NOT MATCHED THEN INSERT (" +
            "  trade_date, market, stock_code, stock_name, open_price, high_price, low_price, " +
            "  close_price, volume, turnover, change_pct, last_trade_date, status, insert_time, update_time" +
            ") VALUES (" +
            "  s.trade_date, s.market, s.stock_code, s.stock_name, s.open_price, s.high_price, " +
            "  s.low_price, s.close_price, s.volume, s.turnover, s.change_pct, s.last_trade_date, " +
            "  s.status, SYSTIMESTAMP, SYSTIMESTAMP" +
            ")";

    private static final String UPSERT_TRACK_SQL =
            "MERGE INTO ETF_IMPORT_TRACK t " +
            "USING (SELECT ? AS market, ? AS stock_code FROM dual) s " +
            "ON (t.market = s.market AND t.stock_code = s.stock_code) " +
            "WHEN MATCHED THEN UPDATE SET gbbq_hash=?, last_trade_date=?, last_import_time=SYSTIMESTAMP " +
            "WHEN NOT MATCHED THEN INSERT VALUES (?, ?, ?, ?, SYSTIMESTAMP)";

    private static final String DELETE_STOCK_SQL =
            "DELETE FROM ETF_DAILY_QUOTE WHERE market=? AND stock_code=?";

    private static final String SELECT_LATEST_DATE_SQL =
            "SELECT MAX(trade_date) FROM ETF_DAILY_QUOTE WHERE market=? AND stock_code=?";

    private static final String SELECT_TRACK_SQL =
            "SELECT gbbq_hash, last_trade_date FROM ETF_IMPORT_TRACK WHERE market=? AND stock_code=?";

    // CSV 解析格式（与 CsvExporter.HEADERS 一致）
    private static final CSVFormat CSV_READ_FMT = CSVFormat.DEFAULT.builder()
            .setHeader(CsvExporter.HEADERS)
            .setSkipHeaderRecord(true)
            .build();

    private final Connection conn;

    // ── 导入结果统计 ──
    private int newEtfCount;
    private int gbbqChangedCount;
    private int catchUpCount;
    private int skippedCount;
    private final List<String> newEtfDetails     = new ArrayList<>();
    private final List<String> gbbqChangedDetails = new ArrayList<>();
    private final List<String> catchUpDetails    = new ArrayList<>();

    public OracleDbWriter(DbConfig config) throws SQLException {
        this.conn = DriverManager.getConnection(config.url(), config.user(), config.password());
        conn.setAutoCommit(false);
        ensureTrackTable();
    }

    // ────────── 公共入口 ──────────

    /**
     * 导入一个 ETF 的所有 CSV 数据。
     *
     * @param csvFile   CSV 文件
     * @param market    市场
     * @param code      股票代码
     * @param gbbqHash  当前 gbbq 权息数据的 MD5 hex（可为 null）
     * @return 实际写入的行数
     */
    public int importCsv(File csvFile, Market market, String code, String gbbqHash) throws Exception {
        if (!csvFile.exists()) return 0;

        // 1. 读取 CSV 全部行到内存（ETF 日线数据量小，最多数千行）
        List<CsvRow> allRows = readCsv(csvFile);

        // 2. 计算 CSV 中最新交易日
        String latestTradeDate = "19700101";
        for (CsvRow row : allRows) {
            if (row.tradeDate.compareTo(latestTradeDate) > 0) {
                latestTradeDate = row.tradeDate;
            }
        }

        // 3. 判断导入策略
        TrackInfo track = getTrackInfo(market.getCode(), code);
        boolean isNew           = (track == null);
        boolean gbbqChanged     = (gbbqHash != null && track != null &&
                                   !gbbqHash.equals(track.gbbqHash));

        List<CsvRow> toImport;
        boolean fullReimport;

        if (isNew) {
            // 新上市 ETF：全量导入
            toImport = allRows;
            fullReimport = true;
            newEtfCount++;
            newEtfDetails.add(market.getCode() + code + " " + latestTradeDate);
        } else if (gbbqChanged) {
            // gbbq 变化：删除旧数据，全量重新导入
            deleteStockData(market.getCode(), code);
            toImport = allRows;
            fullReimport = true;
            gbbqChangedCount++;
            gbbqChangedDetails.add(market.getCode() + code + " " + latestTradeDate);
        } else {
            // 正常：仅导入缺失/当日的数据
            String dbLatest = track.lastTradeDate != null ? track.lastTradeDate : "19700101";
            if (latestTradeDate.compareTo(dbLatest) <= 0) {
                // 无新数据：仅 upsert 当日数据（盘中多次执行场景）
                toImport = new ArrayList<>();
                String today = latestTradeDate; // today = CSV 最新日期
                for (CsvRow row : allRows) {
                    if (row.tradeDate.equals(today)) {
                        toImport.add(row);
                    }
                }
                fullReimport = false;
                if (!toImport.isEmpty()) {
                    // 当日覆盖更新，不计入漏交易日
                } else {
                    // 无任何变化，跳过
                    skippedCount++;
                    return 0;
                }
            } else {
                // 有漏交易日：导入 dbLatest 之后的全部数据
                toImport = new ArrayList<>();
                for (CsvRow row : allRows) {
                    if (row.tradeDate.compareTo(dbLatest) > 0) {
                        toImport.add(row);
                    }
                }
                fullReimport = false;
                int missed = 0;
                String prev = dbLatest;
                for (CsvRow row : toImport) {
                    if (row.tradeDate.compareTo(prev) > 0) missed++;
                    prev = row.tradeDate;
                }
                if (missed > 0) {
                    catchUpCount += missed;
                    catchUpDetails.add(market.getCode() + code + " 补" + missed + "日 ("
                            + dbLatest + " → " + latestTradeDate + ")");
                }
            }
        }

        if (toImport.isEmpty()) {
            skippedCount++;
            // 仍更新跟踪表（记录 gbbq_hash）
            upsertTrack(market.getCode(), code, gbbqHash, latestTradeDate);
            return 0;
        }

        // 4. 批量写入
        int written = batchMerge(toImport, latestTradeDate);

        // 5. 更新跟踪表
        upsertTrack(market.getCode(), code, gbbqHash, latestTradeDate);

        conn.commit();
        return written;
    }

    public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }

    // ────────── 统计 ──────────

    public int  getNewEtfCount()        { return newEtfCount; }
    public int  getGbbqChangedCount()   { return gbbqChangedCount; }
    public int  getCatchUpCount()       { return catchUpCount; }
    public int  getSkippedCount()       { return skippedCount; }
    public List<String> getNewEtfDetails()      { return newEtfDetails; }
    public List<String> getGbbqChangedDetails() { return gbbqChangedDetails; }
    public List<String> getCatchUpDetails()     { return catchUpDetails; }

    // ────────── 私有 ──────────

    /** 读取 CSV 文件全部行 */
    private List<CsvRow> readCsv(File csvFile) throws IOException {
        List<CsvRow> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(csvFile), StandardCharsets.UTF_8));
             CSVParser parser = CSVParser.parse(br, CSV_READ_FMT)) {
            for (CSVRecord rec : parser) {
                CsvRow row = new CsvRow();
                row.tradeDate  = rec.get("交易日").trim();
                row.market     = rec.get("市场").trim();
                row.stockCode  = rec.get("股票代码").trim();
                row.stockName  = rec.get("股票名称").trim();
                row.openPrice  = rec.get("开盘价").trim();
                row.highPrice  = rec.get("最高价").trim();
                row.lowPrice   = rec.get("最低价").trim();
                row.closePrice = rec.get("收盘价").trim();
                row.volume     = rec.get("成交量").trim();
                row.turnover   = rec.get("成交额").trim();
                // 涨幅可能不存在（旧格式兼容）
                try { row.changePct = rec.get("涨幅").trim(); }
                catch (IllegalArgumentException e) { row.changePct = "0.00"; }
                rows.add(row);
            }
        }
        return rows;
    }

    /** 批量 MERGE */
    private int batchMerge(List<CsvRow> rows, String lastTradeDate) throws SQLException {
        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement(MERGE_SQL)) {
            for (int i = 0; i < rows.size(); i++) {
                CsvRow r = rows.get(i);
                int idx = 1;
                ps.setString(idx++, r.tradeDate);
                ps.setString(idx++, r.market);
                ps.setString(idx++, r.stockCode);
                ps.setString(idx++, r.stockName);
                ps.setBigDecimal(idx++, safeDecimal(r.openPrice));
                ps.setBigDecimal(idx++, safeDecimal(r.highPrice));
                ps.setBigDecimal(idx++, safeDecimal(r.lowPrice));
                ps.setBigDecimal(idx++, safeDecimal(r.closePrice));
                ps.setLong(idx++,   safeLong(r.volume));
                ps.setBigDecimal(idx++, safeDecimal(r.turnover));
                ps.setBigDecimal(idx++, safeDecimal(r.changePct));
                ps.setString(idx++, lastTradeDate);
                ps.setString(idx++, "A");
                ps.addBatch();

                if ((i + 1) % BATCH_SIZE == 0) {
                    int[] results = ps.executeBatch();
                    count += Arrays.stream(results).sum();
                }
            }
            int[] results = ps.executeBatch();
            count += Arrays.stream(results).sum();
        }
        return count;
    }

    /** 删除某股票全部数据（gbbq 变动时用） */
    private void deleteStockData(String market, String code) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(DELETE_STOCK_SQL)) {
            ps.setString(1, market);
            ps.setString(2, code);
            ps.executeUpdate();
        }
    }

    /** 查询跟踪信息 */
    private TrackInfo getTrackInfo(String market, String code) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_TRACK_SQL)) {
            ps.setString(1, market);
            ps.setString(2, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new TrackInfo(rs.getString("gbbq_hash"), rs.getString("last_trade_date"));
                }
            }
        }
        return null;
    }

    /** 更新/插入跟踪记录 */
    private void upsertTrack(String market, String code, String gbbqHash,
                             String lastTradeDate) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPSERT_TRACK_SQL)) {
            ps.setString(1, market);
            ps.setString(2, code);
            ps.setString(3, gbbqHash);
            ps.setString(4, lastTradeDate);
            ps.setString(5, market);
            ps.setString(6, code);
            ps.setString(7, gbbqHash);
            ps.setString(8, lastTradeDate);
            ps.executeUpdate();
        }
    }

    /** 确保跟踪表存在 */
    private void ensureTrackTable() throws SQLException {
        try {
            try (Statement st = conn.createStatement()) {
                st.execute(TRACK_DDL);
            }
            conn.commit();
        } catch (SQLException e) {
            // ORA-00955: name is already used → 表已存在，忽略
            if (e.getErrorCode() != 955) throw e;
        }
    }

    // ── 安全类型转换 ──

    private static java.math.BigDecimal safeDecimal(String s) {
        if (s == null || s.isEmpty()) return java.math.BigDecimal.ZERO;
        try { return new java.math.BigDecimal(s); }
        catch (NumberFormatException e) { return java.math.BigDecimal.ZERO; }
    }

    private static long safeLong(String s) {
        if (s == null || s.isEmpty()) return 0L;
        try { return Long.parseLong(s); }
        catch (NumberFormatException e) { return 0L; }
    }

    // ────────── 内部类型 ──────────

    private static class CsvRow {
        String tradeDate, market, stockCode, stockName;
        String openPrice, highPrice, lowPrice, closePrice;
        String volume, turnover, changePct;
    }

    private record TrackInfo(String gbbqHash, String lastTradeDate) {}

    // ────────── 静态工具 ──────────

    /** 计算 XdxRecord 列表的 MD5 哈希（hex），用于 gbbq 变动检测 */
    public static String hashXdxRecords(List<com.infott.tdx.model.XdxRecord> records) {
        if (records == null || records.isEmpty()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            for (com.infott.tdx.model.XdxRecord r : records) {
                md.update(String.format("%d|%.6f|%.6f|%.6f|%.6f",
                        r.getDate(), r.getDividend(), r.getSendRatio(),
                        r.getAllotRatio(), r.getAllotPrice()).getBytes(StandardCharsets.UTF_8));
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }
}
