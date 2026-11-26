package com.gdut.xiaojii;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 校集 · 广东工业大学校园互助平台 —— 服务端启动类。
 *
 * <p>启动后：
 * <ul>
 *   <li>接口地址：http://localhost:8080/api/...</li>
 *   <li>接口文档：http://localhost:8080/api/doc.html</li>
 * </ul>
 *
 * @author 校集后端
 */
@SpringBootApplication
@MapperScan("com.gdut.xiaojii.mapper")
// 【写法】@EnableScheduling 打开定时任务支持
// 【为什么需要】@Scheduled 只是标记，没有这个注解 Spring 不会去扫描它，
//   表现就是"注解写得好好的，方法永远不执行"，而且不报错 —— 很难发现。
// 【代价】Spring 默认的调度线程池只有 1 个线程：所有 @Scheduled 任务串行排队。
//   本项目目前只有一个任务（拼单到期），1 个线程够用；
//   将来任务多了要配 spring.task.scheduling.pool.size，否则一个慢任务会拖住其他任务。
@EnableScheduling
public class XiaojiiApplication {

    public static void main(String[] args) {
        SpringApplication.run(XiaojiiApplication.class, args);
    }
}

