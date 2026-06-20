package com.infott.tdx.service;

import com.infott.tdx.model.XdxRecord;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * 读取通达信每股除权除息文件（.xdx / .qfq）
 *
 * 自动检测：优先查找 .xdx，若不存在则尝试 .qfq；两者都不存在则返回空列表。
 *
 * 文件格式（每条记录 29 字节，小端字节序）：
 *
 *   偏移  字节  类型    说明
 *   0     4     uint    除权日期 YYYYMMDD
 *   4     1     byte    权息类型（1=送配权, 2=现金红利）
 *   5     4     float   f0：type=1 时为送股(每10股), type=2 时为现金红利(每10股)
 *   9     4     float   f1：type=1 时为转增(每10股)
 *   13    4     float   f2：type=1 时为配股(每10股)
 *   17    4     float   f3：type=1 时为配股价
 *   21    4     float   f4：reserved
 *   25    4     float   f5：reserved
 *
 * 换算规则（输出字段均为「每股」单位）：
 *   type=1:  sendRatio  = (f0 + f1) / 10
 *            allotRatio = f2 / 10
 *            allotPrice = f3
 *   type=2:  dividend   = f0 / 10
 */
public class XdxReader {

    private static final int RECORD_SIZE = 29;

    /**
     * 自动检测并读取权息文件。
     *
     * @param ldayDir    lday 目录（如 vipdoc/sh/lday）
     * @param filePrefix 文件前缀（如 "sh510300"）
     * @return 权息记录列表，不存在或解析失败时返回空列表
     */
    public List<XdxRecord> readXdx(File ldayDir, String filePrefix) {
        for (String ext : new String[]{".xdx", ".qfq"}) {
            File xdxFile = new File(ldayDir, filePrefix + ext);
            if (xdxFile.exists()) {
                try {
                    return parse(xdxFile);
                } catch (IOException ignored) {
                    // 解析失败时跳过，尝试下一个扩展名
                }
            }
        }
        return new ArrayList<>();
    }

    private List<XdxRecord> parse(File file) throws IOException {
        List<XdxRecord> records = new ArrayList<>();

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long count = raf.length() / RECORD_SIZE;

            for (long i = 0; i < count; i++) {
                raf.seek(i * RECORD_SIZE);

                int  date = readLEInt(raf);
                int  type = raf.readByte() & 0xFF;

                float[] f = new float[6];
                for (int j = 0; j < 6; j++) {
                    f[j] = readLEFloat(raf);
                }

                if (date <= 0) continue;

                double dividend   = 0;
                double sendRatio  = 0;
                double allotRatio = 0;
                double allotPrice = 0;

                if (type == 1) {
                    // 送配权：f0=送股/10股, f1=转增/10股, f2=配股/10股, f3=配股价
                    sendRatio  = (f[0] + f[1]) / 10.0;
                    allotRatio = f[2] / 10.0;
                    allotPrice = f[3];
                } else if (type == 2) {
                    // 现金红利：f0=红利/10股 → 每股 = f0/10
                    dividend = f[0] / 10.0;
                }
                // 其他类型暂不处理，跳过（不影响整体流程）

                records.add(new XdxRecord(date, dividend, sendRatio, allotRatio, allotPrice));
            }
        }
        return records;
    }

    // -------- 小端序辅助方法 --------

    private int readLEInt(RandomAccessFile raf) throws IOException {
        byte[] b = new byte[4];
        raf.readFully(b);
        return (b[0] & 0xFF)
             | ((b[1] & 0xFF) << 8)
             | ((b[2] & 0xFF) << 16)
             | ((b[3] & 0xFF) << 24);
    }

    private float readLEFloat(RandomAccessFile raf) throws IOException {
        return Float.intBitsToFloat(readLEInt(raf));
    }
}
