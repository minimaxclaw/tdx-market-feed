package com.infott.tdx.model;

/**
 * 除权除息记录（对应通达信 .xdx 文件中每条权息数据）
 *
 * 所有比例字段均已换算为「每股」：
 *   dividend   = 每股现金红利 D
 *   sendRatio  = (送股 + 转增) 每股比例 S
 *   allotRatio = 配股每股比例 P
 *   allotPrice = 配股价 A
 */
public class XdxRecord {

    private final int    date;
    private final double dividend;
    private final double sendRatio;
    private final double allotRatio;
    private final double allotPrice;

    public XdxRecord(int date, double dividend, double sendRatio,
                     double allotRatio, double allotPrice) {
        this.date       = date;
        this.dividend   = dividend;
        this.sendRatio  = sendRatio;
        this.allotRatio = allotRatio;
        this.allotPrice = allotPrice;
    }

    public int    getDate()       { return date;       }
    public double getDividend()   { return dividend;   }
    public double getSendRatio()  { return sendRatio;  }
    public double getAllotRatio() { return allotRatio; }
    public double getAllotPrice() { return allotPrice; }

    @Override
    public String toString() {
        return String.format("XdxRecord{date=%d, D=%.4f, S=%.4f, P=%.4f, A=%.2f}",
                date, dividend, sendRatio, allotRatio, allotPrice);
    }
}
