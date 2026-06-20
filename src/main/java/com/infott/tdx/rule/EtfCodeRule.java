package com.infott.tdx.rule;

import com.infott.tdx.model.Market;

import java.util.ArrayList;
import java.util.List;

/**
 * ETF 代码识别规则
 *
 * 设计目标：可扩展。
 *   - 默认规则内置常见 ETF 前缀（见 initDefaultRules）。
 *   - 可通过 addShPrefix / addSzPrefix 动态追加。
 *   - 可通过 removeShPrefix / removeSzPrefix 移除不需要的规则。
 *
 * 使用：
 *   EtfCodeRule rule = new EtfCodeRule();
 *   boolean isEtf = rule.isEtf(Market.SH, "510300"); // true
 */
public class EtfCodeRule {

    private final List<String> shPrefixes = new ArrayList<>();
    private final List<String> szPrefixes = new ArrayList<>();

    public EtfCodeRule() {
        initDefaultRules();
    }

    private void initDefaultRules() {
        // ---- 上海市场 ETF 代码前缀 ----
        shPrefixes.addAll(List.of(
                "510", "511", "512", "513", "515", "516", "517", "518", "520",
                "530",
                "560", "561", "562", "563",
                "588", "589"
        ));
        // ---- 深圳市场 ETF 代码前缀 ----
        szPrefixes.addAll(List.of(
                "159"
        ));
    }

    // ---- 扩展接口 ----

    public void addShPrefix(String prefix) {
        if (prefix != null && !prefix.isBlank() && !shPrefixes.contains(prefix)) {
            shPrefixes.add(prefix);
        }
    }

    public void addSzPrefix(String prefix) {
        if (prefix != null && !prefix.isBlank() && !szPrefixes.contains(prefix)) {
            szPrefixes.add(prefix);
        }
    }

    public void removeShPrefix(String prefix) { shPrefixes.remove(prefix); }
    public void removeSzPrefix(String prefix) { szPrefixes.remove(prefix); }

    // ---- 判断 ----

    /**
     * 判断指定市场的6位股票代码是否为 ETF。
     *
     * @param market 市场（SH / SZ）
     * @param code   纯数字6位代码，如 "510300"
     */
    public boolean isEtf(Market market, String code) {
        List<String> prefixes = (market == Market.SH) ? shPrefixes : szPrefixes;
        for (String p : prefixes) {
            if (code.startsWith(p)) return true;
        }
        return false;
    }

    // ---- 查询 ----

    public List<String> getShPrefixes() { return List.copyOf(shPrefixes); }
    public List<String> getSzPrefixes() { return List.copyOf(szPrefixes); }
}
