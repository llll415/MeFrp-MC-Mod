package com.mefrp.mc.platform;

final class MeFrpPlatformClassLookup {
    private MeFrpPlatformClassLookup() {
    }

    static boolean classExists(String name) {
        try {
            Class.forName(name, false, MeFrpPlatform.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            try {
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                return loader != null && Class.forName(name, false, loader) != null;
            } catch (ClassNotFoundException ignoredAgain) {
                return false;
            }
        }
    }
}
