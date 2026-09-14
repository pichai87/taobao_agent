package com.ecom.infrastructure;

import org.apache.ibatis.annotations.*;
import com.ecom.domain.Analysis.Metric;
import java.util.List;

/** MyBatis 示例：参数不会拼接到 SQL 文本里。 */
@Mapper
public interface MetricMapper {
    @Select("SELECT code,name,definition,formula,unit FROM semantic_metric ORDER BY code")
    @ConstructorArgs({
        @Arg(column="code",javaType=String.class), @Arg(column="name",javaType=String.class),
        @Arg(column="definition",javaType=String.class), @Arg(column="formula",javaType=String.class),
        @Arg(column="unit",javaType=String.class)
    })
    List<Metric> findAll();
}

