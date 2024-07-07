package br.com.isageek.bridge.classloader;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;

import java.net.URL;
import java.net.URLClassLoader;

public class ExtendableClassLoader extends URLClassLoader {

    public ExtendableClassLoader(final URL[] jarUrls) {
        super(jarUrls);
    }

    public void add(String classQualifiedName, ClassLoader loader) {
        try {
            Class<?> originalClass = loader.loadClass(classQualifiedName);
            Class<?>[] srcInterfaces = originalClass.getInterfaces();
            DynamicType.Builder<?> redefine = new ByteBuddy().redefine(originalClass);
            for (final Class<?> srcInterface : srcInterfaces) {
                Class<?> sameInterfaceOnThisClassLoader = loadClass(srcInterface.getCanonicalName());
                redefine.implement(sameInterfaceOnThisClassLoader);
            }
            redefine.make().load(this, ClassLoadingStrategy.Default.INJECTION);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
