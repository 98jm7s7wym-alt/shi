package cn.catering;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final JdbcTemplate db;
    private final OperationsService operations;

    public ApiController(JdbcTemplate db, OperationsService operations) {
        this.db = db;
        this.operations = operations;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        var rows = db.queryForList(
                "SELECT u.*,r.name role_name,r.code role_code,r.permissions FROM app_user u " +
                        "JOIN app_role r ON u.role_id=r.id WHERE u.username=? AND u.active=TRUE",
                String.valueOf(body.getOrDefault("username", "")));
        if (rows.isEmpty()
                || !WebConfig.matches(String.valueOf(body.getOrDefault("password", "")),
                String.valueOf(rows.get(0).get("password_hash")))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }

        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute("uid", rows.get(0).get("id"));
        session.setAttribute("csrf", UUID.randomUUID().toString());
        return Map.of("user", safe(rows.get(0)), "csrf", session.getAttribute("csrf"));
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        return Map.of(
                "user", current(request),
                "csrf", request.getSession().getAttribute("csrf"));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        request.getSession().invalidate();
        return Map.of("ok", true);
    }

    @GetMapping("/data")
    public Map<String, Object> data(HttpServletRequest request) {
        boolean kitchen = "KITCHEN".equalsIgnoreCase(String.valueOf(request.getAttribute("roleCode")));
        Long storeId = kitchen ? longObject(request.getAttribute("storeId")) : null;
        boolean canPurchase = hasPermission(request, "purchase") || hasPermission(request, "report");
        boolean canSystem = hasPermission(request, "system");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", current(request));
        result.put("stats", stats());
        result.put("stores", kitchen
                ? rows("SELECT * FROM store WHERE id=? ORDER BY id", storeId)
                : rows("SELECT * FROM store ORDER BY id"));
        result.put("warehouses", kitchen
                ? rows("SELECT w.*,s.name store_name FROM warehouse w LEFT JOIN store s ON w.store_id=s.id " +
                "WHERE w.store_id=? ORDER BY w.id", storeId)
                : rows("SELECT w.*,s.name store_name FROM warehouse w LEFT JOIN store s ON w.store_id=s.id ORDER BY w.id"));
        result.put("categories", rows("SELECT * FROM category ORDER BY id"));
        result.put("ingredients", rows(
                "SELECT i.*,c.name category_name,COALESCE(SUM(b.quantity),0) stock " +
                        "FROM ingredient i JOIN category c ON i.category_id=c.id " +
                        "LEFT JOIN stock_batch b ON b.ingredient_id=i.id " +
                        "GROUP BY i.id,c.name ORDER BY i.id"));
        result.put("batches", kitchen
                ? rows("SELECT b.*,i.name ingredient_name,w.name warehouse_name,s.name supplier_name " +
                "FROM stock_batch b JOIN ingredient i ON b.ingredient_id=i.id " +
                "JOIN warehouse w ON b.warehouse_id=w.id LEFT JOIN supplier s ON b.supplier_id=s.id " +
                "WHERE w.store_id=? ORDER BY b.expiry_date", storeId)
                : rows("SELECT b.*,i.name ingredient_name,w.name warehouse_name,s.name supplier_name " +
                "FROM stock_batch b JOIN ingredient i ON b.ingredient_id=i.id " +
                "JOIN warehouse w ON b.warehouse_id=w.id LEFT JOIN supplier s ON b.supplier_id=s.id " +
                "ORDER BY b.expiry_date"));
        result.put("suppliers", canPurchase ? rows("SELECT * FROM supplier ORDER BY id") : List.of());
        result.put("requisitions", kitchen
                ? rows("SELECT r.*,s.name store_name,u.name creator," +
                "(SELECT COUNT(*) FROM requisition_line l WHERE l.requisition_id=r.id) line_count," +
                "(SELECT COALESCE(SUM(l.quantity),0) FROM requisition_line l WHERE l.requisition_id=r.id) total_quantity " +
                "FROM requisition r JOIN store s ON r.store_id=s.id JOIN app_user u ON r.created_by=u.id " +
                "WHERE r.store_id=? ORDER BY r.created_at DESC", storeId)
                : rows("SELECT r.*,s.name store_name,u.name creator," +
                "(SELECT COUNT(*) FROM requisition_line l WHERE l.requisition_id=r.id) line_count," +
                "(SELECT COALESCE(SUM(l.quantity),0) FROM requisition_line l WHERE l.requisition_id=r.id) total_quantity " +
                "FROM requisition r JOIN store s ON r.store_id=s.id JOIN app_user u ON r.created_by=u.id " +
                "ORDER BY r.created_at DESC"));
        result.put("requisitionLines", kitchen
                ? rows("SELECT l.*,i.name ingredient_name,i.unit,r.number requisition_number " +
                "FROM requisition_line l JOIN ingredient i ON l.ingredient_id=i.id " +
                "JOIN requisition r ON l.requisition_id=r.id WHERE r.store_id=? " +
                "ORDER BY l.requisition_id DESC,l.id", storeId)
                : rows("SELECT l.*,i.name ingredient_name,i.unit,r.number requisition_number " +
                "FROM requisition_line l JOIN ingredient i ON l.ingredient_id=i.id " +
                "JOIN requisition r ON l.requisition_id=r.id ORDER BY l.requisition_id DESC,l.id"));
        result.put("purchaseOrders", canPurchase
                ? rows("SELECT po.*,s.name supplier_name,w.name warehouse_name," +
                "(SELECT COUNT(*) FROM purchase_line pl WHERE pl.purchase_order_id=po.id) line_count " +
                "FROM purchase_order po JOIN supplier s ON po.supplier_id=s.id " +
                "JOIN warehouse w ON po.warehouse_id=w.id ORDER BY po.created_at DESC")
                : List.of());
        result.put("purchaseLines", canPurchase
                ? rows("SELECT pl.*,i.name ingredient_name,i.unit,c.name category_name,po.number purchase_number " +
                "FROM purchase_line pl JOIN ingredient i ON pl.ingredient_id=i.id " +
                "JOIN category c ON i.category_id=c.id " +
                "JOIN purchase_order po ON pl.purchase_order_id=po.id ORDER BY pl.purchase_order_id DESC,pl.id")
                : List.of());
        result.put("movements", kitchen
                ? rows("SELECT m.*,i.name ingredient_name,b.batch_no FROM movement m " +
                "JOIN stock_batch b ON m.batch_id=b.id JOIN ingredient i ON b.ingredient_id=i.id " +
                "WHERE m.store_id=? ORDER BY m.created_at DESC LIMIT 30", storeId)
                : rows("SELECT m.*,i.name ingredient_name,b.batch_no FROM movement m " +
                "JOIN stock_batch b ON m.batch_id=b.id JOIN ingredient i ON b.ingredient_id=i.id " +
                "ORDER BY m.created_at DESC LIMIT 30"));
        result.put("users", canSystem
                ? rows("SELECT u.id,u.username,u.name,u.role_id,u.store_id,u.active,r.name role_name,r.code role_code," +
                "s.name store_name FROM app_user u JOIN app_role r ON u.role_id=r.id " +
                "LEFT JOIN store s ON u.store_id=s.id ORDER BY u.id")
                : List.of());
        result.put("roles", canSystem
                ? rows("SELECT id,name,code,permissions FROM app_role ORDER BY id")
                : List.of());
        result.put("auditLogs", canSystem
                ? rows("SELECT a.*,u.name actor_name FROM audit_log a " +
                "LEFT JOIN app_user u ON a.actor_id=u.id ORDER BY a.created_at DESC LIMIT 50")
                : List.of());
        result.put("permissions", permissions(request));
        return result;
    }

    @PostMapping("/action/{action}")
    public Map<String, Object> action(
            @PathVariable String action,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        long uid = longValue(request.getAttribute("uid"));
        return switch (action) {
            case "create-requisition" -> {
                requirePermission(request, "kitchen");
                long storeId = requiredLong(body, "storeId");
                long warehouseId = requiredLong(body, "warehouseId");
                if ("KITCHEN".equalsIgnoreCase(String.valueOf(request.getAttribute("roleCode")))
                        && !Long.valueOf(storeId).equals(longObject(request.getAttribute("storeId")))) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能为本门店创建领料单");
                }
                long id = operations.createRequisition(
                        storeId, warehouseId, String.valueOf(body.getOrDefault("note", "")), uid,
                        lines(body, false));
                String number = db.queryForObject("SELECT number FROM requisition WHERE id=?", String.class, id);
                yield Map.of("id", id, "number", number);
            }
            case "approve-requisition" -> {
                requirePermission(request, "stock");
                operations.approveRequisition(requiredLong(body, "id"), uid);
                yield Map.of("ok", true);
            }
            case "complete-requisition" -> {
                requirePermission(request, "stock");
                operations.completeRequisition(requiredLong(body, "id"), uid);
                yield Map.of("ok", true);
            }
            case "create-purchase" -> {
                requirePermission(request, "purchase");
                long id = operations.createPurchase(
                        requiredLong(body, "supplierId"),
                        requiredLong(body, "warehouseId"),
                        uid,
                        lines(body, true));
                String number = db.queryForObject("SELECT number FROM purchase_order WHERE id=?", String.class, id);
                yield Map.of("id", id, "number", number);
            }
            case "receive-purchase" -> {
                requirePermission(request, "purchase");
                operations.receivePurchase(requiredLong(body, "id"), uid);
                yield Map.of("ok", true);
            }
            case "create-supplier" -> {
                requirePermission(request, "purchase");
                long id = operations.createSupplier(
                        String.valueOf(body.getOrDefault("name", "")),
                        String.valueOf(body.getOrDefault("contact", "")),
                        String.valueOf(body.getOrDefault("phone", "")),
                        String.valueOf(body.getOrDefault("address", "")),
                        uid);
                yield Map.of("id", id);
            }
            case "save-ingredient" -> {
                requirePermission(request, "stock");
                long id = operations.saveIngredient(
                        optionalLong(body, "id"),
                        String.valueOf(body.getOrDefault("name", "")),
                        requiredLong(body, "categoryId"),
                        String.valueOf(body.getOrDefault("specification", "")),
                        String.valueOf(body.getOrDefault("unit", "")),
                        requiredDecimal(body, "price"),
                        Math.toIntExact(requiredLong(body, "shelfDays")),
                        requiredDecimal(body, "minStock"),
                        requiredDecimal(body, "maxStock"),
                        uid);
                yield Map.of("id", id);
            }
            case "adjust-stock" -> {
                requirePermission(request, "stock");
                yield operations.adjustStock(
                        requiredLong(body, "batchId"),
                        requiredDecimal(body, "actualQuantity"),
                        String.valueOf(body.getOrDefault("note", "")),
                        uid);
            }
            case "update-profile" -> {
                operations.updateProfile(uid, String.valueOf(body.getOrDefault("name", "")));
                yield Map.of("ok", true);
            }
            case "change-password" -> {
                operations.changePassword(
                        uid,
                        String.valueOf(body.getOrDefault("currentPassword", "")),
                        String.valueOf(body.getOrDefault("newPassword", "")));
                yield Map.of("ok", true);
            }
            case "create-user" -> {
                requirePermission(request, "system");
                long id = operations.createUser(
                        String.valueOf(body.getOrDefault("username", "")),
                        String.valueOf(body.getOrDefault("password", "")),
                        String.valueOf(body.getOrDefault("name", "")),
                        requiredLong(body, "roleId"),
                        optionalLong(body, "storeId"),
                        uid);
                yield Map.of("id", id);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知操作");
        };
    }

    @GetMapping(value = "/export/inventory", produces = "text/csv;charset=UTF-8")
    public String export(HttpServletRequest request) {
        if (!hasPermission(request, "stock") && !hasPermission(request, "report")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有库存导出权限");
        }
        StringBuilder csv = new StringBuilder("食材,仓库,批次,数量,单位成本,到期日\r\n");
        for (var row : db.queryForList(
                "SELECT i.name ingredient_name,w.name warehouse_name,b.batch_no,b.quantity,b.unit_cost,b.expiry_date " +
                        "FROM stock_batch b JOIN ingredient i ON b.ingredient_id=i.id " +
                        "JOIN warehouse w ON b.warehouse_id=w.id ORDER BY b.expiry_date")) {
            csv.append(String.join(",",
                    csv(row.get("ingredient_name")),
                    csv(row.get("warehouse_name")),
                    csv(row.get("batch_no")),
                    csv(row.get("quantity")),
                    csv(row.get("unit_cost")),
                    csv(row.get("expiry_date")))).append("\r\n");
        }
        return csv.toString();
    }

    private Map<String, Object> stats() {
        return up(db.queryForMap(
                "SELECT (SELECT COUNT(*) FROM ingredient) ingredient_count," +
                        "COALESCE((SELECT SUM(quantity*unit_cost) FROM stock_batch),0) stock_value," +
                        "(SELECT COUNT(*) FROM stock_batch WHERE expiry_date<=DATEADD('DAY',3,CURRENT_DATE)) expiring_count," +
                        "(SELECT COUNT(*) FROM ingredient i WHERE COALESCE(" +
                        "(SELECT SUM(b.quantity) FROM stock_batch b WHERE b.ingredient_id=i.id),0)<i.min_stock) low_count"));
    }

    private Map<String, Object> current(HttpServletRequest request) {
        return up(safe(db.queryForMap(
                "SELECT u.id,u.username,u.name,u.store_id,r.name role_name,r.code role_code,r.permissions " +
                        "FROM app_user u JOIN app_role r ON u.role_id=r.id WHERE u.id=?",
                request.getAttribute("uid"))));
    }

    private List<OperationsService.Line> lines(Map<String, Object> body, boolean requireCost) {
        Object raw = body.get("lines");
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少需要一条业务明细");
        }
        List<OperationsService.Line> lines = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> line)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "业务明细格式不正确");
            }
            long ingredientId = requiredLong(line, "ingredientId");
            BigDecimal quantity = requiredDecimal(line, "quantity");
            BigDecimal unitCost = requireCost ? requiredDecimal(line, "unitCost") : BigDecimal.ZERO;
            lines.add(new OperationsService.Line(ingredientId, quantity, unitCost));
        }
        return lines;
    }

    private long requiredLong(Map<?, ?> body, String key) {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " 不能为空");
        }
        try {
            return new BigDecimal(String.valueOf(value)).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " 格式不正确");
        }
    }

    private Long optionalLong(Map<?, ?> body, String key) {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return requiredLong(body, key);
    }

    private BigDecimal requiredDecimal(Map<?, ?> body, String key) {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " 不能为空");
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " 格式不正确");
        }
    }

    private boolean hasPermission(HttpServletRequest request, String permission) {
        return permissions(request).contains(permission);
    }

    private void requirePermission(HttpServletRequest request, String permission) {
        if (!hasPermission(request, permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色没有此操作权限");
        }
    }

    private List<String> permissions(HttpServletRequest request) {
        String raw = String.valueOf(request.getAttribute("permissions"));
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        return up(db.queryForList(sql, args));
    }

    private Map<String, Object> up(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        row.forEach((key, value) -> result.put(key.toUpperCase(Locale.ROOT), value));
        return result;
    }

    private List<Map<String, Object>> up(List<Map<String, Object>> rows) {
        return rows.stream().map(this::up).toList();
    }

    private Map<String, Object> safe(Map<String, Object> row) {
        var result = new LinkedHashMap<>(row);
        result.remove("PASSWORD_HASH");
        result.remove("password_hash");
        return result;
    }

    private String csv(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private long longValue(Object value) {
        return value instanceof Number numeric ? numeric.longValue() : Long.parseLong(String.valueOf(value));
    }

    private Long longObject(Object value) {
        return value == null ? null : longValue(value);
    }
}
