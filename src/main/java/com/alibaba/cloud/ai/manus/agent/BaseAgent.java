/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.manus.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.tool.ToolCallback;

import com.alibaba.cloud.ai.manus.config.ManusProperties;
import com.alibaba.cloud.ai.manus.llm.LlmService;
import com.alibaba.cloud.ai.manus.planning.PlanningFactory.ToolCallBackContext;
import com.alibaba.cloud.ai.manus.recorder.service.PlanExecutionRecorder;
import com.alibaba.cloud.ai.manus.runtime.entity.vo.ExecutionStep;
import com.alibaba.cloud.ai.manus.runtime.service.PlanIdDispatcher;
import com.alibaba.cloud.ai.manus.tool.SystemErrorReportTool;
import com.alibaba.cloud.ai.manus.tool.TerminateTool;
import com.alibaba.cloud.ai.manus.tool.code.ToolExecuteResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 【智能体抽象基类】An abstract base class for implementing AI agents that can execute multi-step tasks.
 * 【用于实现能够执行多步骤任务的AI智能体的抽象基类】
 * This class provides the core functionality for managing agent state, conversation flow,
 * and step-by-step execution of tasks.
 * 【该类提供了管理智能体状态、对话流程和任务逐步执行的核心功能】
 *
 * <p>
* The agent supports a finite number of execution steps and includes mechanisms for:
 * 【智能体支持有限数量的执行步骤，并包含以下机制：】
 * <ul>
 * <li>State management (idle, running, finished)【状态管理（空闲、运行中、已完成）】</li>
 * <li>Conversation tracking【对话跟踪】</li>
 * <li>Step limitation and monitoring【步骤限制和监控】</li>
 * <li>Thread-safe execution【线程安全执行】</li>
 * <li>Stuck-state detection and handling【卡住状态检测和处理】</li>
 * </ul>
 *
 * <p>
 * Implementing classes must define:
 * 【实现类必须定义：】
 * <ul>
 * <li>{@link #getName()} - Returns the agent's name【返回智能体名称】</li>
 * <li>{@link #getDescription()} - Returns the agent's description【返回智能体描述】</li>
 * <li>{@link #getThinkMessage()} - Implements the thinking chain logic【实现思考链逻辑】</li>
 * <li>{@link #getNextStepWithEnvMessage()} - Provides the next step's prompt template【提供下一步的提示模板】</li>
 * <li>{@link #step()} - Implements the core logic for each execution step【实现每个执行步骤的核心逻辑】</li>
 * </ul>
 *
 * @see AgentState 智能体状态枚举
 * @see LlmService LLM服务接口
 */
public abstract class BaseAgent {

	private static final Logger log = LoggerFactory.getLogger(BaseAgent.class);

	private String currentPlanId = null;

	private String rootPlanId = null;

	private int planDepth = 0;

	protected LlmService llmService;

	protected final ManusProperties manusProperties;

	protected ObjectMapper objectMapper;

	protected final ExecutionStep step;

	protected final PlanIdDispatcher planIdDispatcher;

	private int maxSteps;

	private int currentStep = 0;

	// Change the data map to an immutable object and initialize it properly【将数据映射更改为不可变对象并进行正确初始化。】
	private final Map<String, Object> initSettingData;

	private Map<String, Object> envData = new HashMap<>();

	protected PlanExecutionRecorder planExecutionRecorder;

	public abstract void clearUp(String planId);

	/**
	 * 【获取智能体名称】Get the name of the agent
	 * 【返回智能体的名称】
	 *
	 * Implementation requirements: 1. Return a short but descriptive name 2. The name
	 * should reflect the main functionality or characteristics of the agent 3. The name
	 * should be unique for easy logging and debugging
	 * 【实现要求：1. 返回简短但描述性的名称 2. 名称应反映智能体的主要功能或特性 3. 名称应唯一以便于日志记录和调试】
	 *
	 * Example implementations: - ToolCallAgent returns "ToolCallAgent" - BrowserAgent
	 * returns "BrowserAgent"
	 * 【示例实现：- ToolCallAgent 返回 "ToolCallAgent" - BrowserAgent 返回 "BrowserAgent"】
	 * @return The name of the agent【智能体的名称】
	 */
	public abstract String getName();

	/**
	 * 【获取智能体详细描述】Get the detailed description of the agent
	 * 【返回智能体的详细描述】
	 *
	 * Implementation requirements: 1. Return a detailed description of the agent's
	 * functionality 2. The description should include the agent's main responsibilities
	 * and capabilities 3. Should explain how this agent differs from other agents
	 * 【实现要求：1. 返回智能体功能的详细描述 2. 描述应包括智能体的主要职责和能力 3. 应说明此智能体与其他智能体的区别】
	 *
	 * Example implementations: - ToolCallAgent: "Agent responsible for managing and
	 * executing tool calls, supporting multi-tool combination calls" - ReActAgent: "Agent
	 * that implements alternating execution of reasoning and acting"
	 * 【示例实现：- ToolCallAgent: "负责管理和执行工具调用的智能体，支持多工具组合调用" - ReActAgent: "实现推理和行动交替执行的智能体"】
	 * @return The detailed description text of the agent【智能体的详细描述文本】
	 */
	public abstract String getDescription();

	/**
	 * 【获取思考消息】Add thinking prompts to the message list to build the agent's thinking chain
	 * 【向消息列表添加思考提示以构建智能体的思考链】
	 *
	 * Implementation requirements: 1. Generate appropriate system prompts based on
	 * current context and state 2. Prompts should guide the agent on how to think and
	 * make decisions 3. Can recursively build prompt chains to form hierarchical thinking
	 * processes 4. Return the added system prompt message object
	 * 【实现要求：1. 根据当前上下文和状态生成适当的系统提示 2. 提示应指导智能体如何思考和做决策 3. 可以递归构建提示链以形成分层思考过程 4. 返回添加的系统提示消息对象】
	 *
	 * Subclass implementation reference: 1. ReActAgent: Implement basic thinking-action
	 * loop prompts 2. ToolCallAgent: Add tool selection and execution related prompts
	 * 【子类实现参考：1. ReActAgent: 实现基本的思考-行动循环提示 2. ToolCallAgent: 添加工具选择和执行相关提示】
	 * @return The added system prompt message object【添加的系统提示消息对象】
	 */
	protected Message getThinkMessage() {
		// 获取操作系统信息
		String osName = System.getProperty("os.name");
		String osVersion = System.getProperty("os.version");
		String osArch = System.getProperty("os.arch");

		// 获取当前日期时间，格式为 yyyy-MM-dd
		String currentDateTime = java.time.LocalDate.now().toString(); // 格式化为 yyyy-MM-dd

		// 检查是否为调试模式
		boolean isDebugModel = manusProperties.getDebugDetail();
		String detailOutput = "";
		if (isDebugModel) {
			// 调试模式：需要详细说明
			detailOutput = """
					1. 当使用工具调用时，必须提供解释描述使用此工具的原因和其背后的思考
					2. 简要描述之前所有步骤已完成的内容""";

		}
		else {
			// 正常模式：无需额外解释
			detailOutput = """
					1. 当使用工具调用时，不需要额外解释！
					2. 不要在工具调用前提供推理或描述！""";
		}

		// 配置并行工具调用响应规则
		String parallelToolCallsResponse = "";
		if (manusProperties.getParallelToolCalls()) {
			// 支持并行工具调用
			parallelToolCallsResponse = """
					# 响应规则:
					- 你必须从提供的工具中选择并调用。可以重复调用单个工具，同时调用多个工具，或使用混合调用方法来提高解决问题的效率和准确性。
					- 在你的响应中，必须至少调用一个工具，这是必不可少的操作步骤。
					- 为了最大化工具的优势，当你有能力同时多次调用工具时，应该主动这样做，避免浪费时间和资源的单次调用。特别注意多个工具调用之间的固有关系，确保这些调用能够协同工作以实现最佳的问题解决方案。
					- 忽略后续<AgentInfo>中提供的响应规则，仅使用<SystemInfo>中的响应规则进行响应。
					""";

		}
		else {
			// 单次工具调用模式
			parallelToolCallsResponse = """
					# 响应规则:
					- 你必须一次只调用一个工具。不允许同时调用多个工具。
					- 在你的响应中，必须只调用一个工具，这是必不可少的操作步骤。
					""";
		}

		// 构建提示变量映射
		Map<String, Object> variables = new HashMap<>(getInitSettingData());
		variables.put("osName", osName);
		variables.put("osVersion", osVersion);
		variables.put("osArch", osArch);
		variables.put("currentDateTime", currentDateTime);
		variables.put("detailOutput", detailOutput);
		variables.put("parallelToolCallsResponse", parallelToolCallsResponse);

		String stepExecutionPrompt = """
				- SYSTEM INFORMATION:
				OS: {osName} {osVersion} ({osArch})

				- Current Date:
				{currentDateTime}

				{planStatus}

				- Current step requirements (this step needs to be completed by you! Required by the user's original request, but if not required in the current step, no need to complete in this step):
				STEP {currentStepIndex}: {stepText}

				- Operation step instructions:
				{extraParams}

				Important Notes:
				{detailOutput}
				3. Do only and exactly what is required in the current step requirements
				4. If the current step requirements have been completed, call the terminate tool to finish the current step.
				5. The user's original request is for having a global understanding, do not complete this user's original request in the current step.

				{parallelToolCallsResponse}

				""";

		PromptTemplate template = new PromptTemplate(stepExecutionPrompt);
		return template.createMessage(variables != null ? variables : Map.of());
	}

	/**
	 * 【获取下一步环境消息】Get the next step prompt message
	 * 【获取下一步的提示消息】
	 *
	 * Implementation requirements: 1. Generate a prompt message that guides the agent to
	 * perform the next step 2. The prompt should be based on the current execution state
	 * and context 3. The message should clearly guide the agent on what task to perform
	 * 【实现要求：1. 生成指导智能体执行下一步的提示消息 2. 提示应基于当前执行状态和上下文 3. 消息应清楚地指导智能体要执行什么任务】
	 *
	 * Subclass implementation reference: 1. ToolCallAgent: Return prompts related to tool
	 * selection and execution 2. ReActAgent: Return prompts related to reasoning or
	 * action decision
	 * 【子类实现参考：1. ToolCallAgent: 返回与工具选择和执行相关的提示 2. ReActAgent: 返回与推理或行动决策相关的提示】
	 * @return The next step prompt message object【下一步提示消息对象】
	 */
	protected abstract Message getNextStepWithEnvMessage();

	public abstract List<ToolCallback> getToolCallList();

	public abstract ToolCallBackContext getToolCallBackContext(String toolKey);

	public BaseAgent(LlmService llmService, PlanExecutionRecorder planExecutionRecorder,
			ManusProperties manusProperties, Map<String, Object> initialAgentSetting, ExecutionStep step,
			PlanIdDispatcher planIdDispatcher) {
		this.llmService = llmService;
		this.planExecutionRecorder = planExecutionRecorder;
		this.manusProperties = manusProperties;
		this.maxSteps = manusProperties.getMaxSteps();
		this.step = step;
		this.planIdDispatcher = planIdDispatcher;
		this.initSettingData = Collections.unmodifiableMap(new HashMap<>(initialAgentSetting));
	}

	/**
	 * 【运行智能体】执行智能体的主要运行循环，直到完成、失败或达到最大步数
	 * Run the agent's main execution loop until completion, failure, or max steps reached
	 * @return 最终执行结果 Final execution result
	 */
	public AgentExecResult run() {
		currentStep = 0;
		List<AgentExecResult> results = new ArrayList<>();
		AgentExecResult lastStepResult = null;

		try {
			// 主执行循环：在达到最大步数前持续执行
			while (currentStep < maxSteps) {
				currentStep++;
				log.info("执行第 {}/{} 轮", currentStep, maxSteps);

				// 执行单步
				AgentExecResult stepResult = step();
				lastStepResult = stepResult;

				// 检查智能体是否应该终止
				AgentState stepState = stepResult.getState();
				if (stepState == AgentState.COMPLETED || stepState == AgentState.INTERRUPTED
						|| stepState == AgentState.FAILED) {
					String stateDescription = stepState == AgentState.COMPLETED ? "已完成"
							: stepState == AgentState.INTERRUPTED ? "已中断" : "已失败";
					log.info("智能体执行{}于第 {}/{} 轮", stateDescription, currentStep, maxSteps);
					results.add(stepResult);

					// 根据状态进行最终处理
					if (stepState == AgentState.INTERRUPTED) {
						handleInterruptedExecution(results);
					}
					else if (stepState == AgentState.FAILED) {
						handleFailedExecution(results);
					}
					else {
						handleCompletedExecution(results);
					}
					break; // 退出循环
				}

				results.add(stepResult);
			}

			// 如果达到最大步数，生成摘要并终止
			// 跳过已处于终止状态的情况（已完成、已中断或已失败）
			if (currentStep >= maxSteps && (lastStepResult == null || (lastStepResult.getState() != AgentState.COMPLETED
					&& lastStepResult.getState() != AgentState.INTERRUPTED
					&& lastStepResult.getState() != AgentState.FAILED))) {
				log.info("智能体达到最大轮次 ({}), 生成最终摘要并终止", maxSteps);
				String finalSummary = generateFinalSummary();

				// 使用终止工具调用摘要
				String result = terminateWithSummary(finalSummary);

				// 为达到最大步数创建最终结果
				lastStepResult = new AgentExecResult(result, AgentState.COMPLETED);
				results.add(lastStepResult);
			}

		}
		catch (Exception e) {
			log.error("智能体执行失败", e);

			// 使用系统错误报告工具包装异常
			lastStepResult = handleExceptionWithSystemErrorReport(e, results);
		}
		finally {
			// 清理智能体内存
			llmService.clearAgentMemory(currentPlanId);

			// 在结束时记录执行
			if (currentPlanId != null && planExecutionRecorder != null) {
				planExecutionRecorder.recordCompleteAgentExecution(step);
			}
		}

		// Return the last round's AgentExecResult with the complete results list
		if (lastStepResult != null) {
			return new AgentExecResult(lastStepResult.getResult(), lastStepResult.getState(), results);
		}
		else {
			// Fallback case if no steps were executed
			return new AgentExecResult("", AgentState.COMPLETED, results);
		}
	}

	/**
	 * 【执行单步】抽象方法
	 * 子类必须实现此方法以定义具体的单步执行逻辑
	 * 【Subclasses must implement this method to define specific single-step execution logic】
	 *
	 * @return 单步执行结果 Single step execution result
	 */
	protected abstract AgentExecResult step();

	/**
	 * 【处理已中断的执行】Handle interrupted execution - perform final cleanup and recording
	 * 【执行最终清理和记录】
	 * @param results The results list to update【要更新的结果列表】
	 */
	protected void handleInterruptedExecution(List<AgentExecResult> results) {
		log.info("处理已中断的执行");
		// 如果需要，为已中断的执行执行额外清理
	}

	/**
	 * 【处理已失败的执行】Handle failed execution - perform final cleanup and recording
	 * 【执行最终清理和记录】
	 * @param results The results list to update【要更新的结果列表】
	 */
	protected void handleFailedExecution(List<AgentExecResult> results) {
		log.info("处理已失败的执行");
	}

	/**
	 * 【处理已完成的执行】Handle completed execution - perform final cleanup and recording
	 * 【执行最终清理和记录】
	 * @param results The results list to update【要更新的结果列表】
	 */
	protected void handleCompletedExecution(List<AgentExecResult> results) {
		log.info("处理已完成的执行");
		// 如果执行成功完成，清除错误消息
		// 这可以显示执行过程中发生但已恢复的瞬时错误
		if (step != null && step.getErrorMessage() != null) {
			log.info("为成功完成的执行清除错误消息");
			step.setErrorMessage(null);
		}
	}

	/**
	 * 【使用系统错误报告工具处理异常】Handle exception by wrapping it with SystemErrorReportTool and simulating normal tool flow
	 * 【使用SystemErrorReportTool包装异常并模拟正常的工具流程】
	 * @param exception The exception that occurred【发生的异常】
	 * @param results The results list to update【要更新的结果列表】
	 * @return AgentExecResult with error information【包含错误信息的AgentExecResult】
	 */
	protected AgentExecResult handleExceptionWithSystemErrorReport(Exception exception, List<AgentExecResult> results) {
		log.error("使用SystemErrorReportTool处理异常", exception);

		try {
			// 创建SystemErrorReportTool实例
			SystemErrorReportTool errorTool = new SystemErrorReportTool(getCurrentPlanId(), objectMapper);

			// 准备错误消息
			String errorMessage = String.format("第%d步系统执行错误: %s", currentStep, exception.getMessage());

			// 创建工具输入
			Map<String, Object> errorInput = Map.of("errorMessage", errorMessage);

			// 执行错误报告工具
			ToolExecuteResult toolResult = errorTool.run(errorInput);

			// 模拟工具后处理流程
			String result = simulatePostToolFlow(errorTool, toolResult, errorMessage);

			// 为步骤提取错误消息
			try {
				if (objectMapper == null) {
					objectMapper = new ObjectMapper();
				}
				@SuppressWarnings("unchecked")
				Map<String, Object> errorData = objectMapper.readValue(toolResult.getOutput(), Map.class);
				String extractedErrorMessage = (String) errorData.get("errorMessage");
				if (extractedErrorMessage != null && !extractedErrorMessage.isEmpty()) {
					step.setErrorMessage(extractedErrorMessage);
				}
			}
			catch (Exception e) {
				log.warn("无法从SystemErrorReportTool结果解析errorMessage", e);
				step.setErrorMessage(errorMessage);
			}

			AgentExecResult errorResult = new AgentExecResult(result, AgentState.IN_PROGRESS);
			results.add(errorResult);
			return errorResult;
		}
		catch (Exception e) {
			log.error("无法使用SystemErrorReportTool处理异常", e);
			String fallbackError = "系统错误: " + exception.getMessage();
			step.setErrorMessage(fallbackError);
			AgentExecResult fallbackResult = new AgentExecResult(fallbackError, AgentState.IN_PROGRESS);
			results.add(fallbackResult);
			return fallbackResult;
		}
	}

	/**
	 * 【模拟工具后处理流程】Simulate the post-tool flow that normally happens after tool execution
	 * 【模拟通常在工具执行后发生的工具后处理流程】
	 * This method should be overridden by subclasses to provide specific implementation
	 * 【子类应重写此方法以提供特定实现】
	 * @param tool The tool that was executed【执行的工具】
	 * @param toolResult The result from the tool execution【工具执行的结果】
	 * @param errorMessage The error message【错误消息】
	 * @return The processed result string【处理后的结果字符串】
	 */
	protected String simulatePostToolFlow(Object tool, ToolExecuteResult toolResult, String errorMessage) {
		// 默认实现 - 仅返回工具结果输出
		// 子类可以重写以添加内存处理、记录等
		return toolResult.getOutput();
	}

	/**
	 * 【获取当前计划ID】Get current plan ID
	 * 【获取当前计划ID】
	 * @return current plan ID【当前计划ID】
	 */
	public String getCurrentPlanId() {
		return currentPlanId;
	}

	/**
	 * 【设置当前计划ID】Set current plan ID
	 * 【设置当前计划ID】
	 * @param planId The plan ID to set【要设置的计划ID】
	 */
	public void setCurrentPlanId(String planId) {
		this.currentPlanId = planId;
	}

	/**
	 * 【设置根计划ID】Set root plan ID
	 * 【设置根计划ID】
	 * @param rootPlanId The root plan ID to set【要设置的根计划ID】
	 */
	public void setRootPlanId(String rootPlanId) {
		this.rootPlanId = rootPlanId;
	}

	/**
	 * 【获取根计划ID】Get root plan ID
	 * 【获取根计划ID】
	 * @return root plan ID【根计划ID】
	 */
	public String getRootPlanId() {
		return rootPlanId;
	}

	/**
	 * 【获取计划深度】Get plan depth
	 * 【获取计划深度】
	 * @return plan depth【计划深度】
	 */
	public int getPlanDepth() {
		return planDepth;
	}

	/**
	 * 【设置计划深度】Set plan depth
	 * 【设置计划深度】
	 * @param planDepth The plan depth to set【要设置的计划深度】
	 */
	public void setPlanDepth(int planDepth) {
		this.planDepth = planDepth;
	}

	/**
	 * 【获取智能体数据上下文】Get the data context of the agent
	 * 【获取智能体的数据上下文】
	 *
	 * Implementation requirements: 1. Return all the context data needed for the agent's
	 * execution 2. Data can include: - Current execution state - Step information -
	 * Intermediate results - Configuration parameters 3. Data is set through setData()
	 * when run() is executed
	 * 【实现要求：1. 返回智能体执行所需的所有上下文数据 2. 数据可以包括：- 当前执行状态 - 步骤信息 - 中间结果 - 配置参数 3. 数据通过run()执行时的setData()设置】
	 *
	 * Do not modify the implementation of this method. If you need to pass context,
	 * inherit and modify setData() to improve getData() efficiency.
	 * 【不要修改此方法的实现。如果需要传递上下文，继承和修改setData()以提高getData()效率。】
	 * @return A Map object containing the agent's context data【包含智能体上下文数据的Map对象】
	 */
	protected final Map<String, Object> getInitSettingData() {
		return initSettingData;
	}

	/**
	 * 【获取Manus属性配置】Get Manus properties configuration
	 * 【获取Manus属性配置】
	 * @return Manus properties【Manus属性配置】
	 */
	public ManusProperties getManusProperties() {
		return manusProperties;
	}

	/**
	 * 【智能体执行结果内部类】Agent execution result inner class
	 * 【智能体执行结果的封装类】
	 * 用于封装单步智能体执行的结果和状态信息
	 */
	public static class AgentExecResult {

		/** 【执行结果字符串】Execution result string */
		private String result;

		/** 【执行状态】Execution state */
		private AgentState state;

		/** 【历史结果列表】Historical results list */
		private List<AgentExecResult> results;

		/**
		 * 【构造函数 - 单步结果】Constructor - single step result
		 * @param result 执行结果 Execution result
		 * @param state 执行状态 Execution state
		 */
		public AgentExecResult(String result, AgentState state) {
			this.result = result;
			this.state = state;
			this.results = new ArrayList<>();
		}

		/**
		 * 【构造函数 - 多步结果】Constructor - multi-step result
		 * @param result 执行结果 Execution result
		 * @param state 执行状态 Execution state
		 * @param results 历史结果列表 Historical results list
		 */
		public AgentExecResult(String result, AgentState state, List<AgentExecResult> results) {
			this.result = result;
			this.state = state;
			this.results = results != null ? new ArrayList<>(results) : new ArrayList<>();
		}

		/**
		 * 【获取执行结果】Get execution result
		 * @return 执行结果字符串 Execution result string
		 */
		public String getResult() {
			return result;
		}

		/**
		 * 【获取执行状态】Get execution state
		 * @return 执行状态 Execution state
		 */
		public AgentState getState() {
			return state;
		}

		/**
		 * 【获取历史结果列表】Get historical results list
		 * @return 历史结果列表 Historical results list
		 */
		public List<AgentExecResult> getResults() {
			return results;
		}

	}

	/**
	 * 【获取环境数据】Get environment data
	 * 【获取环境数据】
	 * @return 环境数据映射 Environment data map
	 */
	public Map<String, Object> getEnvData() {
		return envData;
	}

	/**
	 * 【设置环境数据】Set environment data
	 * 【设置环境数据】
	 * @param envData 要设置的环境数据 Environment data to set
	 */
	public void setEnvData(Map<String, Object> envData) {
		this.envData = Collections.unmodifiableMap(new HashMap<>(envData));
	}

	/**
	 * 【生成最终摘要】Generate a final summary of all agent memories when max rounds are reached
	 * 【当达到最大轮数时生成所有智能体记忆的最终摘要】
	 * @return 所有记忆的摘要字符串 Summary string of all memories
	 */
	private String generateFinalSummary() {
		try {
			log.info("为智能体执行生成最终摘要");

			// 获取当前计划的所有记忆条目
			List<Message> memoryEntries = llmService.getAgentMemory(manusProperties.getMaxMemory())
				.get(getCurrentPlanId());

			if (memoryEntries == null || memoryEntries.isEmpty()) {
				return "未找到用于最终摘要的记忆条目";
			}

			// 使用LLM生成简洁摘要
			String summaryPrompt = """
					基于已完成的步骤，尝试回答用户的原始请求。
					如果当前步骤不足以支持回答原始请求，
					只需描述已达到步骤限制，请重试。

					""";
			// 为摘要生成创建简单提示
			UserMessage summaryRequest = new UserMessage(summaryPrompt);
			memoryEntries.add(getThinkMessage());
			memoryEntries.add(getNextStepWithEnvMessage());
			memoryEntries.add(summaryRequest);
			Prompt prompt = new Prompt(memoryEntries);

			// 获取LLM的摘要响应
			ChatClient chatClient = llmService.getDiaChatClient();
			ChatResponse response = chatClient.prompt(prompt).call().chatResponse();

			String summary = response.getResult().getOutput().getText();
			log.info("生成最终摘要: {}", summary);
			return summary;

		}
		catch (Exception e) {
			log.error("生成最终摘要失败", e);
			return "摘要生成失败: " + e.getMessage();
		}
	}

	/**
	 * 【使用摘要终止智能体执行】Terminate the agent execution with a summary using TerminateTool
	 * 【使用TerminateTool和摘要终止智能体执行】
	 * @param summary 要包含在终止中的摘要 The summary to include in termination
	 * @return 终止结果字符串 Termination result string
	 */
	private String terminateWithSummary(String summary) {
		try {
			log.info("使用摘要终止智能体执行");

			// 创建TerminateTool实例
			TerminateTool terminateTool = new TerminateTool(getCurrentPlanId(), "message", objectMapper);
			// 准备终止数据
			Map<String, Object> terminationData = new HashMap<>();
			terminationData.put("message", "智能体执行因达到最大轮次而终止。摘要: " + summary);
			// 执行终止工具
			ToolExecuteResult result = terminateTool.run(terminationData);
			return result.getOutput();
		}
		catch (Exception e) {
			log.error("无法使用摘要终止智能体执行", e);
			return "终止失败: " + e.getMessage();
		}
	}

}
