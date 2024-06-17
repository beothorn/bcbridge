package br.com.isageek.bridge.baseloaderinjections;

import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;

public class EnvVars {
    public static Map<ClassLoader, Map<String, String>> vars = new HashMap<>();
}
