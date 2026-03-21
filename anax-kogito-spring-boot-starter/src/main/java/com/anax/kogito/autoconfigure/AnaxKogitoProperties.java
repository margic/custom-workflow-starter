package com.anax.kogito.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Anax Kogito integration.
 */
@ConfigurationProperties(prefix = "anax")
public class AnaxKogitoProperties {

    private CatalogProperties catalog = new CatalogProperties();

    public CatalogProperties getCatalog() {
        return catalog;
    }

    public void setCatalog(CatalogProperties catalog) {
        this.catalog = catalog;
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
}
