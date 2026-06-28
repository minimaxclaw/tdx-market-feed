package com.infott.tdx.service;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

/**
 * RPS（Relative Price Strength / 相对价格强度）计算服务。
 *
 * 计算 5/10/15/20/50 日 RPS 并写入 ETF_DAILY_RPS 表。
 * 每次最多处理 30 个交易日，分批推进。
 */
public class RpsService implements AutoCloseable {

    private static final int[] N_DAYS = {5, 10, 15, 20, 50};
    private static final int MAX_N = 50;
    private static final int MAX_BATCH_DAYS = 30;
    private static final LocalDate DEFAULT_START = LocalDate.of(2016, 1, 1);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    // ── DDL / DML ────────────────────────────────────────────────────

    private static final String ENSURE_TABLE_DDL =
            "CREATE TABLE ETF_DAILY_RPS (" +
            "  trade_date  VARCHAR2(8)   NOT NULL," +
            "  stock_code  VARCHAR2(6)   NOT NULL," +
            "  rps5        NUMBER(5,2)," +
            "  rps10       NUMBER(5,2)," +
            "  rps15       NUMBER(5,2)," +
            "  rps20       NUMBER(5,2)," +
            "  rps50       NUMBER(5,2)," +
            "  insert_time TIMESTAMP     DEFAULT SYSTIMESTAMP," +
            "  update_time TIMESTAMP     DEFAULT SYSTIMESTAMP," +
            "  CONSTRAINT pk_etf_daily_rps PRIMARY KEY (trade_date, stock_code)" +
            ")";

    private static final String GET_MAX_DATE_SQL =
            "SELECT MAX(trade_date) FROM ETF_DAILY_RPS";

    private static final String GET_EXCLUDED_CODES_SQL =
            "SELECT stock_code FROM ETF_PRODUCT WHERE etf_type IN ('BOND','CASH')";

    private static final String MERGE_RPS_SQL =
            "MERGE INTO ETF_DAILY_RPS t " +
            "USING (SELECT ? AS trade_date, ? AS stock_code, " +
            "              ? AS rps5, ? AS rps10, ? AS rps15, ? AS rps20, ? AS rps50 " +
            "       FROM dual) s " +
            "ON (t.trade_date = s.trade_date AND t.stock_code = s.stock_code) " +
            "WHEN MATCHED THEN UPDATE SET " +
            "  rps5 = s.rps5, rps10 = s.rps10, rps15 = s.rps15, " +
            "  rps20 = s.rps20, rps50 = s.rps50, update_time = SYSTIMESTAMP " +
            "WHEN NOT MATCHED THEN INSERT (" +
            "  trade_date, stock_code, rps5, rps10, rps15, rps20, rps50, " +
            "  insert_time, update_time" +
            ") VALUES (" +
            "  s.trade_date, s.stock_code, s.rps5, s.rps10, s.rps15, s.rps20, s.rps50, " +
            "  SYSTIMESTAMP, SYSTIMESTAMP" +
            ")";

    // ── 字段 ─────────────────────────────────────────────────────────

    private final Connection conn;
    private final Consumer<String> log;

    public RpsService(DbConfig config, Consumer<String> log) throws SQLException {
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
     * 执行一次 RPS 计算（最多 30 个交易日）。
     *
     * @return 本次处理的交易日数量
     */
    public int process() throws Exception {
        // 1. 确定最新交易日
        HolidayCalendarService cal = new HolidayCalendarService(log);
        LocalDate today = LocalDate.now();
        LocalDate latestTradeDate = cal.isTradeDay(today)
                ? today
                : cal.findLastTradeDay(today);
        log.accept("  最新交易日: " + latestTradeDate.format(YYYYMMDD));

        // 2. 获取已计算到的最新 RPS 日期
        String lastRpsDateStr = getLatestRpsDate();
        LocalDate startExclusive;

        if (lastRpsDateStr != null) {
            LocalDate lastRpsDate = LocalDate.parse(lastRpsDateStr, YYYYMMDD);
            log.accept("  已计算到: " + lastRpsDateStr);

            if (!lastRpsDate.isBefore(latestTradeDate)) {
                // 已经是最新
                log.accept("  RPS 已是最新，无需计算");
                return 0;
            }
            startExclusive = lastRpsDate;
        } else {
            // 首次计算：从 2016-01-01 开始
            log.accept("  首次计算，起始日期: " + DEFAULT_START.format(YYYYMMDD));
            // 找到 2016-01-01（含）起的第一个交易日
            LocalDate firstTradeDay = cal.nextTradeDay(DEFAULT_START.minusDays(1));
            if (firstTradeDay == null) {
                log.accept("  [错误] 未找到 2016-01-01 后的交易日");
                return 0;
            }
            startExclusive = firstTradeDay.minusDays(1);
        }

        // 3. 获取待计算的交易日列表（最多 30 个）
        List<LocalDate> targetDates = cal.nextNTradeDays(startExclusive, MAX_BATCH_DAYS);

        // 截断到 latestTradeDate
        List<LocalDate> filtered = new ArrayList<>();
        for (LocalDate d : targetDates) {
            if (!d.isAfter(latestTradeDate)) {
                filtered.add(d);
            }
        }
        targetDates = filtered;

        if (targetDates.isEmpty()) {
            log.accept("  RPS 已是最新（无待计算交易日）");
            return 0;
        }

        log.accept("  本次需计算 " + targetDates.size() + " 个交易日: "
                + targetDates.get(0).format(YYYYMMDD) + " ~ "
                + targetDates.get(targetDates.size() - 1).format(YYYYMMDD));

        // 4. 收集本次所有需要的行情日期
        Set<LocalDate> allQuoteDates = new LinkedHashSet<>();
        for (LocalDate td : targetDates) {
            List<LocalDate> n50 = cal.findLastNTradeDays(td, MAX_N);
            allQuoteDates.addAll(n50);
        }

        log.accept("  共需查询 " + allQuoteDates.size() + " 个日历日的行情数据");

        // 5. 一次性查询全部行情
        Map<String, TreeMap<String, QuoteRow>> quoteMap = loadQuotes(allQuoteDates);

        // 5.1 排除 BOND / CASH ETF（无需计算 RPS）
        int excluded = filterExcludedCodes(quoteMap);
        if (excluded > 0) {
            log.accept("  已排除 BOND/CASH ETF " + excluded + " 只（无需计算 RPS）");
        }

        // 6. 逐日计算
        int processed = 0;
        int totalRows = 0;
        long t0 = System.currentTimeMillis();

        for (LocalDate td : targetDates) {
            String tdStr = td.format(YYYYMMDD);

            // 找该日的 50 个交易日
            List<LocalDate> n50TradeDays = cal.findLastNTradeDays(td, MAX_N);
            if (n50TradeDays.size() < MAX_N) {
                log.accept("  [跳过] " + tdStr + "  前50个交易日不足 " + n50TradeDays.size() + " 天");
                continue;
            }

            // 计算
            List<RpsRow> rows = calculateForDate(tdStr, n50TradeDays, quoteMap);
            if (rows.isEmpty()) {
                log.accept("  [跳过] " + tdStr + "  无符合条件 ETF");
                continue;
            }

            // 保存
            saveRpsForDate(tdStr, rows);
            conn.commit();
            totalRows += rows.size();

            processed++;
            if (processed % 5 == 0 || processed == targetDates.size()) {
                log.accept("  [进度] " + processed + "/" + targetDates.size()
                        + "  " + tdStr + "  (" + rows.size() + " 只 ETF)");
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        log.accept("  完成 " + processed + " 个交易日, " + totalRows + " 条记录, 耗时 "
                + (elapsed >= 1000 ? String.format("%.1fs", elapsed / 1000.0) : elapsed + "ms"));

        // 7. 显示剩余天数
        if (lastRpsDateStr != null && !targetDates.isEmpty()) {
            // 检查是否还有未计算的
            LocalDate lastDone = targetDates.get(targetDates.size() - 1);
            List<LocalDate> remaining = cal.nextNTradeDays(lastDone, 1);
            if (!remaining.isEmpty() && !remaining.get(0).isAfter(latestTradeDate)) {
                // 只扫描到 latestTradeDate，避免无限查询
                List<LocalDate> allRemaining = cal.nextNTradeDays(lastDone, 366);
                int remainCount = 0;
                for (LocalDate d : allRemaining) {
                    if (!d.isAfter(latestTradeDate)) remainCount++;
                    else break;
                }
                if (remainCount > 0) {
                    log.accept("  ⏳ 还剩 " + remainCount + " 个交易日待计算，请再次点击按钮继续");
                }
            }
        }

        return processed;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 行情读取
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 批量加载行情数据。
     *
     * @return Map<stockCode, TreeMap<tradeDateStr, QuoteRow>>（每个股票的交易日内已按日期升序排列）
     */
    private Map<String, TreeMap<String, QuoteRow>> loadQuotes(Set<LocalDate> dates) throws SQLException {
        Map<String, TreeMap<String, QuoteRow>> result = new HashMap<>();

        // 构建 IN 子句并查询
        List<String> dateStrs = dates.stream().map(d -> d.format(YYYYMMDD)).toList();

        // 由于 JDBC 不支持直接 IN 与 List，改用批量 OR 或临时表。
        // 这里使用最稳妥的方式：一次性查出这些日期附近的数据（数量可控）
        if (dateStrs.isEmpty()) return result;

        // 构造一个范围查询来避免过长的 IN 列表
        String minDate = Collections.min(dateStrs);
        String maxDate = Collections.max(dateStrs);

        String sql = "SELECT trade_date, stock_code, close_price, change_pct " +
                     "FROM ETF_DAILY_QUOTE " +
                     "WHERE trade_date BETWEEN ? AND ? " +
                     "ORDER BY stock_code, trade_date";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, minDate);
            ps.setString(2, maxDate);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String dateStr = rs.getString("trade_date");
                    // 只保留需要的日期
                    if (!dateStrs.contains(dateStr)) continue;

                    String code = rs.getString("stock_code");
                    double close = rs.getBigDecimal("close_price") != null
                            ? rs.getBigDecimal("close_price").doubleValue() : 0;
                    double chgPct = rs.getBigDecimal("change_pct") != null
                            ? rs.getBigDecimal("change_pct").doubleValue() : 0;

                    result.computeIfAbsent(code, k -> new TreeMap<>())
                          .put(dateStr, new QuoteRow(close, chgPct));
                }
            }
        }
        return result;
    }

    /**
     * 从 quoteMap 中移除 BOND / CASH ETF。
     *
     * @return 被排除的 ETF 数量
     */
    private int filterExcludedCodes(Map<String, TreeMap<String, QuoteRow>> quoteMap) throws SQLException {
        Set<String> excluded = loadExcludedCodeSet();
        int count = 0;
        for (String code : excluded) {
            if (quoteMap.remove(code) != null) {
                count++;
            }
        }
        return count;
    }

    /** 加载 ETF_PRODUCT 中 etf_type 为 BOND 或 CASH 的 stock_code */
    private Set<String> loadExcludedCodeSet() throws SQLException {
        Set<String> result = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(GET_EXCLUDED_CODES_SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(rs.getString("stock_code"));
            }
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 单日 RPS 计算
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 对单个目标日期计算全部 N 日 RPS。
     *
     * @param tdStr       目标交易日（yyyMMdd 字符串）
     * @param n50Days     截至 tdStr 的 50 个交易日列表（升序，index 0 最早，index 49=tdStr）
     * @param quoteMap    全部行情数据
     * @return 每只 ETF 一条 RPS 记录
     */
    private List<RpsRow> calculateForDate(String tdStr,
                                          List<LocalDate> n50Days,
                                          Map<String, TreeMap<String, QuoteRow>> quoteMap) {
        // 50 个交易日的日期字符串列表（升序）
        List<String> day50Strs = n50Days.stream().map(d -> d.format(YYYYMMDD)).toList();

        // ── 对每只 ETF 计算各 N 日涨幅 ──
        // stockGains: stockCode -> double[5] (rps5/rps10/rps15/rps20/rps50 对应的 N 日涨幅)
        // 若某 N 不满足条件则对应值为 NaN
        Map<String, double[]> stockGains = new HashMap<>();

        for (Map.Entry<String, TreeMap<String, QuoteRow>> entry : quoteMap.entrySet()) {
            String code = entry.getKey();
            TreeMap<String, QuoteRow> quotes = entry.getValue();

            double[] gains = new double[N_DAYS.length];

            for (int ni = 0; ni < N_DAYS.length; ni++) {
                int n = N_DAYS[ni];
                int startIdx = MAX_N - n;  // 最早那天的 index（0-based）
                int endIdx = MAX_N - 1;     // 最晚那天 = tdStr

                String earliestDayStr = day50Strs.get(startIdx);
                String latestDayStr   = day50Strs.get(endIdx);

                QuoteRow earliest = quotes.get(earliestDayStr);
                QuoteRow latest   = quotes.get(latestDayStr);

                if (earliest == null || latest == null) {
                    gains[ni] = Double.NaN;  // 不足 N 条
                    continue;
                }

                // 向前推导: prev_close = earliest.close / (1 + changePct/100)
                double prevClose = earliest.close / (1.0 + earliest.changePct / 100.0);
                if (prevClose <= 0) {
                    gains[ni] = Double.NaN;
                    continue;
                }

                // N 日涨幅 = (latest.close / prevClose - 1) * 100
                gains[ni] = (latest.close / prevClose - 1.0) * 100.0;
            }

            stockGains.put(code, gains);
        }

        // ── 对每个 N 分别排名 ──
        // rpsByStock: code -> double[5] (最终 RPS 值)
        Map<String, double[]> rpsByStock = new HashMap<>();
        for (String code : stockGains.keySet()) {
            rpsByStock.put(code, new double[N_DAYS.length]);
        }

        for (int ni = 0; ni < N_DAYS.length; ni++) {
            // 收集该 N 下所有有效的 (code, gain)
            List<Map.Entry<String, Double>> ranked = new ArrayList<>();
            for (Map.Entry<String, double[]> e : stockGains.entrySet()) {
                double g = e.getValue()[ni];
                if (!Double.isNaN(g)) {
                    ranked.add(new AbstractMap.SimpleEntry<>(e.getKey(), g));
                }
            }

            // 按涨幅降序排列
            ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

            int m = ranked.size();
            if (m == 0) continue;

            for (int ri = 0; ri < m; ri++) {
                String code = ranked.get(ri).getKey();
                double score;
                if (m == 1) {
                    score = 50.0;  // 唯一一只 ETF，给中间值
                } else {
                    // 0-1000 归一化: rank 0 (最高涨幅) → 1000, rank m-1 (最低) → 0
                    score = (double) (m - 1 - ri) / (m - 1) * 1000.0;
                }
                double rps = score / 10.0;  // 转为 0-100
                // 保留 1 位小数
                rps = Math.round(rps * 10.0) / 10.0;
                rpsByStock.get(code)[ni] = rps;
            }
        }

        // ── 组装结果 ──
        List<RpsRow> rows = new ArrayList<>();
        for (Map.Entry<String, double[]> e : rpsByStock.entrySet()) {
            String code = e.getKey();
            double[] rpsVals = e.getValue();
            rows.add(new RpsRow(tdStr, code,
                    rpsVals[0], rpsVals[1], rpsVals[2], rpsVals[3], rpsVals[4]));
        }
        return rows;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 数据库写操作
    // ═══════════════════════════════════════════════════════════════════

    private void ensureTable() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(ENSURE_TABLE_DDL);
            conn.commit();
        } catch (SQLException e) {
            // Oracle: ORA-00955 = name already used; ORA-00001 = unique constraint violated
            if (e.getErrorCode() != 955 && e.getErrorCode() != 1) throw e;
        }
    }

    /** 获取 ETF_DAILY_RPS 中最大的 trade_date */
    private String getLatestRpsDate() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(GET_MAX_DATE_SQL);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getString(1);
            }
        }
        return null;
    }

    /** 批量保存一个交易日的 RPS 数据 */
    private void saveRpsForDate(String tradeDate, List<RpsRow> rows) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(MERGE_RPS_SQL)) {
            int batchCnt = 0;
            for (RpsRow row : rows) {
                ps.setString(1, tradeDate);
                ps.setString(2, row.stockCode);
                ps.setBigDecimal(3, bd(row.rps5));
                ps.setBigDecimal(4, bd(row.rps10));
                ps.setBigDecimal(5, bd(row.rps15));
                ps.setBigDecimal(6, bd(row.rps20));
                ps.setBigDecimal(7, bd(row.rps50));
                ps.addBatch();

                if (++batchCnt % 200 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // DTO
    // ═══════════════════════════════════════════════════════════════════

    /** 行情快照：某一天某只 ETF 的收盘价与涨幅 */
    private record QuoteRow(double close, double changePct) {}

    /** 单只 ETF 的 RPS 计算结果 */
    record RpsRow(String tradeDate, String stockCode,
                  double rps5, double rps10, double rps15, double rps20, double rps50) {}

    // ═══════════════════════════════════════════════════════════════════
    // 工具
    // ═══════════════════════════════════════════════════════════════════

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(Math.round(v * 100.0) / 100.0);
    }
}
