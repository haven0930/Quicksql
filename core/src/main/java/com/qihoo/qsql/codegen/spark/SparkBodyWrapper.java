package com.qihoo.qsql.codegen.spark;

import com.qihoo.qsql.codegen.ClassBodyComposer;
import com.qihoo.qsql.codegen.IntegratedQueryWrapper;
import com.qihoo.qsql.plan.proc.LoadProcedure;
import com.qihoo.qsql.plan.proc.QueryProcedure;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * As a child of {@link IntegratedQueryWrapper}, {@link SparkBodyWrapper} implement mixed operations code generation for
 * Spark.
 */
public class SparkBodyWrapper extends IntegratedQueryWrapper {

    @Override
    public IntegratedQueryWrapper run(QueryProcedure plan) {
        plan.accept(new SparkProcedureVisitor(varId, composer));
        return this;
    }

    @Override
    public void interpretProcedure(QueryProcedure plan) {
        plan.accept(new SimpleSparkProcVisitor(varId, composer));
    }

    @Override
    public void importSpecificDependency() {
        String[] imports = {
            "import org.apache.spark.sql.SparkSession",
            "import com.qihoo.qsql.exec.Requirement",
            "import com.qihoo.qsql.exec.spark.SparkRequirement"
        };

        composer.handleComposition(ClassBodyComposer.CodeCategory.IMPORT, imports);
    }

    @Override
    public IntegratedQueryWrapper show() {
        composer.handleComposition(ClassBodyComposer.CodeCategory.SENTENCE,
            latestDeclaredVariable() + ".show();\n");
        return this;
    }

    @Override
    public IntegratedQueryWrapper writeAsTextFile(String path, String deliminator) {
        composer.handleComposition(ClassBodyComposer.CodeCategory.SENTENCE,
            latestDeclaredVariable() + ".toJavaRDD().saveAsTextFile(\"" + path + "\");");
        return this;
    }

    @Override
    public IntegratedQueryWrapper writeAsJsonFile(String path) {
        return this;
    }

    @Override
    public void createTempTable(String tableName) {
        composer.handleComposition(ClassBodyComposer.CodeCategory.SENTENCE,
            latestDeclaredVariable() + ".createOrReplaceTempView(\"" + tableName + "\");");
    }

    private class SimpleSparkProcVisitor extends SparkProcedureVisitor {

        SimpleSparkProcVisitor(AtomicInteger varId,
            ClassBodyComposer composer) {
            super(varId, composer);
        }

        @Override
        public void visit(LoadProcedure procedure) {
        }
    }
}
