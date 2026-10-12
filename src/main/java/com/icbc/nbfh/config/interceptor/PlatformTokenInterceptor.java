package com.icbc.nbfh.config.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icbc.nbfh.service.RegisterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 校验平台访问执行器受保护接口时携带的 Bearer Token。
 */
@Component
@RequiredArgsConstructor
public class PlatformTokenInterceptor implements HandlerInterceptor {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String TASK_RESULT_PATH = "/api/agent/task-result";

    private final RegisterService registerService;
    private final ObjectMapper objectMapper;

    /**
     * 在进入 Controller 之前统一验证平台身份。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param handler 即将执行的处理器
     * @return 认证成功时继续执行，认证失败时终止请求
     */
    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws IOException {
        String token = registerService.getToken();
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (token != null && (BEARER_PREFIX + token).equals(authorization)) {
            return true;
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        // 结果查询接口原本只返回空的 401，继续保持原有协议。
        if (request.getRequestURI().endsWith(TASK_RESULT_PATH)) {
            return false;
        }

        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskNo", null);
        body.put("status", "UNAUTHORIZED");
        body.put("message", "平台令牌无效");
        objectMapper.writeValue(response.getWriter(), body);
        return false;
    }
}
