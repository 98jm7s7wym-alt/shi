package cn.catering;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final JdbcTemplate db;
    public ApiController(JdbcTemplate db) { this.db=db; }
    @PostMapping("/login") public Map<String,Object> login(@RequestBody Map<String,Object> b,HttpServletRequest req){
        var rows=db.queryForList("SELECT u.*,r.name role_name,r.code role_code,r.permissions FROM app_user u JOIN app_role r ON u.role_id=r.id WHERE u.username=? AND u.active=TRUE",String.valueOf(b.getOrDefault("username","")));
        if(rows.isEmpty()||!WebConfig.matches(String.valueOf(b.getOrDefault("password","")),rows.get(0).get("password_hash").toString())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"用户名或密码错误");
        var s=req.getSession(true); s.setAttribute("uid",rows.get(0).get("id")); s.setAttribute("csrf",UUID.randomUUID().toString());
        return Map.of("user",safe(rows.get(0)),"csrf",s.getAttribute("csrf"));
    }
    @GetMapping("/me") public Map<String,Object> me(HttpServletRequest req){return Map.of("user",current(req),"csrf",req.getSession().getAttribute("csrf"));}
    @PostMapping("/logout") public Map<String,Object> logout(HttpServletRequest req){req.getSession().invalidate();return Map.of("ok",true);}
    @GetMapping("/data") public Map<String,Object> data(HttpServletRequest req){
        return Map.of("user",current(req),"stats",stats(),"ingredients",up(db.queryForList("SELECT i.*,c.name category_name,COALESCE(SUM(b.quantity),0) stock FROM ingredient i JOIN category c ON i.category_id=c.id LEFT JOIN stock_batch b ON b.ingredient_id=i.id GROUP BY i.id,c.name ORDER BY i.id")),"batches",up(db.queryForList("SELECT b.*,i.name ingredient_name,w.name warehouse_name,s.name supplier_name FROM stock_batch b JOIN ingredient i ON b.ingredient_id=i.id JOIN warehouse w ON b.warehouse_id=w.id LEFT JOIN supplier s ON b.supplier_id=s.id ORDER BY b.expiry_date")),"suppliers",up(db.queryForList("SELECT * FROM supplier")),"requisitions",up(db.queryForList("SELECT r.*,s.name store_name,u.name creator FROM requisition r JOIN store s ON r.store_id=s.id JOIN app_user u ON r.created_by=u.id ORDER BY r.created_at DESC")),"movements",up(db.queryForList("SELECT m.*,i.name ingredient_name,b.batch_no FROM movement m JOIN stock_batch b ON m.batch_id=b.id JOIN ingredient i ON b.ingredient_id=i.id ORDER BY m.created_at DESC LIMIT 30")));
    }
    private Map<String,Object> stats(){var r=db.queryForMap("SELECT COUNT(DISTINCT i.id) ingredient_count,COALESCE(SUM(b.quantity*b.unit_cost),0) stock_value,COUNT(DISTINCT CASE WHEN b.expiry_date<=DATEADD('DAY',3,CURRENT_DATE) THEN b.id END) expiring_count,COUNT(DISTINCT CASE WHEN b.quantity<i.min_stock THEN b.id END) low_count FROM ingredient i LEFT JOIN stock_batch b ON b.ingredient_id=i.id"); return up(r);}
    private Map<String,Object> up(Map<String,Object> m){var o=new LinkedHashMap<String,Object>();m.forEach((k,v)->o.put(k.toUpperCase(Locale.ROOT),v));return o;}
    private List<Map<String,Object>> up(List<Map<String,Object>> l){return l.stream().map(this::up).toList();}
    @PostMapping("/action/{action}") public Map<String,Object> action(@PathVariable String action,@RequestBody Map<String,Object> b,HttpServletRequest req){long uid=((Number)req.getAttribute("uid")).longValue();
        if("create-requisition".equals(action)){String no="LL-"+System.currentTimeMillis();db.update("INSERT INTO requisition(number,store_id,warehouse_id,status,note,created_by) VALUES(?,?,?,?,?,?)",no,b.get("storeId"),b.get("warehouseId"),"PENDING",b.getOrDefault("note",""),uid);return Map.of("number",no);}
        if("approve-requisition".equals(action)){db.update("UPDATE requisition SET status='APPROVED' WHERE id=?",b.get("id"));return Map.of("ok",true);}
        if("complete-requisition".equals(action)){db.update("UPDATE requisition SET status='COMPLETED' WHERE id=?",b.get("id"));return Map.of("ok",true);}
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"未知操作");
    }
    @GetMapping(value="/export/inventory",produces="text/csv;charset=UTF-8") public String export(){StringBuilder s=new StringBuilder("食材,仓库,批次,数量,单位成本,到期日\n"); for(var r:db.queryForList("SELECT i.name ingredient_name,w.name warehouse_name,b.batch_no,b.quantity,b.unit_cost,b.expiry_date FROM stock_batch b JOIN ingredient i ON b.ingredient_id=i.id JOIN warehouse w ON b.warehouse_id=w.id")) s.append(String.join(",",r.values().stream().map(String::valueOf).toList())).append("\n");return s.toString();}
    private Map<String,Object> current(HttpServletRequest req){return up(safe(db.queryForMap("SELECT u.id,u.username,u.name,r.name role_name,r.code role_code,r.permissions FROM app_user u JOIN app_role r ON u.role_id=r.id WHERE u.id=?",req.getAttribute("uid"))));}
    private Map<String,Object> safe(Map<String,Object> r){var m=new HashMap<>(r);m.remove("PASSWORD_HASH");m.remove("password_hash");return m;}
}
