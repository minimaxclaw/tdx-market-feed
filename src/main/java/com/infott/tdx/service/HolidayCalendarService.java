package com.infott.tdx.service;

import com.infott.tdx.model.CalendarRow;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.sql.*;
import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 假期日历服务：从网页抓取公众假期数据并写入 tdx_calendar 表。
 *
 * 数据来源：https://publicholidays.cn  ——  页面包含一个 &lt;table class="publicholidays phgtable"&gt;
 * 每一行格式：&lt;td&gt;M月D日 ～ M月D日&lt;/td&gt;  &lt;td&gt;周X ～ 周X&lt;/td&gt;  &lt;td&gt;假期名称&lt;/td&gt;
 */
public class HolidayCalendarService {

    private static final String BASE_URL      = "https://publicholidays.cn/zh/%d/";
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 星期中文名，Java DayOfWeek Monday=1 → 星期一 */
    private static final String[] WEEKDAYS_CN =
            {"", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};

    private static final String COUNT_YEAR_SQL =
            "SELECT COUNT(*) FROM tdx_calendar WHERE event_date LIKE ? AND market = 'CN'";

    private static final String INSERT_SQL =
            "INSERT INTO tdx_calendar (event_date, market, weekday, holiday_name) VALUES (?, 'CN', ?, ?)";

    private static final String QUERY_YEAR_SQL =
            "SELECT event_date, weekday, holiday_name FROM tdx_calendar " +
            "WHERE event_date LIKE ? AND market = 'CN' ORDER BY event_date DESC";

    private static final String EXISTS_DATE_SQL =
            "SELECT COUNT(*) FROM tdx_calendar WHERE event_date = ? AND market = 'CN'";

    private static final String NON_TRADE_DAYS_RANGE_SQL =
            "SELECT event_date FROM tdx_calendar WHERE event_date <= ? AND event_date >= ? AND market = 'CN'";

    /** 日期范围： "1月1日"、"1月1日 ～ 1月3日" */
    private static final Pattern RANGE_PATTERN =
            Pattern.compile("(\\d{1,2})月(\\d{1,2})日\\s*[～~]\\s*(\\d{1,2})月(\\d{1,2})日");

    private static final Pattern SINGLE_PATTERN =
            Pattern.compile("^(\\d{1,2})月(\\d{1,2})日$");

    private final Consumer<String> log;

    public HolidayCalendarService(Consumer<String> log) {
        this.log = log;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 网页抓取
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 从 publicholidays.cn 抓取指定年份的公众假期。
     *
     * @return date → holiday_name（仅公众假期日）
     */
    public Map<LocalDate, String> fetchHolidays(int year) throws Exception {
        String url = String.format(BASE_URL, year);
        log.accept("  抓取网页: " + url);

        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                           "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                .timeout(30_000)
                .get();

        // 取第一个假期表格
        Element table = doc.select("table.publicholidays.phgtable").first();
        if (table == null) {
            throw new RuntimeException("未找到假期数据表格，页面结构可能已变化");
        }

        Map<LocalDate, String> holidays = new LinkedHashMap<>();
        Elements rows = table.select("tbody tr");

        for (Element row : rows) {
            Elements tds = row.select("td");
            if (tds.size() < 3) continue;

            String dateRangeStr = tds.get(0).text().trim();
            String holidayCell  = tds.get(2).text().trim();

            // 跳过 “查看 gov.cn” 、观测日（带 *）及空行
            if (holidayCell.contains("查看") || holidayCell.contains("*") || holidayCell.isEmpty()) {
                continue;
            }

            // 提取假期名称（优先 .summary）
            Element summaryEl = tds.get(2).selectFirst(".summary");
            String holidayName = (summaryEl != null) ? summaryEl.text().trim() : holidayCell;

            // 解析日期范围
            Matcher rangeM = RANGE_PATTERN.matcher(dateRangeStr);
            if (rangeM.find()) {
                int sm = Integer.parseInt(rangeM.group(1));
                int sd = Integer.parseInt(rangeM.group(2));
                int em = Integer.parseInt(rangeM.group(3));
                int ed = Integer.parseInt(rangeM.group(4));

                // 跨年处理：开始月份 > 结束月份（如元旦 12月31日 ～ 1月2日 → 上月属于 year-1）
                int startYear = (sm > em) ? year - 1 : year;
                int endYear   = (sm > em) ? year     : year;

                LocalDate startDate = LocalDate.of(startYear, sm, sd);
                LocalDate endDate   = LocalDate.of(endYear,   em, ed);

                // 只保留在目标年份内的日期
                LocalDate d = startDate;
                while (!d.isAfter(endDate)) {
                    if (d.getYear() == year) {
                        holidays.put(d, holidayName);
                    }
                    d = d.plusDays(1);
                }
            } else {
                Matcher singleM = SINGLE_PATTERN.matcher(dateRangeStr);
                if (singleM.find()) {
                    int m = Integer.parseInt(singleM.group(1));
                    int d = Integer.parseInt(singleM.group(2));
                    holidays.put(LocalDate.of(year, m, d), holidayName);
                }
            }
        }

        log.accept("  解析到 " + holidays.size() + " 天假期");
        return holidays;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 数据库操作
    // ═══════════════════════════════════════════════════════════════════

    private Connection getConnection() throws SQLException {
        DbConfig cfg = DbConfig.load();
        return DriverManager.getConnection(cfg.url(), cfg.user(), cfg.password());
    }

    /** 检查 tdx_calendar 中是否已有该年份的数据 */
    public boolean yearExists(int year) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(COUNT_YEAR_SQL)) {
            ps.setString(1, year + "%");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /**
     * 将假期 + 周末写入 tdx_calendar（普通工作日不插入）。
     *
     * @param year       年份
     * @param holidayMap 公众假期映射（date → holiday_name）
     * @return 写入的记录数
     */
    public int insertYearData(int year, Map<LocalDate, String> holidayMap) throws SQLException {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            LocalDate start = LocalDate.of(year, 1, 1);
            LocalDate end   = LocalDate.of(year, 12, 31);
            int count = 0;
            int batchCnt = 0;

            try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
                LocalDate d = start;
                while (!d.isAfter(end)) {
                    int dow = d.getDayOfWeek().getValue();  // 1=Mon ... 7=Sun
                    boolean isWeekend = (dow == 6 || dow == 7);  // 周六/周日
                    String holidayVal = holidayMap.get(d);
                    boolean isHoliday = (holidayVal != null);

                    // 仅插入假期 + 周末，跳过普通工作日
                    if (!isHoliday && !isWeekend) {
                        d = d.plusDays(1);
                        continue;
                    }

                    String dateStr = d.format(YYYYMMDD);
                    String weekday = WEEKDAYS_CN[dow];

                    ps.setString(1, dateStr);
                    ps.setString(2, weekday);
                    if (isHoliday) {
                        ps.setString(3, holidayVal);
                    } else {
                        ps.setNull(3, Types.VARCHAR);
                    }
                    ps.addBatch();

                    count++;
                    batchCnt++;
                    if (batchCnt % 100 == 0) {
                        ps.executeBatch();
                    }
                    d = d.plusDays(1);
                }
                ps.executeBatch();   // 尾部不足 100 的批次
            }

            conn.commit();
            int holidayDays = holidayMap.size();
            int weekends = count - holidayDays;
            log.accept("  已写入 tdx_calendar " + count + " 条（假期 " + holidayDays
                    + " 天 + 周末 " + weekends + " 天）");
            return count;
        }
    }

    /** 查询某年的日历数据，按日期倒序 */
    public List<CalendarRow> queryYear(int year) throws SQLException {
        List<CalendarRow> rows = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(QUERY_YEAR_SQL)) {
            ps.setString(1, year + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String hn = rs.getString(3);
                    rows.add(new CalendarRow(
                            rs.getString(1),           // event_date
                            rs.getString(2),           // weekday
                            hn != null ? hn : ""));    // holiday_name
                }
            }
        }
        return rows;
    }

    /** 返回 ComboBox 里要显示的年份列表（从 2015 到今年，倒序） */
    public static List<Integer> yearRange() {
        List<Integer> years = new ArrayList<>();
        int thisYear = Year.now().getValue();
        for (int y = thisYear; y >= 2015; y--) {
            years.add(y);
        }
        return years;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 交易日判断（不在 tdx_calendar 表中的日期即为交易日）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 判断指定日期是否为交易日（不在 tdx_calendar 表中即为交易日）。
     */
    public boolean isTradeDay(LocalDate date) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(EXISTS_DATE_SQL)) {
            ps.setString(1, date.format(YYYYMMDD));
            try (ResultSet rs = ps.executeQuery()) {
                return !(rs.next() && rs.getInt(1) > 0);
            }
        }
    }

    /**
     * 找到指定日期之前（含）的最近一个交易日。
     * 向前最多查找 30 天，若超出范围仍未找到则返回原日期。
     */
    public LocalDate findLastTradeDay(LocalDate date) throws SQLException {
        LocalDate start = date.minusDays(30);
        Set<String> nonTradeDays = new HashSet<>();

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(NON_TRADE_DAYS_RANGE_SQL)) {
            ps.setString(1, date.format(YYYYMMDD));
            ps.setString(2, start.format(YYYYMMDD));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    nonTradeDays.add(rs.getString(1));
                }
            }
        }

        // 从 date 向前找第一个不在非交易日集合中的日期
        LocalDate d = date;
        while (!d.isBefore(start)) {
            if (!nonTradeDays.contains(d.format(YYYYMMDD))) {
                return d;
            }
            d = d.minusDays(1);
        }

        // 30 天内全是非交易日（极端情况），返回原日期
        return date;
    }
}
