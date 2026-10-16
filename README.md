# 餐饮企业食材管理系统

基于 Spring Boot 的餐饮企业食材管理系统，实现食材库存、采购、领用、供应商等全流程管理。

## 功能模块

- **用户登录** — 支持管理员、仓库、厨房三种角色
- **库存总览** — 实时查看食材库存、库存金额、临期预警、低库存预警
- **食材管理** — 食材分类、规格、保质期、安全库存设置
- **批次管理** — 入库批次追踪、保质期管理
- **领用管理** — 厨房领用申请、审批、出库流程
- **采购管理** — 采购订单创建与管理
- **供应商管理** — 供应商信息维护
- **出入库记录** — 完整的库存变动流水
- **数据导出** — 支持 CSV 格式库存报表导出

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.5.7 |
| 开发语言 | Java 17 |
| 数据库 | H2 Database（内嵌） |
| 数据访问 | Spring JdbcTemplate |
| 密码加密 | Spring Security Crypto (BCrypt) |
| 前端 | 原生 HTML / CSS / JavaScript |
| 构建工具 | Maven 3.9+ |

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.9+

### 本地运行

```bash
# 克隆项目
git clone https://github.com/98jm7s7wym-alt/shi.git
cd shi/后端

# 打包
mvn clean package -DskipTests

# 运行
java -jar target/food-material-system-1.0.0.jar --app.demo-password=Demo@123456
```

浏览器访问：http://localhost:8080

### 演示账号

| 角色 | 用户名 | 密码 |
|------|--------|------|
| 管理员 | admin | Demo@123456 |
| 仓库管理员 | warehouse | Demo@123456 |
| 厨房用户 | kitchen | Demo@123456 |

## 项目结构

```
.
├── 后端/
│   ├── src/main/java/cn/catering/
│   │   ├── Application.java          # 启动类
│   │   ├── ApiController.java        # REST API 控制器
│   │   ├── WebConfig.java            # Web 配置（CORS、安全）
│   │   └── SeedData.java             # 初始数据
│   ├── src/main/resources/
│   │   ├── static/                   # 前端静态文件
│   │   ├── schema.sql                # 数据库建表脚本
│   │   └── application.yml           # 应用配置
│   ├── pom.xml                       # Maven 配置
│   └── application.yml               # 外部配置文件
├── 前端/
│   ├── static/                       # 前端页面（index.html, app.css, app.js）
│   └── wellness-quiz/                # 独立前端子项目
├── 数据库/
│   ├── schema.sql                    # 建表脚本
│   └── data/                         # H2 数据库文件
└── README.md
```

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/login | 用户登录 |
| GET  | /api/me | 获取当前用户信息 |
| POST | /api/logout | 退出登录 |
| GET  | /api/data | 获取系统数据（库存、食材、批次等） |
| POST | /api/action/{action} | 执行操作（创建/审批/完成领用） |
| GET  | /api/export/inventory | 导出库存 CSV 报表 |

## 部署到云服务器

```bash
# 上传 JAR 到服务器
scp target/food-material-system-1.0.0.jar root@服务器IP:/root/app.jar

# 服务器上运行
mkdir -p /root/data
nohup java -jar /root/app.jar --app.demo-password=Demo@123456 > /root/app.log 2>&1 &
```

访问：http://服务器IP:8080

## 许可证

本项目仅供学习交流使用。
