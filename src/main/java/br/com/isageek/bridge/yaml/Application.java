package br.com.isageek.bridge.yaml;

import java.util.List;

public class Application {

    private String name;
    private String jarsPath;
    private String mainClass;
    private List<SystemProperty> systemProperties;
    private List<String> dependencies;

    private List<String> commandArguments;
    private String stdout;
    private List<Redirection> redirections;

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getJarsPath() {
        return jarsPath;
    }

    public void setJarsPath(final String jarsPath) {
        this.jarsPath = jarsPath;
    }

    public String getMainClass() {
        return mainClass;
    }

    public void setMainClass(final String mainClass) {
        this.mainClass = mainClass;
    }

    public List<SystemProperty> getSystemProperties() {
        return systemProperties;
    }

    public void setSystemProperties(final List<SystemProperty> systemProperties) {
        this.systemProperties = systemProperties;
    }

    public String getStdout() {
        return stdout;
    }

    public void setStdout(final String stdout) {
        this.stdout = stdout;
    }

    public List<Redirection> getRedirections() {
        return redirections;
    }

    public void setRedirections(final List<Redirection> redirections) {
        this.redirections = redirections;
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    public void setDependencies(final List<String> dependencies) {
        this.dependencies = dependencies;
    }

    public List<String> getCommandArguments() {
        return commandArguments;
    }

    public void setCommandArguments(final List<String> commandArguments) {
        this.commandArguments = commandArguments;
    }
}