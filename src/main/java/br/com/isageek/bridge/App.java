package br.com.isageek.bridge;

import br.com.isageek.bridge.yaml.Application;
import br.com.isageek.bridge.yaml.JavaAppConfig;
import br.com.isageek.bridge.yaml.Redirection;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.AllArguments;
import net.bytebuddy.asm.Advice.OnMethodEnter;
import net.bytebuddy.asm.Advice.Origin;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class App {
    private static final Map<String, URLClassLoader> classloaders = new LinkedHashMap<>();
    private static final Map<Method, Method> redirectionMethods = new LinkedHashMap<>();

    public static void main( String[] args ) throws IOException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {

        if (args.length != 1) {
            System.err.println("Argument should be an yaml file.");
            System.exit(2);
        }

        Yaml yaml = new Yaml(new Constructor(JavaAppConfig.class, new LoaderOptions()));
        InputStream inputStream = new FileInputStream(args[0]);
        JavaAppConfig javaAppsConfig = yaml.load(inputStream);

        try {
            ByteBuddyAgent.install();
        } catch (IllegalStateException e) {
            System.err.println("[bcbridge] ByteBuddy agent installation failed: " + e.getMessage());
            return;
        }

        for (Application application: javaAppsConfig.getApplications()) {
            File appClasspathDir = new File(application.getJarsPath());
            File[] jarFiles = appClasspathDir.listFiles((dir, name) -> name.endsWith(".jar"));
            URL[] jarUrls;

            if (jarFiles != null) {
                jarUrls = new URL[jarFiles.length];
                for (int i = 0; i < jarFiles.length; i++) {
                    jarUrls[i] = jarFiles[i].toURI().toURL();
                }
            } else {
                throw new RuntimeException("[bcbridge] No JAR files found in the directory '" + appClasspathDir.getAbsolutePath() +"'");
            }

            classloaders.put(application.getName(), new URLClassLoader(jarUrls));
        }

        for (Application application: javaAppsConfig.getApplications()) {
            if (application.getRedirections() != null && !application.getRedirections().isEmpty()) {
                for (final Redirection redirection : application.getRedirections()) {

                    String[] splitSource = redirection.getSourceMethod().split("#");
                    String sourceClassName = splitSource[0];
                    String sourceFullMethod = splitSource[1];

                    URLClassLoader srcClassLoader = classloaders.get(redirection.getSourceApplication());
                    Class<?> srcClass = srcClassLoader.loadClass(sourceClassName);
                    Method[] srcClassMethods = srcClass.getDeclaredMethods();

                    String[] splitDst = redirection.getDestinationMethod().split("#");
                    String dstClassName = splitDst[0];
                    String dstFullMethod = splitDst[1];

                    URLClassLoader dstClassLoader = classloaders.get(application.getName());
                    Class<?> dstClass = dstClassLoader.loadClass(dstClassName);
                    Method[] dstClassMethods = dstClass.getDeclaredMethods();

                    for (final Method srcClassMethod : srcClassMethods) {
                        if (srcClassMethod.getName().equals(sourceFullMethod)) {
                            for (final Method dstClassMethod : dstClassMethods) {
                                if (dstClassMethod.getName().equals(dstFullMethod)) {
                                    System.out.println("srcClassMethod "+srcClassMethod);
                                    System.out.println("dstClassMethod "+dstClassMethod);
                                    redirectionMethods.put(srcClassMethod, dstClassMethod);
                                    break;
                                }
                            }
                            break;
                        }
                    }

                    try(DynamicType.Unloaded<?> matcherForMethodToBeRedirected = new ByteBuddy()
                        .redefine(srcClass)
                        .visit(
                            Advice.to(
                                MethodInterceptor.class
                            ).on(
                                named(sourceFullMethod).and(isMethod()).and(not(isStatic()))
                            )
                        )
                        .make()
                    ) {
                        matcherForMethodToBeRedirected.load(srcClassLoader, ClassReloadingStrategy.fromInstalledAgent());
                        System.out.println("[bcbridge] Successfully redefined method: " + redirection.getSourceApplication()+">"+redirection.getSourceMethod());
                    }

                    try(DynamicType.Unloaded<?> matcherForMethodToBeRedirected = new ByteBuddy()
                            .redefine(srcClass)
                            .visit(
                                    Advice.to(
                                            StaticMethodInterceptor.class
                                    ).on(
                                            named(sourceFullMethod).and(isMethod()).and(isStatic())
                                    )
                            )
                            .make()
                    ) {
                        matcherForMethodToBeRedirected.load(srcClassLoader, ClassReloadingStrategy.fromInstalledAgent());
                        System.out.println("[bcbridge] Successfully redefined method: " + redirection.getSourceApplication()+">"+redirection.getSourceMethod());
                    }
                }
            }
        }

        for (Application application: javaAppsConfig.getApplications()) {
            URLClassLoader currentAppClassLoader = classloaders.get(application.getName());
            Thread appThread = getAppThread(application, currentAppClassLoader);
            appThread.start();
        }
    }

    private static Thread getAppThread(final Application application, final URLClassLoader currentAppClassLoader) {
        Thread appThread = new Thread(application.getName()) {
            @Override
            public void run() {
                super.run();
                System.out.println("[bcbridge] Will start " + application.getName());
                try {
                    Class<?> mainClass = currentAppClassLoader.loadClass(application.getMainClass());
                    Method mainMethod1 = mainClass.getMethod("main", String[].class);
                    mainMethod1.invoke(null, (Object) new String[]{});
                } catch (IllegalAccessException | InvocationTargetException | ClassNotFoundException |
                         NoSuchMethodException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        appThread.setContextClassLoader(currentAppClassLoader);
        return appThread;
    }

    public static void dispatch(Object self, Method srcMethod, final Object[] allArguments) {
        System.out.println("[bcbridge] Will dispatch payload to client: '" + "class"+ srcMethod + " method: " + srcMethod.getName() + ": " + Arrays.toString(allArguments) + "'");
        try {
            Method methodRedirection = redirectionMethods.get(srcMethod);
            methodRedirection.invoke(self, allArguments);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static class MethodInterceptor {
        @OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(
                @Advice.This Object self,
                @Origin Method method,
                @AllArguments Object[] allArguments
        ) {
            System.out.println("[Bridge] Entered method");
            try {
                App.dispatch(self, method, allArguments);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return true; // Indicate to skip the original method execution
        }
    }

    static class StaticMethodInterceptor {
        @OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(
                @Origin Method method,
                @AllArguments Object[] allArguments
        ) {
            System.out.println("[Bridge] Entered method");
            try {
                App.dispatch(null, method, allArguments);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return true; // Indicate to skip the original method execution
        }
    }
}