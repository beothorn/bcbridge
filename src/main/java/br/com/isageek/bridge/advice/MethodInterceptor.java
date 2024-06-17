package br.com.isageek.bridge.advice;

import br.com.isageek.bridge.App;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.*;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.reflect.Method;
import java.util.Objects;

import static net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC;

public class MethodInterceptor {
    @OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static Object enter(
        @Origin Method method,
        @AllArguments Object[] allArguments
    ) {
        try {
            Object dispatch = App.dispatch(method, allArguments);
            return Objects.requireNonNullElse(dispatch, Void.TYPE);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @OnMethodExit
    public static void exit(
        @Return(readOnly = false, typing = DYNAMIC) Object returned,
        @Enter Object enter
    ) {
        if(Void.TYPE.equals(enter)) {
            returned = null;
            return;
        }
        returned = enter;
    }
}