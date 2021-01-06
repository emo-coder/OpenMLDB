package com._4paradigm.sql.stability;

import com._4paradigm.sql.BenchmarkConfig;
import com._4paradigm.sql.sdk.SqlExecutor;
import com._4paradigm.sql.tools.Relation;
import com._4paradigm.sql.tools.TableInfo;
import com._4paradigm.sql.tools.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.sql.Date;
import java.util.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FEQLFZPerf {
    private static Logger logger = LoggerFactory.getLogger(FEQLFZPerf.class);
    private SqlExecutor executor;
    private String db = "perf";
    private String pName = "pname" + System.currentTimeMillis();
    private String script;
    private String mainTable;
    private Map<String, TableInfo> tableMap;
    private List<Integer> commonColumnIndices;

    private ExecutorService putExecuteService;
    private ExecutorService queryExecuteService;
    private Random random = new Random();

    public FEQLFZPerf() {
        executor = BenchmarkConfig.GetSqlExecutor(false);
        db = db + System.currentTimeMillis();
        commonColumnIndices = new ArrayList<>();
        init();
    }

    public void init() {
        String rawScript = Util.getContent(BenchmarkConfig.scriptUrl);
        script = rawScript.trim().replace("\n", " ");
        Relation relation = new Relation(Util.getContent(BenchmarkConfig.relationUrl));
        mainTable = relation.getMainTable();
        tableMap = Util.parseDDL(BenchmarkConfig.ddlUrl, relation);
        if (!BenchmarkConfig.commonCol.isEmpty()) {
            String[] colArr = BenchmarkConfig.commonCol.trim().split(",");
            for (String col : colArr) {
                commonColumnIndices.add(tableMap.get(mainTable).getSchemaPos().get(col));
            }
        }
    }

    public boolean create() {
        if (!executor.createDB(db)) {
            logger.warn("create db " + db + "failed");
            return false;
        }
        logger.info("create db " + db);
        for (TableInfo table : tableMap.values()) {
            if (!executor.executeDDL(db, table.getDDL())) {
                logger.info("create table " + table.getName() + " failed");
                return false;
            }
            logger.info("create table " + table.getName());
        }
        String procedureDDL = Util.getCreateProcedureDDL(pName, tableMap.get(mainTable), script);
        logger.info(procedureDDL);
        if (!executor.executeDDL(db, procedureDDL)) {
            logger.warn("create PROCEDURE error");
            return false;
        }
        return true;
    }

    public void clear() {
        for (String name : tableMap.keySet()) {
            executor.executeDDL(db, "drop table " + name + ";");
            logger.info("drop table " + name);
        }
        executor.dropDB(db);
        logger.info("drop db " + db);
    }

    private void putTableData(TableInfo table) {
        List<String> schema = table.getSchema();
        Set<Integer> index = table.getIndex();
        Set<Integer> tsIndex = table.getTsIndex();

        long ts = System.currentTimeMillis();
        int curNum = random.nextInt(BenchmarkConfig.PK_NUM);
        StringBuilder builder = new StringBuilder();
        builder.append("insert into ");
        builder.append(table.getName());
        builder.append(" values(");
        for (int pos = 0; pos < schema.size(); pos++) {
            if (pos > 0) {
                builder.append(", ");
            }
            String type = schema.get(pos);
            if (type.equals("string")) {
                builder.append("'");
                builder.append("col");
                builder.append(pos);
                builder.append("-");
                builder.append(curNum);
                builder.append("'");
            } else if (type.equals("float")) {
                builder.append(1.3f);
            } else if (type.equals("double")) {
                builder.append(1.4d);
            } else if (type.equals("bigint") || type.equals("int")) {
                if (tsIndex.contains(pos)) {
                    builder.append(ts);
                }  else {
                    builder.append(curNum);
                }
            } else if (type.equals("timestamp")) {
                if (tsIndex.contains(pos)) {
                    builder.append(ts);
                } else {
                    builder.append(curNum);
                }

            } else if (type.equals("bool")) {
                builder.append(true);
            } else if (type.equals("date")) {
                builder.append("'2020-11-27'");
            } else {
                logger.warn("invalid type");
            }
        }
        builder.append(");");
        String exeSql = builder.toString();
        executor.executeInsert(db, exeSql);
    }

    private boolean setRequestData(PreparedStatement requestPs) {
        try {
            ResultSetMetaData metaData = requestPs.getMetaData();
            TableInfo table = tableMap.get(mainTable);
            if (table.getSchema().size() != metaData.getColumnCount()) {
                return false;
            }
            int curNum = random.nextInt(BenchmarkConfig.PK_NUM);
            long ts = System.currentTimeMillis();
            for (int i = 0; i < metaData.getColumnCount(); i++) {
                int columnType = metaData.getColumnType(i + 1);
                if (columnType == Types.VARCHAR) {
                    requestPs.setString(i + 1, "col" + String.valueOf(i) + "-" + String.valueOf(curNum));
                } else if (columnType == Types.DOUBLE) {
                    requestPs.setDouble(i + 1, 1.4d);
                } else if (columnType == Types.FLOAT) {
                    requestPs.setFloat(i + 1, 1.3f);
                } else if (columnType == Types.INTEGER) {
                    requestPs.setInt(i + 1, curNum);
                } else if (columnType == Types.BIGINT) {
                    if (table.getTsIndex().contains(i)) {
                        requestPs.setLong(i + 1, ts);
                    } else {
                        requestPs.setLong(i + 1, curNum);
                    }
                } else if (columnType == Types.TIMESTAMP) {
                    if (table.getTsIndex().contains(i)) {
                        requestPs.setTimestamp(i + 1, new Timestamp(ts));
                    } else {
                        requestPs.setTimestamp(i + 1, new Timestamp(curNum));
                    }
                } else if (columnType == Types.DATE) {
                    requestPs.setDate(i + 1, new Date(System.currentTimeMillis()));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private PreparedStatement getPreparedStatement(BenchmarkConfig.Mode mode, boolean isProcedure) throws SQLException {
        PreparedStatement requestPs = null;
        if (mode == BenchmarkConfig.Mode.BATCH_REQUEST) {
            if (isProcedure) {
                requestPs = executor.getCallablePreparedStmtBatch(db, pName);
            } else {
                requestPs = executor.getBatchRequestPreparedStmt(db, script, commonColumnIndices);
            }
            for (int i = 0; i < BenchmarkConfig.BATCH_SIZE; i++) {
                if (setRequestData(requestPs)) {
                    requestPs.addBatch();
                }
            }
        } else {
            if (isProcedure) {
                requestPs = executor.getCallablePreparedStmt(db, pName);
            } else {
                requestPs = executor.getRequestPreparedStmt(db, script);
            }
            setRequestData(requestPs);
        }
        return requestPs;
    }

    public void putData() {
        while (true) {
            for (TableInfo table : tableMap.values()) {
                putTableData(table);
            }
        }
    }

    public void query() {
        Random curRandom = new Random();
        while (true) {
            BenchmarkConfig.Mode mode = BenchmarkConfig.Mode.REQUEST;
            if (curRandom.nextFloat() > BenchmarkConfig.REQUEST_RATIO) {
                mode = BenchmarkConfig.Mode.BATCH_REQUEST;
            }
            boolean isProcedure = false;
            if (curRandom.nextFloat() > BenchmarkConfig.PROCEDURE_RATIO) {
                isProcedure = true;
            }
            try {
                PreparedStatement ps = getPreparedStatement(mode, isProcedure);
                ResultSet resultSet = ps.executeQuery();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void runPut(int threadNum) {
        putExecuteService = Executors.newFixedThreadPool(threadNum);
        logger.info("put thread: " + threadNum);
        for (int i = 0; i < threadNum; i++) {
            putExecuteService.submit(new Runnable() {
                @Override
                public void run() {
                    putData();
                }
            });
        }
    }

    public void runQuery(int threadNum) {
        queryExecuteService = Executors.newFixedThreadPool(threadNum);
        logger.info("query thread: " + threadNum);
        for (int i = 0; i < threadNum; i++) {
            queryExecuteService.submit(new Runnable() {
                @Override
                public void run() {
                    query();
                }
            });
        }
    }

    public static void main() {
        FEQLFZPerf perf = new FEQLFZPerf();
        if (BenchmarkConfig.NEED_CREATE) {
            if (!perf.create()) {
                return;
            }
        }
        perf.runPut(BenchmarkConfig.PUT_THREAD_NUM);
        perf.runQuery(BenchmarkConfig.QUERY_THREAD_NUM);
        while (true) {
            try {
                Thread.sleep(10000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
