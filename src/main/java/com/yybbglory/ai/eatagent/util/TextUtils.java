package com.yybbglory.ai.eatagent.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 文本工具类
 * 提供 MD5 哈希、文本截断等通用方法
 */
public final class TextUtils {

    private TextUtils() {
        // 工具类禁止实例化
    }

    /**
     * 计算字符串的 MD5 哈希值（UTF-8 编码）
     * 与 Python hashlib.md5(text.encode("utf-8")).hexdigest() 结果一致
     *
     * @param text 输入文本
     * @return 小写十六进制 MD5 哈希字符串
     */
    public static String md5Hash(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // MD5 在所有 JVM 实现中都可用，不应到达此处
            throw new RuntimeException("MD5 算法不可用", e);
        }
    }

    /**
     * 截断文本到指定最大长度
     * 如果文本超过 maxLength，截断并在末尾添加 "..."
     *
     * @param text      原始文本
     * @param maxLength 最大长度
     * @return 截断后的文本
     */
    public static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        if (maxLength <= 3) {
            return text.substring(0, maxLength);
        }
        return text.substring(0, maxLength - 3) + "...";
    }

    /**
     * 检查字符串是否为空或空白
     */
    public static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
