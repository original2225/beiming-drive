package games.beiming.drive.auth;

import java.util.Set;

/**
 * 四个角色。顺序即层级：越靠前权限越大，可以互相包含。
 *
 * <ul>
 *   <li>{@code SUPER_ADMIN} 超级管理员 —— 全站唯一一档，能指派管理员、改系统设置</li>
 *   <li>{@code ADMIN}        管理员 —— 管用户（封禁/改配额）、看全站统计、用维护接口</li>
 *   <li>{@code USER}         用户 —— 注册即可，只管自己的文件</li>
 *   <li>{@code GUEST}        游客 —— 不登录时的身份，只能下载别人公开分享的文件</li>
 * </ul>
 *
 * 为什么用枚举而不是继续传字符串：
 * 以前 `"ADMIN".equals(role)` 散在两处各写一遍，加角色时要满代码库找。
 * 现在所有判断都问这个类，加一档权限只改这里。
 */
public enum Role {
    SUPER_ADMIN("超级管理员"),
    ADMIN("管理员"),
    USER("用户"),
    GUEST("游客");

    private final String label;

    Role(String label) { this.label = label; }

    public String label() { return label; }

    /** 数据库里存的就是枚举名；旧数据只有 USER / ADMIN，能直接兼容 */
    public static Role of(String raw) {
        if (raw == null || raw.isBlank()) return GUEST;
        try {
            return Role.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            // 认不出的角色一律当最低权限，不给人意外升级
            return GUEST;
        }
    }

    /** 是否至少是某一档（含自身） */
    public boolean atLeast(Role floor) {
        return rank() <= floor.rank();
    }

    /** 数字越小权限越大 */
    public int rank() { return ordinal(); }

    /** 后台员工：能进管理界面 */
    public boolean isStaff() { return atLeast(ADMIN); }

    /**
     * 能不能动「系统级」的东西：维护接口、全站数据、改别人的配额。
     * 这就是原来那个 isAdmin() 的语义。
     */
    public boolean isAdmin() { return atLeast(ADMIN); }

    /** 能不能指派/收回管理员。只有超管能 */
    public boolean canManageAdmins() { return this == SUPER_ADMIN; }

    /** 注册用户的默认角色 */
    public static Role defaultForSignup() { return USER; }

    /**
     * 谁可以被别人改角色。
     * 规则：只能动比自己低的档 —— 管理员不能提管理员，也不能碰超管。
     */
    public boolean canAssign(Role target) {
        if (!isAdmin()) return false;
        if (this == SUPER_ADMIN) return target != SUPER_ADMIN;  // 超管也不能造第二个超管
        return target == USER || target == GUEST;
    }

    /** 这些角色能登录。游客是「不登录」的虚拟身份，不落库 */
    public static Set<Role> loginable() { return Set.of(SUPER_ADMIN, ADMIN, USER); }
}