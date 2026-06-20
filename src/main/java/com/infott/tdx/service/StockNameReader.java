package com.infott.tdx.service;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 读取通达信股票名称
 *
 * 数据来源：{TDX_ROOT}/T0002/hq_cache/shase.tnf（上海）
 *                                        sznse.tnf（深圳）
 *
 * TNF 文件记录格式（每条 64 字节，GB2312 编码）：
 *   字节 0-5    : 股票代码（ASCII，6字节）
 *   字节 6-7    : 市场/类型标志
 *   字节 8-17   : 股票名称（GB2312，10字节，最多5个汉字）
 *   字节 18-63  : 其他字段（IP/端口/精度等，忽略）
 *
 * 若文件不存在或格式解析失败，返回空 Map；
 * 调用方在 name == null 时可降级为使用股票代码代替名称。
 */
public class StockNameReader {

    private static final int     RECORD_SIZE = 64;
    private static final Charset GB2312      = Charset.forName("GB2312");

    /**
     * 读取指定市场的股票名称映射。
     *
     * @param tdxRoot 通达信根目录
     * @param market  "sh" 或 "sz"
     * @return 代码 → 名称（如 "510300" → "沪深300ETF"），失败时返回空 Map
     */
    public Map<String, String> readNames(File tdxRoot, String market) {
        String fileName = market.equalsIgnoreCase("sh") ? "shase.tnf" : "sznse.tnf";
        File tnfFile = new File(tdxRoot, "T0002" + File.separator + "hq_cache" + File.separator + fileName);

        if (!tnfFile.exists() || !tnfFile.isFile()) {
            return Collections.emptyMap();
        }

        Map<String, String> names = new HashMap<>();
        byte[] buf = new byte[RECORD_SIZE];

        try (RandomAccessFile raf = new RandomAccessFile(tnfFile, "r")) {
            long recordCount = raf.length() / RECORD_SIZE;
            for (long i = 0; i < recordCount; i++) {
                raf.readFully(buf);
                String code = new String(buf, 0, 6, GB2312).trim();
                String name = new String(buf, 8, 10, GB2312).trim();
                if (!code.isEmpty()) {
                    names.put(code, name.isEmpty() ? code : name);
                }
            }
        } catch (IOException e) {
            // 部分读取失败：返回已收集的数据
        }

        return names;
    }
}
