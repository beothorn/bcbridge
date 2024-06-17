package br.com.isageek.bridge.common;

import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.Map;

public class ClassLoaderDependentPrintStream extends PrintStream {

    private final Map<ClassLoader, PrintStream> printStreamPerClassLoader;

    public ClassLoaderDependentPrintStream(final Map<ClassLoader, PrintStream> printStreamPerClassLoader) throws FileNotFoundException {
        super(System.out);
        this.printStreamPerClassLoader = printStreamPerClassLoader;
    }

    @Override
    public void println(final String x) {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        PrintStream printStream = printStreamPerClassLoader.get(contextClassLoader);
        if (printStream != null) {
            printStream.println(x);
        } else {
            super.println(x);
        }
    }
}
