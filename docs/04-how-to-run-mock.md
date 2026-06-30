# 如何启动并验证Mock Server

## 一、IDEA里启动（开发阶段最常用）

打开 Run/Debug Configurations，找到主启动类 `CognitiveProfileApplication` 的运行配置，
在 **VM options** 里加一行：

```
-Dspring.profiles.active=mock
```

不写进application.yml的原因：开发阶段随时要切换"只跑mock"和"接入真实T1-T6"两种状态，
用启动参数比改配置文件更快，且避免提交代码时误把mock配置带进生产环境。

## 二、命令行启动

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=mock
```

## 三、执行建表和数据初始化（按顺序）

```bash
psql -h <你的PG地址> -U postgres -d postgres -f docs/init_db.sql
psql -h <你的PG地址> -U postgres -d postgres -f docs/03-fill-mock-url.sql
```

## 四、验证Mock Server本身是否跑通（不依赖数据库）

项目启动后，直接curl测试T1的mock端点：

```bash
curl -X POST http://localhost:8080/mock/t1/annotate_text \
  -H "Content-Type: application/json" \
  -d '{"bodyText":"测试文本","language":"zh","platform":"x"}'
```

预期返回一段固定的JSON（见MockAgentController.annotateText方法），
如果这一步返回404，说明`@Profile("mock")`没生效，检查VM options是否正确传入。

## 五、验证完整调用链路（依赖数据库已执行步骤三）

这一步验证的是`AgentProxyClient`能否正确读取`sub_agent_registry`表并打到mock地址，
而不是直接curl mock端点。最简单的验证方式：

写一个临时的测试Controller或单元测试，注入`AgentProxyClient`，调用：
```java
T1AnnotateResponse resp = agentProxyClient.call("T1", "annotate_text",
        someRequest, T1AnnotateResponse.class);
```
如果能正常拿到返回值（不抛`IllegalStateException`），说明：
- sub_agent_registry里T1的active_url_type='mock'且mock_url已填充（验证步骤三的SQL执行成功）
- AgentProxyClient的URL拼接逻辑正确
- MockAgentController的@Profile("mock")在当前启动方式下生效

这一步通过后，才建议正式启动SocialContentConsumer去消费Kafka消息走完整流程，
否则流程中途因为Mock没配对而失败，排查起来会很麻烦，分层验证能省很多调试时间。
