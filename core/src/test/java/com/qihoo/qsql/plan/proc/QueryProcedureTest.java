package com.qihoo.qsql.plan.proc;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.LineProcessor;
import com.google.common.io.Resources;
import com.qihoo.qsql.metadata.MetadataPostman;
import com.qihoo.qsql.plan.QueryProcedureProducer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

public class QueryProcedureTest {

    @ClassRule
    public static final EmbeddedElasticsearchPolicy NODE = EmbeddedElasticsearchPolicy.create();

    private static QueryProcedureProducer producer;

    /**
     * test.
     * @throws IOException io exception
     */

    @BeforeClass
    public static void setupInstance() throws IOException {
        final Map<String, String> mapping = ImmutableMap.of("stu_id", "long", "type", "long",
            "city", "keyword", "digest", "long", "province", "keyword");
        NODE.createIndex("student", mapping);

        // load records from file
        final List<ObjectNode> bulk = new ArrayList<>();
        Resources.readLines(QueryProcedureTest.class.getResource("/student.json"),
            StandardCharsets.UTF_8, new LineProcessor<Void>() {
                @Override public boolean processLine(String line) throws IOException {
                    line = line.replaceAll("_id", "id");
                    bulk.add((ObjectNode) NODE.mapper().readTree(line));
                    return true;
                }

                @Override public Void getResult() {
                    return null;
                }
            });

        if (bulk.isEmpty()) {
            throw new IllegalStateException("No records to index. Empty file ?");
        }

        NODE.insertBulk("student", bulk);

        List<String> tableNames = Arrays.asList(
            "edu_manage.department",
            "student_profile.student",
            "edu_manage.department_student_relation",
            "action_required.homework_content",
            "action_required.action_detection_in_class");
        producer = new QueryProcedureProducer(
            "inline: " + MetadataPostman.getCalciteModelSchema(tableNames));
    }

    @Test
    public void testOnlyValue() {
        String sql = "SELECT 'Hello World' AS col1, 1010 AS col2";
        prepareForChecking(sql).checkExtra("SELECT 'Hello World' AS col1, 1010 AS col2");
    }

    @Test
    public void testElasticsearchEmbeddedSource() {
        String sql = "SELECT city FROM student";
        prepareForChecking(sql).checkExtra("{\"_source\":[\"city\"]}");
    }

    @Test
    public void testScalarQueryWithGroupBy() {
        String sql =
            "SELECT * FROM department AS dep INNER JOIN edu_manage.department_student_relation rel "
                + "ON dep.dep_id = rel.stu_id "
                + "WHERE EXISTS (SELECT 1 FROM department GROUP BY type)";
        prepareForChecking(sql).checkExtra(
            "select department.dep_id, department.cycle, department.type, department.times,"
                + " department_student_relation.id, department_student_relation.dep_id as dep_id0,"
                + " department_student_relation.stu_id "
                + "from edu_manage.department inner join edu_manage.department_student_relation "
                + "on department.dep_id = department_student_relation.stu_id, "
                + "(select true as i from edu_manage.department group by true) as t2");
    }

    @Test
    public void testValueIn() {
        String sql = "SELECT UPPER('Time') NOT IN ('Time', 'New', 'Roman') AS res";
        prepareForChecking(sql)
            .checkExtra("SELECT UPPER('Time') NOT IN ('Time', 'New', 'Roman') AS res");
    }

    @Test
    public void testValueWithUselessTableScan() {
        String sql = "SELECT 1 IN (SELECT dep.times FROM edu_manage.department AS dep) AS res";
        prepareForChecking(sql).checkExtra("SELECT 1 IN "
            + "(SELECT dep.times FROM edu_manage.department AS dep) AS res");
    }

    @Test
    public void testFilterAnd() {
        String sql = "SELECT dep.type FROM edu_manage.department AS dep "
            + "WHERE dep.times BETWEEN 10 AND 20 AND (dep.type LIKE '%abc%')";
        prepareForChecking(sql).checkExtra(
            "select type from edu_manage.department where times >= 10 and times <= 20 and type like '%abc%'");
    }

    @Test
    public void testSelectWithoutFrom() {
        prepareForChecking("SELECT 1").checkExtra(
            "SELECT 1");
        prepareForChecking("SELECT 'hello' < Some('world', 'hi')").checkExtra(
            "SELECT 'hello' < Some('world', 'hi')");
    }

    @Test
    public void testSelectWithoutFromWithJoin() {
        String sql = "SELECT a.e1 FROM (SELECT 1 e1) as a join (SELECT 2 e2) as b ON (a.e1 = b.e2)";
        prepareForChecking(sql).checkExtra(
            "SELECT a.e1 FROM (SELECT 1 e1) as a join (SELECT 2 e2) as b ON (a.e1 = b.e2)");
    }

    @Test
    public void testSimpleArithmetic() {
        String sql = "SELECT ABS(-1) + FLOOR(1.23) % 1 AS res";
        prepareForChecking(sql).checkExtra("SELECT ABS(-1) + FLOOR(1.23) % 1 AS res");
    }

    @Test
    public void testFunctionLength() {
        //original function is length
        String sql = "SELECT ABS(CHAR_LENGTH('Hello World')) AS res";
        prepareForChecking(sql).checkExtra("SELECT ABS(CHAR_LENGTH('Hello World')) AS res");
    }

    @Test
    public void testFunctionConcat() {
        String sql = "SELECT SUBSTRING('Hello World', 0, 5) || SUBSTRING('Hello World', 5) AS res";
        prepareForChecking(sql)
            .checkExtra(
                "SELECT SUBSTRING('Hello World', 0, 5) || SUBSTRING('Hello World', 5) AS res");
    }

    @Test
    public void testTypeBoolean() {
        String sql = "SELECT TRUE, NOT TRUE, 1 IS NOT NULL";
        prepareForChecking(sql).checkExtra("SELECT TRUE, NOT TRUE, 1 IS NOT NULL");
    }

    @Test
    public void testComparison() {
        String sql = "SELECT (1 < 2 <> TRUE) AND TRUE AS res";
        prepareForChecking(sql).checkExtra("SELECT (1 < 2 <> TRUE) AND TRUE AS res");
    }

    @Test
    public void testValueWithIn() {
        String sql = "SELECT UPPER('Time') NOT IN ('Time', 'New', 'Roman') AS res";
        prepareForChecking(sql)
            .checkExtra("SELECT UPPER('Time') NOT IN ('Time', 'New', 'Roman') AS res");
    }

    @Test
    public void testSelectWithExistsScalarQuery() {
        String sql = "SELECT EXISTS (SELECT 1) "
            + "FROM edu_manage.department AS test "
            + "WHERE test.type IN ('male', 'female')";
        prepareForChecking(sql).checkExtra("select t1.i is not null as expr_col__0 "
            + "from (select * from edu_manage.department "
            + "where type = 'male' or type = 'female') as t left join "
            + "(select true as i from dual) as t1 on true");
    }

    @Test
    public void testSelectArithmetic() {
        //es not support arithmetic
        String sql = "SELECT (digest * 2 - 5) AS res \n"
            + "FROM student_profile.student LIMIT 10";
        try {
            prepareForChecking(sql).checkExtra("");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(true);
        }
    }

    //Bug
    @Test
    public void testAggregationsByElastic() {
        String sql = "SELECT MIN(digest), MAX(digest), type "
            + "FROM student_profile.student group by type order by "
            + "type limit 3";
        //prepareForChecking(sql).checkExtra("");
    }

    @Test
    public void testTreeDivision() {
        String sql = "SELECT type, times\n"
            + "FROM edu_manage.department\n"
            + "WHERE dep_id < SOME(\n"
            + "SELECT stu_id \n"
            + "FROM action_required.homework_content \n"
            + "ORDER BY stu_id LIMIT 100) AND times > ALL(10, 11, 12)";
        prepareForChecking(sql)
            .checkExtra("SELECT MAX(stu_id) m, COUNT(*) c,"
                    + " COUNT(stu_id) d FROM (SELECT stu_id FROM action_required.homework_content "
                    + "ORDER BY stu_id LIMIT 100) t1",
                "select * from edu_manage.department")
            .checkTrans("SELECT edu_manage_department_0.type,"
                + " edu_manage_department_0.times FROM edu_manage_department_0,"
                + " action_required_homework_content_1 WHERE CASE WHEN action_required_homework_content_1.c = 0 "
                + "THEN FALSE WHEN edu_manage_department_0.dep_id <"
                + " action_required_homework_content_1.m IS TRUE "
                + "THEN TRUE WHEN action_required_homework_content_1.c > action_required_homework_content_1.d "
                + "THEN NULL ELSE edu_manage_department_0.dep_id < action_required_homework_content_1.m END "
                + "AND edu_manage_department_0.times > 12")
            .checkArchitect("[E]->[E]->[T]->[L]");
    }

    @Test
    public void testGroupByWithHaving() {
        //es not support having
        String sql = "SELECT count(digest), city \n"
            + "FROM student_profile.student \n"
            + "GROUP BY (ip) "
            + "HAVING LEFT(ip, 3) LIKE '10%'\n"
            + "ORDER BY 1";
        //prepareForChecking(sql).checkExtra("");
    }

    @Test
    public void testComplexGroupByWithHaving() {
        String sql = "SELECT course_type \n"
            + "FROM action_required.homework_content AS test\n"
            + "WHERE date_time = '20180820'\n"
            + "GROUP BY course_type\n"
            + "HAVING ((COUNT(*) > 100) AND (1 = 2))\n"
            + "ORDER BY course_type";
        prepareForChecking(sql).checkExtra("SELECT course_type  "
            + "FROM action_required.homework_content AS test "
            + "WHERE date_time = '20180820' GROUP BY course_type "
            + "HAVING ((COUNT(*) > 100) AND (1 = 2)) ORDER BY course_type");
    }

    @Test
    public void testScalarSubQueryWithIn() {
        String sql = "SELECT test1.signature \n"
            + "FROM action_required.homework_content AS test1\n"
            + "LEFT OUTER JOIN action_required.homework_content AS test2\n"
            + "ON test1.content = test2.signature \n"
            + "WHERE test2.content IS NULL AND test1.date_time IN(SELECT '20180713')";
        prepareForChecking(sql)
            .checkExtra("SELECT homework_content.signature "
                + "FROM action_required.homework_content "
                + "LEFT JOIN action_required.homework_content homework_content0 "
                + "ON homework_content.content = homework_content0.signature "
                + "INNER JOIN (SELECT '20180713' expr_col__0 FROM DUAL) t0 "
                + "ON homework_content.date_time = t0.expr_col__0 WHERE homework_content0.content IS NULL");
    }

    @Test
    public void testComplexSingleValue() {
        String sql = "SELECT (SELECT (SELECT 1))";
        prepareForChecking(sql).checkExtra("SELECT (SELECT (SELECT 1))");
    }

    @Test
    public void testConcatValue() {
        String sql = "SELECT 'a' || 'b'";
        prepareForChecking(sql).checkExtra("SELECT 'a' || 'b'");
    }

    @Test
    public void testJoinConditionWithUsing() {
        String sql = "SELECT count(*)\n"
            + "FROM action_required.homework_content AS test1\n"
            + "LEFT OUTER JOIN action_required.homework_content AS test2 \n"
            + "USING(stu_id) \n"
            + "GROUP BY test1.date_time, test2.date_time\n"
            + "HAVING test1.date_time > '20180713' AND test2.date_time > '20180731'";
        prepareForChecking(sql).checkExtra("SELECT COUNT(*) expr_col__0 "
            + "FROM action_required.homework_content LEFT JOIN "
            + "action_required.homework_content homework_content0 "
            + "ON homework_content.stu_id = homework_content0.stu_id "
            + "GROUP BY homework_content.date_time, homework_content0.date_time "
            + "HAVING homework_content.date_time > '20180713' AND homework_content0.date_time >"
            + " '20180731'")
            .checkTrans().checkArchitect("[D]->[E]->[L]");
    }

    @Test
    public void testMixedSqlConcatAndSome() {
        String sql = "SELECT TRIM(es.city) || TRIM(msql.course_type)\n"
            + "FROM student_profile.student AS es\n"
            + "INNER JOIN\n"
            + "action_required.homework_content as msql\n"
            + "ON es.stu_id = msql.stu_id\n"
            + "WHERE es.digest > Some(\n"
            + "\tSELECT times FROM edu_manage.department)";
        prepareForChecking(sql)
            .checkExtra("SELECT * FROM action_required.homework_content",
                "select min(times) as m, count(*) as c, count(times) as d from edu_manage.department",
                "{\"_source\":[\"city\",\"province\",\"digest\",\"type\",\"stu_id\"]}")
            .checkTrans("SELECT TRIM(BOTH ' ' FROM student_profile_student_0.city) || "
                + "TRIM(BOTH ' ' FROM action_required_homework_content_1.course_type) expr_col__0 "
                + "FROM student_profile_student_0 INNER JOIN action_required_homework_content_1 "
                + "ON student_profile_student_0.stu_id = action_required_homework_content_1.stu_id, "
                + "edu_manage_department_2 "
                + "WHERE CASE WHEN edu_manage_department_2.c = 0 "
                + "THEN FALSE WHEN student_profile_student_0.digest > edu_manage_department_2.m IS TRUE "
                + "THEN TRUE WHEN edu_manage_department_2.c > edu_manage_department_2.d "
                + "THEN NULL ELSE student_profile_student_0.digest > edu_manage_department_2.m END");

    }

    @Test
    public void testMixedDataTimeAndReverse() {
        String sql = "SELECT * FROM\n"
            + "(SELECT signature AS reved, EXTRACT(MONTH FROM date_time) AS pmonth\n"
            + "\tFROM action_required.homework_content \n"
            + "\tORDER BY date_time) AS hve \n"
            + "\tFULL JOIN\n"
            + "(SELECT TRIM(type), '20180101' AS pday\n"
            + "\tFROM edu_manage.department) AS msql\n"
            + "\tON hve.pmonth = msql.pday";
        prepareForChecking(sql)
            .checkExtra("SELECT reved, pmonth FROM "
                    + "(SELECT signature reved, EXTRACT(MONTH FROM "
                    + "date_time) pmonth, date_time FROM action_required.homework_content "
                    + "ORDER BY date_time) t0",
                "select trim(both ' ' from type) as expr_col__0, '20180101' as pday from edu_manage.department")
            .checkTrans("SELECT * FROM action_required_homework_content_0 "
                + "FULL JOIN edu_manage_department_1 "
                + "ON action_required_homework_content_0.pmonth = edu_manage_department_1.pday")
            .checkArchitect("[E]->[E]->[T]->[L]");
    }

    @Test
    public void testMixedIfAndElasticsearchLike() {
        String sql = "SELECT (SELECT MAX(digest)\n"
            + "\tFROM student_profile.student \n"
            + "\tWHERE province = 'hunan'), \n"
            + "CASE hve.signature WHEN 'abc' THEN 'cde' \n"
            + "ELSE 'def' END, (CASE WHEN hve.date_time <> '20180820' "
            + "THEN 'Hello' ELSE 'WORLD' END) AS col\n"
            + "FROM action_required.homework_content AS hve\n"
            + "WHERE hve.date_time BETWEEN '20180810' AND '20180830'";
        prepareForChecking(sql)
            .checkExtra("SELECT * FROM action_required.homework_content "
                    + "WHERE date_time >= '20180810' AND date_time <= '20180830'",
                "{\"query\":{\"constant_score\":{\"filter\":{\"term\":{\"province\":\"hunan\"}}}},"
                    + "\"_source\":false,\"size\":0,"
                    + "\"aggregations\":{\"expr_col__0\":{\"max\":{\"field\":\"digest\"}}}}")
            .checkTrans("SELECT student_profile_student_1.expr_col__0, "
                + "CASE WHEN action_required_homework_content_0.signature = 'abc' "
                + "THEN 'cde' ELSE 'def' END expr_col__1, "
                + "CASE WHEN action_required_homework_content_0.date_time <> '20180820' "
                + "THEN 'Hello' ELSE 'WORLD' END col FROM action_required_homework_content_0 "
                + "LEFT JOIN student_profile_student_1 ON TRUE")
            .checkArchitect(("[E]->[E]->[T]->[L]"));
    }

    @Test
    public void testMixedGroupBy() {
        //not support concat
        String sql = "SELECT MOD(es1.age, 10) + FLOOR(es2.age)\n"
            + "FROM (SELECT ip, age, count(*)\n"
            + "\tFROM profile \n"
            + "\tGROUP BY ip, age \n"
            + "\tLIMIT 100) AS es1, (\n"
            + "\tSELECT ip, country, age\n"
            + "\tFROM profile as tmp \n"
            + "\tWHERE tmp.age > 10) AS es2 \n"
            + "\tWHERE es1.ip = es2.ip \n"
            + "\tAND es2.country IS NOT NULL";
    }


    @Test
    public void testElasticsearchGroupBy() {
        String sql = "select count(city), province from student_profile.student "
            + "group by province order by province limit 10";
        prepareForChecking(sql).checkExtra("{\"_source\":false,\"size\":0,"
            + "\"aggregations\":{\"g_province\":{\"terms\":{\"field\":\"province\",\"missing\":\"__MISSING__\","
            + "\"size\":10,\"order\":{\"_key\":\"asc\"}},"
            + "\"aggregations\":{\"expr_col__0\":{\"value_count\":{\"field\":\"_id\"}}}}}}")
            .checkTrans().checkArchitect("[D]->[E]->[L]");

    }

    @Test
    public void testMixedSqlComplexQuery() {
        //not support if
        String sql = "(SELECT msql.mids IN (SELECT 'hello' \n"
            + "\tFROM student_profile.student \n"
            + "\tWHERE type IN ('scholar', 'master')\n"
            + "\tAND city = 'here') AS encode_flag, \n"
            + " \ttrue AS action_flag\n"
            + "FROM mysql_daily AS msql)\n"
            + "UNION\n"
            + "(SELECT false, false \n"
            + "\tFROM hive_daily AS hve\n"
            + "\tGROUP BY pday\n"
            + "\tHAVING pday > 20180810)";

    }

    @Test
    public void testMixedSqlSubQuery() {
        String sql = "(SELECT COUNT(date_time), COUNT(signature) \n"
            + "\tFROM action_required.homework_content AS hive\n"
            + "WHERE date_time IN (SELECT type FROM student_profile.student) "
            + "\tGROUP BY date_time, signature)";
        prepareForChecking(sql).checkExtra("SELECT * FROM action_required.homework_content",
            "{\"_source\":[\"type\"]}")
            .checkTrans("SELECT COUNT(*) expr_col__0, COUNT(*) expr_col__1 "
                + "FROM action_required_homework_content_0 INNER JOIN student_profile_student_1 "
                + "ON action_required_homework_content_0.date_time = student_profile_student_1.type "
                + "GROUP BY action_required_homework_content_0.date_time, action_required_homework_content_0.signature")
            .checkArchitect("[E]->[E]->[T]->[L]");
    }

    @Test
    public void testElasticsearchParse() {
        String sql = "(SELECT province FROM student_profile.student where "
            + "type='100054') UNION (SELECT signature FROM "
            + "action_required.homework_content where course_type = "
            + "'english')";

        prepareForChecking(sql)
            .checkExtra(
                "SELECT signature FROM action_required.homework_content WHERE course_type = 'english'",
                "{\"query\":{\"constant_score\":{\"filter\":{\"term\":{\"type\":\"100054\"}}}}"
                    + ",\"_source\":[\"province\"]}")
            .checkTrans("SELECT * FROM student_profile_student_0 UNION "
                + "SELECT * FROM action_required_homework_content_1")
            .checkArchitect("[E]->[E]->[T]->[L]");
    }

    @Test
    public void testSimpleJoin() {
        String sql =
            "SELECT * FROM (SELECT stu_id, times FROM edu_manage.department AS dep "
                + "INNER JOIN edu_manage.department_student_relation AS relation "
                + "ON dep.dep_id = relation.dep_id) AS stu_times INNER JOIN "
                + "action_required.homework_content logparse ON stu_times.stu_id = logparse.stu_id "
                + "WHERE logparse.date_time = '20180901' LIMIT 100";

        prepareForChecking(sql)
            .checkExtra("SELECT * FROM action_required.homework_content",
                "select department_student_relation.stu_id, department.times "
                    + "from edu_manage.department inner join "
                    + "edu_manage.department_student_relation "
                    + "on department.dep_id = department_student_relation.dep_id")
            .checkTrans("SELECT * FROM edu_manage_department_0 "
                + "INNER JOIN action_required_homework_content_1 "
                + "ON edu_manage_department_0.stu_id = action_required_homework_content_1.stu_id "
                + "WHERE action_required_homework_content_1.date_time = '20180901' LIMIT 100")
            .checkArchitect("[E]->[E]->[T]->[L]");
    }

    private SqlHolder prepareForChecking(String sql) {
        return new SqlHolder(producer.createQueryProcedure(sql));
    }


    private SqlHolder mixed(String sql) {
        QueryProcedure procedure = producer.createQueryProcedure(sql);
        return new SqlHolder(procedure);
    }

    private class SqlHolder {

        QueryProcedure procedure;

        SqlHolder(QueryProcedure procedure) {
            this.procedure = procedure;
        }

        SqlHolder checkExtra(String... expected) {
            List<ExtractProcedure> extractors = new ArrayList<>();
            QueryProcedure current = procedure;
            //change a elegant mode to implement
            do {
                if (current instanceof ExtractProcedure) {
                    extractors.add((ExtractProcedure) current);
                }
                current = current.next();
            }
            while (current != null && current.hasNext());

            List<String> sentences = extractors.stream().map(ExtractProcedure::toRecognizedQuery)
                .sorted().collect(Collectors.toList());

            List<String> expectedSentences = Arrays.stream(expected).sorted()
                .collect(Collectors.toList());
            Assert.assertEquals(expectedSentences.size(), sentences.size());
            for (int i = 0; i < sentences.size(); i++) {
                Assert.assertEquals(expectedSentences.get(i), sentences.get(i));
            }

            return this;
        }

        SqlHolder checkTrans() {
            QueryProcedure current = procedure;
            while (current.hasNext()) {
                if (current instanceof TransformProcedure) {
                    Assert.assertTrue(false);
                }
                current = current.next();
            }
            return this;
        }

        SqlHolder checkTrans(String expected) {
            TransformProcedure transform = null;
            QueryProcedure current = procedure;

            do {
                if (current instanceof TransformProcedure) {
                    transform = ((TransformProcedure) current);
                    break;
                }
                current = current.next();
            }
            while (current != null && current.hasNext());

            Assert.assertTrue(transform != null);
            Assert.assertEquals(expected, transform.sql());
            return this;
        }

        void checkArchitect(String arch) {
            //String[] nodes = arch.split("\\s*->\\s*");
            List<QueryProcedure> procedures = new ArrayList<>();
            QueryProcedure current = procedure;
            procedures.add(current);

            do {
                procedures.add(current.next());
                current = current.next();
            }
            while (current != null && current.hasNext());

            String procAlias = procedures.stream().map(proc -> {
                if (proc instanceof DirectQueryProcedure) {
                    return "[D]";
                } else if (proc instanceof ExtractProcedure) {
                    return "[E]";
                } else if (proc instanceof TransformProcedure) {
                    return "[T]";
                } else if (proc instanceof LoadProcedure) {
                    return "[L]";
                } else {
                    return "[ERROR]";
                }
            }).reduce((left, right) -> left + "->" + right).orElse("");

            Assert.assertEquals(procAlias, arch);
        }
    }
}
