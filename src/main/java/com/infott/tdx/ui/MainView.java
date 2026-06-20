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

                for (EtfScanner.EtfEntry entry : etfs) {
                    try {
                        Market market = entry.market();
                        String code   = entry.code();
                        File   dayFile = entry.dayFile();

                        // 名称解析（找不到时用代码代替）
                        Map<String, String> nameMap = (market == Market.SH) ? shNames : szNames;
                        String name = nameMap.getOrDefault(code, code);

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
                        success++;

                    } catch (Exception ex) {
                        appendLog("[FAIL] " + entry.market().getCode() + entry.code()
                                + "  " + ex.getMessage());
                        fail++;
                    }
                }

                long elapsedMs = Duration.between(start, Instant.now()).toMillis();
                appendLog("─".repeat(60));
                appendLog(String.format("统计：成功=%d  失败=%d  空文件跳过=%d  总计=%d  耗时=%dms",
                        success, fail, skipEmpty, etfs.size(), elapsedMs));

                return null;
            }
        };
    }

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
