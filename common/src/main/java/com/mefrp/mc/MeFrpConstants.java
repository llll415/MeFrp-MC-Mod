package com.mefrp.mc;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class MeFrpConstants {
    public static final String MOD_ID = "mefrpmc";
    public static final String MOD_NAME = "MeFrp-MC-Mod";
    public static final String VERSION = readVersion();
    public static final String MEFRPC_VERSION = "0.67.1_20260626_af59eefd";
    public static final String TUNNEL_NAME = "Mefrp-MC-MOD";
    public static final String API_BASE_URL = "https://api.mefrp.com/api";
    public static final String USER_AGENT = "MeFrp-MC-Mod/" + VERSION + " github.com/mefrp-mc-mod";

    private MeFrpConstants() {
    }

    private static String readVersion() {
        try (InputStream input = MeFrpConstants.class.getResourceAsStream("/mefrp-version.properties")) {
            if (input != null) {
                Properties properties = new Properties();
                properties.load(input);
                String version = properties.getProperty("version");
                if (version != null) {
                    version = version.trim();
                    if (!version.isEmpty() && !version.startsWith("${")) {
                        return version;
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return "dev";
    }
}
