package com.yybbglory.ai.eatagent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;

/**
 * Prompt 模板工具类
 * <p>
 * 封装 Spring AI 的 {@link PromptTemplate}（基于 StringTemplate 4 的 .st 模板渲染）。
 * 模板文件位于 classpath:prompts/ 目录下，使用 {{variable}} 占位符语法。
 */
public final class PromptTemplateUtils {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateUtils.class);

    private PromptTemplateUtils() {
        // 工具类禁止实例化
    }

    /**
     * 从 classpath 加载 .st 模板并渲染
     *
     * @param path      classpath 下的模板路径（如 prompts/basic-answer.st）
     * @param variables 模板变量映射
     * @return 渲染后的提示词文本
     */
    public static String render(String path, Map<String, Object> variables) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            PromptTemplate template = new PromptTemplate(resource);
            return template.render(variables);
        } catch (Exception e) {
            log.error("渲染 Prompt 模板失败: {}", path, e);
            throw new IllegalStateException("无法渲染 Prompt 模板: " + path, e);
        }
    }
}
