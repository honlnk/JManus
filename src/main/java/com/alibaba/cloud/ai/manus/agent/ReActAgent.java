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

import java.util.Map;

import com.alibaba.cloud.ai.manus.config.ManusProperties;
import com.alibaba.cloud.ai.manus.llm.LlmService;
import com.alibaba.cloud.ai.manus.recorder.service.PlanExecutionRecorder;
import com.alibaba.cloud.ai.manus.runtime.entity.vo.ExecutionStep;
import com.alibaba.cloud.ai.manus.runtime.service.PlanIdDispatcher;

/**
 * 【ReAct智能体抽象基类】Base class for ReAct (Reasoning + Acting) pattern agents.
 * 【ReAct（推理+行动）模式智能体的基类】
 * Implements an agent pattern where thinking (Reasoning) and acting (Acting) are executed alternately.
 * 【实现一种智能体模式，其中思考（推理）和行动（行动）交替执行】
 */
public abstract class ReActAgent extends BaseAgent {

	/**
	 * Constructor
	 * @param llmService LLM service instance for handling natural language interactions
	 * @param planExecutionRecorder plan execution recorder for recording execution
	 * process
	 * @param manusProperties Manus configuration properties
	 */

	public ReActAgent(LlmService llmService, PlanExecutionRecorder planExecutionRecorder,
			ManusProperties manusProperties, Map<String, Object> initialAgentSetting, ExecutionStep step,
			PlanIdDispatcher planIdDispatcher) {
		super(llmService, planExecutionRecorder, manusProperties, initialAgentSetting, step, planIdDispatcher);
	}

	/**
	 * 【执行思考过程】Execute thinking process and determine whether action needs to be taken
	 * 【执行思考过程并确定是否需要采取行动】
	 *
	 * Subclass implementation requirements: 1. Analyze current state and context 2.
	 * Perform logical reasoning to decide on next action 3. Return whether action
	 * execution is needed
	 * 【子类实现要求：1. 分析当前状态和上下文 2. 执行逻辑推理以决定下一步行动 3. 返回是否需要执行行动】
	 *
	 * Example implementation: - Return true if tools need to be called - Return false if
	 * current step is completed
	 * 【示例实现：- 如果需要调用工具则返回true - 如果当前步骤已完成则返回false】
	 * @return true indicates action execution is needed, false indicates no action is
	 * currently needed【true表示需要执行行动，false表示当前不需要行动】
	 */
	protected abstract boolean think();

	/**
	 * 【执行具体行动】Execute specific actions
	 * 【执行基于思考结果的具体行动】
	 *
	 * Subclass implementation requirements: 1. Execute specific operations based on
	 * think() decisions 2. Can be tool calls, state updates, or other specific behaviors
	 * 3. Return description of execution results
	 * 【子类实现要求：1. 基于 think() 决策执行具体操作 2. 可以是工具调用、状态更新或其他特定行为 3. 返回执行结果的描述】
	 *
	 * Example implementations: - ToolCallAgent: execute selected tool calls -
	 * BrowserAgent: execute browser operations
	 * 【示例实现：- ToolCallAgent：执行选定的工具调用 - BrowserAgent：执行浏览器操作】
	 * @return description of action execution results【行动执行结果的描述】
	 */
	protected abstract AgentExecResult act();

	/**
	 * 【执行完整的思考-行动步骤】Execute a complete think-act step
	 * 【执行完整的思考-行动循环步骤】
	 * @return returns thinking complete message if no action is needed, otherwise returns
	 * action execution result【如果不需要行动则返回思考完成消息，否则返回行动执行结果】
	 */
	@Override
	public AgentExecResult step() {
		try {
			// 执行思考过程，确定是否需要行动
			boolean shouldAct = think();
			if (!shouldAct) {
				// 如果不需要行动，返回思考完成状态
				AgentExecResult result = new AgentExecResult("Thinking complete - no action needed",
						AgentState.IN_PROGRESS);

				return result;
			}
			// 执行具体行动
			return act();
		}
		catch (com.alibaba.cloud.ai.manus.runtime.service.TaskInterruptionCheckerService.TaskInterruptedException e) {
			// 智能体被中断，返回INTERRUPTED状态以停止执行
			return new AgentExecResult("Agent execution interrupted: " + e.getMessage(), AgentState.INTERRUPTED);
		}
	}

}
