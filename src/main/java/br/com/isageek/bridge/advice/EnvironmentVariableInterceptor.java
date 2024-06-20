package br.com.isageek.bridge.advice;

import br.com.isageek.bridge.baseloaderinjections.EnvVars;
import br.com.isageek.bridge.baseloaderinjections.SysProps;
import net.bytebuddy.asm.Advice.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Map;

import static net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC;

public class EnvironmentVariableInterceptor {

    @OnMethodEnter
    public static String enter(
        @AllArguments Object[] allArguments
    ) {
        Map<URLClassLoader, Map<String, String>> vars;
        try {
            Class<?> envVarsClass =  ClassLoader.getSystemClassLoader().loadClass(EnvVars.class.getCanonicalName());
            Field envVarsMapField = envVarsClass.getDeclaredField("vars");
            vars = (Map<URLClassLoader, Map<String, String>>) envVarsMapField.get(null);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        try {
            Map<String, String> envVars = vars.get(Thread.currentThread().getContextClassLoader());
            if (envVars == null) {
                return null;
            }
            String varKey = (String) allArguments[0];
            return envVars.get(varKey);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @OnMethodExit
    public static void exit(
            @Return(readOnly = false, typing = DYNAMIC) Object returned,
            @Enter String enter
    ) {
        if (enter != null) {
            returned = enter;
        }
    }
}