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
package com.alibaba.cloud.ai.manus.planning.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.cloud.ai.manus.planning.PlanningFactory;
import com.alibaba.cloud.ai.manus.planning.model.po.PlanTemplate;
import com.alibaba.cloud.ai.manus.planning.service.PlanTemplateService;
import com.alibaba.cloud.ai.manus.runtime.entity.vo.PlanInterface;
import com.alibaba.cloud.ai.manus.runtime.service.PlanIdDispatcher;
import com.alibaba.cloud.ai.manus.planning.service.IPlanParameterMappingService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Plan template controller, handles API requests for the plan template page
 * 【计划模板控制器，处理计划模板页面的API请求。】
 */
@RestController
@RequestMapping("/api/plan-template")
public class PlanTemplateController {

	private static final Logger logger = LoggerFactory.getLogger(PlanTemplateController.class);

	@Autowired
	@Lazy
	private PlanningFactory planningFactory;

	@Autowired
	private PlanTemplateService planTemplateService;

	@Autowired
	private PlanIdDispatcher planIdDispatcher;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private IPlanParameterMappingService parameterMappingService;

	/**
	 * Save version history【保存版本历史】
	 * @param planJson Plan JSON data【计划JSON数据】
	 * @param planId Plan template ID (already generated)【计划模板ID（已生成）】
	 * @return Save result【保存结果】
	 */
	private PlanTemplateService.VersionSaveResult saveToVersionHistory(String planJson, String planId) {
		try {
			// 解析JSON以提取标题
			PlanInterface planData = objectMapper.readValue(planJson, PlanInterface.class);

			// 使用提供的planId而不是生成新的
			String planTemplateId = planId;

			String title = planData.getTitle();

			if (planTemplateId == null || planTemplateId.trim().isEmpty()) {
				throw new IllegalArgumentException("Plan ID cannot be found in JSON");
			}
			if (title == null || title.trim().isEmpty()) {
				title = "Untitled Plan";
			}

			// 检查计划是否存在
			PlanTemplate template = planTemplateService.getPlanTemplate(planTemplateId);
			if (template == null) {
				// 如果不存在，创建新计划
				planTemplateService.savePlanTemplate(planTemplateId, title, title, planJson, false);
				logger.info("New plan created: {}", planTemplateId);
				return new PlanTemplateService.VersionSaveResult(true, false, "New plan created", 0);
			}
			else {
				// 如果存在，用新标题更新模板并保存新版本
				boolean updated = planTemplateService.updatePlanTemplate(planTemplateId, title, planJson, false);
				if (updated) {
					logger.info("Updated plan template {} with new title and saved new version", planTemplateId);
					// 更新后获取最新版本索引
					Integer maxVersionIndex = planTemplateService.getPlanVersions(planTemplateId).size() - 1;
					return new PlanTemplateService.VersionSaveResult(true, false,
							"Plan template updated and new version saved", maxVersionIndex);
				}
				else {
					// 如果更新失败，回退到仅保存版本
					PlanTemplateService.VersionSaveResult result = planTemplateService
						.saveToVersionHistory(planTemplateId, planJson);
					if (result.isSaved()) {
						logger.info("New version of plan {} saved", planTemplateId, result.getVersionIndex());
					}
					else {
						logger.info("Plan {} is the same, no new version saved", planTemplateId);
					}
					return result;
				}
			}
		}
		catch (Exception e) {
			logger.error("Failed to parse plan JSON", e);
			throw new RuntimeException("Failed to save version history: " + e.getMessage());
		}
	}

	/**
	 * Save plan【保存计划】
	 * @param request Request containing plan ID and JSON【包含计划ID和JSON的请求】
	 * @return Save result【保存结果】
	 */
	@PostMapping("/save")
	@Transactional
	public ResponseEntity<Map<String, Object>> savePlan(@RequestBody Map<String, String> request) {
		String planJson = request.get("planJson");

		if (planJson == null || planJson.trim().isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of("error", "Plan data cannot be empty"));
		}

		try {
			// 解析JSON以获取planId
			PlanInterface planData = objectMapper.readValue(planJson, PlanInterface.class);
			String planId = planData.getPlanTemplateId();
			if (planId == null) {
				planId = planData.getRootPlanId();
			}

			// 检查planId是否为空或以"new-"开头，然后生成新的
			if (planId == null || planId.trim().isEmpty() || planId.startsWith("new-")) {
				String newPlanId = planIdDispatcher.generatePlanTemplateId();
				logger.info("Original planId '{}' is empty or starts with 'new-', generated new planId: {}", planId,
						newPlanId);

				// 用新ID更新计划对象
				planData.setCurrentPlanId(newPlanId);
				planData.setRootPlanId(newPlanId);

				// 重新序列化更新的计划对象为JSON
				planJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(planData);
				planId = newPlanId;
			}

			if (planId == null || planId.trim().isEmpty()) {
				return ResponseEntity.badRequest().body(Map.of("error", "Plan ID cannot be found in JSON"));
			}

			// 保存到版本历史
			PlanTemplateService.VersionSaveResult saveResult = saveToVersionHistory(planJson, planId);

			// 计算版本数量
			List<String> versions = planTemplateService.getPlanVersions(planId);
			int versionCount = versions.size();

			// 构建响应
			Map<String, Object> response = new HashMap<>();
			response.put("status", "success");
			response.put("planId", planId);
			response.put("versionCount", versionCount);
			response.put("saved", saveResult.isSaved());
			response.put("duplicate", saveResult.isDuplicate());
			response.put("message", saveResult.getMessage());
			response.put("versionIndex", saveResult.getVersionIndex());

			return ResponseEntity.ok(response);
		}
		catch (Exception e) {
			logger.error("Failed to save plan", e);
			return ResponseEntity.internalServerError().body(Map.of("error", "Failed to save plan: " + e.getMessage()));
		}
	}

	/**
	 * Get the version history of the plan【获取计划的版本历史】
	 * @param request Request containing plan ID【包含计划ID的请求】
	 * @return Version history list【版本历史列表】
	 */
	@PostMapping("/versions")
	public ResponseEntity<Map<String, Object>> getPlanVersions(@RequestBody Map<String, String> request) {
		String planId = request.get("planId");

		if (planId == null || planId.trim().isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of("error", "Plan ID cannot be empty"));
		}

		List<String> versions = planTemplateService.getPlanVersions(planId);

		Map<String, Object> response = new HashMap<>();
		response.put("planId", planId);
		response.put("versionCount", versions.size());
		response.put("versions", versions);

		return ResponseEntity.ok(response);
	}

	/**
	 * Get a specific version of the plan【获取计划的特定版本】
	 * @param request Request containing plan ID and version index【包含计划ID和版本索引的请求】
	 * @return Specific version of the plan【计划的特定版本】
	 */
	@PostMapping("/get-version")
	public ResponseEntity<Map<String, Object>> getVersionPlan(@RequestBody Map<String, String> request) {
		String planId = request.get("planId");
		String versionIndex = request.get("versionIndex");

		if (planId == null || planId.trim().isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of("error", "Plan ID cannot be empty"));
		}

		try {
			int index = Integer.parseInt(versionIndex);
			List<String> versions = planTemplateService.getPlanVersions(planId);

			if (versions.isEmpty()) {
				return ResponseEntity.notFound().build();
			}

			if (index < 0 || index >= versions.size()) {
				return ResponseEntity.badRequest().body(Map.of("error", "Version index out of range"));
			}

			String planJson = planTemplateService.getPlanVersion(planId, index);

			if (planJson == null) {
				return ResponseEntity.notFound().build();
			}

			Map<String, Object> response = new HashMap<>();
			response.put("planId", planId);
			response.put("versionIndex", index);
			response.put("versionCount", versions.size());
			response.put("planJson", planJson);

			return ResponseEntity.ok(response);
		}
		catch (NumberFormatException e) {
			return ResponseEntity.badRequest().body(Map.of("error", "Version index must be a number"));
		}
		catch (Exception e) {
			logger.error("Failed to get plan version", e);
			return ResponseEntity.internalServerError()
				.body(Map.of("error", "Failed to get plan version: " + e.getMessage()));
		}
	}

	/**
	 * Get all plan templates【获取所有计划模板】
	 * @return All plan templates【所有计划模板】
	 */
	@GetMapping("/list")
	public ResponseEntity<Map<String, Object>> getAllPlanTemplates() {
		try {
			// 使用PlanTemplateService获取所有计划模板
			// 由于没有直接的方法来获取所有模板，我们使用PlanTemplateRepository的findAll方法
			List<PlanTemplate> templates = planTemplateService.getAllPlanTemplates();

			// 构建响应数据
			List<Map<String, Object>> templateList = new ArrayList<>();
			for (PlanTemplate template : templates) {
				Map<String, Object> templateData = new HashMap<>();
				if (template.isInternalToolcall()) {
					continue;
				}
				templateData.put("id", template.getPlanTemplateId());
				templateData.put("title", template.getTitle());
				templateData.put("description", template.getUserRequest());
				templateData.put("createTime", template.getCreateTime());
				templateData.put("updateTime", template.getUpdateTime());
				templateList.add(templateData);
			}

			Map<String, Object> response = new HashMap<>();
			response.put("templates", templateList);
			response.put("count", templateList.size());

			return ResponseEntity.ok(response);
		}
		catch (Exception e) {
			logger.error("Failed to get plan template list", e);
			return ResponseEntity.internalServerError()
				.body(Map.of("error", "Failed to get plan template list: " + e.getMessage()));
		}
	}

	/**
	 * Delete plan template【删除计划模板】
	 * @param request Request containing plan ID【包含计划ID的请求】
	 * @return Delete result【删除结果】
	 */
	@PostMapping("/delete")
	public ResponseEntity<Map<String, Object>> deletePlanTemplate(@RequestBody Map<String, String> request) {
		String planId = request.get("planId");

		if (planId == null || planId.trim().isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of("error", "Plan ID cannot be empty"));
		}

		try {
			// 检查计划模板是否存在
			PlanTemplate template = planTemplateService.getPlanTemplate(planId);
			if (template == null) {
				return ResponseEntity.notFound().build();
			}

			// 删除计划模板和所有版本
			boolean deleted = planTemplateService.deletePlanTemplate(planId);

			if (deleted) {
				logger.info("Plan template deleted successfully: {}", planId);
				return ResponseEntity
					.ok(Map.of("status", "success", "message", "Plan template deleted", "planId", planId));
			}
			else {
				logger.error("Failed to delete plan template: {}", planId);
				return ResponseEntity.internalServerError().body(Map.of("error", "Failed to delete plan template"));
			}
		}
		catch (Exception e) {
			logger.error("Failed to delete plan template", e);
			return ResponseEntity.internalServerError()
				.body(Map.of("error", "Failed to delete plan template: " + e.getMessage()));
		}
	}

	/**
	 * Get parameter requirements for a plan template【获取计划模板的参数要求】
	 * @param planTemplateId The plan template ID【计划模板ID】
	 * @return List of required parameters【所需参数列表】
	 */
	@GetMapping("/{planTemplateId}/parameters")
	public ResponseEntity<Map<String, Object>> getParameterRequirements(@PathVariable String planTemplateId) {
		try {
			PlanTemplate planTemplate = planTemplateService.getPlanTemplate(planTemplateId);
			if (planTemplate == null) {
				return ResponseEntity.notFound().build();
			}

			String planJson = planTemplateService.getLatestPlanVersion(planTemplateId);
			if (planJson == null) {
				return ResponseEntity.notFound().build();
			}

			List<String> parameters = parameterMappingService.extractParameterPlaceholders(planJson);

			Map<String, Object> response = new HashMap<>();
			response.put("parameters", parameters);
			response.put("hasParameters", !parameters.isEmpty());
			response.put("requirements", parameterMappingService.getParameterRequirements(planJson));

			return ResponseEntity.ok(response);
		}
		catch (Exception e) {
			logger.error("Failed to get parameter requirements for plan template: " + planTemplateId, e);
			return ResponseEntity.internalServerError()
				.body(Map.of("error", "Failed to get parameter requirements: " + e.getMessage()));
		}
	}

}
