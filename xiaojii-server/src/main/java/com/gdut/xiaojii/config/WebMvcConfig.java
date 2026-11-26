package com.gdut.xiaojii.config;

import com.gdut.xiaojii.interceptor.AdminAuthInterceptor;
import com.gdut.xiaojii.interceptor.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Web 层配置：拦截器、跨域、静态资源。
 *
 * 【这个类在整条链路里的位置】
 * 它不处理任何业务，只负责"给 Spring MVC 立规矩"：
 *   ① 哪些路径要过拦截器、哪些放行（addInterceptors）
 *   ② 哪些外部域名可以跨域访问（addCorsMappings）
 *   ③ 上传的图片怎么对外访问（addResourceHandlers）
 *
 * 【为什么路径看起来少了 /api 前缀】
 * 项目配置了 server.servlet.context-path=/api，
 * 所以这里写 /app/**，实际对外地址是 /api/app/**。
 * context-path 在请求进入 Spring 之前就被 Tomcat 剥掉了，拦截器看到的永远是不带 /api 的路径。
 * 这是最容易搞混的一点：白名单写 /app/auth/login 才对，写成 /api/app/auth/login 会永远不生效。
 *
 * 【两条独立的拦截链】
 *   /app/**   → AuthInterceptor（小程序用户，typ=app）
 *   /admin/** → AdminAuthInterceptor（后台管理员，typ=admin）
 * 绝不共用一个拦截器 —— 共用就等于默许"用户令牌可以调后台接口"。
 *
 * @author 校集后端
 */
// 【写法】@Slf4j
// 【为什么】静态资源映射那段要打日志（上传目录映射到哪、创建失败），排查 404 时靠它
@Slf4j
// 【写法】@Configuration + implements WebMvcConfigurer
// 【为什么】Spring MVC 提供了 WebMvcConfigurer 这组回调接口，
//   实现它并覆写对应方法，就能在不改框架代码的前提下定制 MVC 行为。
//   不加 @Configuration 则这个类不会被实例化，所有覆写都不会生效（且不报错）。
@Configuration
// 【写法】@RequiredArgsConstructor 注入三个依赖
// 【为什么】两个拦截器要用 @Component 注册的实例（配置里不能 new，否则拿不到它们的依赖），
//   uploadProperties 提供上传目录路径
@RequiredArgsConstructor
// 【注解作用】implements WebMvcConfigurer 是"实现接口"，不是"继承类"：
//   这个接口由 Spring MVC 提供，十几个方法全是 default 空实现，
//   本类只覆写真正需要的 3 个（拦截器 / 跨域 / 静态资源），其余保持默认。
// 【为什么不用 extends WebMvcConfigurationSupport】那会关闭 Spring Boot 的 MVC 自动配置，
//   静态资源、Jackson、消息转换器都得自己重新配一遍，得不偿失。
public class WebMvcConfig implements WebMvcConfigurer {

    // 学生端拦截器：校验令牌 → 写 UserContext
    private final AuthInterceptor authInterceptor;
    // 管理端拦截器：校验令牌 → 写 AdminContext，规则与学生端完全独立
    private final AdminAuthInterceptor adminAuthInterceptor;
    // 上传配置：提供 baseDir（磁盘物理目录），用于把 /files/** 映射到真实目录
    private final UploadProperties uploadProperties;

    /** 小程序端无需登录即可访问的路径 */
    // 【写法】白名单用 excludePathPatterns 排除，而不是在拦截器内部 if 判断路径
    // 【为什么】排除规则集中在配置里，一眼能看全"哪些接口是公开的"；
    //   若写在拦截器里，每加一个公开接口都要改拦截器代码，容易漏、也难以审计
    // 【风险】这个数组就是"公开接口清单" —— 往里加东西等于对外开一个口子，
    //   加之前必须想清楚：这个接口被匿名调用会不会泄露数据
    private static final String[] APP_WHITE_LIST = {
            // 登录接口本身（微信 code 换 token / 本地调试登录）
            // 必须放行：调用它的时候用户还没有令牌，不可能通过校验
            "/app/auth/login",
            "/app/auth/dev-login",
            // 公告（未登录也能看到学校公告，审核要求页面有公示信息）
            // 这是合规要求：审核人员打开小程序时不会登录，首页必须能看到公示
            "/app/announcement/current"
    };

    /** 管理端无需登录即可访问的路径 */
    // 【为什么只有登录一个】后台没有"公开页面"这种需求，除了登录全都要求管理员身份
    private static final String[] ADMIN_WHITE_LIST = {
            "/admin/auth/login"
    };

    /**
     * 注册拦截器：规定哪扇门守哪片区域。
     */
    // 【写法】.addPathPatterns 指定"守哪些请求"，.excludePathPatterns 指定"哪些除外"
    // 【为什么用 /** 通配】/app/** 能匹配 /app/auth/login、/app/post/feed 等所有子路径，
    //   新增接口自动纳入保护，不需要每加一个接口就来改配置（漏配就是漏洞）
    // 【另一种】逐个列出受保护路径 —— 新增接口时必然漏，是安全漏洞的常见来源
    @Override
    /** 添加interceptors */
    public void addInterceptors(InterceptorRegistry registry) {
        // 小程序端：order(1) 先执行
        // 【order 的作用】多条链路径不重叠时 order 影响不大；
        //   显式写出来是为了将来扩大匹配范围时，执行顺序是确定的，不会因为注册顺序变动而悄悄改变
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/app/**")
                .excludePathPatterns(APP_WHITE_LIST)
                .order(1);

        // 管理端：独立拦截器，独立白名单
        // 【为什么必须是另一个实例】两者校验的 typ 不同（app vs admin），
        //   共用会退化成"任意有效令牌都能进后台"——典型的越权漏洞
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns(ADMIN_WHITE_LIST)
                .order(2);
    }

    /**
     * 跨域配置。
     * 小程序本身不受同源策略限制，但本地用浏览器 / Postman / 前端 Web 调试时需要。
     * 生产环境建议在 Nginx 层收敛为具体域名。
     */
    // 【写法】allowedOriginPatterns 而不是 allowedOrigins
    // 【为什么】allowedOrigins 不支持通配符，且一旦 allowCredentials(true) 就禁止用 "*"；
    //   allowedOriginPatterns 是后来加的增强版，规则更宽松、也更明确支持多域名枚举。
    // 【写法】allowCredentials(false)
    // 【为什么】本项目用 Authorization 头传令牌，不靠 Cookie；
    //   关掉凭证可以少一类 CSRF 攻击面，也不会触发"通配域名 + 携带凭证"的浏览器限制。
    // 【代价】如果将来改成 Cookie 方案，这里必须改成 true，并同步收敛域名列表。
    @Override
    /** 添加corsmappings */
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                // 只放行生产域名与本地管理后台调试端口；小程序不受同源策略限制
                // 【为什么不用 "*"】允许任意域名 = 任何网站都能在用户浏览器里调你的接口
                .allowedOriginPatterns(
                        "https://xj.sxz-blog.xyz",
                        "http://localhost:5173",
                        "http://localhost:5174",
                        "http://127.0.0.1:5173",
                        "http://127.0.0.1:5174")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                // 让前端 JS 能读到响应头 Authorization（默认浏览器会隐藏非简单响应头）
                .exposedHeaders("Authorization")
                // 用 token 而不是 Cookie，不需要带凭证
                .allowCredentials(false)
                // 预检结果缓存 1 小时：减少 OPTIONS 请求次数，降低延迟
                .maxAge(3600);
    }

    /**
     * 上传文件对外访问映射：/api/files/** → 本地磁盘目录。
     *
     * 为什么不用 spring.web.resources.static-locations：那个配置是全局的，
     * 会把上传目录和 classpath 静态资源混在一起，且不好加路径前缀。
     * 这里精确映射一个前缀，将来换 COS 时删掉这段即可。
     */
    // 【写法】自定义 ResourceHandler，把 /files/** 精确映射到上传目录
    // 【为什么不用全局 static-locations】全局配置会把"上传目录"和"jar 里的 static 资源"
    //   混在同一条链上，容易互相干扰；而且无法只给上传目录加 /files 前缀
    // 【好处】将来换 COS 时，只删这一段、把 URL 换成对象存储域名即可，业务代码不动
    @Override
    /** 添加resourcehandlers */
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = Paths.get(uploadProperties.getBaseDir()).toAbsolutePath().normalize();
        // ⚠️ 必须先建目录：Path.toUri() 只有在该目录**已经存在**时才会补结尾斜杠。
        //    若启动时 uploads/ 还不存在，toUri() 会产出 file:///.../uploads（无斜杠），
        //    Spring 会把它当成"一个文件"而不是"目录"，导致 /files/** 全部 404。
        //    这是真实踩过的坑：首次部署时接口 404，但文件确实存在于磁盘上。
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            // 【为什么只 warn 不抛】目录建不出来（权限/磁盘满）时，服务仍然能跑，
            //   只是图片访问不到；不该因为一个非核心功能让整个应用启动失败
            log.warn("上传目录创建失败：{}", dir, e);
        }
        String location = dir.toUri().toString();
        // 【为什么还要再补一次斜杠】上面那条注释说明了 toUri() 的坑，
        //   这里是不依赖"目录是否已存在"的第二道保险：无论如何都保证以 / 结尾
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        log.info("上传目录映射：/files/** -> {}", location);
        registry.addResourceHandler("/files/**")
                .addResourceLocations(location)
                // 图片带 UUID 文件名，内容不会变，可以长缓存
                // 【为什么敢缓存 1 天】文件名由 UUID 生成，同一 URL 的内容永不变化；
                //   换图必然换 URL，所以不存在"缓存了旧图"的问题
                .setCachePeriod(86400);
    }
}
