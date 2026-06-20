package com.infott.tdx.service;

import com.infott.tdx.model.AdjustedBar;
import com.infott.tdx.model.DayBar;
import com.infott.tdx.model.XdxRecord;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 前复权（QFQ）计算器
 *
 * ─────────────────────────────────────────────────────────────────────
 * 单次除权因子公式：
 *
 *          C_pre - D + P × A
 *   F  =  ─────────────────────
 *          C_pre × (1 + S + P)
 *
 *   C_pre = 除权日前最后一个交易日的收盘价
 *   D     = 每股现金红利
 *   S     = 每股送股 + 转增比例
 *   P     = 每股配股比例
 *   A     = 配股价
 *
 * 累计前复权因子（从最新到最旧累乘）：
 *   Factor_t = Factor_{t+1} × F_t
 *
 * 最终复权价格：
 *   Price_adj = Price_raw × Factor（该bar适用的累计因子）
 *
 * ─────────────────────────────────────────────────────────────────────
 * 算法说明（前复权 = 以最新价格为基准，向历史调整）：
 *   1. 最新bar的因子固定为 1.0（最新价格不变）。
 *   2. 从最新bar向最旧bar反向遍历。
 *   3. 每当跨过一个除权日 D，将 cumFactor 乘以 F_D，
 *      使得该除权日之前的所有 bar 同步调低。
 * ─────────────────────────────────────────────────────────────────────
 */
public class QfqCalculator {

    /**
     * 计算前复权 K 线列表。
     *
     * @param bars       原始日线数据（任意顺序，内部自动排序）
     * @param xdxRecords 除权除息记录（可为 null 或空，此时输出原始价格）
     * @return 前复权后的 K 线列表（按日期升序）
     */
    public List<AdjustedBar> calculate(List<DayBar> bars, List<XdxRecord> xdxRecords) {
        if (bars == null || bars.isEmpty()) return Collections.emptyList();

        // 无权息数据 → 直接输出原始价格（factor = 1.0）
        if (xdxRecords == null || xdxRecords.isEmpty()) {
            return bars.stream()
                    .sorted(Comparator.comparingInt(DayBar::getDate))
                    .map(b -> new AdjustedBar(b, b.getOpen(), b.getHigh(), b.getLow(), b.getClose(), 1.0))
                    .collect(Collectors.toList());
        }

        // 按日期升序排列
        List<DayBar> sortedBars = bars.stream()
                .sorted(Comparator.comparingInt(DayBar::getDate))
                .collect(Collectors.toList());

        List<XdxRecord> sortedXdx = xdxRecords.stream()
                .sorted(Comparator.comparingInt(XdxRecord::getDate))
                .collect(Collectors.toList());

        // 步骤 1：为每个除权事件计算单次因子 F
        // key = 除权日, value = F（同一天多条权息则累乘）
        Map<Integer, Double> factorByDate = computeSingleFactors(sortedBars, sortedXdx);

        if (factorByDate.isEmpty()) {
            // 所有权息记录计算失败（如找不到 C_pre），退化为原始价格
            return sortedBars.stream()
                    .map(b -> new AdjustedBar(b, b.getOpen(), b.getHigh(), b.getLow(), b.getClose(), 1.0))
                    .collect(Collectors.toList());
        }

        // 步骤 2：按除权日降序排列（从最新往最旧遍历）
        List<Integer> xdxDatesDesc = factorByDate.keySet().stream()
                .sorted(Collections.reverseOrder())
                .collect(Collectors.toList());

        // 步骤 3：反向遍历 bars，为每个 bar 分配累计因子
        Map<Integer, Double> barCumFactor = new HashMap<>(sortedBars.size());
        double cumFactor = 1.0;
        int xdxPtr = 0;

        for (int i = sortedBars.size() - 1; i >= 0; i--) {
            DayBar bar = sortedBars.get(i);
            // 将所有"严格在此 bar 之后"的除权日因子纳入累乘
            while (xdxPtr < xdxDatesDesc.size() && xdxDatesDesc.get(xdxPtr) > bar.getDate()) {
                cumFactor *= factorByDate.get(xdxDatesDesc.get(xdxPtr));
                xdxPtr++;
            }
            barCumFactor.put(bar.getDate(), cumFactor);
        }

        // 步骤 4：应用复权因子，输出 AdjustedBar
        return sortedBars.stream()
                .map(bar -> {
                    double f = barCumFactor.getOrDefault(bar.getDate(), 1.0);
                    return new AdjustedBar(
                            bar,
                            round4(bar.getOpen()  * f),
                            round4(bar.getHigh()  * f),
                            round4(bar.getLow()   * f),
                            round4(bar.getClose() * f),
                            f
                    );
                })
                .collect(Collectors.toList());
    }

    // -------- 私有辅助 --------

    /**
     * 为每个除权事件计算单次因子 F。
     * 同一除权日多条记录（如既送股又分红）时，将各 F 累乘。
     */
    private Map<Integer, Double> computeSingleFactors(List<DayBar> sortedBars,
                                                      List<XdxRecord> sortedXdx) {
        Map<Integer, Double> result = new LinkedHashMap<>();

        for (XdxRecord xdx : sortedXdx) {
            DayBar prebar = findLastBarBefore(sortedBars, xdx.getDate());
            if (prebar == null) continue;

            double cPre = prebar.getClose();
            if (cPre <= 0) continue;

            double D = xdx.getDividend();
            double S = xdx.getSendRatio();
            double P = xdx.getAllotRatio();
            double A = xdx.getAllotPrice();

            double denominator = cPre * (1 + S + P);
            if (denominator <= 0) continue;

            double F = (cPre - D + P * A) / denominator;

            // 合理性过滤：因子应在 (0, 2] 范围内
            if (F <= 0 || F > 2.0) continue;

            // 同一除权日多条记录累乘
            result.merge(xdx.getDate(), F, (a, b) -> a * b);
        }
        return result;
    }

    /** 在升序 bars 中，找 date 严格之前的最后一条 bar */
    private DayBar findLastBarBefore(List<DayBar> sortedBars, int date) {
        DayBar result = null;
        for (DayBar bar : sortedBars) {
            if (bar.getDate() < date) {
                result = bar;
            } else {
                break;
            }
        }
        return result;
    }

    /** 保留 4 位小数，避免浮点累积误差过大 */
    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
