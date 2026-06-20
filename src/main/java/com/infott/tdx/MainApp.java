package com.infott.tdx;

import com.infott.tdx.ui.MainView;
import javafx.application.Application;
import javafx.stage.Stage;

/**
 * 程序入口
 *
 * 运行方式：
 *   mvn javafx:run
 *
 * 或打包后（需自行配置 JavaFX 模块路径）：
 *   java --module-path <javafx-sdk>/lib --add-modules javafx.controls,javafx.fxml \
 *        -jar tdx-market-feed-1.0.0.jar
 */
public class MainApp extends Application {

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        new MainView().show(primaryStage);
    }
}
