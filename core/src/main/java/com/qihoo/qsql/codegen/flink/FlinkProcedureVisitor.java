package com.qihoo.qsql.codegen.flink;

import com.qihoo.qsql.codegen.ClassBodyComposer;
import com.qihoo.qsql.codegen.QueryGenerator;
import com.qihoo.qsql.plan.ProcedureVisitor;
import com.qihoo.qsql.plan.proc.DirectQueryProcedure;
import com.qihoo.qsql.plan.proc.ExtractProcedure;
import com.qihoo.qsql.plan.proc.LoadProcedure;
import com.qihoo.qsql.plan.proc.QueryProcedure;
import com.qihoo.qsql.plan.proc.TransformProcedure;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * For traversing procedures to generate.
 */
public class FlinkProcedureVisitor extends ProcedureVisitor {

    private ClassBodyComposer composer;
    private AtomicInteger varId;
    private String variable;

    public FlinkProcedureVisitor(AtomicInteger varId, ClassBodyComposer composer) {
        this.composer = composer;
        this.varId = varId;
    }

    @Override
    public void visit(ExtractProcedure extractProcedure) {
        createVariableName();
        QueryGenerator builder = QueryGenerator.getQueryGenerator(
            extractProcedure, composer, variable, false);
        builder.execute();
        builder.saveToTempTable();
        visitNext(extractProcedure);
    }

    @Override
    public void visit(TransformProcedure transformProcedure) {
        composer.handleComposition(ClassBodyComposer.CodeCategory.SENTENCE,
            "Table table = tEnv.sqlQuery(\"" + transformProcedure.sql() + "\");");
        composer.handleComposition(ClassBodyComposer.CodeCategory.SENTENCE,
            "DataSet " + variable + " = tEnv.toDataSet(table, Row.class);");
        visitNext(transformProcedure);
    }

    @Override
    public void visit(LoadProcedure loadProcedure) {
        composer.handleComposition(ClassBodyComposer.CodeCategory.SENTENCE,
            variable + ".print();\n");
        visitNext(loadProcedure);
    }

    @Override
    public void visit(QueryProcedure queryProcedure) {
        visitNext(queryProcedure);
    }

    @Override
    public void visit(DirectQueryProcedure queryProcedure) {
        visitNext(queryProcedure);
    }

    protected void createVariableName() {
        this.variable = "$" + (varId.incrementAndGet());
    }
}
