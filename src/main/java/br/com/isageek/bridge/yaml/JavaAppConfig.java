package br.com.isageek.bridge.yaml;

import java.util.List;

public class JavaAppConfig {
    private List<Application> applications;

    public List<Application> getApplications() {
        return applications;
    }

    public void setApplications(final List<Application> applications) {
        this.applications = applications;
    }
}