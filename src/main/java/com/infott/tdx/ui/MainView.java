package com.infott.tdx.ui;

import com.infott.tdx.model.AdjustedBar;
import com.infott.tdx.model.CalendarRow;
import com.infott.tdx.model.DayBar;
import com.infott.tdx.model.Market;
import com.infott.tdx.model.XdxRecord;
import com.infott.tdx.rule.EtfCodeRule;
import com.infott.tdx.service.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 主界面视图
 *
 * Tab 1「刷新」：数据导出 + Oracle 入库
 * Tab 2「三线红」：（预留）
 * Tab 3「三线白」：（预留）
 */
public class MainView {

    private static final String DEFAULT_TDX_ROOT   = "D:\\swdtool\\new_tdx";
    private static final String DEFAULT_EXPORT_DIR  = "D:\\swdcode\\app_tdx\\etf_csv";

    private TextField tfTdxRoot;
    private TextField tfExportDir;
    private TextArea  taLog;
    private Button    btnExport;

    public void show(Stage stage) {
        stage.setTitle("通达信 ETF 数据工具 v1.0");

        TabPane tabPane = new TabPane();

        // ── Tab 1：刷新 ────────────────────────────────────────────────
        Tab refreshTab = new Tab("刷新");
        refreshTab.setClosable(false);
        refreshTab.setContent(buildRefreshPane(stage));

        // ── Tab 2：三线红 ──────────────────────────────────────────────
        Tab redTab = new Tab("三线红");
        redTab.setClosable(false);
        redTab.setContent(buildPlaceholder("三线红 — 功能开发中"));

        // ── Tab 3：三线白 ──────────────────────────────────────────────
        Tab whiteTab = new Tab("三线白");
        whiteTab.setClosable(false);
        whiteTab.setContent(buildPlaceholder("三线白 — 功能开发中"));

        // ── Tab 4：假期 ──────────────────────────────────────────────
        Tab holidayTab = new Tab("假期");
        holidayTab.setClosable(false);
        holidayTab.setContent(buildHolidayPane(stage));

        tabPane.getTabs().addAll(refreshTab, redTab, whiteTab, holidayTab);

        Scene scene = new Scene(tabPane, 820, 580);
        stage.setScene(scene);
        stage.setMinWidth(600);
        stage.setMinHeight(400);
        stage.show();
    }

    /** 构建「刷新」Tab 的内容 */
    private VBox buildRefreshPane(Stage stage) {

        // ── 行1：通达信目录 ──────────────────────────────────────────
        Label lblTdx = new Label("通达信目录：");
        lblTdx.setMinWidth(80);
        tfTdxRoot = new TextField(DEFAULT_TDX_ROOT);
        HBox.setHgrow(tfTdxRoot, Priority.ALWAYS);
        Button btnBrowseTdx = new Button("浏览...");
        btnBrowseTdx.setOnAction(e -> browseDir(stage, tfTdxRoot));

        HBox row1 = new HBox(8, lblTdx, tfTdxRoot, btnBrowseTdx);
        row1.setAlignment(Pos.CENTER_LEFT);

        // ── 行2：导出目录 ─────────────────────────────────────────────
        Label lblExport = new Label("导出目录：");
        lblExport.setMinWidth(80);
        tfExportDir = new TextField(DEFAULT_EXPORT_DIR);
        HBox.setHgrow(tfExportDir, Priority.ALWAYS);
        Button btnBrowseExport = new Button("浏览...");
        btnBrowseExport.setOnAction(e -> browseDir(stage, tfExportDir));

        HBox row2 = new HBox(8, lblExport, tfExportDir, btnBrowseExport);
        row2.setAlignment(Pos.CENTER_LEFT);

        // ── 行3：按钮栏 ────────────────────────────────────────────────
        btnExport = new Button("刷新数据");
        btnExport.setStyle("-fx-font-size: 14px;");
        btnExport.setPrefWidth(100);
        btnExport.setDefaultButton(true);
        btnExport.setOnAction(e -> startExport());

        Button btnCalcRps = new Button("计算RPS");
        btnCalcRps.setStyle("-fx-font-size: 14px;");
        btnCalcRps.setOnAction(e -> appendLog("[信息] 计算RPS 功能开发中"));

        Button btnClearLog = new Button("清除日志");
        btnClearLog.setStyle("-fx-font-size: 14px;");
        btnClearLog.setOnAction(e -> taLog.clear());

        HBox row3 = new HBox(10, btnExport, btnCalcRps, btnClearLog);
        row3.setAlignment(Pos.CENTER_LEFT);
        row3.setPadding(new Insets(0, 0, 0, 88));

        // ── 行4：日志区域 ─────────────────────────────────────────────
        Label lblLog = new Label("运行日志：");
        taLog = new TextArea();
        taLog.setEditable(false);
        taLog.setWrapText(true);
        taLog.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px;");
        VBox.setVgrow(taLog, Priority.ALWAYS);

        VBox pane = new VBox(10, row1, row2, row3, lblLog, taLog);
        pane.setPadding(new Insets(14));
        return pane;
    }

    /** 构建占位 Tab 内容 */
    private VBox buildPlaceholder(String label) {
        VBox pane = new VBox(new Label(label));
        pane.setPadding(new Insets(14));
        pane.setAlignment(Pos.TOP_LEFT);
        return pane;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 「假期」Tab
    // ─────────────────────────────────────────────────────────────────────

    /** 构建「假期」Tab 的内容 */
    private VBox buildHolidayPane(Stage stage) {

        HolidayCalendarService holidayService = new HolidayCalendarService(this::appendLog);

        // ── 行1：年份输入 + 更新按钮 ──────────────────────────────────
        Label lblYear = new Label("年份：");
        lblYear.setMinWidth(80);

        TextField tfYear = new TextField(String.valueOf(Year.now().getValue()));
        tfYear.setPrefWidth(80);

        Button btnFetch = new Button("更新假期数据");
        btnFetch.setStyle("-fx-font-size: 14px;");

        HBox row1 = new HBox(8, lblYear, tfYear, btnFetch);
        row1.setAlignment(Pos.CENTER_LEFT);

        // ── 行2：查询下拉 ────────────────────────────────────────────
        Label lblQuery = new Label("查询：");
        lblQuery.setMinWidth(80);

        ComboBox<Integer> cbYear = new ComboBox<>();
        cbYear.setItems(FXCollections.observableArrayList(HolidayCalendarService.yearRange()));
        cbYear.setValue(Year.now().getValue());

        HBox row2 = new HBox(8, lblQuery, cbYear);
        row2.setAlignment(Pos.CENTER_LEFT);

        // ── 行3：结果表格 ─────────────────────────────────────────────
        TableView<CalendarRow> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<CalendarRow, String> colDate = new TableColumn<>("日期");
        colDate.setCellValueFactory(new PropertyValueFactory<>("eventDate"));
        colDate.setPrefWidth(120);

        TableColumn<CalendarRow, String> colWeekday = new TableColumn<>("星期");
        colWeekday.setCellValueFactory(new PropertyValueFactory<>("weekday"));
        colWeekday.setPrefWidth(100);

        TableColumn<CalendarRow, String> colHoliday = new TableColumn<>("假期");
        colHoliday.setCellValueFactory(new PropertyValueFactory<>("holidayName"));
        colHoliday.setPrefWidth(200);

        table.getColumns().addAll(colDate, colWeekday, colHoliday);
        VBox.setVgrow(table, Priority.ALWAYS);

        // ── 事件：更新假期数据 ────────────────────────────────────────
        btnFetch.setOnAction(e -> {
            String yearText = tfYear.getText().trim();
            int year;
            try {
                year = Integer.parseInt(yearText);
            } catch (NumberFormatException ex) {
                showAlert(Alert.AlertType.ERROR, "错误", "请输入有效的年份数字");
                return;
            }

            btnFetch.setDisable(true);
            appendLog("[信息] 开始更新 " + year + " 年假期数据...");

            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    try {
                        if (holidayService.yearExists(year)) {
                            Platform.runLater(() -> showAlert(Alert.AlertType.INFORMATION,
                                    "提示", year + " 年数据已存在，无需重复导入"));
                            return null;
                        }

                        Map<LocalDate, String> holidays = holidayService.fetchHolidays(year);
                        int rows = holidayService.insertYearData(year, holidays);

                        Platform.runLater(() -> {
                            showAlert(Alert.AlertType.INFORMATION, "成功",
                                    "成功导入 " + year + " 年数据\n共 " + rows + " 条记录（"
                                    + holidays.size() + " 天假期）");
                            // 若 ComboBox 当前选中该年份，刷新表格
                            if (cbYear.getValue() != null && cbYear.getValue() == year) {
                                reloadTable(holidayService, year, table);
                            }
                        });

                    } catch (Exception ex) {
                        Platform.runLater(() -> showAlert(Alert.AlertType.ERROR,
                                "失败", "更新失败: " + ex.getMessage()));
                        appendLog("[错误] " + year + " 年假期数据更新失败: " + ex.getMessage());
                    }
                    return null;
                }
            };

            task.setOnSucceeded(e2 -> btnFetch.setDisable(false));
            task.setOnFailed(e2 -> btnFetch.setDisable(false));

            Thread thread = new Thread(task, "holiday-fetch-thread");
            thread.setDaemon(true);
            thread.start();
        });

        // ── 事件：ComboBox 切换年份 → 查询 DB ────────────────────────
        cbYear.setOnAction(e -> {
            int selectedYear = cbYear.getValue();
            reloadTable(holidayService, selectedYear, table);
        });

        // 初始加载（当前年份）
        reloadTable(holidayService, Year.now().getValue(), table);

        VBox pane = new VBox(10, row1, row2, table);
        pane.setPadding(new Insets(14));
        return pane;
    }

    /** 后台查询 tdx_calendar 并刷新 TableView */
    private void reloadTable(HolidayCalendarService service, int year, TableView<CalendarRow> table) {
        Task<List<CalendarRow>> queryTask = new Task<>() {
            @Override
            protected List<CalendarRow> call() throws Exception {
                return service.queryYear(year);
            }
        };
        queryTask.setOnSucceeded(e -> {
            List<CalendarRow> rows = queryTask.getValue();
            table.setItems(FXCollections.observableArrayList(rows));
            if (rows.isEmpty()) {
                appendLog("[信息] " + year + " 年暂无数据，请先点击「更新假期数据」");
            }
        });
        queryTask.setOnFailed(e ->
                appendLog("[错误] 查询 " + year + " 年数据失败"));

        Thread thread = new Thread(queryTask, "holiday-query-thread");
        thread.setDaemon(true);
        thread.start();
    }

    /** 弹出提示/错误对话框 */
    private static void showAlert(Alert.AlertType type, String title, String msg) {
        Platform.runLater(() -> {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(msg);
            alert.showAndWait();
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 目录选择
    // ─────────────────────────────────────────────────────────────────────

    private void browseDir(Stage owner, TextField target) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择目录");
        File init = new File(target.getText().trim());
        if (init.exists()) chooser.setInitialDirectory(init);
        File chosen = chooser.showDialog(owner);
        if (chosen != null) target.setText(chosen.getAbsolutePath());
    }

    // ─────────────────────────────────────────────────────────────────────
    // 导出入口（在后台线程执行，实时更新日志）
    // ─────────────────────────────────────────────────────────────────────

    private void startExport() {
        String tdxRootPath  = tfTdxRoot.getText().trim();
        String exportDirPath = tfExportDir.getText().trim();

        if (tdxRootPath.isEmpty() || exportDirPath.isEmpty()) {
            appendLog("[错误] 通达信目录和导出目录不能为空");
            return;
        }

        btnExport.setDisable(true);
        taLog.clear();
        appendLog("▶ 开始导出...");
        appendLog("  TDX根目录 = " + tdxRootPath);
        appendLog("  导出目录  = " + exportDirPath);
        appendLog("─".repeat(60));

        Task<Void> task = createExportTask(tdxRootPath, exportDirPath);

        task.setOnSucceeded(e -> {
            btnExport.setDisable(false);
            appendLog("─".repeat(60));
            appendLog("✅ 全部完成");
        });
        task.setOnFailed(e -> {
            btnExport.setDisable(false);
            Throwable ex = task.getException();
            appendLog("❌ 任务异常: " + (ex != null ? ex.getMessage() : "未知错误"));
            if (ex != null) ex.printStackTrace();
        });

        Thread thread = new Thread(task, "etf-export-thread");
        thread.setDaemon(true);
        thread.start();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 后台任务
    // ─────────────────────────────────────────────────────────────────────

    private Task<Void> createExportTask(String tdxRootPath, String exportDirPath) {
        return new Task<>() {
            @Override
            protected Void call() throws Exception {
                Instant start = Instant.now();

                File tdxRoot   = new File(tdxRootPath);
                File exportDir = new File(exportDirPath);

                // 初始化各服务
                EtfCodeRule     rule      = new EtfCodeRule();
                EtfScanner      scanner   = new EtfScanner(rule);
                TdxDataReader   reader    = new TdxDataReader();
                XdxReader       xdxReader = new XdxReader();
                QfqCalculator   qfq       = new QfqCalculator();
                CsvExporter     exporter  = new CsvExporter();
                StockNameReader nameReader = new StockNameReader();

                // 加载全局权息库（gbbq），作为 .xdx 缺失时的后备
                GbbqReader gbbqReader = null;
                try {
                    gbbqReader = new GbbqReader(tdxRoot);
                    appendLog("权息库：从 gbbq 加载 " + gbbqReader.stockCount() + " 只股票");
                } catch (Exception e) {
                    appendLog("[信息] 未找到 gbbq 文件，仅尝试读取 .xdx/.qfq");
                }

                // 加载名称库
                Map<String, String> shNames = nameReader.readNames(tdxRoot, "sh");
                Map<String, String> szNames = nameReader.readNames(tdxRoot, "sz");
                appendLog("名称库：SH=" + shNames.size() + " 条，SZ=" + szNames.size() + " 条");

                // 扫描 ETF
                List<EtfScanner.EtfEntry> etfs = scanner.scan(tdxRoot);
                appendLog("扫描到 ETF 数量：" + etfs.size());

                if (etfs.isEmpty()) {
                    appendLog("[警告] 未找到任何 ETF 文件，请检查通达信目录是否正确");
                    return null;
                }

                int success = 0, fail = 0, skipEmpty = 0;
                List<String> failDetails = new ArrayList<>();

                // 记录成功导出的 ETF（内存传递，避免 CSV 重读）
                List<ExportData> exportDataList = new ArrayList<>();

                for (EtfScanner.EtfEntry entry : etfs) {
                    Market market = entry.market();
                    String code   = entry.code();
                    File   dayFile = entry.dayFile();

                    // 名称解析（找不到时用代码代替 — 退市ETF）
                    Map<String, String> nameMap = (market == Market.SH) ? shNames : szNames;
                    String name = nameMap.getOrDefault(code, code);
                    String status = nameMap.containsKey(code) ? "A" : "D";

                    try {
                        // 读取原始日线
                        List<DayBar> bars = reader.readDayFile(dayFile);
                        if (bars.isEmpty()) {
                            skipEmpty++;
                            continue;
                        }

                        // 读取权息数据（优先 .xdx/.qfq，其次 gbbq）
                        File   ldayDir    = dayFile.getParentFile();
                        String filePrefix = entry.filePrefix();
                        List<XdxRecord> xdxRecords = xdxReader.readXdx(ldayDir, filePrefix);

                        if (xdxRecords.isEmpty() && gbbqReader != null) {
                            xdxRecords = gbbqReader.getRecords(market, code);
                        }

                        // 计算前复权
                        List<AdjustedBar> adjBars = qfq.calculate(bars, xdxRecords);

                        // 导出 CSV
                        String csvName = filePrefix + ".csv";
                        File   csvFile = new File(exportDir, csvName);

                        exporter.exportAll(csvFile, market, code, name, adjBars);

                        String xdxInfo = xdxRecords.isEmpty() ? "" : " [权息" + xdxRecords.size() + "条]";
                        appendLog("[OK] " + market.getCode() + code
                                + " " + name
                                + " → " + csvName + xdxInfo);
                        exportDataList.add(new ExportData(market.getCode(), code, name, adjBars,
                                OracleDbWriter.hashXdxRecords(xdxRecords), status));
                        success++;

                    } catch (Exception ex) {
                        String reason = ex.getMessage();
                        if (reason == null || reason.isEmpty()) {
                            reason = ex.getClass().getSimpleName();
                        }
                        String marketCode = market.getCode() + code;
                        appendLog("[FAIL] " + marketCode + " " + name + "  " + reason);
                        failDetails.add(marketCode + " " + name + " — " + reason);
                        fail++;
                    }
                }

                long elapsedSec = Duration.between(start, Instant.now()).toSeconds();
                appendLog("─".repeat(60));
                appendLog(String.format("统计：成功=%d  失败=%d  空文件跳过=%d  总计=%d  耗时=%ds",
                        success, fail, skipEmpty, etfs.size(), elapsedSec));

                if (!failDetails.isEmpty()) {
                    appendLog("─".repeat(60));
                    appendLog("❌ 处理失败的 ETF：");
                    for (String detail : failDetails) {
                        appendLog("  · " + detail);
                    }
                }

                // ── 数据时效性验证 ──────────────────────────────────
                boolean dataFresh = true;
                if (!exportDataList.isEmpty()) {
                    dataFresh = validateDataFreshness(exportDataList);
                }

                // ── 导入 Oracle 数据库 ──────────────────────────────────
                if (!exportDataList.isEmpty() && dataFresh) {
                    long csvTotalRows = 0;
                    for (ExportData d : exportDataList) {
                        csvTotalRows += d.adjBars.size();
                    }
                    appendLog("─".repeat(60));
                    appendLog("CSV 导出：合计 " + csvTotalRows + " 条  ETF " + exportDataList.size() + " 只");

                    importToDatabase(exportDataList, csvTotalRows);
                } else if (!exportDataList.isEmpty()) {
                    appendLog("─".repeat(60));
                    appendLog("⛔ 已跳过 Oracle 数据库更新（数据未刷新）");
                }

                return null;
            }
        };
    }

    // ─────────────────────────────────────────────────────────────────────
    // 导入 Oracle 数据库
    // ─────────────────────────────────────────────────────────────────────

    private void importToDatabase(List<ExportData> dataList, long csvTotalRows) {
        appendLog("▶ 开始导入 Oracle 数据库...");

        long dbStart = System.currentTimeMillis();
        try {
            OracleDbWriter writer = new OracleDbWriter(DbConfig.load(), this::appendLog);

            List<OracleDbWriter.ImportItem> items = new ArrayList<>();
            for (ExportData d : dataList) {
                items.add(new OracleDbWriter.ImportItem(
                        d.marketCode, d.code, d.name, d.adjBars, d.gbbqHash, d.status));
            }

            writer.importAll(items);
            OracleDbWriter.Stats s = writer.snapshot();

            // —— CSV vs Oracle 记录数校验 ——
            int dbRows = writer.countDbRows();
            appendLog("─".repeat(60));
            appendLog("📊 数据校验：CSV=" + csvTotalRows + " 条  Oracle=" + dbRows + " 条");

            if (csvTotalRows != dbRows) {
                appendLog("⚠ 数据不一致，开始自动修复...");
                long repairStart = System.currentTimeMillis();
                int repaired = repairMismatch(writer, dataList, csvTotalRows);
                dbRows = writer.countDbRows();
                appendLog("📊 修复后：CSV=" + csvTotalRows + " 条  Oracle=" + dbRows + " 条  "
                        + (csvTotalRows == dbRows ? "✅ 一致" : "⚠ 修复" + repaired + "行，差异=" + Math.abs(csvTotalRows - dbRows) + " 条")
                        + "  耗时 " + formatMs(System.currentTimeMillis() - repairStart));
            } else {
                appendLog("📊 数据校验 ✅ 一致");
            }

            long dbElapsed = System.currentTimeMillis() - dbStart;

            // —— 汇总面板 ——
            appendLog(" ");
            appendLog("  ──────────────────────────────────────────");
            appendLog("           导 入 完 成 汇 总");
            appendLog("  ──────────────────────────────────────────");
            appendLog("   CSV 导出  " + padW(csvTotalRows + " 条", 14) + " ETF " + dataList.size() + " 只");
            appendLog("   Oracle    " + padW(dbRows + " 条", 14)
                    + (csvTotalRows == dbRows ? " ✅ 一致" : " ❌ 差异"));
            appendLog("  ──────────────────────────────────────────");
            appendLog("   新上市    " + padW(s.newEtfCount() + " 只", 8)
                    + padW(s.newWritten() + " 行", 10) + colW(formatMs(s.newMs()), 10));
            appendLog("   补漏      " + padW(s.catchUpCount() + " 天", 8)
                    + padW(s.catchWritten() + " 行", 10) + colW(formatMs(s.catchMs()), 10));
            appendLog("   更新当日  " + padW(s.latestCount() + " 只", 8)
                    + padW(s.latestWritten() + " 行", 10) + colW(formatMs(s.latestMs()), 10));
            appendLog("  ──────────────────────────────────────────");
            appendLog("   总写入    " + writer.totalWritten() + " 行");
            appendLog("   总耗时    " + formatMs(dbElapsed));
            appendLog("  ──────────────────────────────────────────");

            writer.close();

        } catch (Exception ex) {
            appendLog("❌ 数据库连接失败: " + ex.getMessage());
            appendLog("   请检查 Oracle 连接配置（TDX_DB_URL / TDX_DB_USER / TDX_DB_PASSWORD）");
        }
    }

    /** 按显示宽度填充右侧空格（CJK 字符宽度=2，ASCII=1） */
    private static String padW(String s, int targetWidth) {
        int dw = displayWidth(s);
        StringBuilder sb = new StringBuilder(s);
        while (dw < targetWidth) { sb.append(' '); dw++; }
        return sb.toString();
    }

    /** 按显示宽度填充左侧空格 */
    private static String colW(String s, int targetWidth) {
        int dw = displayWidth(s);
        StringBuilder sb = new StringBuilder();
        while (dw < targetWidth) { sb.append(' '); dw++; }
        sb.append(s);
        return sb.toString();
    }

    /** 计算字符串的终端显示宽度（CJK/全角=2，ASCII=1） */
    private static int displayWidth(String s) {
        int w = 0;
        for (char c : s.toCharArray()) {
            if (c > 0x7F) w += 2; else w += 1;
        }
        return w;
    }

    private static String formatMs(long ms) {
        if (ms < 1000) return ms + "ms";
        return String.format("%.1fs", ms / 1000.0);
    }

    /**
     * 当 CSV 与 Oracle 总记录数不一致时，按股票粒度找出缺数据的 ETF 并强制重导。
     */
    private int repairMismatch(OracleDbWriter writer, List<ExportData> dataList,
                               long csvTotal) {
        try {
            Map<String, Integer> dbCounts = writer.countDbRowsByStock();
            int repaired = 0;

            for (ExportData d : dataList) {
                String key = d.marketCode + "|" + d.code;
                int dbCnt = dbCounts.getOrDefault(key, 0);
                int csvCnt = d.adjBars.size();

                if (dbCnt != csvCnt) {
                    try {
                        int written = writer.forceReimport(
                                Market.fromCode(d.marketCode), d.code, d.name,
                                d.adjBars, d.gbbqHash, d.status);
                        repaired += written;
                        appendLog("  🔧 修复 " + key + " " + d.name
                                + "  DB=" + dbCnt + " → CSV=" + csvCnt + " 写入" + written + "行");
                    } catch (Exception ex) {
                        appendLog("  ❌ 修复失败 " + key + " " + ex.getMessage());
                    }
                }
            }

            appendLog("─".repeat(60));
            appendLog("🔧 自动修复完成：重导 " + repaired + " 行");
            return repaired;

        } catch (Exception ex) {
            appendLog("❌ 修复过程异常: " + ex.getMessage());
            return 0;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 数据时效性验证（基于 tdx_calendar 表判断最新数据是否已下载）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 验证导出的数据是否包含最新交易日的数据。
     *
     * 规则：
     *   1. 当天是交易日 → 导出的 CSV 中必须有当天数据
     *   2. 当天不是交易日 → 导出的 CSV 中必须有上一个交易日的数据
     *
     * @return true = 数据已是最新，可以继续入库；false = 数据是旧的，应阻断入库
     */
    private boolean validateDataFreshness(List<ExportData> dataList) {
        try {
            HolidayCalendarService calService = new HolidayCalendarService(this::appendLog);
            LocalDate today = LocalDate.now();
            DateTimeFormatter yyyyMMdd = DateTimeFormatter.ofPattern("yyyyMMdd");

            LocalDate targetDate;
            String reason;
            if (calService.isTradeDay(today)) {
                targetDate = today;
                reason = "今天是交易日（" + today + "）";
            } else {
                targetDate = calService.findLastTradeDay(today);
                reason = "今天不是交易日，上一个交易日为 " + targetDate;
            }

            int targetDateInt = Integer.parseInt(targetDate.format(yyyyMMdd));

            // 检查是否有任意一只 ETF 包含目标日期的数据
            boolean hasData = false;
            for (ExportData d : dataList) {
                for (AdjustedBar bar : d.adjBars) {
                    if (bar.getDate() == targetDateInt) {
                        hasData = true;
                        break;
                    }
                }
                if (hasData) break;
            }

            appendLog("─".repeat(60));
            if (hasData) {
                appendLog("✅ 数据时效验证通过：" + reason + "，CSV 中已包含该日数据");
                return true;
            } else {
                String warning = "数据未更新！\n\n"
                        + reason + "\n"
                        + "但导出的 CSV 中没有该日期的数据（所有 ETF 均缺失）。\n\n"
                        + "请先在通达信客户端中下载最新日线数据：\n"
                        + "  选项 → 盘后数据下载\n"
                        + "下载完成后再点击「刷新数据」。";
                appendLog("⛔ 数据时效验证失败：" + reason);
                appendLog("   CSV 中缺少该日期的数据，通达信可能未下载最新数据。");
                Platform.runLater(() -> showAlert(Alert.AlertType.WARNING, "数据未更新", warning));
                return false;
            }

        } catch (Exception e) {
            appendLog("─".repeat(60));
            appendLog("[信息] 无法验证数据时效性（数据库连接失败）: " + e.getMessage());
            // 无法验证时不阻断，允许继续入库
            return true;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 导出数据 DTO（内存传递）
    // ─────────────────────────────────────────────────────────────────────

    private record ExportData(String marketCode, String code, String name,
                              List<AdjustedBar> adjBars, String gbbqHash, String status) {}

    // ─────────────────────────────────────────────────────────────────────
    // 日志（线程安全）
    // ─────────────────────────────────────────────────────────────────────

    private void appendLog(String msg) {
        Platform.runLater(() -> {
            taLog.appendText(msg + "\n");
            // 自动滚动到底部
            taLog.setScrollTop(Double.MAX_VALUE);
        });
    }
}
