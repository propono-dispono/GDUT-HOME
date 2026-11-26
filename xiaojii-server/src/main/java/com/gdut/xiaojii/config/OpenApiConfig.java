package com.gdut.xiaojii.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档配置（Knife4j / springdoc）。
 *
 * <p>加 Bearer 安全方案后，Knife4j 页面右上角会出现"Authorize"按钮，
 * 填一次 token 后续所有请求自动带 {@code Authorization: Bearer xxx}，
 * 联调时不用每个接口手填（原骨架缺这个配置，调试体验很差）。
 *
 * @author 校集后端
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "Authorization";

    @Bean
    public OpenAPI xiaojiiOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("校集 · 校园互助平台 API")
                        .version("1.0.0")
                        .description("""
                                广东工业大学校园互助平台服务端接口。

                                统一返回体：{"code":0,"msg":"ok","data":{}}，code=0 为成功。
                                业务错误码从 1000 起；401 未登录 / 403 无权限 / 429 限流。

                                小程序端：/api/app/**    管理端：/api/admin/**
                                """)
                        .contact(new Contact().name("校集后端")))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME_NAME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录接口返回的 token，无需自己加 Bearer 前缀")))
                // 全局默认需要鉴权；免登录接口在文档上单独标注即可
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }
}
