package com.infott.tdx.ui;

import com.infott.tdx.model.AdjustedBar;
import com.infott.tdx.model.DayBar;
import com.infott.tdx.model.Market;
import com.infott.tdx.model.XdxRecord;
import com.infott.tdx.rule.EtfCodeRule;
import com.infott.tdx.service.*;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 主界面视图
 *
 * 布局：
 *   行1  [通达信目录] TextField  [浏览]
 *   行2  [导出目录]   TextField  [浏览]
 *   行3  [导出] 按钮
 *   行4  日志 TextArea
 */
public class MainView {

    private static final String DEFAULT_TDX_ROOT   = "D:\\swdtool\\new_tdx";
    private static final String DEFAULT_EXPORT_DIR  = "D:\\swdcode\\app_tdx\\etf_csv";

    private TextField tfTdxRoot;
    private TextField tfExportDir;
    private TextArea  taLog;
    private Button    btnExport;

    public void show(Stage stage) {
        stage.setTitle("通达信 ETF 数据导出工具 v1.0");

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

        // ── 行3：导出按钮 + 清除日志按钮 ─────────────────────────────────────────────
        btnExport = new Button("导出");
        btnExport.setStyle("-fx-font-size: 14px;");
        btnExport.setPrefWidth(110);
        btnExport.setDefaultButton(true);
        btnExport.setOnAction(e -> startExport());

        Button btnClearLog = new Button("清除日志");
        btnClearLog.setStyle("-fx-font-size: 14px;");
        btnClearLog.setOnAction(e -> taLog.clear());

        HBox row3 = new HBox(10, btnExport, btnClearLog);
        row3.setAlignment(Pos.CENTER_LEFT);
        row3.setPadding(new Insets(0, 0, 0, 88));

        // ── 行4：日志区域 ─────────────────────────────────────────────
        Label lblLog = new Label("运行日志：");
        taLog = new TextArea();
        taLog.setEditable(false);
        taLog.setWrapText(true);
        taLog.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px;");
        VBox.setVgrow(taLog, Priority.ALWAYS);

        // ── 根布局 ────────────────────────────────────────────────────
        VBox root = new VBox(10, row1, row2, row3, lblLog, taLog);
        root.setPadding(new Insets(14));

        Scene scene = new Scene(root, 820, 580);
        stage.setScene(scene);
        stage.setMinWidth(600);
        stage.setMinHeight(400);
        stage.show();
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

                // ── 导入 Oracle 数据库 ──────────────────────────────────
                if (!exportDataList.isEmpty()) {
                    // 统计 CSV 总记录数
                    long csvTotalRows = 0;
                    for (ExportData d : exportDataList) {
                        csvTotalRows += d.adjBars.size();
                    }
                    appendLog("─".repeat(60));
                    appendLog("CSV 总记录数: " + csvTotalRows + " 条（不含表头）");

                    importToDatabase(exportDataList);
                }

                return null;
            }
        };
    }

    // ─────────────────────────────────────────────────────────────────────
    // 导入 Oracle 数据库
    // ─────────────────────────────────────────────────────────────────────

    private void importToDatabase(List<ExportData> dataList) {
        appendLog("▶ 开始导入 Oracle 数据库...");

        try {
            OracleDbWriter writer = new OracleDbWriter(DbConfig.load(), this::appendLog);

            // 构建 ImportItem 列表
            List<OracleDbWriter.ImportItem> items = new ArrayList<>();
            for (ExportData d : dataList) {
                items.add(new OracleDbWriter.ImportItem(
                        d.marketCode, d.code, d.name, d.adjBars, d.gbbqHash, d.status));
            }

            writer.importAll(items);

            // 汇总
            appendLog("─".repeat(60));
            if (writer.getNewEtfCount() > 0) {
                appendLog("  🆕 新上市: " + writer.getNewEtfCount() + "只");
            }
            if (writer.getCatchUpCount() > 0) {
                appendLog("  📅 补漏: " + writer.getCatchUpCount() + "天");
            }

            // —— CSV vs Oracle 记录数校验 ——
            int dbRows = writer.countDbRows();
            long csvRows = dataList.stream().mapToLong(d -> d.adjBars.size()).sum();
            appendLog("─".repeat(60));
            appendLog("📊 数据校验：CSV=" + csvRows + " 条  Oracle=" + dbRows + " 条");

            if (csvRows != dbRows) {
                appendLog("⚠ 数据不一致，开始自动修复...");
                int repaired = repairMismatch(writer, dataList, csvRows);
                dbRows = writer.countDbRows();
                csvRows = dataList.stream().mapToLong(d -> d.adjBars.size()).sum();
                appendLog("📊 修复后：CSV=" + csvRows + " 条  Oracle=" + dbRows + " 条  "
                        + (csvRows == dbRows ? "✅ 一致" : "⚠ 修复" + repaired + "行，差异=" + Math.abs(csvRows - dbRows) + " 条"));
            } else {
                appendLog("📊 数据校验 ✅ 一致");
            }

            writer.close();

        } catch (Exception ex) {
            appendLog("❌ 数据库连接失败: " + ex.getMessage());
            appendLog("   请检查 Oracle 连接配置（TDX_DB_URL / TDX_DB_USER / TDX_DB_PASSWORD）");
        }
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
