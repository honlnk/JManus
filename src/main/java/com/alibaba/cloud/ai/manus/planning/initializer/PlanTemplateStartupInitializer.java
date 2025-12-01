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
package com.alibaba.cloud.ai.manus.planning.initializer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import com.alibaba.cloud.ai.manus.coordinator.entity.vo.PlanTemplateConfigVO;
import com.alibaba.cloud.ai.manus.coordinator.exception.CoordinatorToolException;
import com.alibaba.cloud.ai.manus.coordinator.service.CoordinatorToolServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Startup initializer for plan templates from startup-plans directory Also registers
 * default plan templates as coordinator tools (internal toolcalls)
 * 【启动计划模板初始化器，从startup-plans目录加载初始模板，同时将默认计划模板注册为协调器工具（内部工具调用）。】
 */
@Component
public class PlanTemplateStartupInitializer {

	private static final Logger log = LoggerFactory.getLogger(PlanTemplateStartupInitializer.class);

	private static final String CONFIG_BASE_PATH = "prompts/startup-plans/";

	private static final String DEFAULT_LANGUAGE = "en";

	@Autowired
	private CoordinatorToolServiceImpl coordinatorToolService;

	@Autowired
	private ObjectMapper objectMapper;

	@Value("${namespace.value}")
	private String namespace;

	/**
	 * Initialize startup plan templates when application is ready
   * 【应用程序就绪时初始化启动计划模板】
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void initializeStartupPlanTemplates() {
		log.info("开始为命名空间初始化启动计划模板: {}", namespace);

		try {
			// 将所有工具配置为协调工具的规划模板进行注册。
			// 如果不存在，这也将创建PlanTemplate。
			registerPlanTemplatesAsTools();

		}
		catch (Exception e) {
			log.error("未能初始化命名空间的启动计划模板: {}", namespace, e);
		}
	}

	/**
	 * Register all plan templates with toolConfig as coordinator tools Scans all JSON
	 * files in startup-plans directory and registers those with toolConfig
   * 【将所有工具配置为协调器工具的规划模板进行注册，扫描启动规划目录中的所有JSON文件，并注册那些包含工具配置的文件。】
	 */
	private void registerPlanTemplatesAsTools() {
		log.info("开始将计划模板注册为协调员工具");

		int successCount = 0;
		int errorCount = 0;

		// 扫描启动计划目录中的所有JSON文件
		List<String> configFilePaths = scanPlanTemplateConfigFiles();

		if (configFilePaths.isEmpty()) {
			log.info("未找到计划模板配置文件以注册为协调器工具。");
			return;
		}

		log.info("找到 {} 个计划模板配置文件需要处理", configFilePaths.size());

		// 处理每个配置文件
		for (String configPath : configFilePaths) {
			try {
				// 从JSON文件加载并解析PlanTemplateConfigVO
				PlanTemplateConfigVO configVO = loadPlanTemplateConfigFromFile(configPath);
				if (configVO == null) {
					log.warn("从文件加载 PlanTemplateConfigVO 失败: {}。跳过。", configPath);
					errorCount++;
					continue;
				}

				// 仅当存在toolConfig时才注册
				if (configVO.getToolConfig() == null) {
					log.debug("计划模板 {} 没有 toolConfig，跳过协调器工具注册",
							configVO.getPlanTemplateId());
					continue;
				}

				// 验证planTemplateId
				String planTemplateId = configVO.getPlanTemplateId();
				if (planTemplateId == null || planTemplateId.trim().isEmpty()) {
					log.warn("文件 {} 中的计划模板没有 planTemplateId。跳过。", configPath);
					errorCount++;
					continue;
				}

				// 使用服务方法创建或更新协调器工具
				coordinatorToolService.createOrUpdateCoordinatorToolFromPlanTemplateConfig(configVO);
				log.info("成功为计划模板注册协调器工具: {} 来自文件: {}", planTemplateId,
						configPath);
				successCount++;

			}
			catch (CoordinatorToolException e) {
				log.error("从文件 {} 注册协调器工具失败: {}", configPath, e.getMessage(), e);
				errorCount++;
			}
			catch (Exception e) {
				log.error("从文件 {} 注册协调器工具时发生意外错误", configPath, e);
				errorCount++;
			}
		}

		log.info("完成计划模板注册为协调器工具。成功: {}, 错误: {}", successCount,
				errorCount);
	}

	/**
	 * Scan for all plan template configuration files in startup-plans directory
	 * 【扫描启动目录中的所有计划模板配置文件】
	 * @return List of configuration file paths 【配置文件路径列表】
	 */
	private List<String> scanPlanTemplateConfigFiles() {
		List<String> configFilePaths = new ArrayList<>();

		try {
			PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

			// 仅扫描默认语言目录中的JSON文件
			String pattern = CONFIG_BASE_PATH + DEFAULT_LANGUAGE + "/*.json";
			try {
				Resource[] resources = resolver.getResources("classpath:" + pattern);
				for (Resource resource : resources) {
					if (resource.exists() && resource.isReadable()) {
						String path = CONFIG_BASE_PATH + DEFAULT_LANGUAGE + "/" + resource.getFilename();
						configFilePaths.add(path);
						log.debug("找到计划模板配置文件: {}", path);
					}
				}
			}
			catch (Exception ex) {
				log.debug("未找到模式对应的资源: {}", pattern);
			}

			log.info("扫描了 {} 个计划模板配置文件", configFilePaths.size());
			return configFilePaths;

		}
		catch (Exception e) {
			log.error("扫描计划模板配置目录失败", e);
			return configFilePaths;
		}
	}

	/**
	 * Load PlanTemplateConfigVO from JSON configuration file
	 * 【从JSON配置文件加载PlanTemplateConfigVO】
	 * @param configPath Configuration file path 【配置文件路径】
	 * @return PlanTemplateConfigVO if loaded successfully, null otherwise 【如果加载成功返回PlanTemplateConfigVO，否则返回null】
	 */
	private PlanTemplateConfigVO loadPlanTemplateConfigFromFile(String configPath) {
		try {
			org.springframework.core.io.ClassPathResource resource = new org.springframework.core.io.ClassPathResource(
					configPath);
			if (!resource.exists()) {
				log.warn("计划模板配置文件不存在: {}", configPath);
				return null;
			}

			StringBuilder content = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					content.append(line).append("\n");
				}
			}

			String jsonContent = content.toString().trim();
			if (jsonContent.isEmpty()) {
				log.warn("计划模板配置文件为空: {}", configPath);
				return null;
			}

			// 将JSON解析为PlanTemplateConfigVO
			PlanTemplateConfigVO configVO = objectMapper.readValue(jsonContent, PlanTemplateConfigVO.class);
			log.debug("成功从文件加载 PlanTemplateConfigVO: {}", configPath);
			return configVO;

		}
		catch (IOException e) {
			log.error("加载计划模板配置文件失败: {}", configPath, e);
			return null;
		}
		catch (Exception e) {
			log.error("从文件解析 PlanTemplateConfigVO 失败: {}", configPath, e);
			return null;
		}
	}

}
