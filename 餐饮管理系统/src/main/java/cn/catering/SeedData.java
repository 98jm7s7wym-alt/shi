package cn.catering;

import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SeedData implements CommandLineRunner {
    private final JdbcTemplate db;
    @Value("${app.demo-password}") private String password;
    public SeedData(JdbcTemplate db) { this.db = db; }
    public void run(String... args) {
        if (db.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class) > 0) return;
        db.update("INSERT INTO app_role VALUES(1,'系统管理员','ADMIN','system,base,purchase,stock,report,kitchen'),(2,'仓储采购员','WAREHOUSE','base,purchase,stock,report'),(3,'门店厨师','KITCHEN','kitchen')");
        db.update("INSERT INTO store VALUES(1,'人民路旗舰店','人民路88号','王经理','13800000001'),(2,'滨江店','滨江路18号','李经理','13800000002')");
        db.update("INSERT INTO warehouse VALUES(1,'中心食材仓',NULL,'物流园A区'),(2,'旗舰店厨房仓',1,'旗舰店后厨'),(3,'滨江店厨房仓',2,'滨江店后厨')");
        String p = WebConfig.hash(password);
        db.update("INSERT INTO app_user(username,password_hash,name,role_id,store_id) VALUES('admin',?,'系统管理员',1,NULL),('warehouse',?,'张仓管',2,NULL),('kitchen',?,'陈厨师',3,1)", p,p,p);
        db.update("INSERT INTO category VALUES(1,'蔬菜'),(2,'肉禽'),(3,'粮油'),(4,'调味品')");
        db.update("INSERT INTO supplier VALUES(1,'绿源生鲜供应链','刘先生','13900000001','城北批发市场'),(2,'鲜达肉禽配送','赵女士','13900000002','冷链物流园'),(3,'丰禾粮油商贸','孙先生','13900000003','粮油批发中心')");
        db.update("INSERT INTO ingredient VALUES(1,'西红柿',1,'一级鲜果','kg',6.50,7,30,200),(2,'鸡胸肉',2,'冷鲜去骨','kg',22,5,20,150),(3,'大米',3,'东北粳米','kg',5.80,180,80,500),(4,'食用油',3,'非转基因','L',12,365,15,100),(5,'鸡蛋',2,'新鲜鸡蛋','kg',9.80,30,20,120),(6,'生菜',1,'当日鲜菜','kg',4.50,3,15,80)");
        LocalDate d=LocalDate.now();
        Object[][] rows={{1,1,1,"TOMATO-001",60,6.5,2},{2,1,2,"CHICKEN-001",12,22,3},{3,1,3,"RICE-001",260,5.8,120},{4,1,3,"OIL-001",35,12,200},{5,1,2,"EGG-001",45,9.8,20},{6,1,1,"LETTUCE-001",8,4.5,-1},{1,2,1,"TOMATO-STORE",10,6.5,4}};
        for(Object[] r:rows) db.update("INSERT INTO stock_batch(ingredient_id,warehouse_id,supplier_id,batch_no,expiry_date,quantity,unit_cost) VALUES(?,?,?,?,?,?,?)",r[0],r[1],r[2],r[3],d.plusDays((int)r[6]),r[4],r[5]);
    }
}
