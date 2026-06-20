package com.infott.tdx;

/**
 * IDEA / 非模块化 classpath 启动入口
 *
 * 背景：JavaFX 11+ 要求 javafx.* 模块必须在 --module-path 上加载。
 *       当主类直接继承 Application 时，JVM 在启动时就会检查模块是否存在，
 *       而通过普通 classpath 启动会报"缺少 JavaFX 运行时组件"。
 *
 * 解决方式：使用一个 **不继承 Application** 的包装类作为 Main Class，
 *           JVM 启动时不触发 JavaFX 模块检查，待 JavaFX 类真正被调用时
 *           已可从 classpath 正常加载。
 *
 * IDEA 使用方法：
 *   Run → Edit Configurations → Main class 改为 com.infott.tdx.Launcher
 */
public class Launcher {
    public static void main(String[] args) {
        MainApp.main(args);
    }
}
