package cn.catering;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final JdbcTemplate db;
    public WebConfig(JdbcTemplate db) { this.db = db; }
    public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(new Guard(db)).addPathPatterns("/api/**").excludePathPatterns("/api/login"); }
    static class Guard implements HandlerInterceptor {
        private final JdbcTemplate db;
        Guard(JdbcTemplate db) { this.db = db; }
        public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
            HttpSession s = req.getSession(false);
            if (s == null || s.getAttribute("uid") == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
            req.setAttribute("uid", s.getAttribute("uid"));
            if (!req.getMethod().equals("GET") && !req.getMethod().equals("HEAD") && !req.getMethod().equals("OPTIONS") && !s.getAttribute("csrf").equals(req.getHeader("X-CSRF-Token"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请求校验失败");
            return true;
        }
    }
    public static String hash(String value) { return new BCryptPasswordEncoder().encode(value); }
    public static boolean matches(String raw, String encoded) { return new BCryptPasswordEncoder().matches(raw, encoded); }
}
