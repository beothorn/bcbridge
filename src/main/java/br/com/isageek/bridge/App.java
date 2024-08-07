package br.com.isageek.bridge;

import br.com.isageek.bridge.advice.EnvironmentVariableInterceptor;
import br.com.isageek.bridge.advice.MethodInterceptor;
import br.com.isageek.bridge.advice.SystemPropertyInterceptor;
import br.com.isageek.bridge.baseloaderinjections.EnvVars;
import br.com.isageek.bridge.baseloaderinjections.SysProps;
import br.com.isageek.bridge.classloader.ExtendableClassLoader;
import br.com.isageek.bridge.common.ClassLoaderDependentPrintStream;
import br.com.isageek.bridge.yaml.*;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class App {
    private static final Map<String, ClassLoader> classloaders = new LinkedHashMap<>();
    private static final Map<Method, Method> redirectionMethods = new LinkedHashMap<>();
    private static PrintStream bcbridgeOut;

    public static void main( String[] args ) throws IOException, ClassNotFoundException {
        bcbridgeOut = System.out;

        if (args.length == 1 && (Objects.equals(args[0], "help")
                || Objects.equals(args[0], "version")
                || Objects.equals(args[0], "-help")
                || Objects.equals(args[0], "-version"))) {
            Logger.info(() -> "Version 1.0");
            Logger.info(() -> "Usage: bcbridge config.yaml");
            System.exit(0);
        }

        if (args.length != 1) {
            Logger.error(() -> "Argument should be an yaml file.");
            System.exit(2);
        }

        JavaAppConfig javaAppsConfig = loadConfiguration(args);

        try {
            ByteBuddyAgent.install();
        } catch (IllegalStateException e) {
            Logger.error(() -> "ByteBuddy agent installation failed: " + e.getMessage());
            return;
        }

        // Injects PropertyInterceptor on bootloader so  sysProps map is visible to System
        // otherwise, when trying to check if prop should be overridden we would get a PropertyInterceptor classDefNotFound
        Class<SysProps> sysPropsClass = SysProps.class;
        Class<EnvVars> envVarsClass = EnvVars.class;
        ClassInjector.UsingUnsafe.ofBootLoader().inject(Map.of(
            new TypeDescription.ForLoadedType(sysPropsClass), ClassFileLocator.ForClassLoader.read(sysPropsClass),
            new TypeDescription.ForLoadedType(envVarsClass), ClassFileLocator.ForClassLoader.read(envVarsClass)
        ));

        createClassLoaders(javaAppsConfig);
        LinkedHashMap<String, LinkedHashMap<String, DynamicType.Builder>> redefiners = createClassRedefiners(javaAppsConfig);
        redefineClasses(redefiners);
        loadSystemPropertiesFromConfig(javaAppsConfig);
        loadEnvironmentVariablesFromConfig(javaAppsConfig);
        createOutputsFromConfig(javaAppsConfig);

        Class<?> system = System.class;
        new ByteBuddy().ignore(none()).redefine(system)
                .visit(Advice.to(SystemPropertyInterceptor.class).on(named("getProperty").and(isMethod())))
                .visit(Advice.to(EnvironmentVariableInterceptor.class).on(named("getenv").and(isMethod())))
                .make().load(ClassLoader.getSystemClassLoader(), ClassReloadingStrategy.fromInstalledAgent());

        runAllApplications(javaAppsConfig);
    }

    private static void createOutputsFromConfig(final JavaAppConfig javaAppsConfig) throws FileNotFoundException {

        Map<ClassLoader, PrintStream> outs = new HashMap<>();

        for (final Application application : javaAppsConfig.getApplications()) {
            if (application.getStdout() == null) continue;
            File out = new File(application.getStdout());
            ClassLoader appClassLoader = classloaders.get(application.getName());
            outs.put(appClassLoader, new PrintStream(out));
        }

        System.setOut(new ClassLoaderDependentPrintStream(outs));
    }

    private static void loadSystemPropertiesFromConfig(final JavaAppConfig javaAppsConfig) {
        for (final Application application : javaAppsConfig.getApplications()) {
            if (application.getSystemProperties() == null) continue;
            String name = application.getName();
            ClassLoader appClassLoader = classloaders.get(name);
            Map<String, String> sysProps = new HashMap<>();
            for (final SystemProperty systemProperty : application.getSystemProperties()) {
                String sysPropName = systemProperty.getName();
                String sysPropValue = systemProperty.getValue();
                Logger.info(() -> "Adding sys prop on '"+name+"': '"+ sysPropName +"' -> '"+ sysPropValue +"'");
                sysProps.put(sysPropName, sysPropValue);
            }
            SysProps.props.put(appClassLoader, sysProps);
        }
    }

    private static void loadEnvironmentVariablesFromConfig(final JavaAppConfig javaAppsConfig) {
        for (final Application application : javaAppsConfig.getApplications()) {
            if (application.getEnvironmentVariables() == null) continue;
            String name = application.getName();
            ClassLoader appClassLoader = classloaders.get(name);
            Map<String, String> envVars = new HashMap<>();
            for (final EnvironmentVariable environmentVariable : application.getEnvironmentVariables()) {
                String envVarName = environmentVariable.getName();
                String envVarValue = environmentVariable.getValue();
                Logger.info(() -> "Adding env var on '"+name+"': '"+ envVarName +"' -> '"+ envVarValue +"'");
                envVars.put(envVarName, envVarValue);
            }
            EnvVars.vars.put(appClassLoader, envVars);
        }
    }

    private static void runAllApplications(final JavaAppConfig javaAppsConfig) {
        for (Application application: javaAppsConfig.getApplications()) {
            ClassLoader currentAppClassLoader = classloaders.get(application.getName());
            List<String> commandArgumentsOrNull = application.getCommandArguments();
            final String[] args = (commandArgumentsOrNull == null) ? new String[]{} : commandArgumentsOrNull.toArray(new String[]{});
            Thread appThread = getAppThread(
                application,
                args,
                currentAppClassLoader
            );
            Logger.info(() -> "Will start " + application.getName());
            appThread.start();
        }
    }

    private static void redefineClasses(final LinkedHashMap<String, LinkedHashMap<String, DynamicType.Builder>> redefiners) {
        redefiners.forEach((app, classesRedefiners) -> {
            Logger.info(() -> "Will redefine app '"+app+"'");
            classesRedefiners.forEach((className, classRedefiner) -> {
                Logger.info(() -> "Will redefine class '"+className+"' for app '"+app+"'");
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
                    ClassLoader srcClassLoader = classloaders.get(srcApp);
                    Class<?> srcClass;
                    try {
                        srcClass = srcClassLoader.loadClass(sourceClassName);
                    } catch (ClassNotFoundException e) {
                        throw new RuntimeException("Looked for '"+sourceClassName+"' and found nothing on '"+srcApp+"'", e);
                    }
                    Method[] srcClassMethods = srcClass.getDeclaredMethods();

                    String[] splitDst = redirection.getDestinationMethod().split("#");
                    String dstClassName = splitDst[0];
                    String dstFullMethod = splitDst[1];

                    String dstApp = application.getName();
                    ClassLoader dstClassLoader = classloaders.get(dstApp);
                    Class<?> dstClass;
                    try {
                        dstClass = dstClassLoader.loadClass(dstClassName);
                    } catch (ClassNotFoundException e) {
                        throw new RuntimeException("Looked for '"+dstClassName+"' and found nothing on '"+dstApp+"'", e);
                    }
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
                                        Logger.info(() -> "Redirect " + srcApp + ":"+srcClassMethod + "-> "+dstApp+":" + dstClassMethod);
                                        redirectionMethods.put(srcClassMethod, dstClassMethod);
                                        foundDst = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    if (!foundSrc) {
                        Logger.info(() -> "Warning! Missing method '"+sourceFullMethod+"' for app '"+srcApp+"'");
                    }

                    if (!foundDst) {
                        Logger.info(() -> "Warning! Missing method with same signature as source for '"+dstFullMethod+"' for app '"+dstApp+"'");
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
                throw new RuntimeException("No JAR files found in the directory '" + appClasspathDir.getAbsolutePath() +"'");
            }
            classloaders.put(application.getName(), new ExtendableClassLoader(jarUrls));
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
        final ClassLoader currentAppClassLoader
    ) {
        Thread appThread = new Thread(application.getName()) {
            @Override
            public void run() {
                super.run();
                try {
                    Class<?> mainClass = currentAppClassLoader.loadClass(application.getMainClass());
                    Method mainMethod1 = mainClass.getMethod("main", String[].class);
                    Object result = mainMethod1.invoke(null, (Object) args);
                    bcbridgeOut.println(application.getName() + " exited with result " + result);
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
        final Object[] srcArguments
    ) {
        Method dstMethod = redirectionMethods.get(srcMethod);
        try {
            dstMethod.setAccessible(true);
            boolean isStatic = Modifier.isStatic(dstMethod.getModifiers());
            if (isStatic) {
                Logger.trace(() -> "Redirecting static '" + srcMethod.getDeclaringClass().getName() + "#" + srcMethod.getName()
                        + "' to '" + dstMethod.getDeclaringClass().getName() + "#" + dstMethod.getName() + "' with arguments '" + Arrays.toString(srcArguments) + "'");
                return dstMethod.invoke(null, srcArguments);
            }

            Object self = dstMethod.getDeclaringClass().getDeclaredConstructor().newInstance();
            Logger.trace(() -> "Redirecting method '" + srcMethod.getDeclaringClass().getName() + "#" + srcMethod.getName()
                    + "' to '" + dstMethod.getDeclaringClass().getName() + "#" + dstMethod.getName() + "' with arguments '" + Arrays.toString(srcArguments) + "'");
            try {
                return dstMethod.invoke(self, srcArguments);
            } catch (IllegalArgumentException e) {
                Class<?>[] parameterTypes = dstMethod.getParameterTypes();
                ClassLoader dstClassLoader = dstMethod.getDeclaringClass().getClassLoader();
                Object[] dstArguments = new Object[parameterTypes.length];
                for (int i = 0; i < parameterTypes.length; i++) {
                    Object srcObj = srcArguments[i];
                    Object dstObj = reSerializeObjectOnClassLoader(srcObj, dstClassLoader);
                    dstArguments[i] = dstObj;
                }

                Class<?> returnType = dstMethod.getReturnType();
                Object dstReturn = dstMethod.invoke(self, dstArguments);
                if (returnType.equals(Void.TYPE)) {
                    return dstReturn;
                } else {
                    return reSerializeObjectOnClassLoader(dstReturn, srcMethod.getDeclaringClass().getClassLoader());
                }
            }
        } catch (Exception e) {
            Logger.error(() -> {
                if (e.getCause() != null) {
                    return "Could not dispatch "+ srcMethod.getName()
                            + "' to '" + dstMethod.getDeclaringClass().getName() + "#" + dstMethod.getName() + "' with arguments '" +
                            Arrays.toString(srcArguments) + "'. " +
                            "Reason: " + e.getCause().getClass().getName() + " -> " + e.getCause().getMessage();
                }

                return "Could not dispatch "+ srcMethod.getName()
                        + "' to '" + dstMethod.getDeclaringClass().getName() + "#" + dstMethod.getName() + "' with arguments '" +
                        Arrays.toString(srcArguments) + "'. " +
                        "Reason: " + e.getClass().getName() + " -> " + e.getMessage();
            });
            return null;
        }
    }

    private static Object reSerializeObjectOnClassLoader(final Object srcObj, final ClassLoader dstClassLoader) {
        String srcClass = srcObj.getClass().getCanonicalName();
        try {
            dstClassLoader.loadClass(srcClass);
        } catch (ClassNotFoundException e) {
            ExtendableClassLoader dstClassLoaderWithDelegations = (ExtendableClassLoader) dstClassLoader;
            ClassLoader srcClassLoader = srcObj.getClass().getClassLoader();
            dstClassLoaderWithDelegations.add(srcClass, srcClassLoader);
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ObjectOutputStream objectOutStream = new ObjectOutputStream(out);
            objectOutStream.writeObject(srcObj);
            objectOutStream.flush();  // Ensure all data is written to the byte array
            objectOutStream.close();  // Close the stream to release resources
            ByteArrayInputStream byteInStream = new ByteArrayInputStream(out.toByteArray());
            ObjectInputStream objectInputStream = new ObjectInputStream(byteInStream) {
                @Override
                protected Class<?> resolveClass(ObjectStreamClass desc) throws ClassNotFoundException {
                    return Class.forName(desc.getName(), false, dstClassLoader);
                }
            };
            return objectInputStream.readObject();
        } catch (Exception e) {
            Logger.error(() -> {
                if (e.getCause() != null) {
                    return "Exception re-serializing '" + srcObj.getClass().getName() + "': '"
                            + e.getCause().getClass().getName() +" -> "
                            + e.getCause().getMessage() + "'";
                }
                return "Exception re-serializing '" + srcObj.getClass().getName() + "': '"+ e.getClass().getName() +" -> " + e.getMessage() + "'";
            });
            return null;
        }
    }
}