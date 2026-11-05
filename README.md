# shipyard-backend｜船厂工程管理后端

基于 RuoYi-Vue 3.9.1 扩展的船厂工程管理 API，为微信小程序、H5 和 PC 管理后台提供统一身份、权限及业务数据。按船号和工作区域管理包工、点工、加班、报销、安全罚款、工资结算与收支台账。

## 关联仓库

| 项目 | 说明 | GitHub |
| --- | --- | --- |
| shipyard-backend | 后端服务 | [shipyard-backend](https://github.com/jiangyi3265/shipyard-backend) |
| shipyard-admin | 管理后台 | [shipyard-admin](https://github.com/jiangyi3265/shipyard-admin) |
| shipyard-app | 小程序与H5 | [shipyard-app](https://github.com/jiangyi3265/shipyard-app) |

三端使用同一套 API 和数据库。草稿保留在各客户端本机；正式业务单据、审批、财务和消息已读状态由后端统一维护。本仓库描述当前源码能力，不代表某个线上环境已部署相同版本，也不代表微信平台已审核上架。

## 项目简介

- 工程和区域资料、人员档案、人员停用与历史姓名/班组快照。
- 微信首次登录申请、管理员审核绑定、账号多角色、角色权限和工程范围管理；PC/H5 使用账号密码登录。
- 组长查看本人业务；管理员、项目经理和负责人按业务规则查看全工程，财务操作另有权限校验。
- 包工分配到人并核对总价；点工、加班按每人时薪和工时计价，加班与普通时薪相同。金额由服务端计算并按人四舍五入到分。
- 用工提交、退回重提、审批、组长申请完工、经理确认或退回、区域归档。施工完工与工资付款分别记录。
- 报销凭证、经理/负责人签字确认、会计付款；安全罚款审批和收款登记。
- 月度工资结算、收支台账、经营汇总及站内消息；微信订阅通知需另外配置平台模板及用户授权。
- 事务、版本号及请求幂等校验，防止重复提交、重复记账和超时重试导致再次计价。

## 技术栈

| 部分 | 当前实现 |
| --- | --- |
| 语言与构建 | Java；POM 的 source/target 为 1.8；Maven 多模块构建 |
| 框架 | RuoYi-Vue 3.9.1、Spring Boot 2.5.15、Spring Framework 5.3.39 |
| 认证与授权 | Spring Security 5.7.14、JWT、Redis 会话及业务权限校验 |
| 数据访问 | MyBatis / XML Mapper、PageHelper 1.4.7；船厂业务使用 Spring JdbcTemplate 与事务 |
| 数据库与连接池 | MySQL、Druid 1.2.27 |
| API 与数据处理 | Spring MVC、Springfox Swagger 3.0.0、Fastjson2、Apache POI |
| 测试 | JUnit 4、Spring Boot Test；Node.js 原生 fetch 接口回归脚本 |

项目使用 MyBatis，不依赖 MyBatis-Plus。`ruoyi-quartz`、`ruoyi-generator` 源码保留，但当前聚合 POM 和启动模块已注释其模块/依赖，默认不会启用。

## 快速启动

下面步骤用于全新的本地数据库。准备 JDK、Maven 3.6+、MySQL 8、Redis，以及 Node.js 18+；确保 `java`、`mvn`、`mysql`、`redis-cli`、`redis-server`、`node` 可从命令行执行。Java 编译目标以 POM 的 1.8 为准，未将 Java 17 设为强制要求。

### 1. 克隆并准备本机配置

```bash
git clone https://github.com/jiangyi3265/shipyard-backend.git
cd shipyard-backend
```

公开仓库只包含无秘密的配置模板。将以下三个文件分别复制为去掉 `.example` 后缀的文件；实际配置已加入 `.gitignore`。以下是 PowerShell 命令：

```powershell
Copy-Item ruoyi-admin/src/main/resources/application.yml.example ruoyi-admin/src/main/resources/application.yml
Copy-Item ruoyi-admin/src/main/resources/application-druid.yml.example ruoyi-admin/src/main/resources/application-druid.yml
Copy-Item ruoyi-admin/src/main/resources/application-shipyard.yml.example ruoyi-admin/src/main/resources/application-shipyard.yml
```

Linux/macOS 可使用对应的 `cp` 命令。已有配置不要覆盖，按模板对照补充环境变量。

### 2. 设置本地数据库凭据

MySQL 服务需要已启动。使用仅用于本地开发且有建库权限的账号，密码通过当前进程环境提供，不写入脚本：

```powershell
$env:SHIPYARD_DB_USER = Read-Host '本地 MySQL 用户名'
$dbCredential = Get-Credential -UserName $env:SHIPYARD_DB_USER -Message '本地 MySQL 密码'
$env:SHIPYARD_DB_PASSWORD = $dbCredential.GetNetworkCredential().Password
```

Linux/macOS 可使用 `read -r SHIPYARD_DB_USER`、`read -rs SHIPYARD_DB_PASSWORD` 后 `export SHIPYARD_DB_USER SHIPYARD_DB_PASSWORD`。脚本不再从已有配置文件中提取密码，也没有数据库口令默认值。

### 3. 初始化、构建和启动

```bash
node scripts/local.mjs init
mvn -pl ruoyi-admin -am package -DskipTests
node scripts/local.mjs start
```

本地脚本使用 MySQL `127.0.0.1:3306`、数据库 `shipyard33`、Redis `127.0.0.1:6383`（DB 0），后端默认监听 `127.0.0.1:8083`。Redis 未启动时脚本会在 `.local/redis` 启动独立实例；PID、日志与初始化凭据均保存在被忽略的 `.local/` 中。

启动后访问 `http://127.0.0.1:8083/captchaImage` 检查服务。API 根路径没有 `/api`：客户端反向代理可将 `/api` 或 `/dev-api` 转发到此服务并去掉该前缀。PC/H5 的代理和小程序接口地址请分别按两个前端仓库说明配置。

### 4. 首次登录

公开 SQL 没有可直接登录的默认口令或密码哈希。首次启动显式开启 `ShipyardBootstrap`，生成角色、示例工程和人员，并设置本机初始化密码。

- 管理员用户名为 `admin`；五个本地示例账号为 `sy_leader`、`sy_manager`、`sy_owner`、`sy_accountant`、`sy_safety`。
- 在自己机器查看 `.local/shipyard33-credentials.json` 的 `password` 字段取得首次密码。该文件不上传仓库，也不要发送给无关人员。
- 如需自设，首次运行脚本前设置至少 12 位的 `SHIPYARD_INITIAL_PASSWORD`；也可设置随机 `SHIPYARD_TOKEN_SECRET`。未提供时脚本在本机生成随机值并保存。
- 角色已存在时 bootstrap 不重新创建账号，也不会重置已有密码或授权。修改环境变量不等于重置数据库里的密码。

示例资料用于本地流程演示，不包含真实审批、工资、付款或余额。日常使用的真实人员和权限应由管理员创建、审核及分配。

Windows 停止本地 Java 服务：`powershell -File scripts/stop-local.ps1`。脚本校验 PID 与项目 JAR 路径后才停止该进程；Redis 是独立进程，不会随之停止。

## 配置说明

配置按 `druid,shipyard` 两个 profile 启用，`production` 是附加资源限制和关闭 Swagger 的配置。直接启动 JAR 时需自己提供环境变量，例如：

```bash
java -Duser.timezone=Asia/Shanghai -jar ruoyi-admin/target/ruoyi-admin.jar --spring.profiles.active=druid,shipyard,production
```

| 环境变量 | 用途 |
| --- | --- |
| `SHIPYARD_DB_HOST` / `SHIPYARD_DB_PORT` / `SHIPYARD_DB` | 数据库地址、端口和库名；直接启动默认本机 3306 / shipyard33 |
| `SHIPYARD_DB_USER` / `SHIPYARD_DB_PASSWORD` | 数据库账号与密码，必须自行提供 |
| `SHIPYARD_TOKEN_SECRET` | JWT 签名密钥，必须自行提供高强度随机值；本地脚本会生成并保存 |
| `SHIPYARD_BIND` / `SHIPYARD_PORT` | 后端监听地址和端口；模板默认仅监听本机 |
| `SHIPYARD_REDIS_HOST` / `SHIPYARD_REDIS_PORT` / `SHIPYARD_REDIS_DB` | Redis 配置；模板默认本机 6383 / DB 0 |
| `SHIPYARD_REDIS_PASSWORD` | Redis 密码，按实例设置 |
| `SHIPYARD_UPLOAD_PATH` | 若依上传目录；通用模板使用本机 `.local/uploads` |
| `SHIPYARD_BOOTSTRAP` / `SHIPYARD_INITIAL_PASSWORD` | 是否显式进行首次示例初始化及初始密码；直接启动默认不开启 |
| `SHIPYARD_WECHAT_APP_ID` / `SHIPYARD_WECHAT_SECRET` | 微信小程序身份交换配置，仅后端持有 AppSecret；缺配置时微信登录返回配置提示 |
| `SHIPYARD_WECHAT_NOTICE_TEMPLATE` / `SHIPYARD_WECHAT_NOTICE_FIELDS` | 订阅消息模板 ID 和字段映射，按服务及微信平台要求配置 |
| `SHIPYARD_WECHAT_NOTICE_STATE` | 微信通知跳转环境，默认 `formal` |

`local.mjs` 专用于固定的本机开发/测试实例，不是生产部署器。生产环境应提供独立数据库账号、Redis、环境配置及反向代理，关闭首次示例初始化；不要复制 `.local`、数据库导出或客户凭据到公开仓库。环境变量示例不会自动加载 `.env` 文件。

### 数据库文件与升级

- `sql/ry_20250522.sql`：若依基础表和初始角色/菜单，包含 `DROP TABLE`，只用于新的独立数据库。不要在已有业务库整文件重跑。
- `sql/shipyard.sql`：船厂业务表，新库在基础 SQL 后执行；本地 `init` 按此顺序执行。
- `sql/shipyard_*_YYYYMMDD.sql`：既有库的独立迁移，按已应用版本核对，不是自动迁移框架。
- 完工申请功能依赖 `sy_area.completion_requested_at` 和 `completion_requested_by`。旧库需先执行 `shipyard_area_completion_request_20261105.sql` 再上线对应后端；该独立迁移不可重复执行。前后端需使用匹配版本，避免只有客户端有入口而后端缺接口。

## 项目结构

```text
ruoyi-admin/                 启动模块、REST 控制器及船厂业务实现
  src/main/java/com/ruoyi/shipyard/
    ShipyardService.java     用工、审批、完工、结算与汇总
    ShipyardAccess.java      角色、工程和单据权限
    ShipyardAdministration.java 资料与账号管理
    ShipyardWechat*.java     微信身份、注册审核与订阅消息
    ShipyardIdempotency.java 请求参数一致性和幂等校验
  src/main/resources/       配置模板、MyBatis 等资源
  src/test/                 单元测试与显式启用的数据库集成测试
ruoyi-framework/             Spring Security、登录、Web 与公共框架
ruoyi-system/                若依系统用户、角色、菜单及 Mapper
ruoyi-common/                通用实体、异常、工具和基础依赖
ruoyi-generator/             保留的上游生成器源码（默认未启用）
ruoyi-quartz/                保留的上游定时任务源码（默认未启用）
sql/                        基础结构和业务迁移
scripts/                    本地启动与隔离接口回归
.local/                     本机私有文件（不提交）
```

## 验证

不依赖真实业务数据库的专项单元测试示例：

```bash
mvn -pl ruoyi-admin -am test -Dtest=ShipyardIdempotencyTest,ShipyardWorkerLifecycleTest,ShipyardAdministrationTest,ShipyardBusinessAdminRegistrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

接口回归会写入测试资料，只能运行在独立测试实例。先按快速启动配置模板和数据库环境，完成构建后：

```bash
node scripts/local.mjs init --test
node scripts/local.mjs start --test
```

测试实例使用 `shipyard33_test`、8081 端口、Redis DB 1。等待服务可用后，将该实例的初始密码放入 `SHIPYARD_TEST_PASSWORD`。PowerShell 示例只在本机读取已生成文件，不输出密码：

```powershell
$env:SHIPYARD_TEST_PASSWORD = (Get-Content .local/shipyard33_test-credentials.json -Raw | ConvertFrom-Json).password
node scripts/verify-business.mjs
node scripts/verify-bootstrap.mjs
node scripts/verify-idempotent-retries.mjs
node scripts/verify-concurrent-hours.mjs
node scripts/verify-area-completion.mjs
```

这些脚本默认固定连接本机 8081 和 Redis 6383 / DB 1，不应改指向生产。`ShipyardWechatIntegrationTest` 另需显式的 `-Dshipyard.test.config=...` 私有测试配置和隔离数据库，不包含在上面的纯单元测试命令中。浏览器、接口或模拟器检查不能代替真实微信授权、拍照、签字及通知的手机验收。

## 简历描述示例

基于 Spring Boot、Spring Security、MySQL 与 Redis 实现船厂工程管理后端，统一支撑微信小程序、H5 和 PC，覆盖人员权限、用工审批、报销罚款及工资结算。通过服务端计价、历史快照、事务锁、版本控制与幂等校验维护跨端数据一致性。

## 开源归属

本项目基于 [RuoYi-Vue](https://gitee.com/y_project/RuoYi-Vue) 3.9.1 二次开发，保留上游 RuoYi 的 MIT 许可证与版权声明，详见 [LICENSE](LICENSE)。新增船厂业务代码随本仓库按 MIT 许可提供；使用和分发时应继续保留适用的版权及许可声明。

