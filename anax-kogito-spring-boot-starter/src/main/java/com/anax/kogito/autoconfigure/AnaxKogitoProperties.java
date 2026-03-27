package com.anax.kogito.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "anax")
public class AnaxKogitoProperties {

    private CatalogProperties catalog = new CatalogProperties();
    private MetadataServerProperties metadataServer = new MetadataServerProperties();

    public CatalogProperties getCatalog() {
        return catalog;
    }

    public void setCatalog(CatalogProperties catalog) {
        this.catalog = catalog;
    }

    public MetadataServerProperties getMetadataServer() {
        return metadataServer;
    }

    public void setMetadataServer(MetadataServerProperties metadataServer) {
        this.metadataServer = metadataServer;
    }

    public static class CatalogProperties {
        private boolean enabled = true;
        private String formSchemasUrl;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getFormSchemasUrl() {
            return formSchemasUrl;
        }

        public void setFormSchemasUrl(String formSchemasUrl) {
            this.formSchemasUrl = formSchemasUrl;
        }
    }

    public static class MetadataServerProperties {
        private String url;
        private RegistrationProperties registration = new RegistrationProperties();

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public RegistrationProperties getRegistration() {
            return registration;
        }

        public void setRegistration(RegistrationProperties registration) {
            this.registration = registration;
        }
    }

    public static class RegistrationProperties {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
