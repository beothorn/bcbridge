package br.com.isageek.bridge;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.AllArguments;
import net.bytebuddy.asm.Advice.OnMethodEnter;
import net.bytebuddy.asm.Advice.Origin;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

public class App {

    static URLClassLoader serverLoader;
    static URLClassLoader clientLoader;

    public static void main( String[] args ) throws IOException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        try {
            // Install the ByteBuddy agent
            ByteBuddyAgent.install();
        } catch (IllegalStateException e) {
            System.err.println("ByteBuddy agent installation failed: " + e.getMessage());
            return;
        }

        File serverDir = new File("../server/target/output");
        File[] serverJarFiles = serverDir.listFiles((dir, name) -> name.endsWith(".jar"));
        URL[] serverJarUrls;

        if (serverJarFiles != null) {
            serverJarUrls = new URL[serverJarFiles.length];
            for (int i = 0; i < serverJarFiles.length; i++) {
                serverJarUrls[i] = serverJarFiles[i].toURI().toURL();
            }
        } else {
            throw new RuntimeException("[Bridge] No JAR files found in the directory '" + serverDir.getAbsolutePath() +"'");
        }

        try(URLClassLoader serverClassLoader = new URLClassLoader(serverJarUrls)){
            // Load the main class from the JAR
            Class<?> serverMainClass = serverClassLoader.loadClass("br.com.isageek.App");

            Method mainMethod1 = serverMainClass.getMethod("main", String[].class);
            mainMethod1.invoke(null, (Object) new String[] {});
            serverLoader = serverClassLoader;
        }

        File clientDir = new File("../client/target/output");
        File[] clientJarFiles = clientDir.listFiles((dir, name) -> name.endsWith(".jar"));
        URL[] clientJarUrls;

        if (clientJarFiles != null) {
            clientJarUrls = new URL[clientJarFiles.length];
            for (int i = 0; i < clientJarFiles.length; i++) {
                clientJarUrls[i] = clientJarFiles[i].toURI().toURL();
            }
        } else {
            throw new RuntimeException("[Bridge] No JAR files found in the directory '" + clientDir.getAbsolutePath() +"'");
        }

        try(URLClassLoader clientClassLoader = new URLClassLoader(clientJarUrls)){

            Class<?> targetClass = clientClassLoader.loadClass("br.com.isageek.App");

            try(DynamicType.Unloaded<?> callServer = new ByteBuddy()
                    .redefine(targetClass)
                    .visit(Advice.to(MethodInterceptor.class).on(ElementMatchers.named("callServer")))
                    .make()
            ) {
                callServer.load(targetClass.getClassLoader(), ClassReloadingStrategy.fromInstalledAgent());
            }

            // Load the main class from the JAR
            Class<?> clientMainClass = clientClassLoader.loadClass("br.com.isageek.App");

            Method mainMethod = clientMainClass.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) new String[] {});
            clientLoader = clientClassLoader;
        }
    }

    public static void dispatch(String payload) {
        System.out.println("[Bridge] Will dispatch payload to client: '" + payload + "'");
        try {
            Class<?> mainClass = serverLoader.loadClass("br.com.isageek.App");
            Method mainMethod = mainClass.getMethod("bridgeReceive", String.class);
            mainMethod.invoke(null, payload);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Define the advice class
    class MethodInterceptor {
        @OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(
                @Origin Method method,
                @AllArguments Object[] allArguments
        ) {
            System.out.println("[Bridge] Entered method");
            try {
                Class<?> mainClass = Thread.currentThread().getContextClassLoader().loadClass("br.com.isageek.bridge.App");
                Method mainMethod = mainClass.getMethod("dispatch", String.class);
                mainMethod.invoke(null, allArguments[0]);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return true; // Indicate to skip the original method execution
        }
    }
}
