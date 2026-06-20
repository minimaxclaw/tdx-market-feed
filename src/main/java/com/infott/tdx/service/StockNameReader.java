package com.infott.tdx.service;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 读取通达信股票名称
 *
 * 数据来源：{TDX_ROOT}/T0002/hq_cache/shs.tnf（上海）
 *                                        szs.tnf（深圳）
 *
 * TNF 文件格式：
 *   文件头 50 字节（跳过）
 *   每条记录 360 字节：
 *     字节 0-5    : 股票代码（ASCII，6字节）
 *     字节 6-30   : 保留/填充
 *     字节 31-49  : 股票名称（GB2312，最多19字节，约9个汉字，null 补齐）
 *     字节 50-359 : 其他字段（忽略）
 *
 * 名称解码：GB2312 混合 ASCII，逐字符解析，
 *   若末尾残留上一版本的半截 GB2312 字符，自动回溯清理。
 */
public class StockNameReader {

    private static final int     RECORD_SIZE = 360;
    private static final int     HEADER_SIZE = 50;
    private static final int     NAME_OFFSET = 31;   // 名称在记录内的偏移
    private static final int     NAME_MAXLEN = 19;   // 名称字段最大字节数（offset 31-49）
    private static final Charset GB2312      = Charset.forName("GB2312");
    private static final Charset ASCII       = StandardCharsets.US_ASCII;

    /**
     * 读取指定市场的股票名称映射。
     *
     * @param tdxRoot 通达信根目录
     * @param market  "sh" 或 "sz"
     * @return 代码 → 名称，失败时返回空 Map
     */
    public Map<String, String> readNames(File tdxRoot, String market) {
        String fileName = market.equalsIgnoreCase("sh") ? "shs.tnf" : "szs.tnf";
        File tnfFile = new File(tdxRoot, "T0002" + File.separator + "hq_cache" + File.separator + fileName);

        if (!tnfFile.exists() || !tnfFile.isFile()) {
            return Collections.emptyMap();
        }

        Map<String, String> names = new HashMap<>();
        byte[] buf = new byte[RECORD_SIZE];

        try (RandomAccessFile raf = new RandomAccessFile(tnfFile, "r")) {
            // 跳过头部的 50 字节
            raf.seek(HEADER_SIZE);

            long recordCount = (raf.length() - HEADER_SIZE) / RECORD_SIZE;
            for (long i = 0; i < recordCount; i++) {
                raf.readFully(buf);

                String code = new String(buf, 0, 6, ASCII).trim();
                if (code.isEmpty()) continue;

                String name = decodeName(buf, NAME_OFFSET, NAME_MAXLEN);
                names.put(code, name.isEmpty() ? code : name);
            }
        } catch (IOException e) {
            // 返回已成功读取的部分数据
        }

        return names;
    }

    /**
     * 从记录缓冲区解码股票名称，处理可能残留的旧名称字节。
     *
     * 算法：
     *   1. 找到第一个 null 字节作为名称段结束位置
     *   2. 逐字节解析 GB2312/ASCII 混合编码
     *   3. 如果遇到不完整的 GB2312 序列 → 停止
     *   4. 如果停止位置后还有 GB2312 前导字节 → 回退最后几个非 ASCII 字符
     *      （说明缓冲区未被完全覆盖，残留了旧名称的尾字符）
     */
    private String decodeName(byte[] buf, int offset, int maxLen) {
        // 找到名称段的结束位置（第一个 null 字节）
        int end = offset;
        int limit = Math.min(buf.length, offset + maxLen);
        while (end < limit && buf[end] != 0) {
            end++;
        }

        StringBuilder sb = new StringBuilder();
        int i = offset;

        while (i < end) {
            int b = buf[i] & 0xFF;
            if (b < 0x80) {
                // ASCII 单字节
                sb.append((char) b);
                i++;
            } else {
                // GB2312 双字节
                if (i + 1 >= end) {
                    // 不完整：只剩一个前导字节 → 表明确实是残留垃圾
                    break;
                }
                int b2 = buf[i + 1] & 0xFF;
                if (b2 == 0) {
                    break;
                }
                // 尝试验证是否为有效 GB2312 对
                String ch;
                try {
                    ch = new String(new byte[]{buf[i], buf[i + 1]}, GB2312);
                } catch (Exception ex) {
                    break;
                }
                if (ch.length() > 0 && ch.charAt(0) != '�') {
                    sb.append(ch);
                    i += 2;
                } else {
                    break;
                }
            }
        }

        // 如果因为不完整序列而停止，且剩余字节包含 GB2312 前导字节，
        // 则回退上一个非 ASCII 字符（可能是旧名称残留）
        if (i < end) {
            int remainingFirst = buf[i] & 0xFF;
            if (remainingFirst >= 0x80) {
                // 回退末尾的非 ASCII 字符
                while (sb.length() > 0 && sb.charAt(sb.length() - 1) > 127) {
                    sb.deleteCharAt(sb.length() - 1);
                }
            }
        }

        return sb.toString().trim();
    }
}
