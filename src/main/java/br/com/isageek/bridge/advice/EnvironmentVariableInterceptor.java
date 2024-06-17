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
            @Origin Method method,
            @AllArguments Object[] allArguments
    ) {
        Map<URLClassLoader, Map<String, String>> props;
        try {
            Class<?> envVarsClass =  ClassLoader.getSystemClassLoader().loadClass(EnvVars.class.getCanonicalName());
            Field envVarsMapField = envVarsClass.getDeclaredField("vars");
            props = (Map<URLClassLoader, Map<String, String>>) envVarsMapField.get(null);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        System.out.println("[bcbridge] Requested Environment Variable " + method.getName()+" args "+ Arrays.toString(allArguments));
        if(allArguments.length != 1) {
            return null;
        }
        try {
            Map<String, String> envVars = props.get(Thread.currentThread().getContextClassLoader());
            if (envVars == null) {
                System.out.println("[bcbridge] envVars is null");
                return null;
            }
            String varKey = (String) allArguments[0];
            String maybeEnvVar = envVars.get(varKey);
            System.out.println("[bcbridge] envVars override for'"+varKey+"' '"+maybeEnvVar+"'");
            return maybeEnvVar;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @OnMethodExit
    public static void exit(
            @Return(readOnly = false, typing = DYNAMIC) Object returned,
            @Enter String enter
    ) {
        System.out.println("[bcbridge] enter envvar " + enter);
        System.out.println("[bcbridge] returned envvar " + returned);
        if (enter != null) {
            returned = enter;
        }
    }
}