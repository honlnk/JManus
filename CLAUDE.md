# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此代码库中工作时提供指导。

## 🎯 学习目标

**本项目主要用于学习目的，而非开发生产功能。**

主要学习目标：
- 深入理解基于 Spring Boot 的 AI 智能体系统架构
- 掌握 Spring AI Alibaba 框架的使用方法
- 学习企业级 Java 应用的设计模式和最佳实践
- 理解 AI Agent、ReAct 模式、工具系统等概念
- 学习响应式编程、事件驱动架构等现代技术

**重要提醒**：
- 🚫 **不进行新功能开发**：专注于理解现有代码和架构
- 🚫 **不修改生产代码**：如有必要，仅在学习过程中做最小修改
- ✅ **重点学习设计模式**：观察代码中的设计模式应用
- ✅ **记录学习笔记**：在 `docs/learning-notes/` 中记录学习过程
- ✅ **提问和探讨**：积极探讨代码设计思路和实现原理

## 项目概述

JManus 是阿里巴巴开发的基于 Spring Boot 的 AI 智能体管理系统。它是一个纯 Java 的多智能体协作实现，提供 HTTP 调用接口，可集成到现有的 Java 项目中。该系统处理需要确定性的探索性任务，如数据分析、日志处理和自动化工作流。

## 开发命令

### 构建和测试
```bash
# 构建项目（跳过测试）
make build

# 运行测试
make test

# 运行应用程序
mvn spring-boot:run
```

### 代码质量
```bash
# 格式化代码（Spring Java Format）
make format-fix

# 检查代码格式
make format-check

# 应用 Spotless 格式化
make spotless-apply

# 运行 checkstyle
make checkstyle-check
```

### 开发工具
```bash
# 安装开发工具
make tools
```

## 架构概述

### 核心组件

**智能体系统** (`com.alibaba.cloud.ai.manus.agent`):
- `BaseAgent` - 具有状态管理的核心智能体功能
- `DynamicAgent` - 具有动态行为的可配置智能体
- `ReActAgent` - 推理-行动智能体实现
- `AgentController` - 智能体管理的 REST API

**规划系统** (`com.alibaba.cloud.ai.manus.planning`):
- `PlanningFactory` - 规划组件的中央工厂
- `PlanTemplateService` - 基于模板的规划管理
- `PlanningCoordinator` - 协调规划执行

**工具系统** (`com.alibaba.cloud.ai.manus.tool`):
- 浏览器自动化 (`BrowserUseTool`)
- 数据库操作 (`DatabaseReadTool`, `DatabaseWriteTool`)
- 文件系统操作 (`DirectoryOperator`, `TextFileOperator`)
- 文档处理 (PDF, Excel, Markdown)
- 系统操作 (`Bash` 工具)

**运行时执行** (`com.alibaba.cloud.ai.manus.runtime`):
- `PlanExecutorFactory` - 不同的执行策略
- `DynamicToolPlanExecutor` - 基于工具的计划执行
- `LevelBasedExecutorPool` - 线程池管理

### 关键 API

**智能体管理:**
- `POST /api/agent` - 创建智能体
- `GET /api/agent/{id}` - 获取智能体详情
- `PUT /api/agent/{id}` - 更新智能体
- `DELETE /api/agent/{id}` - 删除智能体

**计划执行:**
- `POST /api/executor/execute` - 执行带查询的计划
- `GET /api/executor/details/{planId}` - 获取执行状态
- `POST /api/executor/submit-input/{planId}` - 提交用户输入

**配置管理:**
- `GET /api/config/group/{groupName}` - 按组获取配置
- `POST /api/config/batch-update` - 批量更新配置

### 数据库配置

JManus 支持多种数据库：
- **H2** (默认) - 嵌入式数据库
- **MySQL** - 生产数据库
- **PostgreSQL** - 生产数据库

在 `src/main/resources/application.yml` 中配置：
```yaml
spring:
  profiles:
    active: mysql  # 或 postgres
```

### MCP 集成

系统原生支持模型上下文协议 (MCP) 用于外部服务集成：
- `McpController` - MCP 配置 API
- `McpService` - MCP 服务管理
- 通过 `/api/mcp` 端点配置 MCP 服务器

## 开发说明

### 关键配置文件
- `src/main/resources/application.yml` - 主配置文件
- `src/main/resources/application-{db}.yml` - 数据库特定配置
- `pom.xml` - Maven 依赖项和构建配置

### 技术栈
- **框架:** Spring Boot 3.5.6
- **AI 集成:** Spring AI 1.0.1 与阿里巴巴扩展
- **数据库:** JPA 与 H2/MySQL/PostgreSQL
- **Web:** Spring WebFlux 用于响应式编程
- **浏览器自动化:** Playwright 1.55.0
- **构建:** Maven 与 Java 17

### 测试
测试配置为使用 JUnit 5 和 Mockito 运行。测试配置：
- 构建时默认跳过测试 (`skipTests: true`)
- 使用时区 `Asia/Shanghai`
- 禁用并行测试执行

### 代码风格
- 行长度：120 个字符
- 使用 Spring Java Format 进行代码格式化
- Spotless 用于额外的代码质量检查
- Checkstyle 用于代码风格验证

## 快速开始

1. **先决条件:** Java 17+、DashScope API 密钥（或其他 AI 提供商）
2. **运行:** `mvn spring-boot:run` 或使用预构建的 JAR
3. **访问:** 应用程序运行在 `http://localhost:18080`
4. **配置:** 在引导设置页面中设置 AI 模型 API 密钥

## 重要说明

- 应用程序使用 Spring AI 与阿里巴巴 DashScope 集成
- 支持同步和异步计划执行
- 提供全面的工具系统用于各种操作
- 包含用于解耦组件通信的事件系统
- 配置基于数据库支持运行时更新

## 📚 学习路径

### 1. 基础理解（第1-2天）
- [ ] 阅读 `docs/learning-notes/01-project-overview.md`
- [ ] 运行项目，观察日志输出
- [ ] 浏览主要包结构和类文件

### 2. 智能体系统（第3-5天）
- [ ] 学习 `docs/learning-notes/02-agent-system.md`
- [ ] 重点理解 BaseAgent、ReActAgent、DynamicAgent 的设计
- [ ] 分析智能体状态管理和生命周期

### 3. 规划和工具系统（第6-8天）
- [ ] 学习规划系统（PlanningFactory、PlanTemplateService）
- [ ] 理解工具系统的设计和扩展机制
- [ ] 查看内置工具的实现方式

### 4. API 和数据库设计（第9-10天）
- [ ] 分析 REST API 设计
- [ ] 理解数据库模型和 JPA 使用
- [ ] 学习配置管理机制

### 5. 高级特性（第11-14天）
- [ ] 理解事件系统
- [ ] 学习 MCP 集成
- [ ] 分析流式响应处理

## 🔍 学习建议

### 代码阅读顺序
1. **接口和抽象类**：先理解设计模式
2. **核心流程**：关注主要执行路径
3. **工具集成**：了解扩展机制
4. **配置和启动**：理解系统初始化

### 学习方法
- **多画图**：绘制类图、时序图、架构图
- **做笔记**：记录关键设计决策和实现细节
- **对比学习**：与其他框架或项目对比
- **实践验证**：通过调试验证理解

### 重点关注的模式
- **工厂模式**：PlanningFactory、PlanExecutorFactory
- **策略模式**：不同类型的智能体和执行器
- **观察者模式**：事件系统
- **模板方法**：BaseAgent 的执行流程
- **适配器模式**：工具系统集成

## 📝 学习文档

所有学习笔记都记录在 `docs/learning-notes/` 目录下：
- `01-project-overview.md` - 项目概览
- `02-agent-system.md` - 智能体系统详解
- 后续文档将逐步补充...

## ❓ 学习问题

遇到不理解的概念时，可以：
1. 查找相关技术文档
2. 阅读相关学术论文（如 ReAct 论文）
3. 对比其他类似项目
4. 提问探讨设计思路

记住：**学习是一个循序渐进的过程，不必急于求成。**
