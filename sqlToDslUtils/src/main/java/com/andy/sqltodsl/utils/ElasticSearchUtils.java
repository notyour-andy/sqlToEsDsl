package com.andy.sqltodsl.utils;

import com.andy.sqltodsl.bean.enums.Operator;
import com.andy.sqltodsl.bean.factory.QueryBuilderFactory;
import com.andy.sqltodsl.bean.models.OrderColumnModel;
import com.andy.sqltodsl.bean.models.TreeNode;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;

import java.util.*;

/**
 * @author Andy
 * @date 2022-11-15
 */
public class ElasticSearchUtils {

    public static void main(String[] args) {
        String sql = "select * from text where id = 123 and time > 1 and time < 3 or state = 0 group by time, state, c limit 10";
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.query(parseQuerySqlToDsl(sql));
        //order
        List<OrderColumnModel> orderColumnList = SqlUtils.getOrderColumnList(sql);
        if (CollectionUtils.isNotEmpty(orderColumnList)){
            for (OrderColumnModel model : orderColumnList) {
                searchSourceBuilder.sort(model.getName(), SortOrder.fromString(model.getOrderType()));
            }
        }
        //limit
        Map<String, Object> argMap = SqlUtils.getLimitArgMap(sql);
        if (MapUtils.isNotEmpty(argMap)){
            searchSourceBuilder.from(MapUtils.getInteger(argMap, "from"));
            searchSourceBuilder.size(MapUtils.getInteger(argMap, "size"));
        }
        //group by
        List<String> fieldList = SqlUtils.getGroupByFieldList(sql);
        if (CollectionUtils.isNotEmpty(fieldList)){
            TermsAggregationBuilder last = null;
            TermsAggregationBuilder first = null;
            for (String field : fieldList) {
                TermsAggregationBuilder aggBuilder = AggregationBuilders.terms("groupBy" + field).field(field);
                if (!Objects.isNull(last)){
                    last.subAggregation(aggBuilder);
                }
                if (fieldList.indexOf(field) == 0){
                    first = aggBuilder;
                }
                last = aggBuilder;
            }
            searchSourceBuilder.aggregation(first);
        }
        System.out.println(searchSourceBuilder);
    }


    public static BoolQueryBuilder parseQuerySqlToDsl(String sql){
        String expr = SqlUtils.getWhereStatement(sql).replace(" ", "");
        List<String> condList = SqlUtils.parseQueryConditions(sql);
        List<Map<String, Object>> mapList = SqlUtils.parseQueryConditionsToMapList(sql);
        String pattern = CommonUtils.getPattens(expr, condList);
        TreeNode tree = CommonUtils.makeExprTree(CommonUtils.transInfixToSuffixExpr(pattern), mapList);
        return transTreeToDsl(tree, QueryBuilders.boolQuery(), tree.getValue(), new ArrayList<>());
    }


    private static BoolQueryBuilder transTreeToDsl(TreeNode treeNode, BoolQueryBuilder boolQueryBuilder, String operator, List<RangeQueryBuilder> rangeQueryList){
        if (treeNode != null){
            if (Objects.equals(treeNode.type, 1)){
                //????????????
                boolean isNew = isNew(operator, treeNode.value);
                if(!isNew) {
                    //???????????????
                    transTreeToDsl(treeNode.left, boolQueryBuilder, treeNode.value, rangeQueryList);
                    transTreeToDsl(treeNode.right, boolQueryBuilder, treeNode.value, rangeQueryList);
                }else{
                    //??????????????????????????????bool
                    BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
                    List<RangeQueryBuilder> rangeList = new ArrayList<>();
                    transTreeToDsl(treeNode.left, boolQuery, treeNode.value, rangeList);
                    transTreeToDsl(treeNode.right, boolQuery, treeNode.value, rangeList);
                    List<QueryBuilder> builderList = Objects.equals(operator, Operator.OR.getMark()) ? boolQueryBuilder.should() : boolQueryBuilder.must();
                    builderList.add(boolQuery);
                }
            }else{
                //???????????????
                List<QueryBuilder> builderList = Objects.equals(operator, Operator.OR.getMark()) ? boolQueryBuilder.should() : boolQueryBuilder.must();
                //??????
                int type = parseVal(treeNode, rangeQueryList);
                //????????????
                QueryBuilder queryBuilder = QueryBuilderFactory.generateQueryBuilder(treeNode, type, rangeQueryList);
                if (type != 3){
                    builderList.add(queryBuilder);
                }
            }
        }
        return boolQueryBuilder;
    }

    /**
    *??????rangeQueryBuilder??????
    *@author Andy
    *@date 2022/11/29
    */
    public static void setRangeQueryBuilder(RangeQueryBuilder builder, String operator, Object value){
        if (Objects.equals(operator, ">=")) {
            builder.gte(value);
        } else if (Objects.equals(operator, ">")) {
            builder.gt(value);
        } else if (Objects.equals(operator, "<=")) {
            builder.lte(value);
        } else if (Objects.equals(operator, "<")) {
            builder.lt(value);
        }
    }

    /**
    *?????????????????????0:?????? 1:?????????
    *@author Andy
    *@param treeNode ?????????
    *@date 2022/11/29
    */
    private static Integer parseVal(TreeNode treeNode, List<RangeQueryBuilder> rangeQueryList){
        if (Objects.equals(treeNode.getOperator(), "=")) {
            return treeNode.getValType();
        }else{
            //????????????
            Optional<RangeQueryBuilder> optional = rangeQueryList.stream().filter(ele -> Objects.equals(ele.fieldName(), treeNode.getField())).findAny();
            return optional.isPresent() ? 3 : 2;
        }
    }

    private static boolean isNew(String initOp, String currentOp){
        //?????????????????????null, ?????????????????????????????????????????????bool??????
        return !Objects.isNull(initOp) && !Objects.equals(initOp, currentOp);
    }
}
