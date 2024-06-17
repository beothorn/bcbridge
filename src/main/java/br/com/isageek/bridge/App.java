package br.com.isageek.bridge;

import br.com.isageek.bridge.advice.MethodInterceptor;
import br.com.isageek.bridge.advice.PropertyInterceptor;
import br.com.isageek.bridge.baseloaderinjections.SysProps;
import br.com.isageek.bridge.yaml.Application;
import br.com.isageek.bridge.yaml.JavaAppConfig;
import br.com.isageek.bridge.yaml.Redirection;
import br.com.isageek.bridge.yaml.SystemProperty;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.DynamicType.Unloaded;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.*;

public class App {
    private static final Map<String, URLClassLoader> classloaders = new LinkedHashMap<>();
    private static final Map<Method, Method> redirectionMethods = new LinkedHashMap<>();

    public static void main( String[] args ) throws IOException, ClassNotFoundException {

        if (args.length != 1) {
            System.err.println("Argument should be an yaml file.");
            System.exit(2);
        }

        JavaAppConfig javaAppsConfig = loadConfiguration(args);

        try {
            ByteBuddyAgent.install();

        } catch (IllegalStateException e) {
            System.err.println("[bcbridge] ByteBuddy agent installation failed: " + e.getMessage());
            return;
        }

        // Injects PropertyInterceptor on boot loader so  sysProps map is visible to System
        // otherwise, when trying to check if prop should be overriden we would get a PropertyInterceptor classDefNotFound
        Class<SysProps> sysPropsClass = SysProps.class;
        ClassInjector.UsingUnsafe.ofBootLoader().inject(singletonMap(
            new TypeDescription.ForLoadedType(sysPropsClass),
            ClassFileLocator.ForClassLoader.read(sysPropsClass)
        ));


        createClassLoaders(javaAppsConfig);
        LinkedHashMap<String, LinkedHashMap<String, DynamicType.Builder>> redefiners = createClassRedefiners(javaAppsConfig);
        redefineClasses(redefiners);
        loadSystemPropertiesFromConfig(javaAppsConfig);

        Class<?> system = System.class;
        new ByteBuddy().ignore(none()).redefine(system).visit(Advice.to(PropertyInterceptor.class).on(
            named("getProperty").and(isMethod())
        )).make().load(ClassLoader.getSystemClassLoader(), ClassReloadingStrategy.fromInstalledAgent());

        runAllApplications(javaAppsConfig);
    }

    private static void loadSystemPropertiesFromConfig(final JavaAppConfig javaAppsConfig) {
        for (final Application application : javaAppsConfig.getApplications()) {
            if (application.getSystemProperties() == null) continue;
            URLClassLoader appClassLoader = classloaders.get(application.getName());
            for (final SystemProperty systemProperty : application.getSystemProperties()) {
                SysProps.props.put(appClassLoader, Map.of(systemProperty.getName(), systemProperty.getValue()));
            }
        }
    }

    private static void runAllApplications(final JavaAppConfig javaAppsConfig) {
        for (Application application: javaAppsConfig.getApplications()) {
            URLClassLoader currentAppClassLoader = classloaders.get(application.getName());
            List<String> commandArgumentsOrNull = application.getCommandArguments();
            final String[] args = (commandArgumentsOrNull == null) ? new String[]{} : commandArgumentsOrNull.toArray(new String[]{});
            Thread appThread = getAppThread(
                application,
                args,
                currentAppClassLoader
            );
            appThread.start();
        }
    }

    private static void redefineClasses(final LinkedHashMap<String, LinkedHashMap<String, DynamicType.Builder>> redefiners) {
        redefiners.forEach((app, classesRedefiners) -> {
            System.out.println("[bcvridge] Will redefine app '"+app+"'");
            classesRedefiners.forEach((className, classRedefiner) -> {
                System.out.println("[bcvridge] Will redefine class '"+className+"' for app '"+app+"'");
                try(Unloaded make = classRedefiner.make()) {
                    make.load(classloaders.get(app), ClassReloadingStrategy.fromInstalledAgent());
                }
            });
        });
    }

    private static LinkedHashMap<String, LinkedHashMap<String, DynamicType.Builder>> createClassRedefiners(final JavaAppConfig javaAppsConfig) throws ClassNotFoundException {
        LinkedHashMap<String, LinkedHashMap<String, DynamicType.Builder>> redefiners = new LinkedHashMap<>();
        for (Application application: javaAppsConfig.getApplications()) {
            if (application.getRedirections() != null && !application.getRedirections().isEmpty()) {
                for (final Redirection redirection : application.getRedirections()) {

                    String[] splitSource = redirection.getSourceMethod().split("#");
                    String sourceClassName = splitSource[0];
                    String sourceFullMethod = splitSource[1];

                    String srcApp = redirection.getSourceApplication();
                    URLClassLoader srcClassLoader = classloaders.get(srcApp);
                    Class<?> srcClass = srcClassLoader.loadClass(sourceClassName);
                    Method[] srcClassMethods = srcClass.getDeclaredMethods();

                    String[] splitDst = redirection.getDestinationMethod().split("#");
                    String dstClassName = splitDst[0];
                    String dstFullMethod = splitDst[1];

                    String dstApp = application.getName();
                    URLClassLoader dstClassLoader = classloaders.get(dstApp);
                    Class<?> dstClass = dstClassLoader.loadClass(dstClassName);
                    Method[] dstClassMethods = dstClass.getDeclaredMethods();

                    boolean foundSrc = false;
                    boolean foundDst = false;
                    for (final Method srcClassMethod : srcClassMethods) {
                        if (srcClassMethod.getName().equals(sourceFullMethod)) {
                            foundSrc = true;
                            for (final Method dstClassMethod : dstClassMethods) {
                                if (dstClassMethod.getName().equals(dstFullMethod)) {
                                    // I need to get the names as they are from different classloaders, so classes don't match
                                    List<String> srcParams = Arrays.stream(srcClassMethod.getParameterTypes()).map(Class::getName).toList();
                                    List<String> dstParams = Arrays.stream(dstClassMethod.getParameterTypes()).map(Class::getName).toList();
                                    if (srcParams.equals(dstParams)){
                                        System.out.println("[bcbridge] Redirect " + srcApp + ":"+srcClassMethod + "-> "+dstApp+":" + dstClassMethod);
                                        redirectionMethods.put(srcClassMethod, dstClassMethod);
                                        foundDst = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    if (!foundSrc) {
                        System.out.println("[bcbridge] Warning! Missing method '"+sourceFullMethod+"' for app '"+srcApp+"'");
                    }

                    if (!foundDst) {
                        System.out.println("[bcbridge] Warning! Missing method with same signature as source for '"+dstFullMethod+"' for app '"+dstApp+"'");
                    }

                    LinkedHashMap<String, DynamicType.Builder> appRedefiners = redefiners.getOrDefault(srcApp, new LinkedHashMap<>());
                    DynamicType.Builder<?> classRedefiner = appRedefiners.get(sourceClassName);
                    if (classRedefiner == null) {
                        classRedefiner = new ByteBuddy().redefine(srcClass);
                    }
                    appRedefiners.put(sourceClassName, classRedefiner.visit(
                        Advice.to(
                            MethodInterceptor.class
                        ).on(
                            named(sourceFullMethod)
                            .and(isMethod())
                        )
                    ));
                    redefiners.put(srcApp ,appRedefiners);
                }
            }
        }
        return redefiners;
    }

    private static void createClassLoaders(final JavaAppConfig javaAppsConfig) throws MalformedURLException {
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
    }

    private static JavaAppConfig loadConfiguration(final String[] args) throws FileNotFoundException {
        Yaml yaml = new Yaml(new Constructor(JavaAppConfig.class, new LoaderOptions()));
        InputStream inputStream = new FileInputStream(args[0]);
        return yaml.load(inputStream);
    }

    private static Thread getAppThread(
        final Application application,
        final String[] args,
        final URLClassLoader currentAppClassLoader
    ) {
        Thread appThread = new Thread(application.getName()) {
            @Override
            public void run() {
                super.run();
                System.out.println("[bcbridge] Will start " + application.getName());
                try {
                    Class<?> mainClass = currentAppClassLoader.loadClass(application.getMainClass());
                    Method mainMethod1 = mainClass.getMethod("main", String[].class);
                    mainMethod1.invoke(null, (Object) args);
                } catch (IllegalAccessException | InvocationTargetException | ClassNotFoundException |
                         NoSuchMethodException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        appThread.setContextClassLoader(currentAppClassLoader);
        return appThread;
    }

    public static Object dispatch(
        final Method srcMethod,
        final Object[] allArguments
    ) {
        System.out.println("[bcbridge] Redirect from " + srcMethod.getName() + "(" + Arrays.toString(allArguments) + ")");
        try {
            Method methodRedirection = redirectionMethods.get(srcMethod);
            System.out.println("[bcbridge] Redirect to " + methodRedirection.getName() + "(" + Arrays.toString(allArguments) + ")");
            methodRedirection.setAccessible(true);
            boolean isStatic = Modifier.isStatic(methodRedirection.getModifiers());
            if (isStatic) {
                Object result = methodRedirection.invoke(null, allArguments);
                System.out.println("[bcbridge] RESULT: "+result);
                return result;
            }

            Object self = methodRedirection.getDeclaringClass().getDeclaredConstructor().newInstance();
            return methodRedirection.invoke(self, allArguments);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


}