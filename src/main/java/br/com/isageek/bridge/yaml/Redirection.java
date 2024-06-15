package br.com.isageek.bridge.yaml;

public class Redirection {
    private String sourceApplication;
    private String sourceMethod;
    private String destinationMethod;

    public String getSourceApplication() {
        return sourceApplication;
    }

    public void setSourceApplication(final String sourceApplication) {
        this.sourceApplication = sourceApplication;
    }

    public String getSourceMethod() {
        return sourceMethod;
    }

    public void setSourceMethod(final String sourceMethod) {
        this.sourceMethod = sourceMethod;
    }

    public String getDestinationMethod() {
        return destinationMethod;
    }

    public void setDestinationMethod(final String destinationMethod) {
        this.destinationMethod = destinationMethod;
    }
}