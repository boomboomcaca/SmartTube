package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 翻译服务
 * 负责调用Ollama API获取单词解释
 */
public class TranslationService {
    private static final String TAG = TranslationService.class.getSimpleName();
    private static final String OLLAMA_API_URL = "http://192.168.1.113:11434/api/generate";
    
    /**
     * 获取单词解释
     */
    public static String fetchDefinition(String word, String context, int retryCount) {
        HttpURLConnection connection = null;
        BufferedReader reader = null;
        
        try {
            // 准备查询参数
            String query = word.trim();
            String originalWord = query;
            
            // 添加随机性以增加每次查询的差异
            if (retryCount > 0) {
                long timestamp = System.currentTimeMillis();
                int randomNum = (int)(timestamp % 1000);
                query = query + " #" + randomNum;
                Log.d(TAG, "添加随机后缀: '" + query + "'");
            }
            
            // 构建提示词
            String prompt = buildPrompt(originalWord, context, retryCount);
            
            // 构建请求JSON
            String requestJson = "{\"model\":\"qwen3:latest\",\"stream\":false,\"think\":false,\"prompt\":\"" + 
                                  prompt.replace("\"", "\\\"").replace("\n", "\\n") + 
                                  "\"}";
            
            Log.d(TAG, "Ollama请求JSON: " + requestJson);
            
            // 发送请求
            URL url = new URL(OLLAMA_API_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);
            
            // 写入请求体
            try (java.io.OutputStream os = connection.getOutputStream()) {
                byte[] input = requestJson.getBytes("utf-8");
                os.write(input, 0, input.length);
                os.flush();
                Log.d(TAG, "已向Ollama发送请求");
            }
            
            // 获取响应
            int responseCode = connection.getResponseCode();
            Log.d(TAG, "Ollama响应码: " + responseCode);
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // 读取响应内容
                reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                StringBuilder result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line);
                }
                
                String responseStr = result.toString();
                Log.d(TAG, "Ollama响应长度: " + responseStr.length() + " 字节");
                
                // 解析JSON响应
                return parseResponse(responseStr, originalWord, prompt);
            } else {
                // 尝试获取错误信息
                return handleErrorResponse(connection, responseCode);
            }
        } catch (Exception e) {
            return handleException(e);
        } finally {
            closeResources(reader, connection);
        }
    }
    
    /**
     * 构建提示词
     */
    private static String buildPrompt(String word, String context, int retryCount) {
        String chineseEmphasis;
        if (retryCount == 0) {
            chineseEmphasis = "请始终使用中文回答，保持简洁明了的解释。";
        } else if (retryCount == 1) {
            chineseEmphasis = "请注意：必须用中文回答！保持简洁明了的解释。";
        } else if (retryCount == 2) {
            chineseEmphasis = "警告：你必须完全用中文回答！不要使用英文或其他语言解释。";
        } else if (retryCount == 3) {
            chineseEmphasis = "严格警告：只能用中文回答，一个英文单词都不要出现在解释中！";
        } else {
            chineseEmphasis = "最后警告：我只接受纯中文回答！不要有任何英文单词出现在解释中（音标除外）！";
        }
        
        return "请解释一下\"" + word + "\"这个词在这句话\"" + context + 
               "\"中的用法。" + chineseEmphasis + 
               "必须在单词后面提供美式英语的音标。严格禁止显示任何思考过程，直接给出干净的解释。";
    }
    
    /**
     * 解析响应
     */
    private static String parseResponse(String jsonResponse, String word, String prompt) {
        try {
            if (jsonResponse == null || jsonResponse.trim().isEmpty()) {
                return "【" + capitalizeFirstLetter(word) + "】\n\n获取解释失败：服务器返回空响应";
            }
            
            StringBuilder definition = new StringBuilder();
            String capitalizedWord = capitalizeFirstLetter(word);
            
            // 从JSON中提取响应文本
            if (jsonResponse.contains("\"response\":")) {
                String response = extractResponseText(jsonResponse);
                response = cleanupResponse(response);
                
                // 提取音标
                String phonetics = extractPhonetics(response);
                
                // 构建标题
                definition.append("【").append(capitalizedWord).append("】");
                if (!phonetics.isEmpty()) {
                    definition.append(" ").append(phonetics);
                }
                definition.append("\n");
                
                // 清理响应文本
                String cleanResponse = removePhoneticFromText(response);
                cleanResponse = filterThinkingContent(cleanResponse);
                
                // 检查是否包含中文
                if (!containsChinese(cleanResponse)) {
                    cleanResponse = "注意：AI没有使用中文回答。\n\n发送给AI的命令：\n" + prompt + "\n\n" + cleanResponse;
                }
                
                definition.append(cleanResponse);
                return definition.toString();
            } else {
                return handleErrorInResponse(jsonResponse, capitalizedWord);
            }
        } catch (Exception e) {
            Log.e(TAG, "解析Ollama响应异常: " + e.getMessage(), e);
            return "【" + capitalizeFirstLetter(word) + "】\n\n解析响应失败: " + e.getMessage();
        }
    }
    
    /**
     * 提取响应文本
     */
    private static String extractResponseText(String jsonResponse) {
        int responseIndex = jsonResponse.indexOf("\"response\":") + 12;
        int responseEnd = -1;
        
        for (int i = responseIndex; i < jsonResponse.length(); i++) {
            if (jsonResponse.charAt(i) == '"' && (i == 0 || jsonResponse.charAt(i-1) != '\\')) {
                responseEnd = i;
                break;
            }
        }
        
        if (responseIndex > 0 && responseEnd > responseIndex) {
            String response = jsonResponse.substring(responseIndex, responseEnd);
            // 清理转义字符
            response = response.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
            // 解码Unicode转义序列
            return decodeUnicodeEscapes(response);
        }
        
        return "";
    }
    
    /**
     * 提取音标
     */
    private static String extractPhonetics(String response) {
        // 首先尝试查找标记后面的音标
        Pattern usPattern = Pattern.compile("(?:美音|【美音】|美式发音|美式音标|美式英语|音标)[:：]\\s*\\[([^\\]]+)\\]");
        Matcher usMatcher = usPattern.matcher(response);
        if (usMatcher.find()) {
            String phonetics = usMatcher.group(1);
            if (phonetics != null && !phonetics.isEmpty()) {
                return "[" + phonetics + "]";
            }
        }
        
        // 尝试查找反斜杠格式的音标
        Pattern usSlashPattern = Pattern.compile("(?:美音|【美音】|美式发音|美式音标|美式英语|音标)[:：]\\s*\\/([^\\/]+)\\/");
        Matcher usSlashMatcher = usSlashPattern.matcher(response);
        if (usSlashMatcher.find()) {
            String phonetics = usSlashMatcher.group(1);
            if (phonetics != null && !phonetics.isEmpty()) {
                return "[" + phonetics + "]";
            }
        }
        
        // 在整个文本中查找音标
        Pattern phoneticsPattern = Pattern.compile("\\[([^\\]]+)\\]|（([^）]+)）");
        Matcher phoneticsMatcher = phoneticsPattern.matcher(response);
        
        while (phoneticsMatcher.find()) {
            String phonetics = phoneticsMatcher.group(1) != null ? phoneticsMatcher.group(1) : phoneticsMatcher.group(2);
            if (isPhonetic(phonetics)) {
                return "[" + phonetics + "]";
            }
        }
        
        return "";
    }
    
    /**
     * 检查是否是音标
     */
    private static boolean isPhonetic(String text) {
        if (text == null) return false;
        return text.contains("ə") || text.contains("ɪ") || text.contains("ʌ") || 
               text.contains("ɑ") || text.contains("ɔ") || text.contains("æ") ||
               text.contains("ː") || text.contains("ˈ") || text.contains("ˌ") ||
               text.matches(".*[a-zA-Z].*");
    }
    
    /**
     * 清理响应文本
     */
    private static String cleanupResponse(String text) {
        if (text == null) return "";
        
        // 移除think标签
        text = text.replace("think>", "");
        text = text.replace("<think", "");
        text = text.replace("</think", "");
        text = text.replace("<think>", "");
        text = text.replace("</think>", "");
        text = text.replaceAll("<[^>]*>", "");
        
        // 移除控制字符
        text = text.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
        text = text.replaceAll("[^\\p{Print}\\p{Space}]", "");
        text = text.replaceAll("\\s+", " ");
        text = text.replaceAll("^[^\\w\\u4e00-\\u9fa5\\n]+", "");
        text = text.replace("\uFFFD", "");
        
        return text.trim();
    }
    
    /**
     * 过滤思考内容
     */
    private static String filterThinkingContent(String text) {
        if (text == null) return "";
        
        String filtered = text.replaceAll("(?i)<think>.*?</think>", "");
        filtered = filtered.replaceAll("(?i)<think\\s+[^>]*>.*?</think>", "");
        filtered = filtered.replaceAll("(?i)</?think[^>]*>", "");
        filtered = filtered.replaceAll("(?i)(?m)^\\s*(?:let me think|thinking).*$", "");
        filtered = filtered.replaceAll("(?i)I need to consider|Let's analyze|Let me consider", "");
        filtered = filtered.replaceAll("[^。]*[思考|思索][^。]*。", "");
        filtered = filtered.replaceAll("我来分析一下|我们来看看|让我思考", "");
        filtered = filtered.replaceAll("\n{3,}", "\n\n");
        filtered = filtered.replaceAll("^\\s*\n", "");
        
        return filtered.trim();
    }
    
    /**
     * 移除音标文本
     */
    private static String removePhoneticFromText(String text) {
        if (text == null) return "";
        
        String[] lines = text.split("\n");
        StringBuilder cleanedTextBuilder = new StringBuilder();
        boolean inPhoneticSection = false;
        
        for (String line : lines) {
            if (line.trim().matches("^\\s*【(?:音标|发音|读音|美式音标|英式音标|美音|英音|国际音标|美式英语)】\\s*$")) {
                inPhoneticSection = true;
                continue;
            } else if (inPhoneticSection && line.trim().matches("^\\s*【.*】\\s*$")) {
                inPhoneticSection = false;
            }
            
            if (inPhoneticSection || isPhoneticOnlyLine(line)) {
                continue;
            }
            
            String cleanedLine = removePhoneticFromLine(line);
            if (!cleanedLine.trim().isEmpty()) {
                cleanedTextBuilder.append(cleanedLine).append("\n");
            }
        }
        
        String cleanedText = cleanedTextBuilder.toString();
        cleanedText = cleanedText.replaceAll("(?m)^\\s*(?:音标|发音|读音|美式音标|英式音标|美音|英音|国际音标|美式英语)(?:为|是|:|：)?[^\\n]*$", "");
        cleanedText = cleanedText.replaceAll("(?m)^\\s*$\\n", "");
        cleanedText = cleanedText.replaceAll("\n{3,}", "\n\n");
        
        return cleanedText.trim();
    }
    
    /**
     * 从一行文本中移除音标
     */
    private static String removePhoneticFromLine(String line) {
        String cleanedLine = line.replaceAll("(?:音标|发音|读音|美式音标|英式音标|美音|英音|国际音标|美式英语|美式英语的发音)(?:为|是|:|：)?\\s*\\[[^\\]]+\\]", "");
        cleanedLine = cleanedLine.replaceAll("(?:音标|发音|读音|美式音标|英式音标|美音|英音|国际音标|美式英语|美式英语的发音)(?:为|是|:|：)?\\s*\\/[^\\/]+\\/", "");
        cleanedLine = cleanedLine.replaceAll("\\[\\[\\w\\s\\u0250-\\u02AF\\u02B0-\\u02FF'ˈˌ:ː,.\\-]+\\]", "");
        cleanedLine = cleanedLine.replaceAll("\\/[\\w\\s\\u0250-\\u02AF\\u02B0-\\u02FF'ˈˌ:ː,.\\-]+\\/", "");
        cleanedLine = cleanedLine.replaceAll("(?:音标|发音|读音|美式音标|英式音标|美音|英音|国际音标|美式英语|美式英语的发音)(?:为|是|:|：)?\\s*$", "");
        cleanedLine = cleanedLine.replaceAll("[（(\\[【]?美式英语[)）\\]】]?", "");
        cleanedLine = cleanedLine.replace("*", "");
        
        return cleanedLine.trim();
    }
    
    /**
     * 判断是否是只包含音标的行
     */
    private static boolean isPhoneticOnlyLine(String line) {
        String trimmedLine = line.trim();
        
        return trimmedLine.matches("^\\s*\\[[\\w\\s\\u0250-\\u02AF\\u02B0-\\u02FF'ˈˌ:ː,.\\-]+\\]\\s*$") ||
               trimmedLine.matches("^\\s*\\/[\\w\\s\\u0250-\\u02AF\\u02B0-\\u02FF'ˈˌ:ː,.\\-]+\\/\\s*$") ||
               trimmedLine.matches("^\\s*(?:音标|发音|读音|美式音标|英式音标|美音|英音|国际音标|美式英语|美式英语的发音)(?:为|是|:|：)?\\s*\\[[^\\]]+\\]\\s*$") ||
               trimmedLine.matches("^\\s*(?:音标|发音|读音|美式音标|英式音标|美音|英音|国际音标|美式英语|美式英语的发音)(?:为|是|:|：)?\\s*\\/[^\\/]+\\/\\s*$") ||
               trimmedLine.matches("^\\s*(?:音标|发音|读音|美式音标|英式音标|美音|英音|国际音标|美式英语|美式英语的发音)(?:为|是|:|：)?\\s*$") ||
               trimmedLine.matches("^\\s*【(?:音标|发音|读音|美式音标|英式音标|美音|英音|国际音标|美式英语|美式英语的发音)】\\s*$") ||
               trimmedLine.matches("^\\s*[（(\\[【]?美式英语[)）\\]】]?\\s*$");
    }
    
    /**
     * 检查文本是否包含中文
     */
    private static boolean containsChinese(String text) {
        if (text == null || text.isEmpty()) return false;
        Pattern p = Pattern.compile("[\\u4e00-\\u9fa5]");
        Matcher m = p.matcher(text);
        return m.find();
    }
    
    /**
     * 解码Unicode转义序列
     */
    private static String decodeUnicodeEscapes(String text) {
        if (text == null || text.isEmpty()) return text;
        
        try {
            Pattern pattern = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
            Matcher matcher = pattern.matcher(text);
            StringBuilder result = new StringBuilder();
            int lastEnd = 0;
            
            while (matcher.find()) {
                result.append(text.substring(lastEnd, matcher.start()));
                String hexValue = matcher.group(1);
                int charValue = Integer.parseInt(hexValue, 16);
                result.append((char) charValue);
                lastEnd = matcher.end();
            }
            
            if (lastEnd < text.length()) {
                result.append(text.substring(lastEnd));
            }
            
            return result.toString();
        } catch (Exception e) {
            Log.e(TAG, "解码Unicode转义序列失败: " + e.getMessage());
            return text;
        }
    }
    
    /**
     * 首字母大写
     */
    private static String capitalizeFirstLetter(String word) {
        if (word == null || word.isEmpty()) return word;
        return word.substring(0, 1).toUpperCase() + word.substring(1);
    }
    
    /**
     * 处理错误响应
     */
    private static String handleErrorResponse(HttpURLConnection connection, int responseCode) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getErrorStream(), "UTF-8"));
            StringBuilder errorResult = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                errorResult.append(line);
            }
            reader.close();
            Log.e(TAG, "Ollama错误响应: " + errorResult.toString());
            return "Ollama服务请求失败: " + responseCode + ", " + errorResult.toString();
        } catch (Exception e) {
            Log.e(TAG, "无法读取错误流: " + e.getMessage());
            return "Ollama服务请求失败: " + responseCode;
        }
    }
    
    /**
     * 处理JSON响应中的错误
     */
    private static String handleErrorInResponse(String jsonResponse, String capitalizedWord) {
        if (jsonResponse.contains("\"error\":")) {
            int errorIndex = jsonResponse.indexOf("\"error\":") + 9;
            int errorEnd = jsonResponse.indexOf("\"", errorIndex);
            
            if (errorIndex > 0 && errorEnd > errorIndex) {
                String error = jsonResponse.substring(errorIndex, errorEnd);
                Log.e(TAG, "Ollama返回错误: " + error);
                return "【" + capitalizedWord + "】\n\nOllama服务错误: " + error;
            }
        }
        
        Log.e(TAG, "无法从JSON响应中找到response或error字段: " + jsonResponse);
        return "【" + capitalizedWord + "】\n\n解析失败：响应格式不符合预期\n\n" +
               (jsonResponse.length() < 500 ? "原始响应: " + jsonResponse : "原始响应过长，已省略");
    }
    
    /**
     * 处理异常
     */
    private static String handleException(Exception e) {
        Log.e(TAG, "调用Ollama异常: " + e.getClass().getName() + ": " + e.getMessage(), e);
        if (e instanceof java.net.ConnectException) {
            return "连接Ollama服务失败: 请检查服务是否启动或IP地址是否正确";
        } else if (e instanceof java.net.SocketTimeoutException) {
            return "连接Ollama服务超时: 请求可能需要更长时间或服务器负载过高";
        } else {
            return "查询失败: " + e.getClass().getSimpleName() + " - " + 
                   (e.getMessage() != null ? e.getMessage() : "未知错误");
        }
    }
    
    /**
     * 关闭资源
     */
    private static void closeResources(BufferedReader reader, HttpURLConnection connection) {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                Log.e(TAG, "关闭读取器失败: " + e.getMessage(), e);
            }
        }
        if (connection != null) {
            connection.disconnect();
            Log.d(TAG, "已断开Ollama连接");
        }
    }
}