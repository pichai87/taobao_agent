package com.ecom.infrastructure;

import com.ecom.domain.Lineage;
import com.ecom.domain.BusinessException;
import com.ecom.tools.SqlLineageAnalyzer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.core.io.ClassPathResource;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 使用实际 JDBC 元数据与服务端许可字段的交集，用户不能扩大 Schema 白名单。 */
@Service
public class JdbcLineageService implements Lineage.Service {
    private final DataSource source;
    private final SqlLineageAnalyzer analyzer=new SqlLineageAnalyzer();
    private static final Map<String,List<String>> ALLOWED=Map.of(
        "analytics_daily",List.of("stat_date","category","gmv","paid_orders","uv"),
        "biz_order",List.of("id","paid_date","category","paid_amount","status"),
        "traffic_daily",List.of("stat_date","category","uv"));
    public JdbcLineageService(@Qualifier("readerDataSource") DataSource source) {this.source=source;}
    public Map<String,List<String>> schema() {
        var result=new TreeMap<String,List<String>>();
        try(var connection=source.getConnection()) {
            for(var table:ALLOWED.entrySet()) {
                var actual=new HashSet<String>();
                try(var columns=connection.getMetaData().getColumns(connection.getCatalog(),null,table.getKey(),null)) {
                    while(columns.next()) if(table.getKey().equalsIgnoreCase(columns.getString("TABLE_NAME")))
                        actual.add(columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
                if(!actual.containsAll(table.getValue())) throw new BusinessException("LINEAGE_SCHEMA_DRIFT");
                result.put(table.getKey(),table.getValue());
            }
            return Collections.unmodifiableMap(result);
        } catch(BusinessException e) {throw e;}
        catch(Exception e) {throw new BusinessException("LINEAGE_SCHEMA_UNAVAILABLE");}
    }
    public Lineage.Result analyze(String sql) {return analyzer.analyze(sql,schema());}
    public Lineage.ModelSource model() {
        // 原始 SQL 来自版本化迁移，不允许用户上传/替换 ETL 路径或读取任意文件。
        try(var input=new ClassPathResource("db/migration/V4__complete_analytics_grain.sql").getInputStream()) {
            String migration=new String(input.readAllBytes(),StandardCharsets.UTF_8);
            String marker="CREATE OR REPLACE VIEW analytics_daily AS";
            int start=migration.indexOf(marker);
            if(start<0) throw new BusinessException("LINEAGE_SOURCE_UNAVAILABLE");
            String sql=migration.substring(start+marker.length()).trim();
            return new Lineage.ModelSource("analytics_daily","V4",sql,analyze(sql));
        } catch(BusinessException e) {throw e;}
        catch(Exception e) {throw new BusinessException("LINEAGE_SOURCE_UNAVAILABLE");}
    }
}
