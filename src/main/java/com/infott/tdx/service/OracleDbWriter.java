package com.infott.tdx.service;

import com.infott.tdx.model.AdjustedBar;
import com.infott.tdx.model.Market;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * 将前复权数据批量导入 Oracle 表 ETF_DAILY_QUOTE。
 *
 * 优化策略：
 *   1. 启动时一次查询所有 track 信息到 Map
 *   2. 三路分流：新上市（全量）、补漏（共享 PS）、仅当日（共享 PS）
 *   3. 取消 gbbq_hash 变动检测 —— 历史 gbbq 变更不影响已有数据
 *   4. 共享 PreparedStatement 大幅减少 prepare/close 开销
 *   5. insert_time 仅在 INSERT 时设置，UPDATE 保留原值
 */
public class OracleDbWriter {

    private static final int BATCH_SIZE = 200;

    private static final String TRACK_DDL =
            "CREATE TABLE ETF_IMPORT_TRACK (" +
            "  market          VARCHAR2(2)   NOT NULL," +
            "  stock_code      VARCHAR2(6)   NOT NULL," +
            "  gbbq_hash       VARCHAR2(64)," +
            "  last_trade_date VARCHAR2(8)," +
            "  last_import_time TIMESTAMP," +
            "  CONSTRAINT pk_etf_import_track PRIMARY KEY (market, stock_code)" +
            ")";

    private static final String LOAD_ALL_TRACK_SQL =
            "SELECT market, stock_code, gbbq_hash, last_trade_date FROM ETF_IMPORT_TRACK";

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
            "  status = s.status, insert_time = t.insert_time, update_time = SYSTIMESTAMP " +
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

    private static final String COUNT_SQL =
            "SELECT COUNT(*) FROM ETF_DAILY_QUOTE";

    private static final String COUNT_BY_STOCK_SQL =
            "SELECT market, stock_code, COUNT(*) FROM ETF_DAILY_QUOTE GROUP BY market, stock_code";

    private final Connection conn;
    private final Consumer<String> log;

    // 统计
    private int newEtfCount, catchUpCount, skippedCount;
    private final List<String> newEtfDetails   = new ArrayList<>();
    private final List<String> catchUpDetails  = new ArrayList<>();

    public OracleDbWriter(DbConfig config, Consumer<String> log) throws SQLException {
        this.conn = DriverManager.getConnection(config.url(), config.user(), config.password());
        this.log = log;
        conn.setAutoCommit(false);
        ensureTrackTable();
    }

    // ══════ 公共入口（批量，高效） ══════

    /**
     * 批量导入所有 ETF 数据。
     *
     * @param items 所有待导入的 ETF（含全量 bars、名称、gbbq_hash）
     */
    public void importAll(List<ImportItem> items) throws Exception {
        // 1. 一次性加载全部 track 信息
        Map<String, TrackInfo> trackMap = loadAllTracks();

        // 2. 三路分流
        List<ImportItem> newEtfs   = new ArrayList<>();
        List<ImportItem> catchUps  = new ArrayList<>();
        List<ImportItem> latests   = new ArrayList<>();

        for (ImportItem item : items) {
            TrackInfo track = trackMap.get(key(item.marketCode, item.code));
            String latestDate = String.valueOf(item.bars.get(item.bars.size() - 1).getDate());

            if (track == null) {
                newEtfs.add(item);
                item.latestTradeDate = latestDate;
            } else {
                String dbLatest = track.lastTradeDate != null ? track.lastTradeDate : "19700101";
                if (latestDate.compareTo(dbLatest) <= 0) {
                    latests.add(item);
                } else {
                    catchUps.add(item);
                }
                item.latestTradeDate = latestDate;
                item.dbLatestDate    = dbLatest;
            }
        }

        // 3. 执行：全量重导（新上市，逐只处理）
        log.accept("  ══ 新上市 ETF：" + newEtfs.size() + " 只（全量导入）══");
        for (ImportItem item : newEtfs) {
            String tag = item.tag();
            logRun(tag, "🆕 全量导入 " + item.bars.size() + " 条 → " + item.latestTradeDate);
            int written = singleMerge(item, item.bars);
            upsertTrack(item.marketCode, item.code, item.gbbqHash, item.latestTradeDate);
            conn.commit();
            newEtfCount++;
            newEtfDetails.add(tag + " " + item.latestTradeDate);
            if (item.bars.size() > 365) logRun(tag, "  ↳ 写入 " + written + " 行");
        }

        // 4. 执行：补漏（共享 PreparedStatement）
        if (!catchUps.isEmpty()) {
            log.accept("  ══ 补漏交易日：" + catchUps.size() + " 只 ══");
            int caughtUp = batchMergeShared(catchUps, true);
            for (ImportItem item : catchUps) {
                int missed = countDistinctDates(item.toImport);
                catchUpCount += missed;
                String tag = item.tag();
                catchUpDetails.add(tag + " 补" + missed + "日 ("
                        + item.dbLatestDate + " → " + item.latestTradeDate + ")");
                logRun(tag, "📅 补漏 " + missed + "日 ("
                        + item.dbLatestDate + " → " + item.latestTradeDate + ")");
                upsertTrack(item.marketCode, item.code, item.gbbqHash, item.latestTradeDate);
            }
            conn.commit();
        }

        // 5. 执行：仅当日（共享 PreparedStatement，最快路径）
        if (!latests.isEmpty()) {
            log.accept("  ══ 更新当日：" + latests.size() + " 只 ══");
            batchMergeShared(latests, false);
            for (ImportItem item : latests) {
                upsertTrack(item.marketCode, item.code, item.gbbqHash, item.latestTradeDate);
                logRun(item.tag(), "↻ 更新当日 " + item.latestTradeDate);
            }
            conn.commit();
        }
    }

    // ══════ 统计 ══════

    public int  getNewEtfCount()   { return newEtfCount; }
    public int  getCatchUpCount()  { return catchUpCount; }
    public int  getSkippedCount()  { return skippedCount; }
    public List<String> getNewEtfDetails()  { return newEtfDetails; }
    public List<String> getCatchUpDetails() { return catchUpDetails; }

    public void close() { try { conn.close(); } catch (SQLException ignored) {} }

    // ══════ DB 总数 ══════

    public int countDbRows() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(COUNT_SQL);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public Map<String, Integer> countDbRowsByStock() throws SQLException {
        Map<String, Integer> map = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(COUNT_BY_STOCK_SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) map.put(rs.getString(1) + "|" + rs.getString(2), rs.getInt(3));
        }
        return map;
    }

    /** 强制全量重导（先删后插），用于一致性修复 */
    public int forceReimport(Market market, String code, String name,
                             List<AdjustedBar> bars, String gbbqHash) throws Exception {
        if (bars == null || bars.isEmpty()) return 0;
        String latest = String.valueOf(bars.get(bars.size() - 1).getDate());
        deleteStockData(market.getCode(), code);
        int written = singleMerge(new ImportItem(market.getCode(), code, name, bars, gbbqHash), bars);
        upsertTrack(market.getCode(), code, gbbqHash, latest);
        conn.commit();
        return written;
    }

    // ══════ 批量写入（共享 PS） ══════

    /**
     * 多个 ETF 共享一个 PreparedStatement 批量写入。
     * @param catchUp true=补漏模式（需过滤历史日期），false=仅当日模式（只写最后1条）
     */
    private int batchMergeShared(List<ImportItem> items, boolean catchUp) throws SQLException {
        int total = 0;
        try (PreparedStatement ps = conn.prepareStatement(MERGE_SQL)) {
            int batchCnt = 0;
            for (ImportItem item : items) {
                if (catchUp) {
                    // 补漏：写入 dbLatest 之后的所有 bar
                    item.toImport = new ArrayList<>();
                    for (int i = 0; i < item.bars.size(); i++) {
                        if (String.valueOf(item.bars.get(i).getDate()).compareTo(item.dbLatestDate) > 0) {
                            item.toImport.add(i);  // 存索引，避免 indexOf
                        }
                    }
                } else {
                    // 仅当日：只写最后 1 条
                    item.toImport = List.of(item.bars.size() - 1);
                }

                for (int barIdx : item.toImport) {
                    setParams(ps, item, barIdx);
                    ps.addBatch();
                    if (++batchCnt % BATCH_SIZE == 0) {
                        total += sum(ps.executeBatch());
                    }
                }
            }
            total += sum(ps.executeBatch());
        }
        return total;
    }

    /** 单只 ETF 独立写入（全量，用于新上市 / 修复） */
    private int singleMerge(ImportItem item, List<AdjustedBar> bars) throws SQLException {
        int total = 0;
        try (PreparedStatement ps = conn.prepareStatement(MERGE_SQL)) {
            for (int i = 0; i < bars.size(); i++) {
                setParams(ps, item, i);
                ps.addBatch();
                if ((i + 1) % BATCH_SIZE == 0) total += sum(ps.executeBatch());
            }
            total += sum(ps.executeBatch());
        }
        return total;
    }

    /** 设置 PreparedStatement 参数（barIdx 是 bar 在 item.bars 中的索引） */
    private void setParams(PreparedStatement ps, ImportItem item, int barIdx) throws SQLException {
        AdjustedBar bar = item.bars.get(barIdx);
        int idx = 1;
        ps.setString(idx++, String.valueOf(bar.getDate()));
        ps.setString(idx++, item.marketCode);
        ps.setString(idx++, item.code);
        ps.setString(idx++, item.name);
        ps.setBigDecimal(idx++, bd(bar.getAdjOpen()));
        ps.setBigDecimal(idx++, bd(bar.getAdjHigh()));
        ps.setBigDecimal(idx++, bd(bar.getAdjLow()));
        ps.setBigDecimal(idx++, bd(bar.getAdjClose()));
        ps.setLong(idx++,      bar.getVolume());
        ps.setBigDecimal(idx++, bd(bar.getAmount()));
        ps.setBigDecimal(idx++, bd(computeChangePct(item.bars, barIdx)));
        ps.setString(idx++, item.latestTradeDate);
        ps.setString(idx++, "A");
    }

    // ══════ 涨幅 ══════

    static double computeChangePct(List<AdjustedBar> allBars, int idx) {
        AdjustedBar bar = allBars.get(idx);
        double close = bar.getAdjClose();
        double prev;
        if (idx > 0)       prev = allBars.get(idx - 1).getAdjClose();
        else if (idx == 0) prev = bar.getAdjOpen();
        else               prev = bar.getAdjOpen();  // 容错
        if (prev <= 0) return 0.0;
        return (close - prev) / prev * 100.0;
    }

    // ══════ 跟踪表 ══════

    private Map<String, TrackInfo> loadAllTracks() throws SQLException {
        Map<String, TrackInfo> map = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(LOAD_ALL_TRACK_SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String k = rs.getString(1) + "|" + rs.getString(2);
                map.put(k, new TrackInfo(rs.getString(3), rs.getString(4)));
            }
        }
        return map;
    }

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

    private void deleteStockData(String market, String code) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(DELETE_STOCK_SQL)) {
            ps.setString(1, market);
            ps.setString(2, code);
            ps.executeUpdate();
        }
    }

    private void ensureTrackTable() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(TRACK_DDL);
            conn.commit();
        } catch (SQLException e) {
            if (e.getErrorCode() != 955 && e.getErrorCode() != 1) throw e;
        }
    }

    // ══════ 工具 ══════

    private void logRun(String tag, String msg) {
        log.accept("  [" + tag + "] " + msg);
    }

    private static String key(String market, String code) { return market + "|" + code; }

    private static java.math.BigDecimal bd(double v) { return java.math.BigDecimal.valueOf(v); }
    private static java.math.BigDecimal bd(float v)  { return java.math.BigDecimal.valueOf(v); }
    private static int sum(int[] a) { int s = 0; for (int r : a) if (r > 0) s += r; return s; }
    private static int countDistinctDates(List<Integer> indices) {
        return indices.size();  // 补漏时每个 barIdx 对应一个不同日期，简化处理
    }

    private record TrackInfo(String gbbqHash, String lastTradeDate) {}

    // ══════ 导入项 DTO ══════

    public static class ImportItem {
        final String marketCode;
        final String code;
        final String name;
        final List<AdjustedBar> bars;
        final String gbbqHash;

        // 内部使用
        String latestTradeDate;
        String dbLatestDate;
        List<Integer> toImport;  // bar 索引列表，避免 indexOf

        public ImportItem(String marketCode, String code, String name,
                          List<AdjustedBar> bars, String gbbqHash) {
            this.marketCode = marketCode;
            this.code       = code;
            this.name       = name;
            this.bars       = bars;
            this.gbbqHash   = gbbqHash;
        }

        String tag() { return marketCode + code + " " + name; }
    }

    // ══════ 静态工具 ══════

    public static String hashXdxRecords(List<com.infott.tdx.model.XdxRecord> records) {
        if (records == null || records.isEmpty()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            for (com.infott.tdx.model.XdxRecord r : records) {
                md.update(String.format("%d|%.6f|%.6f|%.6f|%.6f",
                        r.getDate(), r.getDividend(), r.getSendRatio(),
                        r.getAllotRatio(), r.getAllotPrice())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) { return ""; }
    }
}
