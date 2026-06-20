package com.infott.tdx.model;

/**
 * 通达信 .day 文件中一条原始日线记录（32字节/条，小端序）
 *
 * 字段含义：
 *   date   = YYYYMMDD 整数
 *   open / high / low / close = 通达信存储值 ÷ 100（已换算）
 *   amount = 成交额（float，原始）
 *   volume = 成交量（手）
 */
public class DayBar {

    private final int    date;
    private final double open;
    private final double high;
    private final double low;
    private final double close;
    private final float  amount;
    private final int    volume;

    public DayBar(int date, double open, double high, double low,
                  double close, float amount, int volume) {
        this.date   = date;
        this.open   = open;
        this.high   = high;
        this.low    = low;
        this.close  = close;
        this.amount = amount;
        this.volume = volume;
    }

    public int    getDate()   { return date;   }
    public double getOpen()   { return open;   }
    public double getHigh()   { return high;   }
    public double getLow()    { return low;    }
    public double getClose()  { return close;  }
    public float  getAmount() { return amount; }
    public int    getVolume() { return volume; }

    @Override
    public String toString() {
        return String.format("DayBar{%d O=%.2f H=%.2f L=%.2f C=%.2f vol=%d}",
                date, open, high, low, close, volume);
    }
}
