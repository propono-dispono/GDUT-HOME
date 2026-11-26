package com.gdut.xiaojii.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Jackson 全局配置。
 *
 * <p><b>解决什么问题：</b>LocalDateTime 默认被序列化成 ISO 串
 * （{@code 2026-10-17T16:30:00}），而前端原型里显示的是
 * {@code 2026-10-17 16:30:00}。这里统一输出格式。
 *
 * <p><b>入参为什么要兼容两种格式：</b>小程序 / 前端调试时经常直接写
 * {@code "2026-10-18 18:00:00"}（空格分隔），而第三方客户端多传 ISO
 * {@code "2026-10-18T18:00:00"}。只认一种就会出现"请求体格式错误"这种
 * 看不出原因的 400，所以两种都收。
 *
 * <p><b>⚠️ 踩坑记录：</b>不要另外注册一个 {@code ObjectMapper} Bean。
 * Spring Boot 的 JacksonAutoConfiguration 是 {@code @ConditionalOnMissingBean(ObjectMapper.class)}，
 * 一旦容器里有别的 ObjectMapper Bean，它就不再生成本该作为主 Bean 的那个，
 * MVC 会退化成用你那个"裸" mapper —— 本类的定制全部失效，表现为时间格式不生效。
 *
 * @author 校集后端
 */
@Configuration
public class JacksonConfig {

    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    public static final String DATE_PATTERN = "yyyy-MM-dd";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern(DATE_PATTERN);

        // 用 SimpleModule 而不是 new JavaTimeModule()：
        // JavaTimeModule 里已有 LocalDateTime 的反序列化器，重复 addDeserializer 会被它覆盖，
        // 单独提一个模块能保证我们的实现优先级最高。
        SimpleModule module = new SimpleModule("xiaojii-jackson");
        module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(dateTimeFormatter));
        module.addDeserializer(LocalDateTime.class, new FlexibleLocalDateTimeDeserializer(dateTimeFormatter));
        module.addSerializer(LocalDate.class, new LocalDateSerializer(dateFormatter));

        return builder -> {
            builder.modulesToInstall(module);
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        };
    }

    /**
     * 宽松的 LocalDateTime 反序列化：先按 {@code yyyy-MM-dd HH:mm:ss} 解析，
     * 失败再按 ISO-8601 解析（支持 {@code 2026-10-18T18:00:00} 与带秒的变体）。
     */
    static class FlexibleLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

        private final DateTimeFormatter formatter;

        FlexibleLocalDateTimeDeserializer(DateTimeFormatter formatter) {
            this.formatter = formatter;
        }

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            String text = p.getText();
            if (text == null || text.isBlank()) {
                return null;
            }
            String value = text.trim();
            try {
                return LocalDateTime.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
                // 继续尝试 ISO
            }
            try {
                return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException e) {
                // 只传了日期（如 2026-10-18）时按当天 0 点处理
                try {
                    return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
                } catch (DateTimeParseException ex) {
                    throw new IOException("时间格式不正确，应为 yyyy-MM-dd HH:mm:ss 或 ISO-8601：" + value);
                }
            }
        }
    }
}
