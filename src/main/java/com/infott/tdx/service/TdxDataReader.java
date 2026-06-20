package com.infott.tdx.service;

import com.infott.tdx.model.DayBar;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * 读取通达信 .day 二进制日线文件
 *
 * 文件格式（每条记录固定 32 字节，小端字节序 little-endian）：
 *
 *   偏移  字节  类型    说明
 *   0     4     int     日期 YYYYMMDD
 *   4     4     int     开盘价 × 100
 *   8     4     int     最高价 × 100
 *   12    4     int     最低价 × 100
 *   16    4     int     收盘价 × 100
 *   20    4     float   成交额
 *   24    4     int     成交量（手）
 *   28    4     int     保留字段
 *
 * 价格读取后须除以 100 得到实际价格。
 */
public class TdxDataReader {

    private static final int RECORD_SIZE = 32;

    /**
     * 读取指定 .day 文件的全部日线数据，按文件顺序返回（通常为升序日期）。
     *
     * @param file .day 文件
     * @return K线列表，文件不存在或为空时返回空列表
     */
    public List<DayBar> readDayFile(File file) throws IOException {
        List<DayBar> bars = new ArrayList<>();
        if (!file.exists() || !file.isFile()) {
            return bars;
        }

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long recordCount = raf.length() / RECORD_SIZE;
            for (long i = 0; i < recordCount; i++) {
                int   date   = readLEInt(raf);
                int   openI  = readLEInt(raf);
                int   highI  = readLEInt(raf);
                int   lowI   = readLEInt(raf);
                int   closeI = readLEInt(raf);
                float amount = readLEFloat(raf);
                int   volume = readLEInt(raf);
                readLEInt(raf);   // reserved, skip

                if (date <= 0) continue;

                bars.add(new DayBar(
                        date,
                        openI  / 100.0,
                        highI  / 100.0,
                        lowI   / 100.0,
                        closeI / 100.0,
                        amount,
                        volume
                ));
            }
        }
        return bars;
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
