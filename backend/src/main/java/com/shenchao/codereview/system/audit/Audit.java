package com.shenchao.codereview.system.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计注解：标注在需要留痕的方法上。
 *
 * <p>典型用法：
 * <pre>{@code
 * @Audit(action = "LOGOUT", resource = "AUTH")
 * public void logout(...) { ... }
 * }</pre>
 *
 * <p>注意：审计失败绝不影响主流程（切面内部吞掉异常），
 * 否则"日志写不进去导致业务不可用"是本末倒置。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audit {

    /** 动作码，建议全大写，如 LOGIN / USER_CREATE / GATE_OVERRIDE。 */
    String action();

    /** 资源类型，如 AUTH / USER / TASK / GATE。 */
    String resource() default "";

    /** 是否记录方法入参（会自动脱敏 password/token/secret 字段）。 */
    boolean logArgs() default false;

    /** 是否记录方法返回值（默认关闭，避免写大对象）。 */
    boolean logResult() default false;

    /** 资源 ID 在参数中的位置，-1 表示不取。 */
    int resourceIdIndex() default 0;
}
