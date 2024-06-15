package br.com.isageek.bridge.advice;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.*;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import static net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC;

public class PropertyInterceptor {

    // Will need https://stackoverflow.com/questions/63471729/creating-a-transformer-to-add-a-class-from-an-agent-using-bytebuddy
    public static Map<URLClassLoader, Map<String, String>> sysProps;

    @OnMethodEnter
    public static Optional<String> enter(
            @Origin Method method,
            @AllArguments Object[] allArguments
    ) {
        System.out.println("[bcbridge] Requested property " + method.getName()+" args "+ Arrays.toString(allArguments));
        if(allArguments.length != 1) {
            return null;
        }
        try {
            Map<String, String> sysProps = PropertyInterceptor.sysProps.get(Thread.currentThread().getContextClassLoader());
            if (sysProps == null) return null;
            String maybeSysProp = sysProps.get(allArguments[0]);
            if (maybeSysProp != null) {
                return Optional.of(maybeSysProp);
            }
            return Optional.empty();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @OnMethodExit
    public static void exit(
            @Return(readOnly = false, typing = DYNAMIC) Object returned,
            @Enter Optional<String> enter
    ) {
        System.out.println("[bcbridge] enter " + enter);
        System.out.println("[bcbridge] returned " + returned);
        if (enter != null) {
            returned = enter.orElse(null);
        }
    }
}