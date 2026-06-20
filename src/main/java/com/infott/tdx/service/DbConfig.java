package com.infott.tdx.service;

/**
 * Oracle 数据库连接配置。
 *
 * 可通过环境变量或系统属性覆盖默认值：
 *   TDX_DB_URL, TDX_DB_USER, TDX_DB_PASSWORD
 */
public record DbConfig(String url, String user, String password) {

    private static final String DEFAULT_URL      = "jdbc:oracle:thin:@//192.168.3.3:1521/XEPDB1";
    private static final String DEFAULT_USER     = "APP_TDX";
    private static final String DEFAULT_PASSWORD = "pwd_tdx";

    /** 从环境变量/系统属性读取，缺失时使用默认值 */
    public static DbConfig load() {
        return new DbConfig(
                System.getProperty("TDX_DB_URL",     System.getenv().getOrDefault("TDX_DB_URL",     DEFAULT_URL)),
                System.getProperty("TDX_DB_USER",    System.getenv().getOrDefault("TDX_DB_USER",    DEFAULT_USER)),
                System.getProperty("TDX_DB_PASSWORD",System.getenv().getOrDefault("TDX_DB_PASSWORD",DEFAULT_PASSWORD))
        );
    }
}
