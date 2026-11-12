package com.mefrp.mc;

public final class MeFrpMessages {
    public static final String CONFIG_UPGRADED_PREFIX = "[MeFrp] 配置已升级至 ";
    public static final String CONFIG_UPGRADE_FAILED = "[MeFrp] 配置升级失败，原配置未修改。";
    public static final String LEGACY_TOKEN_MIGRATED = "[MeFrp] 检测到旧版明文访问令牌，已加密保存。";
    public static final String LEGACY_TOKEN_MIGRATION_FAILED = "[MeFrp] 旧版访问令牌加密保存失败，原配置未修改，仍为明文。";
    public static final String TOKEN_DECRYPT_FAILED = "[MeFrp] 访问令牌解密失败，可能已更换机器或系统。请用 /mefrp token <访问令牌> 重新设置。";
    public static final String MACHINE_ID_UNAVAILABLE = "[MeFrp] 无法获取机器码，不能加密保存访问令牌。请稍后重试。";
    public static final String RESET_SUCCESS = "[MeFrp] 配置已重建，请用 /mefrp token <访问令牌> 重新设置。";
    public static final String RESET_FAILED = "[MeFrp] 配置重建失败，请检查配置目录是否可写。";
    public static final String RESET_BUSY = "[MeFrp] 请先停止隧道，再重建配置。";
    public static final String RESET_HINT = "[MeFrp] 可用 /mefrp config reset 重建配置。";
    public static final String CONFIG_READ_FAILED = "[MeFrp] 配置读取失败，请检查 config/mefrp.json。";
    public static final String TOKEN_SAVE_FAILED = "[MeFrp] 访问令牌保存失败，请检查配置目录是否可写。";
    public static final String HELP_RESET = "[MeFrp] /mefrp config reset - 重建本地配置";

    private MeFrpMessages() {
    }

    public static String configUpgraded() {
        return CONFIG_UPGRADED_PREFIX + MeFrpConstants.VERSION + "。";
    }
}
