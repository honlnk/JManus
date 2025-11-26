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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import com.alibaba.cloud.ai.manus.config.ManusProperties;
import com.alibaba.cloud.ai.manus.event.JmanusEventPublisher;
import com.alibaba.cloud.ai.manus.event.PlanExceptionClearedEvent;
import com.alibaba.cloud.ai.manus.llm.LlmService;
import com.alibaba.cloud.ai.manus.llm.StreamingResponseHandler;
import com.alibaba.cloud.ai.manus.planning.PlanningFactory.ToolCallBackContext;
import com.alibaba.cloud.ai.manus.recorder.service.PlanExecutionRecorder;
import com.alibaba.cloud.ai.manus.recorder.service.PlanExecutionRecorder.ActToolParam;
import com.alibaba.cloud.ai.manus.recorder.service.PlanExecutionRecorder.ThinkActRecordParams;
import com.alibaba.cloud.ai.manus.runtime.entity.vo.ExecutionStep;
import com.alibaba.cloud.ai.manus.runtime.executor.AbstractPlanExecutor;
import com.alibaba.cloud.ai.manus.runtime.service.AgentInterruptionHelper;
import com.alibaba.cloud.ai.manus.runtime.service.ParallelToolExecutionService;
import com.alibaba.cloud.ai.manus.runtime.service.PlanIdDispatcher;
import com.alibaba.cloud.ai.manus.runtime.service.TaskInterruptionCheckerService;
import com.alibaba.cloud.ai.manus.runtime.service.UserInputService;
import com.alibaba.cloud.ai.manus.tool.ErrorReportTool;
import com.alibaba.cloud.ai.manus.tool.FormInputTool;
import com.alibaba.cloud.ai.manus.tool.SystemErrorReportTool;
import com.alibaba.cloud.ai.manus.tool.TerminableTool;
import com.alibaba.cloud.ai.manus.tool.TerminateTool;
import com.alibaba.cloud.ai.manus.tool.ToolCallBiFunctionDef;
import com.alibaba.cloud.ai.manus.tool.code.ToolExecuteResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.common.util.StringUtils;
import reactor.core.publisher.Flux;

/**
 * 【动态智能体具体实现类】Concrete implementation of a dynamic intelligent agent.
 * 【动态智能体的具体实现类】
 *
 * This class extends ReActAgent to provide a complete implementation of the Think-Act pattern
 * with dynamic tool configuration, streaming response handling, and retry mechanisms.
 * 【此类继承自ReActAgent，提供完整的Think-Act模式实现，包含动态工具配置、流式响应处理和重试机制】
 *
 * Key features:
 * 【主要特性：】
 * - Dynamic tool configuration and management 【动态工具配置和管理】
 * - Streaming response handling for better user experience 【流式响应处理以提供更好的用户体验】
 * - Retry mechanism with exponential backoff 【带指数退避的重试机制】
 * - Parallel tool execution support 【并行工具执行支持】
 * - User input handling with timeout management 【带超时管理的用户输入处理】
 * - Memory management and conversation history 【内存管理和对话历史】
 */
public class DynamicAgent extends ReActAgent {

	/**
	 * 【当前步骤环境数据键名】Key for current step environment data in message metadata
	 * 【消息元数据中当前步骤环境数据的键名】
	 */
	private static final String CURRENT_STEP_ENV_DATA_KEY = "current_step_env_data";

	/**
	 * 【日志记录器】Logger for this class
	 * 【此类的日志记录器】
	 */
	private static final Logger log = LoggerFactory.getLogger(DynamicAgent.class);

	/**
	 * 【JSON对象映射器】ObjectMapper for JSON serialization/deserialization
	 * 【JSON序列化/反序列化的对象映射器】
	 */
	private final ObjectMapper objectMapper;

	/**
	 * 【智能体名称】Name of this agent
	 * 【此智能体的名称】
	 */
	private final String agentName;

	/**
	 * 【智能体描述】Description of this agent
	 * 【此智能体的描述】
	 */
	private final String agentDescription;

	/**
	 * 【下一步提示模板】Prompt template for next step guidance
	 * 【下一步指导的提示模板】
	 */
	private final String nextStepPrompt;

	/**
	 * 【工具回调提供者】Provider for tool callbacks
	 * 【工具回调的提供者】
	 */
	protected ToolCallbackProvider toolCallbackProvider;

	/**
	 * 【可用工具键列表】List of available tool keys
	 * 【可用工具键的列表】
	 */
	protected final List<String> availableToolKeys;

	/**
	 * 【聊天响应】Chat response from LLM
	 * 【来自LLM的聊天响应】
	 */
	private ChatResponse response;

	/**
	 * 【流式响应结果】Result of streaming response processing
	 * 【流式响应处理的结果】
	 */
	private StreamingResponseHandler.StreamingResult streamResult;

	/**
	 * 【用户提示】User prompt for LLM interaction
	 * 【与LLM交互的用户提示】
	 */
	private Prompt userPrompt;

	/**
	 * 【行动工具信息列表】List of action tool information
	 * 【行动工具信息的列表】
	 */
	private List<ActToolParam> actToolInfoList = new ArrayList<>();

	/**
	 * 【工具调用管理器】Manager for tool calling operations
	 * 【工具调用操作的管理器】
	 */
	private final ToolCallingManager toolCallingManager;

	/**
	 * 【用户输入服务】Service for handling user input
	 * 【处理用户输入的服务】
	 */
	private final UserInputService userInputService;

	/**
	 * 【模型名称】Name of the LLM model to use
	 * 【要使用的LLM模型名称】
	 */
	private final String modelName;

	/**
	 * 【流式响应处理器】Handler for streaming responses
	 * 【流式响应的处理器】
	 */
	private final StreamingResponseHandler streamingResponseHandler;

	/**
	 * 【JManus事件发布器】Publisher for JManus events
	 * 【JManus事件的发布器】
	 */
	private JmanusEventPublisher jmanusEventPublisher;

	/**
	 * 【智能体中断助手】Helper for agent interruption handling
	 * 【智能体中断处理的助手】
	 */
	private AgentInterruptionHelper agentInterruptionHelper;

	/**
	 * 【并行工具执行服务】Service for parallel tool execution
	 * 【并行工具执行的服务】
	 */
	private ParallelToolExecutionService parallelToolExecutionService;

	/**
	 * 【LLM调用异常列表】List to record all exceptions from LLM calls during retry attempts
	 * 【记录重试期间LLM调用所有异常的列表】
	 */
	private final List<Exception> llmCallExceptions = new ArrayList<>();

	/**
	 * 【最新LLM异常】Latest exception from LLM calls, used when max retries are reached
	 * 【LLM调用的最新异常，在达到最大重试次数时使用】
	 */
	private Exception latestLlmException = null;

	/**
	 * 【清理资源】Clean up resources associated with this agent and its tools.
	 * 【清理与此智能体及其工具相关的资源】
	 *
	 * This method is called when the agent is no longer needed to properly clean up
	 * resources and prevent memory leaks.
	 * 【当智能体不再需要时调用此方法，以正确清理资源并防止内存泄漏】
	 *
	 * @param planId 计划ID，用于标识需要清理的资源 Plan ID for identifying resources to clean up
	 */
	public void clearUp(String planId) {
		// 获取所有工具回调上下文 Get all tool callback contexts
		Map<String, ToolCallBackContext> toolCallBackContext = toolCallbackProvider.getToolCallBackContext();

		// 遍历并清理每个工具 Iterate through and clean up each tool
		for (ToolCallBackContext toolCallBack : toolCallBackContext.values()) {
			try {
				toolCallBack.getFunctionInstance().cleanup(planId);
			}
			catch (Exception e) {
				log.error("Error cleaning up tool callback context: {}", e.getMessage(), e);
			}
		}

		// 同时移除此根计划ID的任何待处理表单输入工具 Also remove any pending form input tool for this root plan ID
		if (userInputService != null) {
			String rootPlanId = getRootPlanId();
			if (rootPlanId != null) {
				userInputService.removeFormInputTool(rootPlanId);
			}
		}
	}

	/**
	 * 【构造函数】Constructor for DynamicAgent with all required dependencies.
	 * 【DynamicAgent的构造函数，包含所有必需的依赖项】
	 *
	 * @param llmService LLM服务，用于与语言模型交互 LLM service for interacting with language models
	 * @param planExecutionRecorder 计划执行记录器，用于记录执行过程 Plan execution recorder for recording execution process
	 * @param manusProperties Manus配置属性 Manus configuration properties
	 * @param name 智能体名称 Agent name
	 * @param description 智能体描述 Agent description
	 * @param nextStepPrompt 下一步指导提示 Prompt for next step guidance
	 * @param availableToolKeys 可用工具键列表 List of available tool keys
	 * @param toolCallingManager 工具调用管理器 Tool calling manager
	 * @param initialAgentSetting 初始智能体设置 Initial agent settings
	 * @param userInputService 用户输入服务 User input service
	 * @param modelName LLM模型名称 LLM model name
	 * @param streamingResponseHandler 流式响应处理器 Streaming response handler
	 * @param step 执行步骤 Execution step
	 * @param planIdDispatcher 计划ID分发器 Plan ID dispatcher
	 * @param jmanusEventPublisher JManus事件发布器 JManus event publisher
	 * @param agentInterruptionHelper 智能体中断助手 Agent interruption helper
	 * @param objectMapper JSON对象映射器 JSON object mapper
	 * @param parallelToolExecutionService 并行工具执行服务 Parallel tool execution service
	 */
	public DynamicAgent(LlmService llmService, PlanExecutionRecorder planExecutionRecorder,
			ManusProperties manusProperties, String name, String description, String nextStepPrompt,
			List<String> availableToolKeys, ToolCallingManager toolCallingManager,
			Map<String, Object> initialAgentSetting, UserInputService userInputService, String modelName,
			StreamingResponseHandler streamingResponseHandler, ExecutionStep step, PlanIdDispatcher planIdDispatcher,
			JmanusEventPublisher jmanusEventPublisher, AgentInterruptionHelper agentInterruptionHelper,
			ObjectMapper objectMapper, ParallelToolExecutionService parallelToolExecutionService) {
		// 调用父类构造函数 Call parent constructor
		super(llmService, planExecutionRecorder, manusProperties, initialAgentSetting, step, planIdDispatcher);

		// 初始化基本属性 Initialize basic properties
		this.objectMapper = objectMapper;
		super.objectMapper = objectMapper; // 同时设置父类的objectMapper Set parent's objectMapper as well

		// 设置智能体标识信息 Set agent identification information
		this.agentName = name;
		this.agentDescription = description;
		this.nextStepPrompt = nextStepPrompt;

		// 初始化可用工具列表 Initialize available tool list
		if (availableToolKeys == null) {
			this.availableToolKeys = new ArrayList<>();
		}
		else {
			this.availableToolKeys = availableToolKeys;
		}

		// 初始化服务组件 Initialize service components
		this.toolCallingManager = toolCallingManager;
		this.userInputService = userInputService;
		this.modelName = modelName;
		this.streamingResponseHandler = streamingResponseHandler;
		this.jmanusEventPublisher = jmanusEventPublisher;
		this.agentInterruptionHelper = agentInterruptionHelper;
		this.parallelToolExecutionService = parallelToolExecutionService;
	}

	/**
	 * 【执行思考过程】Execute the thinking process of the ReAct pattern.
	 * 【执行ReAct模式的思考过程】
	 *
	 * This method implements the "Think" part of the Think-Act pattern. It analyzes the current
	 * state, collects environment data, and decides what tools to use next.
	 * 【此方法实现了Think-Act模式的"思考"部分。它分析当前状态、收集环境数据并决定接下来使用哪些工具】
	 *
	 * Key steps:
	 * 【关键步骤：】
	 * 1. Check for interruption 【检查中断】
	 * 2. Collect environment data for tools 【为工具收集环境数据】
	 * 3. Execute LLM call with retry mechanism 【使用重试机制执行LLM调用】
	 * 4. Process response and determine tool selection 【处理响应并确定工具选择】
	 *
	 * @return true if tools should be executed (action needed), false if no action needed
	 *         【如果应该执行工具（需要行动）返回true，如果不需要行动返回false】
	 */
	@Override
	protected boolean think() {
		// 在开始思考过程前检查中断 Check for interruption before starting thinking process
		if (agentInterruptionHelper != null && !agentInterruptionHelper.checkInterruptionAndContinue(getRootPlanId())) {
			log.info("Agent {} thinking process interrupted for rootPlanId: {}", getName(), getRootPlanId());
			// 抛出异常以表示中断，而不是返回false Throw exception to signal interruption instead of returning false
			throw new TaskInterruptionCheckerService.TaskInterruptedException(
					"Agent thinking interrupted for rootPlanId: " + getRootPlanId());
		}

		// 为工具收集并设置环境数据 Collect and set environment data for tools
		collectAndSetEnvDataForTools();

		try {
			// 使用重试机制执行（最多3次） Execute with retry mechanism (maximum 3 times)
			boolean result = executeWithRetry(3);
			// 如果重试用尽且有异常，结果将为false，latestLlmException将被设置
			// If retries exhausted and we have exceptions, the result will be false and latestLlmException will be set
			return result;
		}
		catch (TaskInterruptionCheckerService.TaskInterruptedException e) {
			log.info("Agent {} thinking process interrupted: {}", getName(), e.getMessage());
			throw e; // 重新抛出中断异常 Re-throw the interruption exception
		}
		catch (Exception e) {
			// 记录思考过程中的异常 Record exception during thinking process
			log.error(String.format("🚨 Oops! The %s's thinking process hit a snag: %s", getName(), e.getMessage()), e);
			log.info("Exception occurred", e);
			// 同时记录此异常 Also record this exception
			latestLlmException = e;
			llmCallExceptions.add(e);
			return false; // 返回false表示不需要行动 Return false to indicate no action needed
		}
	}

	/**
	 * 【带重试机制的执行】Execute thinking process with retry mechanism.
	 * 【使用重试机制执行思考过程】
	 *
	 * This method implements a robust retry mechanism with exponential backoff for handling
	 * transient failures during LLM calls.
	 * 【此方法实现了健壮的重试机制，使用指数退避处理LLM调用期间的瞬时故障】
	 *
	 * @param maxRetries 最大重试次数 Maximum number of retry attempts
	 * @return true if successful and tools should be executed, false if all retries failed
	 *         【如果成功且应该执行工具返回true，如果所有重试都失败返回false】
	 * @throws Exception if non-retryable error occurs or interruption happens
	 *         【如果发生不可重试的错误或中断则抛出异常】
	 */
	private boolean executeWithRetry(int maxRetries) throws Exception {
		int attempt = 0;
		Exception lastException = null;

		// 在重试周期开始时清除异常列表 Clear exception list at the start of retry cycle
		llmCallExceptions.clear();
		latestLlmException = null;

		while (attempt < maxRetries) {
			attempt++;

			// 在每次重试尝试前检查中断 Check for interruption before each retry attempt
			if (agentInterruptionHelper != null
					&& !agentInterruptionHelper.checkInterruptionAndContinue(getRootPlanId())) {
				log.info("Agent {} retry process interrupted at attempt {}/{} for rootPlanId: {}", getName(), attempt,
						maxRetries, getRootPlanId());
				throw new TaskInterruptionCheckerService.TaskInterruptedException(
						"Agent thinking interrupted at attempt " + attempt);
			}

			try {
				log.info("Attempt {}/{}: Executing agent thinking process", attempt, maxRetries);

				// 准备系统消息 Prepare system message
				Message systemMessage = getThinkMessage();

				// 使用当前环境作为用户消息 Use current env as user message
				Message currentStepEnvMessage = currentStepEnvMessage();

				// 记录思考消息 Record think message
				List<Message> thinkMessages = Arrays.asList(systemMessage, currentStepEnvMessage);
				String thinkInput = thinkMessages.toString();

				// 构建当前提示。系统消息是第一条消息 Build current prompt. System message is the first message
				List<Message> messages = new ArrayList<>(Collections.singletonList(systemMessage));

				// 添加历史消息 Add history message
				ChatMemory chatMemory = llmService.getAgentMemory(manusProperties.getMaxMemory());
				List<Message> historyMem = chatMemory.get(getCurrentPlanId());
				messages.addAll(historyMem);
				messages.add(currentStepEnvMessage);

				// 生成工具调用ID Generate tool call ID
				String toolcallId = planIdDispatcher.generateToolCallId();

				// 调用LLM Call the LLM
				Map<String, Object> toolContextMap = new HashMap<>();
				toolContextMap.put("toolcallId", toolcallId);
				toolContextMap.put("planDepth", getPlanDepth());
				ToolCallingChatOptions chatOptions = ToolCallingChatOptions.builder()
					.internalToolExecutionEnabled(false)
					.toolContext(toolContextMap)
					// 工具调用选项目前不支持：
					// can't support by toolcall options :
					// .parallelToolCalls(manusProperties.getParallelToolCalls())
					.build();

				userPrompt = new Prompt(messages, chatOptions);
				List<ToolCallback> callbacks = getToolCallList();

				// 获取聊天客户端 Get chat client
				ChatClient chatClient;
				if (modelName == null || modelName.isEmpty()) {
					chatClient = llmService.getDefaultDynamicAgentChatClient();
				}
				else {
					chatClient = llmService.getDynamicAgentChatClient(modelName);
				}

				// 使用流式响应处理器以获得更好的用户体验和内容合并
				// Use streaming response handler for better user experience and content merging
				Flux<ChatResponse> responseFlux = chatClient.prompt(userPrompt)
					.toolCallbacks(callbacks)
					.stream()
					.chatResponse();

				boolean isDebugModel = manusProperties.getDebugDetail() != null && manusProperties.getDebugDetail();

				// 启用智能体思考的早期终止（应该有工具调用）
				// Enable early termination for agent thinking (should have tool calls)
				streamResult = streamingResponseHandler.processStreamingResponse(responseFlux,
						"Agent " + getName() + " thinking", getCurrentPlanId(), isDebugModel, true);

				response = streamResult.getLastResponse();

				// 使用来自流处理器的合并内容 Use merged content from streaming handler
				List<ToolCall> toolCalls = streamResult.getEffectiveToolCalls();
				String responseByLLm = streamResult.getEffectiveText();

				log.info(String.format("✨ %s's thoughts: %s", getName(), responseByLLm));
				log.info(String.format("🛠️ %s selected %d tools to use", getName(), toolCalls.size()));

				if (!toolCalls.isEmpty()) {
					log.info(String.format("🧰 Tools being prepared: %s",
							toolCalls.stream().map(ToolCall::name).collect(Collectors.toList())));

					// 记录思考-行动过程 Record think-act process
					String stepId = super.step.getStepId();
					String thinkActId = planIdDispatcher.generateThinkActId();

					actToolInfoList = new ArrayList<>();
					for (ToolCall toolCall : toolCalls) {
						ActToolParam actToolInfo = new ActToolParam(toolCall.name(), toolCall.arguments(), toolcallId);
						actToolInfoList.add(actToolInfo);
					}

					ThinkActRecordParams paramsN = new ThinkActRecordParams(thinkActId, stepId, thinkInput,
							responseByLLm, null, actToolInfoList);
					planExecutionRecorder.recordThinkingAndAction(step, paramsN);

					// 如果这是重试尝试，清除异常缓存 Clear exception cache if this was a retry attempt
					if (attempt > 1 && jmanusEventPublisher != null) {
						log.info("Retry successful for planId: {}, clearing exception cache", getCurrentPlanId());
						jmanusEventPublisher.publish(new PlanExceptionClearedEvent(getCurrentPlanId()));
					}

					return true; // 成功，应该执行工具 Successful, should execute tools
				}

				log.warn("Attempt {}: No tools selected. Retrying...", attempt);

			}
			catch (Exception e) {
				lastException = e;
				latestLlmException = e;

				// 将异常记录到列表中（记录所有异常，包括不可重试的）
				// Record exception to the list (record all exceptions, even non-retryable ones)
				llmCallExceptions.add(e);
				log.warn("Attempt {} failed: {}", attempt, e.getMessage());
				log.debug("Exception details for attempt {}: {}", attempt, e.getMessage(), e);

				// 检查是否是应该重试的网络相关错误 Check if this is a network-related error that should be retried
				if (isRetryableException(e)) {
					if (attempt < maxRetries) {
						long waitTime = calculateBackoffDelay(attempt);
						log.info("Retrying in {}ms due to retryable error: {}", waitTime, e.getMessage());
						try {
							Thread.sleep(waitTime);
						}
						catch (InterruptedException ie) {
							Thread.currentThread().interrupt();
							throw new Exception("Retry interrupted", ie);
						}
						continue; // 继续下一次重试 Continue to next retry
					}
				}
				else {
					// 不可重试的错误 - 仍然记录，但立即抛出 Non-retryable error - still record it, but throw immediately
					log.error("Non-retryable error encountered at attempt {}/{}: {}", attempt, maxRetries,
							e.getMessage());
					throw e;
				}
			}
		}

		// 所有重试都用尽了 All retries exhausted
		if (lastException != null) {
			log.error("All {} retry attempts failed. Total exceptions recorded: {}. Latest exception: {}", maxRetries,
					llmCallExceptions.size(), latestLlmException != null ? latestLlmException.getMessage() : "N/A");
			// 存储最新的异常供step()方法使用 Store the latest exception for use in step() method
			// 不要在这里抛出异常，让think()返回false，由step()处理
			// Don't throw exception here, let think() return false and step() handle it
			return false;
		}

		return false;
	}

	/**
	 * 【检查异常是否可重试】Check if the exception is retryable (network issues, timeouts, etc.).
	 * 【检查异常是否可重试（网络问题、超时等）】
	 *
	 * @param e 要检查的异常 The exception to check
	 * @return true if the exception is retryable, false otherwise
	 *         【如果异常可重试返回true，否则返回false】
	 */
	private boolean isRetryableException(Exception e) {
		String message = e.getMessage();
		if (message == null)
			return false;

		// 检查网络相关错误 Check for network-related errors
		return message.contains("Failed to resolve") || message.contains("timeout") || message.contains("connection")
				|| message.contains("DNS") || message.contains("WebClientRequestException")
				|| message.contains("DnsNameResolverTimeoutException");
	}

	/**
	 * 【计算指数退避延迟】Calculate exponential backoff delay.
	 * 【计算指数退避延迟时间】
	 *
	 * @param attempt 当前尝试次数 Current attempt number (1-based)
	 * @return 延迟时间（毫秒）Delay time in milliseconds
	 */
	private long calculateBackoffDelay(int attempt) {
		// 指数退避：2^attempt * 2000ms，最大60秒
		// Exponential backoff: 2^attempt * 2000ms, max 60 seconds
		long delay = Math.min(2000L * (1L << (attempt - 1)), 60000L);
		return delay;
	}

	/**
	 * 【执行完整步骤】Execute a complete think-act step.
	 * 【执行完整的思考-行动步骤】
	 *
	 * This method implements the main execution logic of the ReAct pattern by combining
	 * the think() and act() methods. It handles various error conditions and provides
	 * appropriate responses.
	 * 【此方法通过结合think()和act()方法实现了ReAct模式的主要执行逻辑。它处理各种错误条件并提供适当的响应】
	 *
	 * @return 包含执行结果和状态的AgentExecResult AgentExecResult containing execution result and state
	 */
	@Override
	public AgentExecResult step() {
		try {
			// 执行思考过程 Execute thinking process
			boolean shouldAct = think();

			if (!shouldAct) {
				// 检查是否有来自LLM调用的最新异常（已达到最大重试次数）
				// Check if we have a latest exception from LLM calls (max retries reached)
				if (latestLlmException != null) {
					log.error(
							"Agent {} thinking failed after all retries. Simulating full flow with SystemErrorReportTool",
							getName());
					return handleLlmTimeoutWithSystemErrorReport();
				}

				// 正常情况：思考完成，无需行动 Normal case: thinking complete, no action needed
				return new AgentExecResult("Thinking complete - no action needed", AgentState.IN_PROGRESS);
			}

			// 执行行动 Execute action
			return act();
		}
		catch (TaskInterruptionCheckerService.TaskInterruptedException e) {
			// 智能体被中断，返回INTERRUPTED状态以停止执行
			// Agent was interrupted, return INTERRUPTED state to stop execution
			return new AgentExecResult("Agent execution interrupted: " + e.getMessage(), AgentState.INTERRUPTED);
		}
		catch (Exception e) {
			log.error("Unexpected exception in step()", e);
			// 使用系统错误报告工具处理异常 Handle exception with SystemErrorReportTool
			return handleExceptionWithSystemErrorReport(e, new ArrayList<>());
		}
	}

	/**
	 * 【获取LLM调用异常列表】Get the list of all exceptions recorded during LLM calls.
	 * 【获取LLM调用期间记录的所有异常的列表】
	 *
	 * @return 异常列表的副本（如果没有发生异常可能为空） List of exceptions (may be empty if no exceptions occurred)
	 */
	public List<Exception> getLlmCallExceptions() {
		return new ArrayList<>(llmCallExceptions); // 返回副本以防止外部修改 Return a copy to prevent external modification
	}

	/**
	 * 【获取最新LLM异常】Get the latest exception from LLM calls.
	 * 【获取LLM调用的最新异常】
	 *
	 * @return 最新异常，如果没有发生异常则返回null Latest exception, or null if no exceptions occurred
	 */
	public Exception getLatestLlmException() {
		return latestLlmException;
	}

	/**
	 * Build error message from the latest exception
	 * @return Formatted error message with exception details
	 */
	private String buildErrorMessageFromLatestException() {
		if (latestLlmException == null) {
			return "Unknown error occurred during LLM call";
		}

		StringBuilder errorMessage = new StringBuilder();
		errorMessage.append("LLM call failed after all retry attempts. ");

		// Add exception type and message
		String exceptionType = latestLlmException.getClass().getSimpleName();
		String exceptionMessage = latestLlmException.getMessage();

		errorMessage.append("Latest error: [").append(exceptionType).append("] ").append(exceptionMessage);

		// Add exception count information
		if (!llmCallExceptions.isEmpty()) {
			errorMessage.append(" (Total attempts: ").append(llmCallExceptions.size()).append(")");
		}

		// Add detailed error information for WebClientResponseException
		if (latestLlmException instanceof org.springframework.web.reactive.function.client.WebClientResponseException webClientException) {
			String responseBody = webClientException.getResponseBodyAsString();
			if (responseBody != null && !responseBody.isEmpty()) {
				errorMessage.append(". API Response: ").append(responseBody);
			}
		}

		return errorMessage.toString();
	}

	/**
	 * 【执行具体行动】Execute specific actions based on thinking results.
	 * 【基于思考结果执行具体行动】
	 *
	 * This method implements the "Act" part of the Think-Act pattern. It executes the tools
	 * selected during the thinking phase and handles both single and multiple tool scenarios.
	 * 【此方法实现了Think-Act模式的"行动"部分。它执行在思考阶段选择的工具，并处理单工具和多工具场景】
	 *
	 * @return 包含执行结果和状态的AgentExecResult AgentExecResult containing execution result and state
	 */
	@Override
	protected AgentExecResult act() {
		// 在开始行动过程前检查中断 Check for interruption before starting action process
		if (agentInterruptionHelper != null && !agentInterruptionHelper.checkInterruptionAndContinue(getRootPlanId())) {
			log.info("Agent {} action process interrupted for rootPlanId: {}", getName(), getRootPlanId());
			return new AgentExecResult("Action interrupted by user", AgentState.INTERRUPTED);
		}

		try {
			// 获取有效工具调用 Get effective tool calls
			List<ToolCall> toolCalls = streamResult.getEffectiveToolCalls();

			// 根据工具数量路由到适当的处理器 Route to appropriate handler based on tool count
			if (toolCalls == null || toolCalls.isEmpty()) {
				return new AgentExecResult("tool call is empty , please retry", AgentState.IN_PROGRESS);
			}
			else if (toolCalls.size() == 1) {
				// 单工具执行 - 核心逻辑 Single tool execution - core logic
				return processSingleTool(toolCalls.get(0));
			}
			else {
				// 多工具执行 Multiple tools execution
				return processMultipleTools(toolCalls);
			}
		}
		catch (Exception e) {
			// 处理工具执行错误 Handle tool execution errors
			log.error("Error executing tools: {}", e.getMessage(), e);

			StringBuilder errorMessage = new StringBuilder("Error executing tools: ");
			errorMessage.append(e.getMessage());

			String firstToolcall = actToolInfoList != null && !actToolInfoList.isEmpty()
					&& actToolInfoList.get(0).getParameters() != null
							? actToolInfoList.get(0).getParameters().toString() : "unknown";
			errorMessage.append("  . llm return param :  ").append(firstToolcall);

			// 出错时使用根计划ID清理表单输入工具 Clean up form input tool using root plan ID on error
			String rootPlanId = getRootPlanId();
			if (rootPlanId != null) {
				userInputService.removeFormInputTool(rootPlanId);
			}

			return new AgentExecResult(e.getMessage(), AgentState.COMPLETED);
		}
	}

	/**
	 * Process a single tool execution This is the core logic for tool execution
	 * @param toolCall The tool call to execute
	 * @return AgentExecResult containing the execution result
	 */
	private AgentExecResult processSingleTool(ToolCall toolCall) {
		ToolExecutionResult toolExecutionResult = null;
		try {
			// Check for interruption before tool execution
			if (agentInterruptionHelper != null
					&& !agentInterruptionHelper.checkInterruptionAndContinue(getRootPlanId())) {
				log.info("Agent {} tool execution interrupted for rootPlanId: {}", getName(), getRootPlanId());
				return new AgentExecResult("Tool execution interrupted by user", AgentState.INTERRUPTED);
			}

			// Execute tool call
			toolExecutionResult = toolCallingManager.executeToolCalls(userPrompt, response);
			processMemory(toolExecutionResult);

			// Get tool response message
			ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult.conversationHistory()
				.get(toolExecutionResult.conversationHistory().size() - 1);

			if (toolResponseMessage.getResponses().isEmpty()) {
				return new AgentExecResult("Tool response is empty", AgentState.IN_PROGRESS);
			}

			// Process single tool response
			ToolResponseMessage.ToolResponse toolCallResponse = toolResponseMessage.getResponses().get(0);
			String toolName = toolCall.name();
			ActToolParam param = actToolInfoList.get(0);
			ToolCallBiFunctionDef<?> toolInstance = getToolCallBackContext(toolName).getFunctionInstance();

			String result;
			boolean shouldTerminate = false;

			// Handle different tool types
			if (toolInstance instanceof FormInputTool) {
				AgentExecResult formResult = handleFormInputTool((FormInputTool) toolInstance, param);
				result = formResult.getResult();
				param.setResult(result);
			}
			else if (toolInstance instanceof TerminableTool) {
				TerminableTool terminableTool = (TerminableTool) toolInstance;
				result = processToolResult(toolCallResponse.responseData());
				param.setResult(result);

				// Handle TerminateTool specifically - set state to COMPLETED
				if (toolInstance instanceof TerminateTool) {
					log.info("TerminateTool called for planId: {}", getCurrentPlanId());
					shouldTerminate = true;
				}
				// Handle ErrorReportTool specifically to extract errorMessage
				else if (toolInstance instanceof ErrorReportTool) {
					String errorMessage = extractAndSetErrorMessage(result, "ErrorReportTool");
					recordErrorToolThinkingAndAction(param, "Error occurred during execution",
							"ErrorReportTool called to report error", errorMessage);
				}

				if (terminableTool.canTerminate()) {
					log.info("TerminableTool can terminate for planId: {}", getCurrentPlanId());
					String rootPlanId = getRootPlanId();
					if (rootPlanId != null) {
						userInputService.removeFormInputTool(rootPlanId);
					}
					shouldTerminate = true;
				}
				else {
					log.info("TerminableTool cannot terminate yet for planId: {}", getCurrentPlanId());
				}
			}
			// Handle SystemErrorReportTool specifically to extract errorMessage
			else if (toolInstance instanceof SystemErrorReportTool) {
				result = processToolResult(toolCallResponse.responseData());
				param.setResult(result);
				String errorMessage = extractAndSetErrorMessage(result, "SystemErrorReportTool");
				recordErrorToolThinkingAndAction(param, "System error occurred during execution",
						"SystemErrorReportTool called to report system error", errorMessage);
			}
			else {
				// Regular tool
				result = processToolResult(toolCallResponse.responseData());
				param.setResult(result);
				log.info("Tool {} executed successfully for planId: {}", toolName, getCurrentPlanId());
			}

			// Execute shared post-tool flow
			executePostToolFlow(toolInstance, toolCallResponse, result, List.of(param));

			// Return result with appropriate state
			return new AgentExecResult(result, shouldTerminate ? AgentState.COMPLETED : AgentState.IN_PROGRESS);
		}
		catch (Exception e) {
			log.error("Error executing single tool: {}", e.getMessage(), e);
			processMemory(toolExecutionResult); // Process memory even on error
			// For other errors, wrap exception with SystemErrorReportTool
			List<AgentExecResult> emptyResults = new ArrayList<>();
			return handleExceptionWithSystemErrorReport(e, emptyResults);
		}
	}

	/**
	 * Process multiple tools execution using parallel execution service Multiple tools
	 * execution does not support TerminableTool and FormInputTool. If these tools are
	 * present, return error message asking LLM to retry without them.
	 * @param toolCalls List of tool calls to execute
	 * @return AgentExecResult containing the execution results
	 */
	private AgentExecResult processMultipleTools(List<ToolCall> toolCalls) {
		// Check for interruption before starting
		if (agentInterruptionHelper != null && !agentInterruptionHelper.checkInterruptionAndContinue(getRootPlanId())) {
			log.info("Agent {} tool execution interrupted before starting for rootPlanId: {}", getName(),
					getRootPlanId());
			return new AgentExecResult("Tool execution interrupted by user", AgentState.INTERRUPTED);
		}

		try {
			// Check for TerminableTool and FormInputTool in multiple tools
			List<String> restrictedToolNames = new ArrayList<>();
			for (ToolCall toolCall : toolCalls) {
				String toolName = toolCall.name();
				ToolCallBackContext context = getToolCallBackContext(toolName);
				if (context != null) {
					ToolCallBiFunctionDef<?> toolInstance = context.getFunctionInstance();
					if (toolInstance instanceof TerminableTool || toolInstance instanceof FormInputTool) {
						restrictedToolNames.add(toolName);
					}
				}
			}

			// If restricted tools found, return error asking LLM to retry without them
			if (!restrictedToolNames.isEmpty()) {
				String errorMessage = String.format(
						"Multiple tools execution does not support TerminableTool and FormInputTool. "
								+ "Found restricted tools: %s. Please retry by calling tools separately, "
								+ "excluding TerminableTool and FormInputTool from multiple tool calls.",
						String.join(", ", restrictedToolNames));
				log.warn("Multiple tools execution rejected: {}", errorMessage);
				return new AgentExecResult(errorMessage, AgentState.IN_PROGRESS);
			}

			// Execute all tools in parallel
			if (parallelToolExecutionService == null) {
				log.error("ParallelToolExecutionService is not available");
				return new AgentExecResult("Parallel execution service is not available", AgentState.COMPLETED);
			}

			Map<String, ToolCallBackContext> toolCallbackMap = toolCallbackProvider.getToolCallBackContext();
			Map<String, Object> toolContextMap = new HashMap<>();
			toolContextMap.put("toolcallId", planIdDispatcher.generateToolCallId());
			toolContextMap.put("planDepth", getPlanDepth());
			ToolContext parentToolContext = new ToolContext(toolContextMap);

			List<ParallelToolExecutionService.ToolExecutionResult> parallelResults = parallelToolExecutionService
				.executeToolsInParallel(toolCalls, toolCallbackMap, planIdDispatcher, parentToolContext);
			log.info("Executed {} tools in parallel", parallelResults.size());

			// Process results and update actToolInfoList
			List<String> resultList = new ArrayList<>();
			for (int i = 0; i < toolCalls.size() && i < actToolInfoList.size(); i++) {
				ToolCall toolCall = toolCalls.get(i);
				String toolName = toolCall.name();
				ActToolParam param = actToolInfoList.get(i);

				// Find corresponding result
				String processedResult = null;
				for (ParallelToolExecutionService.ToolExecutionResult result : parallelResults) {
					if (result.getToolName().equals(toolName)) {
						if (result.isSuccess()) {
							processedResult = processToolResult(result.getResult().getOutput());
						}
						else {
							processedResult = "Error: " + result.getResult().getOutput();
						}
						break;
					}
				}

				if (processedResult == null) {
					processedResult = "Tool execution result not found";
					log.warn("Result not found for tool: {}", toolName);
				}

				param.setResult(processedResult);
				resultList.add(processedResult);
				log.info("Tool {} executed successfully for planId: {}", toolName, getCurrentPlanId());
			}

			// Record the results
			recordActionResult(actToolInfoList);

			// Update memory using ToolCallingManager (for compatibility)
			try {
				ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(userPrompt, response);
				processMemory(toolExecutionResult);
			}
			catch (Exception e) {
				log.warn("Error processing memory after parallel execution: {}", e.getMessage());
			}

			// Return result
			return new AgentExecResult(resultList.toString(), AgentState.IN_PROGRESS);
		}
		catch (Exception e) {
			log.error("Error executing multiple tools: {}", e.getMessage(), e);
			return new AgentExecResult("Error executing tools: " + e.getMessage(), AgentState.IN_PROGRESS);
		}
	}

	/**
	 * Handle FormInputTool specific logic with exclusive storage
	 */
	private AgentExecResult handleFormInputTool(FormInputTool formInputTool, ActToolParam param) {
		// Ensure the form input tool has the correct plan IDs set
		formInputTool.setCurrentPlanId(getCurrentPlanId());
		formInputTool.setRootPlanId(getRootPlanId());

		// Check if the tool is waiting for user input
		if (formInputTool.getInputState() == FormInputTool.InputState.AWAITING_USER_INPUT) {
			String rootPlanId = getRootPlanId();
			String currentPlanId = getCurrentPlanId();
			log.info("FormInputTool is awaiting user input for rootPlanId: {} (currentPlanId: {})", rootPlanId,
					currentPlanId);

			// Use exclusive storage method - this will handle waiting and queuing
			// automatically
			boolean stored = userInputService.storeFormInputToolExclusive(rootPlanId, formInputTool, currentPlanId);
			if (!stored) {
				log.error("Failed to store form for sub-plan {} due to lock timeout or interruption", currentPlanId);
				param.setResult("Failed to store form due to system timeout");
				return new AgentExecResult("Failed to store form due to system timeout", AgentState.COMPLETED);
			}

			// Wait for user input or timeout
			waitForUserInputOrTimeout(formInputTool);

			// After waiting, check the state again
			if (formInputTool.getInputState() == FormInputTool.InputState.INPUT_RECEIVED) {
				log.info("User input received for rootPlanId: {} from sub-plan {}", rootPlanId, currentPlanId);

				UserMessage userMessage = UserMessage.builder()
					.text("User input received for form: " + formInputTool.getCurrentToolStateString())
					.build();
				processUserInputToMemory(userMessage);

				// Update the result in actToolInfoList
				param.setResult(formInputTool.getCurrentToolStateString());
				return new AgentExecResult(param.getResult(), AgentState.IN_PROGRESS);

			}
			else if (formInputTool.getInputState() == FormInputTool.InputState.INPUT_TIMEOUT) {
				log.warn("Input timeout occurred for FormInputTool for rootPlanId: {} from sub-plan {}", rootPlanId,
						currentPlanId);

				UserMessage userMessage = UserMessage.builder().text("Input timeout occurred for form: ").build();
				processUserInputToMemory(userMessage);
				userInputService.removeFormInputTool(rootPlanId);
				param.setResult("Input timeout occurred");

				return new AgentExecResult("Input timeout occurred.", AgentState.IN_PROGRESS);
			}
			else {
				throw new RuntimeException("FormInputTool is not in the correct state");
			}
		}
		else {
			throw new RuntimeException("FormInputTool is not in the correct state");
		}
	}

	/**
	 * Process tool result to remove escaped JSON if it's a valid JSON string. This fixes
	 * the issue where DefaultToolCallingManager returns escaped JSON strings.
	 * @param result The raw tool result string
	 * @return Processed result with unescaped JSON if applicable
	 */
	private String processToolResult(String result) {
		if (result == null || result.trim().isEmpty()) {
			return result;
		}

		// Try to parse and re-serialize if it's a valid JSON string
		// This removes escaping that might have been added by DefaultToolCallingManager
		try {
			// First, try to parse as JSON object
			Object jsonObject = objectMapper.readValue(result, Object.class);

			// Check if it's a Map with "output" field (from DefaultToolCallingManager
			// format)
			if (jsonObject instanceof Map<?, ?> map) {
				Object outputValue = map.get("output");
				if (outputValue instanceof String outputString) {
					// The output field contains an escaped JSON string, parse it
					try {
						Object innerJsonObject = objectMapper.readValue(outputString, Object.class);
						// Create a new map with the parsed inner JSON object, preserving
						// the "output" field
						Map<String, Object> resultMap = new HashMap<>();
						// Copy all entries from the original map
						for (Map.Entry<?, ?> entry : map.entrySet()) {
							if (entry.getKey() instanceof String key) {
								resultMap.put(key, entry.getValue());
							}
						}
						resultMap.put("output", innerJsonObject);
						// Return the unescaped JSON string with output field preserved
						return objectMapper.writeValueAsString(resultMap);
					}
					catch (Exception innerException) {
						// If inner parsing fails, return the original map as-is
						return objectMapper.writeValueAsString(jsonObject);
					}
				}
				else {
					// It's a Map but no "output" field or output is not a string,
					// re-serialize as-is
					return objectMapper.writeValueAsString(jsonObject);
				}
			}
			// If the parsed object is a String, it means the input was a JSON string
			// (e.g., "\"{\\\"message\\\":[...]}\""), so we need to parse it again
			else if (jsonObject instanceof String jsonString) {
				// Try to parse the inner JSON string
				try {
					Object innerJsonObject = objectMapper.readValue(jsonString, Object.class);
					// Re-serialize the inner JSON object
					return objectMapper.writeValueAsString(innerJsonObject);
				}
				catch (Exception innerException) {
					// If inner parsing fails, return the parsed string as-is
					return jsonString;
				}
			}
			else {
				// It's already a JSON object, re-serialize it
				return objectMapper.writeValueAsString(jsonObject);
			}
		}
		catch (Exception e) {
			// If it's not valid JSON, return as-is
			return result;
		}
	}

	/**
	 * Record action result with simplified parameters
	 */
	private void recordActionResult(List<ActToolParam> actToolInfoList) {
		planExecutionRecorder.recordActionResult(actToolInfoList);
	}

	/**
	 * Execute shared post-tool flow - record action result This method is called after
	 * tool execution to perform common post-processing
	 * @param toolInstance The tool instance that was executed
	 * @param toolCallResponse The tool call response
	 * @param result The processed result string
	 * @param actToolParams The action tool parameters
	 */
	private void executePostToolFlow(ToolCallBiFunctionDef<?> toolInstance,
			ToolResponseMessage.ToolResponse toolCallResponse, String result, List<ActToolParam> actToolParams) {
		// Record the result
		recordActionResult(actToolParams);
	}

	/**
	 * Extract error message from tool result and set it on the step
	 * @param result The tool result JSON string
	 * @param toolName The name of the tool (for logging)
	 * @return The extracted error message, or the result itself if extraction fails
	 */
	private String extractAndSetErrorMessage(String result, String toolName) {
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> errorData = objectMapper.readValue(result, Map.class);
			String errorMessage = (String) errorData.get("errorMessage");
			if (errorMessage != null && !errorMessage.isEmpty()) {
				step.setErrorMessage(errorMessage);
				log.info("{} extracted errorMessage for stepId: {}, errorMessage: {}", toolName, step.getStepId(),
						errorMessage);
				return errorMessage;
			}
		}
		catch (Exception e) {
			log.warn("Failed to parse errorMessage from {} result: {}", toolName, result, e);
		}
		// Fallback: use the result as errorMessage
		step.setErrorMessage(result);
		return result;
	}

	/**
	 * Record thinking and action for error reporting tools to make them visible in
	 * frontend
	 * @param param The ActToolParam containing tool information
	 * @param thinkInput Description of the error context
	 * @param thinkOutput Description of what tool was called
	 * @param errorMessage The actual error message
	 */
	private void recordErrorToolThinkingAndAction(ActToolParam param, String thinkInput, String thinkOutput,
			String errorMessage) {
		try {
			String stepId = step.getStepId();
			String thinkActId = planIdDispatcher.generateThinkActId();
			String finalErrorMessage = step.getErrorMessage() != null ? step.getErrorMessage() : errorMessage;

			ThinkActRecordParams errorParams = new ThinkActRecordParams(thinkActId, stepId, thinkInput, thinkOutput,
					finalErrorMessage, List.of(param));
			planExecutionRecorder.recordThinkingAndAction(step, errorParams);
			log.info("Recorded thinking and action for error tool, stepId: {}", stepId);
		}
		catch (Exception e) {
			log.warn("Failed to record thinking and action for error tool", e);
		}
	}

	/**
	 * Handle LLM timeout (3 retries exhausted) by simulating full flow with
	 * SystemErrorReportTool
	 * @return AgentExecResult with error information
	 */
	private AgentExecResult handleLlmTimeoutWithSystemErrorReport() {
		log.error("Handling LLM timeout with SystemErrorReportTool");

		try {
			// Create SystemErrorReportTool instance
			SystemErrorReportTool errorTool = new SystemErrorReportTool(getCurrentPlanId(), objectMapper);

			// Build error message from latest exception
			String errorMessage = buildErrorMessageFromLatestException();

			// Create tool input
			Map<String, Object> errorInput = Map.of("errorMessage", errorMessage);

			// Execute the error report tool
			ToolExecuteResult toolResult = errorTool.run(errorInput);

			// Simulate post-tool flow (memory processing, recording, etc.)
			String result = simulatePostToolFlow(errorTool, toolResult, errorMessage);

			// Extract error message for step
			try {
				@SuppressWarnings("unchecked")
				Map<String, Object> errorData = objectMapper.readValue(toolResult.getOutput(), Map.class);
				String extractedErrorMessage = (String) errorData.get("errorMessage");
				if (extractedErrorMessage != null && !extractedErrorMessage.isEmpty()) {
					step.setErrorMessage(extractedErrorMessage);
				}
			}
			catch (Exception e) {
				log.warn("Failed to parse errorMessage from SystemErrorReportTool result", e);
				step.setErrorMessage(errorMessage);
			}

			// Record thinking and action for SystemErrorReportTool to make it visible in
			// frontend
			String toolCallId = planIdDispatcher.generateToolCallId();
			String parametersJson = objectMapper.writeValueAsString(errorInput);
			ActToolParam param = new ActToolParam(SystemErrorReportTool.name, parametersJson, toolResult.getOutput(),
					toolCallId);
			String finalErrorMessage = step.getErrorMessage() != null ? step.getErrorMessage() : errorMessage;
			recordErrorToolThinkingAndAction(param, "LLM timeout after 3 retries",
					"SystemErrorReportTool called to report LLM timeout error", finalErrorMessage);

			return new AgentExecResult(result, AgentState.FAILED);
		}
		catch (Exception e) {
			log.error("Failed to handle LLM timeout with SystemErrorReportTool", e);
			String fallbackError = "LLM timeout error: " + buildErrorMessageFromLatestException();
			step.setErrorMessage(fallbackError);
			return new AgentExecResult(fallbackError, AgentState.FAILED);
		}
	}

	@Override
	protected String simulatePostToolFlow(Object tool, ToolExecuteResult toolResult, String errorMessage) {
		// Override to provide DynamicAgent-specific post-tool flow
		// This simulates what normally happens after tool execution:
		// 1. Process memory (if applicable)
		// 2. Record action result

		// For SystemErrorReportTool, we need to create a mock ActToolParam for recording
		if (tool instanceof SystemErrorReportTool) {
			try {
				String toolCallId = planIdDispatcher.generateToolCallId();
				String parametersJson = objectMapper.writeValueAsString(Map.of("errorMessage", errorMessage));
				ActToolParam param = new ActToolParam(SystemErrorReportTool.name, parametersJson,
						toolResult.getOutput(), toolCallId);

				// Record the action result
				recordActionResult(List.of(param));
			}
			catch (Exception e) {
				log.warn("Failed to record SystemErrorReportTool result", e);
			}
		}

		return toolResult.getOutput();
	}

	private void processUserInputToMemory(UserMessage userMessage) {
		if (userMessage != null && userMessage.getText() != null) {
			// Process the user message to update memory
			String userInput = userMessage.getText();

			if (!StringUtils.isBlank(userInput)) {
				// Add user input to memory

				llmService.getAgentMemory(manusProperties.getMaxMemory()).add(getCurrentPlanId(), userMessage);

			}
		}
	}

	private void processMemory(ToolExecutionResult toolExecutionResult) {
		if (toolExecutionResult == null) {
			return;
		}
		// Process the conversation history to update memory
		List<Message> messages = toolExecutionResult.conversationHistory();
		if (messages.isEmpty()) {
			return;
		}
		// clear current plan memory
		llmService.getAgentMemory(manusProperties.getMaxMemory()).clear(getCurrentPlanId());
		for (Message message : messages) {
			// exclude all system message
			if (message instanceof SystemMessage) {
				continue;
			}
			// exclude env data message
			if (message instanceof UserMessage userMessage
					&& userMessage.getMetadata().containsKey(CURRENT_STEP_ENV_DATA_KEY)) {
				continue;
			}
			// only keep assistant message and tool_call message
			llmService.getAgentMemory(manusProperties.getMaxMemory()).add(getCurrentPlanId(), message);
		}
	}

	/**
	 * 【获取智能体名称】Get the name of this agent.
	 * 【获取此智能体的名称】
	 *
	 * @return 智能体名称 The name of this agent
	 */
	@Override
	public String getName() {
		return this.agentName;
	}

	/**
	 * 【获取智能体描述】Get the description of this agent.
	 * 【获取此智能体的描述】
	 *
	 * @return 智能体描述 The description of this agent
	 */
	@Override
	public String getDescription() {
		return this.agentDescription;
	}

	@Override
	protected Message getNextStepWithEnvMessage() {
		if (StringUtils.isBlank(this.nextStepPrompt)) {
			return new UserMessage("");
		}
		PromptTemplate promptTemplate = new SystemPromptTemplate(this.nextStepPrompt);
		Message userMessage = promptTemplate.createMessage(getMergedData());
		return userMessage;
	}

	private Map<String, Object> getMergedData() {
		Map<String, Object> data = new HashMap<>();
		data.putAll(getInitSettingData());
		data.put(AbstractPlanExecutor.EXECUTION_ENV_STRING_KEY, convertEnvDataToString());
		return data;
	}

	@Override
	protected Message getThinkMessage() {
		Message baseThinkPrompt = super.getThinkMessage();
		Message nextStepWithEnvMessage = getNextStepWithEnvMessage();
		SystemMessage thinkMessage = new SystemMessage("""
				<SystemInfo>
				%s
				</SystemInfo>

				<AgentInfo>
				%s
				</AgentInfo>
				""".formatted(baseThinkPrompt.getText(), nextStepWithEnvMessage.getText()));
		return thinkMessage;
	}

	/**
	 * Current step env data
	 * @return User message for current step environment data
	 */
	private Message currentStepEnvMessage() {
		String currentStepEnv = """
				- Current step environment information:
				{current_step_env_data}
				""";
		PromptTemplate template = new PromptTemplate(currentStepEnv);
		Message stepEnvMessage = template.createMessage(getMergedData());
		// mark as current step env data
		stepEnvMessage.getMetadata().put(CURRENT_STEP_ENV_DATA_KEY, Boolean.TRUE);
		return stepEnvMessage;
	}

	public ToolCallBackContext getToolCallBackContext(String toolKey) {
		Map<String, ToolCallBackContext> toolCallBackContext = toolCallbackProvider.getToolCallBackContext();
		if (toolCallBackContext.containsKey(toolKey)) {
			return toolCallBackContext.get(toolKey);
		}
		else {
			log.warn("在映射中未找到 {} 对应的工具回调。", toolKey);
			return null;
		}
	}

	@Override
	public List<ToolCallback> getToolCallList() {
		List<ToolCallback> toolCallbacks = new ArrayList<>();
		Map<String, ToolCallBackContext> toolCallBackContext = toolCallbackProvider.getToolCallBackContext();
		for (String toolKey : availableToolKeys) {
			if (toolCallBackContext.containsKey(toolKey)) {
				ToolCallBackContext toolCallback = toolCallBackContext.get(toolKey);
				if (toolCallback != null) {
					toolCallbacks.add(toolCallback.getToolCallback());
				}
			}
			else {
				log.warn("Tool callback for {} not found in the map.", toolKey);
			}
		}
		return toolCallbacks;
	}

	public void addEnvData(String key, String value) {
		Map<String, Object> data = super.getInitSettingData();
		if (data == null) {
			throw new IllegalStateException("Data map is null. Cannot add environment data.");
		}
		data.put(key, value);
	}

	public void setToolCallbackProvider(ToolCallbackProvider toolCallbackProvider) {
		this.toolCallbackProvider = toolCallbackProvider;
	}

	protected String collectEnvData(String toolCallName) {
		log.info("🔍 collectEnvData called for tool: {}", toolCallName);
		ToolCallBackContext context = toolCallbackProvider.getToolCallBackContext().get(toolCallName);
		if (context != null) {
			String envData = context.getFunctionInstance().getCurrentToolStateString();
			return envData;
		}
		// If corresponding tool callback context is not found, return empty string
		log.warn("⚠️ No context found for tool: {}", toolCallName);
		return "";
	}

	/**
	 * 【收集并设置工具环境数据】Collect and set environment data for all available tools.
	 * 【为所有可用工具收集并设置环境数据】
	 *
	 * This method gathers the current state information from all tools and stores it
	 * in the environment data map for use in the next thinking cycle.
	 * 【此方法从所有工具收集当前状态信息，并将其存储在环境数据映射中供下一个思考周期使用】
	 */
	public void collectAndSetEnvDataForTools() {

		// 创建工具环境数据映射 Create tool environment data map
		Map<String, Object> toolEnvDataMap = new HashMap<>();

		// 获取旧的环境数据并合并 Get old environment data and merge
		Map<String, Object> oldMap = getEnvData();
		toolEnvDataMap.putAll(oldMap);

		// 用新数据覆盖旧数据 Overwrite old data with new data
		for (String toolKey : availableToolKeys) {
			String envData = collectEnvData(toolKey);
			toolEnvDataMap.put(toolKey, envData);
		}

		// log.debug("Collected tool environment data: {}", toolEnvDataMap);

		// 设置环境数据 Set environment data
		setEnvData(toolEnvDataMap);
	}

	public String convertEnvDataToString() {
		StringBuilder envDataStringBuilder = new StringBuilder();

		for (String toolKey : availableToolKeys) {
			Object value = getEnvData().get(toolKey);
			if (value == null || value.toString().isEmpty()) {
				continue; // Skip tools with no data
			}
			envDataStringBuilder.append(toolKey).append(" context information:\n");
			envDataStringBuilder.append("    ").append(value.toString()).append("\n");
		}

		return envDataStringBuilder.toString();
	}

	// Add a method to wait for user input or handle timeout.
	private void waitForUserInputOrTimeout(FormInputTool formInputTool) {
		log.info("Waiting for user input for planId: {}...", getCurrentPlanId());
		long startTime = System.currentTimeMillis();
		long lastInterruptionCheck = startTime;
		// Get timeout from ManusProperties and convert to milliseconds
		long userInputTimeoutMs = getManusProperties().getUserInputTimeout() * 1000L;
		long interruptionCheckIntervalMs = 2000L; // Check for interruption every 2
													// seconds

		while (formInputTool.getInputState() == FormInputTool.InputState.AWAITING_USER_INPUT) {
			long currentTime = System.currentTimeMillis();

			// Check for interruption periodically
			if (currentTime - lastInterruptionCheck >= interruptionCheckIntervalMs) {
				if (agentInterruptionHelper != null
						&& !agentInterruptionHelper.checkInterruptionAndContinue(getRootPlanId())) {
					log.info("User input wait interrupted for rootPlanId: {}", getRootPlanId());
					formInputTool.handleInputTimeout(); // Treat interruption as timeout
					break;
				}
				lastInterruptionCheck = currentTime;
			}

			if (currentTime - startTime > userInputTimeoutMs) {
				log.warn("Timeout waiting for user input for planId: {}", getCurrentPlanId());
				formInputTool.handleInputTimeout(); // This will change its state to
				// INPUT_TIMEOUT
				break;
			}
			try {
				// Poll for input state change. In a real scenario, this might involve
				// a more sophisticated mechanism like a Future or a callback from the UI.
				TimeUnit.MILLISECONDS.sleep(500); // Check every 500ms
			}
			catch (InterruptedException e) {
				log.warn("Interrupted while waiting for user input for planId: {}", getCurrentPlanId());
				Thread.currentThread().interrupt();
				formInputTool.handleInputTimeout(); // Treat interruption as timeout for
				// simplicity
				break;
			}
		}
		if (formInputTool.getInputState() == FormInputTool.InputState.INPUT_RECEIVED) {
			log.info("User input received for planId: {}", getCurrentPlanId());
		}
		else if (formInputTool.getInputState() == FormInputTool.InputState.INPUT_TIMEOUT) {
			log.warn("User input timed out for planId: {}", getCurrentPlanId());
		}
	}

}
