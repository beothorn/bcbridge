package br.com.isageek.bridge.baseloaderinjections;

import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;

public class EnvVars {
    public static Map<URLClassLoader, Map<String, String>> vars = new HashMap<>();
}
