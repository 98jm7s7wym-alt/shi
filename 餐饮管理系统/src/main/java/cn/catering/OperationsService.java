package cn.catering;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OperationsService {
    private final JdbcTemplate db;

    public OperationsService(JdbcTemplate db) {
        this.db = db;
    }

    public record Line(long ingredientId, BigDecimal quantity, BigDecimal unitCost) {
    }

    @Transactional
    public long createRequisition(long storeId, long warehouseId, String note, long uid, List<Line> lines) {
        validateLines(lines);
        requireRow("SELECT id FROM store WHERE id=?", storeId, "门店不存在");
        requireWarehouseForStore(warehouseId, storeId);

        String number = "LL-" + System.currentTimeMillis();
        long id = insert(
                "INSERT INTO requisition(number,store_id,warehouse_id,status,note,created_by) VALUES(?,?,?,?,?,?)",
                number, storeId, warehouseId, "PENDING", text(note, 500), uid);
        for (Line line : lines) {
            requireIngredient(line.ingredientId());
            db.update("INSERT INTO requisition_line(requisition_id,ingredient_id,quantity) VALUES(?,?,?)",
                    id, line.ingredientId(), line.quantity());
        }
        audit(uid, "CREATE_REQUISITION", number);
        return id;
    }

    @Transactional
    public void approveRequisition(long id, long uid) {
        Map<String, Object> requisition = lockRequisition(id);
        requireStatus(requisition, "PENDING", "只有待审批领料单可以审批");
        db.update("UPDATE requisition SET status='APPROVED' WHERE id=?", id);
        audit(uid, "APPROVE_REQUISITION", String.valueOf(requisition.get("number")));
    }

    @Transactional
    public void completeRequisition(long id, long uid) {
        Map<String, Object> requisition = lockRequisition(id);
        requireStatus(requisition, "APPROVED", "只有已通过的领料单可以出库");

        long storeId = number(requisition.get("store_id")).longValue();
        long warehouseId = number(requisition.get("warehouse_id")).longValue();
        String requisitionNumber = String.valueOf(requisition.get("number"));
        List<Map<String, Object>> lines = db.queryForList(
                "SELECT ingredient_id,quantity FROM requisition_line WHERE requisition_id=? ORDER BY id", id);
        if (lines.isEmpty()) {
            throw conflict("领料单没有明细，无法出库");
        }

        for (Map<String, Object> line : lines) {
            long ingredientId = number(line.get("ingredient_id")).longValue();
            BigDecimal remaining = decimal(line.get("quantity"));
            List<Map<String, Object>> batches = db.queryForList(
                    "SELECT id,quantity FROM stock_batch " +
                            "WHERE ingredient_id=? AND warehouse_id=? AND quantity>0 " +
                            "AND (expiry_date IS NULL OR expiry_date>=CURRENT_DATE) " +
                            "ORDER BY expiry_date,id FOR UPDATE",
                    ingredientId, warehouseId);

            for (Map<String, Object> batch : batches) {
                if (remaining.signum() <= 0) {
                    break;
                }
                long batchId = number(batch.get("id")).longValue();
                BigDecimal available = decimal(batch.get("quantity"));
                BigDecimal used = available.min(remaining);
                db.update("UPDATE stock_batch SET quantity=quantity-? WHERE id=?", used, batchId);
                db.update(
                        "INSERT INTO movement(batch_id,kind,quantity,reference_no,actor_id,store_id) VALUES(?,?,?,?,?,?)",
                        batchId, "OUT", used, requisitionNumber, uid, storeId);
                remaining = remaining.subtract(used);
            }

            if (remaining.signum() > 0) {
                String name = db.queryForObject("SELECT name FROM ingredient WHERE id=?", String.class, ingredientId);
                throw conflict(name + " 可用库存不足，缺少 " + remaining.stripTrailingZeros().toPlainString());
            }
        }

        db.update("UPDATE requisition SET status='COMPLETED' WHERE id=?", id);
        audit(uid, "COMPLETE_REQUISITION", requisitionNumber);
    }

    @Transactional
    public long createPurchase(long supplierId, long warehouseId, long uid, List<Line> lines) {
        validateLines(lines);
        requireRow("SELECT id FROM supplier WHERE id=?", supplierId, "供应商不存在");
        requireRow("SELECT id FROM warehouse WHERE id=?", warehouseId, "仓库不存在");

        BigDecimal total = lines.stream()
                .map(line -> line.quantity().multiply(line.unitCost()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String number = "PO-" + System.currentTimeMillis();
        long id = insert(
                "INSERT INTO purchase_order(number,supplier_id,warehouse_id,status,total) VALUES(?,?,?,?,?)",
                number, supplierId, warehouseId, "PENDING", total);
        for (Line line : lines) {
            requireIngredient(line.ingredientId());
            db.update(
                    "INSERT INTO purchase_line(purchase_order_id,ingredient_id,quantity,unit_cost) VALUES(?,?,?,?)",
                    id, line.ingredientId(), line.quantity(), line.unitCost());
        }
        audit(uid, "CREATE_PURCHASE", number + " / " + total.toPlainString());
        return id;
    }

    @Transactional
    public void receivePurchase(long id, long uid) {
        List<Map<String, Object>> orders = db.queryForList(
                "SELECT * FROM purchase_order WHERE id=? FOR UPDATE", id);
        if (orders.isEmpty()) {
            throw notFound("采购单不存在");
        }
        Map<String, Object> order = orders.get(0);
        requireStatus(order, "PENDING", "只有待审核采购单可以入库");

        long supplierId = number(order.get("supplier_id")).longValue();
        long warehouseId = number(order.get("warehouse_id")).longValue();
        String orderNumber = String.valueOf(order.get("number"));
        List<Map<String, Object>> lines = db.queryForList(
                "SELECT * FROM purchase_line WHERE purchase_order_id=? ORDER BY id", id);
        if (lines.isEmpty()) {
            throw conflict("采购单没有明细，无法入库");
        }

        for (Map<String, Object> line : lines) {
            long ingredientId = number(line.get("ingredient_id")).longValue();
            List<Map<String, Object>> ingredients = db.queryForList(
                    "SELECT shelf_days FROM ingredient WHERE id=?", ingredientId);
            if (ingredients.isEmpty()) {
                throw conflict("采购明细中的食材不存在");
            }
            Object shelfDays = ingredients.get(0).get("shelf_days");
            LocalDate expiry = shelfDays == null
                    ? null
                    : LocalDate.now().plusDays(number(shelfDays).intValue());
            String batchNumber = orderNumber + "-" + line.get("id");
            long batchId = insert(
                    "INSERT INTO stock_batch(ingredient_id,warehouse_id,supplier_id,batch_no,expiry_date,quantity,unit_cost) " +
                            "VALUES(?,?,?,?,?,?,?)",
                    ingredientId, warehouseId, supplierId, batchNumber,
                    expiry == null ? null : Date.valueOf(expiry),
                    decimal(line.get("quantity")), decimal(line.get("unit_cost")));
            db.update(
                    "INSERT INTO movement(batch_id,kind,quantity,reference_no,actor_id,store_id) VALUES(?,?,?,?,?,NULL)",
                    batchId, "IN", decimal(line.get("quantity")), orderNumber, uid);
        }

        db.update("UPDATE purchase_order SET status='RECEIVED' WHERE id=?", id);
        audit(uid, "RECEIVE_PURCHASE", orderNumber);
    }

    @Transactional
    public long createSupplier(String name, String contact, String phone, String address, long uid) {
        String supplierName = requiredText(name, 100, "供应商名称不能为空");
        long id = insert("INSERT INTO supplier(name,contact,phone,address) VALUES(?,?,?,?)",
                supplierName, text(contact, 50), text(phone, 30), text(address, 200));
        audit(uid, "CREATE_SUPPLIER", supplierName);
        return id;
    }

    @Transactional
    public long saveIngredient(
            Long id,
            String name,
            long categoryId,
            String specification,
            String unit,
            BigDecimal price,
            int shelfDays,
            BigDecimal minStock,
            BigDecimal maxStock,
            long uid) {
        String ingredientName = requiredText(name, 100, "食材名称不能为空");
        String ingredientUnit = requiredText(unit, 20, "计量单位不能为空");
        requireRow("SELECT id FROM category WHERE id=?", categoryId, "食材分类不存在");
        requireNonNegative(price, "参考价格不能为负数");
        requireNonNegative(minStock, "安全库存不能为负数");
        requireNonNegative(maxStock, "目标库存不能为负数");
        if (shelfDays < 0) {
            throw badRequest("保质期不能为负数");
        }
        if (maxStock.signum() > 0 && maxStock.compareTo(minStock) < 0) {
            throw badRequest("目标库存不能小于安全库存");
        }

        long ingredientId;
        if (id == null) {
            ingredientId = insert(
                    "INSERT INTO ingredient(name,category_id,specification,unit,price,shelf_days,min_stock,max_stock) " +
                            "VALUES(?,?,?,?,?,?,?,?)",
                    ingredientName, categoryId, text(specification, 100), ingredientUnit,
                    price, shelfDays, minStock, maxStock);
            audit(uid, "CREATE_INGREDIENT", ingredientName);
        } else {
            requireRow("SELECT id FROM ingredient WHERE id=?", id, "食材不存在");
            db.update(
                    "UPDATE ingredient SET name=?,category_id=?,specification=?,unit=?,price=?,shelf_days=?,min_stock=?,max_stock=? WHERE id=?",
                    ingredientName, categoryId, text(specification, 100), ingredientUnit,
                    price, shelfDays, minStock, maxStock, id);
            ingredientId = id;
            audit(uid, "UPDATE_INGREDIENT", ingredientName);
        }
        return ingredientId;
    }

    @Transactional
    public Map<String, Object> adjustStock(long batchId, BigDecimal actualQuantity, String note, long uid) {
        requireNonNegative(actualQuantity, "盘点数量不能为负数");
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT * FROM stock_batch WHERE id=? FOR UPDATE", batchId);
        if (rows.isEmpty()) {
            throw notFound("库存批次不存在");
        }
        Map<String, Object> batch = rows.get(0);
        BigDecimal previous = decimal(batch.get("quantity"));
        BigDecimal difference = actualQuantity.subtract(previous);
        if (difference.signum() == 0) {
            throw badRequest("盘点数量与当前库存一致，无需调整");
        }
        String reference = "PD-" + System.currentTimeMillis();
        db.update("UPDATE stock_batch SET quantity=? WHERE id=?", actualQuantity, batchId);
        db.update(
                "INSERT INTO movement(batch_id,kind,quantity,reference_no,actor_id,store_id) VALUES(?,?,?,?,?,NULL)",
                batchId, "ADJUST", difference, reference, uid);
        audit(uid, "ADJUST_STOCK", reference + " / " + batch.get("batch_no") + " / "
                + previous.toPlainString() + " -> " + actualQuantity.toPlainString()
                + (note == null || note.isBlank() ? "" : " / " + text(note, 160)));
        return Map.of(
                "ok", true,
                "previous", previous,
                "actual", actualQuantity,
                "difference", difference);
    }

    @Transactional
    public Map<String, Object> inboundStock(
            long ingredientId,
            long warehouseId,
            Long supplierId,
            String batchNo,
            LocalDate expiryDate,
            BigDecimal quantity,
            BigDecimal unitCost,
            long uid) {
        requirePositive(quantity, "入库数量必须大于 0");
        requireNonNegative(unitCost, "单位成本不能为负数");
        requireIngredient(ingredientId);
        requireRow("SELECT id FROM warehouse WHERE id=?", warehouseId, "仓库不存在");
        if (supplierId != null) {
            requireRow("SELECT id FROM supplier WHERE id=?", supplierId, "供应商不存在");
        }

        String normalizedBatchNo = batchNo == null || batchNo.isBlank()
                ? "IN-" + System.currentTimeMillis()
                : text(batchNo, 80);
        Integer duplicate = db.queryForObject(
                "SELECT COUNT(*) FROM stock_batch WHERE ingredient_id=? AND warehouse_id=? AND batch_no=?",
                Integer.class, ingredientId, warehouseId, normalizedBatchNo);
        if (duplicate != null && duplicate > 0) {
            throw conflict("该仓库已存在相同食材和批次号");
        }

        long batchId = insert(
                "INSERT INTO stock_batch(ingredient_id,warehouse_id,supplier_id,batch_no,expiry_date,quantity,unit_cost) " +
                        "VALUES(?,?,?,?,?,?,?)",
                ingredientId, warehouseId, supplierId, normalizedBatchNo,
                expiryDate == null ? null : Date.valueOf(expiryDate), quantity, unitCost);
        db.update(
                "INSERT INTO movement(batch_id,kind,quantity,reference_no,actor_id,store_id) VALUES(?,?,?,?,?,NULL)",
                batchId, "IN", quantity, normalizedBatchNo, uid);
        audit(uid, "STOCK_IN", normalizedBatchNo + " / +" + quantity.toPlainString());
        return Map.of("ok", true, "batchId", batchId, "batchNo", normalizedBatchNo, "quantity", quantity);
    }

    @Transactional
    public Map<String, Object> outboundStock(
            long batchId,
            BigDecimal quantity,
            String referenceNo,
            Long storeId,
            long uid) {
        requirePositive(quantity, "出库数量必须大于 0");
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT * FROM stock_batch WHERE id=? FOR UPDATE", batchId);
        if (rows.isEmpty()) {
            throw notFound("库存批次不存在");
        }
        Map<String, Object> batch = rows.get(0);
        BigDecimal available = decimal(batch.get("quantity"));
        if (quantity.compareTo(available) > 0) {
            throw conflict("出库数量不能大于批次剩余库存");
        }
        BigDecimal remaining = available.subtract(quantity);
        db.update("UPDATE stock_batch SET quantity=? WHERE id=?", remaining, batchId);
        String normalizedReference = referenceNo == null || referenceNo.isBlank()
                ? "CK-" + System.currentTimeMillis()
                : text(referenceNo, 80);
        db.update(
                "INSERT INTO movement(batch_id,kind,quantity,reference_no,actor_id,store_id) VALUES(?,?,?,?,?,?)",
                batchId, "OUT", quantity, normalizedReference, uid, storeId);
        audit(uid, "STOCK_OUT", normalizedReference + " / -" + quantity.toPlainString());
        return Map.of(
                "ok", true,
                "batchId", batchId,
                "quantity", quantity,
                "remaining", remaining);
    }

    @Transactional
    public void updateProfile(long uid, String name) {
        String displayName = requiredText(name, 50, "显示名称不能为空");
        db.update("UPDATE app_user SET name=? WHERE id=?", displayName, uid);
        audit(uid, "UPDATE_PROFILE", displayName);
    }

    @Transactional
    public void changePassword(long uid, String currentPassword, String newPassword) {
        String storedHash = db.queryForObject(
                "SELECT password_hash FROM app_user WHERE id=?", String.class, uid);
        if (storedHash == null || !WebConfig.matches(currentPassword, storedHash)) {
            throw badRequest("当前密码不正确");
        }
        validatePassword(newPassword);
        if (WebConfig.matches(newPassword, storedHash)) {
            throw badRequest("新密码不能与当前密码相同");
        }
        db.update("UPDATE app_user SET password_hash=? WHERE id=?", WebConfig.hash(newPassword), uid);
        audit(uid, "CHANGE_PASSWORD", "用户修改登录密码");
    }

    @Transactional
    public long createUser(String username, String password, String name, long roleId, Long storeId, long actorId) {
        String normalizedUsername = requiredText(username, 30, "账号不能为空").toLowerCase();
        if (!normalizedUsername.matches("[a-z0-9._-]{3,30}")) {
            throw badRequest("账号只能包含字母、数字、点、下划线或短横线，长度 3-30 位");
        }
        Integer duplicates = db.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE LOWER(username)=?", Integer.class, normalizedUsername);
        if (duplicates != null && duplicates > 0) {
            throw conflict("账号已存在");
        }

        List<Map<String, Object>> roles = db.queryForList(
                "SELECT code FROM app_role WHERE id=?", roleId);
        if (roles.isEmpty()) {
            throw badRequest("角色不存在");
        }
        String roleCode = String.valueOf(roles.get(0).get("code"));
        if ("KITCHEN".equalsIgnoreCase(roleCode)) {
            if (storeId == null) {
                throw badRequest("门店厨房账号必须绑定门店");
            }
            requireRow("SELECT id FROM store WHERE id=?", storeId, "门店不存在");
        } else {
            storeId = null;
        }

        validatePassword(password);
        String displayName = requiredText(name, 50, "显示名称不能为空");
        long id = insert(
                "INSERT INTO app_user(username,password_hash,name,role_id,store_id,active) VALUES(?,?,?,?,?,TRUE)",
                normalizedUsername, WebConfig.hash(password), displayName, roleId, storeId);
        audit(actorId, "CREATE_USER", normalizedUsername + " / " + roleCode);
        return id;
    }

    private Map<String, Object> lockRequisition(long id) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT * FROM requisition WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw notFound("领料单不存在");
        }
        return rows.get(0);
    }

    private void requireStatus(Map<String, Object> row, String expected, String message) {
        if (!expected.equalsIgnoreCase(String.valueOf(row.get("status")))) {
            throw conflict(message);
        }
    }

    private void requireWarehouseForStore(long warehouseId, long storeId) {
        Integer count = db.queryForObject(
                "SELECT COUNT(*) FROM warehouse WHERE id=? AND (store_id IS NULL OR store_id=?)",
                Integer.class, warehouseId, storeId);
        if (count == null || count == 0) {
            throw badRequest("出库仓库与门店不匹配");
        }
    }

    private void requireIngredient(long id) {
        requireRow("SELECT id FROM ingredient WHERE id=?", id, "食材不存在");
    }

    private void requireRow(String sql, long id, String message) {
        if (db.queryForList(sql, id).isEmpty()) {
            throw badRequest(message);
        }
    }

    private void validateLines(List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            throw badRequest("至少需要一条业务明细");
        }
        if (lines.size() > 100) {
            throw badRequest("单次最多提交 100 条明细");
        }
        for (Line line : lines) {
            if (line.ingredientId() <= 0 || line.quantity() == null || line.quantity().signum() <= 0) {
                throw badRequest("食材和数量必须有效");
            }
            if (line.unitCost() != null && line.unitCost().signum() < 0) {
                throw badRequest("单价不能为负数");
            }
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8) {
            throw badRequest("密码至少需要 8 个字符");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw badRequest("密码过长，请控制在 72 个字节以内");
        }
    }

    private void requireNonNegative(BigDecimal value, String message) {
        if (value == null || value.signum() < 0) {
            throw badRequest(message);
        }
    }

    private void requirePositive(BigDecimal value, String message) {
        if (value == null || value.signum() <= 0) {
            throw badRequest(message);
        }
    }

    private long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        db.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("数据库未返回新增记录 ID");
        }
        return key.longValue();
    }

    private void audit(long uid, String action, String detail) {
        db.update("INSERT INTO audit_log(actor_id,action,detail) VALUES(?,?,?)",
                uid, action, text(detail, 500));
    }

    private Number number(Object value) {
        if (value instanceof Number numeric) {
            return numeric;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private String requiredText(String value, int maxLength, String message) {
        String cleaned = text(value, maxLength);
        if (cleaned.isBlank()) {
            throw badRequest(message);
        }
        return cleaned;
    }

    private String text(Object value, int maxLength) {
        String cleaned = value == null ? "" : String.valueOf(value).trim();
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
