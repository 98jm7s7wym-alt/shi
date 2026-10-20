package cn.catering;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Objects;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final JdbcTemplate db;

    public WebConfig(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new Guard(db))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/login");
    }

    static class Guard implements HandlerInterceptor {
        private final JdbcTemplate db;

        Guard(JdbcTemplate db) {
            this.db = db;
        }

        @Override
        public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
            HttpSession s = req.getSession(false);
            if (s == null || s.getAttribute("uid") == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
            }

            var users = db.queryForList(
                    "SELECT u.store_id,r.code role_code,r.permissions FROM app_user u " +
                            "JOIN app_role r ON u.role_id=r.id WHERE u.id=? AND u.active=TRUE",
                    s.getAttribute("uid"));
            if (users.isEmpty()) {
                s.invalidate();
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号已停用，请重新登录");
            }

            boolean safeMethod = Objects.equals(req.getMethod(), "GET")
                    || Objects.equals(req.getMethod(), "HEAD")
                    || Objects.equals(req.getMethod(), "OPTIONS");
            if (!safeMethod && !Objects.equals(s.getAttribute("csrf"), req.getHeader("X-CSRF-Token"))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请求校验失败");
            }

            var user = users.get(0);
            req.setAttribute("uid", s.getAttribute("uid"));
            req.setAttribute("storeId", user.get("store_id"));
            req.setAttribute("roleCode", user.get("role_code"));
            req.setAttribute("permissions", user.get("permissions"));
            return true;
        }
    }

    public static String hash(String value) {
        return new BCryptPasswordEncoder().encode(value);
    }

    public static boolean matches(String raw, String encoded) {
        return new BCryptPasswordEncoder().matches(raw, encoded);
    }
}
