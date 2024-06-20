package br.com.isageek.bridge;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class Logger {

    public static boolean infoEnabled = true;
    public static boolean errorEnabled = true;
    public static boolean traceEnabled = true;

    public static void info(Supplier<String> info) {
        if (infoEnabled) {
            System.out.println("[bcbridge] [INFO] "+info.get());
        }
    }

    public static void error(Supplier<String> error) {
        if (errorEnabled) {
            System.out.println("[bcbridge] [ERROR] "+error.get());
        }
    }

    public static void trace(Supplier<String> trace) {
        if (traceEnabled) {
            System.out.println("[bcbridge] [TRACE]"+trace.get());
        }
    }

}
