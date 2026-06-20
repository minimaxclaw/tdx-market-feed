package com.infott.tdx.service;

import com.infott.tdx.model.Market;
import com.infott.tdx.rule.EtfCodeRule;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 扫描通达信 lday 目录，识别并返回所有 ETF 的 .day 文件信息。
 *
 * 扫描路径：
 *   {TDX_ROOT}/vipdoc/sh/lday/sh*.day
 *   {TDX_ROOT}/vipdoc/sz/lday/sz*.day
 *
 * 识别规则由 EtfCodeRule 提供，可外部扩展。
 */
public class EtfScanner {

    private final EtfCodeRule rule;

    public EtfScanner(EtfCodeRule rule) {
        this.rule = rule;
    }

    /**
     * 一条 ETF 文件条目（Java 16+ record）。
     *
     * @param market  市场（SH/SZ）
     * @param code    6位股票代码（如 "510300"）
     * @param dayFile 对应的 .day 文件
     */
    public record EtfEntry(Market market, String code, File dayFile) {
        /** 文件前缀，如 "sh510300" */
        public String filePrefix() {
            return market.getDirName() + code;
        }
    }

    /**
     * 扫描 SH + SZ 两个市场，返回所有 ETF 文件条目。
     *
     * @param tdxRoot 通达信根目录
     */
    public List<EtfEntry> scan(File tdxRoot) {
        List<EtfEntry> result = new ArrayList<>();
        result.addAll(scanMarket(tdxRoot, Market.SH));
        result.addAll(scanMarket(tdxRoot, Market.SZ));
        return result;
    }

    private List<EtfEntry> scanMarket(File tdxRoot, Market market) {
        List<EtfEntry> result = new ArrayList<>();
        String dir = "vipdoc" + File.separator + market.getDirName() + File.separator + "lday";
        File ldayDir = new File(tdxRoot, dir);

        if (!ldayDir.exists() || !ldayDir.isDirectory()) {
            return result;
        }

        String prefix = market.getDirName();   // "sh" or "sz"

        File[] files = ldayDir.listFiles((d, name) ->
                name.toLowerCase().endsWith(".day")
                && name.toLowerCase().startsWith(prefix)
                && name.length() == prefix.length() + 6 + 4  // "sh" + 6位代码 + ".day"
        );

        if (files == null) return result;

        for (File f : files) {
            String filename = f.getName();
            // 去掉前缀和 ".day" 后缀，得到6位代码
            String code = filename.substring(prefix.length(), filename.length() - 4);

            if (code.length() == 6 && rule.isEtf(market, code)) {
                result.add(new EtfEntry(market, code, f));
            }
        }

        return result;
    }
}
