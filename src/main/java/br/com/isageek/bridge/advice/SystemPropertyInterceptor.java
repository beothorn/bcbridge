package br.com.isageek.bridge.advice;

import br.com.isageek.bridge.baseloaderinjections.SysProps;
import net.bytebuddy.asm.Advice.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Map;

import static net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC;

public class SystemPropertyInterceptor {

    @OnMethodEnter
    public static String enter(
            @Origin Method method,
            @AllArguments Object[] allArguments
    ) {
        Map<URLClassLoader, Map<String, String>> props;
        try {
            Class<?> sysPropsClass =  ClassLoader.getSystemClassLoader().loadClass(SysProps.class.getCanonicalName());
            Field propsMapField = sysPropsClass.getDeclaredField("props");
            props = (Map<URLClassLoader, Map<String, String>>) propsMapField.get(null);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        System.out.println("[bcbridge] Requested property " + method.getName()+" args "+ Arrays.toString(allArguments));
        if(allArguments.length != 1) {
            return null;
        }
        try {
            Map<String, String> sysProps = props.get(Thread.currentThread().getContextClassLoader());
            if (sysProps == null) {
                System.out.println("[bcbridge] sysProps is null");
                return null;
            }
            String propKey = (String) allArguments[0];
            String maybeSysProp = sysProps.get(propKey);
            System.out.println("[bcbridge] sysProps override for'"+propKey+"' '"+maybeSysProp+"'");
            return maybeSysProp;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @OnMethodExit
    public static void exit(
            @Return(readOnly = false, typing = DYNAMIC) Object returned,
            @Enter String enter
    ) {
        System.out.println("[bcbridge] enter " + enter);
        System.out.println("[bcbridge] returned " + returned);
        if (enter != null) {
            returned = enter;
        }
    }
}