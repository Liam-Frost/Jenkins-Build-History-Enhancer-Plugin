package io.jenkins.plugins.bhe;

import edu.umd.cs.findbugs.annotations.NonNull;
import jenkins.model.Jenkins;

/** Resolves the class loader that can see classes from other, optional plugins. */
final class PluginClassLoaders {

    private PluginClassLoaders() {}

    /**
     * The Jenkins uber class loader, which spans all loaded plugins, falling back to this plugin's
     * own loader when Jenkins is not available (for example in a plain unit test).
     */
    @NonNull
    static ClassLoader uberClassLoader() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins != null) {
            ClassLoader loader = jenkins.getPluginManager().uberClassLoader;
            if (loader != null) {
                return loader;
            }
        }
        return PluginClassLoaders.class.getClassLoader();
    }
}
