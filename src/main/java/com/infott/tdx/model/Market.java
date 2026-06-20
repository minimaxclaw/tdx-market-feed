package com.infott.tdx.model;

/**
 * 交易市场枚举
 */
public enum Market {

    SH("sh", "SH", "上海"),
    SZ("sz", "SZ", "深圳");

    /** vipdoc 子目录名 */
    private final String dirName;
    /** CSV 中的市场标识 */
    private final String code;
    /** 显示名称 */
    private final String displayName;

    Market(String dirName, String code, String displayName) {
        this.dirName = dirName;
        this.code = code;
        this.displayName = displayName;
    }

    public String getDirName()    { return dirName; }
    public String getCode()       { return code; }
    public String getDisplayName(){ return displayName; }

    public static Market fromDirName(String dirName) {
        for (Market m : values()) {
            if (m.dirName.equalsIgnoreCase(dirName)) return m;
        }
        throw new IllegalArgumentException("未知市场目录名: " + dirName);
    }
}
