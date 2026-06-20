package com.infott.tdx.model;

/**
 * 前复权后的K线记录
 *
 * 保留原始 DayBar 引用，另持有复权后的四个价格和本条记录适用的累计复权因子。
 */
public class AdjustedBar {

    private final DayBar raw;
    private final double adjOpen;
    private final double adjHigh;
    private final double adjLow;
    private final double adjClose;
    private final double factor;

    public AdjustedBar(DayBar raw, double adjOpen, double adjHigh,
                       double adjLow, double adjClose, double factor) {
        this.raw      = raw;
        this.adjOpen  = adjOpen;
        this.adjHigh  = adjHigh;
        this.adjLow   = adjLow;
        this.adjClose = adjClose;
        this.factor   = factor;
    }

    public DayBar getRaw()      { return raw;       }
    public int    getDate()     { return raw.getDate();   }
    public double getAdjOpen()  { return adjOpen;   }
    public double getAdjHigh()  { return adjHigh;   }
    public double getAdjLow()   { return adjLow;    }
    public double getAdjClose() { return adjClose;  }
    public float  getAmount()   { return raw.getAmount(); }
    public int    getVolume()   { return raw.getVolume(); }
    public double getFactor()   { return factor;    }
}
