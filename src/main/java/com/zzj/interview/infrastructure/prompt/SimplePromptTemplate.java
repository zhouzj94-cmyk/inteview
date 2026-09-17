package com.zzj.interview.infrastructure.prompt;

import com.zzj.interview.domain.prompt.PromptTemplate;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt模板实现
 *
 * 支持 {{variable}} 格式的变量替换
 */
public class SimplePromptTemplate implements PromptTemplate {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    private final String name;
    private final String version;
    private final String content;

    public SimplePromptTemplate(String name, String version, String content) {
        this.name = name;
        this.version = version;
        this.content = content;
    }

    @Override
    public String render(Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) {
            return content;
        }

        StringBuffer result = new StringBuffer();
        Matcher matcher = VARIABLE_PATTERN.matcher(content);

        while (matcher.find()) {
            String varName = matcher.group(1);
            Object value = variables.get(varName);
            String replacement = value != null ? value.toString() : "";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    @Override
    public String getRawContent() {
        return content;
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public String getName() {
        return name;
    }
}
