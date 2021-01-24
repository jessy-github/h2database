/*
 * Copyright 2004-2021 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.table;

import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import org.h2.api.ErrorCode;
import org.h2.command.Prepared;
import org.h2.engine.SessionLocal;
import org.h2.engine.UndoLogRecord;
import org.h2.index.Index;
import org.h2.index.IndexType;
import org.h2.index.LinkedIndex;
import org.h2.jdbc.JdbcConnection;
import org.h2.message.DbException;
import org.h2.result.Row;
import org.h2.result.RowList;
import org.h2.schema.Schema;
import org.h2.util.JdbcUtils;
import org.h2.util.StringUtils;
import org.h2.util.Utils;
import org.h2.value.DataType;
import org.h2.value.TypeInfo;
import org.h2.value.Value;
import org.h2.value.ValueDate;
import org.h2.value.ValueTime;
import org.h2.value.ValueTimestamp;

/**
 * A linked table contains connection information for a table accessible by
 * JDBC. The table may be stored in a different database.
 */
public class TableLink extends Table {

    private static final int MAX_RETRY = 2;

    private static final long ROW_COUNT_APPROXIMATION = 100_000;

    private final String originalSchema;
    private String driver, url, user, password, originalTable, qualifiedTableName;
    private TableLinkConnection conn;
    private HashMap<String, PreparedStatement> preparedMap = new HashMap<>();
    private final ArrayList<Index> indexes = Utils.newSmallArrayList();
    private final boolean emitUpdates;
    private LinkedIndex linkedIndex;
    private DbException connectException;
    private boolean storesLowerCase;
    private boolean storesMixedCase;
    private boolean storesMixedCaseQuoted;
    private boolean supportsMixedCaseIdentifiers;
    private boolean globalTemporary;
    private boolean readOnly;
    private boolean targetsMySql;
    private int fetchSize = 0;

    public TableLink(Schema schema, int id, String name, String driver,
            String url, String user, String password, String originalSchema,
            String originalTable, boolean emitUpdates, boolean force) {
        super(schema, id, name, false, true);
        this.driver = driver;
        this.url = url;
        this.user = user;
        this.password = password;
        this.originalSchema = originalSchema;
        this.originalTable = originalTable;
        this.emitUpdates = emitUpdates;
        this.targetsMySql = isMySqlUrl(this.url);
        try {
            connect();
        } catch (DbException e) {
            if (!force) {
                throw e;
            }
            Column[] cols = { };
            setColumns(cols);
            linkedIndex = new LinkedIndex(this, id, IndexColumn.wrap(cols),
                    IndexType.createNonUnique(false));
            indexes.add(linkedIndex);
        }
    }

    private void connect() {
        connectException = null;
        for (int retry = 0;; retry++) {
            try {
                conn = database.getLinkConnection(driver, url, user, password);
                synchronized (conn) {
                    try {
                        readMetaData();
                        return;
                    } catch (Exception e) {
                        // could be SQLException or RuntimeException
                        conn.close(true);
                        conn = null;
                        throw DbException.convert(e);
                    }
                }
            } catch (DbException e) {
                if (retry >= MAX_RETRY) {
                    connectException = e;
                    throw e;
                }
            }
        }
    }

    private void readMetaData() throws SQLException {
        DatabaseMetaData meta = conn.getConnection().getMetaData();
        storesLowerCase = meta.storesLowerCaseIdentifiers();
        storesMixedCase = meta.storesMixedCaseIdentifiers();
        storesMixedCaseQuoted = meta.storesMixedCaseQuotedIdentifiers();
        supportsMixedCaseIdentifiers = meta.supportsMixedCaseIdentifiers();
        ArrayList<Column> columnList = Utils.newSmallArrayList();
        HashMap<String, Column> columnMap = new HashMap<>();
        String schema = null;
        boolean isQuery = originalTable.startsWith("(");
        if (!isQuery) {
            try (ResultSet rs = meta.getTables(null, originalSchema, originalTable, null)) {
                if (rs.next() && rs.next()) {
                    throw DbException.get(ErrorCode.SCHEMA_NAME_MUST_MATCH, originalTable);
                }
            }
            try (ResultSet rs = meta.getColumns(null, originalSchema, originalTable, null)) {
                int i = 0;
                String catalog = null;
                while (rs.next()) {
                    String thisCatalog = rs.getString("TABLE_CAT");
                    if (catalog == null) {
                        catalog = thisCatalog;
                    }
                    String thisSchema = rs.getString("TABLE_SCHEM");
                    if (schema == null) {
                        schema = thisSchema;
                    }
                    if (!Objects.equals(catalog, thisCatalog) ||
                            !Objects.equals(schema, thisSchema)) {
                        // if the table exists in multiple schemas or tables,
                        // use the alternative solution
                        columnMap.clear();
                        columnList.clear();
                        break;
                    }
                    String n = rs.getString("COLUMN_NAME");
                    n = convertColumnName(n);
                    int sqlType = rs.getInt("DATA_TYPE");
                    String sqlTypeName = rs.getString("TYPE_NAME");
                    long precision = rs.getInt("COLUMN_SIZE");
                    precision = convertPrecision(sqlType, precision);
                    int scale = rs.getInt("DECIMAL_DIGITS");
                    scale = convertScale(sqlType, scale);
                    int type = DataType.convertSQLTypeToValueType(sqlType, sqlTypeName);
                    Column col = new Column(n, TypeInfo.getTypeInfo(type, precision, scale, null), this, i++);
                    columnList.add(col);
                    columnMap.put(n, col);
                }
            }
        }
        if (originalTable.indexOf('.') < 0 && !StringUtils.isNullOrEmpty(schema)) {
            qualifiedTableName = schema + '.' + originalTable;
        } else {
            qualifiedTableName = originalTable;
        }
        // check if the table is accessible

        try (Statement stat = conn.getConnection().createStatement();
                ResultSet rs = stat.executeQuery("SELECT * FROM " + qualifiedTableName + " T WHERE 1=0")) {
            if (columnList.isEmpty()) {
                // alternative solution
                ResultSetMetaData rsMeta = rs.getMetaData();
                for (int i = 0; i < rsMeta.getColumnCount();) {
                    String n = rsMeta.getColumnName(i + 1);
                    n = convertColumnName(n);
                    int sqlType = rsMeta.getColumnType(i + 1);
                    long precision = rsMeta.getPrecision(i + 1);
                    precision = convertPrecision(sqlType, precision);
                    int scale = rsMeta.getScale(i + 1);
                    scale = convertScale(sqlType, scale);
                    int type = DataType.getValueTypeFromResultSet(rsMeta, i + 1);
                    Column col = new Column(n, TypeInfo.getTypeInfo(type, precision, scale, null), this, i++);
                    columnList.add(col);
                    columnMap.put(n, col);
                }
            }
        } catch (Exception e) {
            throw DbException.get(ErrorCode.TABLE_OR_VIEW_NOT_FOUND_1, e,
                    originalTable + '(' + e.toString() + ')');
        }
        Column[] cols = columnList.toArray(new Column[0]);
        setColumns(cols);
        int id = getId();
        linkedIndex = new LinkedIndex(this, id, IndexColumn.wrap(cols),
                IndexType.createNonUnique(false));
        indexes.add(linkedIndex);
        if (!isQuery) {
            readIndexes(meta, columnMap);
        }
    }

    private void readIndexes(DatabaseMetaData meta, HashMap<String, Column> columnMap) {
        String pkName = null;
        try (ResultSet rs = meta.getPrimaryKeys(null, originalSchema, originalTable)) {
            if (rs.next()) {
                pkName = readPrimaryKey(rs, columnMap);
            }
        } catch (Exception e) {
            // Some ODBC bridge drivers don't support it:
            // some combinations of "DataDirect SequeLink(R) for JDBC"
            // https://www.progress.com/odbc/sequelink
        }
        try (ResultSet rs = meta.getIndexInfo(null, originalSchema, originalTable, false, true)) {
            readIndexes(rs, columnMap, pkName);
        } catch (Exception e) {
            // Oracle throws an exception if the table is not found or is a
            // SYNONYM
        }
    }

    private String readPrimaryKey(ResultSet rs, HashMap<String, Column> columnMap) throws SQLException {
        String pkName = null;
        // the problem is, the rows are not sorted by KEY_SEQ
        ArrayList<Column> list = Utils.newSmallArrayList();
        do {
            int idx = rs.getInt("KEY_SEQ");
            if (StringUtils.isNullOrEmpty(pkName)) {
                pkName = rs.getString("PK_NAME");
            }
            while (list.size() < idx) {
                list.add(null);
            }
            String col = rs.getString("COLUMN_NAME");
            col = convertColumnName(col);
            Column column = columnMap.get(col);
            if (idx == 0) {
                // workaround for a bug in the SQLite JDBC driver
                list.add(column);
            } else {
                list.set(idx - 1, column);
            }
        } while (rs.next());
        addIndex(list, IndexType.createPrimaryKey(false, false));
        return pkName;
    }

    private void readIndexes(ResultSet rs, HashMap<String, Column> columnMap, String pkName) throws SQLException {
        String indexName = null;
        ArrayList<Column> list = Utils.newSmallArrayList();
        IndexType indexType = null;
        while (rs.next()) {
            if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
                // ignore index statistics
                continue;
            }
            String newIndex = rs.getString("INDEX_NAME");
            if (pkName != null && pkName.equals(newIndex)) {
                continue;
            }
            if (indexName != null && !indexName.equals(newIndex)) {
                addIndex(list, indexType);
                indexName = null;
            }
            if (indexName == null) {
                indexName = newIndex;
                list.clear();
            }
            boolean unique = !rs.getBoolean("NON_UNIQUE");
            indexType = unique ? IndexType.createUnique(false, false) :
                    IndexType.createNonUnique(false);
            String col = rs.getString("COLUMN_NAME");
            col = convertColumnName(col);
            Column column = columnMap.get(col);
            list.add(column);
        }
        if (indexName != null) {
            addIndex(list, indexType);
        }
    }

    private static long convertPrecision(int sqlType, long precision) {
        // workaround for an Oracle problem:
        // for DATE columns, the reported precision is 7
        // for DECIMAL columns, the reported precision is 0
        switch (sqlType) {
        case Types.DECIMAL:
        case Types.NUMERIC:
            if (precision == 0) {
                precision = 65535;
            }
            break;
        case Types.DATE:
            precision = Math.max(ValueDate.PRECISION, precision);
            break;
        case Types.TIMESTAMP:
            precision = Math.max(ValueTimestamp.MAXIMUM_PRECISION, precision);
            break;
        case Types.TIME:
            precision = Math.max(ValueTime.MAXIMUM_PRECISION, precision);
            break;
        }
        return precision;
    }

    private static int convertScale(int sqlType, int scale) {
        // workaround for an Oracle problem:
        // for DECIMAL columns, the reported precision is -127
        switch (sqlType) {
        case Types.DECIMAL:
        case Types.NUMERIC:
            if (scale < 0) {
                scale = 32767;
            }
            break;
        }
        return scale;
    }

    private String convertColumnName(String columnName) {
        if(targetsMySql) {
            // MySQL column names are not case-sensitive on any platform
            columnName = StringUtils.toUpperEnglish(columnName);
        } else if ((storesMixedCase || storesLowerCase) &&
                columnName.equals(StringUtils.toLowerEnglish(columnName))) {
            columnName = StringUtils.toUpperEnglish(columnName);
        } else if (storesMixedCase && !supportsMixedCaseIdentifiers) {
            // TeraData
            columnName = StringUtils.toUpperEnglish(columnName);
        } else if (storesMixedCase && storesMixedCaseQuoted) {
            // MS SQL Server (identifiers are case insensitive even if quoted)
            columnName = StringUtils.toUpperEnglish(columnName);
        }
        return columnName;
    }

    private void addIndex(List<Column> list, IndexType indexType) {
        // bind the index to the leading recognized columns in the index
        // (null columns might come from a function-based index)
        int firstNull = list.indexOf(null);
        if (firstNull == 0) {
            trace.info("Omitting linked index - no recognized columns.");
            return;
        } else if (firstNull > 0) {
            trace.info("Unrecognized columns in linked index. " +
                    "Registering the index against the leading {0} " +
                    "recognized columns of {1} total columns.", firstNull, list.size());
            list = list.subList(0, firstNull);
        }
        Column[] cols = list.toArray(new Column[0]);
        Index index = new LinkedIndex(this, 0, IndexColumn.wrap(cols), indexType);
        indexes.add(index);
    }

    @Override
    public String getDropSQL() {
        StringBuilder builder = new StringBuilder("DROP TABLE IF EXISTS ");
        return getSQL(builder, DEFAULT_SQL_FLAGS).toString();
    }

    @Override
    public String getCreateSQL() {
        StringBuilder buff = new StringBuilder("CREATE FORCE ");
        if (isTemporary()) {
            if (globalTemporary) {
                buff.append("GLOBAL ");
            } else {
                buff.append("LOCAL ");
            }
            buff.append("TEMPORARY ");
        }
        buff.append("LINKED TABLE ");
        getSQL(buff, DEFAULT_SQL_FLAGS);
        if (comment != null) {
            buff.append(" COMMENT ");
            StringUtils.quoteStringSQL(buff, comment);
        }
        buff.append('(');
        StringUtils.quoteStringSQL(buff, driver).append(", ");
        StringUtils.quoteStringSQL(buff, url).append(", ");
        StringUtils.quoteStringSQL(buff, user).append(", ");
        StringUtils.quoteStringSQL(buff, password).append(", ");
        StringUtils.quoteStringSQL(buff, originalTable).append(')');
        if (emitUpdates) {
            buff.append(" EMIT UPDATES");
        }
        if (readOnly) {
            buff.append(" READONLY");
        }
        if (fetchSize != 0) {
            buff.append(" FETCH_SIZE ").append(fetchSize);
        }
        buff.append(" /*").append(DbException.HIDE_SQL).append("*/");
        return buff.toString();
    }

    @Override
    public Index addIndex(SessionLocal session, String indexName, int indexId,
            IndexColumn[] cols, IndexType indexType, boolean create,
            String indexComment) {
        throw DbException.getUnsupportedException("LINK");
    }

    @Override
    public Index getScanIndex(SessionLocal session) {
        return linkedIndex;
    }

    @Override
    public boolean isInsertable() {
        return !readOnly;
    }

    private void checkReadOnly() {
        if (readOnly) {
            throw DbException.get(ErrorCode.DATABASE_IS_READ_ONLY);
        }
    }

    @Override
    public void removeRow(SessionLocal session, Row row) {
        checkReadOnly();
        getScanIndex(session).remove(session, row);
    }

    @Override
    public void addRow(SessionLocal session, Row row) {
        checkReadOnly();
        getScanIndex(session).add(session, row);
    }

    @Override
    public void close(SessionLocal session) {
        if (conn != null) {
            try {
                conn.close(false);
            } finally {
                conn = null;
            }
        }
    }

    @Override
    public synchronized long getRowCount(SessionLocal session) {
        //The foo alias is used to support the PostgreSQL syntax
        String sql = "SELECT COUNT(*) FROM " + qualifiedTableName + " as foo";
        try {
            PreparedStatement prep = execute(sql, null, false, session);
            ResultSet rs = prep.getResultSet();
            rs.next();
            long count = rs.getLong(1);
            rs.close();
            reusePreparedStatement(prep, sql);
            return count;
        } catch (Exception e) {
            throw wrapException(sql, e);
        }
    }

    /**
     * Wrap a SQL exception that occurred while accessing a linked table.
     *
     * @param sql the SQL statement
     * @param ex the exception from the remote database
     * @return the wrapped exception
     */
    public static DbException wrapException(String sql, Exception ex) {
        SQLException e = DbException.toSQLException(ex);
        return DbException.get(ErrorCode.ERROR_ACCESSING_LINKED_TABLE_2,
                e, sql, e.toString());
    }

    public String getQualifiedTable() {
        return qualifiedTableName;
    }

    /**
     * Execute a SQL statement using the given parameters. Prepared
     * statements are kept in a hash map to avoid re-creating them.
     *
     * @param sql the SQL statement
     * @param params the parameters or null
     * @param reusePrepared if the prepared statement can be re-used immediately
     * @param session the session
     * @return the prepared statement, or null if it is re-used
     */
    public PreparedStatement execute(String sql, ArrayList<Value> params, boolean reusePrepared, //
            SessionLocal session) {
        if (conn == null) {
            throw connectException;
        }
        for (int retry = 0;; retry++) {
            try {
                synchronized (conn) {
                    PreparedStatement prep = preparedMap.remove(sql);
                    if (prep == null) {
                        prep = conn.getConnection().prepareStatement(sql);
                        if (fetchSize != 0) {
                            prep.setFetchSize(fetchSize);
                        }
                    }
                    if (trace.isDebugEnabled()) {
                        StringBuilder builder = new StringBuilder(getName()).append(":\n").append(sql);
                        if (params != null && !params.isEmpty()) {
                            builder.append(" {");
                            for (int i = 0, l = params.size(); i < l;) {
                                Value v = params.get(i);
                                if (i > 0) {
                                    builder.append(", ");
                                }
                                builder.append(++i).append(": ");
                                v.getSQL(builder, DEFAULT_SQL_FLAGS);
                            }
                            builder.append('}');
                        }
                        builder.append(';');
                        trace.debug(builder.toString());
                    }
                    if (params != null) {
                        JdbcConnection ownConnection = session.createConnection(false);
                        for (int i = 0, size = params.size(); i < size; i++) {
                            Value v = params.get(i);
                            JdbcUtils.set(prep, i + 1, v, ownConnection);
                        }
                    }
                    prep.execute();
                    if (reusePrepared) {
                        reusePreparedStatement(prep, sql);
                        return null;
                    }
                    return prep;
                }
            } catch (SQLException e) {
                if (retry >= MAX_RETRY) {
                    throw DbException.convert(e);
                }
                conn.close(true);
                connect();
            }
        }
    }

    @Override
    public void checkSupportAlter() {
        throw DbException.getUnsupportedException("LINK");
    }

    @Override
    public long truncate(SessionLocal session) {
        throw DbException.getUnsupportedException("LINK");
    }

    @Override
    public boolean canGetRowCount(SessionLocal session) {
        return true;
    }

    @Override
    public boolean canDrop() {
        return true;
    }

    @Override
    public TableType getTableType() {
        return TableType.TABLE_LINK;
    }

    @Override
    public void removeChildrenAndResources(SessionLocal session) {
        super.removeChildrenAndResources(session);
        close(session);
        database.removeMeta(session, getId());
        driver = null;
        url = user = password = originalTable = null;
        preparedMap = null;
        invalidate();
    }

    public boolean isOracle() {
        return url.startsWith("jdbc:oracle:");
    }

    private static boolean isMySqlUrl(String url) {
        return url.startsWith("jdbc:mysql:")
                || url.startsWith("jdbc:mariadb:");
    }

    @Override
    public ArrayList<Index> getIndexes() {
        return indexes;
    }

    @Override
    public long getMaxDataModificationId() {
        // data may have been modified externally
        return Long.MAX_VALUE;
    }

    @Override
    public Index getUniqueIndex() {
        for (Index idx : indexes) {
            if (idx.getIndexType().isUnique()) {
                return idx;
            }
        }
        return null;
    }

    @Override
    public void updateRows(Prepared prepared, SessionLocal session, RowList rows) {
        checkReadOnly();
        if (emitUpdates) {
            for (rows.reset(); rows.hasNext();) {
                prepared.checkCanceled();
                Row oldRow = rows.next();
                Row newRow = rows.next();
                linkedIndex.update(oldRow, newRow, session);
                session.log(this, UndoLogRecord.DELETE, oldRow);
                session.log(this, UndoLogRecord.INSERT, newRow);
            }
        } else {
            super.updateRows(prepared, session, rows);
        }
    }

    public void setGlobalTemporary(boolean globalTemporary) {
        this.globalTemporary = globalTemporary;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    @Override
    public long getRowCountApproximation(SessionLocal session) {
        return ROW_COUNT_APPROXIMATION;
    }

    /**
     * Add this prepared statement to the list of cached statements.
     *
     * @param prep the prepared statement
     * @param sql the SQL statement
     */
    public void reusePreparedStatement(PreparedStatement prep, String sql) {
        synchronized (conn) {
            preparedMap.put(sql, prep);
        }
    }

    @Override
    public boolean isDeterministic() {
        return false;
    }

    /**
     * Linked tables don't know if they are readonly. This overwrites
     * the default handling.
     */
    @Override
    public void checkWritingAllowed() {
        // only the target database can verify this
    }

    @Override
    public void convertInsertRow(SessionLocal session, Row row, Boolean overridingSystem) {
        convertRow(session, row);
    }

    @Override
    public void convertUpdateRow(SessionLocal session, Row row) {
        convertRow(session, row);
    }

    private void convertRow(SessionLocal session, Row row) {
        for (int i = 0; i < columns.length; i++) {
            Value value = row.getValue(i);
            if (value != null) {
                // null means use the default value
                Column column = columns[i];
                Value v2 = column.validateConvertUpdateSequence(session, value, row);
                if (v2 != value) {
                    row.setValue(i, v2);
                }
            }
        }
    }

    /**
     * Specify the number of rows fetched by the linked table command
     *
     * @param fetchSize
     */
    public void setFetchSize(int fetchSize) {
        this.fetchSize = fetchSize;
    }

    /**
     * The number of rows to fetch
     * default is 0
     *
     * @return
     */
    public int getFetchSize() {
        return fetchSize;
    }

}
