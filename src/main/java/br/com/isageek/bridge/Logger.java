package br.com.isageek.bridge;

public class Logger {

    public static void info(String info) {
        System.out.println("[bcbridge] "+info);
    }

    public static void error(String error) {
        System.out.println("[bcbridge] "+error);
    }

}
