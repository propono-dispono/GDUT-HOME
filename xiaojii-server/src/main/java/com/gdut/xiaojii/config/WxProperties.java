package com.gdut.xiaojii.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信小程序配置，对应 application.yml 里的 xiaojii.wx.*。
 *
 * 这个类本身没有逻辑，它只做一件事：把配置文件里的值"搬"到 Java 字段上。
 *
 * 绑定过程（发生在服务启动时，只做一次）：
 *   ① 启动时 Spring Boot 把 application.yml 读成一张扁平的 key-value 表放进 Environment，
 *      表里长这样：xiaojii.wx.appid / xiaojii.wx.secret / xiaojii.wx.dev-login-enabled …
 *   ② 扫到 @Component → 创建这个类的实例（此时字段还是 Java 默认值）
 *   ③ 看到 @ConfigurationProperties(prefix = "xiaojii.wx") → 拿这个前缀去 Environment 里捞 key
 *   ④ 挨个调 setter 把值写进同名字段
 *   ⑤ 绑定完成后字段值就固定了，之后不再读 yml
 *
 * 三个直接推论：
 *   · 改了 yml 必须重启服务才生效（绑定只发生一次）；
 *   · 绑定失败不报错 —— prefix 打错、字段名对不上、缺 @Component，都只是"保持默认值"；
 *   · @PostConstruct 里已经能读到绑定后的值（绑定在 Bean 初始化之前完成）。
 *
 * 名字怎么对上的（松散绑定 / relaxed binding）：
 *   绑定器比较前会把两边都"抹平"——去掉 - 和 _、全部转小写，
 *   所以 yml 的 dev-login-enabled 与字段 devLoginEnabled 都能归一成 devloginenabled。
 *   写法上统一用短横线即可。
 *
 * 谁在用它：
 *   WxApiClient 用它拼请求（appid / secret / 接口地址）；
 *   AuthServiceImpl 用 isDevLoginEnabled() 决定开发登录是否放行。
 *
 * @author 校集后端
 */
// Lombok 生成 getter/setter。setter 是必需的 —— Spring 绑定时就是靠 setter 把 yml 的值写进字段
@Data
// 注册成 Spring Bean。不加这个注解，@ConfigurationProperties 不会被处理，字段全是默认值（且不报错）
@Component
// 声明"我要绑定 xiaojii.wx 这个配置节点"。
// 不加则不知道绑哪个节点；prefix 写错也不会报错，只是字段永远拿到默认值 —— 这是最难查的配置问题
@ConfigurationProperties(prefix = "xiaojii.wx")
public class WxProperties {

    // 对应 application.yml 第 120 行：appid: ${WX_APPID:}
    // 小程序 AppID（公开的"名字"，不是密码）。没配时正式登录走不通，只能用 dev-login 调试
    private String appid;

    // 对应 application.yml 第 121 行：secret: ${WX_SECRET:}
    // 小程序 AppSecret（身份证密码）。两条红线：
    //   ① 绝不能提交到 Git（提交了就等于公开，必须立刻去微信后台重置）
    //   ② 绝不能下发给前端（小程序被反编译就会泄露，微信因此强制要求由后端调用）
    private String secret;

    // 对应 application.yml 第 124 行：dev-login-enabled: ${WX_DEV_LOGIN:true}
    // 松散绑定：yml 的 dev-login-enabled（短横线）自动对应 devLoginEnabled（驼峰）
    // 默认值 true 是"危险的默认值" —— 方便本地开发，但生产必须用环境变量 WX_DEV_LOGIN=false 覆盖，
    // 否则任何人都能传一个 openid 登录别人的账号
    private boolean devLoginEnabled = true;

    // 对应 application.yml 第 126 行：jscode2session-url
    // 同样是松散绑定：jscode2session-url → jscode2sessionUrl
    // 写成字段默认值而不是硬编码在 WxApiClient 里，方便将来换域名或用代理
    private String jscode2sessionUrl = "https://api.weixin.qq.com/sns/jscode2session";

    /**
     * 判断微信登录所需的配置是否齐全（appid 和 secret 都不能为空）。
     *
     * 谁在调：WxApiClient.code2Session 的第一行 —— 没配齐就直接给出明确提示，
     * 而不是拼出一个 appid= 的空 URL 去请求微信，让前端收到莫名其妙的"登录失败"。
     *
     * 为什么用 isBlank() 而不是 isEmpty()：
     *   isEmpty() 只判长度是否为 0，配置里写成 "   "（几个空格）它能通过；
     *   isBlank() 会把"全是空白字符"也算成空，更符合"有没有真正配置"的语义。
     *
     * 为什么要判 null：yml 里 ${WX_APPID:} 没配环境变量时是空字符串，
     *   但如果整个 key 被删掉，Spring 不会赋值，字段就是 null。
     */
    public boolean configured() {
        return appid != null && !appid.isBlank() && secret != null && !secret.isBlank();
    }
}
