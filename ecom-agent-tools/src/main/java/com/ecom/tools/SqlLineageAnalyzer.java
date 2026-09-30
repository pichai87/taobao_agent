package com.ecom.tools;

import com.ecom.domain.BusinessException;
import com.ecom.domain.Lineage.*;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.*;
import java.util.*;

/**
 * A bounded AST analyzer for SELECT-only SQL. Never connects to a database or executes SQL.
 * All physical tables/columns must exist in the caller's trusted schema registry.
 * Unsupported constructs fail closed instead of silently dropping dependencies.
 */
public final class SqlLineageAnalyzer {
    private static final int MAX_SQL = 32000, MAX_DEPTH = 16, MAX_NODES = 4096, MAX_COLUMNS = 256;
    private static final Set<String> AGGREGATES = Set.of("SUM", "COUNT", "AVG", "MIN", "MAX");
    private static final Set<String> FUNCTIONS = Set.of("COALESCE", "NULLIF", "ABS", "ROUND", "LOWER", "UPPER", "CONCAT");
    private static final Set<String> BINARY = Set.of("Addition", "Subtraction", "Multiplication", "Division", "IntegerDivision",
            "Modulo", "Concat", "EqualsTo", "NotEqualsTo", "GreaterThan", "GreaterThanEquals", "MinorThan",
            "MinorThanEquals", "AndExpression", "OrExpression", "XorExpression");

    public Result analyze(String sql, Map<String,List<String>> trustedSchema) {
        if (sql == null || sql.isBlank() || sql.length() > MAX_SQL) throw error("LINEAGE_INVALID_SQL");
        State state = new State(registry(trustedSchema));
        if (CCJSqlParserUtil.getNestingDepth(sql) > 32) throw error("LINEAGE_DEPTH_LIMIT");
        try {
            var statements = CCJSqlParserUtil.parseStatements(sql, parser -> parser.withTimeOut(2000));
            if (statements.size() != 1 || !(statements.get(0) instanceof Select select))
                throw error("LINEAGE_SELECT_ONLY");
            Relation result = select(select, Map.of(), Set.of(), 0, state);
            return new Result(result.columns(), sorted(result.tables()), result.predicates(), List.of(
                    "仅做 SQL 静态血缘分析，不执行 SQL；解析成功不代表获得执行权限。",
                    "支持受控 Schema 的 SELECT、JOIN ON、非递归 CTE、FROM 子查询、UNION 与已声明表达式子集。",
                    "COUNT(*) 记录行来源而不虚构字段来源；筛选与关联依赖在 predicates 中单独列出。",
                    "不支持递归 CTE、相关/标量子查询、窗口函数、NATURAL/USING JOIN、任意 UDF 或写入型 ETL。",
                    "这是本地 AST 实现，未连接 DataWorks、LightRAG 或淘宝内部 ETL；不推断跨文件上游。"));
        } catch (BusinessException expected) { throw expected; }
        catch (Exception invalid) { throw error("LINEAGE_PARSE_FAILED"); }
    }

    private Relation select(Select query, Map<String,Relation> outerCtes, Set<String> pendingCtes, int depth, State state) {
        state.visit(depth);
        if (query.getForMode() != null || query.getForUpdateTable() != null || query.getForClause() != null
                || query.getPivot() != null || query.getUnPivot() != null || query.getIsolation() != null)
            throw error("LINEAGE_UNSUPPORTED_SELECT");
        Map<String,Relation> ctes = new LinkedHashMap<>(outerCtes);
        Set<String> pending = new HashSet<>(pendingCtes);
        var with = query.getWithItemsList();
        if (with != null) {
            Set<String> localNames = new HashSet<>();
            for (WithItem<?> item : with) {
                if (item.isRecursive() || !(item.getParenthesedStatement() instanceof ParenthesedSelect))
                    throw error("LINEAGE_UNSUPPORTED_CTE");
                String name = identifier(item.getAliasName());
                if (!localNames.add(name)) throw error("LINEAGE_DUPLICATE_CTE");
                pending.add(name);
            }
            for (WithItem<?> item : with) {
                String name = identifier(item.getAliasName());
                Relation body = select(((ParenthesedSelect)item.getParenthesedStatement()).getSelect(), ctes, pending, depth + 1, state);
                if (item.getWithItemList() != null && !item.getWithItemList().isEmpty()) {
                    if (item.getWithItemList().size() != body.columns().size()) throw error("LINEAGE_CTE_ARITY");
                    List<ColumnLineage> renamed = new ArrayList<>();
                    for (int i = 0; i < body.columns().size(); i++) {
                        var expression = item.getWithItemList().get(i).getExpression();
                        if (!(expression instanceof Column column) || column.getTable() != null)
                            throw error("LINEAGE_UNSUPPORTED_CTE");
                        renamed.add(rename(body.columns().get(i), identifier(column.getColumnName())));
                    }
                    body = new Relation(unique(renamed), body.tables(), body.predicates());
                }
                ctes.put(name,body); pending.remove(name);
            }
        }
        if (query instanceof ParenthesedSelect parenthesed)
            return select(parenthesed.getSelect(),ctes,pending,depth+1,state);
        if (query instanceof SetOperationList union) return union(union,ctes,pending,depth,state);
        if (!(query instanceof PlainSelect plain)) throw error("LINEAGE_UNSUPPORTED_SELECT");
        rejectUnsupported(plain);
        Scope scope = new Scope();
        if (plain.getFromItem() != null) scope.add(from(plain.getFromItem(),ctes,pending,depth+1,state));
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                if (join.isNatural() || join.isApply() || join.isSemi() || join.isWindowJoin()
                        || (join.getUsingColumns() != null && !join.getUsingColumns().isEmpty()))
                    throw error("LINEAGE_UNSUPPORTED_JOIN");
                scope.add(from(join.getRightItem(),ctes,pending,depth+1,state));
                if (join.getOnExpressions() != null) for (Expression on : join.getOnExpressions())
                    scope.predicates.add(predicate("JOIN",on,scope,depth+1,state));
            }
        }
        if (plain.getWhere() != null) scope.predicates.add(predicate("WHERE",plain.getWhere(),scope,depth+1,state));
        if (plain.getGroupBy() != null) {
            var grouping = plain.getGroupBy();
            if (grouping.isMysqlWithRollup() || (grouping.getGroupingSets() != null && !grouping.getGroupingSets().isEmpty()))
                throw error("LINEAGE_UNSUPPORTED_GROUPING");
            if (grouping.getGroupByExpressions() != null) for (Object e : grouping.getGroupByExpressions())
                scope.predicates.add(predicate("GROUP_BY",(Expression)e,scope,depth+1,state));
        }
        if (plain.getHaving() != null) scope.predicates.add(predicate("HAVING",plain.getHaving(),scope,depth+1,state));
        List<ColumnLineage> outputs = new ArrayList<>();
        for (SelectItem<?> item : plain.getSelectItems()) {
            Expression expression = item.getExpression();
            if (expression instanceof AllColumns star) {
                if (item.getAlias() != null || star.getExceptColumns() != null || star.getReplaceExpressions() != null)
                    throw error("LINEAGE_UNSUPPORTED_STAR");
                List<Binding> bindings = star instanceof AllTableColumns qualified
                        ? List.of(scope.binding(identifier(qualified.getTable().getFullyQualifiedName())))
                        : scope.bindings;
                if (bindings.isEmpty()) throw error("LINEAGE_STAR_WITHOUT_SCHEMA");
                bindings.forEach(binding -> outputs.addAll(binding.relation().columns()));
            } else {
                String name;
                if (item.getAlias() != null) name = alias(item.getAlias());
                else if (expression instanceof Column column) name = identifier(column.getColumnName());
                else throw error("LINEAGE_EXPRESSION_ALIAS_REQUIRED");
                Dependency dependency = expression(expression,scope,depth+1,state);
                outputs.add(new ColumnLineage(name,expression.toString(),dependency.transformation(),
                        sources(dependency.columns()),sorted(dependency.rowTables())));
            }
            if (outputs.size() > MAX_COLUMNS) throw error("LINEAGE_COLUMN_LIMIT");
        }
        List<ColumnLineage> columns = unique(outputs);
        validateOrdering(query,scope,columns,depth,state);
        return new Relation(columns,Set.copyOf(scope.tables),List.copyOf(scope.predicates));
    }

    private Relation union(SetOperationList union, Map<String,Relation> ctes, Set<String> pending, int depth, State state) {
        if (union.getOperations() == null || union.getOperations().stream().anyMatch(op -> !(op instanceof UnionOp)))
            throw error("LINEAGE_UNSUPPORTED_SET_OPERATION");
        List<Relation> branches = new ArrayList<>();
        for (Select child : union.getSelects()) branches.add(select(child,ctes,pending,depth+1,state));
        if (branches.isEmpty()) throw error("LINEAGE_UNSUPPORTED_SET_OPERATION");
        int arity = branches.getFirst().columns().size();
        if (branches.stream().anyMatch(b -> b.columns().size() != arity)) throw error("LINEAGE_UNION_ARITY");
        List<ColumnLineage> merged = new ArrayList<>();
        Set<String> tables = new TreeSet<>();List<Predicate> predicates = new ArrayList<>();
        branches.forEach(b -> {tables.addAll(b.tables());predicates.addAll(b.predicates());});
        for (int i = 0; i < arity; i++) {
            Dependency dependency = Dependency.empty(Transformation.COMPUTED);
            List<String> expressions = new ArrayList<>();
            for (Relation branch : branches) {
                ColumnLineage column = branch.columns().get(i);
                dependency = dependency.plus(Dependency.of(column)); expressions.add(column.expression());
            }
            merged.add(new ColumnLineage(branches.getFirst().columns().get(i).output(),String.join(" UNION ",expressions),
                    dependency.transformation(),sources(dependency.columns()),sorted(dependency.rowTables())));
        }
        Relation result = new Relation(unique(merged),Set.copyOf(tables),List.copyOf(predicates));
        Scope scope = new Scope();scope.add(new Binding("union_result",result));
        validateOrdering(union,scope,result.columns(),depth,state);
        return result;
    }

    private Binding from(FromItem item, Map<String,Relation> ctes, Set<String> pending, int depth, State state) {
        state.visit(depth);
        if (item == null || item.getPivot() != null || item.getUnPivot() != null || item.getSampleClause() != null)
            throw error("LINEAGE_UNSUPPORTED_FROM");
        if (item instanceof Table table) {
            String name = identifier(table.getFullyQualifiedName());
            // A pending CTE shadows a physical table with the same name; never resolve recursive references as base tables.
            if (pending.contains(name)) throw error("LINEAGE_CTE_CYCLE_OR_FORWARD_REFERENCE");
            Relation relation = ctes.get(name);
            if (relation == null) {
                List<String> fields = state.schema.get(name);
                if (fields == null) throw error("LINEAGE_UNKNOWN_TABLE");
                List<ColumnLineage> columns = fields.stream().map(field -> new ColumnLineage(field,name+"."+field,
                        Transformation.DIRECT,List.of(new SourceColumn(name,field)),List.<String>of())).toList();
                relation = new Relation(columns,Set.of(name),List.of());
            }
            return new Binding(table.getAlias() == null ? name : alias(table.getAlias()),relation);
        }
        if (item instanceof ParenthesedSelect nested) {
            if (nested.getAlias() == null) throw error("LINEAGE_SUBQUERY_ALIAS_REQUIRED");
            return new Binding(alias(nested.getAlias()),select(nested.getSelect(),ctes,pending,depth+1,state));
        }
        throw error("LINEAGE_UNSUPPORTED_FROM");
    }

    private Dependency expression(Expression value, Scope scope, int depth, State state) {
        state.visit(depth);
        if (value == null) return Dependency.empty(Transformation.COMPUTED);
        if (value instanceof Column column) {
            if (column.getArrayConstructor() != null) throw error("LINEAGE_UNSUPPORTED_EXPRESSION");
            String name = identifier(column.getColumnName());
            if (column.getTable() != null && column.getTable().getName() != null)
                return Dependency.of(scope.binding(identifier(column.getTable().getFullyQualifiedName())).column(name));
            List<ColumnLineage> found = scope.bindings.stream().flatMap(b -> b.relation().columns().stream())
                    .filter(c -> c.output().equals(name)).toList();
            if (found.size() > 1) throw error("LINEAGE_AMBIGUOUS_COLUMN");
            if (found.isEmpty()) throw error("LINEAGE_UNKNOWN_COLUMN");
            return Dependency.of(found.getFirst());
        }
        if (value instanceof LongValue || value instanceof DoubleValue || value instanceof StringValue
                || value instanceof NullValue || value instanceof DateValue || value instanceof TimeValue
                || value instanceof TimestampValue || value instanceof JdbcParameter || value instanceof JdbcNamedParameter)
            return Dependency.empty(Transformation.COMPUTED);
        if (value instanceof BinaryExpression binary) {
            if (!BINARY.contains(binary.getClass().getSimpleName())) throw error("LINEAGE_UNSUPPORTED_EXPRESSION");
            return computed(expression(binary.getLeftExpression(),scope,depth+1,state)
                    .plus(expression(binary.getRightExpression(),scope,depth+1,state)));
        }
        if (value instanceof Function function) {
            String name = function.getName().toUpperCase(Locale.ROOT);
            if ((!AGGREGATES.contains(name) && !FUNCTIONS.contains(name)) || function.getNamedParameters() != null
                    || function.getAttribute() != null || function.getKeep() != null || function.getHavingClause() != null
                    || function.getLimit() != null || function.getOrderByElements() != null)
                throw error("LINEAGE_UNSUPPORTED_FUNCTION");
            Dependency result = Dependency.empty(AGGREGATES.contains(name) ? Transformation.AGGREGATED : Transformation.COMPUTED);
            if (function.isAllColumns() && !name.equals("COUNT")) throw error("LINEAGE_UNSUPPORTED_FUNCTION");
            if (function.getParameters() != null) for (Expression parameter : function.getParameters()) {
                if (parameter instanceof AllColumns) {
                    if (!name.equals("COUNT") || parameter instanceof AllTableColumns) throw error("LINEAGE_UNSUPPORTED_FUNCTION");
                } else result = result.plus(expression(parameter,scope,depth+1,state));
            }
            if (AGGREGATES.contains(name)) result = result.plus(new Dependency(Set.of(),Set.copyOf(scope.tables),Transformation.AGGREGATED));
            return result;
        }
        if (value instanceof ExpressionList<?> list) {
            Dependency result = Dependency.empty(Transformation.COMPUTED);
            for (Expression item : list) result = result.plus(expression(item,scope,depth+1,state));
            return result;
        }
        if (value instanceof CastExpression cast) return computed(expression(cast.getLeftExpression(),scope,depth+1,state));
        if (value instanceof SignedExpression signed) return computed(expression(signed.getExpression(),scope,depth+1,state));
        if (value instanceof NotExpression not) return computed(expression(not.getExpression(),scope,depth+1,state));
        if (value instanceof IsNullExpression isNull) return computed(expression(isNull.getLeftExpression(),scope,depth+1,state));
        if (value instanceof Between between) return computed(expression(between.getLeftExpression(),scope,depth+1,state)
                .plus(expression(between.getBetweenExpressionStart(),scope,depth+1,state))
                .plus(expression(between.getBetweenExpressionEnd(),scope,depth+1,state)));
        if (value instanceof InExpression in) {
            if (!(in.getRightExpression() instanceof ExpressionList<?>)) throw error("LINEAGE_UNSUPPORTED_SUBQUERY");
            return computed(expression(in.getLeftExpression(),scope,depth+1,state)
                    .plus(expression(in.getRightExpression(),scope,depth+1,state)));
        }
        if (value instanceof CaseExpression conditional) {
            Dependency result = expression(conditional.getSwitchExpression(),scope,depth+1,state)
                    .plus(expression(conditional.getElseExpression(),scope,depth+1,state));
            for (WhenClause clause : conditional.getWhenClauses()) result = result
                    .plus(expression(clause.getWhenExpression(),scope,depth+1,state))
                    .plus(expression(clause.getThenExpression(),scope,depth+1,state));
            return computed(result);
        }
        if (value instanceof Select) throw error("LINEAGE_UNSUPPORTED_SUBQUERY");
        throw error("LINEAGE_UNSUPPORTED_EXPRESSION");
    }

    private void validateOrdering(Select query,Scope scope,List<ColumnLineage> outputs,int depth,State state) {
        if (query.getOrderByElements() == null) return;
        for (OrderByElement order : query.getOrderByElements()) {
            Expression value = order.getExpression();
            if (value instanceof LongValue position) {
                if (position.getValue() < 1 || position.getValue() > outputs.size()) throw error("LINEAGE_INVALID_ORDER");
            } else if (value instanceof Column column && (column.getTable() == null || column.getTable().getName() == null)
                    && outputs.stream().anyMatch(c -> c.output().equals(identifier(column.getColumnName())))) {
                // ORDER BY may reference an output alias; this changes order, not value lineage.
            } else expression(value,scope,depth+1,state);
        }
    }

    private Predicate predicate(String kind,Expression value,Scope scope,int depth,State state) {
        return new Predicate(kind,value.toString(),sources(expression(value,scope,depth,state).columns()));
    }

    private static void rejectUnsupported(PlainSelect query) {
        if ((query.getIntoTables() != null && !query.getIntoTables().isEmpty()) || query.getIntoTempTable() != null)
            throw error("LINEAGE_SELECT_ONLY");
        if (query.getQualify() != null || query.getOracleHierarchical() != null || query.getPreferringClause() != null
                || query.getKsqlWindow() != null || query.getForXmlPath() != null || query.isEmitChanges()
                || (query.getLateralViews() != null && !query.getLateralViews().isEmpty())
                || (query.getWindowDefinitions() != null && !query.getWindowDefinitions().isEmpty())
                || query.getBigQuerySelectQualifier() != null || query.getSampleClause() != null
                || (query.getDistinct() != null && query.getDistinct().getOnSelectItems() != null))
            throw error("LINEAGE_UNSUPPORTED_SELECT");
    }

    private static Map<String,List<String>> registry(Map<String,List<String>> schema) {
        if (schema == null || schema.isEmpty() || schema.size() > 64) throw error("LINEAGE_INVALID_SCHEMA");
        Map<String,List<String>> result = new LinkedHashMap<>();
        for (var entry : schema.entrySet()) {
            String table = identifier(entry.getKey());
            if (entry.getValue() == null || entry.getValue().isEmpty() || entry.getValue().size() > MAX_COLUMNS)
                throw error("LINEAGE_INVALID_SCHEMA");
            List<String> columns = entry.getValue().stream().map(SqlLineageAnalyzer::identifier).toList();
            if (new HashSet<>(columns).size() != columns.size() || columns.stream().anyMatch(c -> c.contains("."))
                    || result.putIfAbsent(table,columns) != null) throw error("LINEAGE_INVALID_SCHEMA");
        }
        return Map.copyOf(result);
    }

    private static String identifier(String value) {
        if (value == null || value.length() > 256) throw error("LINEAGE_INVALID_IDENTIFIER");
        String normalized = value.replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*){0,2}")) throw error("LINEAGE_INVALID_IDENTIFIER");
        return normalized;
    }
    private static String alias(Alias alias) {
        if (alias.getAliasColumns() != null && !alias.getAliasColumns().isEmpty()) throw error("LINEAGE_UNSUPPORTED_ALIAS");
        String name = identifier(alias.getName());
        if (name.contains(".")) throw error("LINEAGE_INVALID_IDENTIFIER");
        return name;
    }
    private static ColumnLineage rename(ColumnLineage column,String name) {
        return new ColumnLineage(name,column.expression(),column.transformation(),column.sources(),column.rowSources());
    }
    private static List<ColumnLineage> unique(List<ColumnLineage> columns) {
        Set<String> names = new HashSet<>();
        for (ColumnLineage column : columns) if (!names.add(column.output())) throw error("LINEAGE_DUPLICATE_OUTPUT");
        return List.copyOf(columns);
    }
    private static Dependency computed(Dependency dependency) {
        return dependency.plus(Dependency.empty(Transformation.COMPUTED));
    }
    private static List<SourceColumn> sources(Set<SourceColumn> columns) {
        return columns.stream().sorted(Comparator.comparing(SourceColumn::table).thenComparing(SourceColumn::column)).toList();
    }
    private static List<String> sorted(Set<String> values) { return values.stream().sorted().toList(); }
    private static BusinessException error(String code) { return new BusinessException(code); }

    private record Relation(List<ColumnLineage> columns,Set<String> tables,List<Predicate> predicates) {}
    private record Binding(String qualifier,Relation relation) {
        ColumnLineage column(String name) {
            return relation.columns().stream().filter(c -> c.output().equals(name)).findFirst()
                    .orElseThrow(() -> error("LINEAGE_UNKNOWN_COLUMN"));
        }
    }
    private static final class Scope {
        final List<Binding> bindings = new ArrayList<>();
        final Set<String> tables = new TreeSet<>();
        final List<Predicate> predicates = new ArrayList<>();
        void add(Binding binding) {
            if (bindings.stream().anyMatch(b -> b.qualifier().equals(binding.qualifier()))) throw error("LINEAGE_DUPLICATE_ALIAS");
            bindings.add(binding); tables.addAll(binding.relation().tables()); predicates.addAll(binding.relation().predicates());
        }
        Binding binding(String qualifier) {
            return bindings.stream().filter(b -> b.qualifier().equals(qualifier)).findFirst()
                    .orElseThrow(() -> error("LINEAGE_UNKNOWN_QUALIFIER"));
        }
    }
    private record Dependency(Set<SourceColumn> columns,Set<String> rowTables,Transformation transformation) {
        static Dependency empty(Transformation transformation) { return new Dependency(Set.of(),Set.of(),transformation); }
        static Dependency of(ColumnLineage column) {
            return new Dependency(Set.copyOf(column.sources()),Set.copyOf(column.rowSources()),column.transformation());
        }
        Dependency plus(Dependency other) {
            Set<SourceColumn> merged = new HashSet<>(columns); merged.addAll(other.columns);
            Set<String> rows = new HashSet<>(rowTables); rows.addAll(other.rowTables);
            Transformation type = transformation.ordinal() > other.transformation.ordinal() ? transformation : other.transformation;
            return new Dependency(Set.copyOf(merged),Set.copyOf(rows),type);
        }
    }
    private static final class State {
        final Map<String,List<String>> schema;
        int nodes;
        State(Map<String,List<String>> schema) { this.schema=schema; }
        void visit(int depth) {
            if (depth > MAX_DEPTH) throw error("LINEAGE_DEPTH_LIMIT");
            if (++nodes > MAX_NODES) throw error("LINEAGE_NODE_LIMIT");
        }
    }
}
