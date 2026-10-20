package cn.catering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:catering-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.demo-password=Test@123456"
})
@AutoConfigureMockMvc
class ApiIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate db;

    @Test
    void anonymousRequestsAreRejectedAndKitchenDataIsScoped() throws Exception {
        mvc.perform(get("/api/data"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("请先登录"));

        Auth kitchen = login("kitchen");
        mvc.perform(get("/api/data").session(kitchen.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.ROLE_CODE").value("KITCHEN"))
                .andExpect(jsonPath("$.suppliers").isEmpty())
                .andExpect(jsonPath("$.purchaseOrders").isEmpty());
    }

    @Test
    void kitchenCannotApproveRequisition() throws Exception {
        Auth kitchen = login("kitchen");
        long id = createRequisition(kitchen, 1, 2, 1);

        mvc.perform(auth(post("/api/action/approve-requisition"), kitchen)
                        .content("{\"id\":" + id + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("当前角色没有此操作权限"));
    }

    @Test
    void completingRequisitionDeductsStockAndCreatesMovement() throws Exception {
        Auth kitchen = login("kitchen");
        Auth warehouse = login("warehouse");
        long id = createRequisition(kitchen, 1, 2, 2);
        BigDecimal before = stock(1, 2);

        mvc.perform(auth(post("/api/action/approve-requisition"), warehouse)
                        .content("{\"id\":" + id + "}"))
                .andExpect(status().isOk());
        mvc.perform(auth(post("/api/action/complete-requisition"), warehouse)
                        .content("{\"id\":" + id + "}"))
                .andExpect(status().isOk());

        assertThat(stock(1, 2)).isEqualByComparingTo(before.subtract(new BigDecimal("2")));
        Integer movements = db.queryForObject(
                "SELECT COUNT(*) FROM movement WHERE kind='OUT' AND store_id=1", Integer.class);
        assertThat(movements).isGreaterThanOrEqualTo(1);
        assertThat(db.queryForObject("SELECT status FROM requisition WHERE id=?", String.class, id))
                .isEqualTo("COMPLETED");
    }

    @Test
    void insufficientStockRollsBackTheWholeIssue() throws Exception {
        Auth kitchen = login("kitchen");
        Auth warehouse = login("warehouse");
        long id = createRequisition(kitchen, 1, 2, 999999);

        mvc.perform(auth(post("/api/action/approve-requisition"), warehouse)
                        .content("{\"id\":" + id + "}"))
                .andExpect(status().isOk());
        BigDecimal before = stock(1, 2);

        mvc.perform(auth(post("/api/action/complete-requisition"), warehouse)
                        .content("{\"id\":" + id + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("库存不足")));

        assertThat(stock(1, 2)).isEqualByComparingTo(before);
        assertThat(db.queryForObject("SELECT status FROM requisition WHERE id=?", String.class, id))
                .isEqualTo("APPROVED");
    }

    @Test
    void receivingPurchaseAddsBatchAndStock() throws Exception {
        Auth warehouse = login("warehouse");
        BigDecimal before = stock(2, 1);
        MvcResult created = mvc.perform(auth(post("/api/action/create-purchase"), warehouse)
                        .content("""
                                {
                                  "supplierId": 1,
                                  "warehouseId": 1,
                                  "lines": [{"ingredientId": 2, "quantity": 4, "unitCost": 22}]
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();

        mvc.perform(auth(post("/api/action/receive-purchase"), warehouse)
                        .content("{\"id\":" + id + "}"))
                .andExpect(status().isOk());

        assertThat(stock(2, 1)).isEqualByComparingTo(before.add(new BigDecimal("4")));
        assertThat(db.queryForObject("SELECT status FROM purchase_order WHERE id=?", String.class, id))
                .isEqualTo("RECEIVED");
    }

    @Test
    void adminCanCreateAccountsAndKitchenCannot() throws Exception {
        Auth admin = login("admin");
        mvc.perform(auth(post("/api/action/create-user"), admin)
                        .content("""
                                {
                                  "username": "linecook",
                                  "password": "LineCook@123",
                                  "name": "储备厨师",
                                  "roleId": 3,
                                  "storeId": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber());

        Auth created = loginWithPassword("linecook", "LineCook@123");
        mvc.perform(get("/api/data").session(created.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.ROLE_CODE").value("KITCHEN"));

        Auth kitchen = login("kitchen");
        mvc.perform(auth(post("/api/action/create-user"), kitchen)
                        .content("""
                                {
                                  "username": "forbidden-user",
                                  "password": "Forbidden@123",
                                  "name": "越权账号",
                                  "roleId": 2,
                                  "storeId": ""
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void userCanChangePasswordOnlyWithCurrentPassword() throws Exception {
        Auth admin = login("admin");
        mvc.perform(auth(post("/api/action/create-user"), admin)
                        .content("""
                                {
                                  "username": "passworduser",
                                  "password": "Initial@123",
                                  "name": "密码测试账号",
                                  "roleId": 2,
                                  "storeId": ""
                                }
                                """))
                .andExpect(status().isOk());

        Auth user = loginWithPassword("passworduser", "Initial@123");
        mvc.perform(auth(post("/api/action/change-password"), user)
                        .content("""
                                {"currentPassword": "wrong-password", "newPassword": "Updated@123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("当前密码不正确"));

        mvc.perform(auth(post("/api/action/change-password"), user)
                        .content("""
                                {"currentPassword": "Initial@123", "newPassword": "Updated@123"}
                                """))
                .andExpect(status().isOk());

        loginWithPassword("passworduser", "Updated@123");
        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"passworduser\",\"password\":\"Initial@123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void warehouseCanManageIngredientsAndAdjustStock() throws Exception {
        Auth warehouse = login("warehouse");
        MvcResult ingredientResult = mvc.perform(auth(post("/api/action/save-ingredient"), warehouse)
                        .content("""
                                {
                                  "name": "集成测试香料",
                                  "categoryId": 4,
                                  "specification": "测试规格",
                                  "unit": "kg",
                                  "price": 12.5,
                                  "shelfDays": 30,
                                  "minStock": 5,
                                  "maxStock": 20
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn();
        long ingredientId = json.readTree(ingredientResult.getResponse().getContentAsString()).path("id").asLong();

        mvc.perform(auth(post("/api/action/save-ingredient"), warehouse)
                        .content("""
                                {
                                  "id": %d,
                                  "name": "集成测试香料-更新",
                                  "categoryId": 4,
                                  "specification": "更新规格",
                                  "unit": "kg",
                                  "price": 13.5,
                                  "shelfDays": 45,
                                  "minStock": 6,
                                  "maxStock": 24
                                }
                                """.formatted(ingredientId)))
                .andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT name FROM ingredient WHERE id=?", String.class, ingredientId))
                .isEqualTo("集成测试香料-更新");

        BigDecimal before = db.queryForObject(
                "SELECT quantity FROM stock_batch WHERE id=1", BigDecimal.class);
        BigDecimal actual = before.subtract(new BigDecimal("5"));
        mvc.perform(auth(post("/api/action/adjust-stock"), warehouse)
                        .content("""
                                {"batchId": 1, "actualQuantity": %s, "note": "集成测试盘点"}
                                """.formatted(actual)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.difference").value(-5));
        assertThat(db.queryForObject("SELECT quantity FROM stock_batch WHERE id=1", BigDecimal.class))
                .isEqualByComparingTo(actual);
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM movement WHERE kind='ADJUST' AND batch_id=1", Integer.class))
                .isGreaterThanOrEqualTo(1);

        Auth kitchen = login("kitchen");
        mvc.perform(auth(post("/api/action/adjust-stock"), kitchen)
                        .content("""
                                {"batchId": 1, "actualQuantity": 1, "note": "越权盘点"}
                                """))
                .andExpect(status().isForbidden());
    }

    private long createRequisition(Auth auth, long storeId, long warehouseId, double quantity) throws Exception {
        MvcResult result = mvc.perform(auth(post("/api/action/create-requisition"), auth)
                        .content("""
                                {
                                  "storeId": %d,
                                  "warehouseId": %d,
                                  "note": "integration-test",
                                  "lines": [{"ingredientId": 1, "quantity": %s}]
                                }
                                """.formatted(storeId, warehouseId, quantity)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private Auth login(String username) throws Exception {
        return loginWithPassword(username, "Test@123456");
    }

    private Auth loginWithPassword(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        return new Auth((MockHttpSession) result.getRequest().getSession(false), body.path("csrf").asText());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder auth(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            Auth auth) {
        return builder.session(auth.session())
                .header("X-CSRF-Token", auth.csrf())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private BigDecimal stock(long ingredientId, long warehouseId) {
        BigDecimal value = db.queryForObject(
                "SELECT COALESCE(SUM(quantity),0) FROM stock_batch WHERE ingredient_id=? AND warehouse_id=?",
                BigDecimal.class, ingredientId, warehouseId);
        return value == null ? BigDecimal.ZERO : value;
    }

    private record Auth(MockHttpSession session, String csrf) {
    }
}
