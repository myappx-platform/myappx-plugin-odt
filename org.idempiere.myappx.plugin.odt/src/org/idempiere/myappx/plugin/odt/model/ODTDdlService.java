/**********************************************************************
 * Copyright (C) Contributors                                          *
 * Contributors: ken.longnan@gmail.com                               *
 **********************************************************************/

package org.idempiere.myappx.plugin.odt.model;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MColumn;
import org.compiere.model.MSysConfig;
import org.compiere.model.MTable;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Trx;
import org.compiere.util.Util;

import org.idempiere.myappx.plugin.odt.util.ODTLog;

/** Column DDL synchronization and deferred foreign keys for ODT post-install. */
public final class ODTDdlService
{
	private ODTDdlService()
	{
	}

	/** Queue a physical column for post-install synchronization. */
	public static void queueColumnSync(MColumn column, MTable table, LinkedHashSet<Integer> pendingColumnIds)
	{
		if (pendingColumnIds == null || column == null || table == null)
			return;
		if (table.isView() || column.isVirtualColumn() || column.getAD_Column_ID() <= 0)
			return;
		pendingColumnIds.add(column.getAD_Column_ID());
	}

	/**
	 * Synchronize pending columns with the database (ADD/MODIFY only, no FK).
	 * Same approach as 2Pack {@code ColumnElementHandler}, batched at end of ODT install.
	 */
	public static void synchronizeColumns(Properties ctx, LinkedHashSet<Integer> columnIds, CLogger log, String trxName)
	{
		if (columnIds == null || columnIds.isEmpty())
			return;

		List<MColumn> columns = resolveColumnsInSyncOrder(ctx, columnIds, trxName);
		if (columns.isEmpty())
			return;

		ODTLog.info(log, ODTLog.MOD_DDL, "Synchronize columns | " + ODTLog.kvs("count", columns.size()));

		Trx trx = openDdlTrx(trxName, log);
		String effectiveTrxName = trx.getTrxName();
		LinkedHashSet<Integer> tablesCreated = new LinkedHashSet<>();

		for (MColumn column : columns)
		{
			MTable table = MTable.get(ctx, column.getAD_Table_ID(), effectiveTrxName);
			if (table.isView() || column.isVirtualColumn())
				continue;

			try
			{
				if (syncColumnWithoutFK(ctx, table, column, tablesCreated, trx, log) < 0)
					throw new AdempiereException("Column sync failed for " + table.getTableName() + "." + column.getColumnName());
			}
			catch (SQLException e)
			{
				ODTLog.severe(log, ODTLog.MOD_DDL, "Column sync failed | "
						+ ODTLog.kvs("table", table.getTableName(), "column", column.getColumnName()), e);
				throw new AdempiereException("Column sync failed for " + table.getTableName() + "." + column.getColumnName(), e);
			}
		}

		finishDdlTrx(trx, trxName);
		ODTLog.info(log, ODTLog.MOD_DDL, "Column sync completed | " + ODTLog.kvs("count", columns.size()));
	}

	/** Create deferred foreign keys after columns and indexes exist (2Pack {@code processDeferFKElements}). */
	public static void deferForeignKeys(Properties ctx, LinkedHashSet<Integer> columnIds, CLogger log, String trxName)
	{
		if (columnIds == null || columnIds.isEmpty())
			return;

		List<MColumn> columns = resolveColumnsInSyncOrder(ctx, columnIds, trxName);
		if (columns.isEmpty())
			return;

		ODTLog.info(log, ODTLog.MOD_DDL, "Defer foreign keys | " + ODTLog.kvs("columnCount", columns.size()));

		Trx trx = openDdlTrx(trxName, log);
		String effectiveTrxName = trx.getTrxName();
		try
		{
			Connection conn = trx.getConnection();
			DatabaseMetaData md = conn.getMetaData();
			String catalog = DB.getDatabase().getCatalog();
			String schema = DB.getDatabase().getSchema();

			for (MColumn column : columns)
			{
				MTable table = MTable.get(ctx, column.getAD_Table_ID(), effectiveTrxName);
				if (table.isView() || column.isVirtualColumn())
					continue;

				String tableName = table.getTableName();
				String fkConstraintSql = MColumn.getForeignKeyConstraintSql(md, catalog, schema, tableName, table, column, false);
				if (Util.isEmpty(fkConstraintSql))
					continue;

				if (fkConstraintSql.toLowerCase().contains(" ad_sequence(ad_sequence_id)"))
					fkConstraintSql = fkConstraintSql + "; COMMIT";

				executeDdlStatements(fkConstraintSql, effectiveTrxName, trx, log,
						"FK " + table.getTableName() + "." + column.getColumnName());
			}
		}
		catch (Exception e)
		{
			ODTLog.severe(log, ODTLog.MOD_DDL, "deferForeignKeys failed", e);
			throw new AdempiereException("Defer foreign keys failed", e);
		}

		finishDdlTrx(trx, trxName);
		ODTLog.info(log, ODTLog.MOD_DDL, "Foreign keys deferred | " + ODTLog.kvs("columnCount", columns.size()));
	}

	static List<MColumn> resolveColumnsInSyncOrder(Properties ctx, LinkedHashSet<Integer> columnIds, String trxName)
	{
		List<MColumn> columns = new ArrayList<>();
		for (Integer columnId : columnIds)
		{
			if (columnId == null || columnId.intValue() <= 0)
				continue;
			MColumn column = new MColumn(ctx, columnId, trxName);
			if (column.get_ID() > 0)
				columns.add(column);
		}
		columns.sort((c1, c2) -> {
			int tableCmp = Integer.compare(c1.getAD_Table_ID(), c2.getAD_Table_ID());
			if (tableCmp != 0)
				return tableCmp;
			if (c1.isKey() != c2.isKey())
				return c1.isKey() ? -1 : 1;
			return Integer.compare(c1.getAD_Column_ID(), c2.getAD_Column_ID());
		});
		return columns;
	}

	static Trx openDdlTrx(String trxName, CLogger log)
	{
		Trx trx = trxName != null ? Trx.get(trxName, true) : Trx.get(Trx.createTrxName("ODTDDL"), true);
		if (MSysConfig.getBooleanValue(MSysConfig.TWOPACK_COMMIT_DDL, false))
		{
			if (!trx.commit())
				ODTLog.warning(log, ODTLog.MOD_DDL, "Commit before DDL failed");
		}
		return trx;
	}

	static void finishDdlTrx(Trx trx, String callerTrxName)
	{
		if (trx == null)
			return;
		if (MSysConfig.getBooleanValue(MSysConfig.TWOPACK_COMMIT_DDL, false))
		{
			try
			{
				trx.commit(true);
			}
			catch (SQLException e)
			{
				throw new AdempiereException("ODT: commit after DDL failed", e);
			}
		}
		if (callerTrxName == null)
			trx.close();
	}

	static int syncColumnWithoutFK(Properties ctx, MTable table, MColumn column,
			LinkedHashSet<Integer> tablesCreated, Trx trx, CLogger log) throws SQLException
	{
		String trxName = trx.getTrxName();
		Connection conn = trx.getConnection();
		DatabaseMetaData md = conn.getMetaData();
		String catalog = DB.getDatabase().getCatalog();
		String schema = DB.getDatabase().getSchema();
		String tableName = table.getTableName();
		String columnName = column.getColumnName();
		if (md.storesUpperCaseIdentifiers())
		{
			tableName = tableName.toUpperCase();
			columnName = columnName.toUpperCase();
		}
		else if (md.storesLowerCaseIdentifiers())
		{
			tableName = tableName.toLowerCase();
			columnName = columnName.toLowerCase();
		}

		String sql = null;
		ResultSet rst = null;
		ResultSet rsc = null;
		try
		{
			rst = md.getTables(catalog, schema, tableName, new String[] { "TABLE" });
			if (!rst.next())
			{
				if (!tablesCreated.contains(table.getAD_Table_ID()))
				{
					sql = table.getSQLCreate();
					tablesCreated.add(table.getAD_Table_ID());
				}
			}
			else if (!tablesCreated.contains(table.getAD_Table_ID()))
			{
				rsc = md.getColumns(catalog, schema, tableName, columnName);
				if (rsc.next())
				{
					boolean notNull = DatabaseMetaData.columnNoNulls == rsc.getInt("NULLABLE");
					sql = column.getSQLModify(table, column.isMandatory() != notNull);
					sql = adjustOracleLobModifySql(column, rsc, sql);
				}
				else
				{
					sql = column.getSQLAdd(table);
				}
			}
		}
		finally
		{
			DB.close(rsc);
			DB.close(rst);
		}

		if (Util.isEmpty(sql, true))
			return 0;

		ODTLog.info(log, ODTLog.MOD_DDL, "Execute column DDL | "
				+ ODTLog.kvs("label", table.getTableName() + "." + column.getColumnName(), "sql", sql));

		return executeDdlStatements(sql, trxName, trx, log,
				table.getTableName() + "." + column.getColumnName()) ? 1 : -1;
	}

	static String adjustOracleLobModifySql(MColumn column, ResultSet rsc, String sql) throws SQLException
	{
		if (!DB.isOracle() || Util.isEmpty(sql))
			return sql;
		int actualType = rsc.getInt("DATA_TYPE");
		if (actualType == Types.CLOB && sql.contains(" MODIFY " + column.getColumnName() + " CLOB"))
			return sql.replaceFirst(" MODIFY " + column.getColumnName() + " CLOB", " MODIFY " + column.getColumnName());
		if (actualType == Types.BLOB && sql.contains(" MODIFY " + column.getColumnName() + " BLOB"))
			return sql.replaceFirst(" MODIFY " + column.getColumnName() + " BLOB", " MODIFY " + column.getColumnName());
		return sql;
	}

	static boolean executeDdlStatements(String sql, String trxName, Trx trx, CLogger log, String label)
	{
		if (Util.isEmpty(sql))
			return true;

		if (sql.indexOf(DB.SQLSTATEMENT_SEPARATOR) == -1)
		{
			int ret = DB.executeUpdate(sql, false, trxName);
			if (ret == -1)
				throw new AdempiereException("DDL failed for " + label + ": " + sql);
			return true;
		}

		String[] statements = sql.split(DB.SQLSTATEMENT_SEPARATOR);
		for (String statement : statements)
		{
			if (Util.isEmpty(statement, true) || "null".equals(statement))
				continue;
			int ret = DB.executeUpdateEx(statement, trxName);
			if (ret == -1)
				throw new AdempiereException("DDL failed for " + label + ": " + statement);
		}
		return true;
	}
}
