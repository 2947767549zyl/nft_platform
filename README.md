# NFT数字藏品发售平台

基于Spring Boot + Redis + Kafka 构建的高并发秒杀系统，支持热点藏品查询、秒杀下单、订单异步落库等核心功能。

## ✨ 核心特性

- 🚀 **高并发秒杀**：Redis Lua脚本原子扣减 + Kafka异步落库，单机支持2000+ TPS
- 🛡️ **多级缓存防御**：布隆过滤器 + Caffeine + Redis，解决缓存穿透/击穿/雪崩
- 🎯 **一人一单保障**：数据库唯一索引 + 乐观锁 + 幂等消费，保证交易公平
- 🔄 **全链路容错**：超时控制 + 快速失败 + 异常重试 + 降级兜底

## 📦 技术栈

| 技术 | 用途 |
|------|------|
| Spring Boot 2.7 | 应用框架 |
| MyBatis-Plus | ORM框架 |
| Redis | 缓存、分布式锁、Lua脚本 |
| Kafka | 异步消息、削峰填谷 |
| MySQL | 数据持久化 |
| Caffeine | 本地缓存 |
| Redisson | 分布式锁（布隆过滤器） |
| Hutool | 工具类 |

## 🏗️ 系统架构
┌─────────────────────────────────────────────────────────────┐
│ 用户请求 │
└─────────────────────────────────────────────────────────────┘
│
▼
┌─────────────────────────────────────────────────────────────┐
│ 查询链路：布隆过滤器 → Caffeine → Redis → DB │
│ 下单链路：Lua原子扣减 → Kafka → 异步落库 │
└─────────────────────────────────────────────────────────────┘


## 📋 核心功能

### 1. 精细化流量防护
- 基于自定义注解 + AOP 实现非侵入式限流
- Guava RateLimiter 令牌桶算法，按用户ID独立配额

### 2. 多级缓存防御
- **防穿透**：布隆过滤器 + 空值缓存
- **防雪崩**：Caffeine本地缓存 + Redis分布式缓存 + 随机TTL
- **防击穿**：分布式互斥锁 + 双重检查机制

### 3. 交易数据正确性
- Redis Lua脚本原子操作（库存校验 + 一人一单）
- Kafka异步落库 + 幂等消费（唯一索引 + 乐观锁）

### 4. 高可用与容错
- 全链路超时控制（Redis 1s / MySQL 3s / Kafka 5s）
- @Retryable 指数退避重试 + @Recover 降级兜底
- 全局异常处理器 + 分类降级策略

## 🔧 快速开始

### 环境要求
- JDK 11+
- Docker / Docker Compose
- Maven 3.6+

### 启动步骤

```bash
# 1. 克隆项目
git clone https://github.com/your-username/nft-platform.git

# 2. 启动中间件（Docker Compose）
docker-compose up -d

# 3. 修改配置（application.yml）
# 配置数据库、Redis、Kafka连接信息

# 4. 启动应用
mvn spring-boot:run
Docker Compose 配置
yaml
version: '3.8'
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root123
    ports:
      - "3306:3306"

  redis:
    image: redis:6.2-alpine
    ports:
      - "6379:6379"

  kafka:
    image: confluentinc/cp-kafka:7.3.0
    ports:
      - "9092:9092"
```bash

📁 项目结构
text
src/main/java/com/hmdp/
├── annotation/          # 自定义注解（限流、缓存等）
├── aspect/              # AOP切面（限流、日志）
├── controller/          # 控制器层
├── service/             # 业务逻辑层
│   ├── impl/            # 服务实现
│   └── consumer/        # Kafka消费者
├── mapper/              # MyBatis-Plus Mapper
├── entity/              # 实体类
├── config/              # 配置类
├── utils/               # 工具类（ID生成器、布隆过滤器等）
└── dto/                 # 数据传输对象
🗄️ 数据库设计
sql
-- 订单表（核心）
CREATE TABLE `voucher_order` (
    `id` bigint NOT NULL COMMENT '订单ID（雪花算法）',
    `user_id` bigint NOT NULL COMMENT '用户ID',
    `voucher_id` bigint NOT NULL COMMENT '优惠券ID',
    `status` tinyint DEFAULT 1 COMMENT '订单状态',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_voucher` (`user_id`, `voucher_id`)  -- 一人一单
);
📊 压测数据
场景	QPS	P99 RT	成功率
读接口（多级缓存）	5000+	< 30ms	100%
写接口（秒杀）	2000	< 50ms	99.5%
🔄 后续优化方向
分布式限流（Sentinel + Redis滑动窗口）

实时对账（Canal + Kafka）

监控告警（Prometheus + Grafana）

容器化部署（K8s）

📄 License
MIT License

📧 联系方式
邮箱：19992774992@163.com

---


---

