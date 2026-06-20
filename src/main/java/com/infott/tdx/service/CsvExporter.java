package com.infott.tdx.service;

import com.infott.tdx.model.AdjustedBar;
import com.infott.tdx.model.Market;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 将前复权 K 线数据导出为 CSV 文件（UTF-8，无 BOM）。
 *
 * CSV 列顺序：
 *   交易日, 市场, 股票代码, 股票名称, 开盘价, 收盘价, 最高价, 最低价, 成交额, 成交量
 *
 * 提供两种导出模式：
 *   exportAll()       : 全量写入，覆盖整个文件
 *   exportLatestDay() : 仅写最新一条，若文件已存在则替换同日数据并保留历史
 */
public class CsvExporter {

    public static final String[] HEADERS = {
            "交易日", "市场", "股票代码", "股票名称",
            "开盘价", "收盘价", "最高价", "最低价", "成交额", "成交量"
    };

    private static final CSVFormat WRITE_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader(HEADERS)
            .build();

    private static final CSVFormat READ_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader(HEADERS)
            .setSkipHeaderRecord(true)
            .build();

    // ─────────────────────────────────────────────────────────────────

    /**
     * 全量导出：覆盖整个文件，写出所有 bar。
     */
    public void exportAll(File csvFile, Market market, String code, String name,
                          List<AdjustedBar> bars) throws IOException {
        ensureParentDir(csvFile);
        try (CSVPrinter printer = buildPrinter(csvFile, false)) {
            for (AdjustedBar bar : bars) {
                printRecord(printer, market, code, name, bar);
            }
        }
    }

    /**
     * 仅导出当天（最新一条）：
     *   - 文件不存在 → 新建并写入
     *   - 文件已存在 → 过滤掉同日期的旧行，追加新行（保留历史）
     */
    public void exportLatestDay(File csvFile, Market market, String code, String name,
                                AdjustedBar latestBar) throws IOException {
        ensureParentDir(csvFile);

        if (!csvFile.exists()) {
            try (CSVPrinter printer = buildPrinter(csvFile, false)) {
                printRecord(printer, market, code, name, latestBar);
            }
            return;
        }

        // 读取已有行，跳过与 latestBar 日期相同的旧行
        String todayStr = String.valueOf(latestBar.getDate());
        List<String[]> kept = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(csvFile), StandardCharsets.UTF_8));
             CSVParser parser = CSVParser.parse(br, READ_FORMAT)) {

            for (CSVRecord rec : parser) {
                if (!rec.get("交易日").trim().equals(todayStr)) {
                    kept.add(toArray(rec));
                }
            }
        }

        // 重写：历史行 + 最新行
        try (CSVPrinter printer = buildPrinter(csvFile, false)) {
            for (String[] row : kept) {
                printer.printRecord((Object[]) row);
            }
            printRecord(printer, market, code, name, latestBar);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 私有辅助
    // ─────────────────────────────────────────────────────────────────

    private void printRecord(CSVPrinter printer, Market market, String code,
                             String name, AdjustedBar bar) throws IOException {
        printer.printRecord(
                bar.getDate(),
                market.getCode(),
                code,
                name,
                fmt3(bar.getAdjOpen()),
                fmt3(bar.getAdjClose()),
                fmt3(bar.getAdjHigh()),
                fmt3(bar.getAdjLow()),
                fmt2(bar.getAmount()),
                bar.getVolume()
        );
    }

    private CSVPrinter buildPrinter(File file, boolean append) throws IOException {
        FileOutputStream fos = new FileOutputStream(file, append);
        OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
        BufferedWriter bw = new BufferedWriter(osw);
        return new CSVPrinter(bw, WRITE_FORMAT);
    }

    private String[] toArray(CSVRecord rec) {
        String[] arr = new String[HEADERS.length];
        for (int i = 0; i < HEADERS.length; i++) {
            arr[i] = rec.get(i);
        }
        return arr;
    }

    private void ensureParentDir(File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }

    private String fmt3(double v) { return String.format("%.3f", v); }
    private String fmt2(float  v) { return String.format("%.2f",  v); }
}
