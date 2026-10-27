package com.shenchao.codereview.system.bootstrap;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shenchao.codereview.common.util.PasswordPolicy;
import com.shenchao.codereview.system.entity.SysRole;
import com.shenchao.codereview.system.entity.SysUser;
import com.shenchao.codereview.system.entity.SysUserRole;
import com.shenchao.codereview.system.mapper.SysRoleMapper;
import com.shenchao.codereview.system.mapper.SysUserMapper;
import com.shenchao.codereview.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 首次启动引导：创建超级管理员。
 *
 * <p>为什么不把密码摘要写进 Flyway 脚本：
 * <ol>
 *   <li>脚本里的固定摘要意味着"所有部署共用同一个初始密码"，无法接受</li>
 *   <li>密码通过环境变量注入，可随部署环境不同而变化</li>
 *   <li>密码策略在这里落地校验，弱密码直接拒绝启动</li>
 * </ol>
 *
 * <p>已存在同名用户时不做任何修改——避免重启把管理员密码重置回默认值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final String DEFAULT_PASSWORD = "Admin@123456";

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.bootstrap.admin-username:admin}")
    private String adminUsername;

    @Value("${app.bootstrap.admin-password:" + DEFAULT_PASSWORD + "}")
    private String adminPassword;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) {
        String username = adminUsername.trim().toLowerCase(java.util.Locale.ROOT);

        if (userMapper.countByUsername(username) > 0) {
            log.debug("管理员 {} 已存在，跳过初始化", username);
            return;
        }

        PasswordPolicy.validate(adminPassword);

        SysRole adminRole = roleMapper.selectOne(
                Wrappers.<SysRole>lambdaQuery().eq(SysRole::getCode, "ADMIN"));
        if (adminRole == null) {
            log.error("未找到 ADMIN 角色，Flyway 种子数据可能未执行，管理员初始化中止");
            return;
        }

        SysUser admin = new SysUser();
        admin.setUsername(username);
        admin.setPassword(passwordEncoder.encode(adminPassword));
        admin.setNickname("系统管理员");
        admin.setStatus(SysUser.STATUS_ENABLED);
        userMapper.insert(admin);

        userRoleMapper.insert(SysUserRole.of(admin.getId(), adminRole.getId()));

        log.info("已创建超级管理员账号: {}", username);
        if (DEFAULT_PASSWORD.equals(adminPassword)) {
            log.warn("""
                    
                    ============================================================
                     当前使用的是默认管理员密码，存在安全风险！
                     请立即登录后修改密码，或通过环境变量 APP_ADMIN_PASSWORD 指定初始密码。
                    ============================================================""");
        }
    }
}
